package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Merchant scenes are isolated fixtures; clock rows are the pinned original 4.6 rows. */
class UniverseClockExpenditure46Test {
  @TempDir Path root;
  final UniverseMiracleShopTest merchant = new UniverseMiracleShopTest();
  final Map<Integer, GameplayDefinitions.Miracle> oldRows = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects = new HashMap<>();
  static final int ENTITY = UniverseEnhance46Test.ENTITY, BUFF = UniverseEnhance46Test.BUFF;

  @BeforeEach
  void setup() throws Exception {
    merchant.root = root;
    merchant.setup();
    clockRows(oldRows, oldEffects);
  }

  @AfterEach
  void cleanup() throws Exception {
    restoreClockRows(oldRows, oldEffects);
    merchant.cleanup();
  }

  static void clockRows(
      Map<Integer, GameplayDefinitions.Miracle> oldRows,
      Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects)
      throws Exception {
    for (int id : List.of(9, 70)) {
      oldRows.put(id, GameplayDefinitions.miracles.get(id));
      oldEffects.put(id, GameplayDefinitions.miracleEffects.get(id));
    }
    var gson = new Gson();
    try (var in =
        new InputStreamReader(
            UniverseClockExpenditure46Test.class.getResourceAsStream(
                "/fixtures/rogue-broken-clock-4.6.json"),
            StandardCharsets.UTF_8)) {
      var rows = JsonParser.parseReader(in).getAsJsonObject().getAsJsonObject("rows");
      for (var row : rows.getAsJsonArray("RogueMiracle")) {
        var value = gson.fromJson(row, GameplayDefinitions.Miracle.class);
        GameplayDefinitions.miracles.put(value.MiracleID(), value);
      }
      for (var row : rows.getAsJsonArray("RogueMiracleEffect")) {
        var value = gson.fromJson(row, GameplayDefinitions.MiracleEffect.class);
        GameplayDefinitions.miracleEffects.put(value.MiracleEffectID(), value);
      }
    }
  }

  static void restoreClockRows(
      Map<Integer, GameplayDefinitions.Miracle> rows,
      Map<Integer, GameplayDefinitions.MiracleEffect> effects) {
    for (int id : List.of(9, 70)) {
      GameplayDefinitions.miracles.remove(id);
      GameplayDefinitions.miracleEffects.remove(id);
      if (rows.get(id) != null) GameplayDefinitions.miracles.put(id, rows.get(id));
      if (effects.get(id) != null) GameplayDefinitions.miracleEffects.put(id, effects.get(id));
    }
  }

  Player player() {
    return merchant.p();
  }

  GameplayState.RogueRun run() {
    return merchant.run();
  }

  GameplayState.RogueMiracle clock() {
    return run().getMiracles().get(70);
  }

  void acquireClock() {
    assertTrue(RogueMiracles.acquire(player(), 70));
  }

  void counter(long n) throws Exception {
    var f = GameplayState.RogueMiracle.class.getDeclaredField("extraFragmentsSpent");
    f.setAccessible(true);
    f.setLong(clock(), n);
  }

  void blessingMerchant() throws Exception {
    merchant.merchant.graph("BuffShop");
    merchant.merchant.merchant(UniverseBuffShopTest.GRAPH);
  }

  List<RogueBuffShop.Quote> blessingQuotes() {
    return RogueBuffShop.quotes(player(), ENTITY);
  }

  Retcode buyBlessing() {
    return RogueBuffShop.buy(player(), ENTITY, List.of(BUFF));
  }

  @Test
  void blessingQuoteIncludesClockAndPurchaseCountsItsExtraOnce() throws Exception {
    acquireClock();
    blessingMerchant();
    assertEquals(125, blessingQuotes().getFirst().cost());
    assertEquals(Retcode.SUCC, buyBlessing());
    assertEquals(175, run().getCoins());
    assertEquals(25, clock().getExtraFragmentsSpent());
    assertNotEquals(Retcode.SUCC, buyBlessing());
    assertEquals(175, run().getCoins());
    assertEquals(25, clock().getExtraFragmentsSpent());
  }

