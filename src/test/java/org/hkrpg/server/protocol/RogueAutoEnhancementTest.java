package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Actual curio upgrades; scene/tutorial and the single-candidate dice pool are explicit fixtures.
 */
class RogueAutoEnhancementTest {
  final RogueEnhancementQuestTest f = new RogueEnhancementQuestTest();
  final Map<Integer, GameplayDefinitions.Miracle> oldMiracles = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleGroup> oldGroups = new HashMap<>();
  Map<Integer, RogueHandbookCatalog.Miracle> oldHandbook;

  @BeforeEach
  void setup() throws Exception {
    oldMiracles.putAll(GameplayDefinitions.miracles);
    oldEffects.putAll(GameplayDefinitions.miracleEffects);
    oldGroups.putAll(GameplayDefinitions.miracleGroups);
    oldHandbook = RogueHandbookCatalog.snapshot();
    f.setup();
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracleEffects.clear();
    GameplayDefinitions.miracleGroups.clear();
    var handbook = new JsonArray();
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-auto-enhancement-4.6.json")) {
      var rows =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(34, rows.size());
      for (var raw : rows) {
        var spec = raw.getAsJsonObject();
        var row = spec.getAsJsonObject("row");
        switch (spec.get("kind").getAsString()) {
          case "miracle" -> {
            var r = f.gson.fromJson(row, GameplayDefinitions.Miracle.class);
            GameplayDefinitions.miracles.put(r.MiracleID(), r);
          }
          case "effect" -> {
            var r = f.gson.fromJson(row, GameplayDefinitions.MiracleEffect.class);
            GameplayDefinitions.miracleEffects.put(r.MiracleEffectID(), r);
          }
          case "group" -> {
            var r = f.gson.fromJson(row, GameplayDefinitions.MiracleGroup.class);
            GameplayDefinitions.miracleGroups.put(r.RogueMiracleGroupID(), r);
          }
          case "handbook" -> handbook.add(row);
          case "buff" -> {
            var r = f.gson.fromJson(row, GameplayDefinitions.Blessing.class);
            if (r.MazeBuffID() != RogueEnhancementQuestTest.BUFF)
              GameplayDefinitions.blessings.add(r);
          }
          case "maze" ->
              GameData.getMazeBuffExcelMap().add(f.gson.fromJson(row, MazeBuffExcel.class));
          default -> {}
        }
      }
    }
    var dir = Files.createTempDirectory("auto-enhancement-handbook-");
    try {
      Files.createDirectory(dir.resolve("ExcelOutput"));
      Files.writeString(dir.resolve("ExcelOutput/RogueHandbookMiracle.json"), handbook.toString());
      RogueHandbookCatalog.load(dir);
    } finally {
      Files.deleteIfExists(dir.resolve("ExcelOutput/RogueHandbookMiracle.json"));
      Files.deleteIfExists(dir.resolve("ExcelOutput"));
      Files.deleteIfExists(dir);
    }
  }

  @AfterEach
  void restore() throws Exception {
    f.restore();
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracles.putAll(oldMiracles);
    GameplayDefinitions.miracleEffects.clear();
    GameplayDefinitions.miracleEffects.putAll(oldEffects);
    GameplayDefinitions.miracleGroups.clear();
    GameplayDefinitions.miracleGroups.putAll(oldGroups);
    var field = RogueHandbookCatalog.class.getDeclaredField("miracles");
    field.setAccessible(true);
    field.set(null, oldHandbook);
  }

  GameplayState.RogueRun begin(boolean three) {
    f.begin();
    var run = f.player().getGameplay().getRogue();
    if (three)
      for (int id : List.of(612030, 612040)) {
        run.offer(List.of(id));
        assertEquals(
            Retcode.SUCC, f.player().getUniverseManager().selectBlessing(run.getSelectionId(), id));
      }
    return run;
  }

  Retcode choose(int id) {
    var run = f.player().getGameplay().getRogue();
    run.offerMiracles(List.of(id));
    return f.player().getUniverseManager().selectMiracle(run.getSelectionId(), id);
  }

  int total() {
    return f.player().getGameplay().getRogueEnhancements().total();
  }

  void auto(int times) {
    for (int i = 0; i < times; i++) {
      var run = begin(true);
      assertEquals(Retcode.SUCC, choose(20));
      assertEquals(2, run.getBlessings().values().stream().filter(v -> v == 2).count());
      assertEquals(300, run.getCoins());
    }
  }

  @Test
  void actualTwoCurioUpgradesAdvanceTheOriginalGoalImmediately() {
    auto(1);
    assertEquals(2, total());
    assertEquals(2, f.player().getQuestStates().get(4092314).getProgress());
  }

  @Test
  void onlyOneActualUpgradeCountsOnceDespiteTheOriginalLimitOfTwo() {
    var run = begin(false);
    assertEquals(Retcode.SUCC, choose(20));
    assertEquals(2, run.getBlessings().get(RogueEnhancementQuestTest.BUFF));
    assertEquals(1, total());
  }

  @Test
  void fiveActualCuriosPayTheOriginalTenUpgradeGoalOnce() {
    auto(5);
    assertEquals(10, f.state(4092314).getProgress());
    assertEquals(Retcode.SUCC, f.player().getQuestManager().claim(4092314).retcode());
    assertEquals(5, f.player().getHcoin());
    assertNotEquals(Retcode.SUCC, f.player().getQuestManager().claim(4092314).retcode());
  }

  @Test
  void oneHundredActualCuriosPayBothOriginalGoalsForFifteenJades() {
    auto(100);
    assertEquals(200, total());
    for (int id : List.of(4092314, 4092315))
      assertEquals(Retcode.SUCC, f.player().getQuestManager().claim(id).retcode());
    assertEquals(15, f.player().getHcoin());
  }

  @Test
  void aRepeatedActualAcquisitionDoesNotCountTwice() {
    auto(1);
    var run = f.player().getGameplay().getRogue();
    int batch = run.getSelectionId();
    assertNotEquals(Retcode.SUCC, f.player().getUniverseManager().selectMiracle(batch, 20));
    assertEquals(2, total());
  }

  @Test
  void newBlessingsFromTheBoxDoNotCountAsEnhancements() {
    begin(false);
    assertEquals(Retcode.SUCC, choose(5));
    assertEquals(0, total());
    assertTrue(
        f.player().getGameplay().getRogue().getBlessings().values().stream().allMatch(v -> v == 1));
  }

  @Test
  void alreadyEnhancedHoldingsDoNotCreateAnotherOperation() {
    var run = begin(true);
    run.getBlessings().replaceAll((k, v) -> 2); // Negative check: ownership is not an operation.
    assertEquals(Retcode.SUCC, choose(20));
    assertEquals(0, total());
  }

  void dicePool() {
    // Retain the exact source identity and weight of20; this isolated pool makes the branch
    // deterministic.
    GameplayDefinitions.miracleGroups.put(
        10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(20, 1)));
  }

  @Test
  void repeatedDicePreviewsDoNotMutateCountsOrGoalProgress() {
    begin(true);
    dicePool();
    String before = JsonUtils.encode(f.player().getGameplay());
    for (int i = 0; i < 5; i++) assertNotNull(RogueMiracleDice.prepare(f.player()));
    assertEquals(before, JsonUtils.encode(f.player().getGameplay()));
    assertEquals(0, total());
    assertEquals(0, f.state(4092314).getProgress());
  }

  @Test
  void actualDiceSelectionCommitsProjectedUpgradesAndCountsTogether() {
    var run = begin(true);
    dicePool();
    assertEquals(Retcode.SUCC, choose(21));
    assertTrue(run.getMiracles().containsKey(20));
    assertEquals(2, run.getBlessings().values().stream().filter(v -> v == 2).count());
    assertEquals(2, total());
    assertEquals(2, f.player().getQuestStates().get(4092314).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void changedHistoryRejectsThePreparedDicePlanWithoutPublishingTheProjection(int mode)
      throws Exception {
    begin(true);
    dicePool();
    var plan = RogueMiracleDice.prepare(f.player());
    assertNotNull(plan);
    if (mode == 0) damageCount(1);
    else {
      var field = GameplayState.class.getDeclaredField("rogueEnhancements");
      field.setAccessible(true);
      field.set(f.player().getGameplay(), mode == 1 ? null : new RogueEnhancementState());
    }
    String before = JsonUtils.encode(f.player().getGameplay());
    assertNull(plan.commit(f.player()));
    assertEquals(before, JsonUtils.encode(f.player().getGameplay()));
  }

  void damageCount(int count) throws Exception {
    var field = RogueEnhancementState.class.getDeclaredField("count");
    field.setAccessible(true);
    field.setInt(f.player().getGameplay().getRogueEnhancements(), count);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void damagedCountRejectsAcquisitionBeforeAnyCurioOrBuffMutation(int mode) throws Exception {
    begin(true);
    if (mode == 0) damageCount(-1);
    else {
      var field = GameplayState.class.getDeclaredField("rogueEnhancements");
      field.setAccessible(true);
      field.set(f.player().getGameplay(), null);
    }
    var run = f.player().getGameplay().getRogue();
    run.offerMiracles(List.of(20));
    String before = JsonUtils.encode(f.player().getGameplay());
    assertNotEquals(
        Retcode.SUCC, f.player().getUniverseManager().selectMiracle(run.getSelectionId(), 20));
    assertEquals(before, JsonUtils.encode(f.player().getGameplay()));
  }

  @Test
  void wholeTwoUpgradeBatchIsRejectedWhenOnlyOneCountSlotRemains() throws Exception {
    begin(true);
    damageCount(Integer.MAX_VALUE - 1);
    var run = f.player().getGameplay().getRogue();
    run.offerMiracles(List.of(20));
    String before = JsonUtils.encode(f.player().getGameplay());
    assertNotEquals(
        Retcode.SUCC, f.player().getUniverseManager().selectMiracle(run.getSelectionId(), 20));
    assertEquals(before, JsonUtils.encode(f.player().getGameplay()));
  }

  @Test
  void actualAutomaticUpgradesAddToPaidHistoryAndRetainTheClaimedGoal() {
    f.enhance(10);
    assertEquals(Retcode.SUCC, f.player().getQuestManager().claim(4092314).retcode());
    begin(true);
    dicePool();
    assertEquals(Retcode.SUCC, choose(21));
    assertEquals(12, total());
    assertEquals(12, f.state(4092315).getProgress());
    assertEquals(5, f.player().getHcoin());
    assertNotEquals(Retcode.SUCC, f.player().getQuestManager().claim(4092314).retcode());
  }

  @Test
  void concurrentCurioSelectionsCountTheWholeActualBatchOnce() throws Exception {
    var run = begin(true);
    run.offerMiracles(List.of(20));
    int batch = run.getSelectionId();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var tasks = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 24; i++)
        tasks.add(() -> f.player().getUniverseManager().selectMiracle(batch, 20));
      int successes = 0;
      for (var v : pool.invokeAll(tasks)) if (v.get() == Retcode.SUCC) successes++;
      assertEquals(1, successes);
      assertEquals(2, total());
      assertEquals(300, run.getCoins());
      assertEquals(2, f.state(4092314).getProgress());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentDiceCommitsPublishAndCountOneProjection() throws Exception {
    var run = begin(true);
    dicePool();
    var plan = RogueMiracleDice.prepare(f.player());
    assertNotNull(plan);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var tasks = new ArrayList<java.util.concurrent.Callable<RogueMiracles.Acquisition>>();
      for (int i = 0; i < 24; i++) tasks.add(() -> plan.commit(f.player()));
      int successes = 0;
      for (var v : pool.invokeAll(tasks)) if (v.get() != null) successes++;
      assertEquals(1, successes);
      assertEquals(2, total());
      assertEquals(2, run.getBlessings().values().stream().filter(v -> v == 2).count());
      assertEquals(2, f.state(4092314).getProgress());
    } finally {
      pool.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void missingDuplicateOrOtherVersionLevelsDoNotCreateAnUpgrade(int mode) throws Exception {
    var run = begin(false);
    int id = RogueEnhancementQuestTest.BUFF;
    var next =
        GameplayDefinitions.blessings.stream()
            .filter(b -> b.MazeBuffID() == id && b.MazeBuffLevel() == 2)
            .findFirst()
            .orElseThrow();
    if (mode == 0) GameplayDefinitions.blessings.remove(next);
    else if (mode == 1) GameplayDefinitions.blessings.add(next);
    else {
      GameplayDefinitions.blessings.remove(next);
      GameplayDefinitions.blessings.add(
          new GameplayDefinitions.Blessing(
              id, 2, next.RogueBuffType(), next.RogueBuffCategory(), 2, next.UnlockIDList()));
    }
    assertEquals(Retcode.SUCC, choose(20));
    assertEquals(1, run.getBlessings().get(id));
    assertEquals(0, total());
  }

  @Test
  void oneActualUpgradeCanUseTheFinalValidCountSlot() throws Exception {
    var run = begin(false);
    damageCount(Integer.MAX_VALUE - 1);
    assertEquals(Retcode.SUCC, choose(20));
    assertEquals(Integer.MAX_VALUE, total());
    assertEquals(2, run.getBlessings().get(RogueEnhancementQuestTest.BUFF));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualCurioCountsAndBothGoalReceiptsSurviveTwoPlayerMorphiaReloads(boolean claimed)
      throws Exception {
    auto(100);
    if (claimed)
      for (int id : List.of(4092314, 4092315))
        assertEquals(Retcode.SUCC, f.player().getQuestManager().claim(id).retcode());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "automatic-upgrade-goals");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      for (String name : List.of("uid", "name", "gameplay", "questStates", "hcoin")) {
        var field = emu.lunarcore.game.player.Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(document, name.equals("uid") ? 123456785 : field.get(f.player()));
      }
      store.save(document);
      var first = store.find(emu.lunarcore.game.player.Player.class).first();
      assertEquals(200, first.getGameplay().getRogueEnhancements().total());
      assertEquals(claimed ? 15 : 0, first.getHcoin());
      store.save(first);
      var second = store.find(emu.lunarcore.game.player.Player.class).first();
      for (String name : List.of("gameplay", "questStates", "hcoin")) {
        var field = emu.lunarcore.game.player.Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(f.player(), field.get(second));
      }
      for (int id : List.of(4092314, 4092315))
        assertEquals(
            claimed ? Retcode.QUEST_REWARD_ALREADY_TAKEN : Retcode.SUCC,
            f.player().getQuestManager().claim(id).retcode());
      assertEquals(15, f.player().getHcoin());
      assertEquals(200, total());
      assertEquals(200, f.state(4092315).getProgress());
    } finally {
      server.shutdownNow();
    }
  }
}
