package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original daily objective; the combat room and its terminal map are explicit test fixtures. */
class DailyRogueFinishTest {
  static final int QUEST = 2100150;
  final UniverseCombatFragmentsTest combat = new UniverseCombatFragmentsTest();
  List<GameplayDefinitions.DailyPool> oldPool;
  QuestExcel oldQuest;
  FinishWayExcel oldRule;
  RewardExcel oldReward;
  FloorInfo oldFloor;
  String floorKey;
  JsonArray fixture;

  @BeforeEach
  void setup() throws Exception {
    combat.setup();
    try (var in = getClass().getResourceAsStream("/fixtures/daily-rogue-finish-4.6.json")) {
      assertNotNull(in);
      fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    oldPool = new ArrayList<>(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    oldQuest = GameData.getQuestExcelMap().get(QUEST);
    oldRule = GameData.getFinishWayExcelMap().get(QUEST);
    oldReward = GameData.getRewardExcelMap().get(103005);
    for (var value : fixture) {
      var record = value.getAsJsonObject();
      var row = record.getAsJsonObject("row");
      switch (record.get("kind").getAsString()) {
        case "pool" ->
            GameplayDefinitions.dailyPool.add(
                combat.gson.fromJson(row, GameplayDefinitions.DailyPool.class));
        case "quest" ->
            GameData.getQuestExcelMap().add(combat.gson.fromJson(row, QuestExcel.class));
        case "rule" ->
            GameData.getFinishWayExcelMap().add(combat.gson.fromJson(row, FinishWayExcel.class));
        case "reward" -> {
          var reward = combat.gson.fromJson(row, RewardExcel.class);
          reward.onLoad();
          GameData.getRewardExcelMap().add(reward);
        }
      }
    }
    floorKey = "P" + combat.p.getPlaneId() + "_F" + combat.p.getFloorId();
    oldFloor = GameData.getFloorInfos().put(floorKey, new FloorInfo());
    GameplayDefinitions.maps.put(
        1999601, List.of(new GameplayDefinitions.Site(1999601, 1, true, List.of(), 1)));
    combat
        .p
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));
    combat.p.getDailyManager().ensureDay();
  }

  @AfterEach
  void cleanup() {
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
    GameData.getQuestExcelMap().keySet().remove(QUEST);
    if (oldQuest != null) GameData.getQuestExcelMap().add(oldQuest);
    GameData.getFinishWayExcelMap().keySet().remove(QUEST);
    if (oldRule != null) GameData.getFinishWayExcelMap().add(oldRule);
    GameData.getRewardExcelMap().keySet().remove(103005);
    if (oldReward != null) GameData.getRewardExcelMap().add(oldReward);
    if (oldFloor == null) GameData.getFloorInfos().remove(floorKey);
    else GameData.getFloorInfos().put(floorKey, oldFloor);
    combat.cleanup();
  }

  QuestState state() {
    return combat.p.getGameplay().getDailyQuests().get(QUEST);
  }

  @Test
  void originalRogueCompletionObjectiveIsSelectedWithoutAnInventedProgressOrReward() {
    assertNotNull(state(), "Original RogueFinishCnt objective not selected");
    assertEquals(0, state().getProgress());
    assertEquals(QuestState.Phase.DOING, state().getPhase());
    assertEquals(0, combat.p.getGameplay().getDailyPoints());
  }

  emu.lunarcore.game.battle.Battle win() {
    var battle = combat.battle(combat.monster(8, 1));
    combat.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    return battle;
  }

  void complete() {
    assertEquals(
        GameplayState.RogueRun.Phase.FINISHED, combat.p.getGameplay().getRogue().getPhase());
    assertEquals(1, state().getProgress());
    assertEquals(QuestState.Phase.FINISHED, state().getPhase());
  }

