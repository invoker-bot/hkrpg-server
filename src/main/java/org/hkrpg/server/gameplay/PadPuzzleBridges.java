package org.hkrpg.server.gameplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify;
import java.util.*;

/** The pad puzzle's original finish callback opens its separately placed bridge. */
public final class PadPuzzleBridges {
  private static final String PUZZLE = "Config/Level/Maze/MazeExtra/PadsPuzzle/Padpuzzle_Common.json";
  private static final String BRIDGE = "Config/Level/Props/Chap00/InitLevelGraph_Prop_Chap00_PadBridge_01.json";
  // Audited original placements. Before bindings were retained, their values were
  // absent from the saved source hash, so legacy receipts need this exact mapping.
  private static final String LEVELS = "Props/Outputs/Chap00/Chap00_Prop_Pad/Levels/";
  private static final Map<String, List<String>> LEGACY_BINDINGS = Map.of(
      "20002:20002001:26:300002", List.of("3,300039", LEVELS + "Chap00_Prop_Pad_Lv1.prefab"),
      "20002:20002001:32:300002", List.of("3,300040", LEVELS + "Chap00_Prop_Pad_Lv2.prefab"),
      "20002:20002001:33:300002", List.of("3,300041", LEVELS + "Chap00_Prop_Pad_Lv3.prefab"),
      "20003:20003001:32:300002", List.of("1,300025", LEVELS + "Chap00_Prop_Pad_N3_Lv1.prefab"),
      "20003:20003001:41:300002", List.of("1,300025", LEVELS + "Chap00_Prop_Pad_N3_Lv2.prefab"),
      "20003:20003001:42:300002", List.of("1,300025", LEVELS + "Chap00_Prop_Pad_N3_Lv3.prefab"),
      "20003:20003001:43:300002", List.of("1,300025", LEVELS + "Chap00_Prop_Pad_N3_Lv4.prefab"),
      "20004:20004001:5:300006", List.of("5,300001", LEVELS + "Chap00_Prop_Pad_N4.prefab"));

  private record Link(GroupInfo group, PropInfo prop) {}

  private static final int SUPPLY_PLANE = 20003, SUPPLY_FLOOR = 20003001;
  private static final int SHARED_GROUP = 1, SHARED_BRIDGE = 300025, BRIDGE_PROP = 300004;
  private static final int BOARD_INSTANCE = 300002;
  private static final int CHEST_GROUP = 18;
  private record RepeatBoard(int group, int reset, int reveal, int chest, int mapping) {}
  private static final List<RepeatBoard> REPEAT_BOARDS = List.of(
      new RepeatBoard(43, 200030400, 200030402, 300012, 20003659),
      new RepeatBoard(42, 200030300, 200030302, 300011, 20003658),
      new RepeatBoard(41, 200030200, 200030201, 300010, 20003657));
  // The client graph omits the server task parameters; the matching PropState objective has them.
  private static final JsonElement RESET_SCRIPT = JsonParser.parseString("""
      {"OnInitSequece":[],"OnStartSequece":[{"TaskList":[
        {"$type":"RPG.GameCore.ChangePropState"}]}],"Type":"Mission"}
      """);

  private static boolean world(Scene scene) {
    return scene != null && (scene.getPlaneType() == PlaneType.Town || scene.getPlaneType() == PlaneType.Maze)
        && scene.getDimensionId() == 0 && scene.getStoryLineId() == 0
        && scene.getPlayer().getInstance() == null
        && !scene.getPlayer().getGameplay().getStoryLines().isTransitionPending();
  }

  private static boolean puzzle(PropInfo info) {
    return info != null && info.getPropID() == 100 && PUZZLE.equals(info.getInitLevelGraph());
  }

  /** Keep only the authored bindings needed by the ordinary-space-station pad puzzles. */
  public static boolean retainsSource(FloorInfo floor, GroupInfo group, PropInfo info) {
    return floor != null && group != null && puzzle(info)
        && LEGACY_BINDINGS.containsKey(floor.getFloorID() / 1000 + ":" + floor.getFloorID()
            + ":" + group.getId() + ":" + info.getID());
  }

  /** Older loaders discarded ValueSource before hashing otherwise identical puzzle placements. */
  static boolean legacySource(ScenePropState.Snapshot record, PropInfo info) {
    if (record == null || !puzzle(info) || info.getValueSource() == null) return false;
    var key = record.getPlane() + ":" + record.getFloor() + ":" + record.getGroup() + ":" + record.getInstance();
    var expected = LEGACY_BINDINGS.get(key);
    if (expected == null || info.getID() != record.getInstance()
        || !expected.get(0).equals(value(info, "Bridge"))
        || !expected.get(1).equals(value(info, "PadsPuzzlePath"))) return false;
    var old = ResourceLoader.getGson().fromJson(ResourceLoader.getGson().toJson(info), PropInfo.class);
    old.setValueSource(null);
    return record.getSource().equals(PropTimelines.source(old));
  }

