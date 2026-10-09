package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.time.Instant;
import java.util.*;

/** Only settled, stateless source talk/aggregation graphs can install a fresh daily view. */
final class DailyMissionRepeats {
  static boolean supportedGraph(int id) {
    return safeGraph(GameData.getMissionInfos().get(id));
  }

  static boolean historyValid(Player player, Instant now) {
    return valid(player, player.getGameplay().getDailyMissionPeriods(), now)
        && DailyMissionExpiry.valid(player, now);
  }

  static boolean payoutAllowed(Player player, int id, Instant now) {
    if (!DailyCommissionAcceptance.applies(id)) return true;
    var periods = player.getGameplay().getDailyMissionPeriods();
    if (!historyValid(player, now)) return false;
    if (!periods.getRunDays().containsKey(id))
      return periods.getExpired().values().stream()
          .noneMatch(snapshot -> snapshot.getMainId() == id);
    var day = periods.getRunDays().get(id);
    if (day == null || day > DailyManager.day(now) || !Objects.equals(day, selectedDay(player, id)))
      return false;
    return periods.getArchives().values().stream()
            .anyMatch(snapshot -> snapshot.getMainId() == id && snapshot.getDay() < day)
        || periods.getExpired().values().stream()
            .anyMatch(snapshot -> snapshot.getMainId() == id && snapshot.getDay() < day);
  }

  static String mainKey(Player player, int id) {
    return scoped(player, id, "mission:" + id);
  }

  static String subKey(Player player, MissionInfo.SubMissionInfo sub) {
    return scoped(player, sub.getMainMissionID(), "submission:" + sub.getId());
  }

  private static String scoped(Player player, int main, String suffix) {
    if (!DailyCommissionAcceptance.applies(main)) return suffix;
    var periods = player.getGameplay().getDailyMissionPeriods();
    var day =
        periods == null || periods.getRunDays() == null ? null : periods.getRunDays().get(main);
    return day == null ? suffix : "daily-mission:" + day + ":" + suffix;
  }

  static boolean reopen(Player player, int id, Instant now) {
    if (!DailyCommissionAcceptance.applies(id)) return false;
    var gameplay = player.getGameplay();
    var main = gameplay.getMainMissions().get(id);
    var graph = GameData.getMissionInfos().get(id);
    var source = GameData.getMainMissionExcelMap().get(id);
    var periods = gameplay.getDailyMissionPeriods();
    long today = DailyManager.day(now);
    if (main == null
        || main.getPhase() != QuestState.Phase.CLOSED
        || !MissionStateRecords.mainCompleted(player, id)
        || source == null
        || source.getId() != id
        || !"Daily".equals(source.getType())
        || !safeGraph(graph)
        || !valid(player, periods, now)) return false;
    Long prior = selectedDay(player, id);
    if (prior == null
        || prior >= today
        || main.getFinishTime() > now.getEpochSecond()
        || !sameDay(main.getFinishTime(), prior)
        || periods.getRunDays().containsKey(id)
            && !Objects.equals(periods.getRunDays().get(id), prior)
        || periods.getArchives().containsKey(id + ":" + prior)) return false;
    String key = mainKey(player, id);
    if (!settled(player, key, source.getRewardID())) return false;
    var subs = new HashMap<Integer, QuestState>();
    var goals = new HashMap<Integer, Integer>();
    var actions = new HashMap<Integer, GameplayState.ActionCursor>();
    var keys = new ArrayList<String>();
    keys.add(key);
    for (var sub : graph.getSubMissionList()) {
      int subId = sub.getId();
      goals.put(subId, Math.max(sub.getProgress(), 1));
      var state = gameplay.getSubMissions().get(subId);
      if (gameplay.getCancelledSubs().contains(subId)
          || gameplay.getMissionPayments().containsKey(subId)
          || gameplay.getMissionPaymentPlans().containsKey(subId)
          || gameplay.getMissionAcquiredItems().containsKey(subId)
          || gameplay.getMissionAcceptedDays().containsKey(subId)) return false;
      if (state != null) {
        if (!MissionStateRecords.doing(state, subId, Math.max(1, sub.getProgress()))
            && !MissionStateRecords.subCompleted(player, subId)) return false;
        if (state.getPhase() == QuestState.Phase.CLAIMING) return false;
        if (state.getPhase() != QuestState.Phase.DOING
            && (state.getFinishTime() > now.getEpochSecond()
                || !sameDay(state.getFinishTime(), prior))) return false;
        subs.put(subId, state);
      }
      var cursor = gameplay.getStoryActions().get(subId);
      if (gameplay.getStoryActions().containsKey(subId)) {
        if (cursor == null || cursor.isPending() || cursor.getNext() != 0) return false;
        actions.put(subId, cursor);
      }
      if (sub.getSubRewardID() > 0) {
        String subKey = subKey(player, sub);
        if (state == null
            || state.getPhase() != QuestState.Phase.CLOSED
            || !settled(player, subKey, sub.getSubRewardID())) return false;
        keys.add(subKey);
      }
    }
    for (int end : graph.getFinishSubMissionList())
      if (!MissionStateRecords.subCompleted(player, end)) return false;
    if (gameplay.getStoryValues().keySet().stream()
        .anyMatch(name -> name != null && name.startsWith("mission:" + id + ":"))) return false;
    var snapshot =
        new DailyMissionPeriodState.Snapshot(prior, id, main, subs, goals, actions, keys);
    periods.getArchives().put(id + ":" + prior, snapshot);
    for (var sub : graph.getSubMissionList()) {
      gameplay.getSubMissions().remove(sub.getId());
      gameplay.getStoryActions().remove(sub.getId());
    }
    periods.getRunDays().put(id, today);
    gameplay.getMainMissions().put(id, new QuestState(id));
    DailyCommissionAcceptance.record(player, id, now);
    return true;
  }