  @Test
  void actualTerminalRoomVictoryFinishesAndGrantsTheOriginalFiveHundredPointsOnce() {
    win();
    complete();
    assertEquals(Retcode.SUCC, combat.p.getDailyManager().claimQuest(QUEST).retcode());
    assertEquals(500, combat.p.getGameplay().getDailyPoints());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, combat.p.getDailyManager().claimQuest(QUEST).retcode());
    assertEquals(500, combat.p.getGameplay().getDailyPoints());
  }

  @Test
  void aNonterminalClearedRoomCannotCompleteAnEntireUniverse() {
    GameplayDefinitions.maps.put(
        1999601, List.of(new GameplayDefinitions.Site(1999601, 1, true, List.of(2), 1)));
    win();
    assertTrue(combat.p.getGameplay().getRogue().getCleared().contains(1));
    assertEquals(
        GameplayState.RogueRun.Phase.EXPLORING, combat.p.getGameplay().getRogue().getPhase());
    assertEquals(0, state().getProgress());
  }

  @ParameterizedTest
  @EnumSource(
      value = BattleEndStatus.class,
      names = {"BATTLE_END_LOSE", "BATTLE_END_QUIT"})
  void defeatAndQuitDoNotCompleteTheDailyObjective(BattleEndStatus outcome) {
    combat.finish(combat.battle(combat.monster(8, 1)), outcome);
    assertEquals(0, state().getProgress());
    assertEquals(QuestState.Phase.DOING, state().getPhase());
    assertTrue(combat.p.getGameplay().getRogueWins().isEmpty());
  }

  @Test
  void otherLivingContentGroupMonstersPreventTheLastRoomFromCountingAsCompleted() {
    var battle = combat.battle(combat.monster(8, 1));
    combat.monster(9, 2);
    combat.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    assertEquals(
        GameplayState.RogueRun.Phase.EXPLORING, combat.p.getGameplay().getRogue().getPhase());
    assertEquals(0, state().getProgress());
  }

  @Test
  void abandoningTheRunCannotGrantTheCompletionDaily() {
    assertEquals(Retcode.SUCC, combat.p.getUniverseManager().abandon());
    assertEquals(
        GameplayState.RogueRun.Phase.ABANDONED, combat.p.getGameplay().getRogue().getPhase());
    assertEquals(0, state().getProgress());
  }

  @Test
  void aBoundBattleFromAnOlderRunCannotCompleteTheCurrentDaily() {
    var battle = combat.battle(combat.monster(8, 1));
    var run =
        new GameplayState.RogueRun(19996, 1999601, Map.of(1, UniverseCombatFragmentsTest.ROOM));
    run.setupTeam(combat.p, List.of(199961));
    run.enter(1, UniverseCombatFragmentsTest.ROOM);
    combat.p.getGameplay().setRogue(run);
    combat.finish(battle, BattleEndStatus.BATTLE_END_WIN);
    assertEquals(0, state().getProgress());
    assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run.getPhase());
  }

  @Test
  void repeatedOutcomeAndSettlementQueriesCannotCountASecondUniverseCompletion() {
    var battle = win();
    complete();
    combat.p.getUniverseManager().onBattleFinish(battle);
    combat.p.getUniverseManager().settleResults();
    combat.p.getQuestManager().states();
    complete();
    assertEquals(1, combat.p.getGameplay().getRogueWins().get(19996));
  }

  @Test
  void concurrentCompletionRetriesAdvanceTheOriginalDailyExactlyOnce() throws Exception {
    var battle = combat.battle(combat.monster(8, 1));
    battle.setResult(BattleEndStatus.BATTLE_END_WIN);
    for (var monster : battle.getNpcMonsters()) {
      combat.p.getScene().getEntities().remove(monster.getEntityId());
      monster.setEntityId(0);
    }
    try (var workers = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 4; i++)
        tasks.add(
            () -> {
              combat.p.getUniverseManager().onBattleFinish(battle);
              return null;
            });
      for (var result : workers.invokeAll(tasks)) result.get();
    }
    complete();
    assertEquals(1, combat.p.getGameplay().getRogueWins().get(19996));
  }

  @Test
  void yesterdayCompletedRunsAndTheirQueriesCannotCompleteTheNewDaily() {
    var battle = win();
    combat
        .p
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T20:00:00Z"), ZoneOffset.UTC));
    combat.p.getDailyManager().ensureDay();
    combat.p.getUniverseManager().onBattleFinish(battle);
    combat.p.getUniverseManager().settleResults();
    combat.p.getQuestManager().states();
    assertEquals(0, state().getProgress());
    assertEquals(QuestState.Phase.DOING, state().getPhase());
  }

  @Test
  void aVictoryAfterFourAmAdvancesTheNewDailyWithoutChangingTheOldReference() {
    var previous = state();
    combat
        .p
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T20:00:00Z"), ZoneOffset.UTC));
    win();
    complete();
    assertNotSame(previous, state());
    assertEquals(0, previous.getProgress());
  }

  @Test
  void aJsonReloadAndSettlementOfACompletedRunCannotCountItAgain() throws Exception {
    var battle = win();
    complete();
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(
        combat.p,
        combat.gson.fromJson(combat.gson.toJson(combat.p.getGameplay()), GameplayState.class));
    combat.p.getUniverseManager().onBattleFinish(battle);
    combat.p.getUniverseManager().settleResults();
    complete();
    assertEquals(1, combat.p.getGameplay().getRogueWins().get(19996));
  }

  @ParameterizedTest
  @ValueSource(strings = {"comparison", "number", "text", "list", "items", "type"})
  void unknownHotChangedDailyRulesCannotBorrowTheOriginalFinishRule(String mode) {
    var raw =
        fixture.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> "rule".equals(r.get("kind").getAsString()))
            .findFirst()
            .orElseThrow()
            .getAsJsonObject("row")
            .deepCopy();
    switch (mode) {
      case "comparison" -> raw.addProperty("ParamType", "Equal");
      case "number" -> raw.addProperty("ParamInt1", 1);
      case "text" -> raw.addProperty("ParamStr1", "Unknown");
      case "list" -> raw.add("ParamIntList", JsonParser.parseString("[1]"));
      case "items" -> raw.add("ParamItemList", JsonParser.parseString("[{}]"));
      case "type" -> raw.addProperty("FinishType", "Custom.RogueFinishCnt");
    }
    GameData.getFinishWayExcelMap().add(combat.gson.fromJson(raw, FinishWayExcel.class));
    win();
    assertEquals(
        GameplayState.RogueRun.Phase.FINISHED, combat.p.getGameplay().getRogue().getPhase());
    assertEquals(0, state().getProgress());
  }

  @Test
  void aMissingSiteDefinitionCannotBeGuessedToMeanATerminalRoom() {
    GameplayDefinitions.maps.remove(1999601);
    win();
    assertEquals(
        GameplayState.RogueRun.Phase.EXPLORING, combat.p.getGameplay().getRogue().getPhase());
    assertEquals(0, state().getProgress());
  }
}
