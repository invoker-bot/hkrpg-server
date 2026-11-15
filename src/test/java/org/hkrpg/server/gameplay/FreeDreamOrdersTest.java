package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.player.Player;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FreeDreamOrdersTest {
  @BeforeAll static void catalog() { assertTrue(FreeDreamCatalog.load(Path.of("resources/4.6"))); }

  static class TestPlayer extends Player {
    boolean fail;
    @Override public void save() { if (fail) throw new IllegalStateException("test storage failure"); }
  }

  @Test void aFreeOrderGrantsOnlyItsConfiguredCoinsAndIsDurablyIdempotent() {
    var player = new TestPlayer();
    var first = FreeDreamOrders.claim(player, "rpgchncoin300tier5", "order-1");
    assertEquals(FreeDreamOrders.Status.APPLIED, first.status());
    assertEquals(330, first.amount());
    assertEquals(330, player.getMcoin());
    assertEquals(FreeDreamOrders.Status.DUPLICATE,
        FreeDreamOrders.claim(player, "rpgchncoin300tier5", "order-1").status());
    assertEquals(330, player.getMcoin());
    assertEquals(FreeDreamOrders.Status.REJECTED,
        FreeDreamOrders.claim(player, "rpgchncoin60tier1", "order-1").status());
    assertEquals(FreeDreamOrders.Status.APPLIED,
        FreeDreamOrders.claim(player, "rpgchncoin60tier1", "order-2").status());
    assertEquals(390, player.getMcoin());
  }

  @Test void unsupportedAndUnidentifiedOrdersDoNotTouchTheWallet() {
    var player = new TestPlayer();
    for (var product : new String[] {"rpgchnmonthcardtier5", "unknown", ""})
      assertEquals(FreeDreamOrders.Status.REJECTED, FreeDreamOrders.claim(player, product, "id").status());
    for (var id : new String[] {null, "", "bad id", "x".repeat(129)})
      assertEquals(FreeDreamOrders.Status.REJECTED,
          FreeDreamOrders.claim(player, "rpgchncoin60tier1", id).status());
    assertEquals(0, player.getMcoin());
  }

  @Test void originalRecommendationPricesAreNotUsedForFreeCoins() throws Exception {
    var resources = java.nio.file.Files.readString(Path.of("src/main/resources/sdk-shop-prices.json"));
    var prices = com.google.gson.JsonParser.parseString(resources).getAsJsonObject();
    assertEquals(600, prices.get("Tier_1").getAsInt());
    assertEquals(3000, prices.get("Tier_5").getAsInt());
    assertEquals(9800, prices.get("Tier_15").getAsInt());
    assertFalse(prices.has(FreeDreamCatalog.FREE_TIER));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "rpgchncoin60tier1,60", "rpgchncoin300tier5,330", "rpgchncoin980tier15,1090",
      "rpgchncoin1980tier30,2240", "rpgchncoin3280tier50,3880", "rpgchncoin6480tier60,8080"})
  void eachFreeProductUsesItsOwnResourceAmount(String product, int expected) {
    var player = new TestPlayer();
    var result = FreeDreamOrders.claim(player, product, "order-per-tier");
    assertEquals(FreeDreamOrders.Status.APPLIED, result.status());
    assertEquals(expected, result.amount());
    assertEquals(expected, player.getMcoin());
  }

  @Test void failedSaveRestoresTheBalanceAndBlocksAnAmbiguousRetryUntilReload() {
    var player = new TestPlayer();
    player.fail = true;
    assertEquals(FreeDreamOrders.Status.REJECTED,
        FreeDreamOrders.claim(player, "rpgchncoin60tier1", "retryable").status());
    assertEquals(0, player.getMcoin());
    player.fail = false;
    assertEquals(FreeDreamOrders.Status.REJECTED,
        FreeDreamOrders.claim(player, "rpgchncoin60tier1", "retryable").status());
    assertEquals(FreeDreamOrders.Status.REJECTED,
        FreeDreamOrders.claim(player, "rpgchncoin300tier5", "different-order").status());
    var reloaded = new TestPlayer(); // Storage fixture models a failure before any write.
    assertEquals(FreeDreamOrders.Status.APPLIED,
        FreeDreamOrders.claim(reloaded, "rpgchncoin60tier1", "retryable").status());
    assertEquals(60, reloaded.getMcoin());
  }

  @Test void simultaneousRetriesOfTheSameOrderGrantOnce() throws Exception {
    var player = new TestPlayer();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var jobs = java.util.stream.IntStream.range(0, 20)
          .<java.util.concurrent.Callable<FreeDreamOrders.Result>>mapToObj(i ->
              () -> FreeDreamOrders.claim(player, "rpgchncoin60tier1", "parallel-order"))
          .toList();
      var results = pool.invokeAll(jobs, 10, TimeUnit.SECONDS);
      assertEquals(1, results.stream().filter(f -> {
        try { return f.get().status() == FreeDreamOrders.Status.APPLIED; }
        catch (Exception e) { throw new AssertionError(e); }
      }).count());
      assertEquals(60, player.getMcoin());
    }
  }
}
