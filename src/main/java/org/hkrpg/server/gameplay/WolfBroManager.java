package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/**
 * Save checkpoints before acknowledging them; no client telemetry manufactures bullets or rewards.
 */
public final class WolfBroManager {
  public record Result(Retcode code, int id, WolfBroState.Run run, boolean changed) {}

  private final Player player;

  public WolfBroManager(Player player) {
    this.player = player;
  }

  private Retcode context(int id) {
    var level = WolfBroCatalog.level(id);
    if (level == null) return Retcode.REQ_PARA_INVALID;
    var scene = player.getScene();
    if (scene == null
        || scene.getPlayer() != player
        || scene.getExcel() != GameData.getMazePlaneExcelMap().get(level.PlaneID())
        || scene.getExcel().getPlaneID() != level.PlaneID()
        || scene.getPlaneId() != level.PlaneID()
        || scene.getFloorId() != level.FloorID()
        || !(scene.getPlaneType() == PlaneType.Town || scene.getPlaneType() == PlaneType.Maze))
      return Retcode.NOT_IN_SCENE;
    var floor = scene.getFloorInfo();
    if (floor == null
        || floor != GameData.getFloorInfo(level.PlaneID(), level.FloorID())
        || floor.getFloorID() != level.FloorID()
        || !scene.hasStoryGroup(level.ControlGroupID())) return Retcode.NOT_IN_SCENE;
    var groups = new HashSet<>(level.GroupIDList());
    groups.addAll(level.MonsterGroupIDList());
    groups.add(level.ControlGroupID());
    for (int idGroup : groups) {
      var group = floor.getGroups().get(idGroup);
      if (group == null
          || group.getId() != idGroup
          || !floor.isGroupInDimension(idGroup, scene.getDimensionId()))
        return Retcode.NOT_IN_SCENE;
    }
    if (player.isInBattle()) return Retcode.SYSTEM_BUSY;
    var state = player.getGameplay().getWolfBro();
    return state != null && state.valid() ? Retcode.SUCC : Retcode.PLAYER_DATA_ERROR;
  }

  private Result rejected(Retcode code, int id) {
    return new Result(code, id, null, false);
  }

  private Result commit(int id, WolfBroState.Run next, int active, int controlState) {
    var state = player.getGameplay().getWolfBro();
    var control = WolfBroControl.prepare(player, WolfBroCatalog.level(id), controlState);
    if (control == null) return rejected(Retcode.PLAYER_DATA_ERROR, id);
    var old = state.run(id);
    int oldActive = state.getActiveId();
    state.replace(id, next);
    state.active(active);
    control.apply();
    try {
      player.save();
    } catch (RuntimeException failed) {
      state.replace(id, old);
      state.active(oldActive);
      control.rollback();
      emu.lunarcore.LunarCore.getLogger().debug("WolfBro checkpoint save failed", failed);
      return rejected(Retcode.SYSTEM_BUSY, id);
    }
    control.publish();
    return new Result(Retcode.SUCC, id, next, true);
  }

  public Result query(int id) {
    synchronized (player) {
      var code = context(id);
      if (code != Retcode.SUCC) return rejected(code, id);
      if (!WolfBroAmmunition.valid(player, player.getGameplay().getWolfBro().run(id)))
        return rejected(Retcode.PLAYER_DATA_ERROR, id);
      return new Result(Retcode.SUCC, id, player.getGameplay().getWolfBro().run(id), false);
    }
  }

  public Result start(
      int id, int control, int state, int entry, int extra, WolfBroState.Motion motion) {
    synchronized (player) {
      var code = context(id);
      if (code != Retcode.SUCC) return rejected(code, id);
      var level = WolfBroCatalog.level(id);
      if (control != level.ControlGroupID()
          || state != level.StartState()
          || extra != 0
          || entry != 0 && entry != player.getScene().getEntryId()
          || motion == null
          || !motion.valid()) return rejected(Retcode.REQ_PARA_INVALID, id);
      var data = player.getGameplay().getWolfBro();
      if (data.getActiveId() != 0) return rejected(Retcode.SYSTEM_BUSY, id);
      if (data.run(id) == null && data.getRuns().size() >= 128)
        return rejected(Retcode.PLAYER_DATA_ERROR, id);
      return commit(
          id,
          new WolfBroState.Run(
              level, UUID.randomUUID().toString(), true, motion, level.StartState()),
          id,
          level.StartState());
    }
  }

  public Result archive(int id, WolfBroState.Motion motion) {
    synchronized (player) {
      var code = context(id);
      if (code != Retcode.SUCC) return rejected(code, id);
      if (motion == null || !motion.valid()) return rejected(Retcode.REQ_PARA_INVALID, id);
      var run = player.getGameplay().getWolfBro().run(id);
      if (run == null || !run.isActive()) return rejected(Retcode.REQ_PARA_INVALID, id);
      var level = WolfBroCatalog.level(id);
      var control = WolfBroControl.current(player, level);
      if (control == null) return rejected(Retcode.PLAYER_DATA_ERROR, id);
      if (!WolfBroAmmunition.valid(player, run)) return rejected(Retcode.PLAYER_DATA_ERROR, id);
      var next = run.checkpoint(motion, run.getArchiveControlState() == null ? null : control);
      return commit(id, next, id, control);
    }
  }

