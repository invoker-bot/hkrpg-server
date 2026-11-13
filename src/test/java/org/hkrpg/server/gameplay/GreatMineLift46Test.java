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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real source records, disposable player; no operations on the game client or operator saves. */
class GreatMineLift46Test {
  static final String FLOOR = "P20121_F20121001";
  static final String GROUP = "Config/LevelOutput/SharedRuntimeGroup/Groups_" + FLOOR + "/LevelGroup_" + FLOOR + "_G3.json";
  static final String MISSION = "Config/Level/Mission/1010501/";
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, PropExcel> oldProps = new HashMap<>();
  final Map<Integer, InteractExcel> oldInteractions = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final Map<Integer, MissionInfo> oldInfos = new HashMap<>();
  JsonObject records;
  FloorInfo oldFloor;
  MazePlaneExcel plane;
  Actor player;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    final List<BasePacket> packets = new ArrayList<>();
    Scene scene;
    boolean failSave;
    int saves;
    Actor() { teams.getCurrentLineup().getAvatars().add(0); }
    public LineupManager getLineupManager() { return teams; }
    public GameAvatar getAvatarById(int id) { return null; }
    public Scene getScene() { return scene; }
    public int getPlaneId() { return 20121; }
    public int getFloorId() { return 20121001; }
    public int getEntryId() { return 201210102; }
    public void save() { if (failSave) throw new IllegalStateException("isolated save failure"); saves++; }
    public void sendPacket(BasePacket packet) { packets.add(packet); }
  }

  @BeforeEach void prepare() throws Exception {
    oldFloor = GameData.getFloorInfos().get(FLOOR);
    oldSubs.putAll(GameplayDefinitions.subs); GameplayDefinitions.subs.clear();
    oldMains.addAll(GameData.getMainMissionExcelMap().values()); GameData.getMainMissionExcelMap().values().clear();
    oldInfos.putAll(GameData.getMissionInfos()); GameData.getMissionInfos().clear();
    try (var in = new InputStreamReader(getClass().getResourceAsStream("/fixtures/great-mine-lift-4.6.json"), StandardCharsets.UTF_8)) {
      records = JsonParser.parseReader(in).getAsJsonObject().getAsJsonObject("records");
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MazeProp.json")) {
      var row = raw.getAsJsonObject().deepCopy();
      row.addProperty("PropName", row.getAsJsonObject("PropName").get("Hash").getAsLong());
      var prop = gson.fromJson(row, PropExcel.class); prop.onLoad();
      oldProps.put(prop.getId(), GameData.getPropExcelMap().get(prop.getId())); GameData.getPropExcelMap().add(prop);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/InteractConfig.json")) {
      var row = gson.fromJson(raw, InteractExcel.class);
      oldInteractions.put(row.getId(), GameData.getInteractExcelMap().get(row.getId())); GameData.getInteractExcelMap().add(row);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MainMission.json")) GameData.getMainMissionExcelMap().add(gson.fromJson(raw, MainMissionExcel.class));
    GameData.getMissionInfos().put(1010501, gson.fromJson(records.get(MISSION + "MissionInfo_1010501.json"), MissionInfo.class));
    GameData.getMissionInfos().put(1010601, gson.fromJson("{\"MainMissionID\":1010601,\"FinishSubMissionList\":[101060101]}", MissionInfo.class));
    for (var raw : records.getAsJsonObject(MISSION + "MissionInfo_1010501.partial.json").getAsJsonArray("SubMissionList")) {
      var sub = gson.fromJson(raw, MissionInfo.SubMissionInfo.class); GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    script(records.getAsJsonObject(MISSION + "Mission_101050116.json"));
    plane = gson.fromJson(records.getAsJsonArray("ExcelOutput/MazePlane.json").get(0), MazePlaneExcel.class);
    player = new Actor();
    player.getGameplay().getMainMissions().put(1010501, new QuestState(1010501));
    accepted(101050111); player.getMissionManager().sub(101050111).advance(1, 1, 100);
    accepted(101050116);
    scene();
  }

  void accepted(int id) { player.getGameplay().getSubMissions().put(id, new QuestState(id)); }
  void script(JsonObject source) {
    var task = gson.fromJson(source, MissionTaskInfo.class); task.retainSourceDefinition(source);
    GameplayDefinitions.subs.get(101050116).setTask(task);
  }
  void scene() {
    var floor = gson.fromJson(records.get("Config/LevelOutput/RuntimeFloor/" + FLOOR + ".json"), FloorInfo.class);
    var group = gson.fromJson(records.get(GROUP), GroupInfo.class); group.setId(3); floor.getGroups().put(3, group);
    floor.onLoad(); GameData.getFloorInfos().put(FLOOR, floor);
    player.scene = new Scene(player, plane, 20121001); player.scene.setEntryId(player.getEntryId());
  }
  EntityProp lift() { return player.scene.getEntitiesByGroup(EntityProp.class, 3).stream().filter(p -> p.getInstId() == 300005).findFirst().orElseThrow(); }
  void reload() throws Exception {
    var saved = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    player = new Actor(); DailyMissionAcceptance46Test.set(player, "gameplay", saved); scene();
  }

  @AfterEach void cleanup() {
    if (oldFloor == null) GameData.getFloorInfos().remove(FLOOR); else GameData.getFloorInfos().put(FLOOR, oldFloor);
    oldProps.forEach((id, old) -> { GameData.getPropExcelMap().keySet().remove(id); if (old != null) GameData.getPropExcelMap().add(old); });
    oldInteractions.forEach((id, old) -> { GameData.getInteractExcelMap().keySet().remove(id); if (old != null) GameData.getInteractExcelMap().add(old); });
    GameplayDefinitions.subs.clear(); GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getMainMissionExcelMap().values().clear(); oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getMissionInfos().clear(); GameData.getMissionInfos().putAll(oldInfos);
  }

  @Test void originalHiddenChangePropStateFinishesTheChapterAndAcceptsNextMain() {
    assertEquals("4fe1e410cf67f30ee38fb8997c2ca67130ef161b0502ff1a464af1dbac7661b5", PropTimelines.source(lift().getPropInfo()));
    assertEquals(PropState.Locked, lift().getState());
    player.getMissionManager().refresh();
    assertEquals(PropState.Elevator2, lift().getState());
    assertTrue(MissionStateRecords.subCompleted(player, 101050116));
    assertTrue(MissionStateRecords.mainCompleted(player, 1010501));
    assertTrue(MissionStateRecords.mainDoing(player, 1010601));
    assertEquals(1, player.getGameplay().getScenePropStates().getRecords().size());
  }

  @Test void savedLiftStateBeforeMissionCallbackRecoversAndDoesNotRunAgainAfterCompletion() throws Exception {
    player.getMissionManager().refresh();
    assertEquals(PropState.Elevator2, lift().getState());
    accepted(101050116); player.getGameplay().getMainMissions().put(1010501, new QuestState(1010501));
    player.getGameplay().getMainMissions().remove(1010601);
    reload(); player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainCompleted(player, 1010501));
    assertEquals(Retcode.SUCC, ScenePropTransitions.interact(player, 115140, lift().getEntityId()).retcode());
    assertEquals(PropState.Elevator1, lift().getState());
    reload(); player.getMissionManager().refresh();
    assertEquals(PropState.Elevator1, lift().getState(), "Completed chapter must not force the lift upstairs again");
    assertTrue(MissionStateRecords.mainDoing(player, 1010601));
  }

  @Test void failedPropSaveDoesNotFinishTheHiddenObjectiveOrPublishAChange() {
    var before = gson.toJson(player.getGameplay());
    player.scene.toProto(); player.packets.clear(); player.failSave = true;
    assertDoesNotThrow(() -> player.getMissionManager().refresh());
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(PropState.Locked, lift().getState());
    assertTrue(player.packets.isEmpty());
  }

  @Test void actualFinalDialogueAcceptsAndExecutesTheHiddenStateChangeInTheSameSession() {
    accepted(101050111); player.getGameplay().getSubMissions().remove(101050116);
    player.getMissionManager().refresh();
    assertEquals(PropState.Locked, lift().getState());
    assertNull(player.getMissionManager().sub(101050116));
    assertEquals(Retcode.SUCC, player.getMissionManager().finishTalk(101050111, "Mission_101050111"));
    assertEquals(PropState.Elevator2, lift().getState());
    assertTrue(MissionStateRecords.mainCompleted(player, 1010501));
    assertTrue(MissionStateRecords.mainDoing(player, 1010601));
    int saves = player.saves;
    player.getMissionManager().refresh(); player.getMissionManager().refresh();
    assertEquals(saves, player.saves, "Repeated refreshes must not save or reward the chapter again");
  }

  @Test void oldUnlockedConsoleSaveRecoversWithoutRewritingThatExistingReceipt() throws Exception {
    var controller = player.scene.getEntitiesByGroup(EntityProp.class, 3).stream()
        .filter(p -> p.getInstId() == 300027).findFirst().orElseThrow();
    var old = new ScenePropState.Snapshot(20121, 20121001, 0, 0, 3, 300027, 101008, 0,
        PropTimelines.source(controller.getPropInfo()), "");
    player.getGameplay().scenePropStates(player.getGameplay().getScenePropStates().with(List.of(old)));
    var key = old.key(); var saved = gson.toJson(old);
    reload(); player.getMissionManager().refresh();
    assertEquals(PropState.Elevator2, lift().getState());
    assertTrue(MissionStateRecords.mainDoing(player, 1010601));
    assertEquals(saved, gson.toJson(player.getGameplay().getScenePropStates().get(key)));
    assertEquals(2, player.getGameplay().getScenePropStates().getRecords().size());
  }

  @ParameterizedTest @ValueSource(strings = {"prerequisite", "cancelled-sub", "cancelled-main", "changed-script", "wrong-target", "wrong-state", "extra-effect", "changed-lift", "wrong-scene"})
  void missingProofCannotCompleteTheChapter(String variant) {
    var c = GameplayDefinitions.subs.get(101050116);
    switch (variant) {
      case "prerequisite" -> accepted(101050111);
      case "cancelled-sub" -> player.getGameplay().getCancelledSubs().add(101050116);
      case "cancelled-main" -> player.getGameplay().getCancelledMissions().add(1010501);
      case "wrong-target" -> c.setParamInt2(300011);
      case "wrong-state" -> c.setParamInt3(14);
      case "extra-effect" -> c.setSubRewardID(123456);
      case "wrong-scene" -> player.scene.setEntryId(201210103);
      case "changed-lift" -> lift().getPropInfo().setInitLevelGraph("Config/Level/Props/Other.json");
      case "changed-script" -> { var raw = records.getAsJsonObject(MISSION + "Mission_101050116.json").deepCopy(); raw.addProperty("Unexpected", true); script(raw); }
    }
    player.getMissionManager().refresh();
    assertEquals(PropState.Locked, lift().getState());
    assertFalse(MissionStateRecords.mainCompleted(player, 1010501));
    assertNull(player.getGameplay().getMainMissions().get(1010601));
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
  }
}
