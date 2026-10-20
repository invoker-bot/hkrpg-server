package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;

/** Source spear requests persist in order; an actual shield removal reveals its own treasure. */
public final class GoldenSpears {
  public static boolean keepsSourceValues(GroupInfo group) {
    return group != null && "DestructableGoldenSpear".equals(group.getGroupName());
  }

  private static Integer identity(Scene scene, int group, int instance, int prop) {
    if (scene == null
        || (instance != 300001 || prop != GoldenSpearSources.SHIELD)
            && (instance != 300002 || prop != GoldenSpearSources.SPEAR)) return null;
    return GoldenSpearSources.PUZZLES.entrySet().stream()
        .filter(
            e ->
                e.getValue().plane() == scene.getPlaneId()
                    && e.getValue().floor() == scene.getFloorId()
                    && e.getValue().group() == group)
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(null);
  }

  private static Integer identity(EntityProp actor) {
    return actor == null
        ? null
        : identity(actor.getScene(), actor.getGroupId(), actor.getInstId(), actor.getPropId());
  }

  public static boolean applies(EntityProp actor) {
    return identity(actor) != null;
  }

  public static Retcode validate(Player p, int config, int entity) {
    var base = SceneInteractions.validate(p, config, entity);
    if (base != Retcode.SUCC) return base;
    var actor = (EntityProp) p.getScene().getEntityById(entity);
    var id = identity(actor);
    if (id == null || actor.getPropId() != GoldenSpearSources.SPEAR)
      return Retcode.INTERACT_TYPE_INVALID;
    var rules = WorldChestRules.load();
    if (rules == null || !rules.golden(id)) return Retcode.REWARD_CONFIG_NOT_EXIST;
    var state = p.getGameplay().getWorldChests();
    if (!state.valid(rules, p)) return Retcode.PLAYER_DATA_ERROR;
    if (state.getUnlocks().values().stream().anyMatch(r -> !r.completed()))
      return Retcode.SYSTEM_BUSY;
    var record = state.getSpears().get(id);
    int index = record == null ? 0 : record.getSteps().size();
    if (index >= GoldenSpearSources.CONFIGS.length
        || config != GoldenSpearSources.CONFIGS[index]
        || actor.getState() != GoldenSpearSources.STATES.get(index))
      return Retcode.UNSUPPORTED_PROP_STATE;
    return rules.actor(p, id, 300002, GoldenSpearSources.SPEAR) == actor
        ? Retcode.SUCC
        : Retcode.MISSION_SCENE_NOT_MATCH;
  }

  public static WorldChests.Result interact(Player p, int config, int entity) {
    synchronized (p) {
      var code = validate(p, config, entity);
      if (code != Retcode.SUCC) return new WorldChests.Result(code, null);
      var actor = (EntityProp) p.getScene().getEntityById(entity);
      int id = identity(actor);
      var rules = WorldChestRules.load();
      if (rules == null) return new WorldChests.Result(Retcode.REWARD_CONFIG_NOT_EXIST, null);
      var old = p.getGameplay().getWorldChests();
      int index = old.getSpears().containsKey(id) ? old.getSpears().get(id).getSteps().size() : 0;
      var next = old.spearStep(p, id, actor, rules, index, Instant.now().getEpochSecond());
      p.getGameplay().worldChests(next);
      if (!next.valid(rules, p)) {
        p.getGameplay().worldChests(old);
        return new WorldChests.Result(Retcode.PLAYER_DATA_ERROR, null);
      }
      try {
        p.save();
      } catch (RuntimeException failed) {
        p.getGameplay().worldChests(old);
        return new WorldChests.Result(Retcode.SYSTEM_BUSY, null);
      }
      actor.setState(GoldenSpearSources.STATES.get(index + 1));
      p.getMissionManager()
          .onPropInteraction(
              p.getFloorId(), actor.getGroupId(), actor.getInstId(), actor.getState().getVal());
      return new WorldChests.Result(Retcode.SUCC, actor);
    }
  }

  public static final class Removal {
    private final Player player;
    private final EntityProp shield, treasure;
    private final int chest, entity;
    private final long runtime;
    private final WorldChestState prepared;
    private final WorldChestRules rules;

