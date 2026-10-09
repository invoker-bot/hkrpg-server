package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original weighted waves; tutorial completion is an explicit domain prerequisite fixture. */
class RogueChestKillQuestTest {
  final RogueMonsterSelectionTest encounter = new RogueMonsterSelectionTest();
  List<QuestExcel> oldQuests;
  List<FinishWayExcel> oldRules;
  List<RewardExcel> oldRewards;
  List<MainMissionExcel> oldMains;
  ItemExcel oldItem;
  final Map<Integer, JsonObject> rules = new HashMap<>();
  Set<Integer> wanted;
  final BattleService service =
      new BattleService(null) {
        protected void grantDrops(Battle ignored) {}
      };

  @BeforeEach
  void setup() throws Exception {
    encounter.setup();
    oldQuests = new ArrayList<>(GameData.getQuestExcelMap().values());
    oldRules = new ArrayList<>(GameData.getFinishWayExcelMap().values());
    oldRewards = new ArrayList<>(GameData.getRewardExcelMap().values());
    oldMains = new ArrayList<>(GameData.getMainMissionExcelMap().values());
    oldItem = GameData.getItemExcelMap().get(1);
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameData.getMainMissionExcelMap().values().clear();
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-chest-kill-quests-4.6.json")) {
      var records =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(41, records.size());
      for (var raw : records) {
        var spec = raw.getAsJsonObject();
        var row = spec.getAsJsonObject("row").deepCopy();
        switch (spec.get("kind").getAsString()) {
          case "quest" ->
              GameData.getQuestExcelMap().add(encounter.gson.fromJson(row, QuestExcel.class));
          case "rule" -> {
            var r = encounter.gson.fromJson(row, FinishWayExcel.class);
            GameData.getFinishWayExcelMap().add(r);
            rules.put(r.getId(), row);
          }
          case "reward" -> {
            var r = encounter.gson.fromJson(row, RewardExcel.class);
            r.onLoad();
            GameData.getRewardExcelMap().add(r);
          }
          case "item" -> {
            encounter.hash(row, "ItemName");
            GameData.getItemExcelMap().add(encounter.gson.fromJson(row, ItemExcel.class));
          }
          case "main" ->
              GameData.getMainMissionExcelMap()
                  .add(encounter.gson.fromJson(row, MainMissionExcel.class));
          default -> {}
        }
      }
    }
    wanted = Set.copyOf(GameData.getFinishWayExcelMap().get(4092320).getParamIntList());
    var tutorial = new QuestState(1000201);
    assertTrue(tutorial.advance(1, 1, 1));
    encounter.player.getGameplay().getMainMissions().put(1000201, tutorial);
    assertTrue(encounter.player.getQuestManager().onMissionProgress());
    assertEquals(QuestState.Phase.FINISHED, state(4000001).getPhase());
  }

  @AfterEach
  void restore() {
    GameData.getQuestExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    oldRules.forEach(GameData.getFinishWayExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getItemExcelMap().keySet().remove(1);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
    encounter.restore();
  }

  QuestState state(int id) {
    return encounter.player.getQuestStates().get(id);
  }

  Battle battle(boolean trotter) {
    for (int i = 0; i < 300; i++) {
      encounter.begin();
      var actor = encounter.actor();
      var stage = GameData.getStageExcelMap().get(actor.getEventId());
      boolean has =
          stage.getMonsterWaves().stream().anyMatch(w -> w.intStream().anyMatch(wanted::contains));
      if (has == trotter) {
        var b = new Battle(encounter.player, encounter.player.getCurrentLineup(), List.of(actor));
        encounter.player.setBattle(b);
        return b;
      }
    }
    throw new AssertionError("Original encounter category did not become reachable");
  }

  List<MissionBattleKills.Monster> reports(Battle battle, int death) {
    var rows = new ArrayList<MissionBattleKills.Monster>();
    int entity = 5001;
    for (var wave : battle.getWaves())
      for (int id : wave.getMonsters())
        rows.add(
            new MissionBattleKills.Monster(
                entity++, id, wave.getStage().getId(), wanted.contains(id) ? death : 1));
    return rows;
  }

  void finish(Battle battle, List<MissionBattleKills.Monster> rows) {
    assertNotNull(
        service.finishBattle(
            encounter.player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            rows));
  }

  void kill(int count) {
    for (int i = 0; i < count; i++) {
      var b = battle(true);
      finish(b, reports(b, 1));
    }
  }

  @Test
  void oneActualOriginalTrotterDeathFinishesLowerGoalAndPaysFiveJades() {
    kill(1);
    assertEquals(1, state(4092320).getProgress());
    assertEquals(1, state(4092321).getProgress());
    assertEquals(QuestState.Phase.FINISHED, state(4092320).getPhase());
    assertEquals(Retcode.SUCC, encounter.player.getQuestManager().claim(4092320).retcode());
    assertEquals(5, encounter.player.getHcoin());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN,
        encounter.player.getQuestManager().claim(4092320).retcode());
  }

  @Test
  void sixtySixActualTrotterDeathsFinishBothSourceGoalsAndPayFifteen() {
    kill(66);
    assertEquals(66, state(4092321).getProgress());
    assertEquals(QuestState.Phase.FINISHED, state(4092321).getPhase());
    for (int id : List.of(4092320, 4092321))
      assertEquals(Retcode.SUCC, encounter.player.getQuestManager().claim(id).retcode());
    assertEquals(15, encounter.player.getHcoin());
  }

  @Test
  void sixtyFiveTrottersCannotFinishLargerGoal() {
    kill(65);
    assertEquals(1, state(4092320).getProgress());
    assertEquals(65, state(4092321).getProgress());
    assertEquals(QuestState.Phase.DOING, state(4092321).getPhase());
    assertEquals(
        Retcode.QUEST_NOT_FINISH, encounter.player.getQuestManager().claim(4092321).retcode());
  }

  @Test
  void originalOrdinaryDeathsDoNotAdvanceTrotterGoals() {
    var b = battle(false);
    finish(b, reports(b, 1));
    assertEquals(0, state(4092320).getProgress());
    assertEquals(0, state(4092321).getProgress());
  }

  @Test
  void escapedTrotterWithOtherReportedDeathsDoesNotCount() {
    var b = battle(true);
    finish(b, reports(b, 3));
    assertEquals(0, state(4092320).getProgress());
    assertEquals(0, state(4092321).getProgress());
  }

  @Test
  void sourceTrotterVictoryWithoutReportsDoesNotInventDeath() {
    var b = battle(true);
    finish(b, List.of());
    assertEquals(0, state(4092320).getProgress());
  }

  @Test
  void forgedCompletedTrotterGoalCannotClaimWithoutActualHistory() {
    assertTrue(state(4092320).advance(1, 1, 1));
    assertNotEquals(Retcode.SUCC, encounter.player.getQuestManager().claim(4092320).retcode());
    assertEquals(0, encounter.player.getHcoin());
  }

  @Test
  void unsupportedSourceFilterCannotClaimFromForgedProgress() {
    var row = rules.get(4092320).deepCopy();
    row.addProperty("ParamType", "NoPara");
    GameData.getFinishWayExcelMap().add(encounter.gson.fromJson(row, FinishWayExcel.class));
    assertTrue(state(4092320).advance(1, 1, 1));
    assertNotEquals(Retcode.SUCC, encounter.player.getQuestManager().claim(4092320).retcode());
    assertEquals(0, encounter.player.getHcoin());
  }

  @Test
  void detailsChangedAfterCaptureCannotBeMadePlausibleByUnrelatedOrdinaryDeaths() throws Exception {
    var b = battle(false);
    RogueMonsterSelectionTest.set(
        encounter.player.getGameplay().getRogueBattleDeaths(), "counts", Map.of(8002060, 1));
    finish(b, reports(b, 1));
    assertEquals(0, state(4092320).getProgress());
    assertNull(RogueChestKillQuests.state(encounter.player, 4092320));
    assertNotEquals(Retcode.SUCC, encounter.player.getQuestManager().claim(4092320).retcode());
    assertEquals(0, encounter.player.getHcoin());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void liveOrSkillKilledSourceTrotterUsesActualDeathCategory(int death) {
    var b = battle(true);
    finish(b, reports(b, death));
    assertEquals(death == 2 ? 1 : 0, state(4092320).getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrong-stage",
        "unknown-trotter",
        "duplicate-entity",
        "over-roster",
        "invalid-death"
      })
  void malformedReportBatchCannotCreditValidPrefix(String damage) {
    var b = battle(true);
    var rows = new ArrayList<>(reports(b, 1));
    var first = rows.getFirst();
    switch (damage) {
      case "wrong-stage" ->
          rows.add(new MissionBattleKills.Monster(9001, first.monsterId(), first.stageId() + 1, 1));
      case "unknown-trotter" ->
          rows.add(new MissionBattleKills.Monster(9001, 8002051, first.stageId(), 1));
      case "duplicate-entity" ->
          rows.add(
              new MissionBattleKills.Monster(
                  first.entityId(), first.monsterId(), first.stageId(), 1));
      case "over-roster" ->
          rows.add(new MissionBattleKills.Monster(9001, first.monsterId(), first.stageId(), 1));
      case "invalid-death" ->
          rows.add(new MissionBattleKills.Monster(9001, first.monsterId(), first.stageId(), 4));
    }
    finish(b, rows);
    assertEquals(0, state(4092320).getProgress());
    assertEquals(0, state(4092321).getProgress());
    assertEquals(0, encounter.player.getGameplay().getRogueBattleDeaths().total());
  }

  @ParameterizedTest
  @ValueSource(strings = {"BATTLE_END_LOSE", "BATTLE_END_QUIT"})
  void sourceFailureOrQuitDoesNotCountEvenWithTrotterDeathReport(String end) {
    var b = battle(true);
    assertNotNull(
        service.finishBattle(
            encounter.player,
            b.getId(),
            b.getStage().getId(),
            BattleEndStatus.valueOf(end),
            BattleStatistics.newInstance(),
            reports(b, 1)));
    assertEquals(0, state(4092320).getProgress());
    assertEquals(0, encounter.player.getGameplay().getRogueBattleDeaths().total());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-history",
        "null-counts",
        "negative-count",
        "zero-count",
        "unknown-monster",
        "null-count",
        "negative-id",
        "overflow"
      })
  void damagedClassifiedHistoryCannotBeRepairedByNewDeaths(String damage) throws Exception {
    var details = encounter.player.getGameplay().getRogueBattleDeaths();
    switch (damage) {
      case "null-history" ->
          RogueMonsterSelectionTest.set(encounter.player.getGameplay(), "rogueBattleDeaths", null);
      case "null-counts" -> RogueMonsterSelectionTest.set(details, "counts", null);
      case "negative-count" ->
          RogueMonsterSelectionTest.set(details, "counts", Map.of(8002060, -1));
      case "zero-count" -> RogueMonsterSelectionTest.set(details, "counts", Map.of(8002060, 0));
      case "unknown-monster" ->
          RogueMonsterSelectionTest.set(details, "counts", Map.of(1999999999, 1));
      case "negative-id" -> RogueMonsterSelectionTest.set(details, "counts", Map.of(-1, 1));
      case "null-count" -> {
        var map = new HashMap<Integer, Integer>();
        map.put(8002060, null);
        RogueMonsterSelectionTest.set(details, "counts", map);
      }
      case "overflow" ->
          RogueMonsterSelectionTest.set(
              details, "counts", Map.of(8002060, Integer.MAX_VALUE, 8002070, 1));
    }
    kill(1);
    assertEquals(0, state(4092320).getProgress());
    assertNull(RogueChestKillQuests.state(encounter.player, 4092320));
    assertNotEquals(Retcode.SUCC, encounter.player.getQuestManager().claim(4092320).retcode());
    assertEquals(0, encounter.player.getHcoin());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "int1",
        "int2",
        "int3",
        "text",
        "item",
        "duplicate-target",
        "unknown-target",
        "threshold"
      })
  void unknownSourceRuleShapesHoldActualEarnedProgress(String damage) {
    var row = rules.get(4092320).deepCopy();
    switch (damage) {
      case "int1" -> row.addProperty("ParamInt1", 1);
      case "int2" -> row.addProperty("ParamInt2", 1);
      case "int3" -> row.addProperty("ParamInt3", 1);
      case "text" -> row.addProperty("ParamStr1", "unrecognized");
      case "item" -> {
        var list = new JsonArray();
        list.add(new JsonObject());
        row.add("ParamItemList", list);
      }
      case "duplicate-target" -> row.getAsJsonArray("ParamIntList").add(8002060);
      case "unknown-target" ->
          row.getAsJsonArray("ParamIntList").set(0, new JsonPrimitive(1999999999));
      case "threshold" -> row.addProperty("Progress", 2);
    }
    GameData.getFinishWayExcelMap().add(encounter.gson.fromJson(row, FinishWayExcel.class));
    kill(1);
    assertEquals(0, state(4092320).getProgress());
    assertNull(RogueChestKillQuests.state(encounter.player, 4092320));
    assertNotEquals(Retcode.SUCC, encounter.player.getQuestManager().claim(4092320).retcode());
    assertEquals(0, encounter.player.getHcoin());
  }

  @Test
  void repeatedAcceptedSettlementCannotAddSecondTrotter() {
    var b = battle(true);
    var rows = reports(b, 1);
    finish(b, rows);
    assertNull(
        service.finishBattle(
            encounter.player,
            b.getId(),
            b.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            rows));
    assertEquals(1, state(4092321).getProgress());
  }

  @Test
  void concurrentSameSettlementRecordsOneActualTrotter() throws Exception {
    var b = battle(true);
    var rows = reports(b, 1);
    var ready = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(24)) {
      var jobs = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 24; i++)
        jobs.add(
            pool.submit(
                () -> {
                  ready.await();
                  return service.finishBattle(
                          encounter.player,
                          b.getId(),
                          b.getStage().getId(),
                          BattleEndStatus.BATTLE_END_WIN,
                          BattleStatistics.newInstance(),
                          rows)
                      != null;
                }));
      ready.countDown();
      int successes = 0;
      for (var job : jobs) if (job.get(20, TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes);
    }
    assertEquals(1, state(4092321).getProgress());
  }

  @Test
  void concurrentRewardClaimsPayFiveOnce() throws Exception {
    kill(1);
    var ready = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(24)) {
      var jobs = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 24; i++)
        jobs.add(
            pool.submit(
                () -> {
                  ready.await();
                  return encounter.player.getQuestManager().claim(4092320).retcode()
                      == Retcode.SUCC;
                }));
      ready.countDown();
      int successes = 0;
      for (var job : jobs) if (job.get(20, TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes);
    }
    assertEquals(5, encounter.player.getHcoin());
  }

  @Test
  void queryRecoversSavedClassifiedHistoryWithoutAddingAnotherDeath() {
    kill(1);
    var before = encounter.player.getGameplay().getRogueBattleDeaths().snapshot();
    for (int id : List.of(4092320, 4092321))
      encounter.player.getQuestStates().put(id, new QuestState(id));
    encounter.player.getQuestManager().states();
    assertEquals(1, state(4092320).getProgress());
    assertEquals(1, state(4092321).getProgress());
    assertEquals(before, encounter.player.getGameplay().getRogueBattleDeaths().snapshot());
  }

  @Test
  void oldAggregateHistoryCannotInferTrotterCounts() {
    assertTrue(encounter.player.getGameplay().getRogueBattleKills().record(66));
    encounter.player.getQuestManager().states();
    assertEquals(0, state(4092320).getProgress());
    assertEquals(0, state(4092321).getProgress());
    assertEquals(0, encounter.player.getGameplay().getRogueBattleDeaths().total());
  }

  @Test
  void missingTutorialHoldsHistoryUntilPrerequisiteIsRestored() {
    encounter.player.getGameplay().getMainMissions().remove(1000201);
    encounter.player.getQuestStates().remove(4000001);
    kill(1);
    assertEquals(0, state(4092320).getProgress());
    assertTrue(
        encounter.player.getQuestManager().states().stream()
            .noneMatch(q -> q.getQuestId() == 4092320 || q.getQuestId() == 4092321));
    var tutorial = new QuestState(1000201);
    assertTrue(tutorial.advance(1, 1, 1));
    encounter.player.getGameplay().getMainMissions().put(1000201, tutorial);
    assertTrue(encounter.player.getQuestManager().onMissionProgress());
    assertEquals(1, state(4092320).getProgress());
    assertEquals(1, state(4092321).getProgress());
  }

  @Test
  void classifiedDeathSavePrecedesGoalProgressRefresh() throws Exception {
    var observed = new ArrayList<Integer>();
    var prior = encounter.player;
    var observer =
        new RogueMonsterSelectionTest.Explorer() {
          public void save() {
            super.save();
            if (getGameplay().getRogueBattleDeaths().count(wanted).orElse(0) > 0)
              observed.add(getQuestStates().get(4092320).getProgress());
          }
        };
    for (String field : List.of("name", "gameplay", "questStates")) {
      var f = emu.lunarcore.game.player.Player.class.getDeclaredField(field);
      f.setAccessible(true);
      f.set(observer, f.get(prior));
    }
    encounter.player = observer;
    kill(1);
    assertEquals(0, observed.getFirst());
    assertEquals(1, observed.getLast());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoPlayerMorphiaReadsPreserveSixtySixTrottersAndRewardState(boolean claimed)
      throws Exception {
    kill(66);
    if (claimed)
      for (int id : List.of(4092320, 4092321))
        assertEquals(Retcode.SUCC, encounter.player.getQuestManager().claim(id).retcode());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "trotter-achievements");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      RogueMonsterSelectionTest.set(document, "uid", 123456786);
      for (String field : List.of("name", "gameplay", "questStates", "hcoin")) {
        var f = emu.lunarcore.game.player.Player.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(document, f.get(encounter.player));
      }
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored = store.find(emu.lunarcore.game.player.Player.class).first();
        assertEquals(66, restored.getGameplay().getRogueBattleDeaths().count(wanted).orElseThrow());
        assertEquals(claimed ? 15 : 0, restored.getHcoin());
        for (int id : List.of(4092320, 4092321))
          assertEquals(
              claimed ? QuestState.Phase.CLOSED : QuestState.Phase.FINISHED,
              restored.getQuestStates().get(id).getPhase());
        store.save(restored);
        if (i == 1)
          for (String field : List.of("gameplay", "questStates", "hcoin")) {
            var f = emu.lunarcore.game.player.Player.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(encounter.player, f.get(restored));
          }
      }
      for (int id : List.of(4092320, 4092321))
        assertEquals(
            claimed ? Retcode.QUEST_REWARD_ALREADY_TAKEN : Retcode.SUCC,
            encounter.player.getQuestManager().claim(id).retcode());
      assertEquals(15, encounter.player.getHcoin());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void quarantineSurvivesPlayerMorphiaAndPreservesDamagedCountForReconciliation() throws Exception {
    var b = battle(false);
    RogueMonsterSelectionTest.set(
        encounter.player.getGameplay().getRogueBattleDeaths(), "counts", Map.of(8002060, 1));
    finish(b, reports(b, 1));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "trotter-quarantine");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      RogueMonsterSelectionTest.set(document, "uid", 123456787);
      RogueMonsterSelectionTest.set(document, "name", encounter.player.getName());
      RogueMonsterSelectionTest.set(document, "gameplay", encounter.player.getGameplay());
      store.save(document);
      var restored = store.find(emu.lunarcore.game.player.Player.class).first();
      assertFalse(restored.getGameplay().getRogueBattleDeaths().valid());
      var f = RogueBattleDeathState.class.getDeclaredField("counts");
      f.setAccessible(true);
      assertEquals(Map.of(8002060, 1), f.get(restored.getGameplay().getRogueBattleDeaths()));
    } finally {
      mongo.shutdownNow();
    }
  }
}
