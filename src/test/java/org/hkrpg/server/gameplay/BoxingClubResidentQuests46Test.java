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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Entry prerequisites are fixtures. Boxing completions are earned through real battle settlement.
 */
class BoxingClubResidentQuests46Test {
  @TempDir Path root;
  final BoxingClubResonance46Test fixture = new BoxingClubResonance46Test();
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldFinish = new ArrayList<>();

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldFinish.addAll(GameData.getFinishWayExcelMap().values());
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    var gson = ResourceLoader.getGson();
    var source = fixture.fixture.fixture("boxing-club-resident-quests-4.6.json");
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
        }
    }
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    oldFinish.forEach(GameData.getFinishWayExcelMap()::add);
    fixture.restore();
  }

  QuestState state(int id) {
    return fixture.fixture.session.actor.getQuestManager().states().stream()
        .filter(q -> q.getQuestId() == id)
        .findFirst()
        .orElse(null);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void threeEarnedWinsCompleteTheOriginalPermanentClearQuestAtEachWorld(int world)
      throws Exception {
    fixture.fixture.session.actor.world = world;
    fixture.prepare(6);
    fixture.threeWins(6, 1, 0);
    assertEquals(
        3,
        fixture
            .fixture
            .session
            .actor
            .getGameplay()
            .getBoxingClubResonance()
            .getCompleted()
            .get(6)
            .rounds());
    var earned = state(6000405);
    assertNotNull(earned);
    assertEquals(
        QuestState.Phase.FINISHED,
        earned.getPhase(),
        "Earned resident boxing quest remains pending");
    assertEquals(1, earned.getProgress());
  }

  void earn(int id, int total) throws Exception {
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(40003, fixture.challenge(id).module(), 1, Long.MAX_VALUE));
    fixture.prepare(id);
    for (int i = 0; i < 3; i++) {
      if (i > 0) fixture.choose(id, fixture.options(id, i).get(0), false);
      var battle = fixture.start(id);
      assertSame(
          battle, fixture.finish(battle, BattleEndStatus.BATTLE_END_WIN, i == 2 ? total - 2 : 1));
    }
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        6000405, 6000406, 6000407, 6000408, 6000409, 6000410, 6000411,
        6000415, 6000416, 6000417, 6000418, 6000419, 6000420, 6000421,
        6000422, 6000423, 6000424, 6000425, 6000426, 6000427, 6000428,
        6000429, 6000430, 6000431, 6000432, 6000433, 6000434, 6000435
      })
  void everyResidentGoalRequiresItsOwnCompletedChallenge(int quest) throws Exception {
    var finish =
        GameData.getFinishWayExcelMap()
            .get(GameData.getQuestExcelMap().get(quest).getFinishWayID());
    assertEquals(QuestState.Phase.DOING, state(quest).getPhase());
    int challenge = finish.getParamInt2();
    for (int id = 6; id < challenge; id++) earn(id, 3);
    assertEquals(0, state(quest).getProgress());
    earn(challenge, 3);
    assertEquals(1, state(quest).getProgress());
    assertEquals(QuestState.Phase.FINISHED, state(quest).getPhase());
    assertTrue(QuestCompletionRecords.completed(fixture.fixture.session.actor, quest));
  }

  @ParameterizedTest
  @CsvSource({"3,1,1,1", "5,1,1,1", "6,0,1,1", "7,0,0,1", "8,0,0,0", "20,0,0,0"})
  void originalFiveSixSevenLimitsUseCumulativeRoundsAndZeroMeansNoExtraLimit(
      int rounds, int five, int six, int seven) throws Exception {
    earn(6, rounds);
    assertEquals(1, state(6000405).getProgress());
    assertEquals(five, state(6000415).getProgress());
    assertEquals(six, state(6000422).getProgress());
    assertEquals(seven, state(6000429).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void TwoActualWinsDoNotCompleteAClearGoal(int world) throws Exception {
    fixture.fixture.session.actor.world = world;
    fixture.prepare(6);
    var first = fixture.start(6);
    assertSame(first, fixture.finish(first, BattleEndStatus.BATTLE_END_WIN, 1));
    fixture.choose(6, fixture.options(6, 1).get(0), false);
    var second = fixture.start(6);
    assertSame(second, fixture.finish(second, BattleEndStatus.BATTLE_END_WIN, 1));
    assertEquals(
        2,
        fixture.fixture.session.actor.getGameplay().getBoxingClubResonance().getActive().index());
    assertEquals(0, state(6000405).getProgress());
    assertEquals(QuestState.Phase.DOING, state(6000405).getPhase());
  }

  @ParameterizedTest
  @CsvSource({"6000398,7", "6000412,6", "6000413,5", "6000414,4"})
  void aggregatesCountDistinctSourceChildrenAndDoNotAdvanceOnRepeatedClears(int quest, int count)
      throws Exception {
    for (int id = 6; id < 6 + count - 1; id++) earn(id, 3);
    assertEquals(count - 1, state(quest).getProgress());
    assertEquals(QuestState.Phase.DOING, state(quest).getPhase());
    earn(6, 3);
    assertEquals(count - 1, state(quest).getProgress());
    earn(6 + count - 1, 3);
    assertEquals(count, state(quest).getProgress());
    assertEquals(QuestState.Phase.FINISHED, state(quest).getPhase());
    assertTrue(fixture.fixture.session.actor.amounts.isEmpty());
  }

  @Test
  void improvedBestCrossesOnlyTheOriginalThresholds() throws Exception {
    earn(6, 8);
    assertEquals(0, state(6000415).getProgress());
    assertEquals(0, state(6000422).getProgress());
    assertEquals(0, state(6000429).getProgress());
    earn(6, 7);
    assertEquals(1, state(6000429).getProgress());
    assertEquals(0, state(6000422).getProgress());
    earn(6, 6);
    assertEquals(1, state(6000422).getProgress());
    assertEquals(0, state(6000415).getProgress());
    earn(6, 5);
    assertEquals(1, state(6000415).getProgress());
    earn(6, 20);
    assertEquals(
        5,
        fixture
            .fixture
            .session
            .actor
            .getGameplay()
            .getBoxingClubResonance()
            .getCompleted()
            .get(6)
            .rounds());
    assertEquals(1, state(6000415).getProgress());
  }

  @Test
  void fourOriginalRewardsGrantExactTotalsOnceAndSurviveWindowClosure() throws Exception {
    for (int id = 6; id <= 12; id++) earn(id, 3);
    GameplayDefinitions.windows.clear();
    var actor = fixture.fixture.session.actor;
    for (int id : new int[] {6000398, 6000412, 6000413, 6000414}) {
      assertEquals(QuestState.Phase.FINISHED, state(id).getPhase());
      assertEquals(Retcode.SUCC, actor.getQuestManager().claim(id).retcode());
      assertEquals(QuestState.Phase.CLOSED, state(id).getPhase());
      assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getQuestManager().claim(id).retcode());
    }
    assertEquals(Map.of(1, 100, 2, 120000, 213, 12), actor.amounts);
    assertEquals(32, BoxingClubResidentQuests.states(actor).size());
  }

  @ParameterizedTest
  @ValueSource(ints = {6000405, 6000415, 6000422, 6000429, 6000398, 6000412, 6000413, 6000414})
  void fakeFinishedStateCannotBePublishedCountedOrClaimed(int id) {
    var actor = fixture.fixture.session.actor;
    var definition = GameData.getQuestExcelMap().get(id);
    int goal = GameData.getFinishWayExcelMap().get(definition.getFinishWayID()).getProgress();
    var fake = new QuestState(id);
    fake.advance(goal, goal, 123);
    actor.getQuestStates().put(id, fake);
    assertNull(state(id));
    assertFalse(QuestCompletionRecords.completed(actor, id));
    assertEquals(Retcode.QUEST_NOT_ACCEPT, actor.getQuestManager().claim(id).retcode());
    assertTrue(actor.amounts.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "QuestData",
        "FinishWay",
        "RewardData",
        "ItemConfig",
        "BoxingClubConstValueClient"
      })
  void changedCompleteSourceCannotBorrowAnEarnedRecordAndRepairRestoresIt(String table)
      throws Exception {
    earn(6, 3);
    var file = root.resolve("ExcelOutput/" + table + ".json");
    String original = Files.readString(file);
    var rows = JsonParser.parseString(original).getAsJsonArray();
    var changed = rows.get(0).getAsJsonObject();
    if (table.equals("RewardData"))
      changed =
          rows.asList().stream()
              .map(JsonElement::getAsJsonObject)
              .filter(r -> r.get("RewardID").getAsInt() == 100)
              .findFirst()
              .orElseThrow();
    if (table.equals("BoxingClubConstValueClient"))
      changed =
          rows.asList().stream()
              .map(JsonElement::getAsJsonObject)
              .filter(
                  r ->
                      r.get("ConstValueName")
                          .getAsString()
                          .equals("BoxingClubResonance_ResidentQuestList"))
              .findFirst()
              .orElseThrow();
    changed.addProperty("UnmatchedExtraSourceField", 1);
    Files.writeString(file, rows.toString());
    assertNull(state(6000405));
    assertEquals(
        Retcode.QUEST_NOT_ACCEPT,
        fixture.fixture.session.actor.getQuestManager().claim(6000405).retcode());
    assertTrue(fixture.fixture.session.actor.amounts.isEmpty());
    Files.writeString(file, original);
    assertEquals(1, state(6000405).getProgress());
  }

  @Test
  void boundLossAndUnselectedRosterDoNotManufactureACompletion() throws Exception {
    fixture.seed(6);
    assertEquals(0, state(6000405).getProgress());
    fixture.choose(6, fixture.resonance(6), true);
    var battle = fixture.start(6);
    assertSame(battle, fixture.finish(battle, BattleEndStatus.BATTLE_END_LOSE, 0));
    assertNull(fixture.fixture.session.actor.getGameplay().getBoxingClubResonance().getActive());
    assertEquals(0, state(6000405).getProgress());
  }

  @Test
  void damagedAcceptedBattleProofCannotBacktrackIntoResidentGoals() throws Exception {
    earn(6, 3);
    var actor = fixture.fixture.session.actor;
    var gson = ResourceLoader.getGson();
    var json = gson.toJsonTree(actor.getGameplay().getBoxingClubResonance()).getAsJsonObject();
    json.getAsJsonObject("completed")
        .getAsJsonObject("6")
        .getAsJsonArray("wins")
        .get(0)
        .getAsJsonObject()
        .addProperty("battle", 0);
    actor.getGameplay().boxingClubResonance(gson.fromJson(json, BoxingClubResonanceState.class));
    assertNull(state(6000405));
    assertEquals(Retcode.QUEST_NOT_ACCEPT, actor.getQuestManager().claim(6000405).retcode());
    assertTrue(actor.amounts.isEmpty());
  }
}
