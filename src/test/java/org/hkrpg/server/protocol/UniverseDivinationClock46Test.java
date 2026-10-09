package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseDivinationClock46Test {
  final UniverseReroll46Test fixture = new UniverseReroll46Test();
  GameplayDefinitions.Miracle previous;
  GameplayDefinitions.MiracleEffect previousEffect;

  @BeforeEach
  void setup() throws Exception {
    previous = GameplayDefinitions.miracles.get(65);
    previousEffect = GameplayDefinitions.miracleEffects.get(65);
    fixture.setup();
    GameplayDefinitions.miracles.put(65, new GameplayDefinitions.Miracle(65, 46, 65));
    GameplayDefinitions.miracleEffects.put(
        65,
        new GameplayDefinitions.MiracleEffect(65, List.of(new GameplayDefinitions.EffectValue(1))));
    assertEquals(0, Gameplay46.number(fixture.s.start(List.of(199961), 0), "retcode"));
    run().clear();
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    emu.lunarcore.data.GameData.getMazeBuffExcelMap()
        .values()
        .removeIf(b -> b.getID() >= 19997301 && b.getID() <= 19997310);
    GameplayDefinitions.miracles.remove(65);
    GameplayDefinitions.miracleEffects.remove(65);
    if (previous != null) GameplayDefinitions.miracles.put(65, previous);
    if (previousEffect != null) GameplayDefinitions.miracleEffects.put(65, previousEffect);
  }

  GameplayState.RogueRun run() {
    return fixture.s.explorer.getGameplay().getRogue();
  }

  List<Integer> offer() {
    return fixture.s.explorer.getUniverseManager().offerBlessings();
  }

  void snapshot() {
    run().getMiracles().put(65, new GameplayState.RogueMiracle(0, List.of(1.0)));
  }

  @Test
  void exactDivinationClockResourceCanBeAcquiredOnlyOnce() {
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 65));
    assertFalse(RogueMiracles.acquire(fixture.s.explorer, 65));
    assertEquals(List.of(1.0), run().getMiracles().get(65).getEffectParameters());
    assertEquals(0, run().getMiracles().get(65).getTotalCount());
  }

  @Test
  void nativeVictoryMenuHasTwoDistinctChoicesAndRerollKeepsTheLimit() throws Exception {
    snapshot();
    var first = offer();
    assertEquals(2, first.size());
    assertEquals(2, new HashSet<>(first).size());
    assertEquals(2, ((List<?>) Gameplay46.value(fixture.offer(), "LEOFNINDJDB")).size());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(2, run().getOffers().size());
    assertTrue(Collections.disjoint(first, run().getOffers()));
    assertEquals(70, run().getCoins());
    int batch = run().getSelectionId();
    assertEquals(
        Retcode.SUCC,
        fixture
            .s
            .explorer
            .getUniverseManager()
            .selectBlessing(batch, run().getOffers().getFirst()));
    assertFalse(run().pending());
  }

  @Test
  void savedMenuKeepsTwoOptionsAfterOwnershipAndResourcesAreRemoved() throws Exception {
    snapshot();
    assertEquals(2, offer().size());
    run().getMiracles().remove(65);
    GameplayDefinitions.miracles.remove(65);
    GameplayDefinitions.miracleEffects.remove(65);
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(2, run().getOffers().size());
  }

  @Test
  void aLaterAcquisitionDoesNotRewriteAnExistingThreeOptionMenu() throws Exception {
    assertEquals(3, offer().size());
    snapshot();
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(3, run().getOffers().size());
  }

  @Test
  void anUnknownRatioOrDisplayCannotBeAcquiredAndAnInvalidOwnedLifetimeHasNoEffect() {
    GameplayDefinitions.miracles.put(65, new GameplayDefinitions.Miracle(65, 51, 65));
    assertFalse(RogueMiracles.acquire(fixture.s.explorer, 65));
    GameplayDefinitions.miracles.put(65, new GameplayDefinitions.Miracle(65, 46, 65));
    GameplayDefinitions.miracleEffects.put(
        65,
        new GameplayDefinitions.MiracleEffect(
            65, List.of(new GameplayDefinitions.EffectValue(.5))));
    assertFalse(RogueMiracles.acquire(fixture.s.explorer, 65));
    run().getMiracles().put(65, new GameplayState.RogueMiracle(1, List.of(1.0)));
    assertEquals(3, offer().size());
    assertEquals(100, run().getCoins());
  }

  @Test
  void aSingleEligibleBlessingRemainsSelectableWithoutInventingAnExtraOption() {
    snapshot();
    int id = GameplayDefinitions.blessings.getFirst().MazeBuffID();
    GameplayDefinitions.blessings.removeIf(b -> b.MazeBuffID() != id);
    assertEquals(List.of(id), offer());
    assertTrue(fixture.s.explorer.getUniverseManager().blessingRerollCost().isEmpty());
    assertEquals(
        Retcode.SUCC,
        fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), id));
    assertFalse(run().pending());
    assertEquals(100, run().getCoins());
  }

  @Test
  void doubleChoiceRemainsTwoDistinctSelectionsWhenCombinedWithTheClock() throws Exception {
    snapshot();
    run().getMiracles().put(1, new GameplayState.RogueMiracle(2, List.of(1.0, 1.0, 2.0)));
    assertEquals(2, offer().size());
    assertEquals(2, run().offerChoiceRemaining());
    assertEquals(1, run().getMiracles().get(1).getUseCount());
    int id = run().getOffers().getFirst();
    assertEquals(
        Retcode.SUCC,
        fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), id));
    assertEquals(1, run().offerChoiceRemaining());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(2, run().getOffers().size());
    assertFalse(run().getOffers().contains(id));
    assertEquals(
        Retcode.SUCC,
        fixture
            .s
            .explorer
            .getUniverseManager()
            .selectBlessing(run().getSelectionId(), run().getOffers().getFirst()));
    assertFalse(run().pending());
    assertEquals(2, run().getBlessings().size());
    assertEquals(1, run().getMiracles().get(1).getUseCount());
  }

  @Test
  void aCorruptSavedOptionLimitRejectsRerollAndSelectionWithoutCharging() throws Exception {
    snapshot();
    assertEquals(2, offer().size());
    var saved = List.copyOf(run().getOffers());
    var limit = GameplayState.RogueRun.class.getDeclaredField("offerOptionLimit");
    limit.setAccessible(true);
    for (int invalid : new int[] {-1, 4, 1}) {
      limit.setInt(run(), invalid);
      assertFalse(run().validOfferLevels());
      assertNotEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
      assertNotEquals(
          Retcode.SUCC,
          fixture
              .s
              .explorer
              .getUniverseManager()
              .selectBlessing(run().getSelectionId(), saved.getFirst()));
      assertEquals(saved, run().getOffers());
      assertEquals(100, run().getCoins());
    }
  }

  @Test
  void concurrentRerollUsesTheTwoOptionPlanAndChargesOnce() throws Exception {
    snapshot();
    assertEquals(2, offer().size());
    int batch = run().getSelectionId();
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 6; i++)
        calls.add(() -> fixture.s.explorer.getUniverseManager().rerollBlessings(batch));
      int success = 0;
      for (var result : workers.invokeAll(calls)) if (result.get() == Retcode.SUCC) success++;
      assertEquals(1, success);
    }
    assertEquals(2, run().getOffers().size());
    assertEquals(70, run().getCoins());
    assertEquals(1, run().getOfferRerolls());
  }

  @Test
  void actualMorphiaReloadKeepsTwoOptionsAndConsumedRerollAfterResourceRemoval() throws Exception {
    snapshot();
    assertEquals(2, offer().size());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "divinationClock");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456764);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      gameplay.set(
          fixture.s.explorer,
          store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      run().getMiracles().remove(65);
      GameplayDefinitions.miracles.remove(65);
      GameplayDefinitions.miracleEffects.remove(65);
      assertEquals(2, run().getOffers().size());
      assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
      assertEquals(2, run().getOffers().size());
      assertEquals(70, run().getCoins());
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      gameplay.set(
          fixture.s.explorer,
          store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      assertEquals(2, run().getOffers().size());
      assertEquals(1, run().getOfferRerolls());
      assertNotEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
      assertEquals(70, run().getCoins());
    } finally {
      mongo.shutdownNow();
    }
  }
}
