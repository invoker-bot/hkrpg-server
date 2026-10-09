package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;

/** Counts distinct, defined completed children; malformed shapes remain unsupported. */
final class MissionAggregateRecords {
  static int count(Player player, MissionInfo.SubMissionInfo target) {
    synchronized (player) {
      if (target == null
          || !"SubMissionFinishCnt".equals(target.getFinishType())
          || !"ListContain".equals(target.getParamType())
          || target.getProgress() < 0
          || target.getParamInt1() != 0
          || target.getParamInt2() != 0
          || target.getParamInt3() != 0
          || target.getParamStr1() == null
          || !target.getParamStr1().isBlank()
          || target.getParamItemList() == null
          || !target.getParamItemList().isEmpty()) return -1;
      var ids = target.getParamIntList();
      int goal = Math.max(target.getProgress(), 1);
      if (ids == null
          || ids.length == 0
          || ids.length > 256
          || goal > ids.length
          || Arrays.stream(ids).anyMatch(id -> id == null || id <= 0 || id == target.getId())
          || new HashSet<>(Arrays.asList(ids)).size() != ids.length) return -1;
      var gameplay = player.getGameplay();
      var main = GameData.getMissionInfos().get(target.getMainMissionID());
      var parent = gameplay.getMainMissions().get(target.getMainMissionID());
      var state = gameplay.getSubMissions().get(target.getId());
      if (main == null
          || main.getMainMissionID() != target.getMainMissionID()
          || main.getSubMissionById(target.getId()) != target
          || GameplayDefinitions.subs.get(target.getId()) != target
          || parent == null
          || parent.getQuestId() != target.getMainMissionID()
          || parent.getPhase() != QuestState.Phase.DOING
          || parent.getFinishTime() != 0
          || parent.getProgress() < 0
          || state == null
          || state.getQuestId() != target.getId()
          || state.getPhase() != QuestState.Phase.DOING
          || state.getFinishTime() != 0
          || state.getProgress() < 0
          || state.getProgress() >= goal
          || gameplay.getCancelledMissions().contains(target.getMainMissionID())
          || gameplay.getCancelledSubs().contains(target.getId())) return -1;
      int count = 0;
      for (int id : ids) {
        var source = GameplayDefinitions.subs.get(id);
        var owner =
            source == null ? null : GameData.getMissionInfos().get(source.getMainMissionID());
        if (source == null
            || source.getId() != id
            || source.getProgress() < 0
            || owner == null
            || owner.getMainMissionID() != source.getMainMissionID()
            || owner.getSubMissionById(id) != source) return -1;
        var accepted = gameplay.getMainMissions().get(source.getMainMissionID());
        var completed = gameplay.getSubMissions().get(id);
        if (accepted == null
            || accepted.getQuestId() != source.getMainMissionID()
            || !accepted(accepted)
            || gameplay.getCancelledMissions().contains(source.getMainMissionID())
            || gameplay.getCancelledSubs().contains(id)) continue;
        if (completed != null
            && completed.getQuestId() == id
            && finished(completed)
            && completed.getFinishTime() > 0
            && completed.getProgress() == Math.max(source.getProgress(), 1)) count++;
      }
      return count;
    }
  }

  private static boolean accepted(QuestState state) {
    if (state.getPhase() == QuestState.Phase.DOING)
      return state.getFinishTime() == 0 && state.getProgress() >= 0;
    return finished(state) && state.getFinishTime() > 0 && state.getProgress() == 1;
  }

  private static boolean finished(QuestState state) {
    return state.getPhase() == QuestState.Phase.FINISHED
        || state.getPhase() == QuestState.Phase.CLAIMING
        || state.getPhase() == QuestState.Phase.CLOSED;
  }

  private MissionAggregateRecords() {}
}
