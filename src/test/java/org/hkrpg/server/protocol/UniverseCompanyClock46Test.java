package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseCompanyClock46Test {
  final UniverseEnhance46Test fixture = new UniverseEnhance46Test();
  GameplayDefinitions.Miracle previous;
  GameplayDefinitions.MiracleEffect previousEffect;

  @BeforeEach
  void setup() {
    previous = GameplayDefinitions.miracles.get(70);
    previousEffect = GameplayDefinitions.miracleEffects.get(70);
    fixture.setup();
    GameplayDefinitions.miracles.put(70, new GameplayDefinitions.Miracle(70, 51, 70));
    GameplayDefinitions.miracleEffects.put(
        70,
        new GameplayDefinitions.MiracleEffect(
            70, List.of(new GameplayDefinitions.EffectValue(.25))));
    fixture.begin();
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameplayDefinitions.miracles.remove(70);
    GameplayDefinitions.miracleEffects.remove(70);
    if (previous != null) GameplayDefinitions.miracles.put(70, previous);
    if (previousEffect != null) GameplayDefinitions.miracleEffects.put(70, previousEffect);
  }

  GameplayState.RogueRun run() {
    return fixture.s.explorer.getGameplay().getRogue();
  }

  void clockSnapshot() {
    run().getMiracles().put(70, new GameplayState.RogueMiracle(0, List.of(.25)));
  }

  @Test
  void actualCompanyCuckooClockCanBeAcquiredOnceWithItsExactResourceRatio() {
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 70));
    assertEquals(List.of(.25), run().getMiracles().get(70).getEffectParameters());
    assertEquals(0, run().getMiracles().get(70).getTotalCount());
    assertEquals(300, run().getCoins());
    assertFalse(RogueMiracles.acquire(fixture.s.explorer, 70));
  }

  @Test
  void nativeEnhancementQuoteAndDebitIncludeTheSavedClockSurcharge() throws Exception {
    clockSnapshot();
    assertEquals(125, fixture.price(fixture.query()));
    assertEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    assertEquals(175, run().getCoins());
    assertEquals(25, run().getMiracles().get(70).getExtraFragmentsSpent());
    assertEquals(2, run().getBlessings().get(UniverseEnhance46Test.BUFF));
    assertNotEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    assertEquals(175, run().getCoins());
    assertEquals(25, run().getMiracles().get(70).getExtraFragmentsSpent());
  }

  @Test
  void frozenAcquisitionDoesNotBorrowAChangedOrRemovedCurrentEffect() throws Exception {
    clockSnapshot();
    GameplayDefinitions.miracles.remove(70);
    GameplayDefinitions.miracleEffects.remove(70);
    assertEquals(125, fixture.price(fixture.query()));
    assertEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    assertEquals(175, run().getCoins());
  }

  @Test
  void aPreviouslyAdvertisedQuoteKeepsItsOriginalPriceAfterClockAcquisition() throws Exception {
    assertEquals(100, fixture.price(fixture.query()));
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 70));
    assertEquals(100, fixture.price(fixture.query()));
    assertEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    assertEquals(200, run().getCoins());
    assertEquals(0, run().getMiracles().get(70).getExtraFragmentsSpent());
  }

  @Test
  void savedDiscountIsAppliedBeforeTheProjectIntegerSurchargeRule() throws Exception {
    clockSnapshot();
    run().getMiracles().put(13, new GameplayState.RogueMiracle(0, List.of(.3)));
    assertEquals(87, fixture.price(fixture.query()));
    assertEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    assertEquals(213, run().getCoins());
    assertEquals(17, run().getMiracles().get(70).getExtraFragmentsSpent());
  }

  @Test
  void rareBlessingUsesTheExplicitIntegerRoundingRuleAndCountsOnlyTheExtra() throws Exception {
    clockSnapshot();
    fixture.buff(1, "Rare");
    assertEquals(162, fixture.price(fixture.query()));
    assertEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    assertEquals(138, run().getCoins());
    assertEquals(32, run().getMiracles().get(70).getExtraFragmentsSpent());
  }

  @Test
  void aReplacementAcquisitionCannotReceiveAnEarlierQuotesCounter() throws Exception {
    clockSnapshot();
    String original = run().getMiracles().get(70).getAcquisitionId();
    assertEquals(125, fixture.price(fixture.query()));
    run().getMiracles().remove(70);
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 70));
    assertNotEquals(original, run().getMiracles().get(70).getAcquisitionId());
    assertEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    assertEquals(175, run().getCoins());
    assertEquals(0, run().getMiracles().get(70).getExtraFragmentsSpent());
  }

  @Test
  void invalidDisplayAssociationAndUnknownRatioCannotBeAcquired() {
    GameplayDefinitions.miracles.put(70, new GameplayDefinitions.Miracle(70, 55, 70));
    assertFalse(RogueMiracles.acquire(fixture.s.explorer, 70));
    GameplayDefinitions.miracles.put(70, new GameplayDefinitions.Miracle(70, 51, 70));
    for (double value : new double[] {0, -.25, .5, Double.NaN, Double.POSITIVE_INFINITY}) {
      GameplayDefinitions.miracleEffects.put(
          70,
          new GameplayDefinitions.MiracleEffect(
              70, List.of(new GameplayDefinitions.EffectValue(value))));
      assertFalse(RogueMiracles.acquire(fixture.s.explorer, 70));
    }
    assertEquals(300, run().getCoins());
  }

  @Test
  void counterOverflowCannotDebitOrUpgradeTheQuotedBlessing() throws Exception {
    clockSnapshot();
    var counter = GameplayState.RogueMiracle.class.getDeclaredField("extraFragmentsSpent");
    counter.setAccessible(true);
    counter.setLong(run().getMiracles().get(70), Long.MAX_VALUE);
    assertEquals(125, fixture.price(fixture.query()));
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    assertEquals(300, run().getCoins());
    assertEquals(1, run().getBlessings().get(UniverseEnhance46Test.BUFF));
    assertEquals(Long.MAX_VALUE, run().getMiracles().get(70).getExtraFragmentsSpent());
  }

  @Test
  void concurrentPurchaseUsesOneQuotedSurchargeAndOneCounterIncrement() throws Exception {
    clockSnapshot();
    assertEquals(125, fixture.price(fixture.query()));
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 6; i++)
        calls.add(() -> RogueEnhancement.enhance(fixture.s.explorer, UniverseEnhance46Test.BUFF));
      long success = 0;
      for (var result : workers.invokeAll(calls)) if (result.get() == Retcode.SUCC) success++;
      assertEquals(1, success);
    }
    assertEquals(175, run().getCoins());
    assertEquals(25, run().getMiracles().get(70).getExtraFragmentsSpent());
  }

  @Test
  void nativeRerollFreezesTheSurchargeAndIncrementsTheCounterOnlyOnSuccess() throws Exception {
    fixture.cleanup();
    var roller = new UniverseReroll46Test();
    roller.setup();
    try {
      assertEquals(0, Gameplay46.number(roller.s.start(List.of(199961), 0), "retcode"));
      var run = roller.s.explorer.getGameplay().getRogue();
      run.getMiracles().put(70, new GameplayState.RogueMiracle(0, List.of(.25)));
      run.clear();
      assertEquals(3, roller.s.explorer.getUniverseManager().offerBlessings().size());
      assertEquals(List.of(37), run.getOfferRerollCosts());
      GameplayDefinitions.miracleEffects.remove(70);
      assertEquals(0, Gameplay46.number(roller.roll(null), "retcode"));
      assertEquals(63, run.getCoins());
      assertEquals(7, run.getMiracles().get(70).getExtraFragmentsSpent());
      assertNotEquals(0, Gameplay46.number(roller.roll(null), "retcode"));
      assertEquals(63, run.getCoins());
      assertEquals(7, run.getMiracles().get(70).getExtraFragmentsSpent());
    } finally {
      roller.cleanup();
      GameData.getMazeBuffExcelMap()
          .values()
          .removeIf(b -> b.getID() >= 19997301 && b.getID() <= 19997310);
    }
  }

  @Test
  void aCorruptCounterCannotConsumeItsAlreadyAdvertisedClockQuote() throws Exception {
    clockSnapshot();
    assertEquals(125, fixture.price(fixture.query()));
    var counter = GameplayState.RogueMiracle.class.getDeclaredField("extraFragmentsSpent");
    counter.setAccessible(true);
    counter.setLong(run().getMiracles().get(70), -1);
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
    assertEquals(300, run().getCoins());
    assertEquals(1, run().getBlessings().get(UniverseEnhance46Test.BUFF));
  }

  @Test
  void morphiaReloadKeepsTheQuotedAcquisitionAndCounterWithTheActualWallet() throws Exception {
    clockSnapshot();
    String token = run().getMiracles().get(70).getAcquisitionId();
    assertEquals(125, fixture.price(fixture.query()));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "companyClock");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456765);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      gameplay.set(
          fixture.s.explorer,
          store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      GameplayDefinitions.miracleEffects.remove(70);
      assertEquals(token, run().getMiracles().get(70).getAcquisitionId());
      assertEquals(125, fixture.price(fixture.query()));
      assertEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
      assertEquals(175, run().getCoins());
      assertEquals(25, run().getMiracles().get(70).getExtraFragmentsSpent());
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      gameplay.set(
          fixture.s.explorer,
          store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      assertEquals(175, run().getCoins());
      assertEquals(25, run().getMiracles().get(70).getExtraFragmentsSpent());
      assertNotEquals(0, fixture.enhance(fixture.request(13, UniverseEnhance46Test.BUFF)));
      assertEquals(25, run().getMiracles().get(70).getExtraFragmentsSpent());
      assertFalse(run().getMiracles().containsKey(9));
    } finally {
      mongo.shutdownNow();
    }
  }
}
