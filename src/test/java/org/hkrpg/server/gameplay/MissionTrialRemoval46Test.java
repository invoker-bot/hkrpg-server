package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.NpcExcel;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Original removal graph and NPC placement; accepted dialogue results are explicit fixtures. */
class MissionTrialRemoval46Test {
  final MissionTrialProvisioning46Test base = new MissionTrialProvisioning46Test();
  @TempDir Path root;
  JsonObject records;
  GroupInfo group;
  NpcInfo placement;
  NpcExcel oldNpc;
  MissionInfo.SubMissionInfo removal;

  @BeforeEach
  void prepare() throws Exception {
    base.root = root;
    base.prepare();
    base.accept(100010110, 1001006);
    records = base.fixture("mission-trial-removal-4.6.json").getAsJsonObject("records");
    var mainJson =
        row("Config/Level/Mission/1000101/MissionInfo_1000101.json").deepCopy().getAsJsonObject();
    mainJson.add(
        "SubMissionList",
        row("Config/Level/Mission/1000101/MissionInfo_1000101.partial.json")
            .getAsJsonObject()
            .get("SubMissionList"));
    var main = base.gson.fromJson(mainJson, MissionInfo.class);
    var partial =
        base.gson.fromJson(
            row("Config/Level/Mission/1000101/MissionInfo_1000101.partial.json"),
            MissionInfo.class);
    GameData.getMissionInfos().put(1000101, main);
    for (var sub : partial.getSubMissionList()) {
      if (sub.getId() == 100010110 || sub.getId() == 100010123) {
        var source = row("Config/Level/Mission/1000101/Mission_" + sub.getId() + ".json");
        var task = base.gson.fromJson(source, MissionTaskInfo.class);
        task.retainSourceDefinition(source);
        sub.setTask(task);
      }
      GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    removal = GameplayDefinitions.subs.get(100010123);
    group =
        ResourceLoader.getGson()
            .fromJson(
                row(
                    "Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001/LevelGroup_P20001_F20001001_G27.json"),
                GroupInfo.class);
    group.setId(27);
    placement =
        group.getNPCList().stream().filter(n -> n.getID() == 400003).findFirst().orElseThrow();
    var floor =
        ResourceLoader.getGson()
            .fromJson(
                row("Config/LevelOutput/RuntimeFloor/P20001_F20001001.json"), FloorInfo.class);
    floor.getGroups().put(27, group);
    MissionTrialProvisioning46Test.setField(
        base.player.getScene(), Scene.class, "floorInfo", floor);
    oldNpc = GameData.getNpcExcelMap().get(1006);
    GameData.getNpcExcelMap()
        .add(base.gson.fromJson(row("ExcelOutput/NPCData.json"), NpcExcel.class));
    base.player.getMissionManager().refresh();
    assertNotNull(
        base.player.getAvatarById(1001006), "Creation must succeed before removal is tested");
    var talk = new QuestState(100010116);
    talk.advance(1, 1, 100);
    base.player.getGameplay().getSubMissions().put(100010116, talk);
  }

  JsonElement row(String path) {
    return records.getAsJsonArray(path).get(0);
  }

  void refresh() {
    base.player.getMissionManager().refresh();
  }

  long npcs() {
    return base.player.getScene().getEntities().values().stream()
        .filter(e -> e instanceof EntityNpc && e.getGroupId() == 27 && e.getInstId() == 400003)
        .count();
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getNpcExcelMap().values().removeIf(n -> n.getId() == 1006);
    if (oldNpc != null) GameData.getNpcExcelMap().add(oldNpc);
    base.restore();
  }

  @Test
  void originalGraphRemovesOwnedTrialAndKeepsFormalPartyValues() {
    var normal = base.player.getLineupManager().getLineups()[0];
    normal.setMp(2);
    var formal = base.player.getAvatars().getAvatars().get(199986);
    formal.setCurrentHp(normal, 6100);
    formal.setCurrentSp(normal, 2400);
    refresh();
    assertNull(base.player.getAvatarById(1001006));
    assertNull(base.player.getGameplay().getMissionTrials().getParty());
    assertEquals(List.of(199986), base.player.getCurrentLineup().getAvatars());
    assertEquals(2, normal.getMp());
    assertEquals(6100, formal.getCurrentHp(normal));
    assertEquals(2400, formal.getCurrentSp(normal));
    assertEquals(
        QuestState.Phase.FINISHED, base.player.getMissionManager().sub(100010123).getPhase());
    assertEquals(QuestState.Phase.DOING, base.player.getMissionManager().sub(100010117).getPhase());
    assertFalse(base.player.getMissionManager().finished(1000101));
  }

  @Test
  void originalParallelCreateNpcPublishesOnlyItsDeclaredPlacement() {
    assertFalse(placement.isLoadOnInitial());
    assertEquals(0, npcs());
    refresh();
    assertEquals(1, npcs());
    var npc =
        (EntityNpc)
            base.player.getScene().getEntities().values().stream()
                .filter(
                    e -> e instanceof EntityNpc && e.getGroupId() == 27 && e.getInstId() == 400003)
                .findFirst()
                .orElseThrow();
    assertEquals(1006, npc.getNpcId());
    assertEquals(
        0,
        base.player.getScene().getEntities().values().stream()
            .filter(e -> e instanceof EntityNpc && e.getGroupId() == 27 && e.getInstId() != 400003)
            .count());
    var before = base.gson.toJson(base.player.getGameplay());
    int saves = base.player.saves;
    refresh();
    assertEquals(before, base.gson.toJson(base.player.getGameplay()));
    assertEquals(saves, base.player.saves);
    assertEquals(1, npcs());
  }

  @Test
  void noReceiptDoesNotSpawnScriptOnlyNpc() {
    assertNull(
        base.player.getScene().getEntityLoader().loadNpc(base.player.getScene(), group, placement));
  }

  @Test
  void missingDeclaredNpcDoesNotRetireTheTrialOrCompleteTheGoal() {
    group.getNPCList().remove(placement);
    refresh();
    assertNotNull(base.player.getAvatarById(1001006));
    assertEquals(QuestState.Phase.DOING, base.player.getMissionManager().sub(100010123).getPhase());
    assertEquals(0, npcs());
  }

  @Test
  void foreignTrialCacheDoesNotAuthorizeRemoval() {
    var old = base.player.getAvatars().getTempAvatars().remove(1001006);
    refresh();
    assertEquals(QuestState.Phase.DOING, base.player.getMissionManager().sub(100010123).getPhase());
    assertEquals(0, npcs());
    base.player.getAvatars().getTempAvatars().put(1001006, old);
  }

  @Test
  void unacceptedOrCancelledSourceDoesNotRemoveTrial() {
    base.player.getGameplay().getCancelledSubs().add(100010123);
    refresh();
    assertNotNull(base.player.getAvatarById(1001006));
    assertEquals(0, npcs());
  }

  @Test
  void extraUnknownTaskKeepsTheWholeGraphPending() {
    var graph =
        row("Config/Level/Mission/1000101/Mission_100010123.json").deepCopy().getAsJsonObject();
    graph
        .getAsJsonArray("OnStartSequece")
        .get(1)
        .getAsJsonObject()
        .getAsJsonArray("TaskList")
        .add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.Unknown\"}"));
    removal.getTask().retainSourceDefinition(graph);
    refresh();
    assertNotNull(base.player.getAvatarById(1001006));
    assertEquals(0, npcs());
  }

  @Test
  void actualPlayerDocumentsKeepRemovalAndNpcReceiptAcrossTwoRestores() throws Exception {
    refresh();
    assertEquals(1, npcs());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "trial-removal-fixture");
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
      var expected = base.gson.toJson(base.player.getGameplay());
      for (int i = 0; i < 2; i++) {
        var saved =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(expected, base.gson.toJson(saved.getGameplay()));
        var copy = base.reload(saved.getGameplay());
        copy.getMissionTrialManager().restoreOnLogin();
        var scene = new Scene(copy, base.player.getScene().getExcel(), base.player.getFloorId());
        MissionTrialProvisioning46Test.setField(
            scene, Scene.class, "floorInfo", base.player.getScene().getFloorInfo());
        MissionTrialProvisioning46Test.set(copy, "scene", scene);
        var npc = scene.getEntityLoader().loadNpc(scene, group, placement);
        assertNotNull(npc);
        assertEquals(1006, npc.getNpcId());
        assertNull(copy.getAvatarById(1001006));
        assertEquals(expected, base.gson.toJson(copy.getGameplay()));
        assertEquals(0, copy.saves);
        store.save(saved);
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void changedPlacementClosesRestoredNpcWithoutRecreatingTrial() throws Exception {
    refresh();
    assertEquals(1, npcs());
    MissionTrialProvisioning46Test.setField(placement, ObjectInfo.class, "PosX", -99.0f);
    var copy = base.reload(base.player.getGameplay());
    var scene = new Scene(copy, base.player.getScene().getExcel(), base.player.getFloorId());
    MissionTrialProvisioning46Test.setField(
        scene, Scene.class, "floorInfo", base.player.getScene().getFloorInfo());
    MissionTrialProvisioning46Test.set(copy, "scene", scene);
    assertNull(scene.getEntityLoader().loadNpc(scene, group, placement));
    assertNull(copy.getAvatarById(1001006));
    assertEquals(0, copy.saves);
  }

  @Test
  void savedRemovalResumesItsPendingGoalWithoutRecreatingOrRemovingAnotherActor() throws Exception {
    refresh();
    var copy = base.reload(base.player.getGameplay());
    copy.getGameplay().getSubMissions().put(100010123, new QuestState(100010123));
    var scene = new Scene(copy, base.player.getScene().getExcel(), base.player.getFloorId());
    MissionTrialProvisioning46Test.setField(
        scene, Scene.class, "floorInfo", base.player.getScene().getFloorInfo());
    MissionTrialProvisioning46Test.set(copy, "scene", scene);
    assertNull(scene.getEntityLoader().loadNpc(scene, group, placement));
    copy.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(copy, 100010123));
    assertNull(copy.getAvatarById(1001006));
    assertEquals(1, copy.getGameplay().getMissionTrials().getRemovals().size());
    assertEquals(
        1,
        scene.getEntities().values().stream()
            .filter(e -> e instanceof EntityNpc && e.getGroupId() == 27 && e.getInstId() == 400003)
            .count());
  }

  @Test
  void corruptedCreationProofClosesRestoredNpc() throws Exception {
    refresh();
    var copy = base.reload(base.player.getGameplay());
    copy.getGameplay()
        .getMissionTrials()
        .getRemovals()
        .get(100010123)
        .getCreations()
        .put(100010110, "wrong");
    var scene = new Scene(copy, base.player.getScene().getExcel(), base.player.getFloorId());
    MissionTrialProvisioning46Test.setField(
        scene, Scene.class, "floorInfo", base.player.getScene().getFloorInfo());
    MissionTrialProvisioning46Test.set(copy, "scene", scene);
    assertNull(scene.getEntityLoader().loadNpc(scene, group, placement));
    assertNull(copy.getAvatarById(1001006));
    assertEquals(0, copy.saves);
  }

  @Test
  void failedSaveKeepsTheOwnedActorAndRollsBackTheRemovalReceipt() throws Exception {
    class FailingActor extends MissionTrialProvisioning46Test.Actor {
      boolean fail;

      @Override
      public void save() {
        if (fail) throw new IllegalStateException("Explicit fixture save failure");
        super.save();
      }
    }
    var copy = new FailingActor();
    MissionTrialProvisioning46Test.set(
        copy,
        "gameplay",
        base.gson.fromJson(base.gson.toJson(base.player.getGameplay()), GameplayState.class));
    MissionTrialProvisioning46Test.set(copy, "gender", base.player.getGender());
    MissionTrialProvisioning46Test.set(copy, "planeId", base.player.getPlaneId());
    MissionTrialProvisioning46Test.set(copy, "floorId", base.player.getFloorId());
    var formal = new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(199986));
    formal.setOwner(copy);
    copy.getAvatars().getAvatars().put(199986, formal);
    copy.getLineupManager().getLineups()[0].getAvatars().add(199986);
    copy.getMissionTrialManager().restoreOnLogin();
    var scene = new Scene(copy, base.player.getScene().getExcel(), base.player.getFloorId());
    MissionTrialProvisioning46Test.setField(
        scene, Scene.class, "floorInfo", base.player.getScene().getFloorInfo());
    MissionTrialProvisioning46Test.set(copy, "scene", scene);
    copy.getGameplay().getSubMissions().put(100010123, new QuestState(100010123));
    var expected = base.gson.toJson(copy.getGameplay());
    copy.fail = true;
    assertFalse(copy.getMissionTrialManager().remove(removal));
    assertEquals(expected, base.gson.toJson(copy.getGameplay()));
    assertNotNull(copy.getAvatarById(1001006));
    assertEquals(List.of(199986, 1001006), copy.getCurrentLineup().getAvatars());
    assertTrue(copy.getGameplay().getMissionTrials().getRemovals().isEmpty());
    assertEquals(0, copy.saves);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  @SuppressWarnings("unchecked")
  void sceneConstructorRestoresScriptNpcBeforeTheDestinationIsPublished(boolean fromAnotherFloor) throws Exception {
    refresh();
    var field = GameData.class.getDeclaredField("floorInfos");
    field.setAccessible(true);
    var floors = (Map<String, FloorInfo>) field.get(null);
    var key = "P20001_F20001001";
    var previous = floors.put(key, base.player.getScene().getFloorInfo());
    try {
      var copy = base.reload(base.player.getGameplay());
      if (fromAnotherFloor) {
        MissionTrialProvisioning46Test.set(copy, "planeId", 20002);
        MissionTrialProvisioning46Test.set(copy, "floorId", 20002001);
      }
      assertNull(copy.getScene());
      copy.getMissionTrialManager().restoreOnLogin();
      var scene = new Scene(copy, base.player.getScene().getExcel(), base.player.getFloorId());
      assertEquals(1, scene.getEntities().values().stream().filter(e -> e instanceof EntityNpc && e.getGroupId() == 27 && e.getInstId() == 400003).count());
      assertNull(copy.getScene());
      assertEquals(0, copy.saves);
    } finally {
      if (previous == null) floors.remove(key); else floors.put(key, previous);
    }
  }
}
