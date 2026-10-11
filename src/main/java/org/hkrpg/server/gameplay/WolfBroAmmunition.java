package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Server policy: one first magazine, one round per report and one pickup per real source monster. */
public final class WolfBroAmmunition {
  private final Player player;
  private WolfBroAmmoRules rules;
  private WolfBroState.Run run;
  private int id;

  public WolfBroAmmunition(Player player) { this.player = player; }

  private Retcode context() {
    var state = player.getGameplay().getWolfBro();
    id = state == null ? 0 : state.getActiveId();
    if (id == 0) return Retcode.REQ_PARA_INVALID;
    var checked = new WolfBroManager(player).query(id);
    if (checked.code() != Retcode.SUCC) return checked.code();
    run = checked.run(); rules = WolfBroAmmoRules.read(WolfBroCatalog.level(id));
    if (run == null || !run.isActive() || player.getUid() <= 0 || rules == null || !rules.scene(player))
      return Retcode.PLAYER_DATA_ERROR;
    var control = WolfBroControl.current(player, rules.level);
    if (control == null || control != rules.small) return Retcode.REQ_PARA_INVALID;
    return valid(player, run, rules) ? Retcode.SUCC : Retcode.PLAYER_DATA_ERROR;
  }

  private static boolean valid(Player player, WolfBroState.Run run, WolfBroAmmoRules rules) {
    for (var ammo : new WolfBroAmmoState[] {run.getAmmo(), run.getArchiveAmmo()}) {
      if (ammo == null) continue;
      if (!ammo.matches(player.getUid(), run.getUuid(), rules.signature)) return false;
      for (String key : ammo.getDefeated()) {
        var pair = key.split(":");
        var source = rules.sourceMonster(Integer.parseInt(pair[0]), Integer.parseInt(pair[1]));
        if (source == null) return false;
        var drop = ammo.getBullets().get(key);
        if (drop != null && !point(source.getPos(), drop)) return false;
      }
    }
    return true;
  }

  static boolean valid(Player player, WolfBroState.Run run) {
    if (run == null || run.getAmmo() == null && run.getArchiveAmmo() == null) return true;
    var rules = WolfBroAmmoRules.read(WolfBroCatalog.level(run.getId()));
    return rules != null && rules.scene(player) && valid(player, run, rules);
  }

  private WolfBroManager.Result reject(Retcode code) {
    return new WolfBroManager.Result(code, id, null, false);
  }

  private WolfBroManager.Result commit(WolfBroAmmoState next) {
    var state = player.getGameplay().getWolfBro();
    var changed = run.ammo(next);
    state.replace(id, changed);
    try { player.save(); }
    catch (RuntimeException failed) {
      state.replace(id, run);
      emu.lunarcore.LunarCore.getLogger().debug("WolfBro ammo save failed", failed);
      return reject(Retcode.SYSTEM_BUSY);
    }
    return new WolfBroManager.Result(Retcode.SUCC, id, changed, true);
  }

  public WolfBroManager.Result activate(int group, int instance) {
    synchronized (player) {
      var code = context(); if (code != Retcode.SUCC) return reject(code);
      if (rules.gun(player, group, instance) == null) return reject(Retcode.REQ_PARA_INVALID);
      var old = run.getAmmo();
      if (old != null && old.isActivated()) return new WolfBroManager.Result(Retcode.SUCC, id, run, false);
      var next = old == null ? new WolfBroAmmoState(player.getUid(), run.getUuid(), rules.signature) : old.copy();
      next.activate(rules.maximum); return commit(next);
    }
  }

  public WolfBroManager.Result use(List<Integer> ids) {
    return hit(ids, false);
  }

  /** The actual 6502 sender reports blast deaths without calling the ordinary ammo spender. */
  public WolfBroManager.Result explode(List<Integer> ids) {
    return hit(ids, true);
  }

