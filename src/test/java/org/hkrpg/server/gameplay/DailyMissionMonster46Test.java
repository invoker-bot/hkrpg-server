package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Source-owned groups and real Scene lifetime, not native combat or UI acceptance.
 * Closed tutorials, one catalog candidate and the three retained floor groups are fixtures.
 */
class DailyMissionMonster46Test {
  final DailyMissionTravel46Test source = new DailyMissionTravel46Test();
  final Gson gson = emu.lunarcore.data.ResourceLoader.getGson();
  final List<NpcMonsterExcel> oldNpcMonsters = new ArrayList<>();
  final List<PropExcel> oldProps = new ArrayList<>();
  final List<PlaneEventExcel> oldEvents = new ArrayList<>();
  final List<StageExcel> oldStages = new ArrayList<>();
  final Map<Integer, GroupInfo> groups = new HashMap<>();
  final Map<Integer, FloorInfo> floors = new HashMap<>();
  @TempDir Path temp;

  @BeforeEach
  void setup() throws Exception {
    source.temp = temp;
    source.setup();
    oldNpcMonsters.addAll(GameData.getNpcMonsterExcelMap().values());
    oldProps.addAll(GameData.getPropExcelMap().values());
    oldEvents.addAll(GameData.getPlaneEventExcelMap().values());
    oldStages.addAll(GameData.getStageExcelMap().values());
    GameData.getNpcMonsterExcelMap().values().clear();
    GameData.getPropExcelMap().values().clear();
    GameData.getPlaneEventExcelMap().values().clear();
    GameData.getStageExcelMap().values().clear();
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getMissionInfos().clear();
    GameData.getMazePlaneExcelMap().values().clear();
    GameData.getMapEntranceExcelMap().values().clear();
    GameData.getFloorInfos().clear();
    GameplayDefinitions.subs.clear();
    var definitions = new HashMap<Integer, JsonObject>();
    var children = new HashMap<Integer, JsonArray>();
    var scripts = new HashMap<Integer, JsonObject>();
    JsonArray records;
    try (var input = getClass().getResourceAsStream("/fixtures/daily-killmonster-4.6.json")) {
      records = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
    }
    assertEquals(102, records.size());
    for (var entry : records) {
      var record = entry.getAsJsonObject();
      assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", record.get("sourceCommit").getAsString());
      var row = record.getAsJsonObject("row");
      switch (record.get("kind").getAsString()) {
        case "definition" -> definitions.put(row.get("MainMissionID").getAsInt(), row);
        case "sub" -> children.computeIfAbsent(row.get("MainMissionID").getAsInt(), ignored -> new JsonArray()).add(row);
        case "main" -> GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
        case "daily" -> source.daily.put(row.get("ID").getAsInt(), row);
        case "plane" -> GameData.getMazePlaneExcelMap().add(gson.fromJson(row, MazePlaneExcel.class));
        case "entrance" -> GameData.getMapEntranceExcelMap().add(gson.fromJson(row, MapEntranceExcel.class));
        case "npcMonster" -> GameData.getNpcMonsterExcelMap().add(gson.fromJson(row, NpcMonsterExcel.class));
        case "prop" -> { var value = gson.fromJson(row, PropExcel.class); value.onLoad(); GameData.getPropExcelMap().add(value); }
        case "event" -> GameData.getPlaneEventExcelMap().add(gson.fromJson(row, PlaneEventExcel.class));
        case "stage" -> { var value = gson.fromJson(row, StageExcel.class); value.onLoad(); GameData.getStageExcelMap().add(value); }
        case "floor" -> { var value = gson.fromJson(row, FloorInfo.class); floors.put(value.getFloorID(), value); }
        case "group" -> {
          var value = gson.fromJson(row, GroupInfo.class);
          int main = value.getOwnerMainMissionID();
          value.setId(groupId(main));
          groups.put(main, value);
        }
        case "script" -> {
          String name = record.get("source").getAsString();
          int id = Integer.parseInt(name.substring(name.lastIndexOf("Mission_") + 8, name.length() - 5));
          scripts.put(id, row);
        }
        // The base fixture already holds the identical original quota, active target and rewards.
        case "count", "quest", "rule", "pool", "reward", "item" -> { }
        default -> fail("Unknown original source record");
      }
    }
    for (var entry : definitions.entrySet()) {
      entry.getValue().add("SubMissionList", children.get(entry.getKey()));
      var graph = gson.fromJson(entry.getValue(), MissionInfo.class);
      GameData.getMissionInfos().put(entry.getKey(), graph);
      for (var sub : graph.getSubMissionList()) {
        GameplayDefinitions.subs.put(sub.getId(), sub);
        if (scripts.containsKey(sub.getId())) {
          var task = gson.fromJson(scripts.get(sub.getId()), MissionTaskInfo.class);
          task.retainSourceDefinition(scripts.get(sub.getId()));
          sub.setTask(task);
        }
      }
    }
    for (int main : List.of(3000211, 3000212, 3000213)) {
      var floor = floors.get(floorId(main));
      int index = switch (main) { case 3000211 -> 62; case 3000212 -> 63; default -> 54; };
      assertEquals(groupId(main), floor.getSimpleGroupList().get(index).getID());
      assertTrue(floor.getMainDimension().getGroupIndexList().contains(index));
      floor.registerGroup(index, groups.get(main));
    }
    for (var floor : floors.values()) {
      floor.onLoad();
      GameData.getFloorInfos().put("P" + floor.getFloorID() / 1000 + "_F" + floor.getFloorID(), floor);
    }
  }

