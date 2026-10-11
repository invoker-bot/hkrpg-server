package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.time.Instant;
import java.util.*;

/** The source reset policy is limited to safe, unpaid talk/internal-aggregation graphs. */
final class DailyMissionExpiry {
  static boolean current(Player player, int id, Instant now) {
    if (!DailyCommissionAcceptance.applies(id) || !DailyMissionRepeats.supportedGraph(id))
      return true;
    if (!DailyMissionRepeats.historyValid(player, now)) return false;
    if (!DailyCommissionAcceptance.valid(player.getGameplay().getDailyMissionSelections()))
      return false;
    Long day = DailyMissionRepeats.selectedDay(player, id);
    return day == null || day == DailyManager.day(now);
  }

  static void bind(Player player, int id, long day) {
    var periods = player.getGameplay().getDailyMissionPeriods();
    if (periods.getExpired().values().stream()
        .anyMatch(s -> s.getMainId() == id && s.getDay() < day)) periods.getRunDays().put(id, day);
  }

  static boolean expire(Player player, Instant now) {
    var g = player.getGameplay();
    var selections = g.getDailyMissionSelections();
    if (!DailyCommissionAcceptance.valid(selections)
        || selections.getLatestDay() > DailyManager.day(now)) return false;
    var active =
        DailyMissionCatalog.missions().stream()
            .filter(row -> MissionStateRecords.mainDoing(player, row.ID()))
            .toList();
    // The installed source quota is one; ambiguous multiple active views are kept for review.
    if (active.size() != 1) return false;
    int id = active.getFirst().ID();
    Long day = DailyMissionRepeats.selectedDay(player, id);
    long today = DailyManager.day(now);
    if (day == null || day >= today || !DailyMissionRepeats.supportedGraph(id)) return false;
    var row = active.getFirst();
    var choice = selections.getDays().get(Long.toString(day));
    if (choice == null
        || choice.stream()
            .noneMatch(
                s ->
                    s.getId() == id
                        && s.getType() == row.DailyMissionType()
                        && s.getGroup() == row.GroupID())) return false;
    var source = GameData.getMainMissionExcelMap().get(id);
    var periods = g.getDailyMissionPeriods();
    var main = g.getMainMissions().get(id);
    var graph = GameData.getMissionInfos().get(id);
    String key = DailyMissionRepeats.mainKey(player, id);
    if (source == null
        || source.getId() != id
        || !"Daily".equals(source.getType())
        || source.isInRaid()
        || periods.getRunDays().containsKey(id)
            && !Objects.equals(periods.getRunDays().get(id), day)
        || periods.getArchives().containsKey(id + ":" + day)
        || periods.getExpired().containsKey(id + ":" + day)
        || g.getMissionRewardPlans().containsKey(key)
        || g.getRewards().containsKey(key)) return false;
    var subs = new HashMap<Integer, QuestState>();
    var goals = new HashMap<Integer, Integer>();
    var actions = new HashMap<Integer, GameplayState.ActionCursor>();
    for (var sub : graph.getSubMissionList()) {
      int sid = sub.getId(), goal = Math.max(1, sub.getProgress());
      if (sub.getSubRewardID() != 0
          || g.getCancelledSubs().contains(sid)
          || g.getMissionPayments().containsKey(sid)
          || g.getMissionPaymentPlans().containsKey(sid)
          || g.getMissionAcquiredItems().containsKey(sid)
          || g.getMissionAcceptedDays().containsKey(sid)
          || g.getMissionRewardPlans().containsKey(DailyMissionRepeats.subKey(player, sub))
          || g.getRewards().containsKey(DailyMissionRepeats.subKey(player, sub))) return false;
      goals.put(sid, goal);
      var state = g.getSubMissions().get(sid);
      if (g.getSubMissions().containsKey(sid)) {
        if (state == null
            || state.getPhase() == QuestState.Phase.CLAIMING
            || !MissionStateRecords.doing(state, sid, goal)
                && !MissionStateRecords.completed(state, sid, goal)
            || state.getPhase() != QuestState.Phase.DOING && !sameDay(state.getFinishTime(), day))
          return false;
        subs.put(sid, state);
      }
      var cursor = g.getStoryActions().get(sid);
      if (g.getStoryActions().containsKey(sid)) {
        if (cursor == null || cursor.isPending() || cursor.getNext() != 0) return false;
        actions.put(sid, cursor);
      }
    }
    if (g.getStoryValues().keySet().stream()
        .anyMatch(name -> name != null && name.startsWith("mission:" + id + ":"))) return false;
    if (Arrays.stream(graph.getFinishSubMissionList())
        .allMatch(
            end ->
                subs.get(end) != null
                    && MissionStateRecords.completed(subs.get(end), end, goals.get(end))))
      return false;
    var snapshot =
        new DailyMissionExpiredState(day, now.getEpochSecond(), id, main, subs, goals, actions);
    periods.getExpired().put(id + ":" + day, snapshot);
    g.getMainMissions().remove(id);
    for (int sid : goals.keySet()) {
      g.getSubMissions().remove(sid);
      g.getStoryActions().remove(sid);
    }
    periods.getRunDays().remove(id);
    // Let authored groups observe the expired run's absence before assigning the same main again.
    if (player.getScene() != null) player.getScene().refreshMissionGroups();
    return true;
  }

