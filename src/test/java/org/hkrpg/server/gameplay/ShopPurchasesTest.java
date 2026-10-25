package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.ResourceType;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.game.shop.ShopService;
import java.util.*;
import org.junit.jupiter.api.*;

class ShopPurchasesTest {
  final Gson gson = new Gson();
  final Map<Integer, ItemExcel> previous = new HashMap<>();
  final int shopId = 199871;
  final ShopService service = new ShopService(null);
  final InventoryGrantDeltaTest.Resident player = new InventoryGrantDeltaTest.Resident() {
    public String getName() { return "Shop regression"; }
  };

  ItemExcel item(int id, String main, String sub, int limit) {
    previous.putIfAbsent(id, GameData.getItemExcelMap().get(id));
    var row = gson.fromJson("{\"ID\":"+id+",\"ItemMainType\":\""+main
        +"\",\"ItemSubType\":\""+sub+"\",\"PileLimit\":"+limit+"}", ItemExcel.class);
    GameData.getItemExcelMap().add(row);
    return row;
  }

  void goods(int item, int currency, int price, int limit) {
    var shop = gson.fromJson("{\"ShopID\":"+shopId+",\"ShopType\":1}", ShopExcel.class);
    GameData.getShopExcelMap().add(shop);
    var row = gson.fromJson("{\"GoodsID\":199871001,\"ShopID\":"+shopId
        +",\"ItemID\":"+item+",\"ItemCount\":1,\"LimitTimes\":"+limit
        +",\"CurrencyList\":["+currency+"],\"CurrencyCostList\":["+price+"]}",ShopGoodsExcel.class);
    row.onLoad();
  }

  @AfterEach void clean() {
    previous.forEach((id, row) -> { if(row==null) GameData.getItemExcelMap().keySet().remove(id);
      else GameData.getItemExcelMap().add(row); });
    GameData.getShopExcelMap().keySet().remove(shopId);
  }

  @Test void skinItemDefinitionsMustBeLoadedAlongsideTheCatalog() {
    assertTrue(List.of(ItemExcel.class.getAnnotation(ResourceType.class).name())
        .contains("ItemConfigAvatarSkin.json"));
  }

  @Test void premiumCurrencyIsSpendableAndActuallyDebited() {
    item(3,"Virtual","Virtual",99999999);
    player.addMCoin(2000);
    assertTrue(player.inventory.hasItem(3,1680));
    player.inventory.removeItemsByParams(List.of(new ItemParam(3,1680)));
    assertEquals(320,player.getMcoin());
  }

  @Test void skinPurchaseUnlocksAndCannotChargeTwice() {
    item(1,"Virtual","Virtual",99999999);
    item(1131001,"Usable","AvatarSkin",1);
    player.addHCoin(4000);
    goods(1131001,1,1680,1);
    assertNotNull(service.buyGoods(player,shopId,199871001,1));
    assertTrue(player.getUnlocks().getAvatarSkins().contains(1131001));
    assertNull(service.buyGoods(player,shopId,199871001,1));
    assertEquals(2320,player.getHcoin());
  }

  @Test void unsupportedDeliveryMustNotTakePayment() {
    item(1,"Virtual","Virtual",99999999);
    item(199871002,"Usable","Unknown",1);
    player.addHCoin(4000); goods(199871002,1,100,1);
    assertNull(service.buyGoods(player,shopId,199871001,1));
    assertEquals(4000,player.getHcoin());
  }

  @Test void purchaseLimitAppliesToTheWholeBatchBeforePayment() {
    item(1,"Virtual","Virtual",99999999);
    item(199871003,"Usable","HeadIcon",1);
    player.addHCoin(4000); goods(199871003,1,100,1);
    assertNull(service.buyGoods(player,shopId,199871001,2));
    assertEquals(4000,player.getHcoin());
    assertFalse(player.getUnlocks().getHeadIcons().contains(199871003));
  }

  @Test void limitAndCountsSurviveStateSerialization() throws Exception {
    item(1,"Virtual","Virtual",99999999);
    item(199871004,"Material","Material",99);
    player.addHCoin(4000); goods(199871004,1,100,3);
    var now = java.time.Instant.parse("2026-10-25T12:00:00Z");
    assertEquals(2, service.buy(player,shopId,199871001,2,0,List.of(),now).bought());
    var restored = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    field.setAccessible(true); field.set(player,restored);
    assertNull(service.buyGoods(player,shopId,199871001,2));
    assertEquals(3, service.buy(player,shopId,199871001,1,0,List.of(),now).bought());
    assertEquals(3700,player.getHcoin());
  }

