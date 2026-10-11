package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.List;

/**
 * Read-only profile for the source-bound Chess201 guide. Eligibility comes from the original
 * area101 -> unlock1000020 -> completed8013104 rule. The guide's configured fragment credits
 * are not FirstReward109011, Nous dice acquisitions, Nous talents or virtual currency281013.
 */
public record ChessRogueProfile(List<Integer> unlockedAreaIds, List<Integer> firstRewardTakenAreaIds) {
  public ChessRogueProfile {
    unlockedAreaIds = List.copyOf(unlockedAreaIds);
    firstRewardTakenAreaIds = List.copyOf(firstRewardTakenAreaIds);
  }

  public static ChessRogueProfile current(Player player) {
    var run = player.getGameplay().getChessRogue();
    if (!ChessRogueRoutes.valid(run) || (run.isInside()
        ? !ChessRogueRoutes.sceneSource(player.getScene(),run,true)
        : !ChessRogueRoutes.returnedSceneSource(player.getScene(),run)))
      throw new IllegalStateException("Chess native profile requires the complete original guide source");
    var mission = player.getGameplay().getMainMissions().get(8013104);
    var unlocked = mission != null && mission.getPhase() != QuestState.Phase.DOING
        ? List.of(101) : List.<Integer>of();
    // No original first-reward payment path exists in this bounded guide implementation.
    return new ChessRogueProfile(unlocked,List.of());
  }
}
