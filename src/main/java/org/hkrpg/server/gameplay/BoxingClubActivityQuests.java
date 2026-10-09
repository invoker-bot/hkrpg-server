package org.hkrpg.server.gameplay;

import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.time.Instant;
import java.util.*;

/** Original nonperiodic goals observe only accepted completions inside their owning module. */
public final class BoxingClubActivityQuests {
  public static boolean belongs(QuestExcel definition) {
    return definition != null && BoxingClubActivityQuestRules.sourceID(definition.getId());
  }

  static BoxingClubActivityQuestState record(
      Player p, BoxingClubState.Run first, BoxingClubResonanceState.Run second, Instant at) {
    var current = p.getGameplay().getBoxingClubActivityQuests();
    var rules = BoxingClubActivityQuestRules.load();
    if (rules == null || at == null || (first == null) == (second == null)) return current;
    int season = first != null ? 1 : 2;
    if (season == 1 && !rules.unlocked(p, rules.quests.get(6000060))) return current;
    var window = rules.window(p, season, at);
    if (window == null || counts(p, rules) == null) return current;
    var proof =
        new BoxingClubActivityQuestState.Completion(window, at.getEpochSecond(), first, second);
    if (!proof.valid(
        p,
        season == 1 ? BoxingClubRules.load() : null,
        season == 2 ? BoxingClubResonanceRules.load() : null)) return current;
    var next = current.copy();
    next.record(proof);
    return next;
  }

  private static Map<Integer, Integer> counts(Player p, BoxingClubActivityQuestRules rules) {
    if (rules == null) return null;
    var domain = p.getGameplay().getBoxingClubActivityQuests();
    var records = domain.getCompleted();
    if (records == null
        || records.size() > 12
        || domain.getQuests() == null
        || domain.getQuests().size() > 50) return null;
    var firstRules =
        records.keySet().stream().anyMatch(k -> k != null && k >= 1 && k <= 5)
            ? BoxingClubRules.load()
            : null;
    var secondRules =
        records.keySet().stream().anyMatch(k -> k != null && k >= 6 && k <= 12)
            ? BoxingClubResonanceRules.load()
            : null;
    for (var e : records.entrySet())
      if (e.getKey() == null
          || e.getValue() == null
          || !e.getValue().valid(p, firstRules, secondRules)
          || e.getKey() != e.getValue().challenge()) return null;
    if (domain.getQuests().keySet().stream()
        .anyMatch(id -> id == null || !BoxingClubActivityQuestRules.sourceID(id))) return null;
    var result = new TreeMap<Integer, Integer>();
    for (var quest : rules.quests.values()) {
      var finish = rules.finishes.get(quest.getFinishWayID());
      if (!"BoxingClubAccomplish".equals(finish.getFinishType())) continue;
      var proof = records.get(finish.getParamInt2());
      // The original first-season hidden clear goals omit the turn limit. As for resident
      // clears, this server uses an accepted complete run without an extra turn restriction.
      result.put(
          quest.getId(),
          proof != null && (finish.getParamInt1() == 0 || proof.rounds() <= finish.getParamInt1())
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
    return result.size() == 50 ? result : null;
  }

  public static boolean refresh(Player p) {
    synchronized (p) {
      var rules = BoxingClubActivityQuestRules.load();
      var counts = counts(p, rules);
      if (counts == null) return false;
      var domain = p.getGameplay().getBoxingClubActivityQuests();
      boolean changed = false;
      var now = Instant.now();
      for (var quest : rules.quests.values()) {
        int id = quest.getId(), goal = rules.finishes.get(quest.getFinishWayID()).getProgress();
        if (rules.window(p, BoxingClubActivityQuestRules.season(id), now) == null
            || !rules.unlocked(p, quest)) continue;
        var state = domain.getQuests().get(id);
        if (state == null) {
          state = new QuestState(id);
          domain.getQuests().put(id, state);
          changed = true;
        }
        int count = counts.get(id);
        if (MissionStateRecords.doing(state, id, goal) && state.getProgress() <= count)
          changed |= state.advance(count - state.getProgress(), goal, now.getEpochSecond());
      }
      return changed;
    }
  }

  private static QuestState checked(
      Player p, int id, BoxingClubActivityQuestRules rules, Map<Integer, Integer> counts) {
    var quest = rules == null ? null : rules.quests.get(id);
    if (quest == null || counts == null || !rules.unlocked(p, quest)) return null;
    int goal = rules.finishes.get(quest.getFinishWayID()).getProgress();
    var state = p.getGameplay().getBoxingClubActivityQuests().getQuests().get(id);
    return (MissionStateRecords.doing(state, id, goal)
                || MissionStateRecords.completed(state, id, goal))
            && state.getProgress() <= counts.get(id)
        ? state
        : null;
  }

  public static QuestState completionState(Player p, int id) {
    synchronized (p) {
      var rules = BoxingClubActivityQuestRules.load();
      return checked(p, id, rules, counts(p, rules));
    }
  }

  public static Map<Integer, QuestState> states(Player p) {
    synchronized (p) {
      var rules = BoxingClubActivityQuestRules.load();
      var counts = counts(p, rules);
      if (counts == null) return Map.of();
      var result = new TreeMap<Integer, QuestState>();
      var now = Instant.now();
      for (int id : rules.quests.keySet()) {
        if (rules.window(p, BoxingClubActivityQuestRules.season(id), now) == null) continue;
        var state = checked(p, id, rules, counts);
        if (state != null) result.put(id, state);
      }
      return Map.copyOf(result);
    }
  }

  public static QuestState state(Player p, int id) {
    return states(p).get(id);
  }

  private BoxingClubActivityQuests() {}
}
