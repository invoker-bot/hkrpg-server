package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;

/**
 * One unlock per source good. Interrupted debit/grant receipts remain reserved for reconciliation.
 */
public final class DiceCombatShop {
  public record Purchase(Retcode code, int item, long time, List<GameItem> granted) {}

  private static Purchase rejected(Retcode code) {
    return new Purchase(code, 0, 0, List.of());
  }

  public static boolean valid(Player player, DiceCombatCatalog catalog) {
    if (catalog == null) return false;
    var state = player.getGameplay().getDiceCombat();
    if (state.getAvatars() == null || state.getDice() == null || state.getPurchases() == null)
      return false;
    for (var entry : state.getAvatars().entrySet()) {
      var owned = entry.getValue();
      var id = entry.getKey();
      if (id == null
          || owned == null
          || owned.getId() != id
          || owned.getLevel() < 1
          || owned.getLevel() > 3
          || owned.getTime() <= 0
          || !catalog.avatars().containsKey(id)
          || owned.getDice() == null
          || owned.getDice().isEmpty()
          || owned.getDice().size() > 32
          || owned.getDice().stream().anyMatch(d -> d == null || !catalog.dice().contains(d))
          || catalog.avatars().get(id).wireSlots(owned.getDice()) == null) return false;
    }
    for (var entry : state.getDice().entrySet())
      if (entry.getKey() == null
          || !catalog.dice().contains(entry.getKey())
          || entry.getValue() == null
          || entry.getValue() <= 0) return false;
    var received = new HashSet<Integer>();
    for (var entry : state.getPurchases().entrySet()) {
      var receipt = entry.getValue();
      var good = catalog.goods().get(entry.getKey());
      if (receipt == null
          || entry.getKey() == null
          || good == null
          || receipt.getGood() != entry.getKey()
          || receipt.getItem() != good.item()
          || receipt.getTime() <= 0
          || !catalog.shops().containsKey(receipt.getShop())
          || !catalog.shops().get(receipt.getShop()).goods().contains(entry.getKey())
          || receipt.getCosts() == null
          || receipt.getCosts().isEmpty()
          || receipt.getCosts().size() > 4
          || receipt.getCosts().entrySet().stream()
              .anyMatch(
                  c ->
                      c.getKey() == null
                          || c.getKey() <= 0
                          || c.getValue() == null
                          || c.getValue() <= 0)
          || !received.add(receipt.getItem())) return false;
      if (receipt.isGranted()) {
        var avatar = state.getAvatars().get(receipt.getItem());
        Long time =
            avatar == null
                ? state.getDice().get(receipt.getItem())
                : Long.valueOf(avatar.getTime());
        if (time == null || time != receipt.getTime()) return false;
      }
    }
    return true;
  }

  public static Purchase buy(
      Player player, DiceCombatCatalog catalog, int shopId, int goodId, Instant now) {
    synchronized (player) {
      if (catalog == null) return rejected(Retcode.REWARD_CONFIG_NOT_EXIST);
      if (!valid(player, catalog)) return rejected(Retcode.PLAYER_DATA_ERROR);
      if (!catalog.available(player, now)) return rejected(Retcode.SHOP_NOT_OPEN);
      var shop = catalog.shops().get(shopId);
      var good = catalog.goods().get(goodId);
      if (shop == null || good == null || !shop.goods().contains(goodId) || !good.unlocked(player))
        return rejected(Retcode.REQ_PARA_INVALID);
      var state = player.getGameplay().getDiceCombat();
      if (DiceProgression.pending(player)
          || DicePveMissionReports.pending(player)
          || state.getPurchases().values().stream().anyMatch(r -> r == null || !r.isGranted()))
        return rejected(Retcode.SYSTEM_BUSY);
      var inventory = player.getInventory();
      if (state.getPurchases().containsKey(goodId) || inventory.hasItem(good.item(), 1))
        return rejected(Retcode.BUY_TIMES_LIMIT);
      if (player.getBattle() != null) return rejected(Retcode.SYSTEM_BUSY);
      var excel = GameData.getItemExcelMap().get(good.item());
      if (excel == null
          || excel.getItemMainType() != ItemMainType.Material
          || excel.getPileLimit() < 1) return rejected(Retcode.REWARD_CONFIG_NOT_EXIST);
      var tab = inventory.getTabByItemType(ItemMainType.Material);
      if (tab == null || tab.getAvailableCapacity() == 0)
        return rejected(Retcode.PLAYER_DATA_ERROR);
      var costs = new ArrayList<ItemParam>();
      for (var entry : new TreeMap<>(good.costs()).entrySet()) {
        var currency = GameData.getItemExcelMap().get(entry.getKey());
        if (currency == null
            || currency.getItemMainType() != ItemMainType.Material
            || entry.getValue() <= 0) return rejected(Retcode.REWARD_CONFIG_NOT_EXIST);
        if (!inventory.hasItem(entry.getKey(), entry.getValue()))
          return rejected(Retcode.ITEM_NOT_ENOUGH);
        costs.add(new ItemParam(entry.getKey(), entry.getValue()));
      }
      var receipt =
          new GameplayState.DicePurchase(
              shopId, goodId, good.item(), now.getEpochSecond(), good.costs());
      state.getPurchases().put(goodId, receipt);
      player.save();
      inventory.removeItemsByParams(costs);
      var reward = new ItemParamMap();
      reward.add(good.item(), 1);
      var result = inventory.grantItems(reward, true);
      if (!result.complete() || !inventory.hasItem(good.item(), 1))
        return rejected(Retcode.PLAYER_DATA_ERROR);
      var avatar = catalog.avatars().get(good.item());
      if (avatar != null)
        state
            .getAvatars()
            .put(
                good.item(),
                new GameplayState.DiceAvatar(good.item(), receipt.getTime(), avatar.initialDice()));
      else state.getDice().put(good.item(), receipt.getTime());
      receipt.complete();
      player.save();
      return new Purchase(
          Retcode.SUCC, good.item(), receipt.getTime(), List.copyOf(result.items()));
    }
  }

  private DiceCombatShop() {}
}
