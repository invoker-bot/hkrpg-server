package org.hkrpg.server.gameplay;

import emu.lunarcore.GameConstants;
import emu.lunarcore.data.*;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.common.ItemParam.ItemParamType;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.time.Instant;
import java.util.*;
import us.hebi.quickbuf.RepeatedInt;

/** Validates source affixes and costs before reserving actual unique relic delivery. */
public final class SelectedRelicComposition {
  private record Plan(List<GameItem> outputs, List<ItemParam> costs, int coins) {}

  public static List<GameItem> compose(
      Player player, int recipeId, int relicId, int count, int main, RepeatedInt subs) {
    synchronized (player) {
      if (subs == null || subs.length() > 2) return null;
      var chosen = new ArrayList<Integer>();
      for (int i = 0; i < subs.length(); i++) chosen.add(subs.get(i));
      return compose(player, recipeId, relicId, count, main, chosen, Instant.now());
    }
  }

  public static List<GameItem> compose(
      Player player,
      int recipeId,
      int relicId,
      int count,
      int main,
      List<Integer> subs,
      Instant now) {
    synchronized (player) {
      var recipe = GameData.getItemComposeExcelMap().get(recipeId);
      var plan = prepare(player, recipe, relicId, count, main, subs, now);
      if (plan == null) return null;
      var costs = new TreeMap<Integer, Integer>();
      plan.costs().forEach(p -> costs.put(p.getId(), p.getCount()));
      var ledger = player.getGameplay().getCompositionLimits();
      var attempt = ledger.reserveRelics(recipe, plan.outputs(), now, costs, plan.coins());
      player.save();
      var inventory = player.getInventory();
      inventory.removeItemsByParams(plan.costs());
      if (plan.coins() > 0) player.addSCoin(-plan.coins());
      ledger.debited(attempt);
      player.save();
      var received = inventory.addItems(plan.outputs());
      var identities = Collections.newSetFromMap(new IdentityHashMap<GameItem, Boolean>());
      boolean complete = received.size() == plan.outputs().size();
      for (int i = 0; i < received.size(); i++) {
        var item = received.get(i);
        complete &= owned(player, item) && identities.add(item);
        if (i >= attempt.getPlannedRelics().size()
            || !attempt.getPlannedRelics().get(i).matches(item)) complete = false;
      }
      ledger.settle(attempt, received, complete);
      player.save();
      if (!complete) return null;
      player
          .getDailyManager()
          .onItemComposed(plan.outputs().getFirst().getExcel().getItemSubType(), count);
      return List.copyOf(received);
    }
  }

  private static Plan prepare(
      Player player,
      ItemComposeExcel recipe,
      int relicId,
      int count,
      int main,
      List<Integer> subs,
      Instant now) {
    if (recipe == null
        || recipe.getFormulaType() != ItemComposeExcel.FormulaType.SelectedRelic
        || !"Compose".equals(recipe.getFuncType())
        || !"NotLimit".equals(recipe.getLimitType())
        || recipe.getFormulaRequire() != 0
        || count <= 0
        || recipe.getMaxCount() <= 0
        || count > recipe.getMaxCount()
        || recipe.getWorldLevelRequire() < 0
        || player.getWorldLevel() < recipe.getWorldLevelRequire()
        || !player.getGameplay().getCompositionLimits().available(recipe, count, now)
        || recipe.getRelicList() == null
        || !recipe.getRelicList().contains(relicId)
        || recipe.getMaterialCost() == null
        || recipe.getMaterialCost().isEmpty()
        || recipe.getSpecialMaterialCost() == null
        || recipe.getSpecialMaterialCost().size() != 1
        || recipe.getSpecialMaterialCostNumber() != 1
        || recipe.getCoinCost() < 0
        || subs == null
        || subs.size() > 2
        || main < 0
        || main == 0 && !subs.isEmpty()) return null;
    var excel = GameData.getItemExcelMap().get(relicId);
    var relic = GameData.getRelicExcelMap().get(relicId);
    if (excel == null
        || excel.getItemMainType() != ItemMainType.Relic
        || excel.getRelicExcel() != relic
        || relic == null
        || relic.getType() == null
        || relic.getType() == RelicType.Unknow
        || excel.getRarity() == null
        || excel.getRarity().getVal() != 5
        || relic.getMaxLevel() <= 0
        || !validGroups(relic)) return null;
    var chosen = new IntOpenHashSet();
    var properties = EnumSet.noneOf(AvatarPropertyType.class);
    for (var id : subs) {
      if (id == null || id <= 0 || !chosen.add(id)) return null;
      var affix = GameData.getRelicSubAffixExcelMap().get(relic.getSubAffixGroup(), id);
      if (affix == null || !properties.add(affix.getProperty())) return null;
    }
    if (main > 0) {
      var affix = GameData.getRelicMainAffixExcelMap().get(relic.getMainAffixGroup(), main);
      if (affix == null || properties.contains(affix.getProperty())) return null;
    }
    long coins = (long) recipe.getCoinCost() * count;
    if (coins > Integer.MAX_VALUE || player.getScoin() < coins) return null;
    var costs = new TreeMap<Integer, Integer>();
    for (var p : recipe.getMaterialCost())
      if (p == null
          || p.getType() != ItemParamType.PILE
          || !addCost(costs, p.getId(), p.getCount(), count)) return null;
    if (main > 0)
      for (int id : recipe.getSpecialMaterialCost())
        if (!addCost(costs, id, recipe.getSpecialMaterialCostNumber(), count)) return null;
    if (!subs.isEmpty()
        && !addCost(
            costs, GameConstants.RELIC_COMPOSE_SUB_AFFIX_ITEM, subs.size() == 1 ? 1 : 4, count))
      return null;
    var inventory = player.getInventory();
    var tab = inventory.getTabByItemType(ItemMainType.Relic);
    if (!inventory.hasItemIdCapacity(count)
        || tab == null
        || tab.getSize() < 0
        || (long) tab.getSize() + count > tab.getMaxCapacity()) return null;
    var debit = new ArrayList<ItemParam>();
    for (var entry : costs.entrySet()) {
      var item = inventory.getMaterialByItemId(entry.getKey());
      if (!owned(player, item)
          || !Inventory.isStackType(item.getExcel())
          || item.isLocked()
          || item.isEquipped()
          || item.getCount() < entry.getValue()) return null;
      debit.add(new ItemParam(entry.getKey(), entry.getValue()));
    }
    var outputs = new ArrayList<GameItem>();
    for (int i = 0; i < count; i++) {
      var output = new GameItem(excel, 1, main, chosen);
      if (!validOutput(relic, output, main, chosen)) return null;
      outputs.add(output);
    }
    return new Plan(List.copyOf(outputs), List.copyOf(debit), (int) coins);
  }

