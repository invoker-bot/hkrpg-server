package org.hkrpg.server.gacha;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.avatar.AvatarStorage;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.gacha.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class GachaCeilingRewardsTest {
  GachaRarityResources resources;
  final Map<Integer, ItemExcel> previousItems = new HashMap<>();
  final GachaService service = new GachaService(null) { @Override public void watch() {} };
  @BeforeEach void setup() throws Exception {
    resources = new GachaRarityResources();
    for (int id : List.of(11003, 252)) {
      previousItems.put(id, GameData.getItemExcelMap().get(id));
      GameData.getItemExcelMap().add(new Gson().fromJson("{\"ID\":" + id
          + ",\"ItemMainType\":\"Material\",\"ItemSubType\":\"Material\",\"PileLimit\":999999}", ItemExcel.class));
    }
  }
  @AfterEach void close() {
    previousItems.forEach((id, old) -> {
      if (old == null) GameData.getItemExcelMap().keySet().remove(id);
      else GameData.getItemExcelMap().add(old);
    });
    resources.close();
  }

  static class Resident extends Player {
    final PlayerGachaInfo history = new PlayerGachaInfo();
    final AvatarStorage avatars = new AvatarStorage(this);
    final AtomicInteger deliveries = new AtomicInteger();
    volatile boolean rejectDelivery, coordinateLegacy;
    final CountDownLatch entered = new CountDownLatch(2);
    final Inventory inventory = new Inventory(this) {
      @Override public List<GameItem> addItems(List<GameItem> requested) {
        if (coordinateLegacy) {
          entered.countDown();
          try { entered.await(2, TimeUnit.SECONDS); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
        if (rejectDelivery) return List.of();
        deliveries.incrementAndGet();
        return requested;
      }
      @Override public GrantResult grantItems(ItemParamMap requested, boolean showHint) {
        if (rejectDelivery) return new GrantResult(List.of(), false);
        deliveries.incrementAndGet();
        return new GrantResult(requested.toItemList(), true);
      }
    };
    @Override public PlayerGachaInfo getGachaInfo() { return history; }
    @Override public AvatarStorage getAvatars() { return avatars; }
    @Override public Inventory getInventory() { return inventory; }
    @Override public void save() {}
  }

  @Test void shortProgressAndUnlistedAvatarCannotClaim() {
    var p = new Resident(); p.history.addCeilingNum(299);
    assertNull(service.exchangeGachaCeiling(p, 1003));
    p.history.addCeilingNum(1);
    assertNull(service.exchangeGachaCeiling(p, 1102));
    assertFalse(p.history.isCeilingClaimed());
    assertEquals(0, p.deliveries.get());
  }

  @Test void failedDeliveryMustNotMarkTheSelectionAsClaimed() {
    var p = new Resident(); p.history.addCeilingNum(300); p.rejectDelivery = true;
    assertNull(service.exchangeGachaCeiling(p, 1003));
    assertFalse(p.history.isCeilingClaimed());
    assertEquals(0, p.deliveries.get());
    assertTrue(p.getGameplay().getRewards().containsKey(PlayerGachaInfo.CEILING_REWARD_KEY));
    p.rejectDelivery = false;
    assertNull(service.exchangeGachaCeiling(p, 1003), "An interrupted delivery needs reconciliation, not an automatic replay");
    assertEquals(0, p.deliveries.get());
  }

  @Test void successfulSelectionCannotBeGrantedTwice() {
    var p = new Resident(); p.history.addCeilingNum(300);
    var first = service.exchangeGachaCeiling(p, 1003);
    assertNotNull(first);
    assertEquals(1003, first.getFirst().getItemId());
    assertTrue(p.history.isCeilingClaimed());
    assertNull(service.exchangeGachaCeiling(p, 1101));
    assertEquals(1, p.deliveries.get());
    p.history.setCeilingClaimed(false); // Simulate an older persisted legacy flag after receipt completion.
    assertTrue(p.history.isCeilingClaimed(p));
    assertNull(service.exchangeGachaCeiling(p, 1101));
  }

  @Test void alreadyOwnedCharacterGivesItsEidolonOrMaxRankConversion() {
    for (int rank : List.of(0, 6)) {
      var p = new Resident(); p.history.addCeilingNum(300);
      var avatar = new GameAvatar(GameData.getAvatarExcelMap().get(1003));
      avatar.getData().setRank(rank);
      p.avatars.getAvatars().put(1003, avatar);
      var items = service.exchangeGachaCeiling(p, 1003);
      assertNotNull(items);
      assertEquals(rank == 0 ? 11003 : 252, items.getFirst().getItemId());
      assertEquals(rank == 0 ? 1 : 100, items.getFirst().getCount());
      assertNull(service.exchangeGachaCeiling(p, 1003));
    }
  }

  @Test void simultaneousSelectionsOnlyDeliverOneCharacter() throws Exception {
    var p = new Resident(); p.history.addCeilingNum(300); p.coordinateLegacy = true;
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> { start.await(); return service.exchangeGachaCeiling(p, 1003); });
      var b = pool.submit(() -> { start.await(); return service.exchangeGachaCeiling(p, 1101); });
      start.countDown();
      var first = a.get(8, TimeUnit.SECONDS);
      var second = b.get(8, TimeUnit.SECONDS);
      assertNotEquals(first == null, second == null);
      assertEquals(1, p.deliveries.get());
    }
  }
}
