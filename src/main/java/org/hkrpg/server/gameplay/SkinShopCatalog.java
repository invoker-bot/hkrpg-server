package org.hkrpg.server.gameplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.ShopGoodsExcel;
import emu.lunarcore.game.enums.ItemSubType;
import emu.lunarcore.game.shop.ShopItems;
import java.util.ArrayList;
import java.util.List;

/** Three local 4.6 shop entries; the matched client needs the companion data patch. */
public final class SkinShopCatalog {
  private record Entry(int goods, int skin, int avatar, int currency, int price, int source) {}
  private static final int SHOP = 107, RECOMMEND = 996;
  private static final List<Entry> ENTRIES = List.of(
      new Entry(107013, 1141501, 1415, 3, 1680, 0),
      new Entry(107015, 1150501, 1505, 1, 2680, 107012),
      new Entry(107016, 1140901, 1409, 3, 1350, 107014));

  /** Validate everything before adding any row; never replace an operator resource. */
  public static boolean install() {
    var shop = GameData.getShopExcelMap().get(SHOP);
    var recommend = GameData.getShopExcelMap().get(RECOMMEND);
    if (shop == null || recommend == null || shop.getShopType() != 1) return skip("shop-missing", 0);
    int alreadyPresent = 0;
    for (var entry : ENTRIES) {
      var skin = GameData.getAvatarSkinExcelMap().get(entry.skin());
      var item = GameData.getItemExcelMap().get(entry.skin());
      if (skin == null || skin.getAvatarID() != entry.avatar() || item == null
          || item.getItemSubType() != ItemSubType.AvatarSkin || !ShopItems.deliverable(item))
        return skip("skin-resource-mismatch", entry.skin());
      if (skin.getPlayerCardID() > 0
          && !ShopItems.deliverable(GameData.getItemExcelMap().get(skin.getPlayerCardID())))
        return skip("skin-portrait-missing", skin.getPlayerCardID());
      if (entry.source() != 0 && !matches(recommend.getGoods().get(entry.source()), entry, false))
        return skip("source-offer-mismatch", entry.source());
      for (var existingShop : GameData.getShopExcelMap().values()) {
        var existing = existingShop.getGoods().get(entry.goods());
        if (existing == null) continue;
        if (existingShop.getId() != SHOP || !matches(existing, entry, true))
          return skip("goods-id-conflict", entry.goods());
        alreadyPresent++;
      }
    }
    if (alreadyPresent == ENTRIES.size()) return true;
    if (alreadyPresent != 0) return skip("partial-catalog", alreadyPresent);
    var added = new ArrayList<Integer>();
    try {
      for (var entry : ENTRIES) {
        var row = new JsonObject();
        row.addProperty("ShopID", SHOP);
        row.addProperty("GoodsID", entry.goods());
        row.addProperty("ItemID", entry.skin());
        row.addProperty("ItemCount", 1);
        row.addProperty("LimitTimes", 1);
        var currency = new JsonArray(); currency.add(entry.currency()); row.add("CurrencyList", currency);
        var price = new JsonArray(); price.add(entry.price()); row.add("CurrencyCostList", price);
        var goods = ResourceLoader.getGson().fromJson(row, ShopGoodsExcel.class);
        goods.onLoad();
        added.add(entry.goods());
        if (!matches(shop.getGoods().get(entry.goods()), entry, true)) throw new IllegalStateException("Invalid shop entry");
      }
      return true;
    } catch (RuntimeException failed) {
      added.forEach(id -> shop.getGoods().remove(id));
      return skip("catalog-install-failed", 0);
    }
  }

  private static boolean matches(ShopGoodsExcel goods, Entry expected, boolean local) {
    return goods != null && (!local || goods.getGoodsID() == expected.goods())
        && goods.getItemID() == expected.skin() && goods.getItemCount() == 1
        && goods.getLimitTimes() == 1 && goods.getCostList() != null && goods.getCostList().size() == 1
        && goods.getCostList().getFirst().getId() == expected.currency()
        && goods.getCostList().getFirst().getCount() == expected.price();
  }

  private static boolean skip(String reason, int id) {
    LunarCore.getLogger().warn("Skin shop catalog skipped: reason={} id={}", reason, id);
    return false;
  }

  private SkinShopCatalog() {}
}
