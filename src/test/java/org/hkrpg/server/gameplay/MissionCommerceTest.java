package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.shop.ShopService;
import java.util.*;
import org.junit.jupiter.api.*;

class MissionCommerceTest {
  static final int MAIN = 199940, ITEM = 19994001, GOODS = 19994002, SHOP = 19994003;
  final GameplayProgressTest fixture = new GameplayProgressTest();
  final InventoryGrantDeltaTest stocks = new InventoryGrantDeltaTest();

  @AfterEach
  void cleanup() {
    fixture.reset();
    GameData.getItemExcelMap().keySet().remove(ITEM);
    GameData.getShopExcelMap().keySet().remove(SHOP);
  }

  InventoryGrantDeltaTest.Resident consumer(String subs) {
    fixture.mission(MAIN, "Manual", "1999401,1999402", subs);
    var p = stocks.stocked(ITEM, 12, 99);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, p.getMissionManager().accept(MAIN));
    return p;
  }

  @Test
  void actualDirectDebitAdvancesOneUseEventAndFailedDebitsNeverAdvance() {
    var p =
        consumer(
            """
[{"ID":1999401,"MainMissionID":199940,"TakeType":"Auto","FinishType":"UseItem","ParamType":"Equal","ParamInt1":19994001,"Progress":3},
 {"ID":1999402,"MainMissionID":199940,"TakeType":"Auto","FinishType":"UseItem","ParamType":"NotEqual","ParamInt1":19994001,"Progress":3}]
""");
    var item = p.inventory.getMaterialByItemId(ITEM);
    assertTrue(p.inventory.removeItem(item, 2));
    assertEquals(1, p.getMissionManager().sub(1999401).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999402).getProgress());
    assertFalse(p.inventory.removeItem(item, 0));
    assertFalse(p.inventory.removeItem(item, -1));
    assertFalse(p.inventory.removeItem(item, 100));
    assertEquals(10, item.getCount());
    assertEquals(1, p.getMissionManager().sub(1999401).getProgress());
  }

  @Test
  void batchDebitsUseActualIdsAndDoNotAdvanceAnObjectiveUnlockedByTheSameEvent() {
    var p =
        consumer(
            """
[{"ID":1999401,"MainMissionID":199940,"TakeType":"Auto","FinishType":"UseItem","ParamType":"GreaterEqual","ParamInt1":19994001},
 {"ID":1999402,"MainMissionID":199940,"TakeType":"MultiSequence","TakeParamIntList":[1999401],"FinishType":"UseItem","ParamType":"Equal","ParamInt1":19994001}]
""");
    p.inventory.removeItemsByParams(List.of(new ItemParam(ITEM, 1), new ItemParam(ITEM, 1)));
    assertEquals(1, p.getMissionManager().sub(1999401).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999402).getProgress());
    p.inventory.removeItems(List.of(p.inventory.getMaterialByItemId(ITEM)));
    assertEquals(1, p.getMissionManager().sub(1999402).getProgress());
  }

  ShopService shop(int itemCount) {
    var gson = new Gson();
    var shop = gson.fromJson("{\"ShopID\":" + SHOP + "}", ShopExcel.class);
    GameData.getShopExcelMap().add(shop);
    var goods =
        gson.fromJson(
            "{\"GoodsID\":"
                + GOODS
                + ",\"ShopID\":"
                + SHOP
                + ",\"ItemID\":"
                + ITEM
                + ",\"ItemCount\":"
                + itemCount
                + ",\"CurrencyList\":[],\"CurrencyCostList\":[]}",
            ShopGoodsExcel.class);
    goods.onLoad();
    return new ShopService(null);
  }

  @Test
  void buyingTheConfiguredGoodsAdvancesOnlyAfterActualDelivery() {
    var p =
        consumer(
            """
[{"ID":1999401,"MainMissionID":199940,"TakeType":"Auto","FinishType":"BuyShopGoods","ParamType":"ListContain","ParamIntList":[19994002]},
 {"ID":1999402,"MainMissionID":199940,"TakeType":"Auto","FinishType":"BuyShopGoods","ParamType":"ListContain","ParamIntList":[19994009]}]
""");
    var service = shop(2);
    assertNull(service.buyGoods(p, SHOP, GOODS + 1, 1));
    assertEquals(0, p.getMissionManager().sub(1999401).getProgress());
    var received = service.buyGoods(p, SHOP, GOODS, 2);
    assertEquals(4, received.getFirst().getCount());
    assertEquals(16, p.inventory.getMaterialByItemId(ITEM).getCount());
    assertEquals(1, p.getMissionManager().sub(1999401).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999402).getProgress());
  }

  @Test
  void invalidPurchaseQuantitiesAndOverflowDoNotCreateAPurchaseOrCompleteAMission() {
    var p =
        consumer(
            """
[{"ID":1999401,"MainMissionID":199940,"TakeType":"Auto","FinishType":"BuyShopGoods","ParamType":"ListContain","ParamIntList":[19994002]}]
""");
    var service = shop(2);
    for (int count : List.of(0, -1, Integer.MAX_VALUE))
      assertNull(service.buyGoods(p, SHOP, GOODS, count));
    assertEquals(12, p.inventory.getMaterialByItemId(ITEM).getCount());
    assertEquals(0, p.getMissionManager().sub(1999401).getProgress());
  }

  @Test
  void teamLeaderChangesUseTheActualOwnedLeaderAndRepeatedSelectionDoesNotCountAsANewChange() {
    fixture.mission(
        MAIN,
        "Manual",
        "1999401,1999402",
        """
[{"ID":1999401,"MainMissionID":199940,"TakeType":"Auto","FinishType":"TeamLeaderChange","ParamType":"Equal","ParamInt1":199962},
 {"ID":1999402,"MainMissionID":199940,"TakeType":"MultiSequence","TakeParamIntList":[1999401],"FinishType":"TeamLeaderChange","ParamType":"Equal","ParamInt1":199962}]
""");
    var p =
        new StoryLineupTest.Director() {
          public emu.lunarcore.game.scene.Scene getScene() {
            return null;
          }
        };
    p.team.getAvatars().add(199962);
    p.getMissionManager().accept(MAIN);
    assertFalse(p.teams.changeLeader(9));
    assertEquals(0, p.getMissionManager().sub(1999401).getProgress());
    assertTrue(p.teams.changeLeader(1));
    assertEquals(1, p.getMissionManager().sub(1999401).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999402).getProgress());
    assertFalse(p.teams.changeLeader(1));
    assertEquals(0, p.getMissionManager().sub(1999402).getProgress());
    assertTrue(p.teams.changeLeader(0));
    assertTrue(p.teams.changeLeader(1));
    assertEquals(1, p.getMissionManager().sub(1999402).getProgress());
  }

  @Test
  void duplicatePricesAndAFullStackRejectThePurchaseBeforeAnyDebit() {
    var p =
        consumer(
            """
[{"ID":1999401,"MainMissionID":199940,"TakeType":"Auto","FinishType":"BuyShopGoods","ParamType":"ListContain","ParamIntList":[19994002]}]
""");
    var service = shop(2);
    assertNull(service.buyGoods(p, SHOP, GOODS, 50));
    var goods =
        new Gson()
            .fromJson(
                "{\"GoodsID\":"
                    + GOODS
                    + ",\"ShopID\":"
                    + SHOP
                    + ",\"ItemID\":"
                    + ITEM
                    + ",\"ItemCount\":2,\"CurrencyList\":["
                    + ITEM
                    + ","
                    + ITEM
                    + "],\"CurrencyCostList\":[7,7]}",
                ShopGoodsExcel.class);
    goods.onLoad();
    assertNull(service.buyGoods(p, SHOP, GOODS, 1));
    assertEquals(12, p.inventory.getMaterialByItemId(ITEM).getCount());
    assertEquals(0, p.getMissionManager().sub(1999401).getProgress());
  }

  @Test
  void concurrentDebitsUseTheLiveOwnedStackAndFakeObjectsCannotRemoveIt() throws Exception {
    var p =
        consumer(
            """
[{"ID":1999401,"MainMissionID":199940,"TakeType":"Auto","FinishType":"UseItem","ParamType":"Equal","ParamInt1":19994001,"Progress":3}]
""");
    var item = p.inventory.getMaterialByItemId(ITEM);
    assertFalse(
        p.inventory.removeItem(
            new GameItem(item.getExcel(), 1) {
              public void save() {}
            },
            1));
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 6; i++)
        tasks.add(
            executor.submit(
                () -> {
                  gate.await();
                  return p.inventory.removeItem(item, 12);
                }));
      gate.countDown();
      int successes = 0;
      for (var task : tasks) if (task.get(10, java.util.concurrent.TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes);
    }
    assertNull(p.inventory.getMaterialByItemId(ITEM));
    assertEquals(1, p.getMissionManager().sub(1999401).getProgress());
  }

  @Test
  void wrongPlacesCancelledGoalsAndUnacceptedMissionsNeverConsumeAnEvent() {
    var p =
        consumer(
            """
[{"ID":1999401,"MainMissionID":199940,"TakeType":"Auto","FinishType":"UseItem","ParamType":"Equal","ParamInt1":19994001,"LevelFloorID":10301001},
 {"ID":1999402,"MainMissionID":199940,"TakeType":"Auto","FinishType":"UseItem","ParamType":"Equal","ParamInt1":19994001}]
""");
    p.getGameplay().getCancelledSubs().add(1999402);
    assertTrue(p.inventory.removePileItem(ITEM, 1));
    assertEquals(0, p.getMissionManager().sub(1999401).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999402).getProgress());
    p.getGameplay().getMainMissions().clear();
    p.getGameplay().getCancelledSubs().clear();
    assertTrue(p.inventory.removePileItem(ITEM, 1));
    assertEquals(0, p.getMissionManager().sub(1999402).getProgress());
  }
}
