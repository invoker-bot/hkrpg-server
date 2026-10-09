package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.FinishWayExcel;
import emu.lunarcore.game.player.Player;
import java.time.DateTimeException;
import java.time.Instant;

/** Counts intact Daily-type main completions in the current Chinese four-o'clock day. */
final class TodayDailyMissionCounts {
  static boolean supported(FinishWayExcel rule) {
    return rule != null
        && "FinishTodayDailyMissionCnt".equals(rule.getFinishType())
        && "NoPara".equals(rule.getParamType())
        && rule.getProgress() == 1
        && rule.getParamInt1() == 0
        && rule.getParamInt2() == 0
        && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && rule.getParamIntList() != null
        && rule.getParamIntList().isEmpty()
        && rule.getParamItemList() != null
        && rule.getParamItemList().isEmpty()
        && !rule.isIsBackTrack();
  }

  static boolean completed(Player player, int id, long day, Instant now) {
    var definition = GameData.getMainMissionExcelMap().get(id);
    if (definition == null
        || definition.getId() != id
        || !"Daily".equals(definition.getType())
        || !MissionStateRecords.mainCompleted(player, id)) return false;
    long finish = player.getGameplay().getMainMissions().get(id).getFinishTime();
    if (finish > now.getEpochSecond()) return false;
    try {
      return DailyManager.day(Instant.ofEpochSecond(finish)) == day;
    } catch (DateTimeException invalidTime) {
      return false;
    }
  }

  static int count(Player player, long day, Instant now) {
    synchronized (player) {
      int count = 0;
      for (var id : player.getGameplay().getMainMissions().keySet())
        if (id != null && completed(player, id, day, now)) count++;
      return count;
    }
  }

  private TodayDailyMissionCounts() {}
}
