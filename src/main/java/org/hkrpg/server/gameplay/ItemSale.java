package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.common.ItemParam.ItemParamType;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.inventory.tabs.InventoryTab;
import emu.lunarcore.game.inventory.tabs.InventoryTabType;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** Source SellType/return rows, actual unique-item debit and material delivery. */
public final class ItemSale {
  // Pinned 4.6 ConstValueCommon: Item_Sell_Max_Count and rank-5 relic remains ID/quantity.
  public static final int MAX_ITEMS = 500;
  private static final int REMAINS_ID = 235, REMAINS_COUNT = 10;

  private record Plan(
      List<GameItem> inputs, ItemParamMap returns, Map<Integer, Integer> before, boolean relic) {}

  public static List<GameItem> sell(Player player, boolean materials, List<ItemParam> costs) {
    synchronized (player) {
      var ledger = player.getGameplay().getItemSale();
      if (!ledger.available() || player.getGameplay().getCompositionLimits().pending()) return null;
      var use = player.getGameplay().getLastItemUse();
      if (use != null && use.pending()) return null;
      var plan = prepare(player, materials, costs);
      if (plan == null) return null;
      var expected = new TreeMap<Integer, Integer>();
      for (var entry : plan.returns()) expected.put(entry.getIntKey(), entry.getIntValue());
      var attempt = ledger.reserve(materials, plan.inputs(), expected, plan.before());
      player.save();
      var inventory = player.getInventory();
      // A single sale is one inventory operation: batch the deletion notification and
      // mission observation rather than queueing one frame per unique item.
      inventory.removeItems(plan.inputs());
      for (var item : plan.inputs()) {
        if (item.getCount() != 0
            || inventory.getItemByUid(item.getInternalUid()) != null
            || contains(inventory.getTabByItemType(item.getItemMainType()), item)) return null;
      }
      attempt.debited();
      player.save();
      var grant = inventory.grantItems(plan.returns(), false);
      var actual = new TreeMap<Integer, Integer>();
      boolean complete = grant.complete();
      for (var entry : expected.entrySet()) {
        var stored = inventory.getMaterialByItemId(entry.getKey());
        if (!owned(player, stored)
            || stored.getExcel() != GameData.getItemExcelMap().get(entry.getKey())) {
          complete = false;
          continue;
        }
        long delta = (long) stored.getCount() - plan.before().get(entry.getKey());
        if (delta < 0 || delta > Integer.MAX_VALUE) {
          complete = false;
          continue;
        }
        if (delta > 0) actual.put(entry.getKey(), (int) delta);
        if (delta != entry.getValue()) complete = false;
      }
      attempt.settle(actual, complete);
      player.save();
      if (!complete) return null;
      if (plan.relic()) player.getDailyManager().onRelicResolved();
      return List.copyOf(grant.items());
    }
  }

  private static Plan prepare(Player player, boolean materials, List<ItemParam> costs) {
    if (costs == null || costs.isEmpty() || costs.size() > MAX_ITEMS) return null;
    var inventory = player.getInventory();
    var seen = new HashSet<Integer>();
    var inputs = new ArrayList<GameItem>();
    var returns = new ItemParamMap();
    var before = new TreeMap<Integer, Integer>();
    boolean relic = false;
    for (var cost : costs) {
      if (cost == null
          || cost.getType() != ItemParamType.UNIQUE
          || cost.getId() <= 0
          || cost.getCount() != 1
          || !seen.add(cost.getId())) return null;
      var item = inventory.getItemByUid(cost.getId());
      if (!owned(player, item)
          || item.isLocked()
          || item.isEquipped()
          || item.getCount() != 1
          || item.getExp() != 0
          || item.getTotalExp() != 0
          || item.getPromotion() != 0
          || item.getRank() > 1
          || item.getRank() < 0) return null;
      var excel = item.getExcel();
      if (excel == null
          || excel != GameData.getItemExcelMap().get(item.getItemId())
          || !excel.isEquippable()
          || !"Sell".equals(excel.getSellType())
          || excel.getRarity() == null
          || !contains(inventory.getTabByItemType(excel.getItemMainType()), item)) return null;
      boolean isRelic = excel.getItemMainType() == ItemMainType.Relic;
      if (item.getLevel() != (isRelic ? 0 : 1)) return null;
      if (isRelic
              && (excel.getRelicExcel() == null
                  || excel.getRelicExcel() != GameData.getRelicExcelMap().get(excel.getId()))
          || !isRelic
              && (excel.getEquipmentExcel() == null
                  || excel.getEquipmentExcel() != GameData.getEquipExcelMap().get(excel.getId())))
        return null;
      if (isRelic && excel.getRarity() == ItemRarity.SuperRare && !materials) {
        if (!add(returns, REMAINS_ID, REMAINS_COUNT)) return null;
      } else {
        if (excel.getReturnItemIDList() == null || excel.getReturnItemIDList().isEmpty())
          return null;
        for (var entry : excel.getReturnItemIDList())
          if (entry == null
              || entry.getType() != ItemParamType.PILE
              || !add(returns, entry.getId(), entry.getCount())) return null;
      }
      inputs.add(item);
      relic |= isRelic;
    }
    int newPiles = 0;
    for (var entry : returns) {
      int id = entry.getIntKey(), amount = entry.getIntValue();
      var excel = GameData.getItemExcelMap().get(id);
      if (excel == null
          || excel.getItemMainType() != ItemMainType.Material
          || !Inventory.isStackType(excel)
          || excel.getPileLimit() <= 0) return null;
      var current = inventory.getMaterialByItemId(id);
      if (current != null
          && (!owned(player, current) || current.getExcel() != excel || current.getCount() < 0))
        return null;
      int stock = current == null ? 0 : current.getCount();
      if ((long) stock + amount > excel.getPileLimit()) return null;
      before.put(id, stock);
      if (current == null) newPiles++;
    }
    var tab = inventory.getTab(InventoryTabType.MATERIAL);
    if (tab == null
        || tab.getAvailableCapacity() < newPiles
        || newPiles > 0 && !inventory.hasItemIdCapacity(newPiles)) return null;
    return new Plan(List.copyOf(inputs), returns, before, relic);
  }

  private static boolean add(ItemParamMap result, int id, int count) {
    if (id <= 0 || count <= 0 || (long) result.get(id) + count > Integer.MAX_VALUE) return false;
    result.add(id, count);
    return true;
  }

  private static boolean owned(Player player, GameItem item) {
    return item != null
        && item.getOwnerUid() == player.getUid()
        && item.getInternalUid() > 0
        && player.getInventory().getItemByUid(item.getInternalUid()) == item;
  }

  private static boolean contains(InventoryTab tab, GameItem item) {
    if (tab == null) return false;
    for (var value : tab) if (value == item) return true;
    return false;
  }

  private ItemSale() {}
}
