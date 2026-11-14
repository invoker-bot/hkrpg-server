package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.shop.ShopService;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Auto-opening is resolved before the shop reserves and charges the actual exchange. */
class ShopAutoGiftTest {
  private static final int SHOP = 199878, GOODS = 199878001, GIFT = 199878002;
  private static final int FIRST = 199878011, REWARD_A = 199878031, REWARD_B = 199878032;
  private static final Instant NOW = Instant.parse("2026-11-14T16:55:00Z");
  private final Gson gson = new Gson();
  private final Map<Integer, ItemExcel> previous = new HashMap<>();
  private final InventoryGrantDeltaTest.Resident player = new InventoryGrantDeltaTest.Resident() {
    public String getName() { return "Auto gift regression"; }
  };

  private void item(String json) {
    var value = gson.fromJson(json, ItemExcel.class);
    previous.putIfAbsent(value.getId(), GameData.getItemExcelMap().get(value.getId()));
    GameData.getItemExcelMap().add(value);
  }

  private void prepare(boolean auto, boolean completeReward) {
    item("{\"ID\":1,\"ItemMainType\":\"Virtual\",\"PileLimit\":999999}");
    item("{\"ID\":" + GIFT + ",\"ItemMainType\":\"Usable\",\"ItemSubType\":\"Gift\","
        + "\"UseMethod\":\"FixedRewardGift\",\"PileLimit\":99999}");
    for (int id = FIRST; id < FIRST + 7; id++)
      item("{\"ID\":" + id + ",\"ItemMainType\":\"Usable\",\"ItemSubType\":\"Book\","
          + "\"UseMethod\":\"AutoConversionItem\",\"PileLimit\":99999,\"ReturnItemIDList\":[]}");
    var use = gson.fromJson("{\"UseDataID\":" + GIFT + ",\"UseMultipleMax\":1,\"IsAutoUse\":"
        + auto + ",\"UseParam\":[" + REWARD_A + "," + REWARD_B + "]}", ItemUseExcel.class);
    GameData.getItemUseExcelMap().add(use);
    var first = gson.fromJson("{\"RewardID\":" + REWARD_A + ",\"ItemID_1\":" + FIRST
        + ",\"Count_1\":1,\"ItemID_2\":" + (FIRST + 1) + ",\"Count_2\":1,\"ItemID_3\":"
        + (FIRST + 2) + ",\"Count_3\":1,\"ItemID_4\":" + (FIRST + 3) + ",\"Count_4\":1,"
        + "\"ItemID_5\":" + (FIRST + 4) + ",\"Count_5\":1,\"ItemID_6\":" + (FIRST + 5)
        + ",\"Count_6\":1}", RewardExcel.class);
    first.onLoad();
    GameData.getRewardExcelMap().add(first);
    if (completeReward) {
      var second = gson.fromJson("{\"RewardID\":" + REWARD_B + ",\"ItemID_1\":"
          + (FIRST + 6) + ",\"Count_1\":1}", RewardExcel.class);
      second.onLoad();
      GameData.getRewardExcelMap().add(second);
    }
    GameData.getShopExcelMap().add(gson.fromJson("{\"ShopID\":" + SHOP + ",\"ShopType\":1}", ShopExcel.class));
    var goods = gson.fromJson("{\"GoodsID\":" + GOODS + ",\"ShopID\":" + SHOP + ",\"ItemID\":"
        + GIFT + ",\"ItemCount\":1,\"LimitTimes\":1,\"CurrencyList\":[1],\"CurrencyCostList\":[100]}", ShopGoodsExcel.class);
    goods.onLoad();
    player.addHCoin(4000);
  }

  private ShopService.Purchase buy() {
    return new ShopService(null).buy(player, SHOP, GOODS, 1, GIFT, List.of(), NOW);
  }

  @AfterEach void clean() {
    previous.forEach((id, value) -> {
      if (value == null) GameData.getItemExcelMap().keySet().remove(id);
      else GameData.getItemExcelMap().add(value);
    });
    GameData.getItemUseExcelMap().keySet().remove(GIFT);
    GameData.getRewardExcelMap().keySet().remove(REWARD_A);
    GameData.getRewardExcelMap().keySet().remove(REWARD_B);
    GameData.getShopExcelMap().keySet().remove(SHOP);
  }

  @Test void automaticBookGiftIsDeliveredAsItsSevenActualChaptersOnce() {
    prepare(true, true);
    var result = buy();
    assertEquals(Retcode.SUCC, result.code());
    assertEquals(3900, player.getHcoin());
    assertNull(player.inventory.getMaterialByItemId(GIFT));
    var expected = new TreeSet<Integer>();
    for (int id = FIRST; id < FIRST + 7; id++) {
      expected.add(id);
      assertEquals(1, player.inventory.getMaterialByItemId(id).getCount());
    }
    assertEquals(expected, new TreeSet<>(result.items().stream().map(GameItem::getItemId).toList()));
    assertEquals(1, result.bought());
    assertNull(player.getGameplay().getShopPurchases().getPending());
    assertNotEquals(Retcode.SUCC, buy().code());
    assertEquals(3900, player.getHcoin());
    for (int id : expected) assertEquals(1, player.inventory.getMaterialByItemId(id).getCount());
  }

