package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndReasonOuterClass.BattleEndReason;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original tutorial objective 801710105 must keep its final-battle acceptance identity. */
class StoryChallengeAcceptance46Test {
  @TempDir Path directory;
  final StoryChallengeSettlementGoal46Test fixture = new StoryChallengeSettlementGoal46Test();

  @BeforeEach
  void load() throws Exception {
    fixture.directory = directory;
    fixture.setup();
  }

  @AfterEach
  void restore() throws Exception {
    fixture.cleanup();
  }

  @ParameterizedTest
  @CsvSource({
    "false,sub", "false,main", "false,both", "false,subClone", "false,mainClone",
    "true,sub", "true,main", "true,both", "true,subClone", "true,mainClone"
  })
  void anAlreadyAdmittedSettlementCannotConsumeAReplacementAcceptance(
      boolean turnLimit, String mode) throws Exception {
    var player = fixture.combat.player;
    var battle = fixture.combat.battle(2);
    var admittedMain =
        player.getGameplay().getMainMissions().get(StoryChallengeSettlementGoal46Test.MAIN);
    var admittedSub = fixture.state();
    assertTrue(
        battle.getStorySettlementTargets().containsKey(StoryChallengeSettlementGoal46Test.GOAL));
    MemoryChallengeAcceptance46Test.replace(
        player,
        StoryChallengeSettlementGoal46Test.MAIN,
        StoryChallengeSettlementGoal46Test.GOAL,
        mode,
        fixture.combat.gson);
    var stats = BattleStatistics.newInstance().setChallengeScore(23000);
    if (turnLimit) stats.setEndReason(BattleEndReason.BATTLE_END_REASON_TURN_LIMIT);
    assertSame(
        battle,
        fixture.combat.fixture.fixture.service.finishBattle(
            player,
            battle.getId(),
            battle.getStage().getId(),
            turnLimit ? BattleEndStatus.BATTLE_END_LOSE : BattleEndStatus.BATTLE_END_WIN,
            stats));
    assertEquals(
        0, fixture.state().getProgress(), "Old story settlement consumed a new acceptance");
    assertEquals(QuestState.Phase.DOING, fixture.state().getPhase());
    assertEquals(
        0,
        player.saved.getSubMissions().get(StoryChallengeSettlementGoal46Test.GOAL).getProgress());
    assertEquals(0, admittedMain.getProgress());
    assertEquals(0, admittedSub.getProgress());
    var fresh =
        new emu.lunarcore.game.challenge.ChallengeInstance(
            player, emu.lunarcore.data.GameData.getChallengeExcelMap().get(20011));
    fresh.addBuff(3031301);
    fresh.addBuff(3031302);
    player.setInstance(fresh);
    fixture.finish(2, BattleEndStatus.BATTLE_END_WIN, 23000);
    assertEquals(
        1,
        fixture.state().getProgress(),
        "Fresh actual story settlement should observe the replacement");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void theUnchangedAcceptanceAllowsAnEqualReloadedOriginalDefinition(boolean turnLimit)
      throws Exception {
    var battle = fixture.combat.battle(2);
    var config = fixture.config();
    var copy =
        fixture.combat.gson.fromJson(
            fixture.combat.gson.toJson(config), MissionInfo.SubMissionInfo.class);
    copy.getTask()
        .retainSourceDefinition(JsonParser.parseString(config.getTask().getSourceDefinition()));
    GameplayDefinitions.subs.put(copy.getId(), copy);
    var stats = BattleStatistics.newInstance().setChallengeScore(23000);
    if (turnLimit) stats.setEndReason(BattleEndReason.BATTLE_END_REASON_TURN_LIMIT);
    assertSame(
        battle,
        fixture.combat.fixture.fixture.service.finishBattle(
            fixture.combat.player,
            battle.getId(),
            battle.getStage().getId(),
            turnLimit ? BattleEndStatus.BATTLE_END_LOSE : BattleEndStatus.BATTLE_END_WIN,
            stats));
    assertEquals(1, fixture.state().getProgress());
  }
}
