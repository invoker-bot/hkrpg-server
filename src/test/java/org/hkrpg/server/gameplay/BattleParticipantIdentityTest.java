package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.excel.AvatarExcel;
import emu.lunarcore.game.avatar.*;
import emu.lunarcore.proto.AvatarBattleInfoOuterClass.AvatarBattleInfo;
import emu.lunarcore.proto.AvatarPropertyOuterClass.AvatarProperty;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A stored base character and the path sent to the client are different identities. */
class BattleParticipantIdentityTest {
  final BattleSpSettlement46Test f = new BattleSpSettlement46Test();
  GameAvatar hero;

  @BeforeEach
  void setup() throws Exception {
    f.setup();
    hero =
        new GameAvatar(excel(8001)) {
          @Override
          public void save() {}
        };
    hero.setOwner(f.owner);
    f.owner.actors.put(8001, hero);
    f.owner.normal.getAvatars().clear();
    f.owner.normal.getAvatars().add(8001);
    hero.setCurrentHp(f.owner.normal, 10000);
    hero.setCurrentSp(f.owner.normal, 0);
  }

  @AfterEach
  void cleanup() {
    f.restoreSourceActor();
  }

  AvatarExcel excel(int id) {
    return new Gson().fromJson("{\"AvatarID\":" + id + "}", AvatarExcel.class);
  }

  void path(int id) {
    hero.setMultiPath(new AvatarMultiPath(f.owner, excel(id)));
  }

  BattleStatistics stats(int id) {
    return BattleStatistics.newInstance()
        .addBattleAvatarList(
            AvatarBattleInfo.newInstance()
                .setId(id)
                .setAvatarStatus(
                    AvatarProperty.newInstance()
                        .setMaxHp(1000)
                        .setLeftHp(750)
                        .setMaxSp(120)
                        .setLeftSp(30)));
  }

  @ParameterizedTest
  @ValueSource(ints = {8001, 8002, 8003, 8004, 8005, 8006, 8007, 8008})
  void acceptsTheBattlePathActuallySentAndSavesVitalsOnItsStoredCharacter(int id) {
    path(id);
    var battle = f.battle();
    assertEquals(id, battle.toProto().getBattleAvatarList().get(0).getId());
    assertSame(
        battle,
        f.service.finishBattle(
            f.owner,
            battle.getId(),
            BattleSpSettlement46Test.STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            stats(id)));
    assertNull(f.owner.getBattle(), "Accepted victory must release the battle busy flag");
    assertEquals(7500, hero.getCurrentHp(f.owner.normal));
    assertEquals(2500, hero.getCurrentSp(f.owner.normal));
  }

  @ParameterizedTest
  @ValueSource(ints = {8001, 8003, 8004, 1202, 99999})
  void rejectsOtherPathsAndOwnedCharactersAbsentFromThisBattle(int id) {
    path(8002);
    var battle = f.battle();
    assertNull(
        f.service.finishBattle(
            f.owner,
            battle.getId(),
            BattleSpSettlement46Test.STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            stats(id)));
    assertSame(battle, f.owner.getBattle());
    assertFalse(battle.isSettling());
    assertEquals(10000, hero.getCurrentHp(f.owner.normal));
  }

  @Test
  void duplicateReportsCannotWriteTheSameCharacterTwice() {
    path(8002);
    var battle = f.battle();
    var stats = stats(8002);
    stats.addBattleAvatarList(
        AvatarBattleInfo.newInstance().copyFrom(stats.getBattleAvatarList().get(0)));
    assertNull(
        f.service.finishBattle(
            f.owner,
            battle.getId(),
            BattleSpSettlement46Test.STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            stats));
    assertFalse(battle.isSettling());
    assertEquals(10000, hero.getCurrentHp(f.owner.normal));
  }

  @Test
  void laterPartyEditsDoNotRewriteTheParticipantsOfAnExistingBattle() {
    path(8002);
    var battle = f.battle();
    f.owner.normal.getAvatars().clear();
    f.owner.normal.getAvatars().add(1202);
    assertNull(
        f.service.finishBattle(
            f.owner,
            battle.getId(),
            BattleSpSettlement46Test.STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            stats(1202)));
    assertSame(
        battle,
        f.service.finishBattle(
            f.owner,
            battle.getId(),
            BattleSpSettlement46Test.STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            stats(8002)));
    assertEquals(7500, hero.getCurrentHp(f.owner.normal));
  }
}
