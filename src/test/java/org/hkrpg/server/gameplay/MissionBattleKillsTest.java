package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MissionBattleKillsTest {
  static final int MAIN = 19996923, SUB = 199969231, EVENT = 305001, STAGE = 3050010;
  static final int MONSTER = 800204001;
  final GameplayProgressTest missions = new GameplayProgressTest();
  final Gson gson = new Gson();
  final MissionFarmingBattleTest.Service service = new MissionFarmingBattleTest.Service();
  StageExcel oldStage;
  List<PlaneEventExcel> oldEvents;

  static class Fighter extends MissionFarmingBattleTest.Farmer {
    int plane = 10101, floor = 10101001, entry = 1010101;
    GameplayState saved;

    public int getPlaneId() {
      return plane;
    }

    public int getFloorId() {
      return floor;
    }

    public int getEntryId() {
      return entry;
    }

    public void save() {
      saved = new Gson().fromJson(new Gson().toJson(getGameplay()), GameplayState.class);
    }
  }

  @BeforeEach
  void resources() {
    oldStage = GameData.getStageExcelMap().get(STAGE);
    oldEvents =
        GameData.getPlaneEventExcelMap().values().stream()
            .filter(e -> e.getEventId() == EVENT)
            .toList();
    GameData.getPlaneEventExcelMap().values().removeIf(e -> e.getEventId() == EVENT);
    GameData.getPlaneEventExcelMap()
        .add(gson.fromJson("{\"EventID\":305001,\"StageID\":3050010}", PlaneEventExcel.class));
    var stage =
        gson.fromJson(
            "{\"StageID\":3050010,\"StageType\":\"Adventure\",\"MonsterList\":[{\"Monster0\":800204001,\"Monster1\":1011010}]}",
            StageExcel.class);
    stage.onLoad();
    GameData.getStageExcelMap().add(stage);
    mission();
  }

  void mission() {
    missions.mission(
        MAIN,
        "Manual",
        Integer.toString(SUB),
        "[{\"ID\":"
            + SUB
            + ",\"MainMissionID\":"
            + MAIN
            + ",\"TakeType\":\"Auto\",\"FinishType\":\"KillMonsterInBattle\",\"ParamType\":\"IntContainListContain\",\"ParamInt1\":305001,\"ParamIntList\":[800204001],\"LevelPlaneID\":10101,\"LevelFloorID\":10101001}]");
    script("305001", "false", "24");
  }

  void script(String event, String dynamic, String group) {
    GameplayDefinitions.subs
        .get(SUB)
        .setTask(
            gson.fromJson(
                "{\"Type\":\"Mission\",\"OnStartSequece\":[{\"TaskList\":[{\"$type\":\"RPG.GameCore.TriggerBattle\",\"EventID\":{\"IsDynamic\":"
                    + dynamic
                    + ",\"FixedValue\":{\"Value\":"
                    + event
                    + "}},\"GroupID\":{\"IsDynamic\":false,\"FixedValue\":{\"Value\":"
                    + group
                    + "}},\"BattleAreaID\":{\"IsDynamic\":false,\"FixedValue\":{\"Value\":1}}}]}]}",
                MissionTaskInfo.class));
  }

  @AfterEach
  void cleanup() {
    missions.reset();
    GameData.getMainMissionExcelMap().keySet().remove(MAIN);
    GameData.getMissionInfos().remove(MAIN);
    if (oldStage == null) GameData.getStageExcelMap().keySet().remove(STAGE);
    else GameData.getStageExcelMap().add(oldStage);
    GameData.getPlaneEventExcelMap().values().removeIf(e -> e.getEventId() == EVENT);
    oldEvents.forEach(GameData.getPlaneEventExcelMap()::add);
  }

  Fighter accepted() {
    var p = new Fighter();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    return p;
  }

  Battle start(Fighter p) {
    return Objects.requireNonNull(service.startBattle(p, EVENT, 0));
  }

  MissionBattleKills.Monster row(int entity, int monster, int stage, int death) {
    return new MissionBattleKills.Monster(entity, monster, stage, death);
  }

  Battle finish(Fighter p, Battle b, List<MissionBattleKills.Monster> rows) {
    return service.finishBattle(
        p, b.getId(), STAGE, BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance(), rows);
  }

  int progress(Fighter p) {
    return p.getMissionManager().sub(SUB).getProgress();
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void declaredEventWithActualRosterKillCompletesAndPersists(int death) {
    var p = accepted();
    var b = start(p);
    assertNotNull(b.getMissionBattleKills());
    assertNotNull(finish(p, b, List.of(row(5001, MONSTER, STAGE, death))));
    assertEquals(1, progress(p));
    assertEquals(1, p.saved.getSubMissions().get(SUB).getProgress());
    assertNull(finish(p, b, List.of(row(5001, MONSTER, STAGE, death))));
    assertEquals(1, progress(p));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 3, 4})
  void unknownEscapeAndUnknownNewDeathValuesCannotSubstituteForAKill(int death) {
    var p = accepted();
    var b = start(p);
    assertNotNull(finish(p, b, List.of(row(5001, MONSTER, STAGE, death))));
    assertEquals(0, progress(p));
  }

  @Test
  void frozenRosterSurvivesLaterResourceAndBattleWaveMutation() {
    var p = accepted();
    var b = start(p);
    b.getWaves().getFirst().getMonsters().clear();
    GameData.getStageExcelMap().get(STAGE).getMonsterWaves().clear();
    assertNotNull(finish(p, b, List.of(row(5001, MONSTER, STAGE, 1))));
    assertEquals(1, progress(p));
  }

  @Test
  void missingStatisticsWrongMonsterAndWrongStageStayPending() {
    for (var rows :
        List.of(
            List.<MissionBattleKills.Monster>of(),
            List.of(row(5001, MONSTER + 1, STAGE, 1)),
            List.of(row(5001, MONSTER, STAGE + 1, 1)))) {
      var p = accepted();
      var b = start(p);
      assertNotNull(finish(p, b, rows));
      assertEquals(0, progress(p));
    }
  }

  @Test
  void missingDynamicWrongEventAndWrongGroupScriptsNeverAuthorizeTheObjective() {
    for (int variant = 0; variant < 4; variant++) {
      mission();
      if (variant == 0) GameplayDefinitions.subs.get(SUB).setTask(null);
      if (variant == 1) script("305001", "true", "24");
      if (variant == 2) script("305002", "false", "24");
      if (variant == 3) script("305001", "false", "0");
      var p = accepted();
      var b = start(p);
      assertNull(b.getMissionBattleKills());
      assertNotNull(finish(p, b, List.of(row(5001, MONSTER, STAGE, 1))));
      assertEquals(0, progress(p));
    }
  }

  @Test
  void objectiveAcceptedAfterBattleStartWaitsForTheNextBattle() {
    var p = new Fighter();
    var b = start(p);
    assertNull(b.getMissionBattleKills());
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    assertNotNull(finish(p, b, List.of(row(5001, MONSTER, STAGE, 1))));
    assertEquals(0, progress(p));
    b = start(p);
    assertNotNull(finish(p, b, List.of(row(5001, MONSTER, STAGE, 1))));
    assertEquals(1, progress(p));
  }

  @Test
  void changedSceneCancelledObjectiveAndUnrecognizedParametersRemainPending() {
    for (int variant = 0; variant < 6; variant++) {
      mission();
      var p = accepted();
      var b = start(p);
      if (variant == 0) p.floor++;
      if (variant == 1) p.entry++;
      if (variant == 2) p.getGameplay().getCancelledSubs().add(SUB);
      if (variant == 3) GameplayDefinitions.subs.get(SUB).setParamInt2(1);
      if (variant == 4)
        GameplayDefinitions.subs.get(SUB).setParamIntList(new Integer[] {MONSTER, MONSTER + 1});
      if (variant == 5) p.getGameplay().getCancelledMissions().add(MAIN);
      assertNotNull(finish(p, b, List.of(row(5001, MONSTER, STAGE, 1))));
      assertEquals(0, progress(p), "variant=" + variant);
    }
  }

  @Test
  void duplicateEntitiesExcessRosterCountsAndInvalidEntitiesNeverCreditAKill() {
    for (var rows :
        List.of(
            List.of(row(5001, MONSTER, STAGE, 1), row(5001, 1011010, STAGE, 1)),
            List.of(row(5001, MONSTER, STAGE, 1), row(5002, MONSTER, STAGE, 1)),
            List.of(row(0, MONSTER, STAGE, 1)))) {
      var p = accepted();
      var b = start(p);
      assertNotNull(finish(p, b, rows));
      assertEquals(0, progress(p));
    }
  }

  @Test
  void wrongBattleIdentityAndInvalidAvatarStatsCanBeRetriedWithTheValidReport() {
    var p = accepted();
    var b = start(p);
    var rows = List.of(row(5001, MONSTER, STAGE, 1));
    assertNull(
        service.finishBattle(
            p,
            b.getId() + 1,
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance(),
            rows));
    var stats = BattleStatistics.newInstance();
    stats.addBattleAvatarList(
        emu.lunarcore.proto.AvatarBattleInfoOuterClass.AvatarBattleInfo.newInstance().setId(999));
    assertNull(
        service.finishBattle(p, b.getId(), STAGE, BattleEndStatus.BATTLE_END_WIN, stats, rows));
    assertFalse(b.isSettling());
    assertEquals(0, progress(p));
    assertNotNull(finish(p, b, rows));
    assertEquals(1, progress(p));
  }

  @Test
  void lossAndQuitDoNotClaimThisVictoryOnlySubset() {
    for (var result : List.of(BattleEndStatus.BATTLE_END_LOSE, BattleEndStatus.BATTLE_END_QUIT)) {
      var p = accepted();
      var b = start(p);
      assertNotNull(
          service.finishBattle(
              p,
              b.getId(),
              STAGE,
              result,
              BattleStatistics.newInstance(),
              List.of(row(5001, MONSTER, STAGE, 1))));
      assertEquals(0, progress(p));
      assertTrue(b.getReportedMonsters().isEmpty());
    }
  }
}
