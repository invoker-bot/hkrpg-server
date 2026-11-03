package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/**
 * Ordinary-world consoles, door pairs and source-bound story interactions.
 * Readiness follows accepted, source-bound objectives.
 */
public final class StoryDoors {
  private static final String GRAPH =
      "Config/Level/Props/Chap00/InitLevelGraph_Prop_Chap00_Console01_OpenDoor_01.json";
  // This authored variant only changes its own state. Its unused shared values are not door links.
  private static final String STANDALONE_GRAPH =
      "Config/Level/Props/Chap00/InitLevelGraph_Prop_Chap00_Console01_OpenDoor_03.json";
  private static final String SNOWDRIFT_GRAPH =
      "Config/Level/Mission/1010101/Prop/Prop_NormalInteract_SnowBall.json";
  private static final String REPAIR_GRAPH =
      "Config/Level/Props/Chap01/InitLevelGraph_Prop_Chap01_CablePuzzle_03.json";
  private static final String SIMPLE_DOOR_GRAPH =
      "Config/Level/Props/Chap01/InitLevelGraph_Prop_Chap01_BaseConsole_01A_Control_SimpleDoor.json";
  private static final String IRON_GATE_GRAPH =
      "Config/Level/Props/Chap01/InitLevelGraph_Prop_Chap01_IronGate_06.json";
  private static final String SIMPLE_DOOR_KEY = "Maze_Prop_Chap01_BaseControllSimpleDoor";
  private static final String IRON_GATE_CONTROLLER_KEY = "Maze_Prop_Chap01_IronGate06Controller_ConfigID";
  private static final JsonElement REPAIR_BINDINGS = JsonParser.parseString("""
      {"Values":[
        {"$type":"RPG.GameCore.SharedString","Value":"Chap01_ADV_MachineHouse_LevelArea,CablePuzzleCam_01","Key":"Vcam"},
        {"$type":"RPG.GameCore.SharedString","Value":"Props/Outputs/Chap01/Chap01_Prop_CablePuzzle_01/Chap01_Prop_CablePuzzle_01.prefab","Key":"BoardFilePath"},
        {"$type":"RPG.GameCore.SharedString","Value":"12,300002","Key":"Target"}]}
      """);
  private static final String DOOR_KEY = "Maze_Prop_Chap01_Door";
  private static final String ROOT = "Config/Level/Props/Chap00/InitLevelGraph_Prop_Chap00_";
  private static final String KEY_DOOR = ROOT + "Console01_OpenDoor_02.json";
  private static final String BRIDGE = ROOT + "Console01_OpenBridge_04.json";
  private static final String BRIDGE_SIMPLE = ROOT + "Console01_OpenBridge_03.json";
  private static final String PRIVATE_LIFT = ROOT + "Console01_Elevator_01.json";
  private static final Set<String> PAIRED_GRAPHS = Set.of(GRAPH, KEY_DOOR, BRIDGE, BRIDGE_SIMPLE);
  private static final Set<String> KEY_LIFTS = Set.of(
      ROOT + "Elevator_Console_1F.json", ROOT + "Elevator_Console_2F.json");
  private static final String LIFT_KEY = "Maze_Chap00_N201_Prop_ELevator";
  // The exported task omits its target. Only a matching PropState objective supplies that target;
  // this is deliberately not a general ChangePropState interpreter.
  private static final JsonElement ENABLE_TASK =
      JsonParser.parseString(
          "{\"OnInitSequece\":[],\"OnStartSequece\":[{\"TaskList\":[{\"$type\":\"RPG.GameCore.ChangePropState\"}]}],\"Type\":\"Mission\"}");
  // Three parallel sequences: proximity speech, a delayed reminder, and independent enable.
  // The signal belongs to the objective; neither speech sequence can change world state.
  private static final String PROXIMITY_HINT_TASK = """
      {"OnInitSequece":[],"OnStartSequece":[
        {"TaskList":[{"$type":"RPG.GameCore.PropSetupTrigger","TargetType":{
          "$type":"RPG.GameCore.TargetFetchAdvPropEx","FetchType":"SinglePropByPropID",
          "SinglePropID":{"GroupID":{"IsDynamic":false,"FixedValue":{"Value":%d}},
                          "ID":{"IsDynamic":false,"FixedValue":{"Value":%d}}}},
          "TargetGroupID":{"IsDynamic":false,"FixedValue":{"Value":0}},
          "TargetID":{"IsDynamic":false,"FixedValue":{"Value":0}},"TargetTypes":[],
          "ColliderRelativePath":"Trigger","DestroyAfterTriggered":true,
          "OnTriggerEnter":[%s,{"$type":"RPG.GameCore.TriggerCustomString",
            "CustomString":{"Value":"%d_MissionTalk"}}]}]},
        {"TaskList":[{"$type":"RPG.GameCore.WaitCustomString",
          "CustomString":{"Value":"%d_MissionTalk"}},
          {"$type":"RPG.GameCore.WaitSecond","WaitTime":{"IsDynamic":false,"FixedValue":{"Value":%s}}},%s]},
        {"TaskList":[{"$type":"RPG.GameCore.ChangePropState"}]}
      ],"Type":"Mission"}
      """;
  // A separate authored flow fades out, asks Locked -> Closed, then accepts the next
  // performance. Match its complete literal script and objective, not a mission ID.
  private static final String UNLOCK_TASK = """
      {"OnInitSequece":[],"OnStartSequece":[{"TaskList":[
        {"$type":"RPG.GameCore.PlayScreenTransfer","Mode":"SwitchOut"},
        {"$type":"RPG.GameCore.PropReqInteract","TargetType":{
          "$type":"RPG.GameCore.TargetFetchAdvPropEx","FetchType":"SinglePropByPropID",
          "SinglePropID":{"GroupID":{"IsDynamic":false,"FixedValue":{"Value":%d}},
                          "ID":{"IsDynamic":false,"FixedValue":{"Value":%d}}}},
         "InteractID":{"IsDynamic":false,"FixedValue":{"Value":1200}}}
      ]}],"Type":"Mission"}
      """;

