package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import org.hkrpg.server.GameDiagnostics;

/** Reward phase writes, called under the player's lock; delivered inventory is never rolled back. */
public final class QuestRewardWrites {
  public static void reserve(Player player, QuestState state) {
    var restore = state.progressCheckpoint();
    if (!state.reserveReward()) throw new IllegalStateException("Quest reward is not ready");
    persist(player, state, restore, "quest-reward-reserve");
  }

  public static void complete(Player player, QuestState state) {
    var restore = state.progressCheckpoint();
    state.closeReward();
    persist(player, state, restore, "quest-reward-complete");
  }

  private static void persist(Player player, QuestState state, Runnable restore, String stage) {
    try {
      player.save();
    } catch (RuntimeException failure) {
      // Before delivery this restores FINISHED; after delivery it restores CLAIMING.
      restore.run();
      GameDiagnostics.saveFailed(player, stage, state.getQuestId(), 0, failure);
      throw failure;
    }
  }

  private QuestRewardWrites() {}
}