  @Test void fullStackAndMissingCurrencyDoNotChangeEitherSide() {
    item(1,"Virtual","Virtual",99999999); item(199871004,"Material","Material",5);
    player.addHCoin(4000); goods(199871004,1,100,0);
    assertTrue(player.inventory.addItem(199871004,5));
    assertNull(service.buyGoods(player,shopId,199871001,1));
    assertEquals(4000,player.getHcoin());
    assertEquals(5,player.inventory.getMaterialByItemId(199871004).getCount());
    assertNull(player.getGameplay().getShopPurchases().getPending());
  }

  @Test void optionalGiftRequiresEveryValidGroupBeforeCharging() {
    item(1,"Virtual","Virtual",99999999); item(199871004,"Material","Material",999);
    item(199871005,"Usable","ForceOpitonalGift",99);
    var groups = GameData.getItemGiftPackExcelMap();
    var rewardMap = GameData.getRewardExcelMap();
    var group = gson.fromJson("{\"ID\":199871005,\"GroupID\":1,\"RewardList\":[199871006]}",ItemGiftPackExcel.class);
    var reward = gson.fromJson("{\"RewardID\":199871006,\"ItemID_1\":199871004,\"Count_1\":5}",RewardExcel.class);
    reward.onLoad(); groups.add(group); rewardMap.add(reward);
    try {
      player.addHCoin(4000); goods(199871005,1,100,3);
      for (var choices : List.of(List.<Integer>of(),List.of(199871004),List.of(199871006,199871006))) {
        assertNotEquals(emu.lunarcore.server.packet.Retcode.SUCC,
            service.buy(player,shopId,199871001,1,0,choices,java.time.Instant.now()).code());
        assertEquals(4000,player.getHcoin());
      }
      var result = service.buy(player,shopId,199871001,2,0,List.of(199871006),java.time.Instant.now());
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,result.code());
      assertEquals(10,player.inventory.getMaterialByItemId(199871004).getCount());
      assertNull(player.inventory.getMaterialByItemId(199871005));
      assertEquals(3800,player.getHcoin());
    } finally {
      groups.values().remove(group); rewardMap.keySet().remove(199871006);
    }
  }

  @Test void storedVirtualAndCollectiblesUseRealInventoryBalances() {
    item(1,"Virtual","Virtual",99999999); item(61,"Virtual","Virtual",99999);
    item(253004,"Usable","PersonalCard",1);
    player.addHCoin(4000); goods(61,1,100,3);
    assertNotNull(service.buyGoods(player,shopId,199871001,2));
    assertTrue(player.inventory.hasItem(61,2));
    player.getGameplay().getShopPurchases().getCounts().clear();
    goods(253004,61,2,1);
    assertNotNull(service.buyGoods(player,shopId,199871001,1));
    assertFalse(player.inventory.hasItem(61,1));
    assertTrue(player.inventory.hasItem(253004,1));
    assertNull(service.buyGoods(player,shopId,199871001,1));
  }

  @Test void monthlyRefreshUsesFourAmBoundaryAndLeavesOldReceiptsIntact() {
    var g = gson.fromJson("{\"GoodsID\":199871001,\"RefreshType\":\"MONTH\"}",ShopGoodsExcel.class);
    assertEquals("199871001:2026-09-01",ShopService.periodKey(g,java.time.Instant.parse("2026-09-30T19:59:59Z")));
    assertEquals("199871001:2026-10-01",ShopService.periodKey(g,java.time.Instant.parse("2026-09-30T20:00:00Z")));
  }

  @Test void interruptedPaymentIsNotChargedAgain() {
    item(1,"Virtual","Virtual",99999999); item(199871004,"Material","Material",99);
    var p = new InventoryGrantDeltaTest.Resident() {
      int saves;
      public String getName() { return "Interrupted purchase"; }
      public void save() { if (++saves == 2) throw new IllegalStateException("Injected storage failure"); }
    };
    p.addHCoin(4000); goods(199871004,1,100,3);
    assertThrows(IllegalStateException.class,() -> service.buyGoods(p,shopId,199871001,1));
    assertEquals(3900,p.getHcoin());
    assertNull(p.inventory.getMaterialByItemId(199871004));
    assertNull(service.buyGoods(p,shopId,199871001,1));
    assertEquals(3900,p.getHcoin());
    assertNotNull(gson.fromJson(gson.toJson(p.getGameplay()),GameplayState.class).getShopPurchases().getPending());
  }
}
