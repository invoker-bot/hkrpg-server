package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.challenge.ChallengeInstance;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original daily rows; encounter/avatar construction is the explicit combat unit fixture. */
class DailyChallengeFinishTest {
  static final int QUEST = 2100119, GATE = 4010134, REWARD = 103004;
  static final Instant TODAY = Instant.parse("2026-10-05T12:00:00Z");
  final MissionChallengeWinTest combat = new MissionChallengeWinTest();
  final Gson gson = ResourceLoader.getGson();
  List<GameplayDefinitions.DailyPool> oldPool;
  QuestExcel oldQuest;
  FinishWayExcel oldRule;
  RewardExcel oldReward;
  MainMissionExcel oldGate;
  JsonObject originalRule;

  @BeforeEach
  void setup() throws Exception {
    oldPool = new ArrayList<>(GameplayDefinitions.dailyPool);
    combat.resources();
    GameplayDefinitions.dailyPool.clear();
    oldQuest = GameData.getQuestExcelMap().get(QUEST);
    oldRule = GameData.getFinishWayExcelMap().get(QUEST);
    oldReward = GameData.getRewardExcelMap().get(REWARD);
    oldGate = GameData.getMainMissionExcelMap().get(GATE);
    try (var stream = getClass().getResourceAsStream("/fixtures/daily-challenge-4.6.json")) {
      assertNotNull(stream);
      var records = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
      for (var raw : records.getAsJsonArray()) {
        var record = raw.getAsJsonObject();
        var row = record.getAsJsonObject("row");
        switch (record.get("kind").getAsString()) {
          case "pool" ->
              GameplayDefinitions.dailyPool.add(
                  gson.fromJson(row, GameplayDefinitions.DailyPool.class));
          case "quest" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "rule" -> {
            originalRule = row.deepCopy();
            GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
          }
          case "reward" -> {
            var reward = gson.fromJson(row, RewardExcel.class);
            reward.onLoad();
            GameData.getRewardExcelMap().add(reward);
          }
          case "gate" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "point" -> assertEquals(23, row.get("ID").getAsInt());
          default -> fail("Unknown source fixture record");
        }
      }
    }
  }

  @AfterEach
  void cleanup() {
    combat.cleanup();
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
    GameData.getQuestExcelMap().keySet().remove(QUEST);
    if (oldQuest != null) GameData.getQuestExcelMap().add(oldQuest);
    GameData.getFinishWayExcelMap().keySet().remove(QUEST);
    if (oldRule != null) GameData.getFinishWayExcelMap().add(oldRule);
    GameData.getRewardExcelMap().keySet().remove(REWARD);
    if (oldReward != null) GameData.getRewardExcelMap().add(oldReward);
    GameData.getMainMissionExcelMap().keySet().remove(GATE);
    if (oldGate != null) GameData.getMainMissionExcelMap().add(oldGate);
  }

  MissionChallengeWinTest.Challenger player(boolean unlocked, boolean acceptedFixture) {
    var player = combat.player(false);
    if (unlocked) {
      var gate = new QuestState(GATE);
      gate.advance(1, 1, TODAY.getEpochSecond());
      player.getGameplay().getMainMissions().put(GATE, gate);
    }
    player.getDailyManager().setClock(Clock.fixed(TODAY, ZoneOffset.UTC));
    player.getDailyManager().ensureDay();
    // Establish the accepted goal for callback regressions even on the unfixed baseline.
    if (acceptedFixture)
      player.getGameplay().getDailyQuests().putIfAbsent(QUEST, new QuestState(QUEST));
    return player;
  }

  QuestState state(MissionChallengeWinTest.Challenger player) {
    return Objects.requireNonNull(player.getGameplay().getDailyQuests().get(QUEST));
  }

  void win(MissionChallengeWinTest.Challenger player) {
    assertNotNull(combat.finish(player, combat.start(player), BattleEndStatus.BATTLE_END_WIN));
  }

