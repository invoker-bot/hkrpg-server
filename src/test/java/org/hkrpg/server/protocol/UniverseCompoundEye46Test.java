package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class UniverseCompoundEye46Test {
  final UniverseReroll46Test fixture = new UniverseReroll46Test();
  final List<MazeBuffExcel> priorUpgrades = new ArrayList<>();
  GameplayDefinitions.Miracle priorMiracle;
  GameplayDefinitions.MiracleEffect priorEffect;

  @BeforeEach
  void setup() {
    priorMiracle = GameplayDefinitions.miracles.get(3);
    priorEffect = GameplayDefinitions.miracleEffects.get(3);
    fixture.setup();
    effect(1);
    GameplayDefinitions.blessings.removeIf(b -> b.MazeBuffID() >= 19997309);
    for (int id = 19997301; id <= 19997308; id++) {
      var prior = GameData.getMazeBuffExcelMap().get(id, 2);
      if (prior != null) priorUpgrades.add(prior);
      GameData.getMazeBuffExcelMap()
          .add(new Gson().fromJson("{\"ID\":" + id + ",\"Lv\":2}", MazeBuffExcel.class));
      GameplayDefinitions.blessings.add(
          new GameplayDefinitions.Blessing(id, 2, 100, "Common", 1, List.of()));
    }
    GameplayDefinitions.blessings.add(
        new GameplayDefinitions.Blessing(19997309, 1, 100, "Rare", 1, List.of()));
    GameplayDefinitions.blessings.add(
        new GameplayDefinitions.Blessing(19997310, 1, 100, "Legendary", 1, List.of()));
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameData.getMazeBuffExcelMap()
        .values()
        .removeIf(b -> b.getBuffId() >= 19997301 && b.getBuffId() <= 19997308 && b.getLv() == 2);
    priorUpgrades.forEach(GameData.getMazeBuffExcelMap()::add);
    GameplayDefinitions.miracles.remove(3);
    GameplayDefinitions.miracleEffects.remove(3);
    if (priorMiracle != null) GameplayDefinitions.miracles.put(3, priorMiracle);
    if (priorEffect != null) GameplayDefinitions.miracleEffects.put(3, priorEffect);
  }

  void effect(double value) {
    GameplayDefinitions.miracles.put(3, new GameplayDefinitions.Miracle(3, 3, 3));
    GameplayDefinitions.miracleEffects.put(
        3,
        new GameplayDefinitions.MiracleEffect(
            3, List.of(new GameplayDefinitions.EffectValue(value))));
  }

  GameplayState.RogueRun run() {
    return fixture.s.explorer.getGameplay().getRogue();
  }

  void begin(boolean acquired) throws Exception {
    assertEquals(0, Gameplay46.number(fixture.s.start(List.of(199961), 0), "retcode"));
    if (acquired) assertTrue(RogueMiracles.acquire(fixture.s.explorer, 3));
    run().clear();
    assertEquals(3, fixture.s.explorer.getUniverseManager().offerBlessings().size());
  }

  Map<Integer, Integer> nativeLevels() {
    var levels = new HashMap<Integer, Integer>();
    for (var row : (List<?>) Gameplay46.value(fixture.offer(), "LEOFNINDJDB")) {
      var buff = (DynamicMessage) row;
      levels.put(Gameplay46.number(buff, "buff_id"), Gameplay46.number(buff, "HAGDKKBAAKK"));
    }
    return levels;
  }

  void assertEnhancedMenu() {
    var levels = nativeLevels();
    assertEquals(Set.copyOf(run().getOffers()), levels.keySet());
    assertTrue(levels.keySet().stream().anyMatch(id -> id <= 19997308));
    levels.forEach((id, level) -> assertEquals(id <= 19997308 ? 2 : 1, level));
  }

  int common() {
    return run().getOffers().stream().filter(id -> id <= 19997308).findFirst().orElseThrow();
  }

  @Test
  void nativeVictoryMenuSelectionAndBattleUseTheEnhancedCommonLevel() throws Exception {
    begin(true);
    assertEnhancedMenu();
    int id = common();
    int batch = run().getSelectionId();
    var request = Gameplay46.builder(fixture.s, "OFEDNNJIJFL");
    Gameplay46.set(request, "MPLGEEGOAMC", batch);
    var field = request.getDescriptorForType().findFieldByName("DLKIKBIDJDB");
    var choice = DynamicMessage.newBuilder(field.getMessageType());
    Gameplay46.set(choice, "BAOFIGFFMLN", id);
    request.setField(field, choice.build());
    assertTrue(VersionedPacketHandler.handle(fixture.s, 5676, request.build().toByteArray()));
    assertEquals(0, Gameplay46.number(fixture.s.response, "retcode"));
    assertEquals(2, run().getBlessings().get(id));
    assertFalse(run().pending());
    assertTrue(VersionedPacketHandler.handle(fixture.s, 5676, request.build().toByteArray()));
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(fixture.s.response, "retcode"));
    var stage =
        new Gson()
            .fromJson(
                "{\"StageID\":19997199,\"StageType\":\"Event\"}",
                emu.lunarcore.data.excel.StageExcel.class);
    var battle =
        new emu.lunarcore.game.battle.Battle(
            fixture.s.explorer,
            new emu.lunarcore.game.player.lineup.PlayerLineup(fixture.s.explorer, 0),
            stage,
            false);
    assertEquals(2, battle.getBuffs().get(id).getLevel());
    assertEquals(100, run().getCoins());
  }

  @Test
  void rerollKeepsTheFrozenLevelsWhenOwnershipAndCategoryResourcesChange() throws Exception {
    begin(true);
    var first = List.copyOf(run().getOffers());
    run().getMiracles().remove(3);
    GameplayDefinitions.miracles.remove(3);
    GameplayDefinitions.miracleEffects.remove(3);
    GameplayDefinitions.blessings.replaceAll(
        b -> new GameplayDefinitions.Blessing(b.MazeBuffID(), 1, 100, "Legendary", 1, List.of()));
    assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
    assertTrue(Collections.disjoint(first, run().getOffers()));
    assertEnhancedMenu();
    assertEquals(70, run().getCoins());
  }

  @Test
  void acquisitionDoesNotRewriteAnExistingMenuButEnhancesTheNextVictoryMenu() throws Exception {
    begin(false);
    var first = nativeLevels();
    assertTrue(first.values().stream().allMatch(level -> level == 1));
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 3));
    assertEquals(first, nativeLevels());
    assertEquals(
        Retcode.SUCC,
        fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), common()));
    run().enter(2, run().getRoomId()); // State fixture, not a client movement acceptance test.
    run().clear();
    assertEquals(3, fixture.s.explorer.getUniverseManager().offerBlessings().size());
    assertEnhancedMenu();
  }

  @Test
  void legacyAndInvalidOwnedParametersKeepTheOrdinaryLevelWithoutLosingOwnership()
      throws Exception {
    for (var item :
        List.of(
            new GameplayState.RogueMiracle(0),
            new GameplayState.RogueMiracle(0, List.of(2.0)),
            new GameplayState.RogueMiracle(1, List.of(1.0)),
            new GameplayState.RogueMiracle(0, List.of(1.0, 1.0)))) {
      assertEquals(0, Gameplay46.number(fixture.s.start(List.of(199961), 0), "retcode"));
      run().getMiracles().put(3, item);
      run().clear();
      fixture.s.explorer.getUniverseManager().offerBlessings();
      assertTrue(nativeLevels().values().stream().allMatch(level -> level == 1));
      assertSame(item, run().getMiracles().get(3));
      fixture.s.explorer.getUniverseManager().leave(false);
      fixture.s.explorer.getUniverseManager().quit(Universe46LifecycleTest.AREA);
    }
  }

  @Test
  void missingEnhancedResourcesCannotOfferOrGrantAnUnconstructableCommonBlessing()
      throws Exception {
    GameData.getMazeBuffExcelMap()
        .values()
        .removeIf(b -> b.getBuffId() >= 19997301 && b.getBuffId() <= 19997308 && b.getLv() == 2);
    assertEquals(0, Gameplay46.number(fixture.s.start(List.of(199961), 0), "retcode"));
    assertTrue(RogueMiracles.acquire(fixture.s.explorer, 3));
    run().clear();
    assertEquals(
        Set.of(19997309, 19997310),
        Set.copyOf(fixture.s.explorer.getUniverseManager().offerBlessings()));
    assertTrue(nativeLevels().values().stream().allMatch(level -> level == 1));
    GameplayDefinitions.blessings.clear();
    int id = run().getOffers().getFirst();
    assertEquals(
        Retcode.SUCC,
        fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), id));
    run().enter(2, run().getRoomId());
    run().clear();
    assertTrue(fixture.s.explorer.getUniverseManager().offerBlessings().isEmpty());
    assertFalse(run().pending());
  }

  @Test
  void acquisitionRequiresMatchedResourceAssociationAndExactlyTheKnownEnhancementParameter() {
    assertTrue(RogueMiracles.supported(3));
    for (double invalid : new double[] {0, -1, 0.5, 2, Double.NaN, Double.POSITIVE_INFINITY}) {
      effect(invalid);
      assertFalse(RogueMiracles.supported(3));
    }
    effect(1);
    GameplayDefinitions.miracles.put(3, new GameplayDefinitions.Miracle(3, 3, 2));
    assertFalse(RogueMiracles.supported(3));
    effect(1);
    GameplayDefinitions.miracleEffects.put(
        3,
        new GameplayDefinitions.MiracleEffect(2, List.of(new GameplayDefinitions.EffectValue(1))));
    assertFalse(RogueMiracles.supported(3));
    effect(1);
    GameplayDefinitions.miracleEffects.put(
        3,
        new GameplayDefinitions.MiracleEffect(
            3,
            List.of(
                new GameplayDefinitions.EffectValue(1), new GameplayDefinitions.EffectValue(1))));
    assertFalse(RogueMiracles.supported(3));
  }

  @Test
  void morphiaRestoreKeepsCandidateLevelsAcrossRerollSelectionAndSecondRestore() throws Exception {
    begin(true);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "compound-eye");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456767);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, fixture.s.explorer.getGameplay());
      var before = nativeLevels();
      store.save(document);
      var restored = store.find(Player.class).first();
      assertNotSame(document.getGameplay(), restored.getGameplay());
      gameplay.set(fixture.s.explorer, restored.getGameplay());
      GameplayDefinitions.miracleEffects.remove(3);
      assertEquals(before, nativeLevels());
      assertEquals(0, Gameplay46.number(fixture.roll(null), "retcode"));
      assertEnhancedMenu();
      int id = common();
      assertEquals(
          Retcode.SUCC,
          fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), id));
      gameplay.set(document, fixture.s.explorer.getGameplay());
      store.save(document);
      restored = store.find(Player.class).first();
      assertEquals(2, restored.getGameplay().getRogue().getBlessings().get(id));
      assertFalse(restored.getGameplay().getRogue().pending());
      assertEquals(70, restored.getGameplay().getRogue().getCoins());
    } finally {
      mongo.shutdown();
    }
  }

  @Test
  void concurrentSelectionGrantsTheSavedEnhancedLevelOnlyOnce() throws Exception {
    begin(true);
    int id = common(), batch = run().getSelectionId();
    try (var workers = Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<Callable<Retcode>>();
      for (int i = 0; i < 6; i++)
        calls.add(() -> fixture.s.explorer.getUniverseManager().selectBlessing(batch, id));
      int successes = 0;
      for (var result : workers.invokeAll(calls)) {
        if (result.get() == Retcode.SUCC) successes++;
        else assertEquals(Retcode.REQ_PARA_INVALID, result.get());
      }
      assertEquals(1, successes);
    }
    assertEquals(Map.of(id, 2), run().getBlessings());
    assertEquals(100, run().getCoins());
  }

  @Test
  void incompleteOrCorruptSavedLevelPlansCannotSelectOrChargeARefresh() throws Exception {
    begin(true);
    int id = common();
    int batch = run().getSelectionId();
    var before = List.copyOf(run().getOffers());
    var levels = new HashMap<>(run().getOfferLevels());
    int other =
        levels.keySet().stream().filter(candidate -> candidate != id).findFirst().orElseThrow();
    for (int bad : List.of(0, 3)) {
      run().getOfferLevels().clear();
      run().getOfferLevels().putAll(levels);
      if (bad == 0) run().getOfferLevels().remove(other);
      else run().getOfferLevels().put(other, bad);
      assertEquals(
          Retcode.REQ_PARA_INVALID,
          fixture.s.explorer.getUniverseManager().selectBlessing(batch, id));
      assertEquals(
          Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(fixture.roll(null), "retcode"));
      assertEquals(before, run().getOffers());
      assertEquals(100, run().getCoins());
      assertTrue(run().getBlessings().isEmpty());
    }
    run().getOfferLevels().clear();
    run().getOfferLevels().putAll(levels);
    GameData.getMazeBuffExcelMap().values().removeIf(b -> b.getBuffId() == id && b.getLv() == 2);
    assertEquals(
        Retcode.REQ_PARA_INVALID,
        fixture.s.explorer.getUniverseManager().selectBlessing(batch, id));
    assertEquals(before, run().getOffers());
    assertTrue(run().getBlessings().isEmpty());
  }

  @Test
  void legacyMenusAndNonVictoryMenusAreNotRetrospectivelyEnhanced() throws Exception {
    begin(true);
    var method =
        UniverseManager.class.getDeclaredMethod(
            "createBlessingOffer", GameplayState.RogueRun.class, Set.class, boolean.class);
    method.setAccessible(true);
    method.invoke(fixture.s.explorer.getUniverseManager(), run(), null, false);
    assertTrue(nativeLevels().values().stream().allMatch(level -> level == 1));
    run().offer(List.of(19997301), List.of(19997301), List.of());
    assertEquals(Map.of(19997301, 1), nativeLevels());
    assertEquals(
        Retcode.SUCC,
        fixture.s.explorer.getUniverseManager().selectBlessing(run().getSelectionId(), 19997301));
    assertEquals(1, run().getBlessings().get(19997301));
  }
}
