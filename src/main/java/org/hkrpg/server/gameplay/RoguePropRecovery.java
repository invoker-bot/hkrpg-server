package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.PropInfo;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.send.PacketSyncLineupNotify;
import java.util.*;

/** Recovery follows accepted destruction, with a run-local receipt shared by both curio aliases. */
public final class RoguePropRecovery {
  public static boolean recoveryProp(EntityProp prop) {
    return prop != null && (prop.getExcel().isRecoverHp() || prop.getExcel().isRecoverMp());
  }

  private static String key(GameplayState.RogueRun run, Scene scene, int group, int instance) {
    return run.getSiteId()
        + ":"
        + run.getRoomId()
        + ":"
        + scene.getPlaneId()
        + ":"
        + scene.getFloorId()
        + ":"
        + group
        + ":"
        + instance;
  }

  public static boolean alreadyDestroyed(Scene scene, GroupInfo group, PropInfo info) {
    var excel = GameData.getPropExcelMap().get(info.getPropID());
    if (excel == null || !excel.isRecoverHp() && !excel.isRecoverMp()) return false;
    var run = scene.getPlayer().getGameplay().getRogue();
    return run.getDestroyedRecoveryProps() == null
        || run.getDestroyedRecoveryProps().contains(key(run, scene, group.getId(), info.getID()));
  }

  private static boolean context(Player player, GameplayState.RogueRun run, Scene scene) {
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    var entry = room == null ? null : GameData.getMapEntranceExcelMap().get(room.MapEntrance());
    return entry != null
        && scene != null
        && player.getScene() == scene
        && scene.getPlaneType() == PlaneType.Rogue
        && run.isInside()
        && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING
        && !run.pending()
        && !player.isInBattle()
        && !run.getRoster().isEmpty()
        && player.getEntryId() == room.MapEntrance()
        && scene.getPlaneId() == entry.getPlaneID()
        && scene.getFloorId() == entry.getFloorID()
        && Objects.equals(run.getRooms().get(run.getSiteId()), run.getRoomId())
        && run.getDestroyedRecoveryProps() != null;
  }

  public static Recovery prepare(Player player, EntityProp prop) {
    var run = player.getGameplay().getRogue();
    var scene = player.getScene();
    if (!context(player, run, scene)
        || !recoveryProp(prop)
        || !scene.canDestroyProp(prop)
        || prop.getGroupId() <= 0
        || prop.getInstId() <= 0
        || scene.getFloorInfo() == null
        || GameData.getPropExcelMap().get(prop.getPropId()) != prop.getExcel()) return null;
    var group = scene.getFloorInfo().getGroups().get(prop.getGroupId());
    var info = prop.getPropInfo();
    if (group == null
        || group.getPropList() == null
        || info == null
        || !UniverseEntityLoader.roomGroups(player).contains(prop.getGroupId())
        || group.getPropList().stream().noneMatch(row -> row == info)
        || info.getID() != prop.getInstId()
        || info.getPropID() != prop.getPropId()
        || !info.isLoadOnInitial()
        || info.isIsDelete()
        || info.isIsClientOnly()) return null;
    String key = key(run, scene, prop.getGroupId(), prop.getInstId());
    if (run.getDestroyedRecoveryProps().contains(key)
        || run.getMp() < 0
        || run.getMp() > player.getCurrentLineup().getMaxMp()
        || run.getRoster().stream()
            .anyMatch(
                id -> {
                  var vital = run.getVitals().get(id);
                  return vital == null || vital.getHp() < 0 || vital.getHp() > 10000;
                })) return null;
    int multiplier = RogueMiracles.active(run, 18) || RogueMiracles.active(run, 64) ? 2 : 1;
    // Existing LunarCore base recovery is a project rule, not a parsed official prop effect.
    return new Recovery(
        player,
        run,
        scene,
        prop,
        prop.getEntityId(),
        key,
        prop.getExcel().isRecoverMp() ? 2 * multiplier : 0,
        prop.getExcel().isRecoverMp() ? 0 : 2500 * multiplier);
  }

  public static final class Recovery {
    private final Player player;
    private final GameplayState.RogueRun run;
    private final Scene scene;
    private final EntityProp prop;
    private final int entity, mp, hp;
    private final String key;
    private boolean complete;

    private Recovery(
        Player player,
        GameplayState.RogueRun run,
        Scene scene,
        EntityProp prop,
        int entity,
        String key,
        int mp,
        int hp) {
      this.player = player;
      this.run = run;
      this.scene = scene;
      this.prop = prop;
      this.entity = entity;
      this.key = key;
      this.mp = mp;
      this.hp = hp;
    }

    public void complete() {
      synchronized (player) {
        if (complete
            || player.getGameplay().getRogue() != run
            || !context(player, run, scene)
            || prop.getEntityId() != 0
            || scene.getEntities().get(entity) == prop
            || !run.getDestroyedRecoveryProps().add(key)) return;
        complete = true;
        run.setMp(Math.min(player.getCurrentLineup().getMaxMp(), run.getMp() + mp));
        for (int id : run.getRoster()) {
          var vital = run.getVitals().get(id);
          if (vital.getHp() > 0) vital.setHp(Math.min(10000, vital.getHp() + hp));
        }
        player.save();
        if (player.isOnline())
          player.sendPacket(new PacketSyncLineupNotify(player.getUniverseManager().lineup()));
      }
    }
  }

  private RoguePropRecovery() {}
}
