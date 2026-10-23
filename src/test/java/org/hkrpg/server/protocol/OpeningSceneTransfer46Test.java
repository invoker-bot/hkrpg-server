package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original entry and task, real scene transition; preceding dialogue and entity population are
 * fixtures.
 */
class OpeningSceneTransfer46Test {
  static final int MAIN = 1000101, PREVIOUS = 100010120, SUB = 100010121, ENTRY = 2000201;
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final Map<Integer, MissionInfo> oldGraphs = new HashMap<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final List<MapEntranceExcel> oldEntries = new ArrayList<>();
  final List<MazePlaneExcel> oldPlanes = new ArrayList<>();
  final Map<String, FloorInfo> oldFloors = new HashMap<>();
  JsonObject records;
  Actor actor;
  Gameplay46HandlerTest.Session session;
  MissionInfo.SubMissionInfo sub;

  static class Actor extends Player {
    final LineupManager teams = new LineupManager(this);
    int saves;

    Actor() {
      resetPosition();
      getCurrentLineup().getAvatars().add(0);
    }

    @Override
    public LineupManager getLineupManager() {
      return teams;
    }

    @Override
    public void save() {
      saves++;
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    oldSubs.putAll(GameplayDefinitions.subs);
    GameplayDefinitions.subs.clear();
    oldGraphs.putAll(GameData.getMissionInfos());
    GameData.getMissionInfos().clear();
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    GameData.getMainMissionExcelMap().values().clear();
    oldEntries.addAll(GameData.getMapEntranceExcelMap().values());
    GameData.getMapEntranceExcelMap().values().clear();
    oldPlanes.addAll(GameData.getMazePlaneExcelMap().values());
    GameData.getMazePlaneExcelMap().values().clear();
    oldFloors.putAll(GameData.getFloorInfos());
    GameData.getFloorInfos().clear();
    try (var input = getClass().getResourceAsStream("/fixtures/opening-transfer-4.6.json")) {
      records =
          JsonParser.parseString(
                  new String(
                      Objects.requireNonNull(input).readAllBytes(),
                      java.nio.charset.StandardCharsets.UTF_8))
              .getAsJsonObject()
              .getAsJsonObject("records");
    }
    for (var row : records.getAsJsonArray("ExcelOutput/MapEntrance.json"))
      GameData.getMapEntranceExcelMap().add(gson.fromJson(row, MapEntranceExcel.class));
    for (var row : records.getAsJsonArray("ExcelOutput/MazePlane.json"))
      GameData.getMazePlaneExcelMap().add(gson.fromJson(row, MazePlaneExcel.class));
    for (int plane : List.of(20001, 20002)) {
      var floor =
          gson.fromJson(
              records.get("Config/LevelOutput/RuntimeFloor/P" + plane + "_F" + plane + "001.json"),
              FloorInfo.class);
      var group =
          gson.fromJson(
              records.get(
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P"
                      + plane
                      + "_F"
                      + plane
                      + "001/LevelGroup_P"
                      + plane
                      + "_F"
                      + plane
                      + "001_G1.json"),
              GroupInfo.class);
      group.setId(1);
      // Only original anchors are loaded; unrelated monsters, NPCs and props are outside this test.
      group.getMonsterList().clear();
      group.getNPCList().clear();
      group.getPropList().clear();
      floor.getGroups().put(1, group);
      field(floor, "loaded", true);
      GameData.getFloorInfos().put("P" + plane + "_F" + plane + "001", floor);
    }
    actor = new Actor();
    assertTrue(
        actor.loadScene(
            20001,
            20001001,
            2000101,
            new emu.lunarcore.util.Position(),
            new emu.lunarcore.util.Position(),
            false));
    for (var row : records.getAsJsonArray("ExcelOutput/MainMission.json"))
      GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
    var graphRow =
        records.getAsJsonObject("Config/Level/Mission/1000101/MissionInfo_1000101.json").deepCopy();
    var children = new JsonArray();
    for (var row :
        records
            .getAsJsonObject("Config/Level/Mission/1000101/MissionInfo_1000101.partial.json")
            .getAsJsonArray("SubMissionList"))
      if (Set.of(PREVIOUS, SUB).contains(row.getAsJsonObject().get("ID").getAsInt()))
        children.add(row);
    graphRow.add("SubMissionList", children);
    var graph = gson.fromJson(graphRow, MissionInfo.class);
    GameData.getMissionInfos().put(MAIN, graph);
    for (var child : graph.getSubMissionList()) {
      GameplayDefinitions.subs.put(child.getId(), child);
      var source = records.get("Config/Level/Mission/1000101/Mission_" + child.getId() + ".json");
      var task = gson.fromJson(source, MissionTaskInfo.class);
      task.retainSourceDefinition(source);
      child.setTask(task);
    }
    sub = GameplayDefinitions.subs.get(SUB);
    actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    var previous = new QuestState(PREVIOUS);
    previous.advance(1, 1, 100);
    actor.getGameplay().getSubMissions().put(PREVIOUS, previous);
    actor.getGameplay().getSubMissions().put(SUB, new QuestState(SUB));
    bind();
  }

  void bind() {
    session =
        new Gameplay46HandlerTest.Session() {
          public Player getPlayer() {
            return actor;
          }
        };
  }

  static void field(Object owner, String name, Object value) throws Exception {
    for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
      try {
        var f = type.getDeclaredField(name);
        f.setAccessible(true);
        f.set(owner, value);
        return;
      } catch (NoSuchFieldException absent) {
      }
    }
    fail("Missing field " + name);
  }

