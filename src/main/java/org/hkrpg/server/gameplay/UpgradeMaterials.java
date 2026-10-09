package org.hkrpg.server.gameplay;

import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.common.ItemParam.ItemParamType;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** Prepare bounded XP and a stable debit list before changing an owned upgrade target. */
public final class UpgradeMaterials {
  public enum Kind {
    AVATAR,
    EQUIPMENT,
    RELIC
  }

  public record Plan(int amount, int coins, List<ItemParam> items) {}

  /** The caller holds the player lock through both preparation and applying the debit. */
  public static Plan prepare(
      Player player, GameItem target, Collection<ItemParam> requested, Kind kind) {
    if (requested == null || requested.isEmpty()) return null;
    var quantities = new IdentityHashMap<GameItem, Integer>();
    var debit = new ArrayList<ItemParam>();
    int amount = 0, coins = 0;
    for (var param : requested) {
      if (param == null
          || param.getCount() <= 0
          || param.getType() != ItemParamType.PILE && param.getType() != ItemParamType.UNIQUE)
        return null;
      var item = player.getInventory().getItemByParam(param);
      if (item == null
          || item == target
          || item.getExcel() == null
          || item.getOwnerUid() != player.getUid()
          || player.getInventory().getItemByUid(item.getInternalUid()) != item
          || item.isLocked()
          || item.isEquipped()
          || param.getType() == ItemParamType.UNIQUE && param.getCount() != 1
          || param.getType() == ItemParamType.PILE && item.getExcel().isEquippable()) return null;
      int xp =
          switch (kind) {
            case AVATAR -> item.getExcel().getAvatarExp();
            case EQUIPMENT -> item.getExcel().getEquipmentExp();
            case RELIC -> item.getExcel().getRelicExp();
          };
      int cost =
          switch (kind) {
            case AVATAR -> 0;
            case EQUIPMENT -> item.getExcel().getEquipmentExpCost();
            case RELIC -> item.getExcel().getRelicExpCost();
          };
      if (xp <= 0 || cost < 0) return null;
      int quantity;
      try {
        quantity = Math.addExact(quantities.getOrDefault(item, 0), param.getCount());
      } catch (ArithmeticException overflow) {
        return null;
      }
      if (quantity > item.getCount()) return null;
      quantities.put(item, quantity);
      // A unique item contributes its saved relic XP once, never once per duplicate entry.
      long recovered = 0;
      if (kind == Kind.RELIC && item.getExcel().isRelic()) {
        if (quantity != 1 || item.getTotalExp() < 0) return null;
        recovered = (long) Math.floor(item.getTotalExp() * 0.80D);
      }
      long gain = (long) xp * param.getCount() + recovered;
      long charge = (long) cost * param.getCount();
      if (gain > Integer.MAX_VALUE - amount || charge > Integer.MAX_VALUE - coins) return null;
      amount += (int) gain;
      coins += (int) charge;
      debit.add(new ItemParam(param.getType(), param.getId(), param.getCount()));
    }
    if (kind == Kind.AVATAR) coins = amount / 10;
    return new Plan(amount, coins, List.copyOf(debit));
  }

  private UpgradeMaterials() {}
}
