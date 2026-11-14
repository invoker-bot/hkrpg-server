package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.Scene;
import java.util.Arrays;

/** Two authored story gates whose target models or reverse values differ from IronGate_06. */
final class FollowupStoryGates {
  private static final String CONSOLE =
      "Config/Level/Props/Chap01/InitLevelGraph_Prop_Chap01_BaseConsole_01A_Control_SimpleDoor.json";
  private static final String GATE =
      "Config/Level/Props/Chap01/InitLevelGraph_Prop_Chap01_IronGate_06.json";
  private static final String TARGET = """
      {"$type":"RPG.GameCore.TargetFetchAdvPropEx","FetchType":"SinglePropByPropID",
       "SinglePropID":{"GroupID":{"IsDynamic":false,"FixedValue":{"Value":8}},
                       "ID":{"IsDynamic":false,"FixedValue":{"Value":300005}}}}
      """;
  private static final JsonElement SVAROG_SCRIPT = JsonParser.parseString("""
      {"OnInitSequece":[],"OnStartSequece":[
        {"TaskList":[{"$type":"RPG.GameCore.ChangePropState"}]},
        {"TaskList":[{"$type":"RPG.GameCore.PropSetupUITrigger","ButtonCallback":[
          {"$type":"RPG.GameCore.PropStateExecute","TargetType":%1$s,"Execute":[
            {"$type":"RPG.GameCore.TriggerPerformance","PerformanceType":"E",
             "PerformanceID":101090219,"ValueSource":{}}]}],
          "OverrideTargetTypes":[],"TargetType":%1$s}]},
        {"IsLoop":true,"TaskList":[
          {"$type":"RPG.GameCore.WaitCustomString","CustomString":{"Value":"101090219_F"}},
          {"$type":"RPG.GameCore.PropEnableCollider","OPType":"TriggerOnly",
           "SpecifiedRelativePaths":[],"TargetType":%1$s},
          {"$type":"RPG.GameCore.StartPropInteractMode","TargetType":%1$s,"Mode":"UsePose02"},
          {"$type":"RPG.GameCore.WaitSecond","WaitTime":{"IsDynamic":false,"FixedValue":{"Value":0.6}}},
          {"$type":"RPG.GameCore.PropTriggerAnimState","AnimStateName":"Activing","TargetType":%1$s},
          {"$type":"RPG.GameCore.EndPropInteract"},
          {"$type":"RPG.GameCore.PropReqInteract","TargetType":%1$s,
           "InteractID":{"IsDynamic":false,"FixedValue":{"Value":1010}}}]}],"Type":"Mission"}
      """.formatted(TARGET));
  private static final JsonElement HILL_SCRIPT = JsonParser.parseString("""
      {"OnInitSequece":[],"OnStartSequece":[{"TaskList":[
        {"$type":"RPG.GameCore.ChangePropState"}]}],"Type":"Mission"}
      """);

  private record Definition(int plane, int floor, int group, int console, int gate,
                            int gateProp, int main, int sub, String reverse) {}
  private static final Definition SVAROG =
      new Definition(20123, 20123001, 8, 300005, 300004, 101019, 1010902, 101090232, "8,300005");
  private static final Definition HILL =
      new Definition(20131, 20131001, 6, 300004, 300013, 101005, 1011103, 101110301, "6,300013");
  private static final JsonElement SVAROG_OWNER = ownerBinding(SVAROG);
  private static final JsonElement SVAROG_GATE = gateBinding(SVAROG);
  private static final JsonElement HILL_OWNER = ownerBinding(HILL);
  private static final JsonElement HILL_GATE = gateBinding(HILL);

  private static Definition definition(int plane, int floor) {
    if (plane == SVAROG.plane() && floor == SVAROG.floor()) return SVAROG;
    if (plane == HILL.plane() && floor == HILL.floor()) return HILL;
    return null;
  }

  private static boolean identity(PropInfo info, int group, int instance, int prop, String graph) {
    return info != null && info.getGroup() != null && info.getGroup().getId() == group
        && info.getID() == instance && info.getPropID() == prop && graph.equals(info.getInitLevelGraph());
  }

  static boolean controller(Scene scene, PropInfo info) {
    var d = definition(scene.getPlaneId(), scene.getFloorId());
    return d != null && identity(info, d.group(), d.console(), 101001, CONSOLE);
  }

  static int objectiveId(Scene scene, PropInfo info) {
    var d = definition(scene.getPlaneId(), scene.getFloorId());
    return d != null && controller(scene, info) ? d.sub() : 0;
  }

  static boolean gate(Scene scene, PropInfo info) {
    var d = definition(scene.getPlaneId(), scene.getFloorId());
    return d != null && identity(info, d.group(), d.gate(), d.gateProp(), GATE);
  }

  static boolean retainsGateSource(FloorInfo floor, PropInfo info) {
    if (floor == null) return false;
    var d = floor.getFloorID() == SVAROG.floor() ? SVAROG
        : floor.getFloorID() == HILL.floor() ? HILL : null;
    return d != null && identity(info, d.group(), d.gate(), d.gateProp(), GATE);
  }

  private static JsonElement ownerBinding(Definition d) {
    return JsonParser.parseString("""
        {"Values":[{"$type":"RPG.GameCore.SharedString","Value":"%d,%d",
          "Key":"Maze_Prop_Chap01_BaseControllSimpleDoor"}]}
        """.formatted(d.group(), d.gate()));
  }

  private static JsonElement gateBinding(Definition d) {
    return JsonParser.parseString("""
        {"Values":[{"$type":"RPG.GameCore.SharedString","Value":"%s",
          "Key":"Maze_Prop_Chap01_IronGate06Controller_ConfigID"},
          {"$type":"RPG.GameCore.SharedString","Value":"MazeText_LockedDoor",
           "Key":"Maze_Chap01_Prop_IronGate06_LockHint"}]}
        """.formatted(d.reverse()));
  }

  static boolean pair(Scene scene, PropInfo owner, PropInfo gate) {
    var d = definition(scene.getPlaneId(), scene.getFloorId());
    if (d == null || !controller(scene, owner) || !gate(scene, gate)
        || owner.getState() != PropState.Locked || gate.getState() != PropState.Closed) return false;
    return (d == SVAROG ? SVAROG_OWNER : HILL_OWNER)
            .equals(ResourceLoader.getGson().toJsonTree(owner.getValueSource()))
        && (d == SVAROG ? SVAROG_GATE : HILL_GATE)
            .equals(ResourceLoader.getGson().toJsonTree(gate.getValueSource()));
  }

  static boolean objective(Scene scene, PropInfo owner, MissionInfo.SubMissionInfo c, JsonElement script) {
    var d = definition(scene.getPlaneId(), scene.getFloorId());
    if (d == null || !controller(scene, owner) || c == null || c.getId() != d.sub()
        || c.getMainMissionID() != d.main() || c.isBackTrack()
        || !c.getMissionJsonPath().equals("Config/Level/Mission/" + d.main() + "/Mission_" + d.sub() + ".json")
        || !c.getParamStr1().isEmpty() || c.getParamIntList().length != 0 || !c.getParamItemList().isEmpty())
      return false;
    if (d == SVAROG)
      return "AnySequence".equals(c.getTakeType())
          && Arrays.equals(c.getTakeParamIntList(), new Integer[] {101090227}) && SVAROG_SCRIPT.equals(script);
    return "Auto".equals(c.getTakeType()) && c.getTakeParamIntList().length == 0 && HILL_SCRIPT.equals(script);
  }

  private FollowupStoryGates() {}
}