  @Test
  void miracleQuoteIncludesClockAndPurchaseCountsItsExtraOnce() {
    acquireClock();
    assertEquals(250, merchant.quotes().getFirst().cost());
    assertEquals(Retcode.SUCC, merchant.buy(4));
    assertEquals(50, run().getCoins());
    assertEquals(50, clock().getExtraFragmentsSpent());
    assertTrue(run().getMiracles().containsKey(4));
    assertNotEquals(Retcode.SUCC, merchant.buy(4));
    assertEquals(50, run().getCoins());
  }

  @Test
  void insufficientSurchargedBalanceDoesNotAcquireOrConsumeMiracleStock() {
    acquireClock();
    merchant.quotes();
    run().spend(60);
    assertEquals(Retcode.ITEM_NOT_ENOUGH, merchant.buy(4));
    assertEquals(240, run().getCoins());
    assertEquals(0, clock().getExtraFragmentsSpent());
    assertFalse(run().getMiracles().containsKey(4));
    assertFalse(merchant.quotes().getFirst().purchased());
  }

  @Test
  void shopCanBreakClockAndSettleRewardInItsSinglePurchaseSave() throws Exception {
    acquireClock();
    counter(475);
    merchant.quotes();
    int saves = merchant.p().saves;
    String acquisition = clock().getAcquisitionId();
    assertEquals(Retcode.SUCC, merchant.buy(4));
    assertFalse(run().getMiracles().containsKey(70));
    assertEquals(List.of(499.0, 999.0), run().getMiracles().get(9).getEffectParameters());
    assertEquals(1049, run().getCoins());
    assertEquals(saves + 1, merchant.p().saves);
    assertTrue(run().getMiracleFragmentGrants().get("clock:70:" + acquisition).isSettled());
    assertNotEquals(Retcode.SUCC, merchant.buy(4));
    assertEquals(1049, run().getCoins());
  }

  @Test
  void clockCounterOverflowDoesNotApplyPurchasedMiracleEffects() throws Exception {
    acquireClock();
    counter(Long.MAX_VALUE);
    merchant.quotes();
    int saves = merchant.p().saves;
    assertEquals(Retcode.PLAYER_DATA_ERROR, merchant.buy(4));
    assertEquals(300, run().getCoins());
    assertFalse(run().getMiracles().containsKey(4));
    assertFalse(merchant.quotes().getFirst().purchased());
    assertEquals(saves, merchant.p().saves);
  }

  @Test
  void frozenMerchantQuoteDoesNotTaxLaterClockAcquisition() throws Exception {
    blessingMerchant();
    assertEquals(100, blessingQuotes().getFirst().cost());
    acquireClock();
    assertEquals(100, blessingQuotes().getFirst().cost());
    assertEquals(Retcode.SUCC, buyBlessing());
    assertEquals(200, run().getCoins());
    assertEquals(0, clock().getExtraFragmentsSpent());
  }

  @Test
  void priorQuoteCannotAdvanceReplacementClockCounter() throws Exception {
    acquireClock();
    blessingMerchant();
    assertEquals(125, blessingQuotes().getFirst().cost());
    run().getMiracles().remove(70);
    acquireClock();
    assertEquals(Retcode.SUCC, buyBlessing());
    assertEquals(175, run().getCoins());
    assertEquals(0, clock().getExtraFragmentsSpent());
  }

  @Test
  void clockBoughtFromExistingStockDoesNotTaxItsOwnPurchase() {
    // No clock is owned when the original 200-price stock is frozen.
    var quote = merchant.quotes().stream().filter(q -> q.id() == 70).findFirst().orElseThrow();
    assertEquals(200, quote.cost());
    assertEquals(Retcode.SUCC, merchant.buy(70));
    assertEquals(100, run().getCoins());
    assertEquals(0, clock().getExtraFragmentsSpent());
  }