  static Long selectedDay(Player player, int id) {
    var selections = player.getGameplay().getDailyMissionSelections();
    if (selections == null || selections.getDays() == null) return null;
    Long result = null;
    for (var entry : selections.getDays().entrySet()) {
      final long day;
      try {
        day = Long.parseLong(entry.getKey());
      } catch (RuntimeException bad) {
        return null;
      }
      if (entry.getValue() == null) return null;
      if (entry.getValue().stream().anyMatch(row -> row != null && row.getId() == id))
        result = result == null ? day : Math.max(result, day);
    }
    return result;
  }

  private static boolean settled(Player player, String key, int rewardId) {
    var plan = player.getGameplay().getMissionRewardPlans().get(key);
    return plan != null
        && plan.isReady()
        && plan.getChoices() != null
        && plan.getChoices().isEmpty()
        && plan.getRewardIds() != null
        && plan.getRewardIds().equals(rewardId == 0 ? List.of() : List.of(rewardId))
        && player.getRewardLedger().granted(key);
  }

  private static boolean safeGraph(MissionInfo graph) {
    if (graph == null
        || graph.getMainMissionID() <= 0
        || graph.getSubMissionList() == null
        || graph.getSubMissionList().length == 0
        || graph.getSubMissionList().length > 64
        || graph.getMissionCustomValueList() != null
            && graph.getMissionCustomValueList().length != 0
        || graph.getMissionCustomValueSaveIndexList() != null
            && graph.getMissionCustomValueSaveIndexList().length != 0
        || graph.getSubMissionRelationList() == null
        || !graph.getSubMissionRelationList().isEmpty()) return false;
    var ids = new HashSet<Integer>();
    for (var sub : graph.getSubMissionList())
      if (sub == null
          || sub.getId() <= 0
          || sub.getMainMissionID() != graph.getMainMissionID()
          || GameplayDefinitions.subs.get(sub.getId()) != sub
          || !ids.add(sub.getId())) return false;
    if (graph.getStartSubMissionList() == null
        || graph.getStartSubMissionList().length == 0
        || graph.getFinishSubMissionList() == null
        || graph.getFinishSubMissionList().length == 0) return false;
    for (int start : graph.getStartSubMissionList()) if (!ids.contains(start)) return false;
    for (int end : graph.getFinishSubMissionList()) if (!ids.contains(end)) return false;
    for (var sub : graph.getSubMissionList()) {
      if (sub.getProgress() <= 0
          || sub.getSubRewardID() < 0
          || sub.isBackTrack()
          || sub.getStoryTrialTask() != null
          || sub.getFinishActionList() == null
          || sub.getFinishActionList().length != 0
          || sub.getCustomValueReward() == null
          || sub.getCustomValueReward().length != 0
          || sub.getCustomValueList() == null
          || sub.getCustomValueList().length != 0
          || sub.getParamItemList() == null
          || !sub.getParamItemList().isEmpty()
          || sub.getParamInt1() != 0 && !"StageWin".equals(sub.getFinishType())
          || sub.getParamInt2() != 0
          || sub.getParamInt3() != 0
          || sub.getParamIntList() == null
          || sub.getTakeParamIntList() == null) return false;
      if ("Auto".equals(sub.getTakeType())) {
        if (sub.getTakeParamIntList().length != 0) return false;
      } else if (!Set.of("AnySequence", "MultiSequence")
              .contains(Objects.toString(sub.getTakeType(), ""))
          || sub.getTakeParamIntList().length == 0
          || !distinctInternal(sub.getTakeParamIntList(), ids, sub.getId())) return false;
      if ("Talk".equals(sub.getFinishType())) {
        if (!"Equal".equals(sub.getParamType())
            || sub.getProgress() != 1
            || sub.getParamStr1() == null
            || sub.getParamStr1().isBlank()
            || sub.getParamIntList().length != 0) return false;
      } else if ("StageWin".equals(sub.getFinishType())) {
        if (!MissionStageWins.repeatable(sub)) return false;
      } else if (!"SubMissionFinishCnt".equals(sub.getFinishType())
          || !"ListContain".equals(sub.getParamType())
          || !"".equals(sub.getParamStr1())
          || sub.getParamIntList().length < sub.getProgress()
          || !distinctInternal(sub.getParamIntList(), ids, sub.getId())) return false;
    }
    return true;
  }

