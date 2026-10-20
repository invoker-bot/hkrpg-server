package org.hkrpg.server.gameplay;

import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;

/** Reserve the original chest before inventory delivery; publish state and credit after saving. */
public final class WorldChests {
  public record Result(Retcode retcode, EntityProp prop) {}

  private static final Map<Integer, WorldChestSources.Location> LOCATIONS =
      GoldenSpearSources.merge(MascotSources.LOCATIONS, BaseZoneChestSources.LOCATIONS);

  private static Integer identity(EntityProp actor) {
    if (actor == null || actor.getScene() == null) return null;
    var scene = actor.getScene();
    return LOCATIONS.entrySet().stream()
        .filter(
            e ->
                e.getValue().plane() == scene.getPlaneId()
                    && e.getValue().floor() == scene.getFloorId()
                    && e.getValue().group() == actor.getGroupId()
                    && e.getValue().instance() == actor.getInstId()
                    && e.getValue().prop() == actor.getPropId())
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(null);
  }

  public static boolean applies(EntityProp actor) {
    return identity(actor) != null;
  }

  public static boolean golden(EntityProp actor) {
    var id = identity(actor);
    return id != null && GoldenSpearSources.PUZZLES.containsKey(id);
  }

  public static boolean mascot(EntityProp actor) {
    var id = identity(actor);
    return id != null && MascotSources.PUZZLES.containsKey(id);
  }

  public static Retcode validate(Player p, int config, int entity) {
    var base = SceneInteractions.validate(p, config, entity);
    if (base != Retcode.SUCC) return base;
    if (config != 112131 && config != TimeRelayFlameSources.UNLOCK)
      return Retcode.INTERACT_TYPE_INVALID;
    var actor = (EntityProp) p.getScene().getEntityById(entity);
    var id = identity(actor);
    var rules = WorldChestRules.load();
    if (id == null || rules == null || rules.loot(id) == null)
      return Retcode.REWARD_CONFIG_NOT_EXIST;
    var state = p.getGameplay().getWorldChests();
    if (!state.valid(rules, p)) return Retcode.PLAYER_DATA_ERROR;
    if (state.getRecords().values().stream()
        .anyMatch(r -> r.getPhase() == WorldChestState.Phase.PREPARED)) return Retcode.SYSTEM_BUSY;
    if (state.getUnlocks().values().stream().anyMatch(r -> !r.completed()))
      return Retcode.SYSTEM_BUSY;
    if (state.getRecords().containsKey(id)) return Retcode.SYSTEM_BUSY;
    if (config == TimeRelayFlameSources.UNLOCK) {
      if (!rules.flame(id)) return Retcode.INTERACT_TYPE_INVALID;
      if (state.getUnlocks().containsKey(id)) return Retcode.SYSTEM_BUSY;
    } else if (rules.puzzle(id) && !state.getUnlocks().containsKey(id)) {
      return Retcode.UNSUPPORTED_PROP_STATE;
    }
    if (rules.mascot(id)) {
      var r = state.getMascots().get(id);
      if (r == null || r.state() != 4) return Retcode.UNSUPPORTED_PROP_STATE;
    }
    return rules.actor(p, id) == actor ? Retcode.SUCC : Retcode.MISSION_SCENE_NOT_MATCH;
  }

