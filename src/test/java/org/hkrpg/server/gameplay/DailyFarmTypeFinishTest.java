package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
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

/** Original farming rows/waves; player stamina, delivery, and save are explicit unit doubles. */
class DailyFarmTypeFinishTest {
  static final int GOLD = 2100157, CRIMSON = 2100158;
  static final Instant TODAY = Instant.parse("2026-10-05T12:00:00Z");
  final Gson gson = ResourceLoader.getGson();
  final MissionFarmingBattleTest.Service service = new MissionFarmingBattleTest.Service();
  final Map<Integer, QuestExcel> oldQuests = new HashMap<>();
  final Map<Integer, FinishWayExcel> oldRules = new HashMap<>();
  final Map<Integer, MainMissionExcel> oldGates = new HashMap<>();
  final Map<Integer, CocoonExcel> oldCocoons = new HashMap<>();
  final Map<Integer, StageExcel> oldStages = new HashMap<>();
  final Map<Integer, JsonObject> rules = new HashMap<>(), cocoons = new HashMap<>();
  List<GameplayDefinitions.DailyPool> oldPool;
  RewardExcel oldReward;

  @BeforeEach
  void setup() throws Exception {
    oldPool = new ArrayList<>(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    oldReward = GameData.getRewardExcelMap().get(103004);
    try (var stream = getClass().getResourceAsStream("/fixtures/daily-farmtype-4.6.json")) {
      assertNotNull(stream);
      var rows = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
      for (var raw : rows.getAsJsonArray()) {
        var record = raw.getAsJsonObject();
        var row = record.getAsJsonObject("row");
        switch (record.get("kind").getAsString()) {
          case "pool" ->
              GameplayDefinitions.dailyPool.add(
                  gson.fromJson(row, GameplayDefinitions.DailyPool.class));
          case "quest" -> {
            int id = row.get("QuestID").getAsInt();
            oldQuests.put(id, GameData.getQuestExcelMap().get(id));
            GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          }
          case "rule" -> {
            int id = row.get("ID").getAsInt();
            oldRules.put(id, GameData.getFinishWayExcelMap().get(id));
            rules.put(id, row.deepCopy());
            GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
          }
          case "gate" -> {
            int id = row.get("MainMissionID").getAsInt();
            oldGates.put(id, GameData.getMainMissionExcelMap().get(id));
            GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          }
          case "cocoon" -> {
            int id = row.get("ID").getAsInt();
            oldCocoons.put(id, GameData.getCocoonExcelMap().get(id, 0));
            cocoons.put(id, row.deepCopy());
            GameData.getCocoonExcelMap().add(gson.fromJson(row, CocoonExcel.class));
          }
          case "stage" -> {
            int id = row.get("StageID").getAsInt();
            oldStages.put(id, GameData.getStageExcelMap().get(id));
            var stage = gson.fromJson(row, StageExcel.class);
            stage.onLoad();
            GameData.getStageExcelMap().add(stage);
          }
          case "reward" -> {
            var reward = gson.fromJson(row, RewardExcel.class);
            reward.onLoad();
            GameData.getRewardExcelMap().add(reward);
          }
          case "point" -> assertEquals(23, row.get("ID").getAsInt());
          default -> fail("Unknown source record");
        }
      }
    }
  }

  @AfterEach
  void cleanup() {
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
    oldQuests.forEach(
        (id, row) -> {
          GameData.getQuestExcelMap().keySet().remove(id);
          if (row != null) GameData.getQuestExcelMap().add(row);
        });
    oldRules.forEach(
        (id, row) -> {
          GameData.getFinishWayExcelMap().keySet().remove(id);
          if (row != null) GameData.getFinishWayExcelMap().add(row);
        });
    oldGates.forEach(
        (id, row) -> {
          GameData.getMainMissionExcelMap().keySet().remove(id);
          if (row != null) GameData.getMainMissionExcelMap().add(row);
        });
    oldCocoons.forEach(
        (id, row) -> {
          GameData.getCocoonExcelMap()
              .values()
              .removeIf(r -> r.getCocoonId() == id && r.getWorldLevel() == 0);
          if (row != null) GameData.getCocoonExcelMap().add(row);
        });
    oldStages.forEach(
        (id, row) -> {
          GameData.getStageExcelMap().keySet().remove(id);
          if (row != null) GameData.getStageExcelMap().add(row);
        });
    GameData.getRewardExcelMap().keySet().remove(103004);
    if (oldReward != null) GameData.getRewardExcelMap().add(oldReward);
  }

  MissionFarmingBattleTest.Farmer player(int quest, boolean unlocked, boolean acceptedFixture) {
    GameplayDefinitions.dailyPool.removeIf(row -> row.QuestID() != quest);
    var player = new MissionFarmingBattleTest.Farmer();
    if (unlocked) {
      int gate = quest == GOLD ? 4010121 : 4010123;
      var completed = new QuestState(gate);
      completed.advance(1, 1, TODAY.getEpochSecond());
      player.getGameplay().getMainMissions().put(gate, completed);
    }
    player.getDailyManager().setClock(Clock.fixed(TODAY, ZoneOffset.UTC));
    player.getDailyManager().ensureDay();
    // Explicit accepted-state fixture exposes callback failures independently of selection.
    if (acceptedFixture)
      player.getGameplay().getDailyQuests().putIfAbsent(quest, new QuestState(quest));
    return player;
  }

  QuestState state(MissionFarmingBattleTest.Farmer player, int quest) {
    return Objects.requireNonNull(player.getGameplay().getDailyQuests().get(quest));
  }

  Battle start(MissionFarmingBattleTest.Farmer player, int cocoon, int count) {
    return Objects.requireNonNull(service.startCocoon(player, cocoon, 0, count));
  }

  Battle finish(MissionFarmingBattleTest.Farmer player, Battle battle, BattleEndStatus result) {
    return service.finishBattle(
        player, battle.getId(), battle.getStage().getId(), result, BattleStatistics.newInstance());
  }

  @ParameterizedTest
  @ValueSource(ints = {GOLD, CRIMSON})
  void originalUnlockedFarmDailyIsSelectedWithItsOriginalRule(int quest) {
    var player = player(quest, true, false);
    assertNotNull(
        player.getGameplay().getDailyQuests().get(quest), "Source farm daily not selected");
    assertEquals(0, state(player, quest).getProgress());
    assertEquals(QuestState.Phase.DOING, state(player, quest).getPhase());
    assertEquals(0, player.getGameplay().getDailyPoints());
    assertEquals(103004, GameData.getQuestExcelMap().get(quest).getRewardID());
  }

  @ParameterizedTest
  @ValueSource(ints = {GOLD, CRIMSON})
  void unfinishedOriginalMissionGateDoesNotSelectFarmDaily(int quest) {
    var player = player(quest, false, false);
    assertFalse(player.getGameplay().getDailyQuests().containsKey(quest));
  }

  @Test
  void cocoonResourcePreservesTheOriginalNamedFarmTypeForTheVerifiedNativeEnum() {
    for (int id : List.of(1001, 1002, 1003, 1004)) {
      var loaded = gson.toJsonTree(GameData.getCocoonExcelMap().get(id, 0)).getAsJsonObject();
      assertEquals(cocoons.get(id).get("FarmType"), loaded.get("FarmType"));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1001, 1002, 1003})
  void eachOriginalGoldenFarmTypeCountsItsActualVictoryAndDebitOnce(int cocoon) {
    var player = player(GOLD, true, true);
    var battle = start(player, cocoon, 1);
    assertFalse(battle.getWaves().isEmpty());
    assertTrue(battle.getWaves().stream().allMatch(wave -> !wave.getMonsters().isEmpty()));
    assertNotNull(finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(90, player.stamina);
    assertEquals(1, state(player, GOLD).getProgress());
    assertNull(finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, state(player, GOLD).getProgress());
  }

  @Test
  void originalCrimsonFarmTypeFiveCountsOnlyAfterARealVictory() {
    var player = player(CRIMSON, true, true);
    assertNotNull(finish(player, start(player, 1004, 1), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, state(player, CRIMSON).getProgress());
    assertEquals(90, player.stamina);
  }

  @Test
  void multipleOriginalWavesCompleteOneDailyAndGrantOriginalTwoHundredPointsOnce() {
    var player = player(GOLD, true, true);
    assertNotNull(finish(player, start(player, 1001, 3), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(70, player.stamina);
    assertEquals(1, state(player, GOLD).getProgress());
    assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(GOLD).retcode());
    assertEquals(200, player.getGameplay().getDailyPoints());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getDailyManager().claimQuest(GOLD).retcode());
    assertEquals(200, player.getGameplay().getDailyPoints());
  }

  @ParameterizedTest
  @ValueSource(ints = {GOLD, CRIMSON})
  void otherColorVictoryDoesNotCompleteTheDaily(int quest) {
    var player = player(quest, true, true);
    assertNotNull(
        finish(
            player, start(player, quest == GOLD ? 1004 : 1001, 1), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, state(player, quest).getProgress());
    assertEquals(Retcode.QUEST_NOT_FINISH, player.getDailyManager().claimQuest(quest).retcode());
  }

  @ParameterizedTest
  @EnumSource(
      value = BattleEndStatus.class,
      names = {"BATTLE_END_LOSE", "BATTLE_END_QUIT"})
  void lossAndRetreatDoNotDebitOrFinishFarmDaily(BattleEndStatus result) {
    var player = player(GOLD, true, true);
    assertNotNull(finish(player, start(player, 1001, 1), result));
    assertEquals(100, player.stamina);
    assertEquals(0, state(player, GOLD).getProgress());
  }

  @Test
  void rejectedBattleIdentityAndNullStatisticsDoNotConsumeAValidRetry() {
    var player = player(GOLD, true, true);
    var battle = start(player, 1001, 1);
    assertNull(
        service.finishBattle(
            player,
            battle.getId() + 1,
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertNull(
        service.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            null));
    assertSame(battle, player.getBattle());
    assertEquals(100, player.stamina);
    assertEquals(0, state(player, GOLD).getProgress());
    assertNotNull(finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, state(player, GOLD).getProgress());
  }

  @Test
  void staminaConsumedAfterPreparationRejectsWinWithoutDailyCredit() {
    var player = player(GOLD, true, true);
    var battle = start(player, 1001, 1);
    player.stamina = 0;
    assertNull(finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, state(player, GOLD).getProgress());
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"COCOON\"", "\"COCOON2\"", "\"RELIC\"", "\"Unknown\"", "null"})
  void unverifiedOrOtherSourceFarmTypeCannotBorrowGoldenCredit(String value) {
    var row = cocoons.get(1001).deepCopy();
    row.add("FarmType", JsonParser.parseString(value));
    GameData.getCocoonExcelMap().add(gson.fromJson(row, CocoonExcel.class));
    var player = player(GOLD, true, true);
    assertNotNull(finish(player, start(player, 1001, 1), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, state(player, GOLD).getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"ParamType\":\"Equal\"}",
        "{\"ParamIntList\":[]}",
        "{\"ParamIntList\":[6]}",
        "{\"ParamIntList\":[6,7]}",
        "{\"ParamIntList\":[6,7,8,5]}",
        "{\"ParamIntList\":[6,6,8]}",
        "{\"ParamIntList\":null}",
        "{\"ParamInt1\":1}",
        "{\"ParamInt2\":1}",
        "{\"ParamInt3\":1}",
        "{\"ParamStr1\":\"unverified\"}",
        "{\"ParamStr1\":null}",
        "{\"ParamItemList\":[{}]}",
        "{\"ParamItemList\":null}",
        "{\"Progress\":2}",
        "{\"Progress\":0}",
        "{\"IsBackTrack\":true}"
      })
  void unknownRuleVariantsAreNeitherSelectedNorAdvanced(String mutation) {
    var row = rules.get(GOLD).deepCopy();
    JsonParser.parseString(mutation)
        .getAsJsonObject()
        .entrySet()
        .forEach(e -> row.add(e.getKey(), e.getValue()));
    GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    var player = player(GOLD, true, false);
    assertFalse(player.getGameplay().getDailyQuests().containsKey(GOLD));
    player.getGameplay().getDailyQuests().put(GOLD, new QuestState(GOLD));
    assertNotNull(finish(player, start(player, 1001, 1), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, state(player, GOLD).getProgress());
  }

  @Test
  void concurrentReportsDebitAndFinishOnlyOnce() throws Exception {
    var player = player(GOLD, true, true);
    var battle = start(player, 1001, 1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var results =
          executor.invokeAll(
              List.of(
                  () -> finish(player, battle, BattleEndStatus.BATTLE_END_WIN),
                  () -> finish(player, battle, BattleEndStatus.BATTLE_END_WIN)));
      int accepted = 0;
      for (var result : results) if (result.get() != null) accepted++;
      assertEquals(1, accepted);
      assertEquals(90, player.stamina);
      assertEquals(1, state(player, GOLD).getProgress());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void savedRewardRestoresAndFreshDayDoesNotBorrowAnOldBattle() throws Exception {
    var player = player(GOLD, true, true);
    var battle = start(player, 1001, 1);
    assertNotNull(finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, state(player, GOLD).getProgress());
    assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(GOLD).retcode());
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(player, gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    assertEquals(200, player.getGameplay().getDailyPoints());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getDailyManager().claimQuest(GOLD).retcode());
    player.getDailyManager().setClock(Clock.fixed(TODAY.plusSeconds(86400), ZoneOffset.UTC));
    player.getDailyManager().ensureDay();
    assertEquals(0, state(player, GOLD).getProgress());
    assertEquals(0, player.getGameplay().getDailyPoints());
    assertNull(finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, state(player, GOLD).getProgress());
    assertNotNull(finish(player, start(player, 1001, 1), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, state(player, GOLD).getProgress());
  }
}
