package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseBeaconColoring46Test {
  final UniverseReroll46Test fixture = new UniverseReroll46Test();
  GameplayDefinitions.Miracle previous;
  GameplayDefinitions.MiracleEffect previousEffect;

  @BeforeEach
  void setup() throws Exception {
    previous = GameplayDefinitions.miracles.get(69);
    previousEffect = GameplayDefinitions.miracleEffects.get(69);
    fixture.setup();
    GameplayDefinitions.miracles.put(69, new GameplayDefinitions.Miracle(69, 50, 69));
    GameplayDefinitions.miracleEffects.put(
        69,
        new GameplayDefinitions.MiracleEffect(69, List.of(new GameplayDefinitions.EffectValue(1))));
    for (int id = 19997301; id <= 19997310; id++) {
      GameData.getMazeBuffExcelMap()
          .add(new Gson().fromJson("{\"ID\":" + id + ",\"Lv\":2}", MazeBuffExcel.class));
      GameplayDefinitions.blessings.add(
          new GameplayDefinitions.Blessing(id, 2, 100, "Common", 1, List.of()));
    }
    assertEquals(0, Gameplay46.number(fixture.s.start(List.of(199961), 0), "retcode"));
    run().clear();
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameData.getMazeBuffExcelMap()
        .values()
        .removeIf(b -> b.getID() >= 19997301 && b.getID() <= 19997310);
    GameplayDefinitions.miracles.remove(69);
    GameplayDefinitions.miracleEffects.remove(69);
    if (previous != null) GameplayDefinitions.miracles.put(69, previous);
    if (previousEffect != null) GameplayDefinitions.miracleEffects.put(69, previousEffect);
  }

  GameplayState.RogueRun run() {
    return fixture.s.explorer.getGameplay().getRogue();
  }

  void snapshot() {
    run().getMiracles().put(69, new GameplayState.RogueMiracle(0, List.of(1.0)));
  }

  int upgraded() {
    var ids = run().getOffers().stream().filter(id -> run().offerLevel(id) == 2).toList();
    assertEquals(1, ids.size());
    int id = ids.getFirst();
    assertNotNull(GameData.getMazeBuffExcelMap().get(id, 2));
    var rows = (List<?>) Gameplay46.value(fixture.offer(), "LEOFNINDJDB");
    assertEquals(
        1,
        rows.stream()
            .map(DynamicMessage.class::cast)
            .filter(row -> Gameplay46.number(row, "HAGDKKBAAKK") == 2)
            .count());
    return id;
  }

  @Test
  void exactBeaconColoringResourceCanBeAcquiredOnce() {
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 69));
    assertFalse(RogueMiracles.acquire(fixture.s.explorer, 69));
    assertEquals(List.of(1.0), run().getMiracles().get(69).getEffectParameters());
    assertEquals(0, run().getMiracles().get(69).getTotalCount());
  }

  @Test
  void oneDisplayedVictoryCandidateIsUpgradedAndNativeSelectionGrantsThatLevel() throws Exception {
    snapshot();
    assertEquals(3, fixture.s.explorer.getUniverseManager().offerBlessings().size());
    int id = upgraded();
    var req = Gameplay46.builder(fixture.s, "OFEDNNJIJFL");
    Gameplay46.set(req, "MPLGEEGOAMC", run().getSelectionId());
    var choice = Gameplay46.builder(fixture.s, "BPLNKHGAILK");
    Gameplay46.set(choice, "BAOFIGFFMLN", id);
    Gameplay46.set(req, "DLKIKBIDJDB", choice.build());
    assertTrue(VersionedPacketHandler.handle(fixture.s, 5676, req.build().toByteArray()));
    assertEquals(0, Gameplay46.number(fixture.s.response, "retcode"));
    assertEquals(2, run().getBlessings().get(id));
    assertFalse(run().pending());
    assertEquals(100, run().getCoins());
  }

  @Test
  void eachPaidRerollUpgradesOneOfItsNewDisplayedCandidates() throws Exception {
    snapshot();
    assertEquals(3, fixture.s.explorer.getUniverseManager().offerBlessings().size());
    int original = upgraded();
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(3, run().getOffers().size());
    assertFalse(run().getOffers().contains(original));
    assertNotEquals(original, upgraded());
    assertEquals(70, run().getCoins());
  }

  @Test
  void menuEffectRemainsFrozenWhenOwnershipAndCurrentResourceRowsDisappear() throws Exception {
    snapshot();
    assertEquals(3, fixture.s.explorer.getUniverseManager().offerBlessings().size());
    upgraded();
    run().getMiracles().remove(69);
    GameplayDefinitions.miracles.remove(69);
    GameplayDefinitions.miracleEffects.remove(69);
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    upgraded();
    assertEquals(70, run().getCoins());
  }

  @Test
  void acquiringLaterDoesNotUpgradeAnAlreadyAdvertisedMenuOrItsReroll() throws Exception {
    assertEquals(3, fixture.s.explorer.getUniverseManager().offerBlessings().size());
    snapshot();
    assertTrue(run().getOffers().stream().allMatch(id -> run().offerLevel(id) == 1));
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertTrue(run().getOffers().stream().allMatch(id -> run().offerLevel(id) == 1));
    assertEquals(70, run().getCoins());
  }

  @Test
  void mismatchedDisplayAndUnknownRatioCannotBeAcquiredOrBorrowedByLegacyOwnership() {
    GameplayDefinitions.miracles.put(69, new GameplayDefinitions.Miracle(69, 46, 69));
    assertFalse(RogueMiracles.acquire(fixture.s.explorer, 69));
    GameplayDefinitions.miracles.put(69, new GameplayDefinitions.Miracle(69, 50, 69));
    GameplayDefinitions.miracleEffects.put(
        69,
        new GameplayDefinitions.MiracleEffect(69, List.of(new GameplayDefinitions.EffectValue(2))));
    assertFalse(RogueMiracles.acquire(fixture.s.explorer, 69));
    run().getMiracles().put(69, new GameplayState.RogueMiracle(0));
    assertEquals(3, fixture.s.explorer.getUniverseManager().offerBlessings().size());
    assertTrue(run().getOffers().stream().allMatch(id -> run().offerLevel(id) == 1));
    assertEquals(100, run().getCoins());
  }

  @Test
  void missingSecondLevelsCannotCreateUnconstructibleBlessings() {
    snapshot();
    for (int id = 19997301; id <= 19997310; id++)
      GameData.getMazeBuffExcelMap().values().remove(GameData.getMazeBuffExcelMap().get(id, 2));
    assertEquals(3, fixture.s.explorer.getUniverseManager().offerBlessings().size());
    assertTrue(run().getOffers().stream().allMatch(id -> run().offerLevel(id) == 1));
    assertEquals(
        Retcode.SUCC,
        fixture
            .s
            .explorer
            .getUniverseManager()
            .selectBlessing(run().getSelectionId(), run().getOffers().getFirst()));
    assertEquals(1, run().getBlessings().values().iterator().next());
  }

  @Test
  void compoundEyesSecondLevelCandidatesDoNotBecomeLevelThree() throws Exception {
    snapshot();
    run().getMiracles().put(3, new GameplayState.RogueMiracle(0, List.of(1.0)));
    assertEquals(3, fixture.s.explorer.getUniverseManager().offerBlessings().size());
    assertTrue(run().getOffers().stream().allMatch(id -> run().offerLevel(id) == 2));
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertTrue(run().getOffers().stream().allMatch(id -> run().offerLevel(id) == 2));
  }

  @Test
  void combinedClocksAndDoubleChoiceKeepTheSelectedUpgradeAndRerollTheRemainingChoice()
      throws Exception {
    snapshot();
    run().getMiracles().put(65, new GameplayState.RogueMiracle(0, List.of(1.0)));
    run().getMiracles().put(1, new GameplayState.RogueMiracle(2, List.of(1.0, 1.0, 2.0)));
    assertEquals(2, fixture.s.explorer.getUniverseManager().offerBlessings().size());
    int id = upgraded();
    assertEquals(
        Retcode.SUCC,
        fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), id));
    assertEquals(2, run().getBlessings().get(id));
    assertTrue(run().validOfferLevels());
    assertEquals(1, run().offerChoiceRemaining());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertEquals(2, run().getOffers().size());
    int second = upgraded();
    assertNotEquals(id, second);
    assertEquals(
        Retcode.SUCC,
        fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), second));
    assertEquals(2, run().getBlessings().get(second));
    assertFalse(run().pending());
    assertEquals(1, run().getMiracles().get(1).getUseCount());
  }

  @Test
  void aDisappearingSecondLevelRejectsSelectionWithoutDowngradingItsSavedReward() throws Exception {
    snapshot();
    fixture.s.explorer.getUniverseManager().offerBlessings();
    int id = upgraded();
    GameData.getMazeBuffExcelMap().values().remove(GameData.getMazeBuffExcelMap().get(id, 2));
    assertNotEquals(
        Retcode.SUCC,
        fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), id));
    assertEquals(2, run().offerLevel(id));
    assertFalse(run().getBlessings().containsKey(id));
    assertEquals(100, run().getCoins());
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertNotEquals(id, upgraded());
    assertEquals(70, run().getCoins());
  }

  @Test
  void corruptFrozenLevelsRejectExtraUpgradesAndUnseenUpgradesWithoutCharging() throws Exception {
    snapshot();
    fixture.s.explorer.getUniverseManager().offerBlessings();
    int target = upgraded();
    int other = run().getOffers().stream().filter(id -> id != target).findFirst().orElseThrow();
    int unseen =
        run().getOfferPool().stream()
            .filter(id -> !run().getOffers().contains(id))
            .findFirst()
            .orElseThrow();
    for (int id : List.of(other, unseen)) {
      run().getOfferLevels().put(id, 2);
      assertFalse(run().validOfferLevels());
      assertNotEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
      assertNotEquals(
          Retcode.SUCC,
          fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), target));
      assertEquals(100, run().getCoins());
      assertTrue(run().pending());
      run().getOfferLevels().put(id, 1);
    }
    assertTrue(run().validOfferLevels());
  }

  @Test
  void concurrentRerollsSaveOneMenuWithOneUpgradeAndOneDebit() throws Exception {
    snapshot();
    fixture.s.explorer.getUniverseManager().offerBlessings();
    upgraded();
    int batch = run().getSelectionId();
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 6; i++)
        tasks.add(() -> fixture.s.explorer.getUniverseManager().rerollBlessings(batch));
      int successes = 0;
      for (var result : workers.invokeAll(tasks)) if (result.get() == Retcode.SUCC) successes++;
      assertEquals(1, successes);
    }
    upgraded();
    assertEquals(1, run().getOfferRerolls());
    assertEquals(70, run().getCoins());
  }

  @Test
  void actualMorphiaReloadKeepsTheDisplayedUpgradeBasePoolAndFrozenEffect() throws Exception {
    snapshot();
    fixture.s.explorer.getUniverseManager().offerBlessings();
    int target = upgraded();
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "beaconColoring");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456763);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      gameplay.set(
          fixture.s.explorer,
          store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      assertEquals(target, upgraded());
      run().getMiracles().remove(69);
      GameplayDefinitions.miracles.remove(69);
      GameplayDefinitions.miracleEffects.remove(69);
      assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
      int next = upgraded();
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      gameplay.set(
          fixture.s.explorer,
          store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      assertEquals(next, upgraded());
      assertEquals(70, run().getCoins());
      assertNotEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
      assertEquals(
          Retcode.SUCC,
          fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), next));
      assertEquals(2, run().getBlessings().get(next));
    } finally {
      mongo.shutdownNow();
    }
  }
}
