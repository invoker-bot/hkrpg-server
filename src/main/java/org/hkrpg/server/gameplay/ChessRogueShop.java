package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Original G35 MiracleShop actor. Its stock/pricing is the separately declared server policy. */
public final class ChessRogueShop {
  private static ChessRogueState context(Player player,int entity) {
    var run = player.getGameplay().getChessRogue();
    if (player.isInBattle() || !run.isInside() || run.isFailed() || !ChessRogueRoutes.valid(run)
        || run.getLayerIndex() != 1 || run.getCurrentCell() != 201 || run.getEnteredCell() != 201
        || !ChessRogueRoutes.sceneSource(player.getScene(),run,true)) return null;
    var scene = player.getScene();
    if (entity <= 0 || !(scene.getEntityById(entity) instanceof EntityNpc npc) || npc.getScene() != scene
        || npc.getEntityId() != entity || npc.getGroupId() != 35 || npc.getInstId() != 400001 || npc.getNpcId() != 1013
        || !scene.getLoadedGroups().equals(Set.of(34,35))) return null;
    var group = scene.getResourceGroups().get(35);
    var rows = group.getNPCList().stream().filter(row -> row.getID() == 400001).toList();
    if (rows.size() != 1) return null;
    var original = rows.getFirst();
    return original.getNPCID() == 1013 && original.getDialog() != null
        && ChessRogueRoutes.ACT.equals(original.getDialog().getLevelGraph())
        && original.isLoadOnInitial() && !original.isIsClientOnly() && !original.isIsDelete() ? run : null;
  }
  private static boolean valid(ChessRogueState run,ChessRogueState.Shop plan) {
    return plan != null && run.getRunId().equals(plan.getRunId()) && ChessRogueRoutes.SOURCE_COMMIT.equals(plan.getSourceCommit())
        && ChessRogueRoutes.RULE_ID.equals(plan.getRule()) && plan.getLayer() == 1 && plan.getCell() == 201
        && plan.getRoom() == 1310601 && plan.getGroup() == 35 && plan.getInstance() == 400001 && plan.getNpc() == 1013
        && plan.getPrice() == run.getMiraclePrice() && plan.getIds() != null && plan.getIds().equals(List.of(4))
        && plan.getPurchased() != null && Set.of(4).containsAll(plan.getPurchased())
        && plan.getPurchased().equals(run.getMiracles().keySet())
        && run.getMiracles().values().stream().allMatch(item -> item != null && item.getId() == 4
            && item.getAcquisitionId() != null && !item.getAcquisitionId().isBlank()
            && item.getTotalCount() == 1 && item.getUseCount() == 0 && item.getEffectParameters().equals(List.of(1.0)));
  }
  public static Retcode status(Player player,int entity) {
    synchronized (player) {
      var run = context(player,entity);
      if (run == null) return Retcode.SCENE_ENTITY_NOT_EXIST;
      return run.getShop() != null && !valid(run,run.getShop()) ? Retcode.PLAYER_DATA_ERROR : Retcode.SUCC;
    }
  }
  public static List<RogueMiracleShop.Quote> quotes(Player player,int entity) {
    synchronized (player) {
      var run = context(player,entity);
      if (run == null) return List.of();
      if (run.getShop() == null) {
        var before = run.copy();
        try { run.stock(new ChessRogueState.Shop(run)); player.save(); }
        catch (RuntimeException failed) { run.restore(before); throw failed; }
      }
      if (!valid(run,run.getShop())) return List.of();
      boolean bought = run.getShop().getPurchased().contains(4);
      return List.of(new RogueMiracleShop.Quote(4,run.getShop().getPrice(),bought,bought,!bought));
    }
  }
  public static Retcode buy(Player player,int entity,int id) {
    synchronized (player) {
      var run = context(player,entity);
      if (run == null) return Retcode.SCENE_ENTITY_NOT_EXIST;
      if (id != 4 || !valid(run,run.getShop()) || run.getShop().getPurchased().contains(id)) return Retcode.REQ_PARA_INVALID;
      if (run.getCoins() < run.getShop().getPrice()) return Retcode.ROGUE_COIN_NOT_ENOUGH;
      var before = run.copy();
      try { run.purchase(id); player.save(); }
      catch (RuntimeException failed) { run.restore(before); throw failed; }
      org.hkrpg.server.protocol.ChessRogue46.syncMiracleAcquisition(player,id);
      org.hkrpg.server.protocol.ChessRogue46.sync(player);
      return Retcode.SUCC;
    }
  }
  private ChessRogueShop() {}
}
