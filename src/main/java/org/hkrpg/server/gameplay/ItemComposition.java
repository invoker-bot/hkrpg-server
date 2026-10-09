package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.common.ItemParam.ItemParamType;
import emu.lunarcore.data.excel.ItemComposeExcel;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.player.Player;
import java.time.Instant;
import java.util.*;

/** Validates a bounded source recipe before any debit; caller holds the player lock throughout. */
public final class ItemComposition {
  private record Plan(ItemExcel output, int count, int coins, List<ItemParam> costs) {}

  public static List<GameItem> compose(
      Player player, int recipeId, int count, List<ItemParam> requested) {
    synchronized (player) {
      return compose(player, recipeId, count, requested, Instant.now());
    }
  }

  public static List<GameItem> compose(
      Player player, int recipeId, int count, List<ItemParam> requested, Instant now) {
    synchronized (player) {
      var recipe = GameData.getItemComposeExcelMap().get(recipeId);
      var ledger = player.getGameplay().getCompositionLimits();
      var plan = prepare(player, recipe, count, requested, now);
      if (plan == null) return null;
      CompositionLimits.Attempt attempt = null;
      if (CompositionLimits.periodic(recipe)) {
        var costs = new TreeMap<Integer, Integer>();
        plan.costs().forEach(p -> costs.put(p.getId(), p.getCount()));
        attempt = ledger.reserve(recipe, count, now, costs, plan.coins());
        player.save();
      }
      var inventory = player.getInventory();
      inventory.removeItemsByParams(plan.costs());
      if (plan.coins() > 0) player.addSCoin(-plan.coins());
      if (attempt != null) {
        ledger.debited(attempt);
        player.save();
      }
      var received = inventory.addItems(List.of(new GameItem(plan.output(), plan.count())));
      boolean complete =
          received.size() == 1
              && received.getFirst().getItemId() == plan.output().getId()
              && received.getFirst().getCount() == plan.count();
      if (attempt != null) {
        ledger.settle(attempt, received, complete);
        player.save();
      }
      if (!complete) return null;
      player.getDailyManager().onItemComposed(plan.output().getItemSubType(), plan.count());
      return List.copyOf(received);
    }
  }

  private static Plan prepare(
      Player player, ItemComposeExcel recipe, int count, List<ItemParam> requested, Instant now) {
    if (recipe == null
        || requested == null
        || count <= 0
        || recipe.getFormulaType() == null
        || recipe.getFuncType() == null
        || recipe.getMaxCount() <= 0
        || count > recipe.getMaxCount()
        || recipe.getWorldLevelRequire() < 0
        || player.getWorldLevel() < recipe.getWorldLevelRequire()
        || !FormulaLearning.unlocked(player, recipe)
        || recipe.getRelicList() == null
        || !recipe.getRelicList().isEmpty()
        || !player.getGameplay().getCompositionLimits().available(recipe, count, now)
        || recipe.getCoinCost() < 0) return null;
    var output = GameData.getItemExcelMap().get(recipe.getItemID());
    if (output == null || !Inventory.isStackType(output) || output.getPileLimit() <= 0) return null;
    long coins = (long) recipe.getCoinCost() * count;
    if (coins > Integer.MAX_VALUE || player.getScoin() < coins) return null;
    var costs = new TreeMap<Integer, Integer>();
    switch (recipe.getFormulaType()) {
      case Normal -> {
        if (!"Compose".equals(recipe.getFuncType())
            || !requested.isEmpty()
            || recipe.getMaterialCost() == null
            || recipe.getMaterialCost().isEmpty()
            || recipe.getSpecialMaterialCostNumber() != 0
            || recipe.getSpecialMaterialCost() == null
            || !recipe.getSpecialMaterialCost().isEmpty()) return null;
        for (var p : recipe.getMaterialCost()) if (!addCost(costs, p, count)) return null;
      }
      case Sepcial -> {
        if (!Set.of("Replace", "Compose").contains(recipe.getFuncType())
            || recipe.getMaterialCost() == null
            || !recipe.getMaterialCost().isEmpty()
            || recipe.getSpecialMaterialCost() == null
            || recipe.getSpecialMaterialCost().isEmpty()
            || recipe.getSpecialMaterialCostNumber() <= 0
            || requested.isEmpty()) return null;
        long total = 0, required = (long) count * recipe.getSpecialMaterialCostNumber();
        if (required > Integer.MAX_VALUE) return null;
        for (var p : requested) {
          if (p == null
              || !recipe.getSpecialMaterialCost().contains(p.getId())
              || !addCost(costs, p, 1)) return null;
          total += p.getCount();
          if (total > required) return null;
        }
        if (total != required) return null;
      }
      default -> {
        return null;
      }
    }
    var inventory = player.getInventory();
    var debit = new ArrayList<ItemParam>();
    var tab = inventory.getTabByItemType(output.getItemMainType());
    if (tab == null) return null;
    int releasedSlots = 0;
    for (var entry : costs.entrySet()) {
      var item = inventory.getMaterialByItemId(entry.getKey());
      if (entry.getKey() == output.getId()
          || !owned(player, item)
          || !Inventory.isStackType(item.getExcel())
          || item.isLocked()
          || item.isEquipped()
          || item.getCount() < entry.getValue()) return null;
      debit.add(new ItemParam(entry.getKey(), entry.getValue()));
      if (item.getCount() == entry.getValue()
          && inventory.getTabByItemType(item.getExcel().getItemMainType()) == tab) releasedSlots++;
    }
    var existing = inventory.getMaterialByItemId(output.getId());
    if (existing != null && (!owned(player, existing) || existing.getCount() < 0)) return null;
    long before = existing == null ? 0 : existing.getCount();
    if (before + count > output.getPileLimit()
        || before + count > Integer.MAX_VALUE
        || existing == null && tab.getSize() - releasedSlots >= tab.getMaxCapacity()) return null;
    return new Plan(output, count, (int) coins, List.copyOf(debit));
  }

  private static boolean owned(Player player, GameItem item) {
    return item != null
        && item.getExcel() != null
        && item.getOwnerUid() == player.getUid()
        && player.getInventory().getItemByUid(item.getInternalUid()) == item;
  }

  private static boolean addCost(Map<Integer, Integer> aggregate, ItemParam p, int multiplier) {
    if (p == null || p.getType() != ItemParamType.PILE || p.getId() <= 0 || p.getCount() <= 0)
      return false;
    long amount = (long) p.getCount() * multiplier + aggregate.getOrDefault(p.getId(), 0);
    if (amount > Integer.MAX_VALUE) return false;
    aggregate.put(p.getId(), (int) amount);
    return true;
  }

  private ItemComposition() {}
}
