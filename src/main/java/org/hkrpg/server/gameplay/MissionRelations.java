package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;

/** Explicit single monitored objective in the same accepted resource mission. */
public final class MissionRelations {
  public static boolean finished(Player player, MissionInfo.SubMissionInfo target) {
    if (!"SubMissionMultiFinish".equals(target.getFinishType())
        || !"ListContain".equals(target.getParamType())
        || target.getProgress() != 1
        || target.getParamInt1() != 0
        || target.getParamInt2() != 0
        || target.getParamInt3() != 0
        || target.getParamStr1() == null
        || !target.getParamStr1().isBlank()
        || target.getParamIntList() == null
        || target.getParamIntList().length != 0
        || target.getParamItemList() == null
        || !target.getParamItemList().isEmpty()) return false;
    var gameplay = player.getGameplay();
    var parent = gameplay.getMainMissions().get(target.getMainMissionID());
    var config = GameData.getMissionInfos().get(target.getMainMissionID());
    if (parent == null
        || parent.getQuestId() != target.getMainMissionID()
        || parent.getPhase() != QuestState.Phase.DOING
        || config == null
        || config.getMainMissionID() != target.getMainMissionID()
        || config.getSubMissionById(target.getId()) != target
        || config.getSubMissionRelationList() == null
        || config.getSubMissionRelationList().size() > 256
        || gameplay.getCancelledMissions().contains(target.getMainMissionID())
        || gameplay.getCancelledSubs().contains(target.getId())) return false;
    var matches =
        config.getSubMissionRelationList().stream()
            .filter(Objects::nonNull)
            .filter(row -> row.MonitorSubMissionID() == target.getId())
            .toList();
    if (matches.size() != 1) return false;
    var ids = matches.getFirst().MonitoredSubMissionIDList();
    // Only the original single-monitor shape is established; no vacuous or guessed group result.
    if (ids == null || ids.size() != 1 || ids.getFirst() == null) return false;
    int id = ids.getFirst();
    var source = config.getSubMissionById(id);
    var state = gameplay.getSubMissions().get(id);
    return id > 0
        && id != target.getId()
        && source != null
        && source == GameplayDefinitions.subs.get(id)
        && source.getMainMissionID() == target.getMainMissionID()
        && source.getProgress() > 0
        && state != null
        && state.getQuestId() == id
        && (state.getPhase() == QuestState.Phase.FINISHED
            || state.getPhase() == QuestState.Phase.CLAIMING
            || state.getPhase() == QuestState.Phase.CLOSED)
        && state.getFinishTime() > 0
        && state.getProgress() == source.getProgress()
        && !gameplay.getCancelledSubs().contains(id);
  }

  private MissionRelations() {}
}
