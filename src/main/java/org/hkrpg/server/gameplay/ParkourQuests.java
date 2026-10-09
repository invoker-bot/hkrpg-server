package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.FinishWayExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Resource-linked activity objectives, measured only from this operator window's accepted races.
 */
public final class ParkourQuests {
  private static final Pattern CONDITION = Pattern.compile("\\[QuestClose:([0-9]+)\\]");
  private final Player player;

  public ParkourQuests(Player player) {
    this.player = player;
  }

  private static String key(GameplayDefinitions.Window w) {
    return w.moduleId() + ":" + w.beginTime() + ":" + w.endTime();
  }

  private Set<Integer> linked() {
    var panel = GameData.getActivityPanelExcelMap().get(50039);
    if (panel == null
        || panel.getFinishConditions() == null
        || panel.getFinishConditions().isBlank()) return Set.of();
    var ids = new LinkedHashSet<Integer>();
    for (var part : panel.getFinishConditions().split(",", -1)) {
      var match = CONDITION.matcher(part.trim());
      if (!match.matches()) return Set.of();
      try {
        int id = Integer.parseInt(match.group(1));
        if (id <= 0 || !ids.add(id)) return Set.of();
      } catch (NumberFormatException e) {
        return Set.of();
      }
    }
    return ids;
  }

  private FinishWayExcel rule(int id) {
    var q = GameData.getQuestExcelMap().get(id);
    return q == null || q.getQuestType() != 3
        ? null
        : GameData.getFinishWayExcelMap().get(q.getFinishWayID());
  }

  private boolean valid(int id, Set<Integer> linked) {
    var q = GameData.getQuestExcelMap().get(id);
    var r = rule(id);
    if (q == null
        || r == null
        || !linked.contains(id)
        || !"AutoUnlock".equals(q.getUnlockType())
        || q.getUnlockParamList() == null
        || !q.getUnlockParamList().isEmpty()
        || !r.isIsBackTrack()
        || r.getProgress() <= 0
        || r.getParamInt3() != 0
        || r.getParamStr1() == null
        || !r.getParamStr1().isBlank()
        || r.getParamItemList() == null
        || !r.getParamItemList().isEmpty()
        || r.getParamIntList() == null) return false;
    var ids = r.getParamIntList();
    return switch (r.getFinishType()) {
      case "ParkourFinishLevel" ->
          "ListContain".equals(r.getParamType())
              && r.getParamInt1() == 0
              && r.getParamInt2() == 0
              && !ids.isEmpty()
              && new HashSet<>(ids).size() == ids.size()
              && r.getProgress() <= ids.size()
              && ids.stream()
                  .allMatch(
                      level ->
                          level != null
                              && level > 0
                              && GameplayDefinitions.parkourLevels.containsKey(level));
      case "ParkourReachRankInLevel" ->
          "Equal".equals(r.getParamType())
              && ids.isEmpty()
              && r.getProgress() == 1
              && GameplayDefinitions.parkourLevels.containsKey(r.getParamInt1())
              && r.getParamInt2() > 0
              && r.getParamInt2() <= 32;
      case "FinishQuest" ->
          "ListContain".equals(r.getParamType())
              && r.getParamInt1() == 0
              && r.getParamInt2() == 0
              && !ids.isEmpty()
              && new HashSet<>(ids).size() == ids.size()
              && r.getProgress() <= ids.size()
              && ids.stream()
                  .allMatch(
                      other ->
                          other != null
                              && other > 0
                              && other != id
                              && linked.contains(other)
                              && rule(other) != null);
      default -> false;
    };
  }

  private Map<Integer, FinishWayExcel> supported() {
    var ids = linked();
    var result = new TreeMap<Integer, FinishWayExcel>();
    for (int id : ids) if (valid(id, ids)) result.put(id, rule(id));
    // A summary cannot read unsupported or unrelated prerequisites, including nested summaries.
    boolean removed;
    do {
      removed =
          result
              .entrySet()
              .removeIf(
                  e ->
                      "FinishQuest".equals(e.getValue().getFinishType())
                          && !result.keySet().containsAll(e.getValue().getParamIntList()));
    } while (removed);
    return result;
  }

  void record(
      GameplayDefinitions.Window window, int level, int time, int rank, boolean passed, int ball) {
    var domain =
        player
            .getGameplay()
            .getParkour()
            .getQuestWindows()
            .computeIfAbsent(key(window), ignored -> new GameplayState.ParkourQuestWindow());
    domain
        .getRecords()
        .computeIfAbsent(level, ignored -> new GameplayState.ParkourRecord())
        .record(time, rank, passed, ball);
  }

  public boolean refresh() {
    synchronized (player) {
      return refreshLocked();
    }
  }

  private boolean refreshLocked() {
    var active = new ParkourManager(player).activeWindow();
    var rules = supported();
    if (active.isEmpty() || rules.isEmpty()) return false;
    var windows = player.getGameplay().getParkour().getQuestWindows();
    boolean changed = !windows.containsKey(key(active.get()));
    var domain =
        windows.computeIfAbsent(
            key(active.get()), ignored -> new GameplayState.ParkourQuestWindow());
    for (int id : rules.keySet()) {
      if (!domain.getQuests().containsKey(id)) {
        domain.getQuests().put(id, new QuestState(id));
        changed = true;
      }
    }
    // Recompute a total rather than adding it on each query or reconnect.
    for (int pass = 0; pass <= rules.size(); pass++) {
      boolean advanced = false;
      for (var e : rules.entrySet()) {
        var state = domain.getQuests().get(e.getKey());
        var rule = e.getValue();
        int measured =
            switch (rule.getFinishType()) {
              case "ParkourFinishLevel" ->
                  (int)
                      rule.getParamIntList().stream()
                          .filter(
                              level ->
                                  domain.getRecords().containsKey(level)
                                      && domain.getRecords().get(level).isPassed())
                          .count();
              case "ParkourReachRankInLevel" -> {
                var result = domain.getRecords().get(rule.getParamInt1());
                yield result != null
                        && result.getRank() > 0
                        && result.getRank() <= rule.getParamInt2()
                    ? 1
                    : 0;
              }
              case "FinishQuest" ->
                  (int)
                      rule.getParamIntList().stream()
                          .filter(
                              id -> {
                                var prior = domain.getQuests().get(id);
                                return prior != null && prior.getPhase() != QuestState.Phase.DOING;
                              })
                          .count();
              default -> 0;
            };
        advanced |=
            state.advance(
                measured - state.getProgress(),
                rule.getProgress(),
                System.currentTimeMillis() / 1000);
      }
      changed |= advanced;
      if (!advanced) break;
    }
    return changed;
  }

  public List<QuestState> states() {
    synchronized (player) {
      return statesLocked();
    }
  }

  private List<QuestState> statesLocked() {
    var active = new ParkourManager(player).activeWindow();
    if (active.isEmpty()) return List.of();
    var domain = player.getGameplay().getParkour().getQuestWindows().get(key(active.get()));
    if (domain == null) return List.of();
    var rules = supported();
    return domain.getQuests().entrySet().stream()
        .filter(e -> rules.containsKey(e.getKey()))
        .sorted(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue)
        .toList();
  }

  public QuestState claimState(int id) {
    return states().stream().filter(q -> q.getQuestId() == id).findFirst().orElse(null);
  }
}