  private record Link(int group, int instance) {}

  public static boolean controller(PropInfo info) {
    return paired(info) || standalone(info) || repeatable(info);
  }

  public static boolean retainsSource(PropInfo info) {
    return controller(info) || ironGate(info);
  }

  private static boolean simpleDoorConsole(PropInfo info) {
    return info != null && info.getPropID() == 101001 && SIMPLE_DOOR_GRAPH.equals(info.getInitLevelGraph());
  }

  private static boolean ironGate(PropInfo info) {
    return info != null && info.getPropID() == 101004 && IRON_GATE_GRAPH.equals(info.getInitLevelGraph());
  }

  private static boolean paired(PropInfo info) {
    return simpleDoorConsole(info) || BackwaterStoryConsole.matches(info)
        || info != null && info.getPropID() == 100000
        && info.getInitLevelGraph() != null
        && PAIRED_GRAPHS.contains(info.getInitLevelGraph());
  }

  private static boolean keyDoor(PropInfo info) {
    return info != null && info.getPropID() == 100000 && KEY_DOOR.equals(info.getInitLevelGraph());
  }

  private static boolean keyLift(PropInfo info) {
    return info != null && info.getPropID() == 100000 && info.getInitLevelGraph() != null
        && KEY_LIFTS.contains(info.getInitLevelGraph());
  }

  private static boolean repeatable(PropInfo info) {
    return keyLift(info) || info != null && info.getPropID() == 100022
        && PRIVATE_LIFT.equals(info.getInitLevelGraph());
  }

  private static String bindingKey(PropInfo info) {
    if (keyLift(info)) return LIFT_KEY;
    if (!paired(info)) return null;
    if (BackwaterStoryConsole.matches(info)) return "TargetDoor";
    if (simpleDoorConsole(info) || BRIDGE.equals(info.getInitLevelGraph())) return SIMPLE_DOOR_KEY;
    if (BRIDGE_SIMPLE.equals(info.getInitLevelGraph())) return "Maze_Prop_Chap00_BaseControllSimpleDoor";
    return DOOR_KEY;
  }

  private static boolean standalone(PropInfo info) {
    return snowdrift(info) || repairMachine(info)
        || info != null && info.getPropID() == 100000 && STANDALONE_GRAPH.equals(info.getInitLevelGraph());
  }

