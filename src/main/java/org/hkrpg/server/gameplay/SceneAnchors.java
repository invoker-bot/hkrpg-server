package org.hkrpg.server.gameplay;

import com.google.gson.JsonObject;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.data.excel.MapEntranceExcel;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.enums.PropType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.util.Position;
import java.util.*;

/** Individual anchor progress never changes shared floor definitions. */
public final class SceneAnchors {
  /** Supported authored server volumes, in metres and prop-local coordinates. */
  public sealed interface Volume permits Sphere, Box {
    boolean contains(PropInfo prop, Position position);
  }

  public record Sphere(double radius, double x, double y, double z) implements Volume {
    public boolean contains(PropInfo prop, Position position) {
      if (position == null || prop.getRotX() != 0 || prop.getRotZ() != 0) return false;
      double angle = Math.toRadians(prop.getRotY());
      double dx = position.getX() / 1000.0 - prop.getPosX() - (x * Math.cos(angle) + z * Math.sin(angle));
      double dy = position.getY() / 1000.0 - prop.getPosY() - y;
      double dz = position.getZ() / 1000.0 - prop.getPosZ() - (z * Math.cos(angle) - x * Math.sin(angle));
      return dx * dx + dy * dy + dz * dz <= radius * radius;
    }
  }

  public record Box(double width, double height, double depth, double x, double y, double z)
      implements Volume {
    public boolean contains(PropInfo prop, Position position) {
      if (position == null || prop.getRotX() != 0 || prop.getRotZ() != 0) return false;
      double angle = Math.toRadians(prop.getRotY());
      double dx = position.getX() / 1000.0 - prop.getPosX();
      double dy = position.getY() / 1000.0 - prop.getPosY();
      double dz = position.getZ() / 1000.0 - prop.getPosZ();
      double localX = dx * Math.cos(angle) - dz * Math.sin(angle) - x;
      double localZ = dx * Math.sin(angle) + dz * Math.cos(angle) - z;
      return Math.abs(localX) <= width / 2 && Math.abs(dy - y) <= height / 2
          && Math.abs(localZ) <= depth / 2;
    }
  }

  /** Keep the raw source model unchanged; unsupported shapes never receive a guessed radius. */
  public static void loadTriggers(GroupInfo group, JsonObject original) {
    if (group == null || group.getPropList() == null) return;
    group.getPropList().forEach(p -> p.setSpringActivation(null));
    if (original == null || !original.has("PropList") || !original.get("PropList").isJsonArray()) return;
    for (var value : original.getAsJsonArray("PropList")) {
      try {
        var row = value.getAsJsonObject();
        int id = row.get("ID").getAsBigDecimal().intValueExact();
        var matches = group.getPropList().stream().filter(p -> p.getID() == id).toList();
        if (matches.size() != 1 || !row.has("CustomTriggerMap")) continue;
        var map = row.getAsJsonObject("CustomTriggerMap");
        if (!map.has("CustomTrigger_Spring")) continue;
        var trigger = map.getAsJsonObject("CustomTrigger_Spring");
        String shape = trigger.has("Shape") ? trigger.get("Shape").getAsString() : "Sphere";
        var allowed = switch (shape) {
          case "Sphere" -> Set.of("Radius", "Offset", "PointList", "Server", "Shape");
          case "Box" -> Set.of("Radius", "DimX", "DimY", "DimZ", "Offset", "PointList", "Server", "Shape");
          default -> Set.<String>of();
        };
        if (!allowed.containsAll(trigger.keySet())
            || !trigger.has("Server") || !trigger.get("Server").isJsonPrimitive()
            || !trigger.getAsJsonPrimitive("Server").isBoolean() || !trigger.get("Server").getAsBoolean()
            || trigger.has("PointList") && (!trigger.get("PointList").isJsonArray()
                || !trigger.getAsJsonArray("PointList").isEmpty())) continue;
        var offset = trigger.getAsJsonObject("Offset");
        if (offset == null || !Set.of("X", "Y", "Z").containsAll(offset.keySet())) continue;
        double x = number(offset, "X"), y = number(offset, "Y"), z = number(offset, "Z");
        if (shape.equals("Sphere")) {
          double radius = number(trigger, "Radius");
          if (radius > 0) matches.getFirst().setSpringActivation(new Sphere(radius, x, y, z));
        } else if (shape.equals("Box")) {
          double width = number(trigger, "DimX"), height = number(trigger, "DimY"), depth = number(trigger, "DimZ");
          if (trigger.has("Radius") && number(trigger, "Radius") <= 0) continue;
          if (width > 0 && height > 0 && depth > 0)
            matches.getFirst().setSpringActivation(new Box(width, height, depth, x, y, z));
        }
      } catch (RuntimeException unsupported) {
        // Invalid/missing geometry is not permission to unlock a waypoint.
      }
    }
  }

