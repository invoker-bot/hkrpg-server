package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.Instant;
import java.util.*;

/** Commission acceptance consumes the source quota in the same player save as the mission. */
final class DailyCommissionAcceptance {
  static Retcode check(Player player, int id, Instant now) {
    if (DailyMissionRecoverDeliveryRules.handles(id) && !DailyMissionRecoverDeliveryRules.source()) return Retcode.MISSION_NOT_DOING;
    if (DailyMissionSelectedItemRules.handles(id) && !DailyMissionSelectedItemRules.source(id)) return Retcode.MISSION_NOT_DOING;
    if (DailyMissionFollowupRules.handles(id)) return DailyMissionFollowupRules.check(player, now);
    if (!applies(id)) return Retcode.SUCC;
    var periods = player.getGameplay().getDailyMissionPeriods();
    if (periods == null
        || periods.getRunDays() == null
        || periods.getArchives() == null
        || !DailyMissionExpiry.valid(player, now)) return Retcode.PLAYER_DATA_ERROR;
    var row = DailyMissionCatalog.mission(id);
    if (!source(player, row, now)) return Retcode.MISSION_NOT_DOING;
    long day = DailyManager.day(now);
    var selections = player.getGameplay().getDailyMissionSelections();
    if (!valid(selections)) return Retcode.PLAYER_DATA_ERROR;
    if (day < selections.getLatestDay()) return Retcode.MISSION_NOT_DOING;
    var current = selections.getDays().getOrDefault(Long.toString(day), List.of());
    int count = 0;
    boolean selected = false, sameGroup = false;
    for (var choice : current) {
      var original = DailyMissionCatalog.mission(choice.getId());
      if (original == null
          || original.DailyMissionType() != choice.getType()
          || original.GroupID() != choice.getGroup()) return Retcode.PLAYER_DATA_ERROR;
      selected |= choice.getId() == id;
      sameGroup |= choice.getGroup() == row.GroupID();
      if (choice.getType() == row.DailyMissionType()) count++;
    }
    var quota = DailyMissionCatalog.count(row.DailyMissionType());
    if (quota == null || count > quota.DailyCount()) return Retcode.PLAYER_DATA_ERROR;
    if (selected) return Retcode.SUCC;
    return !sameGroup && count < quota.DailyCount() ? Retcode.SUCC : Retcode.MISSION_NOT_DOING;
  }

  static boolean applies(int id) {
    if (!DailyMissionCatalog.configured()) return false;
    // Original 3000604 follows its pool parent; it is absent from the original daily pool.
    if (DailyMissionFollowupRules.handles(id)) return false;
    var main = GameData.getMainMissionExcelMap().get(id);
    return DailyMissionCatalog.mission(id) != null
        || main != null && "Daily".equals(main.getType());
  }

  static boolean record(Player player, int id, Instant now) {
    boolean changed =
        applies(id)
            && player
                .getGameplay()
                .getDailyMissionSelections()
                .record(DailyManager.day(now), DailyMissionCatalog.mission(id));
    if (changed) DailyMissionExpiry.bind(player, id, DailyManager.day(now));
    return changed;
  }

  private static boolean source(Player player, DailyMissionCatalog.Mission row, Instant now) {
    if (row == null) return false;
    var main = GameData.getMainMissionExcelMap().get(row.ID());
    var graph = GameData.getMissionInfos().get(row.ID());
    var unlock = GameData.getMainMissionExcelMap().get(row.UnlockMainMission());
    var quest = GameData.getQuestExcelMap().get(row.QuestID());
    if (main == null
        || main.getId() != row.ID()
        || !"Daily".equals(main.getType())
        || main.isInRaid()
        || graph == null
        || graph.getMainMissionID() != row.ID()
        || graph.getFinishSubMissionList() == null
        || graph.getFinishSubMissionList().length == 0
        || unlock == null
        || unlock.getId() != row.UnlockMainMission()
        || !MissionStateRecords.mainCompleted(player, row.UnlockMainMission())
        || player.getGameplay().getMainMissions().get(row.UnlockMainMission()).getFinishTime()
            > now.getEpochSecond()
        || quest == null
        || quest.getId() != row.QuestID()
        || quest.getQuestType() != 5) return false;
    var rule = GameData.getFinishWayExcelMap().get(quest.getFinishWayID());
    if (rule == null
        || rule.getId() != quest.getFinishWayID()
        || !TodayDailyMissionCounts.supported(rule)) return false;
    var ends = new HashSet<Integer>();
    for (int id : graph.getFinishSubMissionList()) {
      var sub = GameplayDefinitions.subs.get(id);
      if (id <= 0
          || !ends.add(id)
          || sub == null
          || sub.getId() != id
          || sub.getMainMissionID() != row.ID()) return false;
    }
    return true;
  }

  static boolean valid(DailyMissionSelections selections) {
    if (selections == null || selections.getDays() == null) return false;
    long latest = Long.MIN_VALUE;
    for (var entry : selections.getDays().entrySet()) {
      final long day;
      try {
        day = Long.parseLong(entry.getKey());
      } catch (RuntimeException invalid) {
        return false;
      }
      if (day == Long.MIN_VALUE
          || !Long.toString(day).equals(entry.getKey())
          || entry.getValue() == null
          || entry.getValue().isEmpty()
          || entry.getValue().size() > 16) return false;
      var ids = new HashSet<Integer>();
      var groups = new HashSet<Integer>();
      for (var choice : entry.getValue())
        if (choice == null
            || choice.getId() <= 0
            || choice.getType() <= 0
            || choice.getGroup() <= 0
            || !ids.add(choice.getId())
            || !groups.add(choice.getGroup())) return false;
      latest = Math.max(latest, day);
    }
    return latest == selections.getLatestDay();
  }

  private DailyCommissionAcceptance() {}
}