    private Removal(
        Player p,
        EntityProp shield,
        EntityProp treasure,
        int chest,
        WorldChestState prepared,
        WorldChestRules rules) {
      player = p;
      this.shield = shield;
      this.treasure = treasure;
      this.chest = chest;
      this.prepared = prepared;
      this.rules = rules;
      entity = shield.getEntityId();
      runtime = p.getScene().getRuntimeId();
    }

    public boolean complete() {
      synchronized (player) {
        var scene = player.getScene();
        if (scene == null
            || scene.getRuntimeId() != runtime
            || shield.getEntityId() != 0
            || scene.getEntityById(entity) != null
            || player.getGameplay().getWorldChests() != prepared
            || !prepared.valid(rules, player)
            || rules.actor(player, chest) != treasure) return false;
        var next = prepared.revealed(chest, Instant.now().getEpochSecond());
        player.getGameplay().worldChests(next);
        if (!next.valid(rules, player)) {
          player.getGameplay().worldChests(prepared);
          return false;
        }
        try {
          player.save();
        } catch (RuntimeException failed) {
          player.getGameplay().worldChests(prepared);
          return false;
        }
        treasure.setState(PropState.ChestClosed);
        return true;
      }
    }
  }

  public static Removal prepare(Player p, EntityProp shield) {
    synchronized (p) {
      var id = identity(shield);
      if (id == null || shield.getPropId() != GoldenSpearSources.SHIELD) return null;
      var rules = WorldChestRules.load();
      var old = p.getGameplay().getWorldChests();
      if (rules == null
          || !rules.golden(id)
          || !old.valid(rules, p)
          || rules.actor(p, id, 300001, GoldenSpearSources.SHIELD) != shield
          || !p.getScene().canDestroyProp(shield)
          || old.getUnlocks().containsKey(id)
          || old.getUnlocks().values().stream().anyMatch(r -> !r.completed())
          || old.getRecords().values().stream()
              .anyMatch(r -> r.getPhase() == WorldChestState.Phase.PREPARED)) return null;
      var treasure = rules.actor(p, id);
      if (treasure == null || treasure.getState() != PropState.Hidden) return null;
      var prepared =
          old.unlocked(
              new WorldChestState.Unlock(
                  p, id, treasure, shield, rules, Instant.now().getEpochSecond()));
      p.getGameplay().worldChests(prepared);
      if (!prepared.valid(rules, p)) {
        p.getGameplay().worldChests(old);
        return null;
      }
      try {
        p.save();
      } catch (RuntimeException failed) {
        p.getGameplay().worldChests(old);
        return null;
      }
      return new Removal(p, shield, treasure, id, prepared, rules);
    }
  }

  public static boolean removed(Scene scene, GroupInfo group, PropInfo info) {
    var id = identity(scene, group.getId(), info.getID(), info.getPropID());
    if (id == null || info.getPropID() != GoldenSpearSources.SHIELD) return false;
    var p = scene.getPlayer();
    synchronized (p) {
      var rules = WorldChestRules.load();
      var state = p.getGameplay().getWorldChests();
      if (!state.valid(rules, p)) return false;
      var unlock = state.getUnlocks().get(id);
      return unlock != null
          && unlock.completed()
          && Objects.equals(unlock.getTriggerSource(), PropTimelines.source(info));
    }
  }

  public static void restore(EntityProp actor) {
    var id = identity(actor);
    if (id == null || actor.getPropId() != GoldenSpearSources.SPEAR) return;
    var p = actor.getScene().getPlayer();
    synchronized (p) {
      var rules = WorldChestRules.load();
      var state = p.getGameplay().getWorldChests();
      if (!state.valid(rules, p)
          || rules.groupProp(
                  id, actor.getScene().getDimensionId(), 300002, GoldenSpearSources.SPEAR)
              == null) return;
      var record = state.getSpears().get(id);
      if (record != null
          && Objects.equals(
              record.getSteps().getFirst().getPropSource(),
              PropTimelines.source(actor.getPropInfo()))) actor.setState(record.state(), false);
    }
  }

  private GoldenSpears() {}
}
