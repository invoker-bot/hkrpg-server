package org.hkrpg.server.gameplay;

import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.challenge.ChallengeInstance;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;

/** A cumulative story score cannot move backwards; quitting preserves the current score. */
public final class StoryChallengeReports {
  public static boolean valid(Battle battle, BattleEndStatus result, BattleStatistics stats) {
    var binding = battle.getStoryChallengeBinding();
    if (binding != null && !binding.matches(battle.getPlayer(), battle)) return false;
    if (!(battle.getPlayer().getInstance() instanceof ChallengeInstance challenge)
        || !challenge.isStory()) return true;
    return result == BattleEndStatus.BATTLE_END_QUIT
        || stats.getChallengeScore() >= challenge.getTotalScore();
  }

  private StoryChallengeReports() {}
}
