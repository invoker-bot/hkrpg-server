package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndReasonOuterClass.BattleEndReason;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Complete original tutorial definitions. Parent acceptance and its intro are explicit fixtures.
 */
class StoryChallengeSettlementGoal46Test {
  static final int MAIN = 8017101, GOAL = 801710105;
  final StoryChallengeScope46Test combat = new StoryChallengeScope46Test();
  @TempDir Path directory;
  Map<Integer, MissionInfo.SubMissionInfo> oldSubs;
  MainMissionExcel oldMain;
  MissionInfo oldInfo;
  MissionInfo info;

  @BeforeEach
  void setup() throws Exception {
    oldSubs = Map.copyOf(GameplayDefinitions.subs);
    oldMain = GameData.getMainMissionExcelMap().get(MAIN);
    oldInfo = GameData.getMissionInfos().get(MAIN);
    combat.directory = directory.resolve("combat");
    combat.setup();
    var input = new HashMap<String, JsonElement>();
    try (var in =
        getClass().getResourceAsStream("/fixtures/story-challenge-settlement-goal-4.6.json")) {
      assertNotNull(in);
      var fixture =
          JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      assertEquals(
          "724b139d8c9c32d12552eb95745a4fee72bfe48b", fixture.get("sourceCommit").getAsString());
      for (var raw : fixture.getAsJsonArray("inputs")) {
        var row = raw.getAsJsonObject();
        input.put(row.get("path").getAsString(), row.get("data"));
      }
    }
    var source =
        input
            .get("Config/Level/Mission/8017101/MissionInfo_8017101.json")
            .deepCopy()
            .getAsJsonObject();
    input
        .get("Config/Level/Mission/8017101/MissionInfo_8017101.partial.json")
        .getAsJsonObject()
        .entrySet()
        .forEach(e -> source.add(e.getKey(), e.getValue()));
    info = combat.gson.fromJson(source, MissionInfo.class);
    GameData.getMissionInfos().put(MAIN, info);
    GameData.getMainMissionExcelMap()
        .add(
            combat.gson.fromJson(
                input.get("ExcelOutput/MainMission.json"), MainMissionExcel.class));
    for (var sub : info.getSubMissionList()) {
      var script = input.get(sub.getMissionJsonPath());
      assertNotNull(script);
      sub.setTask(combat.gson.fromJson(script, MissionTaskInfo.class));
      sub.getTask().retainSourceDefinition(script);
      GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    // Acceptance/intro fixtures never prewrite the target completion or challenge history.
    combat.player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    for (int id : List.of(801710101, 801710102)) {
      var state = new QuestState(id);
      state.advance(1, 1, 1);
      combat.player.getGameplay().getSubMissions().put(id, state);
    }
    combat.player.getGameplay().getSubMissions().put(GOAL, new QuestState(GOAL));
    combat.player.getMissionManager().refresh();
    assertEquals(QuestState.Phase.DOING, state().getPhase());
    assertEquals(0, state().getProgress());
  }

  @AfterEach
  void cleanup() throws Exception {
    combat.cleanup();
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getMainMissionExcelMap().keySet().remove(MAIN);
    if (oldMain != null) GameData.getMainMissionExcelMap().add(oldMain);
    GameData.getMissionInfos().remove(MAIN);
    if (oldInfo != null) GameData.getMissionInfos().put(MAIN, oldInfo);
  }

  QuestState state() {
    return combat.player.getMissionManager().sub(GOAL);
  }

  MissionInfo.SubMissionInfo config() {
    return GameplayDefinitions.subs.get(GOAL);
  }

  Battle finish(int half, BattleEndStatus result, int score) throws Exception {
    var battle = combat.battle(half);
    var stats = BattleStatistics.newInstance().setChallengeScore(score);
    if (result == BattleEndStatus.BATTLE_END_LOSE)
      stats.setEndReason(BattleEndReason.BATTLE_END_REASON_TURN_LIMIT);
    assertNotNull(
        combat.fixture.fixture.service.finishBattle(
            combat.player, battle.getId(), battle.getStage().getId(), result, stats));
    return battle;
  }

  @ParameterizedTest
  @ValueSource(strings = {"win", "turnLimit", "zeroStar"})
  void anAcceptedOriginalGoalConsumesOnlyTheFinalCompletedStorySettlement(String mode)
      throws Exception {
    var battle =
        finish(
            2,
            mode.equals("turnLimit")
                ? BattleEndStatus.BATTLE_END_LOSE
                : BattleEndStatus.BATTLE_END_WIN,
            mode.equals("zeroStar") ? 12000 : 23000);
    assertEquals(QuestState.Phase.FINISHED, state().getPhase());
    assertEquals(1, state().getProgress());
    assertTrue(state().getFinishTime() > 0);
    assertEquals(
        state().getFinishTime(), combat.player.saved.getSubMissions().get(GOAL).getFinishTime());
    long time = state().getFinishTime();
    assertNull(
        combat.fixture.fixture.service.finishBattle(
            combat.player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance().setChallengeScore(23000)));
    combat.player.getMissionManager().refresh();
    assertEquals(time, state().getFinishTime());
  }

  @ParameterizedTest
  @ValueSource(strings = {"firstHalf", "quit", "defeat", "legacy"})
  void incompleteOrUnboundStoryDoesNotFinishTheGoal(String mode) throws Exception {
    if (mode.equals("legacy")) combat.player.nativeProtocol = false;
    int half = mode.equals("firstHalf") ? 1 : 2;
    var battle = combat.battle(half);
    var stats = BattleStatistics.newInstance().setChallengeScore(23000);
    var result =
        mode.equals("quit")
            ? BattleEndStatus.BATTLE_END_QUIT
            : mode.equals("defeat")
                ? BattleEndStatus.BATTLE_END_LOSE
                : BattleEndStatus.BATTLE_END_WIN;
    if (!mode.equals("defeat")) stats.setEndReason(BattleEndReason.BATTLE_END_REASON_TURN_LIMIT);
    assertNotNull(
        combat.fixture.fixture.service.finishBattle(
            combat.player, battle.getId(), battle.getStage().getId(), result, stats));
    assertEquals(QuestState.Phase.DOING, state().getPhase());
    assertEquals(0, state().getProgress());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "type",
        "comparison",
        "amount",
        "backTrack",
        "parameter",
        "string",
        "place",
        "storyLine",
        "action"
      })
  void unsupportedOriginalGoalVariantsRemainPending(String mode) throws Exception {
    var c = config();
    switch (mode) {
      case "type" -> c.setFinishType("ChallengeFinishCnt");
      case "comparison" -> c.setParamType("Equal");
      case "amount" -> c.setProgress(2);
      case "backTrack" -> c.setBackTrack(true);
      case "parameter" -> c.setParamInt1(1);
      case "string" -> c.setParamStr1("unknown");
      case "place" -> c.setLevelPlaneID(30102);
      case "storyLine" -> c.setStoryLineID(1);
      case "action" ->
          c.setFinishActionList(
              new MissionInfo.FinishAction[] {
                combat.gson.fromJson(
                    "{\"FinishActionType\":\"SetCustomValue\",\"FinishActionParaString\":[\"unexpected\",\"1\"]}",
                    MissionInfo.FinishAction.class)
              });
      default -> fail();
    }
    finish(2, BattleEndStatus.BATTLE_END_WIN, 23000);
    assertEquals(QuestState.Phase.DOING, state().getPhase());
    assertEquals(0, state().getProgress());
  }

