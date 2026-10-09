package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class RogueMiracleSnapshotTest {
  final UniverseLifecycleTest.Explorer player = new UniverseLifecycleTest.Explorer();
  final emu.lunarcore.game.player.lineup.PlayerLineup normalTeam = player.getCurrentLineup();
  GameplayState.RogueRun run;
  final Map<Integer, GameplayDefinitions.Miracle> oldMiracles = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects = new HashMap<>();

  @BeforeEach
  void setup() {
    for (int id : List.of(4, 6, 13, 15, 17, 20)) {
      if (GameplayDefinitions.miracles.containsKey(id))
        oldMiracles.put(id, GameplayDefinitions.miracles.get(id));
      if (GameplayDefinitions.miracleEffects.containsKey(id))
        oldEffects.put(id, GameplayDefinitions.miracleEffects.get(id));
    }
    run = new GameplayState.RogueRun(100, 1, Map.of());
    run.setupTeam(player, List.of(199961));
    player.getGameplay().setRogue(run);
  }

  @AfterEach
  void cleanup() {
    for (int id : List.of(4, 6, 13, 15, 17, 20)) {
      GameplayDefinitions.miracles.remove(id);
      GameplayDefinitions.miracleEffects.remove(id);
    }
    GameplayDefinitions.miracles.putAll(oldMiracles);
    GameplayDefinitions.miracleEffects.putAll(oldEffects);
  }

  void effect(int id, double value) {
    GameplayDefinitions.miracles.put(id, new GameplayDefinitions.Miracle(id, id, id));
    GameplayDefinitions.miracleEffects.put(
        id,
        new GameplayDefinitions.MiracleEffect(
            id, List.of(new GameplayDefinitions.EffectValue(value))));
  }

  @Test
  void acquiredDiscountCannotBeRepricedByLaterResourceRows() {
    effect(13, .3);
    assertTrue(RogueMiracles.acquire(player, 13));
    effect(13, .9);
    assertEquals(70, RogueMiracles.enhancementCost(run, 100));
    assertEquals(91, RogueMiracles.enhancementCost(run, 130));
  }

  @Test
  void acquiredInterestSurvivesRemovalOfCurrentResources() {
    effect(15, .06);
    assertTrue(RogueMiracles.acquire(player, 15));
    GameplayDefinitions.miracleEffects.remove(15);
    GameplayDefinitions.miracles.remove(15);
    RogueMiracles.onRoomEntered(player);
    assertEquals(106, run.getCoins());
    RogueMiracles.onRoomEntered(player);
    assertEquals(112, run.getCoins());
  }

  @Test
  void acquiredHealingUsesItsOriginalRatioAndKeepsNormalLineupUntouched() {
    effect(6, .3);
    int normalHp = player.avatar.getCurrentHp(normalTeam);
    assertTrue(RogueMiracles.acquire(player, 6));
    effect(6, .9);
    var vital = run.getVitals().get(199961);
    vital.setHp(1000);
    RogueMiracles.onBattleWon(player);
    assertEquals(4000, vital.getHp());
    assertEquals(normalHp, player.avatar.getCurrentHp(normalTeam));
  }

  @Test
  void oldOwnedRecordWithoutAnAcquisitionSnapshotCannotBorrowCurrentEffects() {
    effect(13, .3);
    effect(15, .06);
    effect(6, .3);
    run.getMiracles().put(13, new GameplayState.RogueMiracle(0));
    run.getMiracles().put(15, new GameplayState.RogueMiracle(0));
    run.getMiracles().put(6, new GameplayState.RogueMiracle(0));
    run.getVitals().get(199961).setHp(1000);
    assertEquals(100, RogueMiracles.enhancementCost(run, 100));
    RogueMiracles.onRoomEntered(player);
    RogueMiracles.onBattleWon(player);
    assertEquals(100, run.getCoins());
    assertEquals(1000, run.getVitals().get(199961).getHp());
    assertEquals(3, run.getMiracles().size());
  }

  @Test
  void actualMorphiaReloadPreservesTheCompleteParameterVectorWithoutCurrentResourceRows()
      throws Exception {
    effect(13, .3);
    effect(15, .06);
    effect(6, .3);
    GameplayDefinitions.miracleEffects.put(
        6,
        new GameplayDefinitions.MiracleEffect(
            6,
            List.of(
                new GameplayDefinitions.EffectValue(.3), new GameplayDefinitions.EffectValue(5))));
    for (int id : List.of(13, 15, 6)) assertTrue(RogueMiracles.acquire(player, id));
    run.getVitals().get(199961).setHp(1000);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "miracle-acquisition-snapshot");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456799);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, player.getGameplay());
      store.save(document);
      run = store.find(emu.lunarcore.game.player.Player.class).first().getGameplay().getRogue();
      player.getGameplay().setRogue(run);
      for (int id : List.of(13, 15, 6)) {
        GameplayDefinitions.miracles.remove(id);
        GameplayDefinitions.miracleEffects.remove(id);
      }
      assertEquals(List.of(.3, 5.0), run.getMiracles().get(6).getEffectParameters());
      assertEquals(70, RogueMiracles.enhancementCost(run, 100));
      RogueMiracles.onRoomEntered(player);
      RogueMiracles.onBattleWon(player);
      assertEquals(106, run.getCoins());
      assertEquals(4000, run.getVitals().get(199961).getHp());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void acquisitionCopiesTheSourceVectorAndDoesNotExposeMutableParameters() {
    effect(13, .3);
    var parameters = new ArrayList<GameplayDefinitions.EffectValue>();
    parameters.add(new GameplayDefinitions.EffectValue(.3));
    GameplayDefinitions.miracleEffects.put(
        13, new GameplayDefinitions.MiracleEffect(13, parameters));
    assertTrue(RogueMiracles.acquire(player, 13));
    parameters.set(0, new GameplayDefinitions.EffectValue(.9));
    assertEquals(70, RogueMiracles.enhancementCost(run, 100));
    assertThrows(
        UnsupportedOperationException.class,
        () -> run.getMiracles().get(13).getEffectParameters().set(0, .9));
    assertFalse(RogueMiracles.acquire(player, 13));
    var later = new UniverseLifecycleTest.Explorer();
    var laterRun = new GameplayState.RogueRun(100, 1, Map.of());
    laterRun.setupTeam(later, List.of(199961));
    later.getGameplay().setRogue(laterRun);
    assertTrue(RogueMiracles.acquire(later, 13));
    assertEquals(10, RogueMiracles.enhancementCost(laterRun, 100));
  }

  @Test
  void decimalResourceDiscountDoesNotLoseAnExtraCoinToBinaryRounding() {
    effect(13, .3);
    assertTrue(RogueMiracles.acquire(player, 13));
    assertEquals(63, RogueMiracles.enhancementCost(run, 90));
  }

  @Test
  void corruptFrozenRevivalLifetimeCannotBecomeAnUnlimitedRevival() {
    run.getMiracles().put(4, new GameplayState.RogueMiracle(0, List.of(1.0)));
    run.getVitals().get(199961).setHp(0);
    RogueMiracles.onBattleWon(player);
    assertEquals(0, run.getVitals().get(199961).getHp());
    assertEquals(0, run.getMiracles().get(4).getUseCount());
  }

  @Test
  void corruptSavedParametersCannotApplyBenefitsOrRemoveOwnership() throws Exception {
    for (int id : List.of(6, 13, 15)) {
      effect(id, id == 15 ? .06 : .3);
      assertTrue(RogueMiracles.acquire(player, id));
    }
    var parameters = GameplayState.RogueMiracle.class.getDeclaredField("effectParameters");
    parameters.setAccessible(true);
    parameters.set(run.getMiracles().get(6), List.of(Double.NaN));
    parameters.set(run.getMiracles().get(13), List.of(2.0));
    parameters.set(run.getMiracles().get(15), null);
    run.getVitals().get(199961).setHp(1000);
    assertEquals(100, RogueMiracles.enhancementCost(run, 100));
    RogueMiracles.onRoomEntered(player);
    RogueMiracles.onBattleWon(player);
    assertEquals(100, run.getCoins());
    assertEquals(1000, run.getVitals().get(199961).getHp());
    assertEquals(3, run.getMiracles().size());
  }

  @Test
  void missingNonfiniteOrUnsupportedResourceParametersCannotBeAcquired() {
    for (double value : List.of(-.3, 0.0, 2.0, Double.NaN, Double.POSITIVE_INFINITY)) {
      effect(13, value);
      assertFalse(RogueMiracles.acquire(player, 13));
      assertTrue(run.getMiracles().isEmpty());
    }
    effect(13, .3);
    GameplayDefinitions.miracleEffects.put(
        13,
        new GameplayDefinitions.MiracleEffect(
            13,
            List.of(
                new GameplayDefinitions.EffectValue(.3),
                new GameplayDefinitions.EffectValue(Double.NaN))));
    assertFalse(RogueMiracles.acquire(player, 13));
    GameplayDefinitions.miracleEffects.remove(13);
    assertFalse(RogueMiracles.acquire(player, 13));
    effect(8, .3);
    try {
      assertFalse(RogueMiracles.acquire(player, 8));
    } finally {
      GameplayDefinitions.miracles.remove(8);
      GameplayDefinitions.miracleEffects.remove(8);
    }
    assertTrue(run.getMiracles().isEmpty());
  }
}
