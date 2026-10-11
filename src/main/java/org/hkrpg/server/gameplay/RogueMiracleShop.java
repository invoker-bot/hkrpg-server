package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

public final class RogueMiracleShop {
  private static final Gson GSON = new Gson();
  public record Quote(int id, int cost, boolean purchased, boolean owned, boolean available) {}

  private static boolean valid(Player player, RogueShopContext.Context context,
      GameplayState.RogueShopPlan plan, boolean unboundAllowed) {
    if (!RogueShopContext.valid(plan)) return false;
    var lease = plan.getRestMerchant();
    if (context.recipe() == null) return lease == null;
    if (lease != null) return lease.valid(player.getGameplay().getRogue(), plan);
    // Older stock needs a source lease and a successful save before it can be exposed.
    return unboundAllowed && plan.getOffers().size() <= context.recipe().stockLimit()
        && plan.getOffers().values().stream().allMatch(offer -> !offer.isPurchased()
            && offer.getCost() >= context.recipe().basePrice()
            && offer.getCost() <= context.recipe().basePrice() + context.recipe().basePrice() / 4);
  }

  public static Retcode status(Player player, int entity) {
    synchronized (player) {
      var context = RogueShopContext.context(player, entity, 1013, "MiracleShop");
      if (context == null) return Retcode.SCENE_ENTITY_NOT_EXIST;
      var shops = player.getGameplay().getRogue().getMiracleShops();
      return shops == null || shops.containsKey(context.key()) && !valid(player, context, shops.get(context.key()), true)
          ? Retcode.REQ_PARA_INVALID : Retcode.SUCC;
    }
  }

  public static List<Quote> quotes(Player player, int entity) {
    synchronized (player) {
      var context = RogueShopContext.context(player, entity, 1013, "MiracleShop");
      if (context == null) return List.of();
      var run = player.getGameplay().getRogue();
      var shops = run.getMiracleShops();
      if (shops == null || shops.containsKey(context.key()) && !valid(player, context, shops.get(context.key()), true))
        return List.of();
      var plan = shops.get(context.key());
      if (plan == null || context.recipe() != null && plan.getRestMerchant() == null) {
        var before = copy(player);
        try {
          if (plan == null) {
            var candidates = GameplayDefinitions.miracles.keySet().stream()
                .filter(id -> RogueMiracles.canAcquire(player, id)).sorted()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            Collections.shuffle(candidates, new Random(Objects.hash(run.getRunId(), context.key())));
            var stock = new LinkedHashMap<Integer, GameplayState.RogueShopOffer>();
            int limit = context.recipe() == null ? 3 : context.recipe().stockLimit();
            int price = context.recipe() == null ? 200 : context.recipe().basePrice();
            // These prices, stock limits and uniform draw are explicit server policies.
            candidates.stream().limit(limit).forEach(id -> stock.put(id,
                new GameplayState.RogueShopOffer(1, RogueMiracles.fragmentPlan(run, price))));
            plan = context.recipe() == null ? new GameplayState.RogueShopPlan(stock)
                : new GameplayState.RogueShopPlan(stock, new RogueRestMerchantLease(run, context.recipe()));
          } else {
            plan = new GameplayState.RogueShopPlan(plan.getOffers(), new RogueRestMerchantLease(run, context.recipe()));
          }
          if (!valid(player, context, plan, false)) throw new IllegalStateException("Invalid prepared merchant stock");
          shops.put(context.key(), plan);
          player.save();
        } catch (RuntimeException failed) {
          player.getGameplay().restoreMiracleShop(before);
          throw failed;
        }
      }
      return plan.getOffers().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> {
        var offer = e.getValue();
        boolean owned = run.getMiracles().containsKey(e.getKey());
        return new Quote(e.getKey(), offer.getCost(), offer.isPurchased(), owned,
            !offer.isPurchased() && !owned && RogueMiracles.canAcquire(player, e.getKey()));
      }).toList();
    }
  }

  public static Retcode buy(Player player, int entity, int id) {
    synchronized (player) {
      var context = RogueShopContext.context(player, entity, 1013, "MiracleShop");
      if (context == null) return Retcode.SCENE_ENTITY_NOT_EXIST;
      var run = player.getGameplay().getRogue();
      var shops = run.getMiracleShops();
      var plan = shops == null ? null : shops.get(context.key());
      if (!valid(player, context, plan, false)) return Retcode.REQ_PARA_INVALID;
      var offer = plan.getOffers().get(id);
      if (offer == null || offer.isPurchased() || run.getMiracles().containsKey(id)
          || !RogueMiracles.canAcquire(player, id)) return Retcode.REQ_PARA_INVALID;
      var status = RogueMiracles.paymentStatus(run, offer.debitPlan());
      if (status != Retcode.SUCC) return status;
      var before = copy(player);
      var priorAcquisitions = new HashSet<String>();
      run.getMiracles().values().forEach(item -> priorAcquisitions.add(item.getAcquisitionId()));
      RogueMiracles.Acquisition acquired;
      RogueMiracles.FragmentPayment payment = null;
      boolean resonancePending;
      try {
        if (id == 21) {
          var replacement = RogueMiracleDice.prepare(player, offer.debitPlan());
          acquired = replacement == null ? null : replacement.commit(player);
          if (acquired == null) return Retcode.REQ_PARA_INVALID;
        } else {
          acquired = RogueMiracles.acquireDeferred(player, id);
          if (acquired == null) return Retcode.REQ_PARA_INVALID;
          payment = RogueMiracles.pay(run, offer.debitPlan());
          if (payment.retcode() != Retcode.SUCC) throw new IllegalStateException("Prechecked merchant payment changed");
        }
        offer.purchase();
        if (plan.getRestMerchant() != null) plan.getRestMerchant().record(id, offer.getCost(), priorAcquisitions, run);
        resonancePending = RogueResonance.queue(run);
        player.save();
      } catch (RuntimeException failed) {
        player.getGameplay().restoreMiracleShop(before);
        throw failed;
      }
      if (payment != null) payment.syncEffects(player);
      acquired.syncEffects(player);
      if (resonancePending) org.hkrpg.server.protocol.Universe46.syncPending(player);
      if (id != 21) org.hkrpg.server.protocol.Universe46.syncMiracle(player, id, true, 0);
      org.hkrpg.server.protocol.RogueTalent46.syncCurrency(player);
      return Retcode.SUCC;
    }
  }

  private static GameplayState copy(Player player) {
    return GSON.fromJson(GSON.toJson(player.getGameplay()), GameplayState.class);
  }

  private RogueMiracleShop() {}
}