  @ParameterizedTest
  @ValueSource(strings = {"cancelGoal", "cancelParent", "resource", "script", "lateAcceptance"})
  void aGoalMustStillMatchWhatWasAcceptedAtBattleStart(String mode) throws Exception {
    if (mode.equals("lateAcceptance")) combat.player.getGameplay().getSubMissions().remove(GOAL);
    var battle = combat.battle(2);
    switch (mode) {
      case "cancelGoal" -> combat.player.getGameplay().getCancelledSubs().add(GOAL);
      case "cancelParent" -> combat.player.getGameplay().getCancelledMissions().add(MAIN);
      case "resource" -> config().setTakeParamIntList(new Integer[] {801710102});
      case "script" ->
          config()
              .getTask()
              .retainSourceDefinition(
                  JsonParser.parseString("{\"Type\":\"Mission\",\"OnStartSequece\":[]}"));
      case "lateAcceptance" ->
          combat.player.getGameplay().getSubMissions().put(GOAL, new QuestState(GOAL));
      default -> fail();
    }
    assertNotNull(
        combat.fixture.fixture.service.finishBattle(
            combat.player,
            battle.getId(),
            battle.getStage().getId(),
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance().setChallengeScore(23000)));
    assertEquals(QuestState.Phase.DOING, state().getPhase());
    assertEquals(0, state().getProgress());
  }

  @Test
  void existingChallengeHistoryNeverBacktracksAnUncompletedGoal() {
    var history = new emu.lunarcore.game.challenge.ChallengeHistory(combat.player, 20011);
    history.setStars(1);
    history.setScore(40000);
    history.setStoryScores(12000, 28000);
    combat.player.admission.getHistory().put(20011, history);
    combat.player.getMissionManager().refresh();
    assertEquals(QuestState.Phase.DOING, state().getPhase());
    assertEquals(0, state().getProgress());
  }

  @Test
  void originalFollowupTalkWritesTheSourceEntranceActionOnlyAfterActualSettlement()
      throws Exception {
    finish(2, BattleEndStatus.BATTLE_END_WIN, 23000);
    assertEquals(QuestState.Phase.FINISHED, state().getPhase());
    assertEquals(
        QuestState.Phase.FINISHED, combat.player.getMissionManager().sub(801710103).getPhase());
    assertEquals(
        QuestState.Phase.DOING, combat.player.getMissionManager().sub(801710104).getPhase());
    assertFalse(
        combat
            .player
            .getGameplay()
            .getStoryValues()
            .containsKey("floor:10202001:ChallengeStoryEntrance"));
    combat.player.plane = 10202;
    combat.player.floor = 10202001;
    combat.player.entry = 102020107;
    combat.player.setInstance(null);
    assertEquals(
        Retcode.SUCC, combat.player.getMissionManager().finishTalk(801710104, "Mission_801710104"));
    assertEquals(
        1,
        combat.player.getGameplay().getStoryValues().get("floor:10202001:ChallengeStoryEntrance"));
    assertEquals(1, combat.player.getGameplay().getStoryActions().get(801710104).getNext());
    assertEquals(
        1, combat.player.saved.getStoryValues().get("floor:10202001:ChallengeStoryEntrance"));
    long time = combat.player.getMissionManager().sub(801710104).getFinishTime();
    assertNotEquals(
        Retcode.SUCC, combat.player.getMissionManager().finishTalk(801710104, "Mission_801710104"));
    assertEquals(time, combat.player.getMissionManager().sub(801710104).getFinishTime());
  }
}
