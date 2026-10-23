package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.game.player.Player;
import java.util.HashSet;

/** Observes actual main completion for supported ordinary FinishMission quest rules. */
public final class MainMissionQuestCounts {
  public static int count(Player player, QuestExcel target) {
    return count(player, target, true);
  }

  public static boolean hasCompletedSource(Player player, QuestExcel target) {
    return count(player, target, false) > 0;
  }

  /** Repeated login/scene refreshes must not rescan activity catalogs for already observed progress. */
  public static boolean needsProgress(Player player, QuestExcel target) {
    synchronized (player) {
      int observed = count(player, target, false);
      if (observed <= 0) return false;
      var state = player.getQuestStates().get(target.getId());
      if (state == null) return true;
      var rule = GameData.getFinishWayExcelMap().get(target.getFinishWayID());
      return MissionStateRecords.doing(state, target.getId(), rule.getProgress())
          && state.getProgress() < observed;
    }
  }

  private static int count(Player player, QuestExcel target, boolean requireAccepted) {
    synchronized (player) {
      if (target == null
          || target.getId() <= 0
          || GameData.getQuestExcelMap().get(target.getId()) != target
          || target.getQuestType() != 1 && target.getQuestType() != 4) return -1;
      var rule = GameData.getFinishWayExcelMap().get(target.getFinishWayID());
      if (rule == null
          || rule.getId() != target.getFinishWayID()
          || !"FinishMission".equals(rule.getFinishType())
          || !"ListContain".equals(rule.getParamType())
          || rule.getProgress() <= 0
          || rule.getParamInt1() != 0
          || rule.getParamInt2() != 0
          || rule.getParamInt3() != 0
          || rule.getParamStr1() == null
          || !rule.getParamStr1().isBlank()
          || rule.getParamItemList() == null
          || !rule.getParamItemList().isEmpty()) return -1;
      var ids = rule.getParamIntList();
      if (ids == null || ids.isEmpty() || ids.size() > 256 || rule.getProgress() > ids.size())
        return -1;
      var distinct = new HashSet<Integer>();
      for (var id : ids) {
        if (id == null || id <= 0 || !distinct.add(id)) return -1;
        var source = GameData.getMainMissionExcelMap().get(id);
        if (source == null || source.getId() != id) return -1;
      }
      if (requireAccepted
          && !MissionStateRecords.doing(
              player.getQuestStates().get(target.getId()), target.getId(), rule.getProgress()))
        return -1;
      int count = 0;
      for (int id : ids) if (MissionStateRecords.mainCompleted(player, id)) count++;
      return count;
    }
  }

  private MainMissionQuestCounts() {}
}