  private static double number(JsonObject object, String key) {
    var value = object.getAsJsonPrimitive(key);
    if (value == null || !value.isNumber() || !Double.isFinite(value.getAsDouble()))
      throw new IllegalArgumentException("Invalid anchor geometry");
    return value.getAsDouble();
  }

  public static PropInfo find(int entryId, int groupId, int instance) {
    var entry = GameData.getMapEntranceExcelMap().get(entryId);
    var plane = entry == null ? null : GameData.getMazePlaneExcelMap().get(entry.getPlaneID());
    if (plane == null
        || !Set.of(
                emu.lunarcore.game.enums.PlaneType.Town,
                emu.lunarcore.game.enums.PlaneType.Maze,
                emu.lunarcore.game.enums.PlaneType.Train)
            .contains(plane.getPlaneType())) return null;
    var floor =
        entry == null ? null : GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID());
    var group = floor == null ? null : floor.getGroups().get(groupId);
    if (group == null) return null;
    var matches = group.getPropList().stream().filter(p -> p.getID() == instance).toList();
    if (matches.size() != 1) return null;
    var prop = matches.getFirst();
    var excel = GameData.getPropExcelMap().get(prop.getPropID());
    if (prop.isIsDelete()
        || prop.isIsClientOnly()
        || prop.getMappingInfoID() <= 0
        || prop.getAnchorID() <= 0
        || prop.getAnchorGroupID() <= 0
        || excel == null
        || !excel.getPropStateList().contains(PropState.CheckPointEnable)
        || floor.getAnchorInfo(prop.getAnchorGroupID(), prop.getAnchorID()) == null
        || floor.getCachedTeleports().get(prop.getMappingInfoID()) != prop) return null;
    return prop;
  }

  private static String key(MapEntranceExcel entry, PropInfo prop) {
    return "prop-state:"
        + entry.getPlaneID()
        + ":"
        + entry.getFloorID()
        + ":"
        + prop.getGroup().getId()
        + ":"
        + prop.getID();
  }

  public static PropState state(Player player, MapEntranceExcel entry, PropInfo prop) {
    if (player == null) return prop.getState();
    var states = player.getGameplay().getScenePropStates();
    if (!states.valid()) return prop.getState();
    var record = states.get(entry.getPlaneID() + ":" + entry.getFloorID() + ":0:0:"
        + prop.getGroup().getId() + ":" + prop.getID());
    if (record != null) {
      if (record.getProp() != prop.getPropID() || !record.getAcceptance().isEmpty()
          || !record.getSource().equals(PropTimelines.source(prop))) return prop.getState();
      for (var state : GameData.getPropExcelMap().get(prop.getPropID()).getPropStateList())
        if (state.getVal() == record.getState()) return state;
      return prop.getState();
    }
    Integer saved = player.getGameplay().getStoryValues().get(key(entry, prop));
    if (saved == null) return prop.getState();
    for (var state : GameData.getPropExcelMap().get(prop.getPropID()).getPropStateList())
      if (state.getVal() == saved) return state;
    return prop.getState();
  }

  public static boolean unlocked(Player player, MapEntranceExcel entry, PropInfo prop) {
    return prop != null
        && !GreatMineEntrances.unavailableRift(player, entry.getPlaneID(), entry.getFloorID(), prop.getGroup())
        && find(entry.getId(), prop.getGroup().getId(), prop.getID()) == prop
        && state(player, entry, prop) == PropState.CheckPointEnable;
  }

  public static SortedSet<Integer> unlocked(Player player, MapEntranceExcel entry) {
    var ids = new TreeSet<Integer>();
    var floor = GameData.getFloorInfo(entry.getPlaneID(), entry.getFloorID());
    if (floor != null)
      for (var prop : floor.getCachedTeleports().values())
        if (unlocked(player, entry, prop)) ids.add(prop.getMappingInfoID());
    return ids;
  }

  public static boolean activate(Player player, int entryId, int groupId, int instance) {
    var prop = find(entryId, groupId, instance);
    if (prop == null || !player.getGameplay().getScenePropStates().valid()) return false;
    var entry = GameData.getMapEntranceExcelMap().get(entryId);
    player.getGameplay().scenePropStates(player.getGameplay().getScenePropStates()
        .with(List.of(snapshot(entry, prop))));
    player
        .getGameplay()
        .getStoryValues()
        .put(key(entry, prop), PropState.CheckPointEnable.getVal());
    var scene = player.getScene();
    if (scene != null
        && scene.getPlaneId() == entry.getPlaneID()
        && scene.getFloorId() == entry.getFloorID())
      for (var entity : scene.getEntitiesByGroup(EntityProp.class, groupId))
        if (entity.getInstId() == instance && entity.getPropId() == prop.getPropID())
          entity.setState(PropState.CheckPointEnable);
    return true;
  }

  private static ScenePropState.Snapshot snapshot(MapEntranceExcel entry, PropInfo prop) {
    return new ScenePropState.Snapshot(entry.getPlaneID(), entry.getFloorID(), 0, 0,
        prop.getGroup().getId(), prop.getID(), prop.getPropID(), PropState.CheckPointEnable.getVal(),
        PropTimelines.source(prop), "");
  }

  /** Movement only discovers currently spawned, disabled anchors with an explicit server volume. */
  public static void onMove(Player player) {
    synchronized (player) {
      var scene = player.getScene();
      if (scene == null || !scene.isLoaded() || scene.getPlayer() != player || player.getBattle() != null
          || player.getInstance() != null || player.getGameplay().getCurrentMissionRaid() != 0
          || player.getGameplay().getRogue().isInside() || player.getGameplay().getParkourVenue().inside()
          || scene.getPlaneId() != player.getPlaneId() || scene.getFloorId() != player.getFloorId()
          || scene.getEntryId() != player.getEntryId() || scene.getDimensionId() != 0
          || scene.getStoryLineId() != 0 || player.getGameplay().getStoryLines().getCurrentId() != 0
          || player.getGameplay().getStoryLines().isTransitionPending()
          || !Set.of(PlaneType.Town, PlaneType.Maze).contains(scene.getPlaneType())) return;
      var entry = GameData.getMapEntranceExcelMap().get(scene.getEntryId());
      var previous = player.getGameplay().getScenePropStates();
      if (entry == null || entry.getPlaneID() != scene.getPlaneId() || entry.getFloorID() != scene.getFloorId()
          || !previous.valid()) return;
      var changed = new ArrayList<EntityProp>();
      var records = new ArrayList<ScenePropState.Snapshot>();
      for (var entity : List.copyOf(scene.getHealingSprings())) {
        var prop = entity.getPropInfo();
        if (entity.getScene() != scene || entity.getEntityId() <= 0
            || scene.getEntityById(entity.getEntityId()) != entity
            || entity.getPropType() != PropType.PROP_SPRING || prop == null
            || entity.getState() != PropState.CheckPointDisable || prop.getState() != PropState.CheckPointDisable
            || prop.getSpringActivation() == null
            || find(entry.getId(), entity.getGroupId(), entity.getInstId()) != prop
            || !prop.getSpringActivation().contains(prop, player.getPos())) continue;
        changed.add(entity);
        records.add(snapshot(entry, prop));
      }
      if (records.isEmpty()) return;
      player.getGameplay().scenePropStates(previous.with(records));
      try {
        player.save();
      } catch (RuntimeException failed) {
        player.getGameplay().scenePropStates(previous);
        return;
      }
      for (var entity : changed) {
        entity.setState(PropState.CheckPointEnable);
        player.getMissionManager().onPropInteraction(scene.getFloorId(), entity.getGroupId(),
            entity.getInstId(), PropState.CheckPointEnable.getVal());
      }
    }
  }

  private SceneAnchors() {}
}