  private static boolean distinctInternal(Integer[] values, Set<Integer> ids, int self) {
    var used = new HashSet<Integer>();
    for (var id : values)
      if (id == null || id == self || !ids.contains(id) || !used.add(id)) return false;
    return true;
  }

  private static boolean sameDay(long time, long day) {
    try {
      return time > 0 && DailyManager.day(Instant.ofEpochSecond(time)) == day;
    } catch (java.time.DateTimeException invalid) {
      return false;
    }
  }

  private static boolean valid(Player player, DailyMissionPeriodState periods, Instant now) {
    if (periods == null || periods.getRunDays() == null || periods.getArchives() == null)
      return false;
    for (var entry : periods.getRunDays().entrySet())
      if (entry.getKey() == null
          || entry.getKey() <= 0
          || entry.getValue() == null
          || entry.getValue() == Long.MIN_VALUE) return false;
    for (var entry : periods.getArchives().entrySet()) {
      var snapshot = entry.getValue();
      if (snapshot == null
          || snapshot.getMainId() <= 0
          || snapshot.getDay() == Long.MIN_VALUE
          || !Objects.equals(entry.getKey(), snapshot.getMainId() + ":" + snapshot.getDay())
          || !MissionStateRecords.completed(snapshot.getMain(), snapshot.getMainId(), 1)
          || snapshot.getMain().getPhase() != QuestState.Phase.CLOSED
          || snapshot.getMain().getFinishTime() > now.getEpochSecond()
          || !sameDay(snapshot.getMain().getFinishTime(), snapshot.getDay())
          || snapshot.getSubs() == null
          || snapshot.getGoals() == null
          || snapshot.getGoals().isEmpty()
          || snapshot.getActions() == null
          || snapshot.getRewardKeys() == null
          || snapshot.getRewardKeys().isEmpty()) return false;
      for (var goal : snapshot.getGoals().entrySet())
        if (goal.getKey() == null
            || goal.getKey() <= 0
            || goal.getValue() == null
            || goal.getValue() <= 0) return false;
      for (var sub : snapshot.getSubs().entrySet()) {
        var goal = snapshot.getGoals().get(sub.getKey());
        var state = sub.getValue();
        if (sub.getKey() == null
            || goal == null
            || state == null
            || !MissionStateRecords.doing(state, sub.getKey(), goal)
                && !MissionStateRecords.completed(state, sub.getKey(), goal)
            || state.getPhase() == QuestState.Phase.CLAIMING
            || state.getPhase() != QuestState.Phase.DOING
                && (state.getFinishTime() > snapshot.getMain().getFinishTime()
                    || !sameDay(state.getFinishTime(), snapshot.getDay()))) return false;
      }
      for (var action : snapshot.getActions().entrySet())
        if (action.getKey() == null
            || !snapshot.getSubs().containsKey(action.getKey())
            || action.getValue() == null
            || action.getValue().isPending()
            || action.getValue().getNext() != 0) return false;
      var keys = new HashSet<String>();
      boolean mainReward = false;
      for (var key : snapshot.getRewardKeys()) {
        if (key == null || !keys.add(key) || !player.getRewardLedger().granted(key)) return false;
        var plan = player.getGameplay().getMissionRewardPlans().get(key);
        if (plan == null || !plan.isReady()) return false;
        String prefix = "daily-mission:" + snapshot.getDay() + ":";
        if (key.equals("mission:" + snapshot.getMainId())
            || key.equals(prefix + "mission:" + snapshot.getMainId())) mainReward = true;
        else if (snapshot.getGoals().keySet().stream()
            .noneMatch(
                id -> key.equals("submission:" + id) || key.equals(prefix + "submission:" + id)))
          return false;
      }
      if (!mainReward) return false;
    }
    return true;
  }

  private DailyMissionRepeats() {}
}
