package emu.lunarcore.game.battle;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.BasePacket;
import it.unimi.dsi.fastutil.ints.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BattleSettlementTest {
  static class TestPlayer extends Player {
    final emu.lunarcore.game.player.lineup.LineupManager lineups =
        new emu.lunarcore.game.player.lineup.LineupManager(this);

    public emu.lunarcore.game.player.lineup.LineupManager getLineupManager() {
      return lineups;
    }

    public void save() {}

    public void sendPacket(BasePacket packet) {}

    public boolean removeFoodBuffs(int type) {
      return false;
    }
  }

  static class Service extends BattleService {
    int grants;

    Service() {
      super(null);
    }

    protected void grantDrops(Battle battle) {
      grants++;
    }
  }

  static Battle battle(Player player) {
    BattleStage stage =
        new BattleStage() {
          public int getId() {
            return 123;
          }

          public StageType getStageType() {
            return StageType.FarmElement;
          }

          public List<IntList> getMonsterWaves() {
            return List.of(new IntArrayList(new int[] {1}), new IntArrayList(new int[] {2}));
          }
        };
    return new Battle(player, new PlayerLineup(player, 0), stage);
  }

  @Test
  void rejectedNativeReportsIdentifyTheActualFailedGuardWithoutSettling() throws Exception {
    var player = new TestPlayer();
    var lineup = new PlayerLineup(player, 0);
    lineup.getAvatars().add(2000091);
    BattleStage stage = new BattleStage() {
      public int getId() { return 312040; }
      public StageType getStageType() { return StageType.Trial; }
      public List<IntList> getMonsterWaves() { return List.of(); }
    };
    var battle = new Battle(player, lineup, stage);
    player.setBattle(battle);
    var service = new Service();
    var method = BattleService.class.getMethod("rejectionReason", Player.class, int.class, int.class,
        BattleEndStatus.class, BattleStatistics.class, List.class, List.class);
    var good = emu.lunarcore.proto.AvatarPropertyOuterClass.AvatarProperty.newInstance()
        .setMaxHp(1000).setLeftHp(500).setMaxSp(100).setLeftSp(50);
    var actor = emu.lunarcore.proto.AvatarBattleInfoOuterClass.AvatarBattleInfo.newInstance()
        .setId(2000091).setAvatarStatus(good);
    var stats = BattleStatistics.newInstance().addBattleAvatarList(actor);
    assertNull(method.invoke(service, player, battle.getId(), 312040, BattleEndStatus.BATTLE_END_WIN,
        stats, List.of(), List.of()));
    assertEquals("battle-identity", method.invoke(service, player, battle.getId() + 1, 312040,
        BattleEndStatus.BATTLE_END_WIN, stats, List.of(), List.of()));
    actor.setAvatarStatus(emu.lunarcore.proto.AvatarPropertyOuterClass.AvatarProperty.newInstance()
        .setMaxHp(0).setLeftHp(0).setMaxSp(100).setLeftSp(0));
    var invalidHealth = BattleStatistics.newInstance().addBattleAvatarList(actor);
    assertEquals("avatar-health-max", method.invoke(service, player, battle.getId(), 312040,
        BattleEndStatus.BATTLE_END_WIN, invalidHealth, List.of(), List.of()));
    actor.setAvatarStatus(emu.lunarcore.proto.AvatarPropertyOuterClass.AvatarProperty.newInstance()
        .setMaxHp(1000).setLeftHp(500).setMaxSp(100).setLeftSp(101));
    var invalidEnergy = BattleStatistics.newInstance().addBattleAvatarList(actor);
    assertEquals("avatar-energy-range", method.invoke(service, player, battle.getId(), 312040,
        BattleEndStatus.BATTLE_END_WIN, invalidEnergy, List.of(), List.of()));
    assertSame(battle, player.getBattle());
    assertEquals(0, service.grants);
  }

  @Test
  void validatedLossAndQuitAdvanceOnlyTheirStageAndCannotReplay() {
    var gson = new com.google.gson.Gson();
    var main =
        gson.fromJson(
            """
{"MainMissionID":19996401,"TakeParam":[{"Type":"Manual"}],"BeginParam":[{"Type":"Auto"}]}
""",
            emu.lunarcore.data.excel.MainMissionExcel.class);
    var info =
        gson.fromJson(
            """
{"MainMissionID":19996401,"FinishSubMissionList":[199964011,199964012],"SubMissionList":[
  {"ID":199964011,"MainMissionID":19996401,"TakeType":"Auto","FinishType":"BattleLose","ParamType":"Equal","ParamInt1":123,"Progress":2},
  {"ID":199964012,"MainMissionID":19996401,"TakeType":"Auto","FinishType":"BattleQuit","ParamType":"Equal","ParamInt1":123},
  {"ID":199964013,"MainMissionID":19996401,"TakeType":"Auto","FinishType":"BattleLose","ParamType":"Equal","ParamInt1":124},
  {"ID":199964014,"MainMissionID":19996401,"TakeType":"Auto","FinishType":"BattleLose","ParamType":"NotEqual","ParamInt1":123}]}
""",
            emu.lunarcore.data.config.MissionInfo.class);
    emu.lunarcore.data.GameData.getMainMissionExcelMap().add(main);
    emu.lunarcore.data.GameData.getMissionInfos().put(main.getId(), info);
    for (var sub : info.getSubMissionList())
      org.hkrpg.server.gameplay.GameplayDefinitions.subs.put(sub.getId(), sub);
    try {
      var player = new TestPlayer();
      var service = new Service();
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SUCC,
          player.getMissionManager().accept(main.getId()));
      var first = battle(player);
      player.setBattle(first);
      assertNull(
          service.finishBattle(
              player,
              first.getId() + 1,
              123,
              BattleEndStatus.BATTLE_END_LOSE,
              BattleStatistics.newInstance()));
      assertEquals(0, player.getMissionManager().sub(199964011).getProgress());
      assertNotNull(
          service.finishBattle(
              player,
              first.getId(),
              123,
              BattleEndStatus.BATTLE_END_LOSE,
              BattleStatistics.newInstance()));
      assertEquals(1, player.getMissionManager().sub(199964011).getProgress());
      assertNull(
          service.finishBattle(
              player,
              first.getId(),
              123,
              BattleEndStatus.BATTLE_END_LOSE,
              BattleStatistics.newInstance()));
      assertEquals(1, player.getMissionManager().sub(199964011).getProgress());
      assertEquals(0, player.getMissionManager().sub(199964012).getProgress());
      assertEquals(0, player.getMissionManager().sub(199964013).getProgress());
      assertEquals(0, player.getMissionManager().sub(199964014).getProgress());
      var second = battle(player);
      player.setBattle(second);
      assertNotNull(
          service.finishBattle(
              player,
              second.getId(),
              123,
              BattleEndStatus.BATTLE_END_LOSE,
              BattleStatistics.newInstance()));
      var third = battle(player);
      player.setBattle(third);
      assertNotNull(
          service.finishBattle(
              player,
              third.getId(),
              123,
              BattleEndStatus.BATTLE_END_QUIT,
              BattleStatistics.newInstance()));
      assertTrue(player.getMissionManager().finished(main.getId()));
      assertEquals(0, service.grants);
    } finally {
      for (var sub : info.getSubMissionList())
        org.hkrpg.server.gameplay.GameplayDefinitions.subs.remove(sub.getId());
      emu.lunarcore.data.GameData.getMissionInfos().remove(main.getId());
      emu.lunarcore.data.GameData.getMainMissionExcelMap().keySet().remove(main.getId());
    }
  }

  @Test
  void staleIdentityCannotSettleAndConcurrentWinsGrantOnce() throws Exception {
    var player = new TestPlayer();
    var battle = battle(player);
    player.setBattle(battle);
    var service = new Service();
    var stats = BattleStatistics.newInstance();
    assertNull(
        service.finishBattle(
            player, battle.getId() + 1, 123, BattleEndStatus.BATTLE_END_WIN, stats));
    assertNull(
        service.finishBattle(player, battle.getId(), 124, BattleEndStatus.BATTLE_END_WIN, stats));
    assertSame(battle, player.getBattle());
    assertEquals(0, service.grants);
    try (var pool = Executors.newFixedThreadPool(6)) {
      var jobs = new ArrayList<Callable<Battle>>();
      for (int i = 0; i < 12; i++)
        jobs.add(
            () ->
                service.finishBattle(
                    player, battle.getId(), 123, BattleEndStatus.BATTLE_END_WIN, stats));
      int success = 0;
      for (var job : pool.invokeAll(jobs)) if (job.get() != null) success++;
      assertEquals(1, success);
    }
    assertEquals(1, service.grants);
    assertNull(player.getBattle());
  }

  @Test
  void snapshotsKeepSeedAndOrderedWavesAndQuitGrantsNothing() {
    var player = new TestPlayer();
    var battle = battle(player);
    var service = new Service();
    player.setBattle(battle);
    assertEquals(battle.toProto().getLogicRandomSeed(), battle.toProto().getLogicRandomSeed());
    assertEquals(2, battle.toProto().getMonsterWaveList().get(1).getWaveId());
    assertNotNull(
        service.finishBattle(
            player,
            battle.getId(),
            123,
            BattleEndStatus.BATTLE_END_QUIT,
            BattleStatistics.newInstance()));
    assertEquals(0, service.grants);
  }

  @Test
  void foreignAvatarStatisticsAndUnaffordableStaminaCannotSettle() {
    var player = new TestPlayer();
    var battle = battle(player);
    player.setBattle(battle);
    var service = new Service();
    var stats =
        BattleStatistics.newInstance()
            .addBattleAvatarList(
                emu.lunarcore.proto.AvatarBattleInfoOuterClass.AvatarBattleInfo.newInstance()
                    .setId(999));
    assertNull(
        service.finishBattle(player, battle.getId(), 123, BattleEndStatus.BATTLE_END_WIN, stats));
    assertSame(battle, player.getBattle());
    battle.setStaminaCost(player.getStamina() + 1);
    assertNull(
        service.finishBattle(
            player,
            battle.getId(),
            123,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertEquals(0, service.grants);
  }

  @Test
  void failedSettlementCannotAutomaticallyRepeatDrops() {
    var player = new TestPlayer();
    var battle = battle(player);
    player.setBattle(battle);
    var service =
        new Service() {
          protected void grantDrops(Battle b) {
            grants++;
            throw new IllegalStateException("simulated interruption");
          }
        };
    assertThrows(
        IllegalStateException.class,
        () ->
            service.finishBattle(
                player,
                battle.getId(),
                123,
                BattleEndStatus.BATTLE_END_WIN,
                BattleStatistics.newInstance()));
    assertNull(
        service.finishBattle(
            player,
            battle.getId(),
            123,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertEquals(1, service.grants);
  }
}
