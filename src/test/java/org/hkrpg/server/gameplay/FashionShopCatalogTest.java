package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import java.util.*;
import org.junit.jupiter.api.*;

class FashionShopCatalogTest {
  private final Gson gson = new Gson();
  private final int[] itemIds = {227001, 227002, 227003, 227005, 227007, 227008, 227015, 227017};
  private final int[] outfitIds = {1001, 1002, 1003, 1005, 1007, 1008, 4001, 1017};
  private final Map<Integer, ItemExcel> oldItems = new HashMap<>();
  private final Map<Integer, PlayerOutfitBaseExcel> oldOutfits = new HashMap<>();
  private List<ShopExcel> oldShops;
  private PomSkinExcel oldPam;

  @BeforeEach void resources() {
    oldShops = List.copyOf(GameData.getShopExcelMap().values());
    GameData.getShopExcelMap().values().clear();
    shop(108); shop(109);
    for (int i = 0; i < itemIds.length; i++) {
      int item = itemIds[i], outfit = outfitIds[i];
      oldItems.put(item, GameData.getItemExcelMap().get(item));
      oldOutfits.put(outfit, GameData.getPlayerOutfitBaseExcelMap().get(outfit));
      GameData.getItemExcelMap().add(gson.fromJson("{\"ID\":" + item
          + ",\"ItemMainType\":\"Usable\",\"ItemSubType\":\"PlayerOutfit\",\"PileLimit\":1}", ItemExcel.class));
      GameData.getPlayerOutfitBaseExcelMap().add(gson.fromJson("{\"OutfitID\":" + outfit
          + ",\"ItemID\":" + item + ",\"SlotTypeList\":[\"HeadDecor\"]}", PlayerOutfitBaseExcel.class));
    }
    oldItems.put(252001, GameData.getItemExcelMap().get(252001));
    GameData.getItemExcelMap().add(gson.fromJson("{\"ID\":252001,\"ItemMainType\":\"Usable\","
        + "\"ItemSubType\":\"PamSkin\",\"PileLimit\":100}", ItemExcel.class));
    oldPam = GameData.getPomSkinExcelMap().get(252001);
    GameData.getPomSkinExcelMap().add(gson.fromJson("{\"SkinID\":252001}", PomSkinExcel.class));
    source(109, 109002, 227013, 3, 680);
    source(108, 108012, 252004, 263, 1200);
  }

  private void shop(int id) {
    GameData.getShopExcelMap().add(gson.fromJson("{\"ShopID\":" + id + ",\"ShopType\":1}", ShopExcel.class));
  }

  private void source(int shop, int goods, int item, int currency, int price) {
    gson.fromJson("{\"ShopID\":" + shop + ",\"GoodsID\":" + goods + ",\"ItemID\":" + item
        + ",\"ItemCount\":1,\"LimitTimes\":1,\"CurrencyList\":[" + currency
        + "],\"CurrencyCostList\":[" + price + "]}", ShopGoodsExcel.class).onLoad();
  }

  @AfterEach void restore() {
    GameData.getShopExcelMap().values().clear();
    oldShops.forEach(GameData.getShopExcelMap()::add);
    oldItems.forEach((id, old) -> {
      if (old == null) GameData.getItemExcelMap().keySet().remove((int) id);
      else GameData.getItemExcelMap().add(old);
    });
    oldOutfits.forEach((id, old) -> {
      if (old == null) GameData.getPlayerOutfitBaseExcelMap().keySet().remove((int) id);
      else GameData.getPlayerOutfitBaseExcelMap().add(old);
    });
    if (oldPam == null) GameData.getPomSkinExcelMap().keySet().remove(252001);
    else GameData.getPomSkinExcelMap().add(oldPam);
  }

  @Test void allMissingItemsGetOnePurchasableOfferAndExistingOffersStayIntact() {
    var fashion = GameData.getShopExcelMap().get(109);
    var starlight = GameData.getShopExcelMap().get(108);
    var originalFashion = fashion.getGoods().get(109002);
    var originalPam = starlight.getGoods().get(108012);
    assertTrue(FashionShopCatalog.install());
    for (int i = 0; i < itemIds.length; i++) {
      var goods = fashion.getGoods().get(109006 + i);
      assertNotNull(goods);
      assertEquals(itemIds[i], goods.getItemID());
      assertEquals(1, goods.getLimitTimes());
      assertEquals(3, goods.getCostList().getFirst().getId());
      assertEquals(680, goods.getCostList().getFirst().getCount());
    }
    var chef = starlight.getGoods().get(108022);
    assertNotNull(chef);
    assertEquals(252001, chef.getItemID());
    assertEquals(263, chef.getCostList().getFirst().getId());
    assertEquals(1200, chef.getCostList().getFirst().getCount());
    assertSame(originalFashion, fashion.getGoods().get(109002));
    assertSame(originalPam, starlight.getGoods().get(108012));
    assertTrue(FashionShopCatalog.install());
    assertEquals(9, fashion.getGoods().size());
    assertEquals(2, starlight.getGoods().size());
  }

  @Test void aConflictingGoodsIdOrExistingOfferLeavesTheWholeBatchUntouched() {
    shop(500);
    source(500, 109008, 1, 3, 1);
    assertFalse(FashionShopCatalog.install());
    assertEquals(1, GameData.getShopExcelMap().get(109).getGoods().size());
    assertEquals(1, GameData.getShopExcelMap().get(108).getGoods().size());
    GameData.getShopExcelMap().get(500).getGoods().clear();
    source(500, 500001, 227001, 1, 5);
    assertFalse(FashionShopCatalog.install());
    assertEquals(1, GameData.getShopExcelMap().get(109).getGoods().size());
    assertEquals(1, GameData.getShopExcelMap().get(108).getGoods().size());
  }

  @Test void missingOrChangedResourcesAndSourcePricesRejectTheWholeBatch() {
    GameData.getPlayerOutfitBaseExcelMap().keySet().remove(4001);
    assertFalse(FashionShopCatalog.install());
    assertEquals(1, GameData.getShopExcelMap().get(109).getGoods().size());
    assertEquals(1, GameData.getShopExcelMap().get(108).getGoods().size());
    GameData.getPlayerOutfitBaseExcelMap().add(gson.fromJson(
        "{\"OutfitID\":4001,\"ItemID\":227015,\"SlotTypeList\":[\"BackDecor\"]}", PlayerOutfitBaseExcel.class));
    source(108, 108012, 252004, 263, 1);
    assertFalse(FashionShopCatalog.install());
    assertEquals(1, GameData.getShopExcelMap().get(109).getGoods().size());
    assertEquals(1, GameData.getShopExcelMap().get(108).getGoods().size());
  }
}
