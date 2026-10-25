package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;

class StationEnergyBridges46Test {
  final StoryMechanisms46Test f = new StoryMechanisms46Test();
  @BeforeEach void prepare() throws Exception {
    f.prepare();
    JsonObject records;
    try (var reader = new InputStreamReader(getClass().getResourceAsStream("/fixtures/station-bridges-4.6.json"),StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    for (var e : records.entrySet()) if (!e.getKey().startsWith("ExcelOutput/")) f.records.add(e.getKey(),e.getValue());
    for (var raw : records.getAsJsonArray("ExcelOutput/MazeProp.json")) {
      var prop = ResourceLoader.getGson().fromJson(raw,PropExcel.class);
      GameData.getPropExcelMap().add(prop); prop.onLoad();
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/InteractConfig.json")) {
      var row = f.f.gson.fromJson(raw,InteractExcel.class);
      if (!f.oldInteractions.containsKey(row.getId())) f.oldInteractions.put(row.getId(),GameData.getInteractExcelMap().get(row.getId()));
      GameData.getInteractExcelMap().add(row);
    }
    for (int id : new int[]{100040114}) {
      var raw = records.get("Config/Level/Mission/1000401/Mission_"+id+".json");
      var task = f.f.gson.fromJson(raw,MissionTaskInfo.class);
      task.retainSourceDefinition(raw); GameplayDefinitions.subs.get(id).setTask(task);
    }
    f.ready(100040114);
  }
  @AfterEach void cleanup() { f.cleanup(); }
  void states(PropState controller, PropState orange, PropState blue) {
    assertEquals(controller,f.prop(4,300015).getState(),"First controller");
    assertEquals(controller,f.prop(4,300002).getState(),"Opposite controller must agree");
    assertEquals(orange,f.prop(4,300003).getState(),"Orange bridge");
    assertEquals(blue,f.prop(4,300004).getState(),"Blue bridge");
  }
  @Test void bothControlsAlternateTheBridgesAcrossReloadWithoutOpeningBoth() throws Exception {
    states(PropState.Closed,PropState.Open,PropState.Closed);
    assertEquals(Retcode.SUCC,f.use(4,300015,1010));
    states(PropState.Open,PropState.Closed,PropState.Open);
    assertTrue(MissionStateRecords.subCompleted(f.player,100040114));
    f.reload(); states(PropState.Open,PropState.Closed,PropState.Open);
    f.f.accepted(100040145);
    assertEquals(Retcode.SUCC,f.use(4,300002,1100));
    states(PropState.Closed,PropState.Open,PropState.Closed);
    assertTrue(MissionStateRecords.subCompleted(f.player,100040145));
    f.reload(); states(PropState.Closed,PropState.Open,PropState.Closed);
  }
  @Test void failedSaveRollsBackAllFourActorsAndTheirJournal() {
    String before=f.f.gson.toJson(f.player.getGameplay());
    f.player.failSave=true;
    assertEquals(Retcode.SYSTEM_BUSY,f.use(4,300015,1010));
    states(PropState.Closed,PropState.Open,PropState.Closed);
    assertEquals(before,f.f.gson.toJson(f.player.getGameplay()));
    f.player.failSave=false;
    assertEquals(Retcode.SUCC,f.use(4,300015,1010));
    states(PropState.Open,PropState.Closed,PropState.Open);
  }
  @Test void brokenReciprocalBindingCannotCompleteTheObjectiveOrMoveEitherBridge() {
    f.info(4,300002).getValueSource().getValues().removeIf(v->v.has("Key")&&v.get("Key").getAsString().equals("Controller02"));
    assertNotEquals(Retcode.SUCC,f.use(4,300015,1010));
    states(PropState.Closed,PropState.Open,PropState.Closed);
    assertFalse(MissionStateRecords.subCompleted(f.player,100040114));
  }

  @Test void directBridgeInteractionAndLegacyCommonConsoleFlagCannotOpenBothColors() {
    assertNotEquals(Retcode.SUCC,f.use(4,300004,1010));
    states(PropState.Closed,PropState.Open,PropState.Closed);
    f.info(4,300015).setCommonConsole(true);
    assertEquals(Retcode.SUCC,f.use(4,300015,1010));
    states(PropState.Open,PropState.Closed,PropState.Open);
  }

  @Test void allFourLinkedStatesSurviveTwoActualDatabaseLoads() throws Exception {
    assertEquals(Retcode.SUCC,f.use(4,300015,1010));
    var server=new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address=server.bind();
    try(var client=com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:"+address.getPort())) {
      var store=dev.morphia.Morphia.createDatastore(client,"station-energy");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(emu.lunarcore.game.player.Player.class,emu.lunarcore.game.quest.QuestState.class);
      var document=new emu.lunarcore.game.player.Player();
      MissionTrialProvisioning46Test.set(document,"uid",199985);
      MissionTrialProvisioning46Test.set(document,"gameplay",f.player.getGameplay()); store.save(document);
      for(int i=0;i<2;i++) {
        var loaded=java.util.Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        MissionTrialProvisioning46Test.set(f.player,"gameplay",loaded.getGameplay()); f.reload();
        states(PropState.Open,PropState.Closed,PropState.Open);
        assertTrue(MissionStateRecords.subCompleted(f.player,100040114)); store.save(loaded);
      }
    } finally { server.shutdownNow(); }
  }
}
