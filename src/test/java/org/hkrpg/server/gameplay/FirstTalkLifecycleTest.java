package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FirstTalkLifecycleTest {
  static final int MAIN = 19997002, SUB = 199970021, PERFORMANCE = 199970021;
  @TempDir Path root;

  @BeforeEach
  void fixture() throws Exception {
    Files.createDirectories(root.resolve("ExcelOutput"));
    Files.createDirectories(root.resolve("Config/Level/Mission/19997002/Act"));
    Files.writeString(
        root.resolve("ExcelOutput/PerformanceE.json"),
        """
        [{"PerformanceID":199970021,"IsIntroDialogue":true,
          "PerformancePath":"Config/Level/Mission/19997002/Act/Act199970021.json"}]
        """);
    script("RPG.GameCore.PlayAndWaitSimpleTalk");
    new GameplayProgressTest()
        .mission(
            MAIN,
            "Manual",
            "199970021,199970022",
            """
[{"ID":199970021,"MainMissionID":19997002,"TakeType":"Auto","FinishType":"FinishFirstTalkPerformance",
 "ParamType":"ListContain","ParamIntList":[199970021],"LevelPlaneID":10000,"LevelFloorID":10000001},
 {"ID":199970022,"MainMissionID":19997002,"TakeType":"MultiSequence","TakeParamIntList":[199970021],
  "FinishType":"FinishFirstTalkPerformance","ParamType":"ListContain","ParamIntList":[199970021],
  "LevelPlaneID":10000,"LevelFloorID":10000001}]
""");
  }

  void script(String type) throws Exception {
    Files.writeString(
        root.resolve("Config/Level/Mission/19997002/Act/Act199970021.json"),
        "{\"OnStartSequece\":[{\"TaskList\":[{\"$type\":\""
            + type
            + "\"},{\"$type\":\"RPG.GameCore.EndPerformance\"}]}]}");
    FirstTalkDefinitions.load(root);
  }

  GameplayProgressTest.ScenePlayer player() {
    var p = new GameplayProgressTest.ScenePlayer();
    p.scenePlane = 10000;
    p.sceneFloor = 10000001;
    p.getMissionManager().accept(MAIN);
    return p;
  }

  @AfterEach
  void clean() {
    FirstTalkDefinitions.performances.clear();
    GameplayDefinitions.subs.clear();
    GameData.getMissionInfos().remove(MAIN);
    GameData.getMainMissionExcelMap().keySet().remove(MAIN);
  }

  @Test
  void firstCompletionAdvancesOnlyExistingObjectivesAndReplayCannotFinishANewGoal() {
    var p = player();
    var manager = p.getMissionManager();
    assertFalse(manager.hasSeenFirstTalk(PERFORMANCE));
    assertEquals(Retcode.SUCC, manager.finishFirstTalkPerformance(PERFORMANCE));
    assertTrue(manager.hasSeenFirstTalk(PERFORMANCE));
    assertEquals(QuestState.Phase.FINISHED, manager.sub(SUB).getPhase());
    assertEquals(QuestState.Phase.DOING, manager.sub(SUB + 1).getPhase());
    assertEquals(
        List.of(SUB), p.getGameplay().getFirstTalkReports().get(PERFORMANCE).getSubMissionIds());
    assertEquals(Retcode.SUCC, manager.finishFirstTalkPerformance(PERFORMANCE));
    assertEquals(QuestState.Phase.DOING, manager.sub(SUB + 1).getPhase());
  }

  @Test
  void unacceptedCancelledEndedAndWrongScenesCannotManufactureACompletion() {
    var p = new GameplayProgressTest.ScenePlayer();
    var manager = p.getMissionManager();
    assertEquals(Retcode.MISSION_NOT_DOING, manager.finishFirstTalkPerformance(PERFORMANCE));
    manager.accept(MAIN);
    assertEquals(Retcode.MISSION_SCENE_NOT_MATCH, manager.finishFirstTalkPerformance(PERFORMANCE));
    p.scenePlane = 10000;
    p.sceneFloor = 10000001;
    p.getGameplay().getCancelledSubs().add(SUB);
    assertEquals(Retcode.MISSION_NOT_DOING, manager.finishFirstTalkPerformance(PERFORMANCE));
    p.getGameplay().getCancelledSubs().clear();
    p.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 1);
    assertEquals(Retcode.MISSION_NOT_DOING, manager.finishFirstTalkPerformance(PERFORMANCE));
    assertTrue(p.getGameplay().getFirstTalkReports().isEmpty());
  }

  @Test
  void unsupportedClientEffectsAndUnsupportedFinishActionsStayPending() throws Exception {
    var p = player();
    script("RPG.GameCore.SetMissionCustomValue");
    assertEquals(
        Retcode.MISSION_FINISH_WAY_NOT_MATCH,
        p.getMissionManager().finishFirstTalkPerformance(PERFORMANCE));
    script("RPG.GameCore.PlayAndWaitSimpleTalk");
    var c = GameplayDefinitions.subs.get(SUB);
    c.setFinishActionList(
        new Gson()
            .fromJson(
                """
                [{"FinishActionType":"UnknownEffect","FinishActionPara":[1]}]
                """,
                emu.lunarcore.data.config.MissionInfo.FinishAction[].class));
    assertEquals(
        Retcode.MISSION_FINISH_WAY_NOT_MATCH,
        p.getMissionManager().finishFirstTalkPerformance(PERFORMANCE));
    assertTrue(p.getGameplay().getFirstTalkReports().isEmpty());
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void missingAndEscapingResourcesCannotAuthorizeReports() throws Exception {
    Files.delete(root.resolve("Config/Level/Mission/19997002/Act/Act199970021.json"));
    FirstTalkDefinitions.load(root);
    assertFalse(FirstTalkDefinitions.performances.get(PERFORMANCE).reportable());
    Files.writeString(
        root.resolve("ExcelOutput/PerformanceE.json"),
        """
        [{"PerformanceID":199970021,"IsIntroDialogue":true,"PerformancePath":"../outside.json"}]
        """);
    FirstTalkDefinitions.load(root);
    assertFalse(FirstTalkDefinitions.performances.get(PERFORMANCE).reportable());
  }

  @Test
  void interruptedReportKeepsItsOriginalObjectivesWhenRestored() {
    var p = player();
    p.getGameplay()
        .getFirstTalkReports()
        .put(PERFORMANCE, new GameplayState.FirstTalkReport(10000, 10000001, List.of(SUB)));
    p.getMissionManager().sub(SUB).advance(1, 1, 1);
    p.getMissionManager().refresh();
    assertFalse(p.getMissionManager().hasSeenFirstTalk(PERFORMANCE));
    assertEquals(Retcode.SUCC, p.getMissionManager().finishFirstTalkPerformance(PERFORMANCE));
    assertTrue(p.getMissionManager().hasSeenFirstTalk(PERFORMANCE));
    assertEquals(QuestState.Phase.DOING, p.getMissionManager().sub(SUB + 1).getPhase());
  }

  @Test
  void transitionWithNpcLifecycleCannotBeMistakenForAPurePresentation() throws Exception {
    Files.writeString(
        root.resolve("Config/Level/Mission/19997002/Act/Act199970021.json"),
        """
        {"OnStartSequece":[{"TaskList":[
          {"$type":"RPG.GameCore.PerformanceTransition","CreateNpcList":[],
           "DestroyNpcList":[{"GroupID":47,"GroupNpcID":400001}]},
          {"$type":"RPG.GameCore.EndPerformance"}]}]}
        """);
    FirstTalkDefinitions.load(root);
    var p = player();
    assertEquals(
        Retcode.MISSION_FINISH_WAY_NOT_MATCH,
        p.getMissionManager().finishFirstTalkPerformance(PERFORMANCE));
    assertFalse(p.getMissionManager().hasSeenFirstTalk(PERFORMANCE));
    Files.writeString(
        root.resolve("Config/Level/Mission/19997002/Act/Act199970021.json"),
        """
        {"OnStartSequece":[{"TaskList":[
          {"$type":"RPG.GameCore.PerformanceTransition","CreateNpcList":[],"DestroyNpcList":[],
           "CreateProp":{"TaskEnabled":true,"GroupID":47,"PropID":300001}},
          {"$type":"RPG.GameCore.EndPerformance"}]}]}
        """);
    FirstTalkDefinitions.load(root);
    assertEquals(
        Retcode.MISSION_FINISH_WAY_NOT_MATCH,
        p.getMissionManager().finishFirstTalkPerformance(PERFORMANCE));
    assertFalse(p.getMissionManager().hasSeenFirstTalk(PERFORMANCE));
  }
}
