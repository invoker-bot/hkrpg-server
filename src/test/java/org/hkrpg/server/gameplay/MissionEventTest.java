package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.*;

class MissionEventTest {
  private final GameplayProgressTest fixture = new GameplayProgressTest();

  @AfterEach
  void reset() {
    fixture.reset();
  }

  @Test
  void leavingUsesTheActualPreviousPlaceAndAnEntryReloadDoesNotCount() {
    fixture.mission(
        199934,
        "Manual",
        "1999341,1999342,1999343",
        """
[{"ID":1999341,"MainMissionID":199934,"TakeType":"Auto","FinishType":"LeavePlane","ParamType":"Equal","ParamInt1":100},
 {"ID":1999342,"MainMissionID":199934,"TakeType":"Auto","FinishType":"LeaveFloor","ParamType":"Equal","ParamInt1":100,"ParamInt2":100001},
 {"ID":1999343,"MainMissionID":199934,"TakeType":"Auto","FinishType":"EnterMapByEntrance","ParamType":"Equal","ParamInt1":11},
 {"ID":1999344,"MainMissionID":199934,"TakeType":"MultiSequence","TakeParamIntList":[1999342],"FinishType":"EnterMapByEntrance","ParamType":"Equal","ParamInt1":12}]
""");
    var p = new GameplayProgressTest.ScenePlayer();
    p.scenePlane = 100;
    p.sceneFloor = 100001;
    p.sceneEntry = 11;
    p.getMissionManager().accept(199934);
    p.getMissionManager().onSceneTransition(100, 100001, 11, 100, 100001, 11);
    assertEquals(0, p.getMissionManager().sub(1999343).getProgress());
    p.sceneFloor = 100002;
    p.sceneEntry = 12;
    p.getMissionManager().onSceneTransition(100, 100001, 11, 100, 100002, 12);
    assertEquals(1, p.getMissionManager().sub(1999342).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999341).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999344).getProgress());
    p.scenePlane = 101;
    p.sceneFloor = 101001;
    p.getMissionManager().onSceneTransition(100, 100002, 12, 101, 101001, 12);
    assertEquals(1, p.getMissionManager().sub(1999341).getProgress());
    assertEquals(1, p.getMissionManager().sub(1999344).getProgress());
  }

  @Test
  void monsterListsCountOnlyMatchingDeathsAndNewGoalsWaitForTheirOwnEvent() {
    fixture.mission(
        199932,
        "Manual",
        "1999321,1999322,1999323",
        """
[{"ID":1999321,"MainMissionID":199932,"TakeType":"Auto","FinishType":"KillMonsterList","ParamType":"ListContain","LevelFloorID":20001001,"ParamInt1":36,"ParamIntList":[200001,200002]},
 {"ID":1999322,"MainMissionID":199932,"TakeType":"Auto","FinishType":"KillMonsterGroupList","ParamType":"ListContain","LevelFloorID":20001001,"ParamIntList":[36,38],"Progress":2},
 {"ID":1999323,"MainMissionID":199932,"TakeType":"MultiSequence","TakeParamIntList":[1999321],"FinishType":"KillMonsterList","ParamType":"ListContain","LevelFloorID":20001001,"ParamInt1":36,"ParamIntList":[200001]},
 {"ID":1999324,"MainMissionID":199932,"TakeType":"Auto","FinishType":"KillMonsterList","ParamType":"Equal","LevelFloorID":20001001,"ParamInt1":36,"ParamIntList":[200001]}]
""");
    var p = new GameplayProgressTest.ScenePlayer();
    p.sceneFloor = 20001001;
    p.getMissionManager().accept(199932);
    p.getMissionManager().onKillMonster(20002001, 36, 200001);
    p.getMissionManager().onKillMonster(20001001, 99, 200001);
    assertEquals(0, p.getMissionManager().sub(1999321).getProgress());
    p.getMissionManager().onKillMonster(20001001, 36, 200009);
    assertEquals(1, p.getMissionManager().sub(1999322).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999321).getProgress());
    p.getMissionManager().onKillMonster(20001001, 36, 200001);
    assertEquals(1, p.getMissionManager().sub(1999321).getProgress());
    assertEquals(2, p.getMissionManager().sub(1999322).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999323).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999324).getProgress());
  }

  @Test
  void waitedDaysUseTheFourAmBoundaryAndNeverBackdateAnOldDocument() {
    fixture.mission(
        199933,
        "Manual",
        "1999331",
        """
[{"ID":1999331,"MainMissionID":199933,"TakeType":"Auto","FinishType":"WaitDays","ParamType":"Equal","ParamInt1":1}]
""");
    var p = new GameplayProgressTest.TestPlayer();
    var before = Instant.parse("2026-09-30T19:59:59Z");
    p.getMissionManager().setClock(Clock.fixed(before, ZoneOffset.UTC));
    p.getMissionManager().accept(199933);
    p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(1999331).getProgress());
    p.getMissionManager().setClock(Clock.fixed(before.minusSeconds(86400), ZoneOffset.UTC));
    p.getMissionManager().refresh();
    assertEquals(0, p.getMissionManager().sub(1999331).getProgress());
    p.getMissionManager().setClock(Clock.fixed(before.plusSeconds(1), ZoneOffset.UTC));
    p.getMissionManager().refresh();
    assertEquals(1, p.getMissionManager().sub(1999331).getProgress());
    var old = new GameplayProgressTest.TestPlayer();
    old.getGameplay()
        .getMainMissions()
        .put(199933, new emu.lunarcore.game.quest.QuestState(199933));
    old.getGameplay()
        .getSubMissions()
        .put(1999331, new emu.lunarcore.game.quest.QuestState(1999331));
    old.getMissionManager().setClock(Clock.fixed(before.plusSeconds(86400 * 100), ZoneOffset.UTC));
    old.getMissionManager().refresh();
    assertEquals(0, old.getMissionManager().sub(1999331).getProgress());
  }

  @Test
  void aFinishedParentCannotKeepConsumingSceneEventsForAnOptionalObjective() {
    fixture.mission(
        199920,
        "Manual",
        "1999201",
        """
[{"ID":1999201,"MainMissionID":199920,"TakeType":"Auto","FinishType":"StageWin","ParamInt1":101},
 {"ID":1999202,"MainMissionID":199920,"TakeType":"Auto","FinishType":"PropDestruct","ParamType":"Equal","LevelFloorID":20001001,"ParamInt1":8,"ParamInt2":300001}]
""");
    var player = new GameplayProgressTest.ScenePlayer();
    player.sceneFloor = 20001001;
    player.getMissionManager().accept(199920);
    player.getMissionManager().onStageWin(101);
    assertTrue(player.getMissionManager().finished(199920));
    player.getMissionManager().onPropDestruct(20001001, 8, 300001);
    assertEquals(0, player.getMissionManager().sub(1999202).getProgress());
  }
}
