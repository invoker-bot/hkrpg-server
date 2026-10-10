package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.challenge.ChallengeInstance;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A completed memory battle remains valid history, but belongs to its admitted acceptance. */
class MemoryChallengeAcceptance46Test {
  final MissionChallengeWinTest fixture = new MissionChallengeWinTest();

  @BeforeEach
  void load() {
    fixture.resources();
  }

  @AfterEach
  void restore() {
    fixture.cleanup();
  }

  static void replace(Player player, int main, int sub, String mode, Gson gson) {
    var state = player.getGameplay();
    if (mode.equals("main") || mode.equals("both"))
      state.getMainMissions().put(main, new QuestState(main));
    if (mode.equals("sub") || mode.equals("both"))
      state.getSubMissions().put(sub, new QuestState(sub));
    if (mode.equals("mainClone"))
      state
          .getMainMissions()
          .put(
              main,
              gson.fromJson(gson.toJson(state.getMainMissions().get(main)), QuestState.class));
    if (mode.equals("subClone"))
      state
          .getSubMissions()
          .put(sub, gson.fromJson(gson.toJson(state.getSubMissions().get(sub)), QuestState.class));
  }

  void mission(boolean specific) {
    if (specific)
      fixture.mission(
          "ChallengeFinish",
          ",\"ParamType\":\"Equal\",\"ParamInt1\":" + MissionChallengeWinTest.CHALLENGE);
  }

  @ParameterizedTest
  @CsvSource({
    "false,sub", "false,main", "false,both", "false,subClone", "false,mainClone",
    "true,sub", "true,main", "true,both", "true,subClone", "true,mainClone"
  })
  void anAlreadyAdmittedWinCannotConsumeAReplacementAcceptance(boolean specific, String mode) {
    mission(specific);
    var player = fixture.player(true);
    var battle = fixture.start(player);
    var admittedMain = player.getGameplay().getMainMissions().get(MissionChallengeWinTest.MAIN);
    var admittedSub = player.getMissionManager().sub(MissionChallengeWinTest.SUB);
    assertNotNull(battle.getMissionChallengeWin());
    replace(player, MissionChallengeWinTest.MAIN, MissionChallengeWinTest.SUB, mode, fixture.gson);
    assertSame(battle, fixture.finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertTrue(
        player
            .getGameplay()
            .getCompletedMemoryChallenges()
            .contains(MissionChallengeWinTest.CHALLENGE));
    assertEquals(0, fixture.progress(player), "Old battle consumed a new acceptance record");
    assertEquals(0, player.saved.getSubMissions().get(MissionChallengeWinTest.SUB).getProgress());
    assertEquals(0, admittedMain.getProgress());
    assertEquals(0, admittedSub.getProgress());
    player.setInstance(
        new ChallengeInstance(
            player,
            emu.lunarcore.data.GameData.getChallengeExcelMap()
                .get(MissionChallengeWinTest.CHALLENGE)));
    player.getLineupManager().setCurrentExtraLineup(1, false);
    var fresh = fixture.start(player);
    assertSame(fresh, fixture.finish(player, fresh, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(
        1, fixture.progress(player), "A fresh actual battle should observe the replacement");
  }

  @Test
  void aChangedParentBindingCannotBorrowTheAdmittedDefinitionShape() {
    var player = fixture.player(true);
    var battle = fixture.start(player);
    int other = MissionChallengeWinTest.MAIN + 1;
    player.getGameplay().getMainMissions().put(other, new QuestState(other));
    GameplayDefinitions.subs.get(MissionChallengeWinTest.SUB).setMainMissionID(other);
    assertSame(battle, fixture.finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, fixture.progress(player));
    assertEquals(0, player.getGameplay().getMainMissions().get(other).getProgress());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void anEqualReloadedDefinitionWithTheSameAcceptanceCanStillConsumeItsWin(boolean specific) {
    mission(specific);
    var player = fixture.player(true);
    var battle = fixture.start(player);
    var config = GameplayDefinitions.subs.get(MissionChallengeWinTest.SUB);
    GameplayDefinitions.subs.put(
        config.getId(),
        fixture.gson.fromJson(fixture.gson.toJson(config), MissionInfo.SubMissionInfo.class));
    assertSame(battle, fixture.finish(player, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, fixture.progress(player));
  }
}