  private WolfBroManager.Result hit(List<Integer> ids, boolean explosion) {
    synchronized (player) {
      var code = context(); if (code != Retcode.SUCC) return reject(code);
      var old = run.getAmmo();
      if (old == null || !old.isActivated() || !explosion && old.getCount() <= 0 || ids == null
          || ids.size() > 128 || ids.stream().anyMatch(i -> i == null || i <= 0)
          || new HashSet<>(ids).size() != ids.size()) return reject(Retcode.REQ_PARA_INVALID);
      if (explosion) {
        var source = WolfBroExplosionRules.check(player, rules);
        if (source != Retcode.SUCC) return reject(source);
        if (ids.isEmpty()) return new WolfBroManager.Result(Retcode.SUCC, id, run, false);
      }
      var targets = new ArrayList<EntityMonster>();
      var keys = new HashSet<String>();
      for (int entity : ids) {
        var monster = rules.monster(player, entity);
        if (monster == null) return reject(Retcode.REQ_PARA_INVALID);
        String key = WolfBroAmmoRules.key(monster.getGroupId(), monster.getInstId());
        if (old.getDefeated().contains(key) || !keys.add(key)) return reject(Retcode.REQ_PARA_INVALID);
        targets.add(monster);
      }
      if (old.getDefeated().size() + keys.size() > 512) return reject(Retcode.PLAYER_DATA_ERROR);
      var next = old.copy(); if (!explosion) next.spend();
      for (var monster : targets) next.drop(WolfBroAmmoRules.key(monster.getGroupId(), monster.getInstId()),
          new WolfBroState.Point(monster.getPos().getX(), monster.getPos().getY(), monster.getPos().getZ()));
      var result = commit(next);
      if (result.code() == Retcode.SUCC) for (var target : targets) player.getScene().defeatMonster(target);
      return result;
    }
  }

  public WolfBroManager.Result pickup(WolfBroState.Point point) {
    synchronized (player) {
      var code = context(); if (code != Retcode.SUCC) return reject(code);
      var old = run.getAmmo();
      if (old == null || !old.isActivated() || point == null || !point.bounded(10_000_000)
          || old.getCount() >= rules.maximum) return reject(Retcode.REQ_PARA_INVALID);
      String key = old.getBullets().entrySet().stream().filter(e -> same(e.getValue(), point))
          .map(Map.Entry::getKey).sorted().findFirst().orElse(null);
      if (key == null) return reject(Retcode.REQ_PARA_INVALID);
      var next = old.copy(); next.pickup(key, rules.maximum); return commit(next);
    }
  }

  static boolean point(emu.lunarcore.util.Position source, WolfBroState.Point point) {
    return source != null && point != null && source.getX() == point.getX()
        && source.getY() == point.getY() && source.getZ() == point.getZ();
  }
  private static boolean same(WolfBroState.Point left, WolfBroState.Point right) {
    return left.getX() == right.getX() && left.getY() == right.getY() && left.getZ() == right.getZ();
  }

  /** A committed kill survives group reload. This never creates a kill or advances a mission. */
  public static boolean spawns(Scene scene, GroupInfo group, MonsterInfo monster) {
    var state = scene.getPlayer().getGameplay().getWolfBro();
    if (state == null || state.getActiveId() == 0) return true;
    var run = state.getRuns() == null ? null : state.getRuns().get(state.getActiveId());
    var level = WolfBroCatalog.level(state.getActiveId());
    if (run == null || run.getAmmo() == null || level == null
        || scene.getPlaneId() != level.PlaneID() || scene.getFloorId() != level.FloorID()
        || !level.MonsterGroupIDList().contains(group.getId())) return true;
    var rules = WolfBroAmmoRules.read(level);
    if (rules == null || !run.getAmmo().matches(scene.getPlayer().getUid(), run.getUuid(), rules.signature)) return false;
    var source = rules.sourceMonster(group.getId(), monster.getID());
    return source == null || !run.getAmmo().getDefeated().contains(WolfBroAmmoRules.key(group.getId(), monster.getID()));
  }
}