  static boolean valid(Player player, Instant now) {
    var p = player.getGameplay().getDailyMissionPeriods();
    if (p == null || p.getExpired() == null) return false;
    var selections = player.getGameplay().getDailyMissionSelections();
    if (!p.getExpired().isEmpty() && !DailyCommissionAcceptance.valid(selections)) return false;
    for (var entry : p.getExpired().entrySet()) {
      var s = entry.getValue();
      if (s == null
          || s.getMainId() <= 0
          || s.getDay() == Long.MIN_VALUE
          || !Objects.equals(entry.getKey(), s.getMainId() + ":" + s.getDay())
          || p.getArchives() == null
          || p.getArchives().containsKey(entry.getKey())
          || !MissionStateRecords.doing(s.getMain(), s.getMainId(), 1)
          || s.getExpiredAt() <= 0
          || s.getExpiredAt() > now.getEpochSecond()
          || !laterDay(s.getExpiredAt(), s.getDay())
          || s.getGoals() == null
          || s.getGoals().isEmpty()
          || s.getSubs() == null
          || s.getActions() == null) return false;
      var choices = selections.getDays().get(Long.toString(s.getDay()));
      if (choices == null || choices.stream().noneMatch(c -> c.getId() == s.getMainId()))
        return false;
      for (var goal : s.getGoals().entrySet())
        if (goal.getKey() == null
            || goal.getKey() <= 0
            || goal.getValue() == null
            || goal.getValue() <= 0) return false;
      for (var sub : s.getSubs().entrySet()) {
        var state = sub.getValue();
        var goal = s.getGoals().get(sub.getKey());
        if (sub.getKey() == null
            || goal == null
            || state == null
            || state.getPhase() == QuestState.Phase.CLAIMING
            || !MissionStateRecords.doing(state, sub.getKey(), goal)
                && !MissionStateRecords.completed(state, sub.getKey(), goal)
            || state.getPhase() != QuestState.Phase.DOING
                && (state.getFinishTime() > s.getExpiredAt()
                    || !sameDay(state.getFinishTime(), s.getDay()))) return false;
      }
      for (var action : s.getActions().entrySet())
        if (action.getKey() == null
            || !s.getSubs().containsKey(action.getKey())
            || action.getValue() == null
            || action.getValue().isPending()
            || action.getValue().getNext() != 0) return false;
    }
    return true;
  }

  private static boolean sameDay(long second, long day) {
    try {
      return second > 0 && DailyManager.day(Instant.ofEpochSecond(second)) == day;
    } catch (java.time.DateTimeException invalid) {
      return false;
    }
  }

  private static boolean laterDay(long second, long day) {
    try {
      return DailyManager.day(Instant.ofEpochSecond(second)) > day;
    } catch (java.time.DateTimeException invalid) {
      return false;
    }
  }
}
