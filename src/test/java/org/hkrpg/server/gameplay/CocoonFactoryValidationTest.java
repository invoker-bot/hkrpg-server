package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.*;

class CocoonFactoryValidationTest {
  static final int STAGE = 199969221;
  final MissionFarmingBattleTest fixture = new MissionFarmingBattleTest();
  StageExcel previous;

  @BeforeEach
  void before() {
    previous = GameData.getStageExcelMap().get(STAGE);
    stage("Cocoon", 1);
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    if (previous == null) GameData.getStageExcelMap().keySet().remove(STAGE);
    else GameData.getStageExcelMap().add(previous);
  }

  void stage(String type, int waves) {
    var monsters = java.util.Collections.nCopies(waves, "{\"Monster0\":1}");
    var row =
        fixture.gson.fromJson(
            "{\"StageID\":"
                + STAGE
                + ",\"StageType\":\""
                + type
                + "\",\"MonsterList\":["
                + String.join(",", monsters)
                + "]}",
            StageExcel.class);
    row.onLoad();
    GameData.getStageExcelMap().add(row);
  }

  void cocoon(String changes) {
    GameData.getCocoonExcelMap()
        .add(
            fixture.gson.fromJson(
                "{\"ID\":"
                    + MissionFarmingBattleTest.COCOON
                    + ",\"WorldLevel\":0,\"MappingInfoID\":12,\"CocoonType\":\"TYPE_RELIC\",\"StaminaCost\":10,\"MaxChallengeCnt\":6,"
                    + "\"StageIDList\":["
                    + STAGE
                    + "]"
                    + changes
                    + "}",
                CocoonExcel.class));
  }

  @Test
  void aMissingSelectedStageCannotSilentlyShortenAChargedChallenge() {
    var sequence = new java.util.concurrent.atomic.AtomicInteger();
    GameData.getCocoonExcelMap()
        .add(
            new CocoonExcel() {
              public int getPrimaryKey() {
                return MissionFarmingBattleTest.COCOON;
              }

              public int getSecondaryKey() {
                return 0;
              }

              public int getCocoonId() {
                return MissionFarmingBattleTest.COCOON;
              }

              public int getWorldLevel() {
                return 0;
              }

              public int getMappingInfoID() {
                return 12;
              }

              public int getMaxChallengeCnt() {
                return 6;
              }

              public int getStaminaCost() {
                return 10;
              }

              public IntArrayList getStageIDList() {
                return new IntArrayList(new int[] {STAGE, STAGE + 1});
              }

              public int getRandomStage() {
                return sequence.getAndIncrement() == 0 ? STAGE : STAGE + 1;
              }
            });
    var p = new MissionFarmingBattleTest.Farmer();
    assertNull(
        new MissionFarmingBattleTest.Service()
            .startCocoon(p, MissionFarmingBattleTest.COCOON, 0, 2));
    assertNull(p.getBattle());
    assertEquals(100, p.stamina);
  }

  @Test
  void negativeStaminaCostCannotCreateAFreeRewardBattle() {
    cocoon(",\"StaminaCost\":-10");
    var p = new MissionFarmingBattleTest.Farmer();
    assertNull(
        new MissionFarmingBattleTest.Service()
            .startCocoon(p, MissionFarmingBattleTest.COCOON, 0, 1));
    assertNull(p.getBattle());
    assertEquals(100, p.stamina);
  }

  @Test
  void overflowingStaminaCostCannotBypassTheBalanceCheck() {
    cocoon(",\"StaminaCost\":2147483647");
    var p = new MissionFarmingBattleTest.Farmer();
    assertNull(
        new MissionFarmingBattleTest.Service()
            .startCocoon(p, MissionFarmingBattleTest.COCOON, 0, 2));
    assertNull(p.getBattle());
    assertEquals(100, p.stamina);
  }

  @Test
  void aNonCocoonStageInACocoonResourceCannotStart() {
    stage("Mainline", 1);
    cocoon("");
    var p = new MissionFarmingBattleTest.Farmer();
    assertNull(
        new MissionFarmingBattleTest.Service()
            .startCocoon(p, MissionFarmingBattleTest.COCOON, 0, 1));
    assertNull(p.getBattle());
    assertEquals(100, p.stamina);
  }

  @Test
  void anUnboundedZeroCostChallengeResourceIsNotAccepted() {
    cocoon(",\"StaminaCost\":0,\"MaxChallengeCnt\":2147483647");
    var p = new MissionFarmingBattleTest.Farmer();
    assertNull(
        new MissionFarmingBattleTest.Service()
            .startCocoon(p, MissionFarmingBattleTest.COCOON, 0, 2));
    assertNull(p.getBattle());
    assertEquals(100, p.stamina);
  }

  @Test
  void nullEmptyInvalidPoolsAndEmptyMonsterGroupsFailBeforeStarting() {
    for (String change :
        java.util.List.of(
            ",\"StageIDList\":null",
            ",\"StageIDList\":[]",
            ",\"StageIDList\":[0]",
            ",\"MappingInfoID\":0",
            ",\"MaxChallengeCnt\":0")) {
      cocoon(change);
      var p = new MissionFarmingBattleTest.Farmer();
      assertNull(
          new MissionFarmingBattleTest.Service()
              .startCocoon(p, MissionFarmingBattleTest.COCOON, 0, 1),
          change);
      assertNull(p.getBattle());
      assertEquals(100, p.stamina);
    }
    stage("Cocoon", 0);
    cocoon("");
    var p = new MissionFarmingBattleTest.Farmer();
    assertNull(
        new MissionFarmingBattleTest.Service()
            .startCocoon(p, MissionFarmingBattleTest.COCOON, 0, 1));
    assertNull(p.getBattle());
  }

  @Test
  void validBatchClampsToTheResourceLimitAndLoadsEveryRelicMonsterGroup() {
    stage("Cocoon", 2);
    cocoon("");
    var p = new MissionFarmingBattleTest.Farmer();
    var service = new MissionFarmingBattleTest.Service();
    var b = service.startCocoon(p, MissionFarmingBattleTest.COCOON, 0, 99);
    assertNotNull(b);
    assertEquals(6, b.getCocoonWave());
    assertEquals(12, b.getWaves().size());
    assertEquals(60, b.getStaminaCost());
    assertEquals(100, p.stamina);
    assertNotNull(
        service.finishBattle(
            p,
            b.getId(),
            STAGE,
            emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
            emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance()));
    assertEquals(40, p.stamina);
    assertNull(
        service.finishBattle(
            p,
            b.getId(),
            STAGE,
            emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
            emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance()));
    assertEquals(40, p.stamina);
  }
}
