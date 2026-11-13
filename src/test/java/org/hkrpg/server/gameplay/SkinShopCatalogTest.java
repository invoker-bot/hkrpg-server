package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.*;

class SkinShopCatalogTest {
  final Gson gson = new Gson();
  ShopExcel priorShop, priorRecommend;
  final int[] skins = {1141501, 1150501, 1140901};
  final Map<Integer, AvatarSkinExcel> priorSkins = new HashMap<>();
  final Map<Integer, ItemExcel> priorItems = new HashMap<>();

  @BeforeEach void resources() {
    priorShop = GameData.getShopExcelMap().get(107);
    priorRecommend = GameData.getShopExcelMap().get(996);
    GameData.getShopExcelMap().add(gson.fromJson("{\"ShopID\":107,\"ShopType\":1}", ShopExcel.class));
    GameData.getShopExcelMap().add(gson.fromJson("{\"ShopID\":996,\"ShopType\":1}", ShopExcel.class));
    for (int i = 0; i < skins.length; i++) {
      int avatar = new int[] {1415, 1505, 1409}[i];
      priorSkins.put(skins[i], GameData.getAvatarSkinExcelMap().get(skins[i]));
      priorItems.put(skins[i], GameData.getItemExcelMap().get(skins[i]));
      GameData.getAvatarSkinExcelMap().add(gson.fromJson("{\"ID\":"+skins[i]+",\"AvatarID\":"+avatar+"}", AvatarSkinExcel.class));
      GameData.getItemExcelMap().add(gson.fromJson("{\"ID\":"+skins[i]+",\"ItemMainType\":\"Usable\",\"ItemSubType\":\"AvatarSkin\"}", ItemExcel.class));
    }
    source(107012, 1150501, 1, 2680);
    source(107014, 1140901, 3, 1350);
  }

  void source(int id, int item, int currency, int price) {
    gson.fromJson("{\"ShopID\":996,\"GoodsID\":"+id+",\"ItemID\":"+item
        +",\"ItemCount\":1,\"LimitTimes\":1,\"CurrencyList\":["+currency
        +"],\"CurrencyCostList\":["+price+"]}", ShopGoodsExcel.class).onLoad();
  }

  boolean install() throws Exception {
    return SkinShopCatalog.install();
  }

  @AfterEach void restore() {
    if (priorShop == null) GameData.getShopExcelMap().keySet().remove(107); else GameData.getShopExcelMap().add(priorShop);
    if (priorRecommend == null) GameData.getShopExcelMap().keySet().remove(996); else GameData.getShopExcelMap().add(priorRecommend);
    for (int id : skins) {
      if (priorSkins.get(id)==null) GameData.getAvatarSkinExcelMap().keySet().remove(id); else GameData.getAvatarSkinExcelMap().add(priorSkins.get(id));
      if (priorItems.get(id)==null) GameData.getItemExcelMap().keySet().remove(id); else GameData.getItemExcelMap().add(priorItems.get(id));
    }
  }

  @Test void missingSkinsBecomeDistinctPurchasableGoodsWithoutReplacingSourceOffers() throws Exception {
    assertTrue(install());
    var shop = GameData.getShopExcelMap().get(107);
    for (var expected : List.of(new int[]{107013,1141501,3,1680},
        new int[]{107015,1150501,1,2680}, new int[]{107016,1140901,3,1350})) {
      var goods = shop.getGoods().get(expected[0]);
      assertNotNull(goods);
      assertEquals(expected[1], goods.getItemID());
      assertEquals(1, goods.getLimitTimes());
      assertEquals(expected[2], goods.getCostList().getFirst().getId());
      assertEquals(expected[3], goods.getCostList().getFirst().getCount());
    }
    assertEquals(2, GameData.getShopExcelMap().get(996).getGoods().size());
    assertTrue(install());
    assertEquals(3, shop.getGoods().size());
  }

  @Test void conflictingGoodsPreventTheWholeCompatibilityCatalog() throws Exception {
    gson.fromJson("{\"ShopID\":107,\"GoodsID\":107015,\"ItemID\":101,\"ItemCount\":1,\"CurrencyList\":[1],\"CurrencyCostList\":[1]}",ShopGoodsExcel.class).onLoad();
    assertFalse(install());
    var goods = GameData.getShopExcelMap().get(107).getGoods();
    assertEquals(1, goods.size());
    assertEquals(101, goods.get(107015).getItemID());
  }
}