  @AfterEach
  void cleanup() {
    source.cleanup();
    GameData.getNpcMonsterExcelMap().values().clear(); oldNpcMonsters.forEach(GameData.getNpcMonsterExcelMap()::add);
    GameData.getPropExcelMap().values().clear(); oldProps.forEach(GameData.getPropExcelMap()::add);
    GameData.getPlaneEventExcelMap().values().clear(); oldEvents.forEach(GameData.getPlaneEventExcelMap()::add);
    GameData.getStageExcelMap().values().clear(); oldStages.forEach(GameData.getStageExcelMap()::add);
  }

  static int groupId(int main) { return switch (main) { case 3000211 -> 78; case 3000212 -> 79; case 3000213 -> 95; default -> throw new IllegalArgumentException(); }; }
  static int floorId(int main) { return main == 3000213 ? 20003001 : 20002001; }
  static int first(int main) { return main * 100 + 1; }
  static int kill(int main) { return main * 100 + 2; }

  void select(int main) throws Exception { source.select(main); }
  void targetFloor(int main) { source.move(floorId(main) / 1000, floorId(main)); }

  void rebuildTargetFloor(int main) {
    var oldScene = source.actor.getScene();
    int away = floorId(main) == 20002001 ? 20003001 : 20002001;
    source.move(away / 1000, away);
    targetFloor(main);
    assertNotSame(oldScene, source.actor.getScene());
  }

  List<EntityMonster> targets(int main) {
    return source.actor.getScene().getEntities(EntityMonster.class).stream()
        .filter(monster -> monster.getGroupId() == groupId(main)).toList();
  }

  EntityMonster target(int main) {
    var monsters = targets(main);
    assertEquals(1, monsters.size());
    var monster = monsters.getFirst();
    assertEquals(200001, monster.getInstId());
    assertEquals(groups.get(main).getMonsterList().getFirst().getNPCMonsterID(), monster.getExcel().getId());
    assertEquals(groups.get(main).getMonsterList().getFirst().getEventID(), monster.getEventId());
    assertTrue(monster.isAlive());
    return monster;
  }

  void assertTalkPropRemains(int main) {
    var props = source.actor.getScene().getEntities(EntityProp.class).stream()
        .filter(prop -> prop.getGroupId() == groupId(main) && prop.getInstId() == 300001).toList();
    assertEquals(1, props.size());
    assertEquals(100056, props.getFirst().getPropId());
  }

  void manualReachKill(int main) throws Exception {
    select(main);
    assertEquals(Retcode.SUCC, source.actor.getMissionManager().accept(main));
    source.talk(first(main));
    targetFloor(main);
    assertEquals(0, source.actor.getMissionManager().sub(kill(main)).getProgress());
  }