  private static boolean validGroups(RelicExcel relic) {
    var mains = GameDepot.getRelicMainAffixesByGroup(relic.getMainAffixGroup());
    var subs = GameDepot.getRelicSubAffixesByGroup(relic.getSubAffixGroup());
    if (mains == null || mains.isEmpty() || subs == null || subs.size() < 5) return false;
    var mainIds = new HashSet<Integer>();
    for (var affix : mains)
      if (affix == null
          || affix.getGroupID() != relic.getMainAffixGroup()
          || affix.getAffixID() <= 0
          || !mainIds.add(affix.getAffixID())
          || affix.getProperty() == null
          || affix.getProperty() == AvatarPropertyType.Unknown
          || !Double.isFinite(affix.getBaseValue())
          || affix.getBaseValue() <= 0
          || !Double.isFinite(affix.getLevelAdd())
          || affix.getLevelAdd() < 0
          || GameData.getRelicMainAffixExcelMap().get(affix.getGroupID(), affix.getAffixID())
              != affix) return false;
    var subIds = new HashSet<Integer>();
    var properties = EnumSet.noneOf(AvatarPropertyType.class);
    for (var affix : subs)
      if (affix == null
          || affix.getGroupID() != relic.getSubAffixGroup()
          || affix.getAffixID() <= 0
          || !subIds.add(affix.getAffixID())
          || affix.getProperty() == null
          || affix.getProperty() == AvatarPropertyType.Unknown
          || !properties.add(affix.getProperty())
          || !Double.isFinite(affix.getBaseValue())
          || affix.getBaseValue() <= 0
          || !Double.isFinite(affix.getStepValue())
          || affix.getStepValue() < 0
          || affix.getStepNum() < 0
          || affix.getStepNum() == Integer.MAX_VALUE
          || GameData.getRelicSubAffixExcelMap().get(affix.getGroupID(), affix.getAffixID())
              != affix) return false;
    return true;
  }

  private static boolean validOutput(
      RelicExcel relic, GameItem item, int main, IntOpenHashSet chosen) {
    var mainRow =
        GameData.getRelicMainAffixExcelMap().get(relic.getMainAffixGroup(), item.getMainAffix());
    if (mainRow == null
        || main > 0 && item.getMainAffix() != main
        || item.getSubAffixes() == null
        || item.getSubAffixes().size() < 3
        || item.getSubAffixes().size() > 4) return false;
    var ids = new IntOpenHashSet();
    var properties = EnumSet.noneOf(AvatarPropertyType.class);
    for (var sub : item.getSubAffixes()) {
      var row = GameData.getRelicSubAffixExcelMap().get(relic.getSubAffixGroup(), sub.getId());
      if (row == null
          || !ids.add(sub.getId())
          || !properties.add(row.getProperty())
          || row.getProperty() == mainRow.getProperty()
          || sub.getCount() != 1
          || sub.getStep() < 0
          || sub.getStep() > row.getStepNum()) return false;
    }
    return ids.containsAll(chosen);
  }

  private static boolean owned(Player player, GameItem item) {
    return item != null
        && item.getExcel() != null
        && item.getOwnerUid() == player.getUid()
        && player.getInventory().getItemByUid(item.getInternalUid()) == item;
  }

  private static boolean addCost(Map<Integer, Integer> costs, int id, int number, int count) {
    if (id <= 0 || number <= 0) return false;
    long amount = (long) number * count + costs.getOrDefault(id, 0);
    if (amount > Integer.MAX_VALUE) return false;
    costs.put(id, (int) amount);
    return true;
  }

  private SelectedRelicComposition() {}
}
