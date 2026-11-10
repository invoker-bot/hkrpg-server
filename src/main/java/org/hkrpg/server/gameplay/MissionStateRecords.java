package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;

/** Saved phases are evidence only when identity, progress and completion time agree. */
final class MissionStateRecords {
  static boolean doing(QuestState state, int id, int goal) {
    return id > 0
        && goal > 0
        && state != null
        && state.getQuestId() == id
        && state.getPhase() == QuestState.Phase.DOING
        && state.getFinishTime() == 0
        && state.getProgress() >= 0
        && state.getProgress() < goal;
  }

  static boolean completed(QuestState state, int id, int goal) {
    return id > 0
        && goal > 0
        && state != null
        && state.getQuestId() == id
        && (state.getPhase() == QuestState.Phase.FINISHED
            || state.getPhase() == QuestState.Phase.CLAIMING
            || state.getPhase() == QuestState.Phase.CLOSED)
        && state.getFinishTime() > 0
        && state.getProgress() == goal;
  }

  static boolean mainDoing(Player player, int id) {
    synchronized (player) {
      return recordedMainDoing(player, id) && !ActivityMissionSchedule.paused(player, id);
    }
  }

  private static boolean recordedMainDoing(Player player, int id) {
    return !player.getGameplay().getCancelledMissions().contains(id)
        && doing(player.getGameplay().getMainMissions().get(id), id, 1);
  }

  static boolean mainCompleted(Player player, int id) {
    synchronized (player) {
      return !player.getGameplay().getCancelledMissions().contains(id)
          && completed(player.getGameplay().getMainMissions().get(id), id, 1);
    }
  }

  static boolean mainAccepted(Player player, int id) {
    synchronized (player) {
      return mainDoing(player, id) || mainCompleted(player, id);
    }
  }

  static boolean subCompleted(Player player, int id) {
    synchronized (player) {
      var definition = GameplayDefinitions.subs.get(id);
      int main = definition == null ? 0 : definition.getMainMissionID();
      return definition != null
          && definition.getId() == id
          && definition.getProgress() >= 0
          && !player.getGameplay().getCancelledSubs().contains(id)
          // Activity panel prerequisites can consult completed objectives inside a paused mission.
          && (recordedMainDoing(player, main) || mainCompleted(player, main))
          && completed(
              player.getGameplay().getSubMissions().get(id),
              id,
              Math.max(definition.getProgress(), 1));
    }
  }

  static boolean subAccepted(Player player, int id) {
    synchronized (player) {
      var definition = GameplayDefinitions.subs.get(id);
      return definition != null
          && definition.getId() == id
          && definition.getProgress() >= 0
          && !player.getGameplay().getCancelledSubs().contains(id)
          && mainAccepted(player, definition.getMainMissionID())
          && (doing(
                  player.getGameplay().getSubMissions().get(id),
                  id,
                  Math.max(definition.getProgress(), 1))
              || subCompleted(player, id));
    }
  }

  private MissionStateRecords() {}
}
