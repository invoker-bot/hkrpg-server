package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.junit.jupiter.api.*;

class RogueAngelDebtTest {
  final UniverseCombatFragmentsTest fixture = new UniverseCombatFragmentsTest();
  GameplayDefinitions.Miracle previous;
  GameplayDefinitions.MiracleEffect previousEffect;

  @BeforeEach
  void setup() {
    previous = GameplayDefinitions.miracles.get(60);
    previousEffect = GameplayDefinitions.miracleEffects.get(60);
    fixture.setup();
    GameplayDefinitions.miracles.put(60, new GameplayDefinitions.Miracle(60, 41, 60));
    GameplayDefinitions.miracleEffects.put(
        60,
        new GameplayDefinitions.MiracleEffect(60, List.of(new GameplayDefinitions.EffectValue(5))));
    // Keep another actual content-group monster alive so successive encounters are valid.
    fixture.monster(9, 777);
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameplayDefinitions.miracles.remove(60);
    GameplayDefinitions.miracleEffects.remove(60);
    if (previous != null) GameplayDefinitions.miracles.put(60, previous);
    if (previousEffect != null) GameplayDefinitions.miracleEffects.put(60, previousEffect);
  }

  GameplayState.RogueRun run() {
    return fixture.p.getGameplay().getRogue();
  }

  void snapshot() {
    run().getMiracles().put(60, new GameplayState.RogueMiracle(5, List.of(5.0)));
  }

  Battle win(int instance) {
    var battle = fixture.battle(fixture.monster(8, instance));
    fixture.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    return battle;
  }

  @Test
  void exactResourceAcquisitionHasFiveUsesAndCannotBeRepeated() {
    assertTrue(RogueMiracles.acquire(fixture.p, 60));
    assertFalse(RogueMiracles.acquire(fixture.p, 60));
    assertEquals(5, run().getMiracles().get(60).getTotalCount());
    assertEquals(0, run().getMiracles().get(60).getUseCount());
    assertEquals(50, run().getCoins());
  }

  @Test
  void aBoundVictoryWithholdsFragmentsAndConsumesOnceEvenOnRepeatedSettlement() {
    snapshot();
    var battle = win(1);
    assertEquals(50, run().getCoins());
    assertEquals(1, run().getMiracles().get(60).getUseCount());
    assertTrue(run().getCombatFragmentGrants().isEmpty());
    fixture.p.getUniverseManager().onBattleFinish(battle);
    assertEquals(50, run().getCoins());
    assertEquals(1, run().getMiracles().get(60).getUseCount());
  }

  @Test
  void fifthDistinctVictoryBreaksTheCurioAndDoublesTheActualWalletOnlyOnce() {
    snapshot();
    for (int i = 1; i <= 4; i++) {
      win(i);
      assertEquals(50, run().getCoins());
      assertEquals(i, run().getMiracles().get(60).getUseCount());
      assertFalse(run().getMiracles().get(60).broken());
    }
    var fifth = win(5);
    assertEquals(5, run().getMiracles().get(60).getUseCount());
    assertTrue(run().getMiracles().get(60).broken());
    assertEquals(100, run().getCoins());
    fixture.p.getUniverseManager().onBattleFinish(fifth);
    assertEquals(100, run().getCoins());
    win(6);
    assertTrue(run().getCoins() >= 120 && run().getCoins() <= 140);
    assertEquals(5, run().getMiracles().get(60).getUseCount());
  }

  @Test
  void preparedEncounterKeepsTheWithholdingRuleAfterCurrentResourcesDisappear() {
    snapshot();
    var battle = fixture.battle(fixture.monster(8, 1));
    GameplayDefinitions.miracles.remove(60);
    GameplayDefinitions.miracleEffects.remove(60);
    fixture.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    assertEquals(50, run().getCoins());
    assertEquals(1, run().getMiracles().get(60).getUseCount());
  }

  @Test
  void aLaterAcquisitionCannotWithholdOrConsumeTheAlreadyPreparedEncounter() {
    var battle = fixture.battle(fixture.monster(8, 1));
    snapshot();
    fixture.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    int balance = run().getCoins();
    assertTrue(balance >= 70 && balance <= 90);
    assertEquals(0, run().getMiracles().get(60).getUseCount());
    win(2);
    assertEquals(balance, run().getCoins());
    assertEquals(1, run().getMiracles().get(60).getUseCount());
  }

  @Test
  void quittingLosingAndAnObsoleteBoundBattleCannotConsumeTheCurio() {
    snapshot();
    var old = fixture.battle(fixture.monster(8, 1));
    fixture.finish(old, BattleEndStatus.BATTLE_END_QUIT);
    assertEquals(0, run().getMiracles().get(60).getUseCount());
    var current = fixture.battle(fixture.monster(8, 2));
    old.setResult(BattleEndStatus.BATTLE_END_WIN);
    fixture.p.getUniverseManager().onBattleFinish(old);
    assertEquals(0, run().getMiracles().get(60).getUseCount());
    fixture.finish(current, BattleEndStatus.BATTLE_END_LOSE);
    assertEquals(0, run().getMiracles().get(60).getUseCount());
    assertEquals(50, run().getCoins());
    assertTrue(run().getMiracleFragmentGrants().isEmpty());
  }

  @Test
  void clubCouponDoesNotPayFragmentsWhileTheFrozenDebtRuleWithholdsThem() {
    snapshot();
    run().getMiracles().put(12, new GameplayState.RogueMiracle(0, List.of(.75)));
    win(1);
    assertEquals(50, run().getCoins());
    assertEquals(1, run().getMiracles().get(60).getUseCount());
    assertTrue(run().getCombatFragmentGrants().isEmpty());
  }

