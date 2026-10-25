package emu.lunarcore.game.shop;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.inventory.tabs.InventoryTabType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.*;
import emu.lunarcore.server.packet.Retcode;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Validate the entire exchange before payment; persist its receipt before any grants. */
public class ShopService extends BaseGameService {
  public ShopService(GameServer server) { super(server); }

  public record Purchase(Retcode code, List<GameItem> items, int bought) {}
  private static Purchase fail(Retcode code) { return new Purchase(code, List.of(), 0); }

  public List<GameItem> buyGoods(Player p, int shop, int goods, int count) {
    var result = buy(p, shop, goods, count, 0, List.of(), Instant.now());
    return result.code() == Retcode.SUCC ? result.items() : null;
  }

  public Purchase buy(Player p, int shopId, int goodsId, int count, int requestedItem,
      List<Integer> choices, Instant now) {
    synchronized (p) {
      var state = p.getGameplay().getShopPurchases();
      if (state.getPending() != null) return fail(Retcode.SYSTEM_BUSY);
      var shop = GameData.getShopExcelMap().get(shopId);
      var goods = shop == null ? null : shop.getGoods().get(goodsId);
      if (count <= 0 || goods == null || choices == null || choices.size() > 32)
        return fail(Retcode.REQ_PARA_INVALID);
      int itemId = goods.itemAt(now);
      if (requestedItem != 0 && requestedItem != itemId) return fail(Retcode.REQ_PARA_INVALID);
      var item = GameData.getItemExcelMap().get(itemId);
      if (item == null || goods.getCostList() == null) return fail(Retcode.ITEM_CONFIG_NOT_EXIST);
      int previous = bought(p, goods, now);
      if ((long) previous + count > Integer.MAX_VALUE
          || goods.getLimitTimes() > 0 && (long) previous + count > goods.getLimitTimes())
        return fail(Retcode.ITEM_EXCEED_LIMIT);

      Map<Integer, Integer> costs = new TreeMap<>(), rewards;
      try {
        for (var cost : goods.getCostList()) {
          if (cost.getId() <= 0 || cost.getCount() < 0) return fail(Retcode.ITEM_CONFIG_NOT_EXIST);
          int amount = Math.multiplyExact(cost.getCount(), count);
          if (amount > 0) costs.merge(cost.getId(), amount, Math::addExact);
        }
        rewards = rewards(goods, itemId, count, choices);
        if (!convertAvatars(p, rewards)) return fail(Retcode.AVATAR_RANK_REACH_MAX);
        // Costumes include the portrait specified by the same resource row.
        for (int id : List.copyOf(rewards.keySet())) {
          var skin = GameData.getAvatarSkinExcelMap().get(id);
          if (skin != null && skin.getPlayerCardID() > 0
              && !p.getUnlocks().getHeadIcons().contains(skin.getPlayerCardID()))
            rewards.putIfAbsent(skin.getPlayerCardID(), 1);
        }
      } catch (ArithmeticException | IllegalArgumentException invalid) {
        return fail(Retcode.REQ_PARA_INVALID);
      }
      if (!canDeliver(p, rewards)) return fail(Retcode.ITEM_REWARD_EXCEED_LIMIT);
      List<ItemParam> payment = costs.entrySet().stream()
          .map(e -> new ItemParam(e.getKey(), e.getValue())).toList();
      if (!p.getInventory().hasItems(payment)) return fail(Retcode.ITEM_COST_NOT_ENOUGH);

      var key = periodKey(goods, now);
      state.reserve(key, count, costs, rewards);
      p.save(); // If storage fails, no payment or reward has happened.
      p.getInventory().removeItemsByParams(payment);
      p.save();
      var parameters = new ItemParamMap();
      rewards.forEach((id, amount) -> parameters.put(id.intValue(), amount.intValue()));
      var proposed = parameters.toItemList();
      for (var reward : proposed) {
        if (reward.getItemId() == itemId && reward.getExcel().isEquippable()) {
          if (goods.getLevel() > 0) reward.setLevel(goods.getLevel());
          if (goods.getRank() > 0) reward.setRank(goods.getRank());
        }
      }
      var received = p.getInventory().addItems(proposed);
      var actual = new TreeMap<Integer, Integer>();
      received.forEach(i -> actual.merge(i.getItemId(), i.getCount(), Math::addExact));
      if (!actual.equals(rewards)) return fail(Retcode.SYSTEM_BUSY);
      var receipt = state.getPending();
      int oldCount = state.getCounts().getOrDefault(key, 0);
      state.complete();
      try { p.save(); }
      catch (RuntimeException failed) {
        state.getCounts().put(key, oldCount);
        state.reserve(key, count, receipt.getCosts(), receipt.getRewards());
        throw failed;
      }
      p.getMissionManager().onShopPurchase(goodsId);
      org.hkrpg.server.protocol.ShopCosmetics46.purchased(p, received);
      return new Purchase(Retcode.SUCC, List.copyOf(received), bought(p, goods, now));
    }
  }

