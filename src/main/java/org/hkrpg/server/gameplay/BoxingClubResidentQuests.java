package org.hkrpg.server.gameplay;

import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;

/**
 * Resident progress is re-derived from source-bound three-round completions, never client finish.
 */
public final class BoxingClubResidentQuests {
  private static boolean sourceID(int id) {
    return id == 6000398 || id >= 6000405 && id <= 6000435;
  }

  public static boolean belongs(QuestExcel definition) {
    return definition != null && sourceID(definition.getId());
  }

  private static Map<Integer, Integer> counts(Player player, BoxingClubResidentQuestRules rules) {
    var battleRules = BoxingClubResonanceRules.load();
    var history = player.getGameplay().getBoxingClubResonance();
    if (rules == null || battleRules == null || !history.valid(battleRules, player.getUid()))
      return null;
    var completed = history.getCompleted();
    var result = new TreeMap<Integer, Integer>();
    for (var quest : rules.quests.values()) {
      var finish = rules.finishes.get(quest.getFinishWayID());
      if (!"BoxingClubAccomplish".equals(finish.getFinishType())) continue;
      var best = completed.get(finish.getParamInt2());
      // This server treats the original omitted/zero limit as no additional turn restriction.
      // Positive limits use the exact source value, including shared legacy FinishWay IDs.
      result.put(
          quest.getId(),
          best != null && (finish.getParamInt1() == 0 || best.rounds() <= finish.getParamInt1())
              ? 1
              : 0);
    }
    for (var quest : rules.quests.values()) {
      var finish = rules.finishes.get(quest.getFinishWayID());
      if (!"FinishQuest".equals(finish.getFinishType())) continue;
      result.put(
          quest.getId(),
          Math.min(
              finish.getProgress(),
              (int)
                  finish.getParamIntList().stream()
                      .filter(id -> result.getOrDefault(id, 0) == 1)
                      .count()));
    }
    return result.size() == 32 ? result : null;
  }

  public static boolean refresh(Player player) {
    synchronized (player) {
      var rules = BoxingClubResidentQuestRules.load();
      var counts = counts(player, rules);
      if (counts == null) return false;
      boolean changed = false;
      for (var quest : rules.quests.values()) {
        int id = quest.getId();
        int goal = rules.finishes.get(quest.getFinishWayID()).getProgress();
        var state = player.getQuestStates().get(id);
        if (state == null) {
          state = new QuestState(id);
          player.getQuestStates().put(id, state);
          changed = true;
        }
        int count = counts.get(id);
        if (MissionStateRecords.doing(state, id, goal) && state.getProgress() <= count)
          changed |=
              state.advance(count - state.getProgress(), goal, System.currentTimeMillis() / 1000);
      }
      return changed;
    }
  }

  public static QuestState state(Player player, int id) {
    return states(player).get(id);
  }

  public static Map<Integer, QuestState> states(Player player) {
    synchronized (player) {
      var rules = BoxingClubResidentQuestRules.load();
      if (rules == null) return Map.of();
      var counts = counts(player, rules);
      if (counts == null) return Map.of();
      var result = new TreeMap<Integer, QuestState>();
      for (var quest : rules.quests.values()) {
        int id = quest.getId();
        int goal = rules.finishes.get(quest.getFinishWayID()).getProgress();
        var state = player.getQuestStates().get(id);
        if ((MissionStateRecords.doing(state, id, goal)
                || MissionStateRecords.completed(state, id, goal))
            && state.getProgress() <= counts.get(id)) result.put(id, state);
      }
      return Map.copyOf(result);
    }
  }

  private BoxingClubResidentQuests() {}
}
