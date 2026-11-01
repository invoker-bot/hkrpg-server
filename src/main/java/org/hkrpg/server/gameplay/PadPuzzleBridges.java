package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
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
    return info != null && (info.getPropID() == 100 || info.getPropID() == 300004)
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

  static Retcode validate(EntityProp prop, int config) {
    if (config != 1010 || !puzzle(prop.getPropInfo())) return Retcode.SUCC;
    var scene = prop.getScene();
    var owner = original(scene, prop.getGroupId(), prop.getInstId());
    var linked = owner == null ? null : link(scene, prop.getGroupId(), owner);
    var target = actor(scene, linked);
    if (owner == null || !PropTimelines.source(owner).equals(PropTimelines.source(prop.getPropInfo()))
        || !scene.getLoadedGroups().contains(prop.getGroupId())
        || !scene.getPlayer().getMissionManager().shouldLoadGroup(scene.getResourceGroups().get(prop.getGroupId()), scene)
        || linked == null || scene.getLoadedGroups().contains(linked.group().getId()) && target == null
        || target != null && target.getState() != PropState.Closed && target.getState() != PropState.Open)
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

  /** Reconstruct a missing legacy bridge state from the exact already-saved puzzle receipt. */
  public static void restore(EntityProp prop) {
    if (prop == null || !bridge(prop.getPropInfo()) || prop.getState() != PropState.Closed) return;
    var scene = prop.getScene();
    var state = scene.getPlayer().getGameplay().getScenePropStates();
    if (!state.valid() || state.get(PropTimelines.key(scene, prop.getGroupId(), prop.getInstId())) != null) return;
    if (hasCompletedOwner(prop)) prop.setState(PropState.Open, false);
  }

  /** The client's authored finish callback may repeat the already-applied open interaction. */
  static boolean repeatedOpen(EntityProp prop, int config) {
    var row = GameData.getInteractExcelMap().get(config);
    return prop != null && config == 1010 && row != null && row.getSrcState() == PropState.Closed
        && row.getTargetState() == PropState.Open && row.getItemCostList() != null
        && row.getItemCostList().isEmpty() && row.getInteractCostType() != null
        && (row.getInteractCostType().isBlank() || row.getInteractCostType().equals("None"))
        && prop.getState() == PropState.Open && hasCompletedOwner(prop);
  }

  private PadPuzzleBridges() {}
}
