package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original 204310700 battle family; only drops and scene construction are isolated. */
class RaidStageWinAcceptance46Test {
  @TempDir Path root;
  final DefaultHybridMissionRaid46Test hybrid = new DefaultHybridMissionRaid46Test();
  final List<StageExcel> oldStages = new ArrayList<>();

  static class Service extends BattleService {
    Service() {
      super(null);
    }

    @Override
    protected void grantDrops(Battle battle) {}
  }

  @BeforeEach
  void prepare() throws Exception {
    hybrid.root = root;
    hybrid.prepare();
    oldStages.addAll(GameData.getStageExcelMap().values());
    GameData.getStageExcelMap().values().clear();
    for (var raw :
        hybrid
            .source
            .helper
            .original
            .getAsJsonObject("records")
            .getAsJsonArray("ExcelOutput/StageConfig.json")) {
      var stage = ResourceLoader.getGson().fromJson(raw, StageExcel.class);
      stage.onLoad();
      GameData.getStageExcelMap().add(stage);
    }
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getStageExcelMap().values().clear();
    oldStages.forEach(GameData.getStageExcelMap()::add);
    hybrid.restore();
  }

  private void enter(boolean trial) {
    var player = hybrid.player;
    if (!trial) {
      var role = new GameAvatar(GameData.getAvatarExcelMap().get(1409));
      role.setOwner(player);
      player.getAvatars().getAvatars().put(1409, role);
      player.getCurrentLineup().getAvatars().clear();
      player.getCurrentLineup().getAvatars().add(1409);
    }
    assertEquals(
        Retcode.SUCC,
        new MissionRaid(player)
            .enterSelected(
                40431,
                0,
                0,
                0,
                trial ? List.of(new RaidTeams.Selection(1021409, true)) : List.of()));
  }

  private Battle battle() {
    var stage = GameData.getStageExcelMap().get(204310700);
    assertNotNull(stage);
    var battle = new Battle(hybrid.player, hybrid.player.getCurrentLineup(), stage);
    assertFalse(battle.getWaves().isEmpty());
    hybrid.player.setBattle(battle);
    return battle;
  }

  private boolean matches(Battle battle) {
    return battle
        .getMissionStageWins()
        .matches(hybrid.player, battle, GameplayDefinitions.subs.get(104040830));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualOriginalBattleVictoryAdvancesTheOwnedRaidGoalAndItsFloorAction(boolean trial) {
    enter(trial);
    var battle = battle();
    assertNotNull(
        new Service()
            .finishBattle(
                hybrid.player,
                battle.getId(),
                battle.getStage().getId(),
                BattleEndStatus.BATTLE_END_WIN,
                BattleStatistics.newInstance()));
    assertEquals(1, hybrid.player.getMissionManager().sub(104040830).getProgress());
    assertEquals(
        9, hybrid.player.getGameplay().getStoryValues().get("floor:40431001:FSV_SunState"));
    assertNull(
        new Service()
            .finishBattle(
                hybrid.player,
                battle.getId(),
                battle.getStage().getId(),
                BattleEndStatus.BATTLE_END_WIN,
                BattleStatistics.newInstance()));
  }

  @Test
  void validRaidAcceptanceIsCapturedAtTheActualBattleStart() {
    enter(true);
    assertTrue(matches(battle()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"main", "sub", "run", "scene", "leave", "action", "source", "party"})
  void staleBattleCannotBorrowReplacementAcceptancesResourcesOrRaidOwnership(String changed)
      throws Exception {
    enter(true);
    var battle = battle();
    var player = hybrid.player;
    var state = player.getGameplay();
    var run = new MissionRaid(player).current();
    switch (changed) {
      case "main" -> state.getMainMissions().put(1040408, new QuestState(1040408));
      case "sub" -> state.getSubMissions().put(104040830, new QuestState(104040830));
      case "run" -> {
        var copy = new Gson().fromJson(new Gson().toJson(run), GameplayState.MissionRaidRun.class);
        state.getMissionRaids().put(40431, copy);
      }
      case "scene" -> hybrid.source.helper.position(player, 40431001);
      case "leave" -> state.endMissionRaid();
      case "action" ->
          MissionTrialProvisioning46Test.setField(
              GameplayDefinitions.subs.get(104040830),
              emu.lunarcore.data.config.MissionInfo.SubMissionInfo.class,
              "finishActionList",
              new emu.lunarcore.data.config.MissionInfo.FinishAction[0]);
      case "source" -> {
        var file = root.resolve("ExcelOutput/RaidConfig.json");
        var rows = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
        rows.get(0).getAsJsonObject().addProperty("ChangedRawEntrance", 1);
        Files.writeString(file, rows.toString());
      }
      case "party" -> run.getTrialParty().getConfigs().put(1021409, "{}");
      default -> fail("Unknown mutation");
    }
    assertFalse(matches(battle));
    assertEquals(0, state.getSubMissions().get(104040830).getProgress());
    assertFalse(state.getStoryValues().containsKey("floor:40431001:FSV_SunState"));
  }
}
