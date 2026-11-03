package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.Scene;
import java.util.Arrays;

/** Mission 104 enables the console for its performance; mission 105 observes the later puzzle. */
final class BackwaterStoryConsole {
  private static final String GRAPH =
      "Config/Level/Props/Chap01/InitLevelGraph_Prop_Chap01_CablePuzzle_01.json";
  private static final String TARGET = """
      {"$type":"RPG.GameCore.TargetFetchAdvPropEx","FetchType":"SinglePropByPropID",
       "SinglePropID":{"GroupID":{"IsDynamic":false,"FixedValue":{"Value":21}},
                       "ID":{"IsDynamic":false,"FixedValue":{"Value":300007}}}}
      """;
  private static final JsonElement INTRO = JsonParser.parseString("""
      {"OnInitSequece":[],"OnStartSequece":[{"TaskList":[
        {"$type":"RPG.GameCore.PropSetupUITrigger","ButtonCallback":[
          {"$type":"RPG.GameCore.PropStateExecute","TargetType":%1$s,"Execute":[
            {"$type":"RPG.GameCore.StartPropInteractMode","TargetType":%1$s,"Mode":"UsePose02"},
            {"$type":"RPG.GameCore.WaitSecond","WaitTime":{"IsDynamic":false,"FixedValue":{"Value":0.5}}},
            {"$type":"RPG.GameCore.TriggerPerformance","PerformanceType":"D","PerformanceID":101030112,"ValueSource":{}},
            {"$type":"RPG.GameCore.EndPropInteract"},
            {"$type":"RPG.GameCore.FinishPerformanceMission","Key":"Mission_101030104"}]}],"TargetType":%1$s}]},
        {"TaskList":[{"$type":"RPG.GameCore.ChangePropState"}]}],"Type":"Mission"}
      """.formatted(TARGET));
  private static final JsonElement BINDINGS = JsonParser.parseString("""
      {"Values":[
        {"$type":"RPG.GameCore.SharedString","Value":"Chap01_MAZ_M101_LevelArea,CablePuzzleCam_01","Key":"Vcam"},
        {"$type":"RPG.GameCore.SharedString","Value":"Props/Outputs/Chap01/Chap01_Prop_CablePuzzle_01/Chap01_Prop_CablePuzzle_02.prefab","Key":"BoardFilePath"},
        {"$type":"RPG.GameCore.SharedString","Value":"21,300006","Key":"TargetDoor"}]}
      """);

  static boolean matches(PropInfo info) {
    return info != null && info.getPropID() == 101001 && info.getID() == 300007
        && info.getGroup() != null && info.getGroup().getId() == 21
        && GRAPH.equals(info.getInitLevelGraph());
  }

  private static boolean source(Scene scene, int group, PropInfo info) {
    if (scene.getPlaneId() != 20111 || scene.getFloorId() != 20111001 || group != 21
        || !matches(info) || info.getState() != PropState.Locked
        || !BINDINGS.equals(ResourceLoader.getGson().toJsonTree(info.getValueSource()))) return false;
    var c = GameplayDefinitions.subs.get(101030104);
    try {
      return c != null && c.getId() == 101030104 && c.getMainMissionID() == 1010301
          && c.getLevelPlaneID() == 20111 && c.getLevelFloorID() == 20111001
          && c.getMazePlaneID() == 0 && c.getMazeFloorID() == 0
          && "Talk".equals(c.getFinishType()) && "Equal".equals(c.getParamType())
          && "Auto".equals(c.getTakeType()) && c.getTakeParamIntList().length == 0
          && "Mission_101030104".equals(c.getParamStr1()) && c.getProgress() == 1
          && c.getParamInt1() == 0 && c.getParamInt2() == 0 && c.getParamInt3() == 0
          && c.getParamIntList().length == 0 && c.getParamItemList().isEmpty()
          && "Config/Level/Mission/1010301/Mission_101030104.json".equals(c.getMissionJsonPath())
          && c.getTask() != null
          && INTRO.equals(JsonParser.parseString(c.getTask().getSourceDefinition()));
    } catch (RuntimeException invalid) { return false; }
  }

  static boolean introductionReady(Scene scene, int group, PropInfo info) {
    return source(scene, group, info)
        && MissionStateRecords.doing(scene.getPlayer().getMissionManager().sub(101030104), 101030104, 1)
        && scene.getPlayer().getMissionManager().canFinishFromAction(101030104);
  }

  static boolean objective(Scene scene, int group, PropInfo info, MissionInfo.SubMissionInfo c) {
    // The source mission table deliberately has no MissionJsonPath for 105. Its orphan export
    // is not a second client script; the authored prop puzzle supplies the completion request.
    return source(scene, group, info) && c.getId() == 101030105 && c.getMainMissionID() == 1010301
        && "AnySequence".equals(c.getTakeType())
        && Arrays.equals(c.getTakeParamIntList(), new Integer[] {101030104})
        && c.getMissionJsonPath().isEmpty() && c.getTask() == null
        && c.getParamStr1().isEmpty() && c.getParamIntList().length == 0 && c.getParamItemList().isEmpty();
  }

  private BackwaterStoryConsole() {}
}
