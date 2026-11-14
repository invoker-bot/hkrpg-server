package org.hkrpg.server.gameplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.ShopGoodsExcel;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.enums.ItemSubType;
import emu.lunarcore.game.shop.ShopItems;
import java.util.ArrayList;
import java.util.List;

/** Local 4.6 fashion offers; the fixed client needs the matching design-data patch. */
public final class FashionShopCatalog {
  private record Entry(int goods, int item, int shop, int currency, int price, int outfit) {}
  private static final List<Entry> ENTRIES = List.of(
      new Entry(109006, 227001, 109, 3, 680, 1001),
      new Entry(109007, 227002, 109, 3, 680, 1002),
      new Entry(109008, 227003, 109, 3, 680, 1003),
      new Entry(109009, 227005, 109, 3, 680, 1005),
      new Entry(109010, 227007, 109, 3, 680, 1007),
      new Entry(109011, 227008, 109, 3, 680, 1008),
      new Entry(109012, 227015, 109, 3, 680, 4001),
      new Entry(109013, 227017, 109, 3, 680, 1017),
      new Entry(108022, 252001, 108, 263, 1200, 0));

  /** Validate all identities and operator resources before publishing any row. */
  public static boolean install() {
    var fashion = GameData.getShopExcelMap().get(109);
    var starlight = GameData.getShopExcelMap().get(108);
    if (fashion == null || starlight == null || fashion.getShopType() != 1 || starlight.getShopType() != 1)
      return skip("shop-missing", 0);
    if (!sourceMatches(fashion.getGoods().get(109002), 227013, 3, 680)
        || !sourceMatches(starlight.getGoods().get(108012), 252004, 263, 1200))
      return skip("source-offer-mismatch", 0);
    int alreadyPresent = 0;
    for (var entry : ENTRIES) {
      var item = GameData.getItemExcelMap().get(entry.item());
      var expectedType = entry.outfit() == 0 ? ItemSubType.PamSkin : ItemSubType.PlayerOutfit;
      if (item == null || item.getItemMainType() != ItemMainType.Usable
          || item.getItemSubType() != expectedType || !ShopItems.deliverable(item))
        return skip("item-resource-mismatch", entry.item());
      if (entry.outfit() == 0) {
        if (GameData.getPomSkinExcelMap().get(entry.item()) == null) return skip("pam-resource-missing", entry.item());
      } else {
        var outfit = GameData.getPlayerOutfitBaseExcelMap().get(entry.outfit());
        if (item.getPileLimit() != 1 || outfit == null || outfit.getItemID() != entry.item()
            || outfit.getSlotTypeList() == null || outfit.getSlotTypeList().length == 0)
          return skip("outfit-resource-mismatch", entry.item());
      }
      for (var shop : GameData.getShopExcelMap().values()) {
        for (var existing : shop.getGoods().values()) {
          if (existing.getGoodsID() == entry.goods()) {
            if (shop.getId() != entry.shop() || !matches(existing, entry))
              return skip("goods-id-conflict", entry.goods());
            alreadyPresent++;
          } else if (existing.getItemID() == entry.item()) {
            return skip("item-already-offered", entry.item());
          }
        }
      }
    }
    if (alreadyPresent == ENTRIES.size()) return true;
    if (alreadyPresent != 0) return skip("partial-catalog", alreadyPresent);
    var added = new ArrayList<Entry>();
    try {
      for (var entry : ENTRIES) {
        var row = new JsonObject();
        row.addProperty("ShopID", entry.shop());
        row.addProperty("GoodsID", entry.goods());
        row.addProperty("ItemID", entry.item());
        row.addProperty("ItemCount", 1);
        row.addProperty("LimitTimes", 1);
        var currency = new JsonArray(); currency.add(entry.currency()); row.add("CurrencyList", currency);
        var price = new JsonArray(); price.add(entry.price()); row.add("CurrencyCostList", price);
        var goods = ResourceLoader.getGson().fromJson(row, ShopGoodsExcel.class);
        goods.onLoad();
        added.add(entry);
        if (!matches(GameData.getShopExcelMap().get(entry.shop()).getGoods().get(entry.goods()), entry))
          throw new IllegalStateException("Invalid fashion entry");
      }
      return true;
    } catch (RuntimeException failed) {
      added.forEach(entry -> GameData.getShopExcelMap().get(entry.shop()).getGoods().remove(entry.goods()));
      return skip("catalog-install-failed", 0);
    }
  }

  private static boolean sourceMatches(ShopGoodsExcel goods, int item, int currency, int price) {
    return goods != null && goods.getItemID() == item && goods.getItemCount() == 1 && goods.getLimitTimes() == 1
        && goods.getCostList() != null && goods.getCostList().size() == 1
        && goods.getCostList().getFirst().getId() == currency && goods.getCostList().getFirst().getCount() == price;
  }

  private static boolean matches(ShopGoodsExcel goods, Entry entry) {
    return goods != null && goods.getGoodsID() == entry.goods()
        && sourceMatches(goods, entry.item(), entry.currency(), entry.price());
  }

  private static boolean skip(String reason, int id) {
    LunarCore.getLogger().warn("Fashion shop catalog skipped: reason={} id={}", reason, id);
    return false;
  }

  private FashionShopCatalog() {}
}