  @Test
  void originalUnlockedMemoryDailyIsSelectedWithItsOriginalSingleCompletionAndReward() {
    var player = player(true, false);
    assertNotNull(
        player.getGameplay().getDailyQuests().get(QUEST), "Source Memory daily was not selected");
    assertEquals(0, state(player).getProgress());
    assertEquals(QuestState.Phase.DOING, state(player).getPhase());
    assertEquals(List.of(1), GameData.getFinishWayExcelMap().get(QUEST).getParamIntList());
    assertEquals(REWARD, GameData.getQuestExcelMap().get(QUEST).getRewardID());
    assertEquals(0, player.getGameplay().getDailyPoints());
  }

  @Test
  void sourceMissionGateMustBeCompletedBeforeDailySelection() {
    var player = player(false, false);
    assertFalse(player.getGameplay().getDailyQuests().containsKey(QUEST));
    var gate = new QuestState(GATE);
    player.getGameplay().getMainMissions().put(GATE, gate);
    player.getDailyManager().setClock(Clock.fixed(TODAY.plusSeconds(86400), ZoneOffset.UTC));
    player.getDailyManager().ensureDay();
    assertFalse(player.getGameplay().getDailyQuests().containsKey(QUEST));
    gate.advance(1, 1, TODAY.getEpochSecond());
    player.getDailyManager().setClock(Clock.fixed(TODAY.plusSeconds(172800), ZoneOffset.UTC));
    player.getDailyManager().ensureDay();
    assertNotNull(player.getGameplay().getDailyQuests().get(QUEST));
  }