  private static boolean bridge(PropInfo info) {
    return info != null && (info.getPropID() == 100 || info.getPropID() == BRIDGE_PROP)
        && BRIDGE.equals(info.getInitLevelGraph()) && info.getState() == PropState.Closed;
  }

  private static PropInfo original(Scene scene, int groupId, int instance) {
    var floor = scene.getFloorInfo();
    if (floor == null || groupId <= 0 || instance <= 0
        || !floor.isGroupInDimension(groupId, scene.getDimensionId())) return null;
    var group = scene.getResourceGroups().get(groupId);
    if (group == null || group.getId() != groupId || group.getPropList() == null) return null;
    var matches = group.getPropList().stream()
        .filter(info -> info != null && info.getID() == instance).toList();
    if (matches.size() != 1) return null;
    var info = matches.getFirst();
    return info.isIsDelete() || info.isIsClientOnly() || !info.isLoadOnInitial() ? null : info;
  }

  private static String value(PropInfo info, String key) {
    if (info.getValueSource() == null || info.getValueSource().getValues() == null) return null;
    var entries = info.getValueSource().getValues().stream()
        .filter(Objects::nonNull).filter(v -> v.has("Key") && v.get("Key").isJsonPrimitive()
            && key.equals(v.get("Key").getAsString())).toList();
    if (entries.size() != 1) return null;
    var entry = entries.getFirst().get("Value");
    return entry != null && entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()
        ? entry.getAsString() : null;
  }