  /** Optional reward IDs are matched to the ordered resource groups, never trusted as item IDs. */
  public static Map<Integer, Integer> rewards(ShopGoodsExcel goods, int itemId, int count,
      List<Integer> choices) {
    int amount = Math.multiplyExact(goods.getItemCount(), count);
    if (amount <= 0) throw new IllegalArgumentException("Invalid shop quantity");
    var item = GameData.getItemExcelMap().get(itemId);
    if (item == null) throw new IllegalArgumentException("Missing shop item");
    var result = new TreeMap<Integer, Integer>();
    if (item.getItemSubType() != ItemSubType.ForceOpitonalGift) {
      if (!choices.isEmpty()) throw new IllegalArgumentException("Unexpected optional reward");
      result.put(itemId, amount);
      return result;
    }
    var groups = GameData.getItemGiftPackExcelMap().stream()
        .filter(g -> g.getID() == itemId)
        .sorted(Comparator.comparingInt(ItemGiftPackExcel::getGroupID)).toList();
    if (groups.isEmpty() || choices.size() != groups.size())
      throw new IllegalArgumentException("Missing optional reward group");
    for (int i = 0; i < groups.size(); i++) {
      int choice = choices.get(i);
      if (!groups.get(i).getRewardList().contains(choice))
        throw new IllegalArgumentException("Reward does not belong to this group");
      var reward = GameData.getRewardExcelMap().get(choice);
      if (reward == null || reward.getRewards() == null || reward.getRewards().isEmpty())
        throw new IllegalArgumentException("Missing optional reward definition");
      for (var entry : reward.getRewards()) {
        if (entry.getIntValue() <= 0) throw new IllegalArgumentException("Invalid optional reward");
        result.merge(entry.getIntKey(), Math.multiplyExact(entry.getIntValue(), amount), Math::addExact);
      }
    }
    return result;
  }

  private static boolean convertAvatars(Player p, Map<Integer, Integer> rewards) {
    for (int id : List.copyOf(rewards.keySet())) {
      var excel = GameData.getItemExcelMap().get(id);
      if (excel == null || excel.getItemMainType() != ItemMainType.AvatarCard) continue;
      var avatar = p.getAvatars().getAvatarById(id);
      if (avatar == null) {
        if (rewards.get(id) != 1) return false;
        continue;
      }
      int rankId = avatar.getExcel().getRankUpItemId();
      var held = p.getInventory().getMaterialByItemId(rankId);
      int amount = Math.addExact(rewards.get(id), rewards.getOrDefault(rankId, 0));
      if ((long) avatar.getRank() + (held == null ? 0 : held.getCount()) + amount > 6) return false;
      rewards.remove(id);
      rewards.put(rankId, amount);
    }
    return true;
  }

  public static boolean canDeliver(Player p, Map<Integer, Integer> rewards) {
    if (rewards.isEmpty()) return false;
    var inventory = p.getInventory();
    var slots = new EnumMap<InventoryTabType, Long>(InventoryTabType.class);
    long newIds = 0;
    for (var entry : rewards.entrySet()) {
      int id = entry.getKey(), count = entry.getValue();
      var item = GameData.getItemExcelMap().get(id);
      if (count <= 0 || !ShopItems.deliverable(item)) return false;
      if (ShopItems.unique(item) && (count != 1 || ShopItems.owned(p, item))) return false;
      if (Inventory.isStackType(item)) {
        var held = inventory.getMaterialByItemId(id);
        if ((long) (held == null ? 0 : held.getCount()) + count > ShopItems.stackLimit(item)) return false;
        if (held == null) { slots.merge(InventoryTabType.MATERIAL, 1L, Long::sum); newIds++; }
      } else if (item.isEquippable()) {
        slots.merge(item.getItemMainType().getTabType(), (long) count, Long::sum);
        newIds += count;
      } else if (item.getItemMainType() == ItemMainType.Virtual) {
        int owned = switch (id) { case 1 -> p.getHcoin(); case 2 -> p.getScoin(); case 3 -> p.getMcoin(); default -> -1; };
        if (owned < 0 || (long) owned + count > Integer.MAX_VALUE) return false;
      }
    }
    if (newIds > Integer.MAX_VALUE || newIds > 0 && !inventory.hasItemIdCapacity((int) newIds)) return false;
    for (var entry : slots.entrySet())
      if (inventory.getTab(entry.getKey()).getAvailableCapacity() < entry.getValue()) return false;
    return true;
  }

  public static int bought(Player p, ShopGoodsExcel goods, Instant now) {
    int count = p.getGameplay().getShopPurchases().getCounts().getOrDefault(periodKey(goods, now), 0);
    var item = GameData.getItemExcelMap().get(goods.itemAt(now));
    if (item != null && ShopItems.unique(item) && ShopItems.owned(p, item))
      count = Math.max(count, Math.max(goods.getLimitTimes(), 1));
    return count;
  }

  /** Local server calendar: UTC+8 at 04:00, Monday weeks; cycles anchored at launch. */
  public static String periodKey(ShopGoodsExcel goods, Instant now) {
    var date = now.atOffset(ZoneOffset.ofHours(8)).minusHours(4).toLocalDate();
    String period = switch (Objects.toString(goods.getRefreshType(), "")) {
      case "DAILY" -> date.toString();
      case "WEEK" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
      case "MONTH" -> date.withDayOfMonth(1).toString();
      case "CYCLE" -> "cycle-" + Math.floorDiv(date.toEpochDay() - LocalDate.of(2023, 4, 26).toEpochDay(), Math.max(1, goods.getCycleDays()));
      default -> "once";
    };
    return goods.getGoodsID() + ":" + period;
  }
}
