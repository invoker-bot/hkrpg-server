package org.hkrpg.server.gameplay;

import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.*;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;

/** Exact guard sets and authored client finish reports share one durable, reward-free journal. */
public final class WorldMechanisms {
  private static List<WorldMechanismSources.Rule> rules(Scene scene, int group) {
    if (scene == null) return List.of();
    return WorldMechanismSources.RULES.values().stream()
        .filter(
            r -> {
              var l = MapChestSources.LOCATIONS.get(r.chest());
              return l.plane() == scene.getPlaneId()
                  && l.floor() == scene.getFloorId()
                  && l.group() == group;
            })
        .toList();
  }

  static List<WorldMechanismSources.Rule> controls(EntityProp prop) {
    if (prop == null) return List.of();
    return rules(prop.getScene(), prop.getGroupId()).stream()
        .filter(
            r ->
                r.kind() == WorldMechanismSources.Kind.CONTROLLER
                    && r.controller() == prop.getInstId()
                    && r.controllerProp() == prop.getPropId())
        .toList();
  }

  public static boolean controller(EntityProp prop) {
    return !controls(prop).isEmpty();
  }

  private static boolean context(Player p, Scene scene, GroupInfo group) {
    return scene != null
        && scene == p.getScene()
        && scene.getPlayer() == p
        && p.getPlaneId() == scene.getPlaneId()
        && p.getFloorId() == scene.getFloorId()
        && p.getEntryId() == scene.getEntryId()
        && p.getInstance() == null
        && scene.getStoryLineId() == p.getGameplay().getStoryLines().getCurrentId()
        && !p.getGameplay().getStoryLines().isTransitionPending()
        && group != null
        && scene.getLoadedGroups().contains(group.getId())
        && p.getMissionManager().shouldLoadGroup(group, scene);
  }

  static Retcode validateControl(Player p, int config, EntityProp prop) {
    if (!p.getGameplay().getWorldMechanisms().valid()) return Retcode.PLAYER_DATA_ERROR;
    for (var rule : controls(prop)) {
      if (!rule.interactions().contains(config)) return Retcode.INTERACT_TYPE_INVALID;
      if (!context(p, prop.getScene(), WorldMechanismSources.group(rule, prop.getScene())))
        return Retcode.MISSION_SCENE_NOT_MATCH;
      if (p.getGameplay().getWorldMechanisms().completed(rule.chest())) return Retcode.SYSTEM_BUSY;
    }
    return Retcode.SUCC;
  }

  static WorldMechanismState controlProgress(Player p, int config, EntityProp prop) {
    var next = p.getGameplay().getWorldMechanisms();
    for (var rule : controls(prop))
      if (config == rule.finish())
        next = next.advance(rule, prop.getScene(), 0, Instant.now().getEpochSecond());
    return next;
  }

  static boolean clientReport(int id, int config) {
    var rule = WorldMechanismSources.RULES.get(id);
    return rule != null
        && rule.kind() == WorldMechanismSources.Kind.CLIENT_REPORT
        && rule.reports().contains(config);
  }

  static Retcode validateChest(Player p, int id, int config) {
    var rule = WorldMechanismSources.RULES.get(id);
    if (rule == null) return Retcode.SUCC;
    var state = p.getGameplay().getWorldMechanisms();
    if (!state.valid()) return Retcode.PLAYER_DATA_ERROR;
    if (!context(p, p.getScene(), WorldMechanismSources.group(rule, p.getScene())))
      return Retcode.MISSION_SCENE_NOT_MATCH;
    if (config == 112131)
      return state.completed(id) ? Retcode.SUCC : Retcode.UNSUPPORTED_PROP_STATE;
    return clientReport(id, config) && !state.completed(id)
        ? Retcode.SUCC
        : Retcode.INTERACT_TYPE_INVALID;
  }