  private static boolean snowdrift(PropInfo info) {
    return info != null && info.getPropID() == 301000 && SNOWDRIFT_GRAPH.equals(info.getInitLevelGraph());
  }

  private static boolean repairMachine(PropInfo info) {
    return info != null && info.getPropID() == 301058 && REPAIR_GRAPH.equals(info.getInitLevelGraph());
  }

  static boolean world(Scene scene) {
    return scene != null
        && (scene.getPlaneType() == PlaneType.Town || scene.getPlaneType() == PlaneType.Maze)
        && scene.getDimensionId() == 0
        && scene.getStoryLineId() == 0
        && !scene.getPlayer().getGameplay().getStoryLines().isTransitionPending();
  }

  private static Link link(PropInfo info) {
    return link(info, bindingKey(info));
  }

  private static Link link(PropInfo info, String key) {
    if (key == null
        || info.getValueSource() == null
        || info.getValueSource().getValues() == null) return null;
    try {
      var values =
          info.getValueSource().getValues().stream()
              .filter(v -> v != null && v.has("Key") && key.equals(v.get("Key").getAsString()))
              .toList();
      if (values.size() != 1) return null;
      if ((simpleDoorConsole(info) || BackwaterStoryConsole.matches(info) || ironGate(info))
          && (!values.getFirst().has("$type")
              || !"RPG.GameCore.SharedString".equals(values.getFirst().get("$type").getAsString()))) return null;
      var value = values.getFirst().get("Value");
      if (value == null
          || !value.isJsonPrimitive()
          || !value.getAsJsonPrimitive().isString()
          || !value.getAsString().matches("[1-9][0-9]*,[1-9][0-9]*")) return null;
      var parts = value.getAsString().split(",");
      return new Link(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  private static PropInfo original(Scene scene, int group, int instance) {
    var definition = scene.getResourceGroups().get(group);
    if (definition == null || definition.getPropList() == null) return null;
    var matches = definition.getPropList().stream().filter(p -> p.getID() == instance).toList();
    if (matches.size() != 1) return null;
    var p = matches.getFirst();
    return p.isIsDelete() || p.isIsClientOnly() || !p.isLoadOnInitial() ? null : p;
  }

  private static List<PropInfo> controllers(Scene scene, int group, int instance) {
    var found = new ArrayList<PropInfo>();
    for (var definition : scene.getResourceGroups().values()) {
      if (definition.getPropList() == null) continue;
      for (var p : definition.getPropList()) {
        var link = link(p);
        if (paired(p) && link != null
            && link.group() == group
            && link.instance() == instance
            && original(scene, definition.getId(), p.getID()) == p) found.add(p);
      }
    }
    return found;
  }

  private static boolean objective(
      Scene scene, int group, PropInfo info, MissionInfo.SubMissionInfo c) {
    if (!observes(scene, group, info, c)) return false;
    if (BackwaterStoryConsole.matches(info)) return BackwaterStoryConsole.objective(scene, group, info, c);
    if (c.getTask() == null) return false;
    try {
      var script = JsonParser.parseString(c.getTask().getSourceDefinition());
      if (snowdrift(info))
        return info.getState() == PropState.Locked && group == 25 && info.getID() == 300001
            && c.getId() == 101010108 && c.getMainMissionID() == 1010101
            && "Config/Level/Mission/1010101/Mission_101010108.json".equals(c.getMissionJsonPath())
            && "AnySequence".equals(c.getTakeType())
            && Arrays.equals(c.getTakeParamIntList(), new Integer[] {101010120})
            && c.getParamStr1().isEmpty() && c.getParamIntList().length == 0
            && c.getParamItemList().isEmpty()
            && StoryConsoleTasks.enablesSnowdrift(scene, script);
      // This client-owned circuit puzzle reports 1010 only after its finish callback.
      // Enable its authored board; accepting the objective must never solve it.
      if (repairMachine(info))
        return scene.getPlaneId() == 10101 && scene.getFloorId() == 10101007
            && info.getState() == PropState.Locked && group == 12 && info.getID() == 300002
            && c.getId() == 101020109 && c.getMainMissionID() == 1010206
            && "Config/Level/Mission/1010201/Mission_101020109.json".equals(c.getMissionJsonPath())
            && "AnySequence".equals(c.getTakeType())
            && Arrays.equals(c.getTakeParamIntList(), new Integer[] {101020108})
            && c.getParamStr1().isEmpty() && c.getParamIntList().length == 0
            && c.getParamItemList().isEmpty() && ENABLE_TASK.equals(script)
            && REPAIR_BINDINGS.equals(ResourceLoader.getGson().toJsonTree(info.getValueSource()));
      return ENABLE_TASK.equals(script)
          || standalone(info) && enableWithDialogue(script)
          || !keyDoor(info) && !keyLift(info) && (StoryConsoleTasks.enables(scene, script)
              || enableWithDelayedHint(script) || enableWithProximityHints(scene, c, script));
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private static boolean observes(Scene scene, int group, PropInfo info, MissionInfo.SubMissionInfo c) {
    return !(c == null || info == null
        || !"PropState".equals(c.getFinishType())
        || !"Equal".equals(c.getParamType())
        || c.getLevelPlaneID() != scene.getPlaneId()
        || c.getLevelFloorID() != scene.getFloorId()
        || c.getMazePlaneID() != scene.getPlaneId()
        || c.getMazeFloorID() != scene.getFloorId()
        || c.getParamInt1() != group
        || c.getParamInt2() != info.getID()
        || c.getParamInt3() != 1
        || c.getProgress() != 1);
  }

  /** A parallel literal reminder cannot gate the separate, unconditional console enable. */
  private static boolean enableWithDelayedHint(JsonElement script) {
    var copy = script.getAsJsonObject().deepCopy();
    var sequences = copy.getAsJsonArray("OnStartSequece");
    if (sequences == null || sequences.size() != 2) return false;
    var hint = sequences.get(0).getAsJsonObject();
    if (!hint.keySet().equals(Set.of("TaskList"))) return false;
    var tasks = hint.getAsJsonArray("TaskList");
    if (tasks == null || tasks.size() != 2 || !hintTalk(tasks.get(1))) return false;
    var wait = tasks.get(0);
    var duration = wait.getAsJsonObject().getAsJsonObject("WaitTime")
        .getAsJsonObject("FixedValue").get("Value");
    if (!literalDuration(duration) || !JsonParser.parseString("""
        {"$type":"RPG.GameCore.WaitSecond",
         "WaitTime":{"IsDynamic":false,"FixedValue":{"Value":%s}}}
        """.formatted(duration)).equals(wait)) return false;
    sequences.remove(0);
    return ENABLE_TASK.equals(copy);
  }

  private static boolean enableWithProximityHints(
      Scene scene, MissionInfo.SubMissionInfo objective, JsonElement script) {
    var sequences = script.getAsJsonObject().getAsJsonArray("OnStartSequece");
    if (sequences.size() != 3) return false;
    var trigger = sequences.get(0).getAsJsonObject().getAsJsonArray("TaskList")
        .get(0).getAsJsonObject();
    var target = trigger.getAsJsonObject("TargetType").getAsJsonObject("SinglePropID");
    int group = literalPositiveId(target.getAsJsonObject("GroupID").getAsJsonObject("FixedValue").get("Value"));
    int instance = literalPositiveId(target.getAsJsonObject("ID").getAsJsonObject("FixedValue").get("Value"));
    var original = original(scene, group, instance);
    if (original == null || original.getPropID() != 100) return false;
    var firstTalk = trigger.getAsJsonArray("OnTriggerEnter").get(0);
    var reminder = sequences.get(1).getAsJsonObject().getAsJsonArray("TaskList");
    var wait = reminder.get(1).getAsJsonObject().getAsJsonObject("WaitTime")
        .getAsJsonObject("FixedValue").get("Value");
    var secondTalk = reminder.get(2);
    if (!literalDuration(wait) || !hintTalk(firstTalk) || !hintTalk(secondTalk)) return false;
    return JsonParser.parseString(PROXIMITY_HINT_TASK.formatted(
        group, instance, firstTalk, objective.getId(), objective.getId(), wait, secondTalk)).equals(script);
  }

  private static int literalPositiveId(JsonElement value) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return 0;
    int id = value.getAsBigDecimal().intValueExact();
    return id > 0 ? id : 0;
  }

  private static boolean literalDuration(JsonElement value) {
    return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
        && value.getAsBigDecimal().signum() >= 0;
  }

  private static boolean hintTalk(JsonElement value) {
    var talk = value.getAsJsonObject();
    if (!talk.keySet().equals(Set.of("$type", "SimpleTalkList"))
        || !"RPG.GameCore.PlayMissionTalk".equals(talk.get("$type").getAsString())) return false;
    var lines = talk.getAsJsonArray("SimpleTalkList");
    if (lines.size() != 1) return false;
    var row = lines.get(0).getAsJsonObject();
    return row.keySet().equals(Set.of("TalkSentenceID", "ProtectTime", "ForceToNextTime"))
        && literalPositiveId(row.get("TalkSentenceID")) > 0
        && literalDuration(row.get("ProtectTime")) && literalDuration(row.get("ForceToNextTime"));
  }

  /** Allow only an unconditional enable plus parallel, literal ambient speech; no script effects. */
  private static boolean enableWithDialogue(JsonElement script) {
    if (!script.isJsonObject()) return false;
    var copy = script.getAsJsonObject().deepCopy();
    var sequences = copy.getAsJsonArray("OnStartSequece");
    if (sequences == null || sequences.size() != 2) return false;
    var sequence = sequences.get(1).getAsJsonObject();
    if (!sequence.keySet().equals(Set.of("TaskList"))) return false;
    var tasks = sequence.getAsJsonArray("TaskList");
    if (tasks == null || tasks.size() != 1) return false;
    var talk = tasks.get(0).getAsJsonObject();
    if (!talk.keySet().equals(Set.of("$type", "SimpleTalkList"))
        || !"RPG.GameCore.PlayMissionTalk".equals(talk.get("$type").getAsString())) return false;
    var lines = talk.getAsJsonArray("SimpleTalkList");
    if (lines == null || lines.isEmpty()) return false;
    for (var line : lines) {
      var row = line.getAsJsonObject();
      if (!row.keySet().equals(Set.of("TalkSentenceID", "ProtectTime"))) return false;
      var id = row.getAsJsonPrimitive("TalkSentenceID");
      var duration = row.getAsJsonPrimitive("ProtectTime");
      if (!id.isNumber() || id.getAsBigDecimal().intValueExact() <= 0
          || !duration.isNumber() || duration.getAsBigDecimal().signum() < 0) return false;
    }
    sequences.remove(1);
    return ENABLE_TASK.equals(copy);
  }

  private static List<MissionInfo.SubMissionInfo> objectives(
      Scene scene, int group, PropInfo info) {
    return GameplayDefinitions.subs.values().stream()
        .filter(c -> objective(scene, group, info, c))
        .toList();
  }

  private static boolean unlockObjective(Scene scene, int group, PropInfo info,
      MissionInfo.SubMissionInfo c) {
    if (c == null || !paired(info) || info.getState() != PropState.Locked
        || !"PropState".equals(c.getFinishType()) || !"Equal".equals(c.getParamType())
        || c.getLevelPlaneID() != scene.getPlaneId() || c.getLevelFloorID() != scene.getFloorId()
        || c.getMazePlaneID() != scene.getPlaneId() || c.getMazeFloorID() != scene.getFloorId()
        || c.getParamInt1() != group || c.getParamInt2() != info.getID()
        || c.getParamInt3() != PropState.Closed.getVal() || c.getProgress() != 1
        || c.getTask() == null) return false;
    try {
      return JsonParser.parseString(UNLOCK_TASK.formatted(group, info.getID()))
          .equals(JsonParser.parseString(c.getTask().getSourceDefinition()));
    } catch (RuntimeException invalid) { return false; }
  }

  private static boolean unlockReady(Scene scene, int group, PropInfo info) {
    return GameplayDefinitions.subs.values().stream().anyMatch(c ->
        unlockObjective(scene, group, info, c)
            && doing(scene, c));
  }

  private static boolean doing(Scene scene, MissionInfo.SubMissionInfo c) {
    return MissionStateRecords.doing(scene.getPlayer().getMissionManager().sub(c.getId()), c.getId(), 1)
        && scene.getPlayer().getMissionManager().canFinishFromAction(c.getId());
  }

  private static boolean unlocked(Scene scene, int group, PropInfo info) {
    return GameplayDefinitions.subs.values().stream().anyMatch(c ->
        unlockObjective(scene, group, info, c)
            && (MissionStateRecords.subCompleted(scene.getPlayer(), c.getId())
                || doing(scene, c) && receipt(scene, group, info, PropState.Closed)));
  }

  private static boolean completed(Scene scene, int group, PropInfo info) {
    return objectives(scene, group, info).stream()
        .anyMatch(c -> MissionStateRecords.subCompleted(scene.getPlayer(), c.getId()));
  }

  private static boolean ready(Scene scene, int group, PropInfo info) {
    var player = scene.getPlayer();
    return objectives(scene, group, info).stream()
        .anyMatch(
            c ->
                MissionStateRecords.doing(player.getMissionManager().sub(c.getId()), c.getId(), 1)
                    && player.getMissionManager().canFinishFromAction(c.getId()));
  }

  private static boolean receipt(Scene scene, int group, PropInfo info) {
    return receipt(scene, group, info, PropState.Open);
  }

  private static boolean receipt(Scene scene, int group, PropInfo info, PropState state) {
    var record =
        scene
            .getPlayer()
            .getGameplay()
            .getScenePropStates()
            .get(PropTimelines.key(scene, group, info.getID()));
    return record != null
        && record.getProp() == info.getPropID()
        && record.getState() == state.getVal()
        && record.getAcceptance().isEmpty()
        && record.getSource().equals(PropTimelines.source(info));
  }

  private static boolean pairReceipt(Scene scene, int group, PropInfo info) {
    var link = link(info);
    if (link == null) return false;
    var door = original(scene, link.group(), link.instance());
    return door != null && receipt(scene, group, info) && receipt(scene, link.group(), door);
  }

  private static PropState state(Scene scene, int group, PropInfo info) {
    // A lift console is a reusable switch. Finishing its first-use objective must not pin it Open.
    if (repeatable(info)) {
      if (receipt(scene, group, info, PropState.Closed)) return PropState.Closed;
      if (receipt(scene, group, info, PropState.Open)) return PropState.Open;
      return info.getState() == PropState.Locked && !ready(scene, group, info)
          && !completed(scene, group, info) ? PropState.Locked : PropState.Closed;
    }
    if (completed(scene, group, info)) return PropState.Open;
    if (info.getState() == PropState.Locked && !ready(scene, group, info)
        && !BackwaterStoryConsole.introductionReady(scene, group, info)
        && !unlocked(scene, group, info)) return PropState.Locked;
    if (standalone(info) ? receipt(scene, group, info) : pairReceipt(scene, group, info))
      return PropState.Open;
    if (GRAPH.equals(info.getInitLevelGraph()) && info.getState() == PropState.Closed) {
      var old =
          scene
              .getPlayer()
              .getGameplay()
              .getScenePropStates()
              .get(PropTimelines.key(scene, group, info.getID()));
      if (old != null
          && old.getProp() == info.getPropID()
          && old.getState() == 1
          && old.getAcceptance().isEmpty()
          && legacySource(old, info)) return PropState.Open;
    }
    return info.getState() == PropState.Locked ? PropState.Closed : info.getState();
  }

  /** The former loader changed every 100000 definition to state 5 and discarded its bindings. */
  static boolean legacySource(ScenePropState.Snapshot record, PropInfo info) {
    if (record == null || !(info.getPropID() == 100000 || simpleDoorConsole(info)
        || BackwaterStoryConsole.matches(info) || ironGate(info))) return false;
    var copy =
        ResourceLoader.getGson().fromJson(ResourceLoader.getGson().toJson(info), PropInfo.class);
    if (info.getPropID() == 100000) copy.setState(PropState.BridgeState3);
    copy.setValueSource(null);
    return record.getSource().equals(PropTimelines.source(copy));
  }

  public static void restore(EntityProp prop) {
    var scene = prop.getScene();
    if (!world(scene)) return;
    var info = original(scene, prop.getGroupId(), prop.getInstId());
    if (info == null || prop.getPropInfo() != info) return;
    if (controller(info)) {
      prop.setState(state(scene, prop.getGroupId(), info), false);
    } else if (prop.getExcel().isDoor() || ironGate(info) || prop.getPropType() == PropType.PROP_PLATFORM) {
      var owners = controllers(scene, prop.getGroupId(), prop.getInstId());
      if (owners.isEmpty()) return;
      boolean open =
          owners.stream().anyMatch(p -> state(scene, p.getGroup().getId(), p) == PropState.Open);
      prop.setState(open ? PropState.Open : info.getState(), false);
    }
  }

  public static void reconcile(Scene scene) {
    if (!world(scene)) return;
    for (var entity : List.copyOf(scene.getEntities().values())) {
      if (!(entity instanceof EntityProp prop)) continue;
      var before = prop.getState();
      restore(prop);
      if (scene.isLoaded() && before != prop.getState())
        scene
            .getPlayer()
            .sendPacket(
                new emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify(
                    scene, prop, null));
    }
  }

  static Retcode validate(EntityProp prop, int config) {
    var scene = prop.getScene();
    if (!world(scene)) return Retcode.SUCC;
    var info = original(scene, prop.getGroupId(), prop.getInstId());
    if (info == null)
      return Retcode.SUCC; // The shared transition validator checks original identity.
    if (!controller(info))
      return validateTarget(prop, config);
    if (BackwaterStoryConsole.matches(info) && !ready(scene, prop.getGroupId(), info))
      return Retcode.INTERACT_TYPE_INVALID;
    var link = link(info);
    if (repeatable(info)) {
      var current = state(scene, prop.getGroupId(), info);
      boolean valid = config == 1010 && current == PropState.Closed
          || config == 1100 && current == PropState.Open
          || keyLift(info) && config == 3200 && current == PropState.Locked;
      if (!valid) return Retcode.INTERACT_TYPE_INVALID;
      return keyLift(info) && !validLink(scene, info, link) ? Retcode.REQ_PARA_INVALID : Retcode.SUCC;
    }
    boolean unlock = config == 1200 && prop.getState() == PropState.Locked
        && unlockReady(scene, prop.getGroupId(), info)
        && GameData.getInteractExcelMap().get(config).getTargetState() == PropState.Closed;
    boolean open = config == (keyDoor(info) ? keyInteraction(info) : 1010)
        && state(scene, prop.getGroupId(), info) == PropState.Closed;
    if (standalone(info)) return open ? Retcode.SUCC : Retcode.INTERACT_TYPE_INVALID;
    if ((!unlock && !open) || link == null)
      return Retcode.INTERACT_TYPE_INVALID;
    if (!validLink(scene, info, link))
      return Retcode.REQ_PARA_INVALID;
    return Retcode.SUCC;
  }

  private static boolean validLink(Scene scene, PropInfo owner, Link link) {
    if (link == null) return false;
    var source = original(scene, link.group(), link.instance());
    var target = actor(scene, link);
    return source != null && target != null && target.getPropInfo() == source
        && (simpleDoorConsole(owner) || BackwaterStoryConsole.matches(owner) ? ironGate(source)
            && new Link(owner.getGroup().getId(), owner.getID()).equals(link(source, IRON_GATE_CONTROLLER_KEY))
            && target.getExcel().getPropStateList().contains(PropState.Open)
            : keyLift(owner) ? target.getPropType() == PropType.PROP_ELEVATOR
            && source.getPropID() == 100007
            && (ROOT + "Elevator_Move_01.json").equals(source.getInitLevelGraph())
            : (DOOR_KEY.equals(bindingKey(owner)) ? target.getExcel().isDoor()
                : target.getPropType() == PropType.PROP_PLATFORM && source.getPropID() == 100013)
                && target.getExcel().getPropStateList().contains(PropState.Open));
  }

  private static Retcode validateTarget(EntityProp prop, int config) {
    var scene = prop.getScene();
    if ((prop.getExcel().isDoor() || ironGate(prop.getPropInfo()) || prop.getPropType() == PropType.PROP_PLATFORM)
        && !controllers(scene, prop.getGroupId(), prop.getInstId()).isEmpty())
      return Retcode.INTERACT_TYPE_INVALID;
    if (prop.getPropType() != PropType.PROP_ELEVATOR) return Retcode.SUCC;
    var owners = new ArrayList<PropInfo>();
    for (var group : scene.getResourceGroups().values()) for (var source : group.getPropList()) {
      var link = link(source);
      if (keyLift(source) && link != null && link.group() == prop.getGroupId()
          && link.instance() == prop.getInstId()) owners.add(source);
    }
    if (owners.isEmpty()) return Retcode.SUCC;
    if (config != 114150 && config != 115140) return Retcode.INTERACT_TYPE_INVALID;
    return owners.stream().anyMatch(p -> validLink(scene, p, link(p))
        && state(scene, p.getGroup().getId(), p) != PropState.Locked)
        ? Retcode.SUCC : Retcode.INTERACT_TYPE_INVALID;
  }

  private static int keyInteraction(PropInfo info) {
    if (!keyDoor(info) || info.getValueSource() == null || info.getValueSource().getValues() == null) return 0;
    try {
      var matches = info.getValueSource().getValues().stream().filter(v -> v != null
          && v.has("Key") && "Maze_Prop_Chap00_CostItem_InteractID".equals(v.get("Key").getAsString())).toList();
      if (matches.size() != 1 || !"RPG.GameCore.SharedInt".equals(matches.getFirst().get("$type").getAsString())) return 0;
      return literalPositiveId(matches.getFirst().get("Value"));
    } catch (RuntimeException invalid) { return 0; }
  }

  /** This exported key is checked, never consumed. No arbitrary cost type is accepted here. */
  static Retcode checkCost(EntityProp prop, int config) {
    var scene = prop.getScene();
    var info = original(scene, prop.getGroupId(), prop.getInstId());
    var row = GameData.getInteractExcelMap().get(config);
    if (!world(scene) || info != prop.getPropInfo() || row == null
        || !(keyDoor(info) && config == 3016 && keyInteraction(info) == config
            || keyLift(info) && config == 3200)
        || row.getSrcState() != (keyDoor(info) ? PropState.Closed : PropState.Locked)
        || row.getTargetState() != (keyDoor(info) ? PropState.Open : PropState.Closed)
        || !"CheckItem".equals(row.getInteractCostType()) || row.getItemCostList() == null
        || !row.getItemCostList().equals(List.of(new emu.lunarcore.data.excel.InteractExcel.InteractCost(140037, 1))))
      return Retcode.INTERACT_TYPE_INVALID;
    var validation = validate(prop, config);
    if (validation != Retcode.SUCC) return validation;
    var inventory = scene.getPlayer().getInventory();
    var key = inventory == null ? null : inventory.getMaterialByItemId(140037);
    return key != null && key.getItemId() == 140037 && key.getCount() >= 1
        ? Retcode.SUCC : Retcode.ITEM_NOT_ENOUGH;
  }

  private static EntityProp actor(Scene scene, Link link) {
    var matches =
        scene.getEntitiesByGroup(EntityProp.class, link.group()).stream()
            .filter(p -> p.getInstId() == link.instance())
            .toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  static void transitions(EntityProp prop, Map<EntityProp, PropState> targets) {
    if (!world(prop.getScene()) || !paired(prop.getPropInfo())
        || targets.get(prop) != PropState.Open) return;
    var door = actor(prop.getScene(), link(prop.getPropInfo()));
    if (door != null) targets.put(door, PropState.Open);
  }

  static boolean satisfied(Player player, MissionInfo.SubMissionInfo c) {
    var scene = player.getScene();
    if (!world(scene)) return false;
    var info = original(scene, c.getParamInt1(), c.getParamInt2());
    if (unlockObjective(scene, c.getParamInt1(), info, c))
      return unlockReady(scene, c.getParamInt1(), info)
          && receipt(scene, c.getParamInt1(), info, PropState.Closed);
    return controller(info)
        && (objective(scene, c.getParamInt1(), info, c)
            || !snowdrift(info) && !repairMachine(info)
                && c.getTask() == null && "".equals(c.getMissionJsonPath())
                && observes(scene, c.getParamInt1(), info, c) && doing(scene, c))
        && (standalone(info) || repeatable(info)
            ? doing(scene, c) && receipt(scene, c.getParamInt1(), info)
            : pairReceipt(scene, c.getParamInt1(), info));
  }

  private StoryDoors() {}
}
