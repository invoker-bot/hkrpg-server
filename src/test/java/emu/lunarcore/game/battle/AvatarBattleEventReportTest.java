package emu.lunarcore.game.battle;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.data.excel.SpecialAvatarExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.trial.TrialAvatar;
import emu.lunarcore.proto.AvatarBattleInfoOuterClass.AvatarBattleInfo;
import emu.lunarcore.proto.AvatarPropertyOuterClass.AvatarProperty;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import org.hkrpg.server.gameplay.RogueResonance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AvatarBattleEventReportTest {
  final Gson gson = new Gson();
  final BattleSettlementTest.TestPlayer player = new BattleSettlementTest.TestPlayer();
  final BattleSettlementTest.Service service = new BattleSettlementTest.Service();

  GameAvatar actor(int actorId, boolean trial) {
    var excel = gson.fromJson("{\"AvatarID\":" + actorId + "}", AvatarExcel.class);
    GameAvatar avatar;
    if (trial) {
      var special = gson.fromJson("{\"SpecialAvatarID\":2000091,\"AvatarID\":" + actorId
          + ",\"Level\":80,\"Promotion\":6}", SpecialAvatarExcel.class);
      var temporary = new TrialAvatar(player, special, excel);
      avatar = temporary;
      player.getAvatars().addTempAvatar(temporary);
    } else {
      avatar = new GameAvatar(excel) { public void save() {} };
      avatar.setOwner(player);
      player.getAvatars().getAvatars().put(avatar.getAvatarId(), avatar);
    }
    return avatar;
  }

  Battle battle(GameAvatar... avatars) {
    var lineup = new PlayerLineup(player, 0);
    for (var avatar : avatars) lineup.getAvatars().add(avatar.getAvatarId());
    BattleStage stage = new BattleStage() {
      public int getId() { return 312040; }
      public StageType getStageType() { return StageType.Trial; }
      public List<IntList> getMonsterWaves() { return List.of(); }
    };
    var battle = new Battle(player, lineup, stage);
    player.setBattle(battle);
    return battle;
  }

  BattleStatistics statistics(Battle battle) {
    var stats = BattleStatistics.newInstance();
    for (int id : battle.getParticipantIds()) stats.addBattleAvatarList(
        AvatarBattleInfo.newInstance().setId(id).setAvatarStatus(AvatarProperty.newInstance()
            .setMaxHp(1000).setLeftHp(500).setMaxSp(130).setLeftSp(0)));
    return stats;
  }

  Battle finish(Battle battle, List<RogueResonance.Energy> energy) {
    return service.finishBattle(player, battle.getId(), 312040, BattleEndStatus.BATTLE_END_WIN,
        statistics(battle), List.of(), energy);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void jingYuanParticipantAcceptsHisOwnClientCreatedLightningLordOnce(boolean trial) {
    var battle = battle(actor(1204, trial));
    assertTrue(battle.getBattleEvents().isEmpty(),
        "A client-created summon must not be injected as another server-created event");
    assertNotNull(finish(battle, List.of(new RogueResonance.Energy(11203, 0, 1000))),
        "The real trial report must not fail merely because Lightning-Lord was created by its actor");
    assertEquals(1, service.grants);
    assertNull(player.getBattle());
    assertNull(finish(battle, List.of(new RogueResonance.Energy(11203, 0, 1000))));
    assertEquals(1, service.grants);
  }

  @Test
  void anOwnedJingYuanOutsideTheFrozenParticipantsCannotAuthorizeHisEvent() {
    actor(1204, false);
    var battle = battle(actor(1001, false));
    assertNull(finish(battle, List.of(new RogueResonance.Energy(11203, 0, 1000))));
    assertSame(battle, player.getBattle());
    assertEquals(0, service.grants);
  }

  @Test
  void addingJingYuanAfterBattleCreationCannotAuthorizeHisEvent() {
    var jingYuan = actor(1204, false);
    var battle = battle(actor(1001, false));
    battle.getLineup().getAvatars().add(jingYuan.getAvatarId());
    assertNull(finish(battle, List.of(new RogueResonance.Energy(11203, 0, 1000))));
    assertEquals(0, service.grants);
  }

  @Test
  void aTrialSpecialIdMustResolveToJingYuanRatherThanAuthorizeByItsIdAlone() {
    var battle = battle(actor(1001, true));
    assertNull(finish(battle, List.of(new RogueResonance.Energy(11203, 0, 1000))));
    assertEquals(0, service.grants);
  }

  @Test
  void invalidOrForeignEventsStillRejectWithoutConsumingTheBattle() {
    var battle = battle(actor(1204, true));
    for (var rows : List.of(
        List.of(new RogueResonance.Energy(11204, 0, 1000)),
        List.of(new RogueResonance.Energy(11203, 0, 1000), new RogueResonance.Energy(11203, 0, 1000)),
        List.of(new RogueResonance.Energy(11203, -2, 1000)),
        List.of(new RogueResonance.Energy(11203, 1001, 1000)),
        List.of(new RogueResonance.Energy(11203, 0, 0)))) {
      assertNull(finish(battle, rows));
      assertSame(battle, player.getBattle());
      assertEquals(0, service.grants);
    }
    assertNotNull(finish(battle, List.of(new RogueResonance.Energy(11203, 0, 1000))));
    assertEquals(1, service.grants);
  }

  @Test
  void acceptingAnActorEventDoesNotChangeUnrelatedRogueEnergyOrPublishItInTheSnapshot() {
    player.getGameplay().setRogue(gson.fromJson("{\"resonanceSp\":2500}",
        org.hkrpg.server.gameplay.GameplayState.RogueRun.class));
    var battle = battle(actor(1204, true));
    assertNotNull(finish(battle, List.of(new RogueResonance.Energy(11203, 0, 1000))));
    assertEquals(2500, player.getGameplay().getRogue().getResonanceSp());
    assertTrue(battle.getBattleEvents().isEmpty());
  }
}
