package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

public final class RogueMiracleShop {
  public record Quote(int id, int cost, boolean purchased, boolean owned, boolean available) {}

  public static Retcode status(Player player, int entity) {
    synchronized (player) {
      var key = RogueShopContext.key(player, entity, 1013, "MiracleShop");
      if (key == null) return Retcode.SCENE_ENTITY_NOT_EXIST;
      var shops = player.getGameplay().getRogue().getMiracleShops();
      return shops == null || (shops.containsKey(key) && !RogueShopContext.valid(shops.get(key)))
          ? Retcode.REQ_PARA_INVALID
          : Retcode.SUCC;
    }
  }

  public static List<Quote> quotes(Player player, int entity) {
    synchronized (player) {
      var key = RogueShopContext.key(player, entity, 1013, "MiracleShop");
      if (key == null) return List.of();
      var run = player.getGameplay().getRogue();
      var shops = run.getMiracleShops();
      if (shops == null || (shops.containsKey(key) && !RogueShopContext.valid(shops.get(key))))
        return List.of();
      var plan = shops.get(key);
      if (plan == null) {
        var candidates =
            GameplayDefinitions.miracles.keySet().stream()
                .filter(id -> RogueMiracles.canAcquire(player, id))
                .sorted()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.shuffle(candidates, new Random(Objects.hash(run.getRunId(), key)));
        var stock = new LinkedHashMap<Integer, GameplayState.RogueShopOffer>();
        // Project pricing and uniform stock: no sale price or stock formula in the supplied tables.
        candidates.stream()
            .limit(3)
            .forEach(id -> stock.put(id, new GameplayState.RogueShopOffer(1, RogueMiracles.fragmentPlan(run, 200))));
        plan = new GameplayState.RogueShopPlan(stock);
        shops.put(key, plan);
        player.save();
      }
      return plan.getOffers().entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .map(
              e -> {
                var offer = e.getValue();
                boolean owned = run.getMiracles().containsKey(e.getKey());
                return new Quote(
                    e.getKey(),
                    offer.getCost(),
                    offer.isPurchased(),
                    owned,
                    !offer.isPurchased() && !owned && RogueMiracles.canAcquire(player, e.getKey()));
              })
          .toList();
    }
  }

  public static Retcode buy(Player player, int entity, int id) {
    synchronized (player) {
      var key = RogueShopContext.key(player, entity, 1013, "MiracleShop");
      if (key == null) return Retcode.SCENE_ENTITY_NOT_EXIST;
      var run = player.getGameplay().getRogue();
      var shops = run.getMiracleShops();
      var plan = shops == null ? null : shops.get(key);
      if (!RogueShopContext.valid(plan)) return Retcode.REQ_PARA_INVALID;
      var offer = plan.getOffers().get(id);
      if (offer == null
          || offer.isPurchased()
          || run.getMiracles().containsKey(id)
          || !RogueMiracles.canAcquire(player, id)) return Retcode.REQ_PARA_INVALID;
      var status = RogueMiracles.paymentStatus(run, offer.debitPlan());
      if (status != Retcode.SUCC) return status;
      if (id == 21) {
        var replacement = RogueMiracleDice.prepare(player, offer.debitPlan());
        var acquired = replacement == null ? null : replacement.commit(player);
        if (acquired == null) return Retcode.REQ_PARA_INVALID;
        offer.purchase();
        boolean resonancePending = RogueResonance.queue(run);
        player.save();
        acquired.syncEffects(player);
        if (resonancePending) org.hkrpg.server.protocol.Universe46.syncPending(player);
        org.hkrpg.server.protocol.RogueTalent46.syncCurrency(player);
        return Retcode.SUCC;
      }
      var acquired = RogueMiracles.acquireDeferred(player, id);
      if (acquired == null) return Retcode.REQ_PARA_INVALID;
      var payment = RogueMiracles.pay(run, offer.debitPlan());
      if (payment.retcode() != Retcode.SUCC)
        throw new IllegalStateException("Prechecked merchant payment changed");
      offer.purchase();
      boolean resonancePending = RogueResonance.queue(run);
      player.save();
      payment.syncEffects(player);
      acquired.syncEffects(player);
      if (resonancePending) org.hkrpg.server.protocol.Universe46.syncPending(player);
      org.hkrpg.server.protocol.Universe46.syncMiracle(player, id, true, 0);
      org.hkrpg.server.protocol.RogueTalent46.syncCurrency(player);
      return Retcode.SUCC;
    }
  }

  private RogueMiracleShop() {}
}
