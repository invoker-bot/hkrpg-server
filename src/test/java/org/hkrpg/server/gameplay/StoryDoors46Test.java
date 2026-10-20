package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original opening floor, controllers and objectives; progress histories are isolated fixtures. */
class StoryDoors46Test {
  final Gson gson = ResourceLoader.getGson();
  final OpeningSceneGroups46Test base = new OpeningSceneGroups46Test();
  final Map<Integer, InteractExcel> oldInteractions = new HashMap<>();
  JsonObject records;
  Actor player;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    final List<BasePacket> packets = new ArrayList<>();
    Scene scene;
    boolean failSave;
    int saves;

    Actor() {
      teams.getCurrentLineup().getAvatars().add(0);
    }

    public LineupManager getLineupManager() {
      return teams;
    }

    public GameAvatar getAvatarById(int id) {
      return null;
    }

    public Scene getScene() {
      return scene;
    }

    public int getPlaneId() {
      return 20001;
    }

    public int getFloorId() {
      return 20001001;
    }

    public int getEntryId() {
      return 2000101;
    }

    public void save() {
      if (failSave) throw new IllegalStateException("isolated failed save");
      saves++;
    }

    public void sendPacket(BasePacket packet) {
      packets.add(packet);
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    base.prepare();
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/story-doors-4.6.json"),
            java.nio.charset.StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("records");
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MazeProp.json")) {
      var row = raw.getAsJsonObject().deepCopy();
      if (row.has("PropName") && row.get("PropName").isJsonObject())
        row.addProperty("PropName", row.getAsJsonObject("PropName").get("Hash").getAsLong());
      var excel = gson.fromJson(row, PropExcel.class);
      excel.onLoad();
      GameData.getPropExcelMap().add(excel);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/InteractConfig.json")) {
      var excel = gson.fromJson(raw, InteractExcel.class);
      oldInteractions.put(excel.getId(), GameData.getInteractExcelMap().get(excel.getId()));
      GameData.getInteractExcelMap().add(excel);
    }
    for (var raw :
        records
            .getAsJsonObject("Config/Level/Mission/1000101/MissionInfo_1000101.partial.json")
            .getAsJsonArray("SubMissionList")) {
      var sub = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      var script = records.get("Config/Level/Mission/1000101/Mission_" + sub.getId() + ".json");
      if (script != null) {
        var task = gson.fromJson(script, MissionTaskInfo.class);
        task.retainSourceDefinition(script);
        sub.setTask(task);
      }
      GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    freshFloor();
    player = new Actor();
    player.getGameplay().getMainMissions().put(1000101, new QuestState(1000101));
  }

  void freshFloor() {
    base.floor =
        gson.fromJson(
            records.get("Config/LevelOutput/RuntimeFloor/P20001_F20001001.json"), FloorInfo.class);
    var group =
        gson.fromJson(
            records.get(
                "Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001/LevelGroup_P20001_F20001001_G5.json"),
            GroupInfo.class);
    group.setId(5);
    base.floor.getGroups().put(5, group);
    base.floor.onLoad();
    GameData.getFloorInfos().put(OpeningSceneGroups46Test.FLOOR, base.floor);
  }

  @AfterEach
  void cleanup() {
    oldInteractions.forEach(
        (id, old) -> {
          GameData.getInteractExcelMap().keySet().remove(id);
          if (old != null) GameData.getInteractExcelMap().add(old);
        });
    base.restore();
  }

  void scene() {
    player.scene = new Scene(player, base.plane, 20001001);
    player.scene.setEntryId(2000101);
  }

  EntityProp prop(int instance) {
    return player.scene.getEntitiesByGroup(EntityProp.class, 5).stream()
        .filter(p -> p.getInstId() == instance)
        .findFirst()
        .orElseThrow();
  }

  void accepted(int id) {
    player.getGameplay().getSubMissions().put(id, new QuestState(id));
  }

  void completed(int id) {
    accepted(id);
    player.getMissionManager().sub(id).advance(1, 1, 100);
  }

  void readyFirst() {
    completed(100010103);
    accepted(100010105);
    scene();
  }

  void reload() {
    var state = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    player = new Actor();
    player.getGameplay().getSubMissions().putAll(state.getSubMissions());
    player.getGameplay().getMainMissions().putAll(state.getMainMissions());
    player.getGameplay().scenePropStates(state.getScenePropStates());
    freshFloor();
    scene();
  }

