package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class RogueClubCouponTest {
  final UniverseCombatFragmentsTest combat = new UniverseCombatFragmentsTest();
  GameplayDefinitions.Miracle priorMiracle;
  GameplayDefinitions.MiracleEffect priorEffect;

  @BeforeEach
  void setup() {
    priorMiracle = GameplayDefinitions.miracles.get(12);
    priorEffect = GameplayDefinitions.miracleEffects.get(12);
    combat.setup();
    effect(.75);
  }

  @AfterEach
  void cleanup() {
    combat.cleanup();
    GameplayDefinitions.miracles.remove(12);
    GameplayDefinitions.miracleEffects.remove(12);
    if (priorMiracle != null) GameplayDefinitions.miracles.put(12, priorMiracle);
    if (priorEffect != null) GameplayDefinitions.miracleEffects.put(12, priorEffect);
  }

  void effect(double ratio) {
    GameplayDefinitions.miracles.put(12, new GameplayDefinitions.Miracle(12, 10, 12));
    GameplayDefinitions.miracleEffects.put(
        12,
        new GameplayDefinitions.MiracleEffect(
            12, List.of(new GameplayDefinitions.EffectValue(ratio))));
  }

  GameplayState.RogueRun run() {
    return combat.p.getGameplay().getRogue();
  }

  int base(Battle battle) {
    return (20
            + new Random(Objects.hash(run().getRunId(), battle.getId(), battle.getTimestamp()))
                .nextInt(21))
        * battle.getNpcMonsters().size();
  }

  int enhanced(Battle battle) {
    return base(battle) + base(battle) * 75 / 100;
  }

  @Test
  void acquiredClubCouponIncreasesOnlyTheActualWonEncounterReceipt() {
    assertTrue(RogueMiracles.acquire(combat.p, 12));
    assertEquals(List.of(.75), run().getMiracles().get(12).getEffectParameters());
    assertEquals(22, RogueMiracles.combatFragments(run(), 13));
    assertEquals(0, RogueMiracles.combatFragments(run(), 0));
    assertEquals(50, run().getCoins());
    var battle = combat.battle(combat.monster(8, 1));
    int amount = enhanced(battle);
    combat.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    var grant = run().getCombatFragmentGrants().values().iterator().next();
    assertEquals(amount, grant.getFragments());
    assertTrue(grant.isSettled());
    assertEquals(50 + amount, run().getCoins());
    combat.p.getUniverseManager().onBattleFinish(battle);
    assertEquals(50 + amount, run().getCoins());
    assertEquals(1, run().getCombatFragmentGrants().size());
  }

  @Test
  void aLinkedEncounterAppliesTheRatioOnceToItsCombinedBaseAmount() {
    assertTrue(RogueMiracles.acquire(combat.p, 12));
    var battle = combat.battle(combat.monster(8, 1), combat.monster(9, 2));
    int expected = enhanced(battle);
    combat.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    assertEquals(
        expected, run().getCombatFragmentGrants().values().iterator().next().getFragments());
    assertEquals(50 + expected, run().getCoins());
  }

  @Test
  void preparedEncounterKeepsItsBonusDespiteChangingOwnershipAndResourceRows() {
    // A saved acquisition snapshot lets this regression reach the old settlement path.
    run().getMiracles().put(12, new GameplayState.RogueMiracle(0, List.of(.75)));
    var battle = combat.battle(combat.monster(8, 1));
    int expected = enhanced(battle);
    run().getMiracles().put(12, new GameplayState.RogueMiracle(0, List.of(.1)));
    effect(.1);
    combat.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    assertEquals(
        expected, run().getCombatFragmentGrants().values().iterator().next().getFragments());
    assertEquals(50 + expected, run().getCoins());
  }

  @Test
  void anAcquisitionSnapshotSurvivesRemovalOfCurrentResourceRows() {
    assertTrue(RogueMiracles.acquire(combat.p, 12));
    GameplayDefinitions.miracleEffects.remove(12);
    GameplayDefinitions.miracles.remove(12);
    var battle = combat.battle(combat.monster(8, 1));
    int expected = enhanced(battle);
    combat.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    assertEquals(50 + expected, run().getCoins());
  }

  @Test
  void legacyOrCorruptOwnedRecordsCannotBorrowTheCurrentRatio() {
    for (var record :
        List.of(
            new GameplayState.RogueMiracle(0),
            new GameplayState.RogueMiracle(0, List.of(2.0)),
            new GameplayState.RogueMiracle(1, List.of(.75)),
            new GameplayState.RogueMiracle(0, List.of(.75, 5.0)))) {
      run().getMiracles().put(12, record);
      var battle = combat.battle(combat.monster(8, 1));
      int expected = base(battle);
      combat.finish(battle, BattleEndStatus.BATTLE_END_WIN);
      assertEquals(
          expected, run().getCombatFragmentGrants().values().iterator().next().getFragments());
      assertTrue(run().getMiracles().containsKey(12));
      combat.cleanup();
      combat.setup();
    }
  }

  @Test
  void invalidRatiosAndUnrecognizedExtraArgumentsCannotBeAcquired() {
    for (double invalid : new double[] {0, -1, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
      effect(invalid);
      assertFalse(RogueMiracles.acquire(combat.p, 12));
      assertFalse(run().getMiracles().containsKey(12));
    }
    GameplayDefinitions.miracleEffects.put(
        12,
        new GameplayDefinitions.MiracleEffect(
            12,
            List.of(
                new GameplayDefinitions.EffectValue(.75), new GameplayDefinitions.EffectValue(5))));
    assertFalse(RogueMiracles.acquire(combat.p, 12));
    effect(.75);
    GameplayDefinitions.miracles.put(12, new GameplayDefinitions.Miracle(12, 10, 13));
    assertFalse(RogueMiracles.acquire(combat.p, 12));
    effect(.75);
    GameplayDefinitions.miracleEffects.put(
        12,
        new GameplayDefinitions.MiracleEffect(
            13, List.of(new GameplayDefinitions.EffectValue(.75))));
    assertFalse(RogueMiracles.acquire(combat.p, 12));
    assertEquals(50, run().getCoins());
  }

  @Test
  void quittingLosingAndBossEncountersCannotInventABonus() {
    assertTrue(RogueMiracles.acquire(combat.p, 12));
    combat.finish(combat.battle(combat.monster(8, 1)), BattleEndStatus.BATTLE_END_QUIT);
    assertEquals(50, run().getCoins());
    assertTrue(run().getCombatFragmentGrants().isEmpty());
    GameplayDefinitions.rooms.put(
        UniverseCombatFragmentsTest.ROOM,
        new GameplayDefinitions.Room(
            UniverseCombatFragmentsTest.ROOM, 7, 12, 8, Map.of(8, 11, 9, 11)));
    combat.finish(combat.battle(combat.monster(8, 2)), BattleEndStatus.BATTLE_END_WIN);
    assertEquals(50, run().getCoins());
    assertTrue(run().getCombatFragmentGrants().isEmpty());
    combat.finish(combat.battle(combat.monster(9, 3)), BattleEndStatus.BATTLE_END_LOSE);
    assertEquals(50, run().getCoins());
    assertTrue(run().getCombatFragmentGrants().isEmpty());
  }

  @Test
  void morphiaReloadAndConcurrentRetriesPreserveTheFrozenOverflowReceipt() throws Exception {
    assertTrue(RogueMiracles.acquire(combat.p, 12));
    run().addCoins(Integer.MAX_VALUE - 60);
    var battle = combat.battle(combat.monster(8, 1));
    combat.monster(9, 2);
    int amount = enhanced(battle);
    combat.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    var grant = run().getCombatFragmentGrants().values().iterator().next();
    assertEquals(amount, grant.getFragments());
    assertFalse(grant.isSettled());
    assertEquals(Integer.MAX_VALUE - 10, run().getCoins());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "rogue-club-coupon");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var doc = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(doc, 123456794);
      var state = Player.class.getDeclaredField("gameplay");
      state.setAccessible(true);
      state.set(doc, combat.p.getGameplay());
      store.save(doc);
      state.set(combat.p, store.find(Player.class).first().getGameplay());
      GameplayDefinitions.miracleEffects.remove(12);
      GameplayDefinitions.miracles.remove(12);
      assertEquals(List.of(.75), run().getMiracles().get(12).getEffectParameters());
      assertTrue(run().spend(100));
      try (var pool = Executors.newFixedThreadPool(6)) {
        var tasks = new ArrayList<Callable<Void>>();
        for (int i = 0; i < 6; i++)
          tasks.add(
              () -> {
                combat.p.getUniverseManager().settleResults();
                combat.p.getUniverseManager().onBattleFinish(battle);
                return null;
              });
        for (var result : pool.invokeAll(tasks)) result.get();
      }
      assertEquals(Integer.MAX_VALUE - 110 + amount, run().getCoins());
      assertTrue(run().getCombatFragmentGrants().values().iterator().next().isSettled());
      state.set(doc, combat.p.getGameplay());
      store.save(doc);
      state.set(combat.p, store.find(Player.class).first().getGameplay());
      combat.p.getUniverseManager().settleResults();
      assertEquals(Integer.MAX_VALUE - 110 + amount, run().getCoins());
      assertEquals(
          amount, run().getCombatFragmentGrants().values().iterator().next().getFragments());
    } finally {
      mongo.shutdown();
    }
  }
}