  void tail(int main) {
    for (int id : List.of(main * 100 + 3, main * 100 + 4)) {
      var sub = GameplayDefinitions.subs.get(id);
      if (source.actor.getPlaneId() != sub.getLevelPlaneID() || source.actor.getFloorId() != sub.getLevelFloorID())
        source.move(sub.getLevelPlaneID(), sub.getLevelFloorID());
      assertEquals(Retcode.SUCC, source.actor.getMissionManager().finishTalk(id, sub.getParamStr1()));
    }
    assertEquals(QuestState.Phase.CLOSED, source.actor.getGameplay().getMainMissions().get(main).getPhase());
    assertEquals(Retcode.SUCC, source.actor.getDailyManager().claimQuest(2100003).retcode());
  }

  @ParameterizedTest @ValueSource(ints = {3000211, 3000212, 3000213})
  void originalKillGraphsBecomeEligibleOnlyWithTheirRealOwnedResourceTarget(int main) {
    assertTrue(DailyMissionRepeats.supportedGraph(main)); // Intended causal qualification RED.
  }

  @ParameterizedTest @ValueSource(ints = {3000211, 3000212, 3000213})
  void originalKillCommissionIsAutomaticallyAssignedWithoutManualAccept(int main) throws Exception {
    select(main); source.start(); // Intended actual assignment RED.
    assertEquals(0, source.actor.getScoin());
    assertEquals(List.of(main), source.actor.getGameplay().getDailyMissionSelections().getDays()
        .get(Long.toString(DailyManager.day(source.now))).stream().map(DailyMissionSelections.Selection::getId).toList());
  }

  @ParameterizedTest @ValueSource(strings = {"foreignOwner", "wrongInstance", "duplicateMonster", "clientOnly",
      "unloadedFloor", "storyDimension", "missingNpc", "missingStage", "foreignAccept", "wrongMaze",
      "unknownOperation", "missingEvent"})
  void aSourceKillTargetCannotAuthorizeAnUnownedOrUnusableOriginalResource(String kind) throws Exception {
    int main = 3000211;
    assertTrue(DailyMissionRepeats.supportedGraph(main));
    var sub = GameplayDefinitions.subs.get(kill(main));
    var group = groups.get(main);
    var monster = group.getMonsterList().getFirst();
    switch (kind) {
      case "foreignOwner" -> DailyMissionAcceptance46Test.set(group, "OwnerMainMissionID", 3000212);
      case "wrongInstance" -> monster.ID = 200002;
      case "duplicateMonster" -> DailyMissionAcceptance46Test.set(group, "MonsterList", List.of(monster, monster));
      case "clientOnly" -> DailyMissionAcceptance46Test.set(monster, "IsClientOnly", true);
      case "unloadedFloor" -> DailyMissionAcceptance46Test.set(floors.get(floorId(main)), "loaded", false);
      case "storyDimension" -> DailyMissionAcceptance46Test.set(sub, "storyLineID", 7000);
      case "missingNpc" -> GameData.getNpcMonsterExcelMap().keySet().remove(monster.getNPCMonsterID());
      case "missingStage" -> GameData.getStageExcelMap().keySet().remove(
          GameData.getPlaneEventExcelMap().get(monster.getEventID(), 6).getStageId());
      case "foreignAccept" -> DailyMissionAcceptance46Test.set(group.getLoadCondition().getConditions()[1], "ID", 300021203);
      case "wrongMaze" -> sub.setMazeFloorID(20003001);
      case "unknownOperation" -> DailyMissionAcceptance46Test.set(group.getLoadCondition(), "Operation", "X");
      case "missingEvent" -> GameData.getPlaneEventExcelMap().values().clear();
      default -> fail("Unknown source corruption");
    }
    assertFalse(DailyMissionRepeats.supportedGraph(main));
  }