  @Test
  void actualWholeMemoryWinWithoutAnAcceptedStoryObjectiveCreditsAndClaimsTwoHundredOnce() {
    var player = player(true, true);
    assertTrue(player.getGameplay().getSubMissions().isEmpty());
    win(player);
    assertTrue(((ChallengeInstance) player.getInstance()).isWin());
    assertEquals(0, ((ChallengeInstance) player.getInstance()).getStars());
    assertEquals(1, state(player).getProgress());
    assertEquals(QuestState.Phase.FINISHED, state(player).getPhase());
    assertEquals(1, player.saved.getDailyQuests().get(QUEST).getProgress());
    assertEquals(0, player.getGameplay().getDailyPoints());
    assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(QUEST).retcode());
    assertEquals(200, player.getGameplay().getDailyPoints());
    assertEquals(QuestState.Phase.CLOSED, state(player).getPhase());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getDailyManager().claimQuest(QUEST).retcode());
    assertEquals(200, player.getGameplay().getDailyPoints());
  }

  @Test
  void twoHalfMemoryCountsOnlyTheLastActualWin() {
    combat.challenge(2);
    var player = player(true, true);
    win(player);
    assertFalse(((ChallengeInstance) player.getInstance()).isWin());
    assertEquals(0, state(player).getProgress());
    assertEquals(Retcode.QUEST_NOT_FINISH, player.getDailyManager().claimQuest(QUEST).retcode());
    win(player);
    assertEquals(1, state(player).getProgress());
  }

  @Test
  void wrongIdentityPreservesTheBattleAndOnlyItsValidRetryCounts() {
    var player = player(true, true);
    var battle = combat.start(player);
    assertNull(
        combat.service.finishBattle(
            player,
            battle.getId() + 1,
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertSame(battle, player.getBattle());
    assertEquals(0, state(player).getProgress());
    assertNotNull(combat.finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, state(player).getProgress());
    assertNull(combat.finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, state(player).getProgress());
  }

  @ParameterizedTest
  @EnumSource(
      value = BattleEndStatus.class,
      names = {"BATTLE_END_LOSE", "BATTLE_END_QUIT"})
  void lossAndRetreatDoNotCompleteTheDaily(BattleEndStatus result) {
    var player = player(true, true);
    assertNotNull(combat.finish(player, combat.start(player), result));
    assertEquals(0, state(player).getProgress());
    assertEquals(QuestState.Phase.DOING, state(player).getPhase());
  }

  @Test
  void aWinWithOtherLivingSceneMonstersDoesNotCompleteTheWholeMemory() {
    var player = player(true, true);
    var battle = combat.start(player);
    var extra = combat.monster(player, 2, 200002);
    extra.setEntityId(100);
    player.getScene().getEntities().put(100, extra);
    assertNotNull(combat.finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, state(player).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void unmatchedNpcGroupAndStageOnlyBattleDoNotAuthorizeDailyProgress(int variant) {
    var player = player(true, true);
    emu.lunarcore.game.battle.Battle battle;
    if (variant == 0) {
      combat.monster(player, 7, 200001);
      combat.service.attack(
          player,
          player.getScene().getAvatarEntityIds().iterator().nextInt(),
          0,
          null,
          new IntOpenHashSet(new int[] {99}),
          new IntOpenHashSet());
      battle = player.getBattle();
    } else battle = combat.service.startBattle(player, MissionChallengeWinTest.EVENT, 0);
    assertNotNull(combat.finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, state(player).getProgress());
  }

  @Test
  void changedCurrentEntranceCannotBorrowAPreparedChallenge() {
    var player = player(true, true);
    var battle = combat.start(player);
    player.entry++;
    assertNotNull(combat.finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, state(player).getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"ParamType\":\"NoPara\"}",
        "{\"ParamIntList\":[]}",
        "{\"ParamIntList\":[2]}",
        "{\"ParamIntList\":[3]}",
        "{\"ParamIntList\":[1,2]}",
        "{\"ParamIntList\":[1,1]}",
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
  void unverifiedDailyRuleVariantsAreNeitherSelectedNorAdvanced(String mutation) {
    var row = originalRule.deepCopy();
    JsonParser.parseString(mutation)
        .getAsJsonObject()
        .entrySet()
        .forEach(e -> row.add(e.getKey(), e.getValue()));
    GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    var player = player(true, false);
    assertFalse(player.getGameplay().getDailyQuests().containsKey(QUEST));
    player.getGameplay().getDailyQuests().put(QUEST, new QuestState(QUEST));
    win(player);
    assertEquals(0, state(player).getProgress());
  }

  @Test
  void savedClaimStateRestoresAndTheNextDailyStartsWithoutBorrowingYesterdayHistory()
      throws Exception {
    var player = player(true, true);
    var battle = combat.start(player);
    assertNotNull(combat.finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, state(player).getProgress());
    assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(QUEST).retcode());
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(player, gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    assertEquals(200, player.getGameplay().getDailyPoints());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getDailyManager().claimQuest(QUEST).retcode());
    player.getDailyManager().setClock(Clock.fixed(TODAY.plusSeconds(86400), ZoneOffset.UTC));
    player.getDailyManager().ensureDay();
    assertEquals(0, state(player).getProgress());
    assertEquals(0, player.getGameplay().getDailyPoints());
    assertTrue(
        player
            .getGameplay()
            .getCompletedMemoryChallenges()
            .contains(MissionChallengeWinTest.CHALLENGE));
    assertNull(combat.finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, state(player).getProgress());
    player.setInstance(
        new ChallengeInstance(
            player, GameData.getChallengeExcelMap().get(MissionChallengeWinTest.CHALLENGE)));
    win(player);
    assertEquals(1, state(player).getProgress());
  }

  @Test
  void concurrentCopiesOfTheRealBattleReportSettleAndCreditOnce() throws Exception {
    var player = player(true, true);
    var battle = combat.start(player);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var reports =
          executor.invokeAll(
              List.of(
                  () -> combat.finish(player, battle, BattleEndStatus.BATTLE_END_WIN),
                  () -> combat.finish(player, battle, BattleEndStatus.BATTLE_END_WIN)));
      int accepted = 0;
      for (var report : reports) if (report.get() != null) accepted++;
      assertEquals(1, accepted);
      assertEquals(1, state(player).getProgress());
      assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(QUEST).retcode());
      assertEquals(200, player.getGameplay().getDailyPoints());
    } finally {
      executor.shutdownNow();
    }
  }
}
