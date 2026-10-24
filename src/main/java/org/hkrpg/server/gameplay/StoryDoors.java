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
 * Ordinary-world consoles and door pairs. Readiness follows accepted, source-bound objectives.
 */
public final class StoryDoors {
  private static final String GRAPH =
      "Config/Level/Props/Chap00/InitLevelGraph_Prop_Chap00_Console01_OpenDoor_01.json";
  // This authored variant only changes its own state. Its unused shared values are not door links.
  private static final String STANDALONE_GRAPH =
      "Config/Level/Props/Chap00/InitLevelGraph_Prop_Chap00_Console01_OpenDoor_03.json";
  private static final String DOOR_KEY = "Maze_Prop_Chap01_Door";
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
    return paired(info) || standalone(info);
  }

  private static boolean paired(PropInfo info) {
    return info != null && info.getPropID() == 100000 && GRAPH.equals(info.getInitLevelGraph());
  }

  private static boolean standalone(PropInfo info) {
    return info != null && info.getPropID() == 100000 && STANDALONE_GRAPH.equals(info.getInitLevelGraph());
  }

  static boolean world(Scene scene) {
    return scene != null
        && (scene.getPlaneType() == PlaneType.Town || scene.getPlaneType() == PlaneType.Maze)
        && scene.getDimensionId() == 0
        && scene.getStoryLineId() == 0
        && !scene.getPlayer().getGameplay().getStoryLines().isTransitionPending();
  }

  private static Link link(PropInfo info) {
    if (!paired(info)
        || info.getValueSource() == null
        || info.getValueSource().getValues() == null) return null;
    try {
      var values =
          info.getValueSource().getValues().stream()
              .filter(v -> v != null && v.has("Key") && DOOR_KEY.equals(v.get("Key").getAsString()))
              .toList();
      if (values.size() != 1) return null;
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
        if (link != null
            && link.group() == group
            && link.instance() == instance
            && original(scene, definition.getId(), p.getID()) == p) found.add(p);
      }
    }
    return found;
  }

  private static boolean objective(
      Scene scene, int group, PropInfo info, MissionInfo.SubMissionInfo c) {
    if (c == null
        || !"PropState".equals(c.getFinishType())
        || !"Equal".equals(c.getParamType())
        || c.getLevelPlaneID() != scene.getPlaneId()
        || c.getLevelFloorID() != scene.getFloorId()
        || c.getMazePlaneID() != scene.getPlaneId()
        || c.getMazeFloorID() != scene.getFloorId()
        || c.getParamInt1() != group
        || c.getParamInt2() != info.getID()
        || c.getParamInt3() != 1
        || c.getProgress() != 1
        || c.getTask() == null) return false;
    try {
      var script = JsonParser.parseString(c.getTask().getSourceDefinition());
      return ENABLE_TASK.equals(script)
          || standalone(info) && enableWithDialogue(script)
          || paired(info) && (enableWithDelayedHint(script) || enableWithProximityHints(scene, c, script));
    } catch (RuntimeException invalid) {
      return false;
    }
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
    if (completed(scene, group, info)) return PropState.Open;
    if (info.getState() == PropState.Locked && !ready(scene, group, info)
        && !unlocked(scene, group, info)) return PropState.Locked;
    if (standalone(info) ? receipt(scene, group, info) : pairReceipt(scene, group, info))
      return PropState.Open;
    if (info.getState() == PropState.Closed) {
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
    if (record == null || info.getPropID() != 100000) return false;
    var copy =
        ResourceLoader.getGson().fromJson(ResourceLoader.getGson().toJson(info), PropInfo.class);
    copy.setState(PropState.BridgeState3);
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
    } else if (prop.getExcel().isDoor()) {
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
      return prop.getExcel().isDoor()
              && !controllers(scene, prop.getGroupId(), prop.getInstId()).isEmpty()
          ? Retcode.INTERACT_TYPE_INVALID
          : Retcode.SUCC;
    var link = link(info);
    boolean unlock = config == 1200 && prop.getState() == PropState.Locked
        && unlockReady(scene, prop.getGroupId(), info)
        && GameData.getInteractExcelMap().get(config).getTargetState() == PropState.Closed;
    boolean open = config == 1010 && state(scene, prop.getGroupId(), info) == PropState.Closed;
    if (standalone(info)) return open ? Retcode.SUCC : Retcode.INTERACT_TYPE_INVALID;
    if ((!unlock && !open) || link == null)
      return Retcode.INTERACT_TYPE_INVALID;
    var doorInfo = original(scene, link.group(), link.instance());
    var door = actor(scene, link);
    if (doorInfo == null
        || door == null
        || door.getPropInfo() != doorInfo
        || !door.getExcel().isDoor()
        || !door.getExcel().getPropStateList().contains(PropState.Open))
      return Retcode.REQ_PARA_INVALID;
    return Retcode.SUCC;
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
        && objective(scene, c.getParamInt1(), info, c)
        && (standalone(info)
            ? doing(scene, c) && receipt(scene, c.getParamInt1(), info)
            : pairReceipt(scene, c.getParamInt1(), info));
  }

  private StoryDoors() {}
}