  @Test
  void unknownResourceAssociationsAndOwnedLifetimesCannotSuppressOrdinaryFragments() {
    GameplayDefinitions.miracles.put(60, new GameplayDefinitions.Miracle(60, 50, 60));
    assertFalse(RogueMiracles.acquire(fixture.p, 60));
    GameplayDefinitions.miracles.put(60, new GameplayDefinitions.Miracle(60, 41, 60));
    GameplayDefinitions.miracleEffects.put(
        60,
        new GameplayDefinitions.MiracleEffect(60, List.of(new GameplayDefinitions.EffectValue(4))));
    assertFalse(RogueMiracles.acquire(fixture.p, 60));
    run().getMiracles().put(60, new GameplayState.RogueMiracle(4, List.of(5.0)));
    win(1);
    assertTrue(run().getCoins() >= 70 && run().getCoins() <= 90);
    assertEquals(0, run().getMiracles().get(60).getUseCount());
  }

  @Test
  void aZeroWalletStillBreaksOnTheFifthWinWithoutCreatingAnInvalidZeroGrant() {
    snapshot();
    for (int i = 1; i <= 4; i++) win(i);
    assertTrue(run().spend(50));
    var fifth = win(5);
    assertEquals(0, run().getCoins());
    assertTrue(run().getMiracles().get(60).broken());
    assertTrue(run().getMiracleFragmentGrants().isEmpty());
    fixture.p.getUniverseManager().onBattleFinish(fifth);
    assertEquals(0, run().getCoins());
    win(6);
    assertTrue(run().getCoins() >= 20 && run().getCoins() <= 40);
  }

  @Test
  void aReplacementRecordCannotBorrowThePreparedAcquisitionsCounter() {
    snapshot();
    var battle = fixture.battle(fixture.monster(8, 1));
    String original = run().getMiracles().get(60).getAcquisitionId();
    run().getMiracles().remove(60);
    snapshot();
    assertNotEquals(original, run().getMiracles().get(60).getAcquisitionId());
    fixture.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    assertEquals(0, run().getMiracles().get(60).getUseCount());
    assertEquals(50, run().getCoins());
  }

  @Test
  void concurrentFifthVictoryDoublesAndReservesOneGrantOnce() throws Exception {
    snapshot();
    for (int i = 1; i <= 4; i++) win(i);
    var battle = fixture.battle(fixture.monster(8, 5));
    battle.setResult(BattleEndStatus.BATTLE_END_WIN);
    for (var monster : battle.getNpcMonsters()) {
      fixture.p.getScene().getEntities().remove(monster.getEntityId());
      monster.setEntityId(0);
    }
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
      for (int i = 0; i < 6; i++)
        tasks.add(
            () -> {
              fixture.p.getUniverseManager().onBattleFinish(battle);
              return null;
            });
      for (var result : workers.invokeAll(tasks)) result.get();
    }
    assertEquals(100, run().getCoins());
    assertEquals(5, run().getMiracles().get(60).getUseCount());
    assertEquals(1, run().getMiracleFragmentGrants().size());
    assertTrue(run().getMiracleFragmentGrants().values().iterator().next().isSettled());
  }

  @Test
  void actualMorphiaReloadKeepsTheFrozenOverflowGrantWithoutRecomputingItsAmount()
      throws Exception {
    snapshot();
    for (int i = 1; i <= 4; i++) win(i);
    run().addCoins(Integer.MAX_VALUE - 50);
    var fifth = win(5);
    assertEquals(Integer.MAX_VALUE, run().getCoins());
    var plan = run().getMiracleFragmentGrants().values().iterator().next();
    assertEquals(Integer.MAX_VALUE, plan.getFragments());
    assertFalse(plan.isSettled());
    assertTrue(run().getMiracles().get(60).broken());
    fixture.p.getUniverseManager().onBattleFinish(fifth);
    assertEquals(1, run().getMiracleFragmentGrants().size());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "angelDebt");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456762);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, fixture.p.getGameplay());
      store.save(document);
      gameplay.set(
          fixture.p, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      fixture.p.getUniverseManager().settleResults();
      assertEquals(Integer.MAX_VALUE, run().getCoins());
      assertFalse(run().getMiracleFragmentGrants().values().iterator().next().isSettled());
      assertTrue(run().spend(Integer.MAX_VALUE));
      fixture.p.getUniverseManager().settleResults();
      assertEquals(Integer.MAX_VALUE, run().getCoins());
      assertTrue(run().getMiracleFragmentGrants().values().iterator().next().isSettled());
      gameplay.set(document, fixture.p.getGameplay());
      store.save(document);
      gameplay.set(
          fixture.p, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      fixture.p.getUniverseManager().settleResults();
      assertEquals(Integer.MAX_VALUE, run().getCoins());
      assertEquals(5, run().getMiracles().get(60).getUseCount());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void startingAnotherExplorationCannotOverwriteAnUnsettledMiracleGrant() {
    snapshot();
    for (int i = 1; i <= 4; i++) win(i);
    run().addCoins(Integer.MAX_VALUE - 50);
    win(5);
    var original = run();
    original.finish(GameplayState.RogueRun.Phase.ABANDONED);
    original.setInside(false);
    fixture.p.entry = 11;
    var next = new UniverseLifecycleTest();
    next.definitions();
    try {
      assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, fixture.p.getUniverseManager().start(19996));
      assertSame(original, run());
      assertEquals(1, run().getMiracleFragmentGrants().size());
      assertFalse(run().getMiracleFragmentGrants().values().iterator().next().isSettled());
    } finally {
      next.reset();
    }
  }
}