  @Test void missingSecondRewardRejectsBeforeChargingOrCreatingAReceipt() {
    prepare(true, false);
    assertNotEquals(Retcode.SUCC, buy().code());
    assertEquals(4000, player.getHcoin());
    assertNull(player.inventory.getMaterialByItemId(GIFT));
    assertNull(player.getGameplay().getShopPurchases().getPending());
  }

  @Test void manualGiftRemainsStoredForThePlayerToOpen() {
    prepare(false, true);
    assertEquals(Retcode.SUCC, buy().code());
    assertEquals(1, player.inventory.getMaterialByItemId(GIFT).getCount());
    assertNull(player.inventory.getMaterialByItemId(FIRST));
  }

  @Test void aFullChapterStackRejectsTheWholeExchangeBeforeCharging() {
    prepare(true, true);
    assertTrue(player.inventory.addItem(new GameItem(GameData.getItemExcelMap().get(FIRST), 99999) {
      public void save() {}
    }));
    assertNotEquals(Retcode.SUCC, buy().code());
    assertEquals(4000, player.getHcoin());
    assertEquals(99999, player.inventory.getMaterialByItemId(FIRST).getCount());
    assertNull(player.inventory.getMaterialByItemId(GIFT));
    assertNull(player.getGameplay().getShopPurchases().getPending());
  }

  @Test void anEarlierUnopenedGiftIsNotConsumedOrExpandedByANewPurchase() {
    prepare(true, true);
    assertTrue(player.inventory.addItem(new GameItem(GameData.getItemExcelMap().get(GIFT), 1) {
      public void save() {}
    }));
    assertEquals(Retcode.SUCC, buy().code());
    assertEquals(1, player.inventory.getMaterialByItemId(GIFT).getCount());
    for (int id = FIRST; id < FIRST + 7; id++)
      assertEquals(1, player.inventory.getMaterialByItemId(id).getCount());
  }

  @Test void anAlreadyOwnedGiftCanBeOpenedOnceWithoutAnotherPurchase() {
    prepare(true, true);
    assertTrue(player.inventory.addItem(new GameItem(GameData.getItemExcelMap().get(GIFT), 1) {
      public void save() {}
    }));
    var opened = new ConsumableUse(player).use(GIFT, 1, 0);
    assertEquals(Retcode.SUCC, opened.retcode());
    assertEquals(4000, player.getHcoin());
    assertNull(player.inventory.getMaterialByItemId(GIFT));
    assertEquals(7, opened.items().size());
    for (int id = FIRST; id < FIRST + 7; id++)
      assertEquals(1, player.inventory.getMaterialByItemId(id).getCount());
    assertNotEquals(Retcode.SUCC, new ConsumableUse(player).use(GIFT, 1, 0).retcode());
  }

  @Test void aMissingChapterDefinitionRejectsTheWholeExchangeBeforeCharging() {
    prepare(true, true);
    GameData.getItemExcelMap().keySet().remove(FIRST + 6);
    assertNotEquals(Retcode.SUCC, buy().code());
    assertEquals(4000, player.getHcoin());
    assertNull(player.inventory.getMaterialByItemId(GIFT));
    assertNull(player.getGameplay().getShopPurchases().getPending());
  }

  @Test void aNestedAutomaticGiftIsRejectedBeforeCharging() {
    prepare(true, true);
    var nested = gson.fromJson("{\"RewardID\":" + REWARD_A + ",\"ItemID_1\":"
        + GIFT + ",\"Count_1\":1}", RewardExcel.class);
    nested.onLoad();
    GameData.getRewardExcelMap().add(nested);
    assertNotEquals(Retcode.SUCC, buy().code());
    assertEquals(4000, player.getHcoin());
    assertNull(player.inventory.getMaterialByItemId(GIFT));
    assertNull(player.getGameplay().getShopPurchases().getPending());
  }

  @Test void automaticGiftMultiplicationCannotOverflow() {
    prepare(true, true);
    var large = gson.fromJson("{\"RewardID\":" + REWARD_A + ",\"ItemID_1\":"
        + FIRST + ",\"Count_1\":2147483647}", RewardExcel.class);
    large.onLoad();
    GameData.getRewardExcelMap().add(large);
    var goods = gson.fromJson("{\"GoodsID\":" + GOODS + ",\"ItemCount\":2}", ShopGoodsExcel.class);
    assertThrows(IllegalArgumentException.class, () -> ShopService.rewards(goods, GIFT, 1, List.of()));
    assertEquals(4000, player.getHcoin());
    assertNull(player.getGameplay().getShopPurchases().getPending());
  }
}
