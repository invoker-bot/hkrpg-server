package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;

/** Read-only history checks, distinct from cancellation-sensitive gameplay eligibility. */
public final class MissionSnapshotRecords {
  /** Hide unfinished objectives when their parent is paused or their encounter has finished. */
  public static boolean retiredSub(Player player, int id) {
    synchronized (player) {
      var state = player.getGameplay().getSubMissions().get(id);
      var source = GameplayDefinitions.subs.get(id);
      return state != null
          && state.getQuestId() == id
          && state.getPhase() == QuestState.Phase.DOING
          && source != null
          && source.getId() == id
          && (MissionStateRecords.completed(
                  player.getGameplay().getMainMissions().get(source.getMainMissionID()),
                  source.getMainMissionID(), 1)
              || pausedMain(player, source.getMainMissionID())
              || PeppyMissionScene.retiredFightMarker(player, source)
              || UnavailableMissionScripts.suppressed(player, source));
    }
  }

  public static boolean pausedMain(Player player, int id) {
    synchronized (player) {
      return MissionStateRecords.doing(player.getGameplay().getMainMissions().get(id), id, 1)
          && ActivityMissionSchedule.paused(player, id);
    }
  }

  public static boolean main(Player player, int id) {
    synchronized (player) {
      var state = player.getGameplay().getMainMissions().get(id);
      return MissionStateRecords.doing(state, id, 1) || MissionStateRecords.completed(state, id, 1);
    }
  }

  /** The saved status can be used by a local scene gate without reopening retired objectives. */
  public static QuestState sceneSub(Player player, int id) {
    synchronized (player) {
      return MissionStateRecords.subAccepted(player, id) && !retiredSub(player, id)
          ? player.getGameplay().getSubMissions().get(id)
          : null;
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