  @Test
  void morphiaRestoresFrozenMerchantSurchargeAndSettledConversionAcrossTwoSaves() throws Exception {
    acquireClock();
    counter(490);
    blessingMerchant();
    assertEquals(125, blessingQuotes().getFirst().cost());
    String acquisition = clock().getAcquisitionId();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "clockMerchant");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456765);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, player().getGameplay());
      store.save(document);
      gameplay.set(player(), store.find(Player.class).first().getGameplay());
      assertEquals(acquisition, clock().getAcquisitionId());
      GameplayDefinitions.miracles.remove(9);
      GameplayDefinitions.miracleEffects.remove(9);
      GameplayDefinitions.miracles.remove(70);
      GameplayDefinitions.miracleEffects.remove(70);
      assertEquals(125, blessingQuotes().getFirst().cost());
      assertEquals(Retcode.SUCC, buyBlessing());
      assertEquals(1174, run().getCoins());
      assertTrue(run().getMiracleFragmentGrants().get("clock:70:" + acquisition).isSettled());
      gameplay.set(document, player().getGameplay());
      store.save(document);
      gameplay.set(player(), store.find(Player.class).first().getGameplay());
      assertTrue(blessingQuotes().getFirst().purchased());
      assertNotEquals(Retcode.SUCC, buyBlessing());
      assertEquals(1174, run().getCoins());
      assertFalse(run().getMiracles().containsKey(70));
      assertNotNull(run().getMiracles().get(9));
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void batchChargesAllFrozenPricesAndBreaksOnceWithPerItemRounding() throws Exception {
    acquireClock();
    counter(400);
    blessingMerchant();
    for (int i = 1; i <= 2; i++) {
      int id = BUFF + i;
      GameplayDefinitions.blessings.add(
          new GameplayDefinitions.Blessing(
              id, 1, 100, i == 1 ? "Rare" : "Legendary", 1, List.of()));
      emu.lunarcore.data.GameData.getMazeBuffExcelMap()
          .add(
              new Gson()
                  .fromJson(
                      "{\"ID\":" + id + ",\"Lv\":1}",
                      emu.lunarcore.data.excel.MazeBuffExcel.class));
    }
    assertEquals(
        List.of(125, 187, 312), blessingQuotes().stream().map(RogueBuffShop.Quote::cost).toList());
    run().addCoins(700);
    int saves = merchant.p().saves;
    assertEquals(
        Retcode.SUCC, RogueBuffShop.buy(player(), ENTITY, List.of(BUFF, BUFF + 1, BUFF + 2)));
    assertEquals(1375, run().getCoins());
    assertEquals(saves + 1, merchant.p().saves);
    assertEquals(3, run().getBlessings().size());
    assertFalse(run().getMiracles().containsKey(70));
    assertNotNull(run().getMiracles().get(9));
    assertEquals(1, run().getMiracleFragmentGrants().size());
  }

  @Test
  void legacyUnmodifiedShopQuoteIsNotRetroactivelySurcharged() throws Exception {
    blessingMerchant();
    blessingQuotes();
    acquireClock();
    var gson = new Gson();
    var json = JsonParser.parseString(gson.toJson(player().getGameplay())).getAsJsonObject();
    for (var plan : json.getAsJsonObject("rogue").getAsJsonObject("blessingShops").asMap().values())
      for (var offer : plan.getAsJsonObject().getAsJsonObject("offers").asMap().values())
        offer.getAsJsonObject().remove("fragmentCost");
    var gameplay = Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(player(), gson.fromJson(json, GameplayState.class));
    assertEquals(100, blessingQuotes().getFirst().cost());
    assertEquals(Retcode.SUCC, buyBlessing());
    assertEquals(200, run().getCoins());
    assertEquals(0, clock().getExtraFragmentsSpent());
  }

  @Test
  void corruptAdvertisedPaymentCannotDebitOrConsumeBlessingStock() throws Exception {
    acquireClock();
    blessingMerchant();
    blessingQuotes();
    var plan = run().getBlessingShops().values().iterator().next();
    var fragment = GameplayState.RogueShopOffer.class.getDeclaredField("fragmentCost");
    fragment.setAccessible(true);
    fragment.set(
        plan.getOffers().get(BUFF),
        new GameplayState.RogueFragmentCost(126, 25, clock().getAcquisitionId()));
    assertEquals(Retcode.REQ_PARA_INVALID, buyBlessing());
    assertEquals(300, run().getCoins());
    assertTrue(run().getBlessings().isEmpty());
    assertFalse(plan.getOffers().get(BUFF).isPurchased());
  }
}
