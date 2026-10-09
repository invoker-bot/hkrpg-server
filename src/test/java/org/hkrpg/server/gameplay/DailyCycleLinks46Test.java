package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketGetQuestDataScRsp;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original linked quest graph; tutorial completion and the terminal combat room are fixtures. */
class DailyCycleLinks46Test {
  static final int PARENT = 2100156, ROGUE = 2100161, GRID = 2100162;
  static final Instant TODAY = Instant.parse("2026-10-05T05:00:00Z");
  final UniverseCombatFragmentsTest combat = new UniverseCombatFragmentsTest();
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldRules = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<GameplayDefinitions.DailyPool> oldPool = new ArrayList<>();
  final Map<Integer, DailyCycleCatalog.Cycle> oldCycles = new HashMap<>();
  final Map<Integer, DailyCycleCatalog.Daily> oldDailies = new HashMap<>();
  final Map<Integer, DailyCycleCatalog.Schedule> oldSchedules = new HashMap<>();
  JsonArray fixture;

  @BeforeEach
  void setup() throws Exception {
    combat.setup();
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldRules.addAll(GameData.getFinishWayExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldPool.addAll(GameplayDefinitions.dailyPool);
    oldCycles.putAll(DailyCycleCatalog.cycles);
    oldDailies.putAll(DailyCycleCatalog.dailies);
    oldSchedules.putAll(DailyCycleCatalog.schedules);
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameplayDefinitions.dailyPool.clear();
    DailyCycleCatalog.cycles.clear();
    DailyCycleCatalog.dailies.clear();
    DailyCycleCatalog.schedules.clear();
    try (var in = getClass().getResourceAsStream("/fixtures/daily-cycle-links-4.6.json")) {
      assertNotNull(in);
      fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    assertEquals(32, fixture.size());
    for (var value : fixture) {
      var entry = value.getAsJsonObject();
      var row = entry.getAsJsonObject("row");
      switch (entry.get("kind").getAsString()) {
        case "quest" ->
            GameData.getQuestExcelMap().add(combat.gson.fromJson(row, QuestExcel.class));
        case "rule" ->
            GameData.getFinishWayExcelMap().add(combat.gson.fromJson(row, FinishWayExcel.class));
        case "reward" -> {
          var reward = combat.gson.fromJson(row, RewardExcel.class);
          reward.onLoad();
          GameData.getRewardExcelMap().add(reward);
        }
        case "pool" ->
            GameplayDefinitions.dailyPool.add(
                combat.gson.fromJson(row, GameplayDefinitions.DailyPool.class));
        case "cycle" -> {
          var record = combat.gson.fromJson(row, DailyCycleCatalog.Cycle.class);
          DailyCycleCatalog.cycles.put(record.CycleID(), record);
        }
        case "daily" -> {
          var record = combat.gson.fromJson(row, DailyCycleCatalog.Daily.class);
          DailyCycleCatalog.dailies.put(record.DailyID(), record);
        }
        case "schedule" -> {
          var record = combat.gson.fromJson(row, DailyCycleCatalog.Schedule.class);
          DailyCycleCatalog.schedules.put(record.ID(), record);
        }
        case "prerequisite" -> assertEquals(8023701, row.get("MainMissionID").getAsInt());
        default -> fail("Unknown fixture kind");
      }
    }
    DailyMissionAcceptance46Test.set(combat.p, "level", 10);
    var prerequisite = new QuestState(8023701);
    assertTrue(prerequisite.advance(1, 1, TODAY.minusSeconds(86400).getEpochSecond()));
    combat.p.getGameplay().getMainMissions().put(8023701, prerequisite);
    combat.p.getDailyManager().setClock(Clock.fixed(TODAY, ZoneOffset.UTC));
    combat.p.getMissionManager().setClock(Clock.fixed(TODAY, ZoneOffset.UTC));
    GameplayDefinitions.maps.put(
        1999601, List.of(new GameplayDefinitions.Site(1999601, 1, true, List.of(), 1)));
    combat.p.getDailyManager().ensureDay();
  }

  @AfterEach
  void cleanup() {
    GameData.getQuestExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    oldRules.forEach(GameData.getFinishWayExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
    DailyCycleCatalog.cycles.clear();
    DailyCycleCatalog.cycles.putAll(oldCycles);
    DailyCycleCatalog.dailies.clear();
    DailyCycleCatalog.dailies.putAll(oldDailies);
    DailyCycleCatalog.schedules.clear();
    DailyCycleCatalog.schedules.putAll(oldSchedules);
    combat.cleanup();
  }

  QuestState state(int id) {
    return combat.p.getQuestManager().states().stream()
        .filter(q -> q.getQuestId() == id)
        .findFirst()
        .orElse(null);
  }

  emu.lunarcore.game.battle.Battle win() {
    var battle = combat.battle(combat.monster(8, 1));
    combat.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    return battle;
  }

  void completed(int id) {
    var state = state(id);
    assertNotNull(state, "Missing original linked quest " + id);
    assertEquals(1, state.getProgress());
    assertEquals(QuestState.Phase.FINISHED, state.getPhase());
    assertEquals(TODAY.getEpochSecond(), state.getFinishTime());
  }

  @Test
  void originalFinishQuestDailyIsSelectedAfterItsActualUnlock() {
    assertNotNull(combat.p.getGameplay().getDailyQuests().get(PARENT));
    assertEquals(0, state(PARENT).getProgress());
  }

  @Test
  void originalDailyCycleChildHasItsOwnUnfinishedResetDomain() {
    assertNotNull(state(ROGUE), "Cycle RogueFinishCnt has no visible current state");
    assertEquals(0, state(ROGUE).getProgress());
    assertFalse(combat.p.getQuestStates().containsKey(ROGUE));
    assertNull(state(GRID));
  }

  @Test
  void aLegacyOrdinaryDailyRecordCannotDuplicateTheCurrentDailyView() {
    var legacy = new QuestState(PARENT);
    assertTrue(legacy.advance(1, 1, TODAY.minusSeconds(86400).getEpochSecond()));
    combat.p.getQuestStates().put(PARENT, legacy);
    var rows =
        combat.p.getQuestManager().states().stream().filter(q -> q.getQuestId() == PARENT).toList();
    assertEquals(1, rows.size(), "Ordinary storage duplicates the reset-scoped daily view");
    assertEquals(0, rows.getFirst().getProgress());
    assertEquals(QuestState.Phase.DOING, rows.getFirst().getPhase());
    assertEquals(Retcode.QUEST_NOT_FINISH, combat.p.getDailyManager().claimQuest(PARENT).retcode());
  }

  @Test
  void aRealTerminalUniverseVictoryFinishesTheChildAndItsOriginalOneOfTwoDaily() {
    win();
    completed(ROGUE);
    completed(PARENT);
    assertNull(state(GRID));
  }

  @Test
  void theOriginalEmptyCycleRewardCanBeClosedOnceWithoutInventingItems() {
    win();
    var claim = combat.p.getQuestManager().claim(ROGUE);
    assertEquals(Retcode.SUCC, claim.retcode());
    assertTrue(claim.items().isEmpty());
    assertEquals(QuestState.Phase.CLOSED, state(ROGUE).getPhase());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, combat.p.getQuestManager().claim(ROGUE).retcode());
    completed(PARENT);
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  @Test
  void claimingTheLinkedDailyPaysItsOriginalFiveHundredPointsExactlyOnce() {
    win();
    assertEquals(Retcode.SUCC, combat.p.getDailyManager().claimQuest(PARENT).retcode());
    assertEquals(500, combat.p.getGameplay().getDailyPoints());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN,
        combat.p.getDailyManager().claimQuest(PARENT).retcode());
    assertEquals(500, combat.p.getGameplay().getDailyPoints());
  }

  @Test
  void repeatedSettlementAndQueriesCannotAdvanceOrPayTheLinkedGoalAgain() {
    var battle = win();
    completed(PARENT);
    int saves = combat.p.saves;
    for (int i = 0; i < 12; i++) {
      combat.p.getUniverseManager().onBattleFinish(battle);
      combat.p.getUniverseManager().settleResults();
      completed(ROGUE);
      completed(PARENT);
    }
    assertEquals(saves, combat.p.saves);
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  @Test
  void fourAmResetDoesNotReuseThePriorRunOrOrdinaryCycleRecord() {
    var battle = win();
    var previous = state(ROGUE);
    assertNotNull(previous);
    combat.p.getQuestStates().put(ROGUE, previous);
    combat.p.getDailyManager().setClock(Clock.fixed(TODAY.plusSeconds(86400), ZoneOffset.UTC));
    combat.p.getDailyManager().ensureDay();
    combat.p.getUniverseManager().onBattleFinish(battle);
    var current = state(ROGUE);
    assertNotNull(current);
    assertNotSame(previous, current);
    assertEquals(0, current.getProgress());
    assertEquals(0, state(PARENT).getProgress());
    assertEquals(1, previous.getProgress());
  }

  @Test
  void nativeQuestDataContainsActualCurrentLinkedStatesAndNoUnsupportedGridProgress()
      throws Exception {
    win();
    var protocol = ProtocolBridge.forVersion("4.6.0");
    var core = new PacketGetQuestDataScRsp(combat.p);
    var packet = protocol.outbound(core.getCmdId(), core.getData().toByteArray());
    var response = protocol.clientSchema().parse("GetQuestDataScRsp", packet.body());
    assertTrue(response.getUnknownFields().asMap().isEmpty());
    var descriptor = response.getDescriptorForType();
    var quests = (List<?>) response.getField(descriptor.findFieldByName("quest_list"));
    var actual = new HashMap<Integer, com.google.protobuf.DynamicMessage>();
    for (var raw : quests) {
      var quest = (com.google.protobuf.DynamicMessage) raw;
      assertTrue(quest.getUnknownFields().asMap().isEmpty());
      actual.put(
          (Integer) quest.getField(quest.getDescriptorForType().findFieldByName("id")), quest);
    }
    assertTrue(
        actual.containsKey(ROGUE), "Native response omitted the original current cycle child");
    assertTrue(actual.containsKey(PARENT));
    assertFalse(actual.containsKey(GRID));
    assertEquals(
        1,
        actual
            .get(ROGUE)
            .getField(actual.get(ROGUE).getDescriptorForType().findFieldByName("progress")));
  }

  @Test
  void clientFinishReportsCannotCompleteEitherDailyOrCycleObjective() {
    assertEquals(Retcode.QUEST_STATUS_ERROR, combat.p.getQuestManager().finishByClient(ROGUE));
    assertEquals(Retcode.QUEST_STATUS_ERROR, combat.p.getQuestManager().finishByClient(PARENT));
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  @Test
  void anUnimplementedItemUseAlternativeIsNotSelectedOrGivenProgress() {
    assertNull(state(2100159));
    assertNull(state(2100164));
    assertNull(state(2100165));
  }

  JsonObject original(String kind, String key, int id) {
    return fixture.asList().stream()
        .map(JsonElement::getAsJsonObject)
        .filter(
            r ->
                kind.equals(r.get("kind").getAsString())
                    && r.getAsJsonObject("row").get(key).getAsInt() == id)
        .findFirst()
        .orElseThrow()
        .getAsJsonObject("row")
        .deepCopy();
  }

  DailyCycleQuestState.Window currentWindow() {
    return combat
        .p
        .getGameplay()
        .getDailyCycleQuests()
        .getWindows()
        .get(Long.toString(combat.p.getGameplay().getDailyDay()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "duplicate",
        "unknown",
        "null-list",
        "self",
        "wrong-type",
        "wrong-goal",
        "extra-scalar",
        "text",
        "items"
      })
  void changedOrInvalidParentDefinitionsCannotBorrowTheOriginalDailyProgress(String mode) {
    var raw = original("rule", "ID", PARENT);
    switch (mode) {
      case "duplicate" -> raw.add("ParamIntList", JsonParser.parseString("[2100161,2100161]"));
      case "unknown" -> raw.add("ParamIntList", JsonParser.parseString("[2100161,99999999]"));
      case "null-list" -> raw.add("ParamIntList", JsonNull.INSTANCE);
      case "self" -> raw.add("ParamIntList", JsonParser.parseString("[2100156]"));
      case "wrong-type" -> raw.addProperty("FinishType", "Login");
      case "wrong-goal" -> raw.addProperty("Progress", 2);
      case "extra-scalar" -> raw.addProperty("ParamInt2", 1);
      case "text" -> raw.addProperty("ParamStr1", "Unknown");
      case "items" -> raw.add("ParamItemList", JsonParser.parseString("[{}]"));
    }
    GameData.getFinishWayExcelMap().add(combat.gson.fromJson(raw, FinishWayExcel.class));
    win();
    completed(ROGUE);
    assertEquals(0, combat.p.getGameplay().getDailyQuests().get(PARENT).getProgress());
    assertEquals(
        Retcode.QUEST_STATUS_ERROR, combat.p.getDailyManager().claimQuest(PARENT).retcode());
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "schedule-missing",
        "schedule-end",
        "schedule-begin",
        "schedule-format",
        "schedule-identity",
        "days",
        "weekdays",
        "duplicate-owner",
        "cycle-identity",
        "child-goal",
        "child-type",
        "child-reward",
        "unlock",
        "minimum-level"
      })
  void changedCycleSourcesStopProgressWithoutResettingOrGivingAReward(String mode) {
    var cycle = original("cycle", "CycleID", ROGUE);
    switch (mode) {
      case "days" -> cycle.addProperty("Cycledays", 7);
      case "weekdays" -> cycle.add("WeekDayList", JsonParser.parseString("[1,2,3,4,5,6,6]"));
      case "cycle-identity" -> cycle.addProperty("CycleID", ROGUE + 10);
      case "minimum-level" -> cycle.addProperty("MinLevel", 11);
      case "duplicate-owner" -> {
        var extra = cycle.deepCopy();
        extra.addProperty("CycleID", ROGUE + 10);
        var value = combat.gson.fromJson(extra, DailyCycleCatalog.Cycle.class);
        DailyCycleCatalog.cycles.put(value.CycleID(), value);
      }
      case "schedule-missing" -> DailyCycleCatalog.schedules.remove(22100161);
      case "schedule-end", "schedule-begin", "schedule-format", "schedule-identity" -> {
        var raw = original("schedule", "ID", 22100161);
        if (mode.equals("schedule-end")) raw.addProperty("EndTime", "2026-10-05 13:00:00");
        if (mode.equals("schedule-begin")) raw.addProperty("BeginTime", "2026-10-05 13:00:01");
        if (mode.equals("schedule-format")) raw.addProperty("BeginTime", "2026-02-30 10:00:00");
        if (mode.equals("schedule-identity")) raw.addProperty("ID", 22100162);
        DailyCycleCatalog.schedules.put(
            22100161, combat.gson.fromJson(raw, DailyCycleCatalog.Schedule.class));
      }
      case "child-goal", "child-type" -> {
        var raw = original("rule", "ID", ROGUE);
        if (mode.equals("child-goal")) raw.addProperty("Progress", 2);
        else raw.addProperty("FinishType", "Unknown");
        GameData.getFinishWayExcelMap().add(combat.gson.fromJson(raw, FinishWayExcel.class));
      }
      case "child-reward", "unlock" -> {
        var raw = original("quest", "QuestID", ROGUE);
        if (mode.equals("child-reward")) raw.addProperty("RewardID", 103005);
        else raw.add("UnlockParamList", JsonParser.parseString("[9999999]"));
        GameData.getQuestExcelMap().add(combat.gson.fromJson(raw, QuestExcel.class));
      }
    }
    DailyCycleCatalog.cycles.put(ROGUE, combat.gson.fromJson(cycle, DailyCycleCatalog.Cycle.class));
    var child = currentWindow().getQuests().get(ROGUE);
    win();
    assertEquals(0, child.getProgress());
    assertEquals(0, combat.p.getGameplay().getDailyQuests().get(PARENT).getProgress());
    assertEquals(Retcode.QUEST_NOT_ACCEPT, combat.p.getQuestManager().claim(ROGUE).retcode());
    assertEquals(
        Retcode.QUEST_STATUS_ERROR, combat.p.getDailyManager().claimQuest(PARENT).retcode());
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "alias",
        "negative",
        "overflow",
        "finished-no-goal",
        "finished-no-time",
        "future",
        "yesterday",
        "no-spec",
        "stale-spec",
        "missing-state"
      })
  void malformedChildRecordsCannotBeCountedOrAdvanced(String mode) throws Exception {
    var child = currentWindow().getQuests().get(ROGUE);
    switch (mode) {
      case "alias" -> DailyMissionAcceptance46Test.set(child, "questId", GRID);
      case "negative" -> DailyMissionAcceptance46Test.set(child, "progress", -1);
      case "overflow" -> DailyMissionAcceptance46Test.set(child, "progress", 2);
      case "finished-no-goal", "finished-no-time", "future", "yesterday" -> {
        DailyMissionAcceptance46Test.set(child, "phase", QuestState.Phase.FINISHED);
        DailyMissionAcceptance46Test.set(
            child, "progress", mode.equals("finished-no-goal") ? 0 : 1);
        DailyMissionAcceptance46Test.set(
            child,
            "finishTime",
            switch (mode) {
              case "finished-no-time" -> 0L;
              case "future" -> TODAY.plusSeconds(1).getEpochSecond();
              case "yesterday" -> TODAY.minusSeconds(86400).getEpochSecond();
              default -> TODAY.getEpochSecond();
            });
      }
      case "no-spec" -> currentWindow().getSourceSpecs().remove(ROGUE);
      case "stale-spec" -> currentWindow().getSourceSpecs().put(ROGUE, "unknown");
      case "missing-state" -> currentWindow().getQuests().put(ROGUE, null);
    }
    win();
    assertEquals(0, combat.p.getGameplay().getDailyQuests().get(PARENT).getProgress());
    assertEquals(Retcode.QUEST_NOT_ACCEPT, combat.p.getQuestManager().claim(ROGUE).retcode());
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  @Test
  void aLegacyCompletedGridQuestDoesNotStandInForAnUnimplementedCycleBranch() {
    var legacy = new QuestState(GRID);
    assertTrue(legacy.advance(1, 1, TODAY.getEpochSecond()));
    combat.p.getQuestStates().put(GRID, legacy);
    combat.p.getDailyManager().ensureDay();
    assertEquals(0, state(PARENT).getProgress());
    assertNull(state(GRID));
    assertEquals(Retcode.QUEST_NOT_ACCEPT, combat.p.getQuestManager().claim(GRID).retcode());
  }

  @Test
  void aCompletedParentWithItsRequiredChildRemovedCannotPayActivityPoints() {
    win();
    completed(PARENT);
    currentWindow().getQuests().remove(ROGUE);
    assertEquals(
        Retcode.QUEST_STATUS_ERROR, combat.p.getDailyManager().claimQuest(PARENT).retcode());
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  @Test
  void aChangedParentRewardAfterCompletionCannotPayItsReplacement() {
    win();
    var raw = original("quest", "QuestID", PARENT);
    raw.addProperty("RewardID", 103002);
    GameData.getQuestExcelMap().add(combat.gson.fromJson(raw, QuestExcel.class));
    assertEquals(
        Retcode.QUEST_STATUS_ERROR, combat.p.getDailyManager().claimQuest(PARENT).retcode());
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  @Test
  void claimReservationsRemainBusyWithoutAnAutomaticSecondPayment() {
    win();
    assertTrue(state(ROGUE).reserveReward());
    assertTrue(state(PARENT).reserveReward());
    assertEquals(Retcode.SYSTEM_BUSY, combat.p.getQuestManager().claim(ROGUE).retcode());
    assertEquals(Retcode.SYSTEM_BUSY, combat.p.getDailyManager().claimQuest(PARENT).retcode());
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  @Test
  void aBackwardsClockCannotReplayThePriorDailyDomain() {
    win();
    combat.p.getDailyManager().setClock(Clock.fixed(TODAY.plusSeconds(86400), ZoneOffset.UTC));
    combat.p.getDailyManager().ensureDay();
    long day = combat.p.getGameplay().getDailyDay();
    combat.p.getDailyManager().setClock(Clock.fixed(TODAY, ZoneOffset.UTC));
    assertNull(state(ROGUE));
    assertEquals(day, combat.p.getGameplay().getDailyDay());
    assertEquals(Retcode.QUEST_NOT_ACCEPT, combat.p.getQuestManager().claim(ROGUE).retcode());
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  @Test
  void twoActualPlayerMorphiaReloadsPreservePartialThenClosedSourceDomains() throws Exception {
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "source_daily_cycle_links");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, QuestState.class);
      for (int iteration = 0; iteration < 2; iteration++) {
        if (iteration == 1) {
          win();
          assertEquals(Retcode.SUCC, combat.p.getQuestManager().claim(ROGUE).retcode());
          assertEquals(Retcode.SUCC, combat.p.getDailyManager().claimQuest(PARENT).retcode());
        }
        var base = new emu.lunarcore.game.player.Player();
        DailyMissionAcceptance46Test.set(base, "uid", 123456805);
        DailyMissionAcceptance46Test.set(base, "name", "Cycle source fixture");
        DailyMissionAcceptance46Test.set(base, "level", 10);
        DailyMissionAcceptance46Test.set(base, "gameplay", combat.p.getGameplay());
        store.save(base);
        var restored =
            store
                .find(emu.lunarcore.game.player.Player.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", 123456805))
                .first();
        assertNotNull(restored);
        DailyMissionAcceptance46Test.set(combat.p, "gameplay", restored.getGameplay());
        assertEquals(iteration == 0 ? 0 : 500, combat.p.getGameplay().getDailyPoints());
        assertEquals(
            iteration == 0 ? QuestState.Phase.DOING : QuestState.Phase.CLOSED,
            state(ROGUE).getPhase());
        assertEquals(
            iteration == 0 ? QuestState.Phase.DOING : QuestState.Phase.CLOSED,
            state(PARENT).getPhase());
        int saves = combat.p.saves;
        for (int repeat = 0; repeat < 12; repeat++) state(ROGUE);
        assertEquals(saves, combat.p.saves);
        if (iteration == 1) {
          assertEquals(
              Retcode.QUEST_REWARD_ALREADY_TAKEN,
              combat.p.getQuestManager().claim(ROGUE).retcode());
          assertEquals(
              Retcode.QUEST_REWARD_ALREADY_TAKEN,
              combat.p.getDailyManager().claimQuest(PARENT).retcode());
        }
        assertEquals(1, store.find(emu.lunarcore.game.player.Player.class).count());
      }
    } finally {
      server.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"lose", "quit", "nonterminal"})
  void onlyAnActualTerminalVictoryCountsForThisCycle(String mode) {
    if (mode.equals("nonterminal"))
      GameplayDefinitions.maps.put(
          1999601, List.of(new GameplayDefinitions.Site(1999601, 1, true, List.of(2), 1)));
    var battle = combat.battle(combat.monster(8, 1));
    combat.finish(
        battle,
        mode.equals("lose")
            ? BattleEndStatus.BATTLE_END_LOSE
            : mode.equals("quit")
                ? BattleEndStatus.BATTLE_END_QUIT
                : BattleEndStatus.BATTLE_END_WIN);
    assertEquals(0, state(ROGUE).getProgress());
    assertEquals(0, state(PARENT).getProgress());
    assertEquals(Retcode.QUEST_NOT_FINISH, combat.p.getQuestManager().claim(ROGUE).retcode());
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  @Test
  void concurrentTerminalRetriesAndClaimsKeepOneOriginalCompletionAndPayment() throws Exception {
    var battle = combat.battle(combat.monster(8, 1));
    battle.setResult(BattleEndStatus.BATTLE_END_WIN);
    for (var enemy : battle.getNpcMonsters()) {
      combat.p.getScene().getEntities().remove(enemy.getEntityId());
      enemy.setEntityId(0);
    }
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<java.util.concurrent.Callable<Void>>();
      for (int i = 0; i < 12; i++)
        calls.add(
            () -> {
              combat.p.getUniverseManager().onBattleFinish(battle);
              return null;
            });
      for (var response : workers.invokeAll(calls)) response.get();
      completed(ROGUE);
      completed(PARENT);
      var claims =
          workers.invokeAll(
              List.<java.util.concurrent.Callable<Retcode>>of(
                  () -> combat.p.getDailyManager().claimQuest(PARENT).retcode(),
                  () -> combat.p.getDailyManager().claimQuest(PARENT).retcode()));
      var results = new HashSet<Retcode>();
      for (var claim : claims) results.add(claim.get());
      assertEquals(Set.of(Retcode.SUCC, Retcode.QUEST_REWARD_ALREADY_TAKEN), results);
    }
    assertEquals(500, combat.p.getGameplay().getDailyPoints());
    assertEquals(1, combat.p.getGameplay().getRogueWins().get(19996));
  }

  @Test
  void theFourAmBoundaryResetsBothObjectivesExactlyOnce() {
    win();
    var previous = state(ROGUE);
    combat
        .p
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T19:59:59Z"), ZoneOffset.UTC));
    assertSame(previous, state(ROGUE));
    combat
        .p
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T20:00:00Z"), ZoneOffset.UTC));
    var fresh = state(ROGUE);
    assertNotSame(previous, fresh);
    assertEquals(0, fresh.getProgress());
    assertEquals(0, state(PARENT).getProgress());
    assertEquals(1, previous.getProgress());
    assertEquals(2, combat.p.getGameplay().getDailyCycleQuests().getWindows().size());
  }

  @Test
  void anOldDomainMovedUnderTodaysMapKeyIsRejectedByItsSavedDay() {
    var previous = currentWindow();
    combat.p.getDailyManager().setClock(Clock.fixed(TODAY.plusSeconds(86400), ZoneOffset.UTC));
    combat.p.getDailyManager().ensureDay();
    combat
        .p
        .getGameplay()
        .getDailyCycleQuests()
        .getWindows()
        .put(Long.toString(combat.p.getGameplay().getDailyDay()), previous);
    assertNull(state(ROGUE));
    win();
    assertEquals(0, previous.getQuests().get(ROGUE).getProgress());
    assertEquals(0, combat.p.getGameplay().getDailyQuests().get(PARENT).getProgress());
  }

  @Test
  void interruptedClaimRecordsRemainInYesterdayWithoutFinishingTodaysObjectives() {
    win();
    var previous = currentWindow();
    assertTrue(state(ROGUE).reserveReward());
    combat.p.getDailyManager().setClock(Clock.fixed(TODAY.plusSeconds(86400), ZoneOffset.UTC));
    combat.p.getDailyManager().ensureDay();
    assertEquals(QuestState.Phase.CLAIMING, previous.getQuests().get(ROGUE).getPhase());
    assertEquals(QuestState.Phase.DOING, state(ROGUE).getPhase());
    assertEquals(Retcode.QUEST_NOT_FINISH, combat.p.getQuestManager().claim(ROGUE).retcode());
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  @ParameterizedTest
  @ValueSource(strings = {"tutorial", "level", "missing-table", "bad-daily-owner", "daily-level"})
  void invalidInitialEligibilityDoesNotSelectTheLinkedDaily(String mode) throws Exception {
    var fresh =
        new emu.lunarcore.game.player.Player() {
          @Override
          public void save() {}
        };
    DailyMissionAcceptance46Test.set(fresh, "level", mode.equals("level") ? 9 : 10);
    fresh.getDailyManager().setClock(Clock.fixed(TODAY, ZoneOffset.UTC));
    if (!mode.equals("tutorial"))
      fresh
          .getGameplay()
          .getMainMissions()
          .put(8023701, combat.p.getGameplay().getMainMissions().get(8023701));
    if (mode.equals("missing-table")) DailyCycleCatalog.cycles.clear();
    if (mode.equals("bad-daily-owner")) {
      var raw = original("daily", "DailyID", PARENT);
      raw.addProperty("DailyID", PARENT + 1);
      DailyCycleCatalog.dailies.put(
          PARENT, combat.gson.fromJson(raw, DailyCycleCatalog.Daily.class));
    }
    if (mode.equals("daily-level")) {
      var raw = original("daily", "DailyID", PARENT);
      raw.addProperty("MinLevel", 11);
      DailyCycleCatalog.dailies.put(
          PARENT, combat.gson.fromJson(raw, DailyCycleCatalog.Daily.class));
    }
    fresh.getDailyManager().ensureDay();
    assertNull(fresh.getGameplay().getDailyQuests().get(PARENT));
    assertEquals(0, fresh.getGameplay().getDailyPoints());
  }

  @Test
  void cycleCatalogLoadsOriginalRowsAndAllowsOlderResourcesWithoutTheseOptionalTables(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var excel = java.nio.file.Files.createDirectories(directory.resolve("ExcelOutput"));
    for (var names :
        Map.of("cycle", "CycleQuest", "daily", "DailyQuest", "schedule", "ScheduleDataQuest")
            .entrySet()) {
      var rows = new JsonArray();
      for (var value : fixture) {
        var entry = value.getAsJsonObject();
        if (names.getKey().equals(entry.get("kind").getAsString())) rows.add(entry.get("row"));
      }
      java.nio.file.Files.writeString(
          excel.resolve(names.getValue() + ".json"), rows.toString(), StandardCharsets.UTF_8);
    }
    DailyCycleCatalog.load(directory);
    assertEquals(5, DailyCycleCatalog.cycles.size());
    assertEquals(2, DailyCycleCatalog.dailies.size());
    assertEquals(5, DailyCycleCatalog.schedules.size());
    var old = java.nio.file.Files.createDirectory(directory.resolve("old"));
    DailyCycleCatalog.load(old);
    assertTrue(DailyCycleCatalog.cycles.isEmpty());
    assertTrue(DailyCycleCatalog.dailies.isEmpty());
    assertTrue(DailyCycleCatalog.schedules.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"corrupt", "duplicate"})
  void existingCorruptOrDuplicateCycleTablesAreReportedInsteadOfInventingDefinitions(
      String mode, @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
      throws Exception {
    var excel = java.nio.file.Files.createDirectories(directory.resolve("ExcelOutput"));
    String contents;
    if (mode.equals("corrupt")) contents = "[broken";
    else {
      var rows = new JsonArray();
      rows.add(original("cycle", "CycleID", ROGUE));
      rows.add(original("cycle", "CycleID", ROGUE));
      contents = rows.toString();
    }
    java.nio.file.Files.writeString(
        excel.resolve("CycleQuest.json"), contents, StandardCharsets.UTF_8);
    assertThrows(IllegalStateException.class, () -> DailyCycleCatalog.load(directory));
  }
}
