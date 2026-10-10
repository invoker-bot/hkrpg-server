package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.util.*;

/** Source-bound accepted destruction, with one run-local receipt and permanent actual count. */
public final class RoguePropDestruction {
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

  public static boolean validReceipts(Set<String> receipts) {
    if (receipts == null || receipts.size() > 4096) return false;
    for (String key : receipts) {
      if (key == null || key.length() > 65) return false;
      var parts = key.split(":", -1);
      if (parts.length != 6) return false;
      for (String part : parts)
        try {
          int id = Integer.parseInt(part);
          if (id <= 0 || !Integer.toString(id).equals(part)) return false;
        } catch (NumberFormatException invalid) {
          return false;
        }
    }
    return true;
  }

  public static boolean alreadyDestroyed(Scene scene, GroupInfo group, PropInfo info) {
    var excel = GameData.getPropExcelMap().get(info.getPropID());
    if (excel == null
        || excel.getPropType() != PropType.PROP_DESTRUCT
            && excel.getPropType() != PropType.PROP_NO_REWARD_DESTRUCT) return false;
    var run = scene.getPlayer().getGameplay().getRogue();
    var receipts = run.getDestroyedDestructibleProps();
    return !validReceipts(receipts)
        || receipts.contains(key(run, scene, group.getId(), info.getID()));
  }

  /**
   * Unknown or synthetic entities keep their existing behavior without becoming achievement
   * evidence.
   */
  public static boolean requiresReceipt(Player player, EntityProp prop) {
    var scene = player.getScene();
    var run = player.getGameplay().getRogue();
    var room = GameplayDefinitions.rooms.get(run.getRoomId());
    var entry = room == null ? null : GameData.getMapEntranceExcelMap().get(room.MapEntrance());
    if (scene == null
        || scene.getPlaneType() != PlaneType.Rogue
        || entry == null
        || entry.getId() != room.MapEntrance()
        || !run.isInside()
        || run.getRoster().isEmpty()
        || scene.getDimensionId() != 0
        || scene.getFloorInfo() == null
        || player.getEntryId() != room.MapEntrance()
        || scene.getPlaneId() != entry.getPlaneID()
        || scene.getFloorId() != entry.getFloorID()
        || !Objects.equals(run.getRooms().get(run.getSiteId()), run.getRoomId())
        || prop == null
        || !scene.canDestroyProp(prop)
        || prop.getGroupId() <= 0
        || prop.getInstId() <= 0
        || GameData.getPropExcelMap().get(prop.getPropId()) != prop.getExcel()
        || !UniverseEntityLoader.roomGroups(player).contains(prop.getGroupId())) return false;
    var group = scene.getResourceGroups().get(prop.getGroupId());
    var info = prop.getPropInfo();
    if (group == null
        || group.getId() != prop.getGroupId()
        || group.getPropList() == null
        || info == null
        || info.getID() != prop.getInstId()
        || info.getPropID() != prop.getPropId()
        || !info.isLoadOnInitial()
        || info.isIsDelete()
        || info.isIsClientOnly()) return false;
    var matches =
        group.getPropList().stream()
            .filter(Objects::nonNull)
            .filter(r -> r.getID() == prop.getInstId())
            .toList();
    return matches.size() == 1 && matches.getFirst() == info;
  }

  public static Destruction prepare(Player player, EntityProp prop) {
    synchronized (player) {
      if (!requiresReceipt(player, prop)) return null;
      var run = player.getGameplay().getRogue();
      var scene = player.getScene();
      var history = player.getGameplay().getRogueDestructions();
      var receipts = run.getDestroyedDestructibleProps();
      String key = key(run, scene, prop.getGroupId(), prop.getInstId());
      if (run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
          || run.pending()
          || player.isInBattle()
          || history == null
          || !history.canRecord()
          || !validReceipts(receipts)
          || receipts.size() >= 4096
          || receipts.contains(key)) return null;
      return new Destruction(
          player, run, scene, prop, prop.getEntityId(), key, history, history.total(), receipts);
    }
  }

  public static final class Destruction {
    private final Player player;
    private final GameplayState.RogueRun run;
    private final Scene scene;
    private final EntityProp prop;
    private final int entity, before;
    private final String key;
    private final RogueDestructionState history;
    private final Set<String> receipts;
    private boolean completed;

    private Destruction(
        Player player,
        GameplayState.RogueRun run,
        Scene scene,
        EntityProp prop,
        int entity,
        String key,
        RogueDestructionState history,
        int before,
        Set<String> receipts) {
      this.player = player;
      this.run = run;
      this.scene = scene;
      this.prop = prop;
      this.entity = entity;
      this.key = key;
      this.history = history;
      this.before = before;
      this.receipts = receipts;
    }

    public void complete() {
      synchronized (player) {
        if (completed) return;
        if (player.getGameplay().getRogue() != run
            || player.getScene() != scene
            || prop.getEntityId() != 0
            || scene.getEntities().containsKey(entity)
            || player.getGameplay().getRogueDestructions() != history
            || history.total() != before
            || !history.canRecord()
            || run.getDestroyedDestructibleProps() != receipts
            || !validReceipts(receipts)
            || receipts.size() >= 4096
            || receipts.contains(key))
          throw new IllegalStateException(
              "Prepared prop destruction changed before its accepted removal");
        receipts.add(key);
        if (!history.record()) throw new IllegalStateException("Prechecked prop count changed");
        completed = true;
        player.save();
        player.getQuestManager().onRogueDestructionProgress();
      }
    }
  }

  private RoguePropDestruction() {}
}