  public static Result open(Player p, int config, int entity) {
    synchronized (p) {
      var code = validate(p, config, entity);
      if (code != Retcode.SUCC) return new Result(code, null);
      var actor = (EntityProp) p.getScene().getEntityById(entity);
      int id = identity(actor);
      var rules = WorldChestRules.load();
      if (rules == null) return new Result(Retcode.REWARD_CONFIG_NOT_EXIST, null);
      var old = p.getGameplay().getWorldChests();
      var now = Instant.now();
      if (config == TimeRelayFlameSources.UNLOCK) {
        var unlocked =
            old.unlocked(new WorldChestState.Unlock(p, id, actor, rules, now.getEpochSecond()));
        p.getGameplay().worldChests(unlocked);
        if (!unlocked.valid(rules, p)) {
          p.getGameplay().worldChests(old);
          return new Result(Retcode.PLAYER_DATA_ERROR, null);
        }
        try {
          p.save();
        } catch (RuntimeException failed) {
          p.getGameplay().worldChests(old);
          return new Result(Retcode.SYSTEM_BUSY, null);
        }
        actor.setState(PropState.ChestClosed);
        return new Result(Retcode.SUCC, actor);
      }
      var observation =
          new WorldChestState.Opening(
              p, id, actor, rules, old.getQuests(), rules.window(p, now), now.getEpochSecond());
      var prepared = old.prepared(observation);
      String key = observation.rewardKey();
      if (p.getGameplay().getRewards().containsKey(key))
        return new Result(Retcode.PLAYER_DATA_ERROR, null);
      p.getGameplay().worldChests(prepared);
      p.getGameplay().getRewards().put(key, new GameplayState.Receipt(0));
      if (!prepared.valid(rules, p)) {
        p.getGameplay().worldChests(old);
        p.getGameplay().getRewards().remove(key);
        return new Result(Retcode.PLAYER_DATA_ERROR, null);
      }
      try {
        p.save();
      } catch (RuntimeException failed) {
        p.getGameplay().worldChests(old);
        p.getGameplay().getRewards().remove(key);
        return new Result(Retcode.SYSTEM_BUSY, null);
      }
      var loot = new ItemParamMap();
      observation.getLoot().forEach(loot::add);
      try {
        var granted = p.getInventory().grantItems(loot, true);
        if (granted == null || !granted.complete())
          return new Result(Retcode.PLAYER_DATA_ERROR, null);
      } catch (RuntimeException interrupted) {
        return new Result(Retcode.SYSTEM_BUSY, null);
      }
      var complete = prepared.opened(id);
      p.getGameplay().worldChests(complete);
      p.getGameplay().getRewards().get(key).complete();
      if (!complete.valid(rules, p)) {
        p.getGameplay().worldChests(prepared);
        p.getGameplay().getRewards().put(key, new GameplayState.Receipt(0));
        return new Result(Retcode.PLAYER_DATA_ERROR, null);
      }
      try {
        p.save();
      } catch (RuntimeException interrupted) {
        p.getGameplay().worldChests(prepared);
        p.getGameplay().getRewards().put(key, new GameplayState.Receipt(0));
        return new Result(Retcode.SYSTEM_BUSY, null);
      }
      actor.setState(PropState.ChestUsed);
      if (p.getSession() != null && p.getSession().getProtocol().experimental()) {
        var schema = p.getSession().getProtocol().clientSchema().message("OpenChestScNotify");
        var notify = com.google.protobuf.DynamicMessage.newBuilder(schema);
        notify.setField(schema.findFieldByName("chest_id"), id);
        p.getSession().sendClientPacket("OpenChestScNotify", notify.build().toByteArray());
      }
      p.getQuestManager().onWorldChestProgress();
      p.getMissionManager()
          .onPropInteraction(
              p.getFloorId(), actor.getGroupId(), actor.getInstId(), PropState.ChestUsed.getVal());
      return new Result(Retcode.SUCC, actor);
    }
  }

  public static void restore(EntityProp actor) {
    var id = identity(actor);
    if (id == null) return;
    var p = actor.getScene().getPlayer();
    synchronized (p) {
      var rules = WorldChestRules.load();
      var state = p.getGameplay().getWorldChests();
      if (!state.valid(rules, p)) return;
      var record = state.getRecords().get(id);
      if (rules.original(id, actor.getScene().getDimensionId()) == null) return;
      if (record != null
          && record.getPhase() == WorldChestState.Phase.OPEN
          && Objects.equals(record.getPropSource(), PropTimelines.source(actor.getPropInfo())))
        actor.setState(PropState.ChestUsed, false);
      else {
        var unlock = state.getUnlocks().get(id);
        if (unlock != null
            && unlock.completed()
            && Objects.equals(unlock.getPropSource(), PropTimelines.source(actor.getPropInfo())))
          actor.setState(PropState.ChestClosed, false);
      }
    }
  }

  private WorldChests() {}
}
