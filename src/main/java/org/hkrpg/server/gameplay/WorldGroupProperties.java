package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.protocol.Story46;

/** Source-bound completion reports; group, floor and chest journal commit in one player save. */
public final class WorldGroupProperties {
  public record Result(Retcode retcode, int oldValue, int newValue) {}

  public static Result change(
      Player player, int floor, int dimension, int group, String name, int value) {
    synchronized (player) {
      if (CompatibleMechanisms.ownsProperty(player.getScene(), group))
        return CompatibleMechanisms.property(player, floor, dimension, group, name, value);
      var scene = player.getScene();
      if (scene == null || scene.getFloorId() != floor || scene.getDimensionId() != dimension)
        return new Result(Retcode.MISSION_SCENE_NOT_MATCH, 0, 0);
      var rules =
          WorldMechanismSources.RULES.values().stream()
              .filter(
                  r ->
                      r.kind() == WorldMechanismSources.Kind.GROUP_PROPERTY
                          && r.controllerGroup() == group
                          && MapChestSources.LOCATIONS.get(r.chest()).floor() == floor
                          && MapChestSources.LOCATIONS.get(r.chest()).plane() == scene.getPlaneId())
              .toList();
      if (rules.size() != 1) return new Result(Retcode.INTERACT_TYPE_INVALID, 0, 0);
      var rule = rules.getFirst();
      boolean late = !rule.lateFloorValue().isEmpty() && name.equals("Srv_KindlingFall");
      if ((!rule.property().equals(name) && !late) || value != rule.propertyValue())
        return new Result(Retcode.INTERACT_TYPE_INVALID, 0, 0);
      if (!WorldMechanisms.context(player, scene, WorldMechanismSources.group(rule, scene))
          || !WorldMechanisms.context(
              player, scene, WorldMechanismSources.controlGroup(rule, scene))
          || !WorldMechanismSources.floorValuesMatch(rule, scene))
        return new Result(Retcode.MISSION_SCENE_NOT_MATCH, 0, 0);
      var old = player.getGameplay().getWorldMechanisms();
      if (!old.valid()) return new Result(Retcode.PLAYER_DATA_ERROR, 0, 0);
      var actual = scene.getGroups().get(group);
      if (actual == null || !scene.hasGroupProperty(group, name))
        return new Result(Retcode.MISSION_SCENE_NOT_MATCH, 0, 0);
      int before = actual.getProperties().getInt(name);
      if (late ? old.late(rule.chest()) : old.completed(rule.chest()))
        return new Result(Retcode.SUCC, before, before);
      if (late && !old.completed(rule.chest()))
        return new Result(Retcode.UNSUPPORTED_PROP_STATE, before, before);
      var values = player.getGameplay().getStoryValues();
      String floorName = late ? rule.lateFloorValue() : rule.floorValue();
      String floorKey = "floor:" + floor + ":" + floorName;
      var declarations =
          scene.getFloorInfo().getExtraDatas(dimension).stream()
              .filter(d -> floorName.equals(d.getName()))
              .toList();
      if (declarations.size() != 1) return new Result(Retcode.PLAYER_DATA_ERROR, 0, 0);
      int current = values.getOrDefault(floorKey, declarations.getFirst().getDefaultValue());
      if (current != 0 || before != 0)
        return new Result(Retcode.UNSUPPORTED_PROP_STATE, before, before);
      String groupKey = "group:" + floor + ":" + group + ":" + name;
      Integer previousFloor = values.get(floorKey), previousGroup = values.get(groupKey);
      var next =
          late ? old.finishLate(rule) : old.advance(rule, scene, 0, Instant.now().getEpochSecond());
      values.put(floorKey, value);
      values.put(groupKey, value);
      actual.getProperties().put(name, value);
      player.getGameplay().worldMechanisms(next);
      try {
        player.save();
      } catch (RuntimeException failed) {
        restore(values, floorKey, previousFloor);
        restore(values, groupKey, previousGroup);
        actual.getProperties().put(name, before);
        player.getGameplay().worldMechanisms(old);
        return new Result(Retcode.SYSTEM_BUSY, before, before);
      }
      Story46.syncGroup(player, floor, group, name, before, value);
      Story46.syncFloor(player, scene.getPlaneId(), floor, floorName, value);
      WorldMechanisms.publish(scene);
      player.getMissionManager().refresh();
      return new Result(Retcode.SUCC, before, value);
    }
  }

  public static void restore(emu.lunarcore.game.scene.Scene scene, int group) {
    CompatibleMechanisms.restoreGroup(scene, group);
    var state = scene.getPlayer().getGameplay().getWorldMechanisms();
    var actual = scene.getGroups().get(group);
    if (actual == null) return;
    for (var rule : WorldMechanismSources.RULES.values()) {
      if (rule.kind() != WorldMechanismSources.Kind.GROUP_PROPERTY
          || rule.controllerGroup() != group
          || MapChestSources.LOCATIONS.get(rule.chest()).floor() != scene.getFloorId()
          || MapChestSources.LOCATIONS.get(rule.chest()).plane() != scene.getPlaneId()) continue;
      boolean valid = state.valid() && WorldMechanismSources.controlGroup(rule, scene) != null;
      actual.getProperties().put(rule.property(), valid && state.completed(rule.chest()) ? 1 : 0);
      if (!rule.lateFloorValue().isEmpty())
        actual.getProperties().put("Srv_KindlingFall", valid && state.late(rule.chest()) ? 1 : 0);
    }
  }

  private static void restore(Map<String, Integer> values, String key, Integer previous) {
    if (previous == null) values.remove(key);
    else values.put(key, previous);
  }

  private WorldGroupProperties() {}
}