  @ParameterizedTest
  @ValueSource(ints = {300001, 300003, 300005, 300010, 300007, 300015})
  void untouchedDoorsKeepTheirOriginalClosedState(int instance) {
    scene();
    assertEquals(PropState.Closed, prop(instance).getState());
  }

  @Test
  void authoredOpenDoorStaysOpen() {
    scene();
    assertEquals(PropState.Open, prop(300009).getState());
  }

  @Test
  void allThirtySixAuthoredDoorTypesKeepClosedAndLockedStates() {
    scene();
    int checked = 0;
    for (var raw : records.getAsJsonArray("ExcelOutput/MazeProp.json")) {
      var row = raw.getAsJsonObject();
      if (!row.get("JsonPath").getAsString().contains("_Door_")) continue;
      int id = row.get("ID").getAsInt();
      for (var state : List.of(PropState.Closed, PropState.Locked)) {
        if (!GameData.getPropExcelMap().get(id).getPropStateList().contains(state)) continue;
        var info =
            gson.fromJson(
                "{\"ID\":390000,\"PropID\":" + id + ",\"State\":\"" + state + "\"}",
                PropInfo.class);
        var loaded =
            player
                .scene
                .getEntityLoader()
                .loadProp(player.scene, base.floor.getGroups().get(5), info);
        assertNotNull(loaded);
        assertEquals(state, loaded.getState(), "door type " + id);
      }
      checked++;
    }
    assertEquals(36, checked);
  }

  @Test
  void corruptedOrMissingDoorBindingRejectsWithoutPartialConsoleUpdate() {
    readyFirst();
    assertNotNull(
        prop(300002).getPropInfo().getValueSource(),
        "The loader must retain the authored door binding");
    prop(300002)
        .getPropInfo()
        .getValueSource()
        .getValues()
        .getFirst()
        .addProperty("Value", "5,399999");
    assertNotEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(player, 1010, prop(300002).getEntityId()).retcode());
    assertEquals(PropState.Closed, prop(300002).getState());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test
  void changedUnlockScriptAndCancelledObjectiveStayLocked() {
    completed(100010103);
    accepted(100010105);
    player.getGameplay().getCancelledSubs().add(100010105);
    scene();
    assertEquals(PropState.Locked, prop(300002).getState());
    player.getGameplay().getCancelledSubs().clear();
    GameplayDefinitions.subs
        .get(100010105)
        .getTask()
        .retainSourceDefinition(JsonParser.parseString("{}"));
    scene();
    assertEquals(PropState.Locked, prop(300002).getState());
  }

