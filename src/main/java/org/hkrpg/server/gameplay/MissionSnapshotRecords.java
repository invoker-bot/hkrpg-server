package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;

/** Read-only history checks, distinct from cancellation-sensitive gameplay eligibility. */
public final class MissionSnapshotRecords {
  public static boolean main(Player player, int id) {
    synchronized (player) {
      var state = player.getGameplay().getMainMissions().get(id);
      return MissionStateRecords.doing(state, id, 1) || MissionStateRecords.completed(state, id, 1);
    }
  }

  public static boolean valid(Player player) {
    synchronized (player) {
      for (Integer id : player.getGameplay().getMainMissions().keySet())
        if (id == null || !main(player, id)) return false;
      for (var entry : player.getGameplay().getSubMissions().entrySet()) {
        if (entry.getKey() == null) return false;
        int id = entry.getKey();
        var state = entry.getValue();
        if (id <= 0 || state == null || state.getQuestId() != id) return false;
        var source = GameplayDefinitions.subs.get(id);
        if (source == null) {
          if (!unknownDoing(state)) return false;
          continue;
        }
        if (source.getId() != id || !main(player, source.getMainMissionID())) return false;
        if (source.getProgress() < 0) {
          if (!unknownDoing(state)) return false;
          continue;
        }
        int goal = Math.max(source.getProgress(), 1);
        if (!MissionStateRecords.doing(state, id, goal)
            && !MissionStateRecords.completed(state, id, goal)) return false;
      }
      return true;
    }
  }

  private static boolean unknownDoing(QuestState state) {
    return state.getPhase() == QuestState.Phase.DOING
        && state.getFinishTime() == 0
        && state.getProgress() >= 0;
  }

  private MissionSnapshotRecords() {}
}
