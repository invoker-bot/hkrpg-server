package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.server.packet.Retcode;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original storage-zone three consoles and four mutually exclusive energy bridges. */
class StationStorageEnergyBridges46Test {
  final StationEnergyBridges46Test setup = new StationEnergyBridges46Test();
  final StoryMechanisms46Test f = setup.f;

  @BeforeEach void prepare() throws Exception {
    setup.prepare();
    f.planeId = 20002;
    f.scene();
  }

  @AfterEach void cleanup() { setup.cleanup(); }

  void states(PropState controls, PropState orange, PropState blue) {
    for (int id : new int[] {300012, 300015, 300016})
      assertEquals(controls, f.prop(6, id).getState(), "controller " + id);
    for (int id : new int[] {300010, 300013})
      assertEquals(orange, f.prop(6, id).getState(), "orange bridge " + id);
    for (int id : new int[] {300011, 300014})
      assertEquals(blue, f.prop(6, id).getState(), "blue bridge " + id);
  }

  @Test void floorNormalizationKeepsAllThreeAuthoredConsoleLinks() {
    for (int id : new int[] {300012, 300015, 300016})
      assertNotNull(f.info(6, id).getValueSource(), "console " + id + " must retain its links");
  }

  @Test void consoleChangesAllFourBridgesAndAllThreeControlsTogether() throws Exception {
    states(PropState.Closed, PropState.Open, PropState.Closed);
    assertEquals(Retcode.SUCC, f.use(6, 300012, 1010));
    states(PropState.Open, PropState.Closed, PropState.Open);
    assertEquals(PropState.Closed, f.prop(6, 300006).getState(), "Unrelated bridge must not change");
    f.reload();
    states(PropState.Open, PropState.Closed, PropState.Open);
    assertEquals(Retcode.SUCC, f.use(6, 300015, 1100));
    states(PropState.Closed, PropState.Open, PropState.Closed);
    f.reload();
    states(PropState.Closed, PropState.Open, PropState.Closed);
  }

  @ParameterizedTest @ValueSource(ints = {300012, 300015, 300016})
  void everyConsoleCanToggleTheWholeNetwork(int console) throws Exception {
    assertEquals(Retcode.SUCC, f.use(6, console, 1010));
    states(PropState.Open, PropState.Closed, PropState.Open);
    assertEquals(Retcode.SUCC, f.use(6, console, 1100));
    states(PropState.Closed, PropState.Open, PropState.Closed);
    f.reload();
    states(PropState.Closed, PropState.Open, PropState.Closed);
  }

  @Test void failedSaveRollsBackAllSevenActorsAndTheirJournal() {
    String before = f.f.gson.toJson(f.player.getGameplay());
    f.player.failSave = true;
    f.player.packets.clear();
    assertEquals(Retcode.SYSTEM_BUSY, f.use(6, 300012, 1010));
    states(PropState.Closed, PropState.Open, PropState.Closed);
    assertEquals(before, f.f.gson.toJson(f.player.getGameplay()));
    assertTrue(f.player.packets.isEmpty());
  }

  @ParameterizedTest @ValueSource(strings = {"missing", "duplicate", "wrong-type", "wrong-other", "wrong-bridge"})
  void invalidBindingsCannotMoveAnyBridge(String variant) {
    var values = f.info(6, 300015).getValueSource().getValues();
    var controller = values.stream().filter(v -> v.get("Key").getAsString().equals("Controller02")).findFirst().orElseThrow();
    switch (variant) {
      case "missing" -> values.remove(controller);
      case "duplicate" -> values.add(controller.deepCopy());
      case "wrong-type" -> controller.addProperty("$type", "RPG.GameCore.SharedInt");
      case "wrong-other" -> controller.addProperty("Value", "6,300016");
      case "wrong-bridge" -> values.stream().filter(v -> v.get("Key").getAsString().equals("FlipBridge01"))
          .findFirst().orElseThrow().addProperty("Value", "6,300006");
    }
    var before = f.f.gson.toJson(f.player.getGameplay());
    assertNotEquals(Retcode.SUCC, f.use(6, 300012, 1010));
    states(PropState.Closed, PropState.Open, PropState.Closed);
    assertEquals(before, f.f.gson.toJson(f.player.getGameplay()));
  }

