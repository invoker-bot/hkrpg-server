package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original nonperiodic activity ownership; every boxing completion is earned by settlement. */
class BoxingClubActivityQuests46Test {
  @TempDir Path root;
  final BoxingClubResidentQuests46Test fixture = new BoxingClubResidentQuests46Test();
  final Map<Integer, DailyCycleCatalog.Cycle> oldCycles = new HashMap<>();
  final Map<Integer, DailyCycleCatalog.Daily> oldDailies = new HashMap<>();
  final Map<Integer, DailyCycleCatalog.Schedule> oldSchedules = new HashMap<>();
  JsonObject source;

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
    oldCycles.putAll(DailyCycleCatalog.cycles);
    oldDailies.putAll(DailyCycleCatalog.dailies);
    oldSchedules.putAll(DailyCycleCatalog.schedules);
    source = fixture.fixture.fixture.fixture("boxing-club-activity-quests-4.6.json");
    var gson = ResourceLoader.getGson();
    for (var raw : source.getAsJsonArray("inputs")) {
      var input = raw.getAsJsonObject();
      var file = root.resolve(input.get("path").getAsString());
      var rows =
          Files.isRegularFile(file)
              ? JsonParser.parseString(Files.readString(file)).getAsJsonArray()
              : new JsonArray();
      for (var row : input.getAsJsonArray("rows")) if (!rows.contains(row)) rows.add(row);
      Files.writeString(file, rows.toString());
      for (var row : input.getAsJsonArray("rows"))
        switch (file.getFileName().toString()) {
          case "QuestData.json" ->
              GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "FinishWay.json" ->
              GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
          case "RewardData.json" -> {
            var reward = gson.fromJson(row, RewardExcel.class);
            reward.onLoad();
            GameData.getRewardExcelMap().add(reward);
          }
          case "ItemConfig.json" ->
              GameData.getItemExcelMap().add(gson.fromJson(row, ItemExcel.class));
          case "ActivityPanel.json" ->
              GameData.getActivityPanelExcelMap().add(gson.fromJson(row, ActivityPanelExcel.class));
        }
    }
    DailyCycleCatalog.load(root);
    activityWindows();
  }

  @AfterEach
  void restore() throws Exception {
    DailyCycleCatalog.cycles.clear();
    DailyCycleCatalog.cycles.putAll(oldCycles);
    DailyCycleCatalog.dailies.clear();
    DailyCycleCatalog.dailies.putAll(oldDailies);
    DailyCycleCatalog.schedules.clear();
    DailyCycleCatalog.schedules.putAll(oldSchedules);
    fixture.restore();
  }

  BoxingClubRounds46Test first() {
    return fixture.fixture.fixture;
  }

  BoxingClubRounds46Test.Actor actor() {
    return first().session.actor;
  }

  void activityWindows() {
    GameplayDefinitions.windows.removeIf(w -> w.moduleId() == 5000001 || w.moduleId() == 4000308);
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(50000, 5000001, 1, Long.MAX_VALUE));
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(40003, 4000308, 1, Long.MAX_VALUE));
  }

  void earn(int challenge, int total) throws Exception {
    GameplayDefinitions.windows.removeIf(w -> w.moduleId() != 5000001 && w.moduleId() != 4000308);
    if (challenge <= 5) {
      var c = BoxingClubCatalog.load(root).challenges().get(challenge);
      GameplayDefinitions.windows.add(
          new GameplayDefinitions.Window(50000, c.module(), 1, Long.MAX_VALUE));
      first().match(challenge, 1001);
      assertEquals(0, first().number(first().session.response, "retcode"));
      for (int i = 0; i < 4; i++) first().win(first().start(), i < 3 ? 1 : total - 3);
    } else {
      var c = fixture.fixture.challenge(challenge);
      GameplayDefinitions.windows.add(
          new GameplayDefinitions.Window(40003, c.module(), 1, Long.MAX_VALUE));
      fixture.fixture.prepare(challenge);
      for (int i = 0; i < 3; i++) {
        if (i > 0)
          fixture.fixture.choose(challenge, fixture.fixture.options(challenge, i).get(0), false);
        var battle = fixture.fixture.start(challenge);
        assertSame(
            battle,
            fixture.fixture.finish(
                battle,
                emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
                i < 2 ? 1 : total - 2));
      }
    }
  }

  QuestState state(int id) {
    return actor().getQuestManager().states().stream()
        .filter(q -> q.getQuestId() == id)
        .findFirst()
        .orElse(null);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
  void boundWinsPublishTheOriginalTemporaryGoalForEveryChallenge(int challenge) throws Exception {
    for (int id = challenge <= 5 ? 1 : 6; id <= challenge; id++) earn(id, id <= 5 ? 4 : 3);
    assertNotNull(
        challenge <= 5
            ? actor().getGameplay().getBoxingClub().getCompleted().get(challenge)
            : actor().getGameplay().getBoxingClubResonance().getCompleted().get(challenge));
    int quest = challenge <= 5 ? 6000060 + (challenge - 1) * 3 : 6000370 + (challenge - 6) * 4;
    var earned = state(quest);
    assertNotNull(earned, "Earned temporary boxing quest remains unavailable");
    assertEquals(QuestState.Phase.FINISHED, earned.getPhase());
    assertEquals(1, earned.getProgress());
  }

  static java.util.stream.IntStream goals() {
    return java.util.stream.IntStream.concat(
        java.util.stream.IntStream.rangeClosed(6000060, 6000080),
        java.util.stream.IntStream.rangeClosed(6000369, 6000397));
  }

  void prerequisites(int challenge) throws Exception {
    for (int id = challenge <= 5 ? 1 : 6; id < challenge; id++) earn(id, id <= 5 ? 4 : 3);
  }

  @ParameterizedTest
  @MethodSource("goals")
  void everyLinkedGoalIncludingHiddenPrerequisitesAndFinalsUsesActualWins(int id) throws Exception {
    var finish =
        GameData.getFinishWayExcelMap().get(GameData.getQuestExcelMap().get(id).getFinishWayID());
    int challenge = id == 6000075 ? 5 : id == 6000397 ? 12 : finish.getParamInt2();
    prerequisites(challenge);
    earn(challenge, challenge <= 5 ? 4 : 3);
    var earned = state(id);
    assertNotNull(earned);
    assertEquals(QuestState.Phase.FINISHED, earned.getPhase());
    assertEquals(id == 6000075 ? 5 : id == 6000397 ? 7 : 1, earned.getProgress());
    assertTrue(QuestCompletionRecords.completed(actor(), id));
    assertEquals(Retcode.QUEST_STATUS_ERROR, actor().getQuestManager().finishByClient(id));
  }

  @ParameterizedTest
  @CsvSource({
    "1,4", "1,8", "1,9", "1,15", "1,16", "1,20", "6,3", "6,6", "6,7", "6,8", "6,9", "6,20"
  })
  void originalTurnTiersMeasureTheCumulativeCompleteRun(int challenge, int total) throws Exception {
    earn(challenge, total);
    int base = challenge == 1 ? 6000060 : 6000370;
    var limits = challenge == 1 ? new int[] {30, 15, 8} : new int[] {8, 7, 6};
    for (int i = 0; i < 3; i++) {
      assertEquals(total <= limits[i] ? 1 : 0, state(base + i).getProgress());
      assertEquals(
          total <= limits[i] ? QuestState.Phase.FINISHED : QuestState.Phase.DOING,
          state(base + i).getPhase());
    }
    assertEquals(
        total <= 9 || challenge == 1 ? 1 : 0,
        state(challenge == 1 ? 6000076 : 6000369).getProgress());
  }

  @ParameterizedTest
  @CsvSource({"1,0", "1,6", "6,0", "6,6"})
  void originalGoalsAcceptActualSourceStagesAtBothWorldBoundaries(int challenge, int world)
      throws Exception {
    actor().world = world;
    earn(challenge, challenge == 1 ? 4 : 3);
    assertEquals(1, state(challenge == 1 ? 6000060 : 6000370).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
  void anIncompleteBoundAttemptCannotBecomeATemporaryCompletion(int challenge) throws Exception {
    prerequisites(challenge);
    GameplayDefinitions.windows.removeIf(w -> w.moduleId() != 5000001 && w.moduleId() != 4000308);
    var c =
        challenge <= 5
            ? BoxingClubCatalog.load(root).challenges().get(challenge).module()
            : fixture.fixture.challenge(challenge).module();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(challenge <= 5 ? 50000 : 40003, c, 1, Long.MAX_VALUE));
    if (challenge <= 5) {
      first().match(challenge, 1001);
      for (int i = 0; i < 3; i++) first().win(first().start(), 1);
    } else {
      fixture.fixture.prepare(challenge);
      for (int i = 0; i < 2; i++) {
        if (i > 0)
          fixture.fixture.choose(challenge, fixture.fixture.options(challenge, i).get(0), false);
        var b = fixture.fixture.start(challenge);
        assertSame(b, fixture.fixture.finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
      }
    }
    assertFalse(
        actor().getGameplay().getBoxingClubActivityQuests().getCompleted().containsKey(challenge));
    assertEquals(
        0,
        state(challenge <= 5 ? 6000060 + (challenge - 1) * 3 : 6000370 + (challenge - 6) * 4)
            .getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void theFinalRewardCountsDistinctSourceHiddenGoalsAndNeverRepeatedVictories(int season)
      throws Exception {
    int first = season == 1 ? 1 : 6,
        last = season == 1 ? 5 : 12,
        id = season == 1 ? 6000075 : 6000397;
    for (int challenge = first; challenge < last; challenge++) earn(challenge, season == 1 ? 4 : 9);
    assertEquals(last - first, state(id).getProgress());
    earn(first, season == 1 ? 4 : 9);
    assertEquals(last - first, state(id).getProgress());
    if (season == 2) {
      earn(last, 10);
      assertEquals(QuestState.Phase.DOING, state(id).getPhase());
    }
    earn(last, season == 1 ? 20 : 9);
    assertEquals(QuestState.Phase.FINISHED, state(id).getPhase());
    assertEquals(season == 1 ? 5 : 7, state(id).getProgress());
  }

  Map<Integer, Integer> delta(Map<Integer, Integer> before) {
    var change = new TreeMap<Integer, Integer>();
    actor()
        .amounts
        .forEach(
            (id, count) -> {
              int n = count - before.getOrDefault(id, 0);
              if (n != 0) change.put(id, n);
            });
    return change;
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void everyOriginalRewardIncludingEmptyChildrenAndFinalsGrantsExactTotalsOnce(int season)
      throws Exception {
    for (int challenge = season == 1 ? 1 : 6; challenge <= (season == 1 ? 5 : 12); challenge++)
      earn(challenge, season == 1 ? 4 : 3);
    var before = Map.copyOf(actor().amounts);
    int min = season == 1 ? 6000060 : 6000369, max = season == 1 ? 6000080 : 6000397;
    for (int id = min; id <= max; id++) {
      assertEquals(Retcode.SUCC, actor().getQuestManager().claim(id).retcode());
      assertEquals(
          Retcode.QUEST_REWARD_ALREADY_TAKEN, actor().getQuestManager().claim(id).retcode());
      assertEquals(QuestState.Phase.CLOSED, state(id).getPhase());
    }
    var expected = new TreeMap<Integer, Integer>();
    if (season == 1)
      expected.putAll(
          Map.of(1, 500, 2, 100000, 213, 24, 222, 18, 232, 29, 223, 16, 212, 12, 241, 1));
    else {
      expected.putAll(Map.of(1, 500, 2, 210000, 213, 21, 223, 21, 233, 21, 235, 140, 241, 1));
      for (int item : new int[] {110112, 110122, 110132, 110142, 110152, 110162, 110172})
        expected.put(item, 3);
    }
    assertEquals(expected, delta(before));
    assertNull(state(6000399));
    assertEquals(Retcode.REQ_PARA_INVALID, actor().getQuestManager().claim(6000399).retcode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "QuestData",
        "FinishWay",
        "CycleQuest",
        "RewardData",
        "ItemConfig",
        "ActivityConfig",
        "ActivityPanel",
        "BoxingClubActivityQuest",
        "BoxingClubChallengeSeason",
        "BoxingClubConstValueClient",
        "ConstValueClient"
      })
  void aChangedCompleteSourceIsHiddenWithoutClaimingAndRepairRestoresEarnedProgress(String table)
      throws Exception {
    earn(6, 3);
    var entry =
        source.getAsJsonArray("inputs").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(x -> x.get("path").getAsString().equals("ExcelOutput/" + table + ".json"))
            .findFirst()
            .orElseThrow();
    var selected = entry.getAsJsonArray("rows").get(0);
    var file = root.resolve(entry.get("path").getAsString());
    var original = Files.readString(file);
    var rows = JsonParser.parseString(original).getAsJsonArray();
    rows.asList().stream()
        .filter(selected::equals)
        .findFirst()
        .orElseThrow()
        .getAsJsonObject()
        .addProperty("ChangedUnmatchedField", 1);
    Files.writeString(file, rows.toString());
    assertNull(state(6000370));
    assertFalse(QuestCompletionRecords.completed(actor(), 6000370));
    assertEquals(Retcode.QUEST_NOT_ACCEPT, actor().getQuestManager().claim(6000370).retcode());
    Files.writeString(file, original);
    assertEquals(1, state(6000370).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 6})
  void closedOrAbsentTemporaryWindowsCannotBorrowPermanentBestRecords(int challenge)
      throws Exception {
    GameplayDefinitions.windows.removeIf(w -> w.moduleId() == 5000001 || w.moduleId() == 4000308);
    earn(challenge, challenge == 1 ? 4 : 3);
    assertTrue(actor().getGameplay().getBoxingClubActivityQuests().getCompleted().isEmpty());
    assertNull(state(challenge == 1 ? 6000060 : 6000370));
    activityWindows();
    assertEquals(0, state(challenge == 1 ? 6000060 : 6000370).getProgress());
    earn(challenge, challenge == 1 ? 4 : 3);
    assertEquals(1, state(challenge == 1 ? 6000060 : 6000370).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 6})
  void ambiguousOwningWindowsCannotPublishOrCollectTheAttempt(int challenge) throws Exception {
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(
            challenge == 1 ? 50000 : 40003, challenge == 1 ? 5000001 : 4000308, 2, Long.MAX_VALUE));
    earn(challenge, challenge == 1 ? 4 : 3);
    assertTrue(actor().getGameplay().getBoxingClubActivityQuests().getCompleted().isEmpty());
    assertNull(state(challenge == 1 ? 6000060 : 6000370));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 6})
  void expiryBlocksClaimsAndAnExplicitReopeningDoesNotResetTheNonperiodicReward(int challenge)
      throws Exception {
    earn(challenge, challenge == 1 ? 4 : 3);
    int id = challenge == 1 ? 6000060 : 6000370;
    assertTrue(QuestCompletionRecords.completed(actor(), id));
    GameplayDefinitions.windows.removeIf(w -> w.moduleId() == 5000001 || w.moduleId() == 4000308);
    assertNull(state(id));
    assertTrue(QuestCompletionRecords.completed(actor(), id));
    assertEquals(Retcode.QUEST_NOT_ACCEPT, actor().getQuestManager().claim(id).retcode());
    long now = java.time.Instant.now().getEpochSecond();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(
            challenge == 1 ? 50000 : 40003,
            challenge == 1 ? 5000001 : 4000308,
            now - 1,
            now + 100));
    assertEquals(Retcode.SUCC, actor().getQuestManager().claim(id).retcode());
    var amounts = Map.copyOf(actor().amounts);
    actor()
        .getDailyManager()
        .setClock(
            java.time.Clock.fixed(
                java.time.Instant.now().plusSeconds(86400), java.time.ZoneOffset.UTC));
    actor().getDailyManager().ensureDay();
    assertEquals(QuestState.Phase.CLOSED, state(id).getPhase());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, actor().getQuestManager().claim(id).retcode());
    assertEquals(amounts, actor().amounts);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 6})
  void jsonReloadKeepsEarnedProofAndClosedRewardWithoutDuplicateItems(int challenge)
      throws Exception {
    earn(challenge, challenge == 1 ? 4 : 3);
    int id = challenge == 1 ? 6000060 : 6000370;
    assertEquals(Retcode.SUCC, actor().getQuestManager().claim(id).retcode());
    var gson = ResourceLoader.getGson();
    var json = gson.toJson(actor().getGameplay().getBoxingClubActivityQuests());
    actor()
        .getGameplay()
        .boxingClubActivityQuests(gson.fromJson(json, BoxingClubActivityQuestState.class));
    var before = Map.copyOf(actor().amounts);
    assertEquals(QuestState.Phase.CLOSED, state(id).getPhase());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, actor().getQuestManager().claim(id).retcode());
    assertEquals(before, actor().amounts);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 6})
  void failedFinalSaveRollsBackBothBoxingAndActivityProofAndTheSameBattleRetries(int challenge)
      throws Exception {
    assertNotNull(state(challenge == 1 ? 6000060 : 6000370));
    var module = challenge == 1 ? 5000002 : 4000301;
    GameplayDefinitions.windows.removeIf(w -> w.moduleId() != 5000001 && w.moduleId() != 4000308);
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(challenge == 1 ? 50000 : 40003, module, 1, Long.MAX_VALUE));
    emu.lunarcore.game.battle.Battle current;
    if (challenge == 1) {
      first().match(1, 1001);
      for (int i = 0; i < 3; i++) first().win(first().start(), 1);
      current = first().start();
    } else {
      fixture.fixture.prepare(6);
      for (int i = 0; i < 2; i++) {
        if (i > 0) fixture.fixture.choose(6, fixture.fixture.options(6, i).get(0), false);
        var b = fixture.fixture.start(6);
        assertSame(b, fixture.fixture.finish(b, BattleEndStatus.BATTLE_END_WIN, 1));
      }
      fixture.fixture.choose(6, fixture.fixture.options(6, 2).get(0), false);
      current = fixture.fixture.start(6);
    }
    var old = actor().getGameplay().getBoxingClubActivityQuests();
    var money = Map.copyOf(actor().amounts);
    actor().failSave = true;
    var service = new emu.lunarcore.game.battle.BattleService(null);
    assertThrows(
        IllegalStateException.class,
        () ->
            service.finishBattle(
                actor(),
                current.getId(),
                current.getStage().getId(),
                BattleEndStatus.BATTLE_END_WIN,
                emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance()
                    .setRoundCnt(1)));
    assertSame(old, actor().getGameplay().getBoxingClubActivityQuests());
    assertTrue(old.getCompleted().isEmpty());
    assertSame(current, actor().getBattle());
    assertEquals(money, actor().amounts);
    actor().failSave = false;
    assertSame(
        current,
        service.finishBattle(
            actor(),
            current.getId(),
            current.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance()
                .setRoundCnt(1)));
    assertEquals(1, state(challenge == 1 ? 6000060 : 6000370).getProgress());
    assertNull(
        service.finishBattle(
            actor(),
            current.getId(),
            current.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance()
                .setRoundCnt(1)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missingRun",
        "wrongKey",
        "module",
        "panel",
        "begin",
        "end",
        "future",
        "source",
        "uid",
        "battle"
      })
  void damagedCompletionIdentityOrWindowCannotPublishOrClaimRewards(String fault) throws Exception {
    earn(6, 3);
    var gson = ResourceLoader.getGson();
    var json =
        gson.toJsonTree(actor().getGameplay().getBoxingClubActivityQuests()).getAsJsonObject();
    var records = json.getAsJsonObject("completed");
    var proof = records.getAsJsonObject("6");
    switch (fault) {
      case "missingRun" -> proof.remove("second");
      case "wrongKey" -> {
        records.remove("6");
        records.add("5", proof);
      }
      case "module" -> proof.addProperty("module", 5000001);
      case "panel" -> proof.addProperty("panel", 50000);
      case "begin" -> proof.addProperty("begin", proof.get("acceptedAt").getAsLong() + 1);
      case "end" -> proof.addProperty("end", proof.get("acceptedAt").getAsLong());
      case "future" ->
          proof.addProperty("acceptedAt", java.time.Instant.now().getEpochSecond() + 100);
      case "source" -> proof.addProperty("source", "unmatched-source");
      case "uid" -> proof.getAsJsonObject("second").addProperty("uid", actor().getUid() + 1);
      case "battle" ->
          proof
              .getAsJsonObject("second")
              .getAsJsonArray("wins")
              .get(0)
              .getAsJsonObject()
              .addProperty("battle", 0);
    }
    actor()
        .getGameplay()
        .boxingClubActivityQuests(gson.fromJson(json, BoxingClubActivityQuestState.class));
    assertNull(state(6000370));
    assertFalse(QuestCompletionRecords.completed(actor(), 6000370));
    assertEquals(Retcode.QUEST_NOT_ACCEPT, actor().getQuestManager().claim(6000370).retcode());
  }

  @ParameterizedTest
  @ValueSource(ints = {6000060, 6000075, 6000370, 6000397})
  void aClientFinishedPhaseWithoutAnyWindowBoundVictoryCannotPublishCountOrClaim(int id) {
    var state = new QuestState(id);
    int goal = id == 6000075 ? 5 : id == 6000397 ? 7 : 1;
    state.advance(goal, goal, 123);
    actor().getGameplay().getBoxingClubActivityQuests().getQuests().put(id, state);
    assertNull(state(id));
    assertFalse(QuestCompletionRecords.completed(actor(), id));
    assertEquals(Retcode.QUEST_NOT_ACCEPT, actor().getQuestManager().claim(id).retcode());
    assertTrue(actor().amounts.isEmpty());
  }
}
