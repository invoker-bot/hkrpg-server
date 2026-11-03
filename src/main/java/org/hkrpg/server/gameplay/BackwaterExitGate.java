package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.Scene;

/** The authored 203 performance requests Locked -> Open; acceptance must not enable it early. */
final class BackwaterExitGate {
  private static final String GRAPH =
      "Config/Level/Props/Chap01/InitLevelGraph_Prop_Chap01_BaseConsole_01A_Control_SimpleDoor.json";
  private static final String TARGET = """
      {"$type":"RPG.GameCore.TargetFetchAdvPropEx","FetchType":"SinglePropByPropID",
       "SinglePropID":{"GroupID":{"IsDynamic":false,"FixedValue":{"Value":21}},
                       "ID":{"IsDynamic":false,"FixedValue":{"Value":300004}}}}
      """;
  private static final JsonElement CALLBACK = JsonParser.parseString("""
      {"OnInitSequece":[],"OnStartSequece":[{"TaskList":[
        {"$type":"RPG.GameCore.PropSetupUITrigger","ButtonIcon":"SpriteOutput/TalkIcon/ChatIcon.png",
         "IconType":{"EnumIndex":20,"Value":1},"ButtonText":{"Hash":4901613590055454325},
         "ButtonCallback":[{"$type":"RPG.GameCore.PropStateExecute","TargetType":%1$s,"State":"Locked","Execute":[
           {"$type":"RPG.GameCore.StartPropInteractMode","TargetType":%1$s,"Mode":"UsePose02"},
           {"$type":"RPG.GameCore.PropTriggerAnimState","AnimStateName":"Activing","TargetType":%1$s},
           {"$type":"RPG.GameCore.WaitSecond","WaitTime":{"IsDynamic":false,"FixedValue":{"Value":1.5}}},
           {"$type":"RPG.GameCore.EndPropInteract"},
           {"$type":"RPG.GameCore.TriggerPerformance","PerformanceType":"D","PerformanceID":101030203,"ValueSource":{}},
           {"$type":"RPG.GameCore.PropReqInteract","TargetType":%1$s,
            "InteractID":{"IsDynamic":false,"FixedValue":{"Value":1210}}}]}],"TargetType":%1$s}]}],"Type":"Mission"}
      """.formatted(TARGET));
  private static final JsonElement BINDINGS = JsonParser.parseString("""
      {"Values":[{"$type":"RPG.GameCore.SharedString","Value":"21,300005",
                   "Key":"Maze_Prop_Chap01_BaseControllSimpleDoor"}]}
      """);

  static boolean matches(Scene scene, PropInfo info) {
    return scene.getPlaneId() == 20111 && scene.getFloorId() == 20111001
        && info != null && info.getPropID() == 101001 && info.getID() == 300004
        && info.getGroup() != null && info.getGroup().getId() == 21
        && GRAPH.equals(info.getInitLevelGraph()) && info.getState() == PropState.Locked;
  }

  static boolean objective(Scene scene, int group, PropInfo info, MissionInfo.SubMissionInfo c) {
    if (scene.getPlaneId() != 20111 || scene.getFloorId() != 20111001 || group != 21
        || !matches(scene, info) || scene.getPlayer().getInstance() != null
        || !BINDINGS.equals(ResourceLoader.getGson().toJsonTree(info.getValueSource()))) return false;
    try {
      return c != null && c.getId() == 101030203 && c.getMainMissionID() == 1010302
          && c.getLevelPlaneID() == 20111 && c.getLevelFloorID() == 20111001
          && c.getMazePlaneID() == 20111 && c.getMazeFloorID() == 20111001
          && "PropState".equals(c.getFinishType()) && "Equal".equals(c.getParamType())
          && c.getParamInt1() == 21 && c.getParamInt2() == 300004 && c.getParamInt3() == 1
          && c.getProgress() == 1 && !c.isBackTrack()
          && "Auto".equals(c.getTakeType()) && c.getTakeParamIntList().length == 0
          && c.getParamStr1().isEmpty() && c.getParamIntList().length == 0 && c.getParamItemList().isEmpty()
          && "Config/Level/Mission/1010302/Mission_101030203.json".equals(c.getMissionJsonPath())
          && c.getTask() != null
          && CALLBACK.equals(JsonParser.parseString(c.getTask().getSourceDefinition()));
    } catch (RuntimeException invalid) { return false; }
  }

  private BackwaterExitGate() {}
}
