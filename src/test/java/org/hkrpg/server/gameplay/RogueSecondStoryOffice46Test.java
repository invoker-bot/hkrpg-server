package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.game.scene.SceneEntityLoader;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

/** The completed first test and the second test's active entrance are explicit fixtures. */
class RogueSecondStoryOffice46Test {
  final RogueFirstStoryEntry46Test fixture = new RogueFirstStoryEntry46Test();
  FloorInfo previousFloor;
  MapEntranceExcel previousEntry;
  PropExcel previousProp;

  @BeforeEach
  void setup() throws Exception {
    fixture.prepare();
    var first = fixture.actor.getGameplay().getMainMissions().get(4030001);
    assertTrue(first.advance(1, 1, 100));
    fixture.actor.getGameplay().getMainMissions().put(4030002, new QuestState(4030002));
    fixture.actor.getGameplay().getSubMissions().put(403000202, new QuestState(403000202));
    fixture.actor.getGameplay().getSubMissions().put(403000102, completed(403000102));
    var mission = fixture.gson.fromJson(
        "{\"ID\":403000202,\"MainMissionID\":4030002,\"LevelPlaneID\":80112,"
            + "\"LevelFloorID\":80112001,\"FinishType\":\"EnterFloor\",\"ParamType\":\"Equal\","
            + "\"ParamInt1\":80001,\"ParamInt2\":80001002,\"Progress\":1}",
        MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(403000202, mission);
    var previous = new GameplayState.RogueRun(100, 1, Map.of(1, 100));
    previous.enter(1, 100);
    previous.finish(GameplayState.RogueRun.Phase.FINISHED);
    previous.setInside(false);
    fixture.actor.getGameplay().setRogue(previous);
    GameplayDefinitions.maps.put(2, List.of(
        new GameplayDefinitions.Site(2, 1, true, List.of(2), 0),
        new GameplayDefinitions.Site(2, 2, false, List.of(3), 0),
        new GameplayDefinitions.Site(2, 3, false, List.of(), 0)));
    GameplayDefinitions.rooms.put(201, new GameplayDefinitions.Room(201, 1, 8000102, 19,
        Map.of(19, 2003, 3, 2001, 4, 2002)));
    GameplayDefinitions.rooms.put(202, new GameplayDefinitions.Room(202, 1, 8000102, 18,
        Map.of(18, 2006, 6, 2004, 7, 2005)));
    GameplayDefinitions.rooms.put(203, new GameplayDefinitions.Room(203, 7, 8000102, 29,
        Map.of(29, 2007)));
    GameplayDefinitions.roomPoolByMap.remove(2);
    previousFloor = GameData.getFloorInfos().get("P80001_F80001002");
    previousEntry = GameData.getMapEntranceExcelMap().get(8000102);
    var floor = new FloorInfo();
    var start = fixture.gson.fromJson(
        "{\"AnchorList\":[{\"ID\":1,\"PosX\":-42.98,\"PosY\":18.61,"
            + "\"PosZ\":-91.38,\"RotY\":345}]}", GroupInfo.class);
    start.setId(19);
    floor.getGroups().put(19, start);
    GameData.getFloorInfos().put("P80001_F80001002", floor);
    GameData.getMapEntranceExcelMap().add(fixture.gson.fromJson(
        "{\"ID\":8000102,\"PlaneID\":80001,\"FloorID\":80001002}", MapEntranceExcel.class));
    previousProp = GameData.getPropExcelMap().get(1003);
    var prop = fixture.gson.fromJson(
        "{\"ID\":1003,\"PropType\":\"PROP_ORDINARY\","
            + "\"PropStateList\":[\"Closed\",\"Open\",\"Locked\"]}", PropExcel.class);
    prop.onLoad();
    GameData.getPropExcelMap().add(prop);
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-second-story-office-group14-4.6.json")) {
      assertNotNull(input);
      var group = fixture.gson.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), GroupInfo.class);
      group.setId(14);
      fixture.actor.actual.getResourceGroups().put(14, group);
    }
  }

  private static QuestState completed(int id) {
    var state = new QuestState(id);
    assertTrue(state.advance(1, 1, 100));
    return state;
  }

  @AfterEach
  void cleanup() {
    if (previousFloor == null) GameData.getFloorInfos().remove("P80001_F80001002");
    else GameData.getFloorInfos().put("P80001_F80001002", previousFloor);
    if (previousEntry == null) GameData.getMapEntranceExcelMap().keySet().remove(8000102);
    else GameData.getMapEntranceExcelMap().add(previousEntry);
    if (previousProp == null) GameData.getPropExcelMap().keySet().remove(1003);
    else GameData.getPropExcelMap().add(previousProp);
    fixture.restore();
  }

  @Test
  void acceptedSecondTestLoadsItsAuthoredOfficeEntrance() throws Exception {
    {
      var group = fixture.actor.actual.getResourceGroups().get(14);
      assertTrue(fixture.actor.getMissionManager().shouldLoadGroup(group, fixture.actor.actual));
      new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
      var props = fixture.actor.actual.getEntities(EntityProp.class).stream()
          .filter(p -> p.getGroupId() == 14 && p.getInstId() == 300002).toList();
      assertEquals(1, props.size(), "The tutorial entry must reach the client scene");
      assertEquals(1003, props.getFirst().getPropId());
      assertEquals(PropState.Open, props.getFirst().getState());
    }
  }

  @Test
  void secondEntranceIsNotExposedDuringFirstTest() {
    fixture.actor.getGameplay().getMainMissions().put(4030001, new QuestState(4030001));
    fixture.actor.getGameplay().getMainMissions().remove(4030002);
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    assertTrue(fixture.actor.actual.getEntities(EntityProp.class).stream()
        .noneMatch(p -> p.getGroupId() == 14 && p.getInstId() == 300002));
  }

  @Test
  void unknownSecondEntryMissionDoesNotSilentlyRestartFirstTest() {
    var previous = fixture.actor.getGameplay().getRogue();
    GameplayDefinitions.subs.remove(403000202);
    assertTrue(RogueSecondStoryEntry.inProgress(fixture.actor));
    assertFalse(RogueSecondStoryEntry.awaiting(fixture.actor));
    assertEquals(Retcode.ROGUE_AREA_INVALID, fixture.actor.getUniverseManager().start(100));
    assertSame(previous, fixture.actor.getGameplay().getRogue());
    assertEquals(0, fixture.actor.starts);
  }

  @Test
  void alteredMapAndRoomsAreNotUsedAsSecondTutorial() {
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    GameplayDefinitions.rooms.put(203, new GameplayDefinitions.Room(203, 7, 8000102, 29,
        Map.of(29, 9999)));
    assertTrue(RogueMapPools.assign(2, fixture.actor.getUid(), 100).isEmpty());
    var previous = fixture.actor.getGameplay().getRogue();
    assertEquals(Retcode.ROGUE_AREA_INVALID, fixture.actor.getUniverseManager().start(100));
    assertSame(previous, fixture.actor.getGameplay().getRogue());
  }

  @Test
  void secondTestUsesItsOwnThreeRoomsAndStartAnchor() {
    var old = fixture.actor.getGameplay().getRogue();
    new SceneEntityLoader().onSceneLoad(fixture.actor.actual);
    assertEquals(Map.of(1, 201, 2, 202, 3, 203),
        RogueMapPools.assign(2, fixture.actor.getUid(), 100).orElseThrow().rooms());
    assertEquals(Retcode.SUCC, fixture.actor.getUniverseManager().start(100));
    var current = fixture.actor.getGameplay().getRogue();
    assertNotEquals(old.getRunId(), current.getRunId());
    assertEquals(2, current.getMapId());
    assertEquals(201, current.getRoomId());
    assertEquals(8000102, fixture.actor.getEntryId());
    assertEquals(-42980, fixture.actor.getPos().getX());
    assertEquals(-91380, fixture.actor.getPos().getZ());
    assertEquals(GameplayState.RogueRun.Phase.FINISHED, old.getPhase());
  }

  @Test
  void cannotStartSecondTutorialWhenEntranceIsNotLoaded() {
    var previous = fixture.actor.getGameplay().getRogue();
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, fixture.actor.getUniverseManager().start(100));
    assertSame(previous, fixture.actor.getGameplay().getRogue());
  }

  @Test
  void explicitlyDisabledOperatorPoolIsRespected() {
    GameplayDefinitions.roomPoolByMap.put(2, Map.of(1, List.of()));
    assertTrue(RogueMapPools.assign(2, fixture.actor.getUid(), 100).isEmpty());
  }
}
