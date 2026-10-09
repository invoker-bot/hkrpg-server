package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Resource-bound blessing merchant. */
public final class RogueBuffShop {
  public record Quote(
      int id, int level, int cost, boolean purchased, boolean owned, boolean available) {}

  private static String context(Player p, int entity) {
    return RogueShopContext.key(p, entity, 1007, "BuffShop");
  }

  public static Retcode status(Player player, int entity) {
    synchronized (player) {
      var ctx = context(player, entity);
      if (ctx == null) return Retcode.SCENE_ENTITY_NOT_EXIST;
      var shops = player.getGameplay().getRogue().getBlessingShops();
      if (shops == null || (shops.containsKey(ctx) && !valid(shops.get(ctx))))
        return Retcode.REQ_PARA_INVALID;
      return Retcode.SUCC;
    }
  }

  private static GameplayDefinitions.Blessing resource(Player p, int id) {
    if (GameData.getMazeBuffExcelMap().get(id, 1) == null) return null;
    var rows =
        GameplayDefinitions.blessings.stream()
            .filter(b -> b.MazeBuffID() == id && b.MazeBuffLevel() == 1 && b.RogueVersion() == 1)
            .toList();
    if (rows.size() != 1) return null;
    var row = rows.getFirst();
    return row.AeonID() == 0
            && row.BattleEventBuffType() == null
            && basePrice(row.RogueBuffCategory()) >= 0
            && (row.UnlockIDList() == null
                || row.UnlockIDList().stream().allMatch(u -> RogueUnlocks.open(p, u)))
        ? row
        : null;
  }

  /** Project pricing; the supplied resource snapshot does not specify merchant sale prices. */
  private static int basePrice(String category) {
    if (category == null) return -1;
    return switch (category) {
      case "Common" -> 100;
      case "Rare" -> 150;
      case "Legendary" -> 250;
      default -> -1;
    };
  }

  private static boolean valid(GameplayState.RogueShopPlan plan) {
    return RogueShopContext.valid(plan);
  }

  public static List<Quote> quotes(Player player, int entity) {
    synchronized (player) {
      var ctx = context(player, entity);
      if (ctx == null) return List.of();
      var run = player.getGameplay().getRogue();
      var shops = run.getBlessingShops();
      if (shops == null || (shops.containsKey(ctx) && !valid(shops.get(ctx)))) return List.of();
      var plan = shops.get(ctx);
      if (plan == null) {
        var candidates =
            GameplayDefinitions.blessings.stream()
                .map(GameplayDefinitions.Blessing::MazeBuffID)
                .distinct()
                .filter(id -> !run.getBlessings().containsKey(id) && resource(player, id) != null)
                .sorted()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.shuffle(candidates, new Random(Objects.hash(run.getRunId(), ctx)));
        var stock = new LinkedHashMap<Integer, GameplayState.RogueShopOffer>();
        for (int id : candidates.stream().limit(3).toList())
          stock.put(
              id,
              new GameplayState.RogueShopOffer(
                  1,
                  RogueMiracles.fragmentPlan(
                      run, basePrice(resource(player, id).RogueBuffCategory()))));
        plan = new GameplayState.RogueShopPlan(stock);
        run.getBlessingShops().put(ctx, plan);
        player.save();
      }
      if (!valid(plan)) return List.of();
      return plan.getOffers().entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .map(
              e -> {
                var offer = e.getValue();
                boolean owned = run.getBlessings().containsKey(e.getKey());
                return new Quote(
                    e.getKey(),
                    offer.getLevel(),
                    offer.getCost(),
                    offer.isPurchased(),
                    owned,
                    !offer.isPurchased() && !owned && resource(player, e.getKey()) != null);
              })
          .toList();
    }
  }

  public static Retcode buy(Player player, int entity, List<Integer> ids) {
    synchronized (player) {
      var ctx = context(player, entity);
      if (ctx == null) return Retcode.SCENE_ENTITY_NOT_EXIST;
      if (ids == null
          || ids.isEmpty()
          || ids.size() > 3
          || ids.stream().anyMatch(id -> id == null || id <= 0)
          || new HashSet<>(ids).size() != ids.size()) return Retcode.REQ_PARA_INVALID;
      var selected = List.copyOf(ids);
      var run = player.getGameplay().getRogue();
      if (run.getBlessingShops() == null) return Retcode.REQ_PARA_INVALID;
      var plan = run.getBlessingShops().get(ctx);
      if (!valid(plan)) return Retcode.REQ_PARA_INVALID;
      var costs = new ArrayList<GameplayState.RogueFragmentCost>();
      for (int id : selected) {
        var offer = plan.getOffers().get(id);
        if (offer == null
            || offer.isPurchased()
            || run.getBlessings().containsKey(id)
            || resource(player, id) == null) return Retcode.REQ_PARA_INVALID;
        costs.add(offer.debitPlan());
      }
      var payment = RogueMiracles.pay(run, RogueMiracles.combinePayments(run, costs));
      if (payment.retcode() != Retcode.SUCC) return payment.retcode();
      for (int id : selected) {
        run.getBlessings().put(id, 1);
        player.getGameplay().getRogueCollectedBuffs().add(id);
        plan.getOffers().get(id).purchase();
      }
      boolean resonancePending = RogueResonance.queue(run);
      player.save();
      player.getQuestManager().onRogueHandbookProgress();
      payment.syncEffects(player);
      for (int id : selected) org.hkrpg.server.protocol.Universe46.syncBlessing(player, id, 0);
      org.hkrpg.server.protocol.RogueTalent46.syncCurrency(player);
      if (resonancePending) org.hkrpg.server.protocol.Universe46.syncPending(player);
      return Retcode.SUCC;
    }
  }

  private RogueBuffShop() {}
}
