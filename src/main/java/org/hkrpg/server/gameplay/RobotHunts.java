package org.hkrpg.server.gameplay;

import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.Set;
import org.hkrpg.server.GameDiagnostics;

/** The authored robot-finish marker precedes the separate client chest report. */
public final class RobotHunts {
  private static final Set<Integer> CHESTS = Set.of(
      20111654, 20121655, 20122655, 20131657, 20131658, 20132653, 20132654);
  private static final String GRAPH =
      "Config/Level/Maze/MazeExtra/Robot_TreasureHunt_New/Maze_Prop_Robot_Gamplay.json";
  private static final int ROBOT = 300002, MARKER = 300007, TREASURE = 300004;

  private static WorldMechanismSources.Rule rule(Scene scene, int group) {
    if (scene == null) return null;
    for (int id : CHESTS) {
      var loc = MapChestSources.LOCATIONS.get(id);
      var rule = WorldMechanismSources.RULES.get(id);
      if (loc != null && rule != null && loc.plane() == scene.getPlaneId()
          && loc.floor() == scene.getFloorId() && loc.group() == group
          && loc.instance() == TREASURE && loc.prop() == 60102
          && rule.kind() == WorldMechanismSources.Kind.CLIENT_REPORT
          && rule.reports().equals(Set.of(120120)) && rule.files().contains(GRAPH)) return rule;
    }
    return null;
  }

  static boolean endpoint(EntityProp prop) {
    return prop != null && prop.getInstId() == MARKER && prop.getPropId() == 100
        && rule(prop.getScene(), prop.getGroupId()) != null;
  }

  static int chest(EntityProp prop) {
    var rule = prop == null ? null : rule(prop.getScene(), prop.getGroupId());
    return rule == null ? 0 : rule.chest();
  }

  private static boolean recorded(EntityProp actor) {
    return actor != null && actor.getState() == PropState.Open
        && WorldMechanisms.durableState(actor);
  }

  static Retcode validateFinish(Player player, int config, EntityProp marker) {
    if (config != 1010) return Retcode.INTERACT_TYPE_INVALID;
    var scene = marker.getScene();
    var rule = rule(scene, marker.getGroupId());
    if (rule == null || !WorldMechanisms.context(player, scene,
        WorldMechanismSources.group(rule, scene))) return Retcode.MISSION_SCENE_NOT_MATCH;
    if (!player.getGameplay().getWorldMechanisms().valid()
        || !player.getGameplay().getScenePropStates().valid()
        || player.getGameplay().getWorldChests().getRecords() == null) return Retcode.PLAYER_DATA_ERROR;
    if (WorldMechanisms.boundActor(scene, rule, MARKER, 100) != marker
        || WorldMechanisms.boundActor(scene, rule, TREASURE, 60102) == null)
      return Retcode.MISSION_SCENE_NOT_MATCH;
    var robot = WorldMechanisms.boundActor(scene, rule, ROBOT, 301010);
    return recorded(robot) ? Retcode.SUCC : Retcode.UNSUPPORTED_PROP_STATE;
  }

  static WorldMechanismState progress(
      Player player, int config, EntityProp prop, WorldMechanismState previous) {
    if (!endpoint(prop) || config != 1010) return previous;
    var rule = rule(prop.getScene(), prop.getGroupId());
    if (previous.completed(rule.chest())
        || player.getGameplay().getWorldChests().getRecords().containsKey(rule.chest()))
      return previous;
    return previous.advance(rule, prop.getScene(), 0, Instant.now().getEpochSecond());
  }

  /** A late original report acknowledges the already saved unlock, never an opened chest. */
  static boolean acknowledgesChestReport(Player player, int config, EntityProp prop) {
    if (config != 120120 || prop == null || prop.getInstId() != TREASURE
        || prop.getPropId() != 60102 || prop.getState() != PropState.ChestClosed) return false;
    var scene = prop.getScene();
    var rule = rule(scene, prop.getGroupId());
    if (rule == null || !WorldMechanisms.context(player, scene,
        WorldMechanismSources.group(rule, scene))) return false;
    var chests = player.getGameplay().getWorldChests();
    return player.getGameplay().getWorldMechanisms().completed(rule.chest())
        && chests.getRecords() != null && !chests.getRecords().containsKey(rule.chest())
        && WorldMechanisms.boundActor(scene, rule, TREASURE, 60102) == prop;
  }

  /** Recover old, fully recorded endpoint and repair states after the destination is attached. */
  public static void recover(Player player) {
    synchronized (player) {
      var scene = player.getScene();
      if (scene == null || player.getInstance() != null) return;
      var previous = player.getGameplay().getWorldMechanisms();
      var snapshots = player.getGameplay().getScenePropStates();
      var chests = player.getGameplay().getWorldChests();
      if (!previous.valid() || !snapshots.valid() || chests.getRecords() == null) return;
      var next = previous;
      int subject = 0;
      for (int id : CHESTS) {
        var loc = MapChestSources.LOCATIONS.get(id);
        if (loc == null || loc.plane() != scene.getPlaneId() || loc.floor() != scene.getFloorId()
            || previous.completed(id) || chests.getRecords().containsKey(id)) continue;
        var markerRecord = snapshots.get(PropTimelines.key(scene, loc.group(), MARKER));
        if (markerRecord == null || markerRecord.getState() != PropState.Open.getVal()) continue;
        var rule = rule(scene, loc.group());
        if (rule == null || !WorldMechanisms.context(player, scene,
            WorldMechanismSources.group(rule, scene))) {
          GameDiagnostics.outcome(player.getSession(), "RobotHuntRecovery", id,
              Retcode.MISSION_SCENE_NOT_MATCH, "invalid-source-or-scene", "group=" + loc.group());
          continue;
        }
        var marker = WorldMechanisms.boundActor(scene, rule, MARKER, 100);
        var robot = WorldMechanisms.boundActor(scene, rule, ROBOT, 301010);
        var treasure = WorldMechanisms.boundActor(scene, rule, TREASURE, 60102);
        if (!recorded(marker) || !recorded(robot) || treasure == null) {
          GameDiagnostics.outcome(player.getSession(), "RobotHuntRecovery", id,
              Retcode.UNSUPPORTED_PROP_STATE, "missing-finish-proof", "group=" + loc.group());
          continue;
        }
        next = next.advance(rule, scene, 0, Instant.now().getEpochSecond());
        subject = id;
      }
      if (next == previous) return;
      player.getGameplay().worldMechanisms(next);
      try {
        player.save();
      } catch (RuntimeException failed) {
        player.getGameplay().worldMechanisms(previous);
        GameDiagnostics.saveFailed(player, "robot-hunt-recovery", subject, 0, failed);
        return;
      }
      WorldMechanisms.publish(scene);
    }
  }

  private RobotHunts() {}
}
