package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.trial.TrialAvatar;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real opening mission and plot actor rows; formal ownership and accepted dialogue are fixtures.
 */
class OpeningPlayer46Test {
  final MissionTrialProvisioning46Test base = new MissionTrialProvisioning46Test();
  @TempDir Path root;
  JsonObject records;
  NpcExcel oldNpc;

  @BeforeEach
  void prepare() throws Exception {
    base.root = root;
    base.prepare();
    base.accept(100010110, 1001006);
    base.player.getGameplay().getSubMissions().clear();
    records = base.fixture("opening-player-4.6.json").getAsJsonObject("records");
    for (var entry : records.entrySet()) {
      for (var raw : entry.getValue().getAsJsonArray()) {
        switch (entry.getKey()) {
          case "ExcelOutput/SpecialAvatar.json" ->
              GameData.getSpecialAvatarExcelMap()
                  .add(base.gson.fromJson(raw, SpecialAvatarExcel.class));
          case "ExcelOutput/AvatarConfigTrial.json" ->
              GameData.getTrialActorExcelMap()
                  .add(ResourceLoader.getGson().fromJson(raw, TrialActorExcel.class));
          case "ExcelOutput/AvatarPromotionConfigTrial.json" ->
              GameData.getTrialActorPromotionExcelMap()
                  .add(base.gson.fromJson(raw, TrialActorPromotionExcel.class));
          case "ExcelOutput/AvatarSkillTreeConfigTrial.json" -> {
            var row = base.gson.fromJson(raw, TrialActorSkillTreeExcel.class);
            GameData.getTrialActorSkillTreeExcelMap().add(row);
            row.onLoad();
          }
          case "ExcelOutput/ItemConfigEquipment.json" ->
              GameData.getItemExcelMap()
                  .add(ResourceLoader.getGson().fromJson(raw, ItemExcel.class));
          case "ExcelOutput/EquipmentConfig.json" -> {
            var row = base.gson.fromJson(raw, EquipmentExcel.class);
            GameData.getEquipExcelMap().add(row);
            row.onLoad();
          }
        }
      }
    }
    // ResourceLoader loads ItemConfig before EquipmentConfig; fixture records retain source order.
    GameData.getEquipExcelMap().get(20011).onLoad();
    var source = base.fixture("mission-trial-removal-4.6.json").getAsJsonObject("records");
    var main =
        base.gson.fromJson(
            source.getAsJsonArray("Config/Level/Mission/1000101/MissionInfo_1000101.json").get(0),
            MissionInfo.class);
    var partial =
        base.gson.fromJson(
            source
                .getAsJsonArray("Config/Level/Mission/1000101/MissionInfo_1000101.partial.json")
                .get(0),
            MissionInfo.class);
    GameData.getMissionInfos().put(1000101, main);
    GameplayDefinitions.subs.clear();
    for (var sub : partial.getSubMissionList()) {
      var path = "Config/Level/Mission/1000101/Mission_" + sub.getId() + ".json";
      if (records.has(path)) {
        var raw = records.getAsJsonArray(path).get(0);
        var task = base.gson.fromJson(raw, MissionTaskInfo.class);
        task.retainSourceDefinition(raw);
        sub.setTask(task);
      }
      GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    var group =
        ResourceLoader.getGson()
            .fromJson(
                source
                    .getAsJsonArray(
                        "Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001/LevelGroup_P20001_F20001001_G27.json")
                    .get(0),
                GroupInfo.class);
    group.setId(27);
    var floor =
        ResourceLoader.getGson()
            .fromJson(
                source
                    .getAsJsonArray("Config/LevelOutput/RuntimeFloor/P20001_F20001001.json")
                    .get(0),
                FloorInfo.class);
    floor.getGroups().put(27, group);
    MissionTrialProvisioning46Test.setField(
        base.player.getScene(), Scene.class, "floorInfo", floor);
    oldNpc = GameData.getNpcExcelMap().get(1006);
    GameData.getNpcExcelMap()
        .add(
            base.gson.fromJson(
                source.getAsJsonArray("ExcelOutput/NPCData.json").get(0), NpcExcel.class));
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getNpcExcelMap().values().removeIf(n -> n.getId() == 1006);
    if (oldNpc != null) GameData.getNpcExcelMap().add(oldNpc);
    base.restore();
  }

  void finishFixture(int id) {
    var state = new QuestState(id);
    state.advance(1, 1, 100);
    base.player.getGameplay().getSubMissions().put(id, state);
  }

  @Test
  void firstLoginUsesKafkaWithoutCompletingTheOpeningOrGrantingFormalActors() {
    base.player.getMissionManager().refresh();
    assertEquals(List.of(1001005), base.player.getCurrentLineup().getAvatars());
    var kafka = assertInstanceOf(TrialAvatar.class, base.player.getAvatarById(1001005));
    assertEquals(7005, kafka.getActorId());
    assertEquals(10, kafka.getLevel());
    assertEquals(Set.of(199986), base.player.getAvatars().getAvatars().keySet());
    assertEquals(List.of(199986), base.player.getLineupManager().getLineups()[0].getAvatars());
    assertEquals(QuestState.Phase.DOING, base.player.getMissionManager().sub(100010102).getPhase());
    assertFalse(base.player.hasCreatedCharacter());
    assertFalse(base.player.getMissionManager().finished(1000101));
  }

  @Test
  void silverWolfJoinsAndLeavesWhileKafkaRemainsThenKafkaLeavesForTheProtagonist() {
    base.player.getMissionManager().refresh();
    finishFixture(100010109);
    base.player.getMissionManager().refresh();
    assertEquals(List.of(1001005, 1001006), base.player.getCurrentLineup().getAvatars());
    assertEquals(
        QuestState.Phase.FINISHED, base.player.getMissionManager().sub(100010110).getPhase());
    finishFixture(100010116);
    base.player.getMissionManager().refresh();
    assertEquals(List.of(1001005), base.player.getCurrentLineup().getAvatars());
    assertNull(base.player.getAvatarById(1001006));
    assertEquals(
        QuestState.Phase.FINISHED, base.player.getMissionManager().sub(100010123).getPhase());
    finishFixture(100010112);
    finishFixture(100010136);
    base.player.getGameplay().getStoryValues().put("profile:character-path", 8001);
    base.player.getMissionManager().refresh();
    assertEquals(List.of(199986), base.player.getCurrentLineup().getAvatars());
    assertTrue(base.player.getAvatars().getTempAvatars().isEmpty());
    assertEquals(
        QuestState.Phase.FINISHED, base.player.getMissionManager().sub(100010120).getPhase());
    assertEquals(QuestState.Phase.DOING, base.player.getMissionManager().sub(100010121).getPhase());
    base.player.getMissionManager().refresh();
    assertNull(
        base.player.getGameplay().getMissionTrials().getParty(),
        "Retired actors must not reappear");
  }

  @Test
  void firstSceneAndReconnectRestoreKafkaWithSavedVitals() {
    var scene = base.player.getScene();
    MissionTrialProvisioning46Test.set(base.player, "scene", null);
    base.player.getMissionManager().refresh();
    assertEquals(List.of(1001005), base.player.getCurrentLineup().getAvatars());
    var kafka = base.player.getAvatarById(1001005);
    kafka.setCurrentHp(base.player.getCurrentLineup(), 8100);
    kafka.setCurrentSp(base.player.getCurrentLineup(), 2200);
    var saved = base.gson.toJson(base.player.getGameplay());
    base.player.getMissionTrialManager().detach();
    MissionTrialProvisioning46Test.set(
        base.player, "gameplay", base.gson.fromJson(saved, GameplayState.class));
    base.player.getMissionTrialManager().restoreOnLogin();
    MissionTrialProvisioning46Test.set(base.player, "scene", scene);
    var restored = base.player.getAvatarById(1001005);
    assertEquals(8100, restored.getCurrentHp(base.player.getCurrentLineup()));
    assertEquals(2200, restored.getCurrentSp(base.player.getCurrentLineup()));
    assertEquals(QuestState.Phase.DOING, base.player.getMissionManager().sub(100010102).getPhase());
  }

  @Test
  void existingOpeningProgressIsPreservedWhenMissingKafkaIsRepaired() {
    for (int id : List.of(100010102, 100010100, 100010104, 100010103)) finishFixture(id);
    String saved = base.gson.toJson(base.player.getGameplay().getSubMissions());
    base.player.getMissionManager().refresh();
    assertEquals(List.of(1001005), base.player.getCurrentLineup().getAvatars());
    for (var entry : JsonParser.parseString(saved).getAsJsonObject().entrySet())
      assertEquals(
          entry.getValue(),
          base.gson.toJsonTree(
              base.player.getMissionManager().sub(Integer.parseInt(entry.getKey()))));
    assertEquals(QuestState.Phase.DOING, base.player.getMissionManager().sub(100010105).getPhase());
  }

  @Test
  void advancedOrCancelledPlayersDoNotReceiveOpeningActors() {
    finishFixture(100010120);
    base.player.getMissionManager().refresh();
    assertEquals(List.of(199986), base.player.getCurrentLineup().getAvatars());
    base.player.getGameplay().getSubMissions().clear();
    base.player.getGameplay().getCancelledMissions().add(1000101);
    base.player.getMissionManager().refresh();
    assertEquals(List.of(199986), base.player.getCurrentLineup().getAvatars());
  }

  @Test
  void missingKafkaResourceDoesNotManufactureAnActorOrFinishTheIntro() {
    GameData.getSpecialAvatarExcelMap().values().removeIf(row -> row.getId() == 1001005);
    base.player.getMissionManager().refresh();
    assertEquals(List.of(199986), base.player.getCurrentLineup().getAvatars());
    assertNull(base.player.getGameplay().getMissionTrials().getOpeningSource());
    assertEquals(QuestState.Phase.DOING, base.player.getMissionManager().sub(100010102).getPhase());
  }

  @Test
  void actualPlayerDocumentsRestoreBothPlotActorsWithoutCompletingIntro() throws Exception {
    base.player.getMissionManager().refresh();
    finishFixture(100010109);
    base.player.getMissionManager().refresh();
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "opening-player-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      MissionTrialProvisioning46Test.set(document, "uid", base.player.getUid());
      MissionTrialProvisioning46Test.set(document, "gameplay", base.player.getGameplay());
      store.save(document);
      var expected = base.gson.toJsonTree(document.getGameplay());
      for (int i = 0; i < 2; i++) {
        var loaded =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(expected, base.gson.toJsonTree(loaded.getGameplay()));
        var restored = base.reload(loaded.getGameplay());
        restored.getMissionTrialManager().restoreOnLogin();
        assertEquals(List.of(1001005, 1001006), restored.getCurrentLineup().getAvatars());
        assertEquals(
            QuestState.Phase.DOING, restored.getMissionManager().sub(100010102).getPhase());
        assertEquals(0, restored.saves);
        store.save(loaded);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