  static WorldChests.Result unlock(Player p, int id, EntityProp prop) {
    var previous = p.getGameplay().getWorldMechanisms();
    var next =
        previous.advance(
            WorldMechanismSources.RULES.get(id), p.getScene(), 0, Instant.now().getEpochSecond());
    p.getGameplay().worldMechanisms(next);
    try {
      p.save();
    } catch (RuntimeException failed) {
      p.getGameplay().worldMechanisms(previous);
      return new WorldChests.Result(Retcode.SYSTEM_BUSY, null);
    }
    prop.setState(PropState.ChestClosed);
    return new WorldChests.Result(Retcode.SUCC, prop);
  }

  /** Called inside the player's/scene's defeat transaction, before removal and notifications. */
  public static boolean defeat(Scene scene, EntityMonster monster) {
    var owned =
        rules(scene, monster.getGroupId()).stream()
            .filter(
                r ->
                    r.kind() == WorldMechanismSources.Kind.GUARDS
                        && r.guards().contains(monster.getInstId()))
            .toList();
    if (owned.isEmpty()) return true;
    var p = scene.getPlayer();
    var previous = p.getGameplay().getWorldMechanisms();
    if (!previous.valid()) return false;
    var next = previous;
    for (var rule : owned) {
      var group = WorldMechanismSources.group(rule, scene);
      if (!context(p, scene, group)) return false;
      var originals =
          group.getMonsterList().stream().filter(m -> m.getID() == monster.getInstId()).toList();
      if (originals.size() != 1
          || monster.isFarmElement()
          || monster.getExcel() == null
          || monster.getExcel().getId() != originals.getFirst().getNPCMonsterID()
          || monster.getEventId() != originals.getFirst().getEventID()
          || !ResourceLoader.getGson()
              .toJsonTree(originals.getFirst())
              .equals(ResourceLoader.getGson().toJsonTree(monster.getMonsterInfo()))) return false;
      next = next.advance(rule, scene, monster.getInstId(), Instant.now().getEpochSecond());
    }
    p.getGameplay().worldMechanisms(next);
    try {
      p.save();
    } catch (RuntimeException failed) {
      p.getGameplay().worldMechanisms(previous);
      return false;
    }
    publish(scene);
    return true;
  }

  public static boolean guardGroup(Scene scene, int group) {
    return rules(scene, group).stream()
        .anyMatch(r -> r.kind() == WorldMechanismSources.Kind.GUARDS);
  }

  public static boolean spawns(Scene scene, GroupInfo group, MonsterInfo monster) {
    for (var rule : rules(scene, group.getId())) {
      if (rule.kind() == WorldMechanismSources.Kind.GUARDS
          && rule.guards().contains(monster.getID())
          && scene
              .getPlayer()
              .getGameplay()
              .getWorldMechanisms()
              .defeated(rule.chest(), monster.getID())
          && WorldMechanismSources.group(rule, scene) != null) return false;
    }
    return true;
  }

  public static void restore(EntityProp prop) {
    var id = WorldChests.identity(prop);
    var rule = id == null ? null : WorldMechanismSources.RULES.get(id);
    if (rule == null) return;
    var p = prop.getScene().getPlayer();
    // Existing successful loot receipts remain authoritative, including those predating this
    // journal.
    if (p.getGameplay().getWorldChests().getRecords() != null
        && p.getGameplay().getWorldChests().getRecords().containsKey(id)) return;
    prop.setState(rule.initial(), false);
    if (p.getGameplay().getWorldMechanisms().completed(id)
        && WorldMechanismSources.group(rule, prop.getScene()) != null)
      prop.setState(PropState.ChestClosed, false);
  }

  static void publish(Scene scene) {
    for (var prop : scene.getEntities(EntityProp.class)) {
      var before = prop.getState();
      restore(prop);
      if (before != prop.getState() && scene.isLoaded())
        scene
            .getPlayer()
            .sendPacket(
                new emu.lunarcore.server.packet.send.PacketSceneGroupRefreshScNotify(
                    scene, prop, null));
    }
  }

  private WorldMechanisms() {}
}
