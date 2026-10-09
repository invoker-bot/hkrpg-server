package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import java.util.HashSet;

/** Original FinishMission lists count completed main records against their explicit threshold. */
final class FinishMainMissionCounts {
  static int count(Player player, MissionInfo.SubMissionInfo target) {
    synchronized (player) {
      if (target == null
          || !"FinishMission".equals(target.getFinishType())
          || !"ListContain".equals(target.getParamType())
          || target.getProgress() <= 0
          || target.getParamInt1() != 0
          || target.getParamInt2() != 0
          || target.getParamInt3() != 0
          || target.getParamStr1() == null
          || !target.getParamStr1().isBlank()
          || target.getParamItemList() == null
          || !target.getParamItemList().isEmpty()) return -1;
      var ids = target.getParamIntList();
      if (ids == null || ids.length == 0 || ids.length > 256 || target.getProgress() > ids.length)
        return -1;
      var distinct = new HashSet<Integer>();
      for (var id : ids) {
        if (id == null || id <= 0 || id == target.getMainMissionID() || !distinct.add(id))
          return -1;
        var source = GameData.getMainMissionExcelMap().get(id);
        if (source == null || source.getId() != id) return -1;
      }
      var definition = GameData.getMissionInfos().get(target.getMainMissionID());
      if (definition == null
          || definition.getMainMissionID() != target.getMainMissionID()
          || definition.getSubMissionById(target.getId()) != target
          || GameplayDefinitions.subs.get(target.getId()) != target
          || !MissionStateRecords.mainDoing(player, target.getMainMissionID())
          || player.getGameplay().getCancelledSubs().contains(target.getId())
          || !MissionStateRecords.doing(
              player.getGameplay().getSubMissions().get(target.getId()),
              target.getId(),
              target.getProgress())) return -1;
      int count = 0;
      for (int id : ids) if (MissionStateRecords.mainCompleted(player, id)) count++;
      return count;
    }
  }

  private FinishMainMissionCounts() {}
}
