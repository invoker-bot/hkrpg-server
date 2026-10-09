package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam.ItemParamType;
import emu.lunarcore.data.excel.ItemComposeExcel;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/**
 * Source FormulaRequire is a book item, not a compose ID; learning needs one actual owned debit.
 */
public final class FormulaLearning {
  public static ItemComposeExcel recipe(int book) {
    var item = GameData.getItemExcelMap().get(book);
    if (item == null
        || item.getItemMainType() != ItemMainType.Usable
        || item.getItemSubType() != ItemSubType.Formula
        || item.getUseMethod() != ItemUseMethod.Recipe
        || item.getPileLimit() <= 0
        || item.getUseDataID() != 0 && item.getUseDataID() != book) return null;
    var matches =
        GameData.getItemComposeExcelMap().values().stream()
            .filter(r -> r.getFormulaRequire() == book)
            .toList();
    if (matches.size() != 1) return null;
    var recipe = matches.getFirst();
    var output = GameData.getItemExcelMap().get(recipe.getItemID());
    if (recipe.getId() <= 0
        || recipe.getFormulaType() != ItemComposeExcel.FormulaType.Normal
        || !"Compose".equals(recipe.getFuncType())
        || recipe.getMaxCount() <= 0
        || recipe.getWorldLevelRequire() < 0
        || recipe.getCoinCost() < 0
        || !"NotLimit".equals(recipe.getLimitType())
        || recipe.getLimitValue() != 0
        || recipe.getLimitTypeParam() == null
        || !recipe.getLimitTypeParam().isEmpty()
        || recipe.getSpecialMaterialCostNumber() != 0
        || recipe.getSpecialMaterialCost() == null
        || !recipe.getSpecialMaterialCost().isEmpty()
        || recipe.getRelicList() == null
        || !recipe.getRelicList().isEmpty()
        || output == null
        || !Inventory.isStackType(output)
        || output.getPileLimit() <= 0
        || recipe.getMaterialCost() == null
        || recipe.getMaterialCost().isEmpty()) return null;
    for (var cost : recipe.getMaterialCost()) {
      var ingredient = cost == null ? null : GameData.getItemExcelMap().get(cost.getId());
      if (cost == null
          || cost.getType() != ItemParamType.PILE
          || cost.getCount() <= 0
          || ingredient == null
          || !Inventory.isStackType(ingredient)
          || ingredient.getPileLimit() <= 0
          || cost.getId() == output.getId()) return null;
    }
    return recipe;
  }

  public static boolean unlocked(Player player, ItemComposeExcel recipe) {
    if (recipe.getFormulaRequire() == 0) return true;
    return recipe.getFormulaRequire() > 0
        && player.getGameplay().getLearnedFormulas().contains(recipe.getFormulaRequire())
        && recipe(recipe.getFormulaRequire()) == recipe;
  }

  public static ConsumableUse.Result use(Player player, GameItem book, int count, int avatar) {
    synchronized (player) {
      var previous = player.getGameplay().getLastItemUse();
      if (previous != null && (previous.pending() || previous.getSequence() == Long.MAX_VALUE))
        return error(Retcode.SYSTEM_BUSY);
      if (book == null
          || count != 1
          || avatar != 0
          || book.isLocked()
          || player.isInBattle()
          || book.getCount() < 1
          || book.getOwnerUid() != player.getUid()
          || player.getInventory().getItemByUid(book.getInternalUid()) != book)
        return error(Retcode.ITEM_INVALID_USE);
      int id = book.getItemId();
      if (recipe(id) == null) return error(Retcode.ITEM_FORMULA_NOT_EXIST);
      if (player.getGameplay().getLearnedFormulas().contains(id))
        return error(Retcode.ITEM_INVALID_USE);
      var receipt = player.getGameplay().reserveItemUse(id, 1, 0, Map.of());
      player.save();
      if (!player
          .getInventory()
          .consumeForUse(
              book,
              1,
              () -> {
                receipt.debited();
                player.save();
                if (!player.getGameplay().learnFormula(id))
                  throw new IllegalStateException("Prepared formula was already learned");
                receipt.settle(List.of(), true);
                player.save();
              })) {
        receipt.cancel();
        player.save();
        return error(Retcode.ITEM_NOT_ENOUGH);
      }
      return new ConsumableUse.Result(Retcode.SUCC, List.of(), 1, id);
    }
  }

  private static ConsumableUse.Result error(Retcode code) {
    return new ConsumableUse.Result(code, List.of(), 0);
  }

  private FormulaLearning() {}
}