  private static Link link(Scene scene, int groupId, PropInfo info) {
    if (!world(scene) || !puzzle(info) || original(scene, groupId, info.getID()) != info) return null;
    var boardPath = value(info, "PadsPuzzlePath");
    var binding = value(info, "Bridge");
    var expected = LEGACY_BINDINGS.get(scene.getPlaneId() + ":" + scene.getFloorId()
        + ":" + groupId + ":" + info.getID());
    if (expected == null || !expected.get(0).equals(binding) || !expected.get(1).equals(boardPath)
        || binding == null
        || !binding.matches("[1-9][0-9]*,[1-9][0-9]*")) return null;
    try {
      var parts = binding.split(",");
      int targetGroup = Integer.parseInt(parts[0]), targetInstance = Integer.parseInt(parts[1]);
      var target = original(scene, targetGroup, targetInstance);
      var excel = target == null ? null : GameData.getPropExcelMap().get(target.getPropID());
      if (!bridge(target) || excel == null || !excel.getPropStateList().contains(PropState.Open)) return null;
      return new Link(scene.getResourceGroups().get(targetGroup), target);
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  private static EntityProp actor(Scene scene, Link link) {
    if (link == null || !scene.getLoadedGroups().contains(link.group().getId())) return null;
    var actors = scene.getEntitiesByGroup(EntityProp.class, link.group().getId()).stream()
        .filter(p -> p.getInstId() == link.prop().getID()).toList();
    if (actors.size() != 1) return null;
    var actor = actors.getFirst();
    return actor.getPropId() == link.prop().getPropID() && actor.getPropInfo() != null
        && PropTimelines.source(actor.getPropInfo()).equals(PropTimelines.source(link.prop())) ? actor : null;
  }

  private static EntityProp actor(Scene scene, int group, int instance) {
    var info = original(scene, group, instance);
    return info == null ? null : actor(scene, new Link(scene.getResourceGroups().get(group), info));
  }

  private static boolean supplyZone(Scene scene) {
    return world(scene) && scene.getPlaneId() == SUPPLY_PLANE && scene.getFloorId() == SUPPLY_FLOOR
        && scene.getFloorInfo() != null;
  }

  private static boolean sharedBridge(EntityProp prop) {
    var scene = prop.getScene();
    return prop.getGroupId() == SHARED_GROUP && prop.getInstId() == SHARED_BRIDGE
        && prop.getPropId() == BRIDGE_PROP && supplyZone(scene) && bridge(prop.getPropInfo())
        && original(scene, SHARED_GROUP, SHARED_BRIDGE) == prop.getPropInfo();
  }

  private static RepeatBoard activeRepeat(Scene scene) {
    if (!supplyZone(scene)) return null;
    var player = scene.getPlayer();
    for (var repeat : REPEAT_BOARDS) {
      if (!MissionStateRecords.mainDoing(player, repeat.reset() / 100)) continue;
      var group = scene.getResourceGroups().get(repeat.group());
      var board = original(scene, repeat.group(), BOARD_INSTANCE);
      var bridge = board == null ? null : link(scene, repeat.group(), board);
      if (bridge != null && bridge.group().getId() == SHARED_GROUP
          && bridge.prop().getID() == SHARED_BRIDGE && bridge.prop().getPropID() == BRIDGE_PROP
          && group.getLoadSide() == GroupInfo.GroupLoadSide.Server
          && player.getMissionManager().shouldLoadGroup(group, scene)) return repeat;
    }
    return null;
  }

  private static boolean completed(Scene scene, RepeatBoard repeat) {
    return savedOpen(scene, scene.getResourceGroups().get(repeat.group()),
        original(scene, repeat.group(), BOARD_INSTANCE));
  }

  static Retcode validate(EntityProp prop, int config) {
    var scene = prop.getScene();
    if (config != 1010 && sharedBridge(prop) && activeRepeat(scene) != null)
      return Retcode.INTERACT_TYPE_INVALID;
    if (config != 1010 || !puzzle(prop.getPropInfo())) return Retcode.SUCC;
    var owner = original(scene, prop.getGroupId(), prop.getInstId());
    var linked = owner == null ? null : link(scene, prop.getGroupId(), owner);
    var target = actor(scene, linked);
    if (owner == null || !PropTimelines.source(owner).equals(PropTimelines.source(prop.getPropInfo()))
        || !scene.getLoadedGroups().contains(prop.getGroupId())
        || !scene.getPlayer().getMissionManager().shouldLoadGroup(scene.getResourceGroups().get(prop.getGroupId()), scene)
        || linked == null || scene.getLoadedGroups().contains(linked.group().getId()) && target == null
        || target != null && target.getState() != PropState.Closed && target.getState() != PropState.Open)
      return Retcode.MISSION_SCENE_NOT_MATCH;
    var repeat = activeRepeat(scene);
    if (repeat != null && linked.group().getId() == SHARED_GROUP
        && linked.prop().getID() == SHARED_BRIDGE && repeat.group() != prop.getGroupId())
      return Retcode.MISSION_SCENE_NOT_MATCH;
    return Retcode.SUCC;
  }

  static void transitions(EntityProp prop, int config, Map<EntityProp, PropState> targets) {
    if (config != 1010 || !puzzle(prop.getPropInfo())) return;
    var scene = prop.getScene();
    var owner = original(scene, prop.getGroupId(), prop.getInstId());
    var linked = owner == null ? null : link(scene, prop.getGroupId(), owner);
    if (linked == null) throw new IllegalStateException("Puzzle bridge changed after validation");
    var target = actor(scene, linked);
    if (target == null && !scene.getLoadedGroups().contains(linked.group().getId())) return;
    if (target == null) throw new IllegalStateException("Puzzle bridge actor changed after validation");
    targets.put(target, PropState.Open);
  }

  private static boolean savedOpen(Scene scene, GroupInfo group, PropInfo puzzle) {
    var state = scene.getPlayer().getGameplay().getScenePropStates();
    if (!state.valid()) return false;
    var record = state.get(PropTimelines.key(scene, group.getId(), puzzle.getID()));
    return record != null && record.getProp() == puzzle.getPropID()
        && record.getState() == PropState.Open.getVal() && record.getAcceptance().isEmpty()
        && (PropTimelines.source(puzzle).equals(record.getSource()) || legacySource(record, puzzle));
  }

  private static boolean hasCompletedOwner(EntityProp target) {
    var scene = target.getScene();
    var targetInfo = original(scene, target.getGroupId(), target.getInstId());
    if (!world(scene) || !bridge(targetInfo) || target.getPropInfo() == null
        || !PropTimelines.source(targetInfo).equals(PropTimelines.source(target.getPropInfo()))) return false;
    for (var group : scene.getResourceGroups().values()) {
      if (group.getPropList() == null) continue;
      for (var info : group.getPropList()) {
        if (!puzzle(info)) continue;
        var linked = link(scene, group.getId(), info);
        if (linked != null && linked.group().getId() == target.getGroupId()
            && linked.prop().getID() == target.getInstId() && savedOpen(scene, group, info)) return true;
      }
    }
    return false;
  }

  /** Project the shared bridge for the current board, and recover legacy one-board bridges. */
  public static void restore(EntityProp prop) {
    if (prop == null || !bridge(prop.getPropInfo())) return;
    var scene = prop.getScene();
    var repeat = sharedBridge(prop) ? activeRepeat(scene) : null;
    if (repeat != null) {
      // The solid surface is shared. Only a receipt for the current board can show it.
      prop.setState(completed(scene, repeat) ? PropState.Open : PropState.Closed, false);
      return;
    }
    if (prop.getState() != PropState.Closed) return;
    var state = scene.getPlayer().getGameplay().getScenePropStates();
    if (!state.valid() || state.get(PropTimelines.key(scene, prop.getGroupId(), prop.getInstId())) != null) return;
    if (hasCompletedOwner(prop)) prop.setState(PropState.Open, false);
  }

  /** The shared bridge can remain loaded while mission groups replace their boards. */
  public static void reconcile(Scene scene) {
    if (!supplyZone(scene)) return;
    var player = scene.getPlayer();
    synchronized (player) {
      var prop = actor(scene, SHARED_GROUP, SHARED_BRIDGE);
      if (prop == null || !sharedBridge(prop)) return;
      var before = prop.getState();
      SceneInteractions.restore(prop);
      ScenePropTransitions.restore(prop);
      restore(prop);
      if (before != prop.getState() && scene.isLoaded())
        player.sendPacket(new PacketSceneGroupRefreshScNotify(scene, prop, null));
    }
  }

  /** Bridge-only callbacks may arrive before or after the board's own completion. */
  static boolean acknowledgesBridgeOpen(EntityProp prop, int config) {
    var row = GameData.getInteractExcelMap().get(config);
    return prop != null && config == 1010 && row != null && row.getSrcState() == PropState.Closed
        && row.getTargetState() == PropState.Open && row.getItemCostList() != null
        && row.getItemCostList().isEmpty() && row.getInteractCostType() != null
        && (row.getInteractCostType().isBlank() || row.getInteractCostType().equals("None"))
        && (sharedBridge(prop) && activeRepeat(prop.getScene()) != null
            || prop.getState() == PropState.Open && hasCompletedOwner(prop));
  }

  static boolean satisfied(Player player, MissionInfo.SubMissionInfo objective) {
    if (objective.getLevelPlaneID() != SUPPLY_PLANE || objective.getLevelFloorID() != SUPPLY_FLOOR
        || !"Equal".equals(objective.getParamType()) || objective.getProgress() != 1
        || !"".equals(objective.getParamStr1()) || objective.getParamIntList().length != 0
        || !objective.getParamItemList().isEmpty()) return false;
    var scene = player.getScene();
    var repeat = activeRepeat(scene);
    if (repeat == null || scene.getPlayer() != player || scene.getEntryId() != player.getEntryId()
        || player.getPlaneId() != SUPPLY_PLANE || player.getFloorId() != SUPPLY_FLOOR
        || objective.getMainMissionID() != repeat.reset() / 100) return false;
    if (objective.getId() == repeat.reset() && objective.getParamInt1() == SHARED_GROUP
        && objective.getParamInt2() == SHARED_BRIDGE && objective.getParamInt3() == PropState.Closed.getVal()
        && ("Config/Level/Mission/2000301/Mission_" + repeat.reset() + ".json").equals(objective.getMissionJsonPath())
        && objective.getTask() != null
        && RESET_SCRIPT.equals(JsonParser.parseString(objective.getTask().getSourceDefinition()))) {
      reconcile(scene);
      var bridge = actor(scene, SHARED_GROUP, SHARED_BRIDGE);
      return bridge != null && sharedBridge(bridge)
          && (bridge.getState() == PropState.Closed || completed(scene, repeat));
    }
    if (objective.getId() != repeat.reveal() || objective.getParamInt1() != CHEST_GROUP
        || objective.getParamInt2() != repeat.chest() || objective.getParamInt3() != PropState.ChestClosed.getVal()
        || !MissionStateRecords.subCompleted(player, repeat.reset()) || !completed(scene, repeat)
        || !player.getGameplay().getWorldMechanisms().completed(repeat.mapping())) return false;
    var chest = actor(scene, CHEST_GROUP, repeat.chest());
    return chest != null && Objects.equals(WorldChests.identity(chest), repeat.mapping())
        && (chest.getState() == PropState.ChestClosed || chest.getState() == PropState.ChestUsed);
  }

  static boolean revealsRepeatChest(EntityProp prop) {
    var repeat = activeRepeat(prop.getScene());
    return repeat != null && prop.getGroupId() == repeat.group() && prop.getInstId() == BOARD_INSTANCE
        && puzzle(prop.getPropInfo());
  }

  private PadPuzzleBridges() {}
}