  @ParameterizedTest @ValueSource(strings = {"missingSelection", "foreignSelection", "foreignRunDay", "missingRunDays"})
  void anActualSceneRebuildCannotSpawnThisDailyTargetWithoutTheCurrentAcceptedRun(String kind) throws Exception {
    int main = 3000211;
    manualReachKill(main);
    var old = target(main);
    long day = DailyManager.day(source.now);
    var selections = source.actor.getGameplay().getDailyMissionSelections();
    switch (kind) {
      case "missingSelection" -> selections.getDays().clear();
      case "foreignSelection" -> DailyMissionAcceptance46Test.set(selections.getDays().get(Long.toString(day)).getFirst(), "id", 3000212);
      case "foreignRunDay" -> source.actor.getGameplay().getDailyMissionPeriods().getRunDays().put(main, day - 1);
      case "missingRunDays" -> DailyMissionAcceptance46Test.set(source.actor.getGameplay().getDailyMissionPeriods(), "runDays", null);
      default -> fail("Unknown accepted-run damage");
    }
    assertFalse(DailyMissionMonsterRules.spawns(source.actor.getScene(), groups.get(main),
        groups.get(main).getMonsterList().getFirst()));
    rebuildTargetFloor(main);
    assertTrue(targets(main).isEmpty());
    assertFalse(source.actor.getScene().defeatMonster(old));
    assertEquals(0, source.actor.getMissionManager().sub(kill(main)).getProgress());
    assertEquals(0, source.actor.getScoin());
  }

  @ParameterizedTest @ValueSource(ints = {3000211, 3000212, 3000213})
  void originalSceneGroupGatesActualMonsterLifetimeAndDoesNotCreditAnAdministrativeRemoval(int main) throws Exception {
    select(main);
    int unlock = source.daily.get(main).get("UnlockMainMission").getAsInt();
    var prerequisite = source.actor.getGameplay().getMainMissions().remove(unlock);
    targetFloor(main);
    assertFalse(MissionStateRecords.mainDoing(source.actor, main));
    assertFalse(source.actor.getScene().getLoadedGroups().contains(groupId(main)));
    assertTrue(targets(main).isEmpty());
    source.actor.getGameplay().getMainMissions().put(unlock, prerequisite);
    assertEquals(Retcode.SUCC, source.actor.getMissionManager().accept(main));
    assertEquals(main == 3000213, !targets(main).isEmpty()); // Source G95 uses main Accept, G78/79 use sub Accept.
    source.talk(first(main)); targetFloor(main);
    var monster = target(main);
    for (int i = 0; i < 4; i++) { source.actor.getScene().refreshMissionGroups(); assertSame(monster, target(main)); }
    source.actor.getScene().removeEntity(monster);
    assertFalse(source.actor.getScene().defeatMonster(monster));
    assertEquals(0, source.actor.getMissionManager().sub(kill(main)).getProgress());
    rebuildTargetFloor(main); monster = target(main);
    assertTrue(source.actor.getScene().defeatMonster(monster));
    assertFalse(source.actor.getScene().defeatMonster(monster));
    assertEquals(1, source.actor.getMissionManager().sub(kill(main)).getProgress());
    assertEquals(0, source.actor.getScoin());
    tail(main); assertEquals(5000, source.actor.getScoin());
    assertFalse(source.actor.getScene().defeatMonster(monster));
  }

  @ParameterizedTest @ValueSource(ints = {3000211, 3000212, 3000213})
  void aFinishedKillObjectiveCannotRespawnItsOriginalMonsterBeforeTheTailFinishes(int main) throws Exception {
    manualReachKill(main);
    assertTrue(source.actor.getScene().defeatMonster(target(main)));
    assertTrue(targets(main).isEmpty());
    rebuildTargetFloor(main);
    // Keep the source talk prop/group, but no fresh target after the same period's kill proof.
    assertTrue(targets(main).isEmpty());
    assertTrue(source.actor.getScene().getLoadedGroups().contains(groupId(main)));
    assertTalkPropRemains(main);
    tail(main); assertEquals(5000, source.actor.getScoin());
  }