  public Result restore(int id) {
    synchronized (player) {
      var code = context(id);
      if (code != Retcode.SUCC) return rejected(code, id);
      var run = player.getGameplay().getWolfBro().run(id);
      if (run == null || !run.isActive()) return rejected(Retcode.REQ_PARA_INVALID, id);
      if (!WolfBroAmmunition.valid(player, run)) return rejected(Retcode.PLAYER_DATA_ERROR, id);
      var rewound = run.rewind();
      boolean ammoChanged = !WolfBroAmmoState.same(run.getAmmo(), rewound.getAmmo());
      var next = ammoChanged ? rewound : run;
      var pos = player.getPos();
      var rot = player.getRot();
      if (pos == null || rot == null || pos == rot) return rejected(Retcode.PLAYER_DATA_ERROR, id);
      var motion = run.getArchive();
      var control =
          run.getArchiveControlState() == null
              ? null
              : WolfBroControl.prepare(
                  player, WolfBroCatalog.level(id), run.getArchiveControlState());
      if (run.getArchiveControlState() != null && control == null)
        return rejected(Retcode.PLAYER_DATA_ERROR, id);
      if (same(pos, motion.getPos())
          && same(rot, motion.getRot())
          && (control == null || !control.changed()) && !ammoChanged)
        return new Result(Retcode.SUCC, id, run, false);
      var oldPos = new emu.lunarcore.util.Position(pos);
      var oldRot = new emu.lunarcore.util.Position(rot);
      pos.set(motion.getPos().getX(), motion.getPos().getY(), motion.getPos().getZ());
      rot.set(motion.getRot().getX(), motion.getRot().getY(), motion.getRot().getZ());
      if (control != null) control.apply();
      player.getGameplay().getWolfBro().replace(id, next);
      try {
        player.save();
      } catch (RuntimeException failed) {
        pos.set(oldPos);
        rot.set(oldRot);
        if (control != null) control.rollback();
        player.getGameplay().getWolfBro().replace(id, run);
        emu.lunarcore.LunarCore.getLogger().debug("WolfBro position restore save failed", failed);
        return rejected(Retcode.SYSTEM_BUSY, id);
      }
      if (control != null) control.publish();
      // The compiled client applies archive.motion from the restore response itself.
      // Ammo uses its real nested checkpoint fields; committed defeats are kept across rewind.
      return new Result(Retcode.SUCC, id, next, ammoChanged);
    }
  }

  private static boolean same(emu.lunarcore.util.Position current, WolfBroState.Point checkpoint) {
    return current.getX() == checkpoint.getX()
        && current.getY() == checkpoint.getY()
        && current.getZ() == checkpoint.getZ();
  }

  public Result quit(int id) {
    synchronized (player) {
      var code = context(id);
      if (code != Retcode.SUCC) return rejected(code, id);
      var run = player.getGameplay().getWolfBro().run(id);
      if (run == null || !run.isActive()) return rejected(Retcode.REQ_PARA_INVALID, id);
      var level = WolfBroCatalog.level(id);
      if (!WolfBroAmmunition.valid(player, run)) return rejected(Retcode.PLAYER_DATA_ERROR, id);
      var next = run.stop();
      return commit(id, next, 0, level.CloseState());
    }
  }

  public Retcode control(int group, int target, int entry, int extra) {
    synchronized (player) {
      var data = player.getGameplay().getWolfBro();
      if (data == null) return Retcode.PLAYER_DATA_ERROR;
      int id = data.getActiveId();
      if (id == 0) return Retcode.REQ_PARA_INVALID;
      var code = context(id);
      if (code != Retcode.SUCC) return code;
      var level = WolfBroCatalog.level(id);
      if (group != level.ControlGroupID()
          || extra != 0
          || entry != 0 && entry != player.getScene().getEntryId()) return Retcode.REQ_PARA_INVALID;
      if (data.run(id).getArchiveControlState() == null) return Retcode.PLAYER_DATA_ERROR;
      var current = WolfBroControl.current(player, level);
      if (current == null) return Retcode.PLAYER_DATA_ERROR;
      if (!WolfBroControl.canTransition(level, current, target)) return Retcode.REQ_PARA_INVALID;
      var update = WolfBroControl.prepare(player, level, target);
      if (update == null) return Retcode.PLAYER_DATA_ERROR;
      if (!update.changed()) return Retcode.SUCC;
      update.apply();
      try {
        player.save();
      } catch (RuntimeException failed) {
        update.rollback();
        emu.lunarcore.LunarCore.getLogger().debug("WolfBro control save failed", failed);
        return Retcode.SYSTEM_BUSY;
      }
      update.publish();
      return Retcode.SUCC;
    }
  }
}
