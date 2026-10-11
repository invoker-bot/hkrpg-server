package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
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
    if (!initialSource(player) && (!ChessRogueRoutes.valid(run) || (run.isInside()
        ? !ChessRogueRoutes.sceneSource(player.getScene(),run,true)
        : !ChessRogueRoutes.returnedSceneSource(player.getScene(),run))))
      throw new IllegalStateException("Chess native profile requires the complete original guide source");
    var unlocked = MissionStateRecords.mainCompleted(player,8013104)
        ? List.of(101) : List.<Integer>of();
    // No original first-reward payment path exists in this bounded guide implementation.
    return new ChessRogueProfile(unlocked,List.of());
  }

  /** The original Query permits a null DJE before Start; it still requires a source-bound HIM. */
  public static boolean initialSource(Player player) {
    var run = player.getGameplay().getChessRogue();
    var scene = player.getScene();
    return absent(run) && scene != null && scene.getPlayer() == player
        && scene.getPlaneId() == player.getPlaneId() && scene.getFloorId() == player.getFloorId()
        && scene.getEntryId() == player.getEntryId() && ChessRogueRoutes.available()
        && !ChessRogueRoutes.returnSignature(scene,player.getEntryId()).isEmpty();
  }

  /** A damaged/partial previous record must not acquire the initial profile fallback. */
  private static boolean absent(ChessRogueState run) {
    return run != null && run.getSchema() == 1 && "".equals(run.getRunId())
        && "".equals(run.getSourceCommit()) && "".equals(run.getRouteRule())
        && run.getArea() == 0 && run.getAeon() == 0 && run.getLayerIndex() == 0
        && run.getCurrentCell() == 0 && run.getEnteredCell() == 0 && !run.isInside() && !run.isFailed()
        && run.getCoins() == 0 && run.getActionPoints() == 0 && run.getCoinsPerVictory() == 0
        && run.getMiraclePrice() == 0 && run.getStockLimit() == 0 && run.getMonsterLevel() == 0
        && run.getLeader() == 0 && run.getMp() == 0 && run.getRoster() != null && run.getRoster().isEmpty()
        && run.getVitals() != null && run.getVitals().isEmpty()
        && run.getStatuses() != null && run.getStatuses().isEmpty()
        && run.getVictories() != null && run.getVictories().isEmpty()
        && run.getMiracles() != null && run.getMiracles().isEmpty()
        && run.getShop() == null && run.getReturnTo() == null;
  }
}