  @Test void directBridgeAndLegacyCommonFlagCannotMoveUnrelatedPlatforms() {
    assertNotEquals(Retcode.SUCC, f.use(6, 300011, 1010));
    f.info(6, 300012).setCommonConsole(true);
    assertEquals(Retcode.SUCC, f.use(6, 300012, 1010));
    states(PropState.Open, PropState.Closed, PropState.Open);
    assertEquals(PropState.Closed, f.prop(6, 300006).getState());
  }

  @Test void missingLinkedActorCannotPartiallyMoveTheNetwork() {
    f.player.scene.removeEntity(f.prop(6, 300014));
    String before = f.f.gson.toJson(f.player.getGameplay());
    assertNotEquals(Retcode.SUCC, f.use(6, 300012, 1010));
    assertEquals(PropState.Closed, f.prop(6, 300012).getState());
    assertEquals(PropState.Open, f.prop(6, 300010).getState());
    assertEquals(PropState.Closed, f.prop(6, 300011).getState());
    assertEquals(before, f.f.gson.toJson(f.player.getGameplay()));
  }

  @Test void oldIndividualControllerSnapshotDoesNotPretendTheBridgeMoved() throws Exception {
    var legacy = f.f.gson.fromJson(f.f.gson.toJson(f.info(6, 300012)), emu.lunarcore.data.config.PropInfo.class);
    legacy.setValueSource(null);
    var scene = f.player.getScene();
    var snapshot = new ScenePropState.Snapshot(20002, 20002001, scene.getDimensionId(), scene.getStoryLineId(),
        6, 300012, 100014, PropState.Open.getVal(), PropTimelines.source(legacy), "");
    f.player.getGameplay().scenePropStates(f.player.getGameplay().getScenePropStates().with(List.of(snapshot)));
    f.reload();
    states(PropState.Closed, PropState.Open, PropState.Closed);
    assertEquals(snapshot.getSource(), f.player.getGameplay().getScenePropStates().getRecords().get(snapshot.key()).getSource());
    assertEquals(Retcode.SUCC, f.use(6, 300012, 1010));
    f.reload();
    states(PropState.Open, PropState.Closed, PropState.Open);
  }

  @Test void bridgeStateSurvivesTwoH2DatabaseRestarts(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
      throws Exception {
    assertEquals(Retcode.SUCC, f.use(6, 300012, 1010));
    for (int cycle = 0; cycle < 3; cycle++) {
      var server = new de.bwaldvogel.mongo.MongoServer(
          new de.bwaldvogel.mongo.backend.h2.H2Backend(directory.resolve("player.mv").toString()));
      var address = server.bind();
      try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
        var codecs = org.bson.codecs.configuration.CodecRegistries.fromCodecs(
            new emu.lunarcore.database.codecs.IntSetCodec(), new emu.lunarcore.database.codecs.IntListCodec(),
            new emu.lunarcore.database.codecs.Int2IntMapCodec());
        var options = dev.morphia.mapping.MapperOptions.builder().storeEmpties(true).storeNulls(false)
            .codecProvider(codecs).build();
        var store = dev.morphia.Morphia.createDatastore(client, "storage_bridge", options);
        store.getMapper().mapPackage("org.hkrpg.server.gameplay");
        store.getMapper().map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class,
            emu.lunarcore.util.Position.class);
        if (cycle == 0) {
          var document = new emu.lunarcore.game.player.Player();
          MissionTrialProvisioning46Test.set(document, "uid", 19998532);
          MissionTrialProvisioning46Test.set(document, "gameplay", f.player.getGameplay());
          store.save(document);
        } else {
          var loaded = java.util.Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
          MissionTrialProvisioning46Test.set(f.player, "gameplay", loaded.getGameplay());
          f.scene();
          if (cycle == 1) {
            states(PropState.Open, PropState.Closed, PropState.Open);
            assertEquals(Retcode.SUCC, f.use(6, 300015, 1100));
            MissionTrialProvisioning46Test.set(loaded, "gameplay", f.player.getGameplay());
            store.save(loaded);
          } else {
            states(PropState.Closed, PropState.Open, PropState.Closed);
          }
        }
      } finally { server.shutdownNow(); }
    }
  }
}