  @AfterEach
  void restore() {
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldGraphs);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getMapEntranceExcelMap().values().clear();
    oldEntries.forEach(GameData.getMapEntranceExcelMap()::add);
    GameData.getMazePlaneExcelMap().values().clear();
    oldPlanes.forEach(GameData.getMazePlaneExcelMap()::add);
    GameData.getFloorInfos().clear();
    GameData.getFloorInfos().putAll(oldFloors);
  }

  void enter(int entry, int teleport, Retcode expected) throws Exception {
    var request = Gameplay46.builder(session, "EnterSceneCsReq");
    Gameplay46.set(request, "entry_id", entry);
    Gameplay46.set(request, "teleport_id", teleport);
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("EnterSceneCsReq"),
            request.build().toByteArray()));
    assertEquals("EnterSceneScRsp", session.responseName);
    assertEquals(expected.getVal(), Gameplay46.number(session.response, "retcode"));
  }

  void blocked(int entry, int teleport) throws Exception {
    var scene = actor.getScene();
    var before = gson.toJson(actor.getGameplay());
    int saves = actor.saves;
    enter(entry, teleport, Retcode.MISSION_NOT_FINISH);
    assertSame(scene, actor.getScene());
    assertEquals(20001001, actor.getFloorId());
    assertEquals(before, gson.toJson(actor.getGameplay()));
    assertEquals(saves, actor.saves);
  }

  @Test
  void originalActiveEnterMapBreaksCircularCompletionGateAndFinishesOnlyAfterArrival()
      throws Exception {
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(SUB).getPhase());
    assertFalse(actor.getMissionManager().finished(MAIN));
    enter(ENTRY, 0, Retcode.SUCC);
    assertEquals(20002, actor.getPlaneId());
    assertEquals(20002001, actor.getFloorId());
    assertEquals(ENTRY, actor.getEntryId());
    var anchor = GameData.getFloorInfo(20002, 20002001).getAnchorInfo(1, 1);
    assertEquals(anchor.getPos(), actor.getPos());
    assertEquals(QuestState.Phase.FINISHED, actor.getMissionManager().sub(SUB).getPhase());
    assertFalse(
        actor.getMissionManager().finished(MAIN),
        "The later awakening dialogue still owns main completion");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "no-sub",
        "no-main",
        "cancelled-sub",
        "cancelled-main",
        "predecessor-doing",
        "damaged-sub",
        "wrong-place",
        "missing-script",
        "extra-task",
        "wrong-destination"
      })
  void absentPrematureOrForeignAuthorizationCannotUnlockTheMap(String change) throws Exception {
    switch (change) {
      case "no-sub" -> actor.getGameplay().getSubMissions().remove(SUB);
      case "no-main" -> actor.getGameplay().getMainMissions().remove(MAIN);
      case "cancelled-sub" -> actor.getGameplay().getCancelledSubs().add(SUB);
      case "cancelled-main" -> actor.getGameplay().getCancelledMissions().add(MAIN);
      case "predecessor-doing" ->
          actor.getGameplay().getSubMissions().put(PREVIOUS, new QuestState(PREVIOUS));
      case "damaged-sub" -> field(actor.getMissionManager().sub(SUB), "finishTime", 1L);
      case "wrong-place" -> field(actor, "floorId", 20001002);
      case "missing-script" -> sub.setTask(null);
      case "extra-task", "wrong-destination" -> {
        var source = JsonParser.parseString(sub.getTask().getSourceDefinition()).getAsJsonObject();
        var tasks =
            source
                .getAsJsonArray("OnStartSequece")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("TaskList");
        if (change.equals("extra-task")) tasks.add(tasks.get(0).deepCopy());
        else tasks.get(0).getAsJsonObject().addProperty("EntranceID", 200020102);
        var task = gson.fromJson(source, MissionTaskInfo.class);
        task.retainSourceDefinition(source);
        sub.setTask(task);
      }
    }
    if (change.equals("wrong-place")) {
      enter(ENTRY, 0, Retcode.MISSION_NOT_FINISH);
      assertEquals(20001002, actor.getFloorId());
    } else blocked(ENTRY, 0);
  }

  @Test
  void activeTaskDoesNotUnlockAnotherEntranceOrAnArbitraryTeleport() throws Exception {
    blocked(200020102, 0);
    blocked(ENTRY, 1);
  }

  @Test
  void extraUnfinishedEntranceRequirementRemainsEnforced() throws Exception {
    var row = gson.toJsonTree(GameData.getMapEntranceExcelMap().get(ENTRY)).getAsJsonObject();
    row.getAsJsonArray("FinishSubMissionList").add(100010136);
    GameData.getMapEntranceExcelMap().add(gson.fromJson(row, MapEntranceExcel.class));
    blocked(ENTRY, 0);
  }

  @Test
  void aMissingDestinationAnchorDoesNotCompleteTheArrival() throws Exception {
    GameData.getFloorInfo(20002, 20002001).getGroups().get(1).getAnchorList().clear();
    var before = gson.toJson(actor.getGameplay());
    int saves = actor.saves;
    enter(ENTRY, 0, Retcode.SCENE_ENTRY_ID_NOT_MATCH);
    assertEquals(20001001, actor.getFloorId());
    assertEquals(before, gson.toJson(actor.getGameplay()));
    assertEquals(saves, actor.saves);
  }
}