  @Test
  void pairedReceiptRecoversMissionAfterInterruptionBeforeItsCallback() {
    readyFirst();
    assertEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(player, 1010, prop(300002).getEntityId()).retcode());
    accepted(100010105); // Crash boundary: durable pair, mission callback not yet saved.
    player.getGameplay().getSubMissions().remove(100010107);
    reload();
    player.getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(100010105).getPhase());
    assertEquals(PropState.Open, prop(300001).getState());
    assertEquals(PropState.Closed, prop(300003).getState());
  }

  @Test
  void bothDoorStatesAndTheObjectiveSurviveActualPlayerMorphiaReload() throws Exception {
    readyFirst();
    assertEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(player, 1010, prop(300002).getEntityId()).retcode());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "story-doors");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var saved = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(saved, 123456785);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(saved, player.getGameplay());
      store.save(saved);
      for (int round = 0; round < 2; round++) {
        var loaded = Objects.requireNonNull(store.find(Player.class).first());
        assertEquals(2, loaded.getGameplay().getScenePropStates().getRecords().size());
        player = new Actor();
        gameplay.set(player, loaded.getGameplay());
        scene();
        assertEquals(PropState.Open, prop(300001).getState());
        assertEquals(PropState.Closed, prop(300003).getState());
        assertEquals(
            QuestState.Phase.FINISHED, player.getMissionManager().sub(100010105).getPhase());
        store.save(loaded);
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void lockedConsoleCannotBeUsedBeforeItsStoryObjective() {
    scene();
    assertEquals(PropState.Locked, prop(300004).getState());
    assertNotEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(player, 1010, prop(300004).getEntityId()).retcode());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @Test
  void firstConsoleOpensOnlyItsDoorAndAcceptsButDoesNotOpenTheNext() {
    readyFirst();
    assertEquals(PropState.Closed, prop(300002).getState());
    assertEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(player, 1010, prop(300002).getEntityId()).retcode());
    assertEquals(PropState.Open, prop(300001).getState());
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(100010105).getPhase());
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(100010107).getPhase());
    assertEquals(PropState.Closed, prop(300004).getState());
    assertEquals(PropState.Closed, prop(300003).getState());
    reload();
    assertEquals(PropState.Open, prop(300001).getState());
    assertEquals(PropState.Closed, prop(300003).getState());
    assertEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(player, 1010, prop(300004).getEntityId()).retcode());
    reload();
    assertEquals(PropState.Open, prop(300003).getState());
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(100010107).getPhase());
    assertNotEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(player, 1010, prop(300004).getEntityId()).retcode());
  }

  @Test
  void aDoorCannotBypassItsConsole() {
    readyFirst();
    assertNotEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(player, 1010, prop(300003).getEntityId()).retcode());
    assertEquals(PropState.Closed, prop(300003).getState());
  }

  @Test
  void failedSaveRollsBackDoorConsoleJournalAndMissionTogether() {
    readyFirst();
    String before = gson.toJson(player.getGameplay());
    player.packets.clear();
    player.failSave = true;
    assertEquals(
        Retcode.SYSTEM_BUSY,
        ScenePropTransitions.interact(player, 1010, prop(300002).getEntityId()).retcode());
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(PropState.Closed, prop(300001).getState());
    assertEquals(PropState.Closed, prop(300002).getState());
    assertTrue(player.packets.isEmpty());
  }

  @Test
  void oldPrematureConsoleReceiptCannotOpenAnUnfinishedStoryDoor() {
    completed(100010105);
    accepted(100010107);
    player
        .getGameplay()
        .scenePropStates(
            ScenePropState.empty()
                .with(
                    List.of(
                        new ScenePropState.Snapshot(
                            20001,
                            20001001,
                            0,
                            0,
                            5,
                            300004,
                            100000,
                            1,
                            "8be5debc68d4f17cef85be8ccccccc09840e83f1018f957bf1bee92c7ba2d75d",
                            ""))));
    scene();
    assertEquals(PropState.Closed, prop(300004).getState());
    assertEquals(PropState.Closed, prop(300003).getState());
    assertEquals(PropState.Open, prop(300001).getState());
    assertEquals(
        Retcode.SUCC,
        ScenePropTransitions.interact(player, 1010, prop(300004).getEntityId()).retcode());
    reload();
    assertEquals(PropState.Open, prop(300003).getState());
  }

  @Test
  void oldReceiptOfAnUnrestrictedConsoleStillRestoresItsDoor() {
    var info =
        base.floor.getGroups().get(5).getPropList().stream()
            .filter(p -> p.getID() == 300004)
            .findFirst()
            .orElseThrow();
    // Isolated authored-Closed variant exercises compatibility independently of story unlocks.
    info.setState(PropState.Closed);
    player
        .getGameplay()
        .scenePropStates(
            ScenePropState.empty()
                .with(
                    List.of(
                        new ScenePropState.Snapshot(
                            20001,
                            20001001,
                            0,
                            0,
                            5,
                            300004,
                            100000,
                            1,
                            "8be5debc68d4f17cef85be8ccccccc09840e83f1018f957bf1bee92c7ba2d75d",
                            ""))));
    scene();
    assertEquals(PropState.Open, prop(300004).getState());
    assertEquals(PropState.Open, prop(300003).getState());
  }

  @Test
  void backtrackingCannotPromoteAnOldPrematureConsoleReceiptToMissionCompletion() {
    completed(100010105);
    accepted(100010107);
    var original = GameplayDefinitions.subs.get(100010107);
    var raw = gson.toJsonTree(original).getAsJsonObject();
    raw.addProperty("IsBackTrack", true);
    var backtracking = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
    backtracking.setTask(original.getTask());
    assertTrue(backtracking.isBackTrack());
    GameplayDefinitions.subs.put(100010107, backtracking);
    player
        .getGameplay()
        .scenePropStates(
            ScenePropState.empty()
                .with(
                    List.of(
                        new ScenePropState.Snapshot(
                            20001,
                            20001001,
                            0,
                            0,
                            5,
                            300004,
                            100000,
                            1,
                            "8be5debc68d4f17cef85be8ccccccc09840e83f1018f957bf1bee92c7ba2d75d",
                            ""))));
    scene();
    assertFalse(ScenePropTransitions.satisfied(player, backtracking));
  }
}
