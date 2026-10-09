package emu.lunarcore.game.shop;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.data.excel.ShopExcel;
import emu.lunarcore.data.excel.ShopGoodsExcel;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.BaseGameService;
import emu.lunarcore.server.game.GameServer;
import java.util.ArrayList;
import java.util.List;

public class ShopService extends BaseGameService {

  public ShopService(GameServer server) {
    super(server);
  }

  public List<GameItem> buyGoods(Player player, int shopId, int goodsId, int count) {
    synchronized (player) {
      if (count <= 0) return null;
      // Get shop and goods excels
      ShopExcel shop = GameData.getShopExcelMap().get(shopId);
      if (shop == null) return null;

      ShopGoodsExcel goods = shop.getGoods().get(goodsId);
      if (goods == null) return null;

      ItemExcel itemExcel = GameData.getItemExcelMap().get(goods.getItemID());
      if (itemExcel == null) return null;
      long quantity = (long) goods.getItemCount() * count;
      if (quantity <= 0 || quantity > Integer.MAX_VALUE || goods.getCostList() == null) return null;
      int num = (int) quantity;
      var payments = new java.util.TreeMap<Integer, Integer>();
      try {
        for (var cost : goods.getCostList()) {
          if (cost.getCount() < 0 || cost.getId() <= 0) return null;
          int amount = Math.multiplyExact(cost.getCount(), count);
          if (amount > 0) payments.merge(cost.getId(), amount, Math::addExact);
        }
      } catch (ArithmeticException e) {
        return null;
      }
      var costs =
          payments.entrySet().stream()
              .map(c -> new emu.lunarcore.data.common.ItemParam(c.getKey(), c.getValue()))
              .toList();
      var tab = player.getInventory().getTabByItemType(itemExcel.getItemMainType());
      if (itemExcel.isEquippable()) {
        if (tab == null || tab.getAvailableCapacity() < num) return null;
      } else if (emu.lunarcore.game.inventory.Inventory.isStackType(itemExcel)) {
        var existing = player.getInventory().getMaterialByItemId(goods.getItemID());
        if (tab == null
            || (existing == null && tab.getAvailableCapacity() == 0)
            || (long) (existing == null ? 0 : existing.getCount()) + num > itemExcel.getPileLimit())
          return null;
      }

      // Verify item params
      if (!player.getInventory().hasItems(costs)) {
        return null;
      }

      // Handle payment
      player.getInventory().removeItemsByParams(costs);

      // Buy items
      List<GameItem> items = new ArrayList<>();

      if (!itemExcel.isEquippable()) {
        GameItem item = new GameItem(itemExcel, num);
        items.add(item);
      } else {
        for (int i = 0; i < num; i++) {
          GameItem item = new GameItem(itemExcel, 1);
          items.add(item);
        }
      }

      // Add to inventory
      var received = player.getInventory().addItems(items);
      long delivered =
          received.stream()
              .filter(i -> i.getItemId() == goods.getItemID())
              .mapToLong(GameItem::getCount)
              .sum();
      if (delivered == num) player.getMissionManager().onShopPurchase(goodsId);

      return received.isEmpty() ? null : received;
    }
  }
}