  @ParameterizedTest @ValueSource(ints = {3000211, 3000212, 3000213})
  void nextPeriodLoadsAFreshOriginalMonsterOnlyFromTheNewAcceptedRun(int main) throws Exception {
    manualReachKill(main);
    var old = target(main);
    assertTrue(source.actor.getScene().defeatMonster(old));
    tail(main);
    var closed = source.actor.getGameplay().getMainMissions().get(main);
    source.clock(DailyMissionTravel46Test.NEXT); source.actor.getDailyManager().ensureDay(); source.start();
    source.talk(first(main)); targetFloor(main);
    var fresh = target(main); assertNotSame(old, fresh);
    assertFalse(source.actor.getScene().defeatMonster(old));
    assertEquals(0, source.actor.getMissionManager().sub(kill(main)).getProgress());
    assertTrue(source.actor.getScene().defeatMonster(fresh)); tail(main);
    assertEquals(10000, source.actor.getScoin());
    var archive = source.actor.getGameplay().getDailyMissionPeriods().getArchives()
        .get(main + ":" + DailyManager.day(DailyMissionTravel46Test.TODAY));
    assertNotNull(archive); assertSame(closed, archive.getMain());
    assertEquals(200, source.actor.getGameplay().getDailyPoints());
    assertEquals(Retcode.MISSION_ALREADY_DONE, source.actor.getMissionManager().accept(main));
  }

  @ParameterizedTest @ValueSource(ints = {3000211, 3000212, 3000213})
  void anUnpaidKilledRunExpiresBeforeTheNextAcceptedRunLoadsItsOriginalMonster(int main) throws Exception {
    manualReachKill(main);
    var oldScene = source.actor.getScene();
    var old = target(main);
    var oldMain = source.actor.getGameplay().getMainMissions().get(main);
    assertTrue(oldScene.defeatMonster(old));
    assertTrue(targets(main).isEmpty());
    assertEquals(1, source.actor.getMissionManager().sub(kill(main)).getProgress());
    assertTrue(MissionStateRecords.mainDoing(source.actor, main));
    assertEquals(0, source.actor.getScoin());
    assertEquals(0, source.actor.getGameplay().getDailyPoints());

    source.clock(DailyMissionTravel46Test.NEXT);
    source.actor.getDailyManager().ensureDay();
    source.start();
    long oldDay = DailyManager.day(DailyMissionTravel46Test.TODAY);
    var expired = source.actor.getGameplay().getDailyMissionPeriods().getExpired().get(main + ":" + oldDay);
    assertNotNull(expired);
    assertSame(oldMain, expired.getMain());
    assertEquals(1, expired.getSubs().get(kill(main)).getProgress());
    assertEquals(QuestState.Phase.FINISHED, expired.getSubs().get(kill(main)).getPhase());
    assertEquals(Map.of(main, oldDay + 1), source.actor.getGameplay().getDailyMissionPeriods().getRunDays());
    assertEquals(oldDay + 1, DailyMissionRepeats.selectedDay(source.actor, main));
    assertEquals(0, source.actor.getMissionManager().sub(first(main)).getProgress());
    assertNull(source.actor.getMissionManager().sub(kill(main)));
    assertEquals(0, source.actor.getScoin());
    assertEquals(0, source.actor.getGameplay().getDailyQuests().get(2100003).getProgress());

    // G95's authored MainAccept stays true in the same actual Scene across this assignment.
    // Its old kill is finished, but an accepted new run must receive a fresh original monster.
    if (main == 3000213) {
      assertSame(oldScene, source.actor.getScene());
      assertNotSame(old, target(main));
    }
    source.talk(first(main));
    targetFloor(main);
    var fresh = target(main);
    assertNotSame(old, fresh);
    assertFalse(source.actor.getScene().defeatMonster(old));
    assertEquals(0, source.actor.getMissionManager().sub(kill(main)).getProgress());
    assertTrue(source.actor.getScene().defeatMonster(fresh));
    tail(main);
    assertEquals(5000, source.actor.getScoin());
    assertEquals(200, source.actor.getGameplay().getDailyPoints());
    assertTrue(source.actor.getGameplay().getDailyMissionPeriods().getArchives().isEmpty());
    assertTrue(source.actor.getRewardLedger().granted("daily-mission:" + (oldDay + 1) + ":mission:" + main));
    assertFalse(source.actor.getRewardLedger().granted("mission:" + main));
  }

}
