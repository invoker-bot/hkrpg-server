package org.hkrpg.server.gameplay;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.FinishWayExcel;
import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;

/** Source daily cycle objectives. Unknown gameplay branches never supply completion records. */
public final class DailyCycleQuests {
  private static final ZoneOffset CHS = ZoneOffset.ofHours(8);
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
  private final Player player;

  public DailyCycleQuests(Player player) {
    this.player = player;
  }

  private record Source(int id, int goal, String spec, boolean rogue) {}

  private record Parent(int id, int goal, String spec, List<Source> sources) {}

  private static boolean bare(FinishWayExcel rule) {
    return rule != null
        && rule.getProgress() == 1
        && rule.getParamInt2() == 0
        && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && rule.getParamIntList() != null
        && rule.getParamItemList() != null
        && rule.getParamItemList().isEmpty()
        && !rule.isIsBackTrack();
  }

  private static boolean rogue(FinishWayExcel rule) {
    return bare(rule)
        && "RogueFinishCnt".equals(rule.getFinishType())
        && "NoPara".equals(rule.getParamType())
        && rule.getParamInt1() == 0
        && rule.getParamIntList().isEmpty();
  }

  private static boolean blockedGrid(FinishWayExcel rule) {
    // This original alternative is recognized structurally, but has no accepted completion event.
    return bare(rule)
        && "GridFightFinishGamePlay".equals(rule.getFinishType())
        && "NoPara".equals(rule.getParamType())
        && rule.getParamInt1() == 1
        && List.of(1, 2).equals(rule.getParamIntList());
  }

  private static FinishWayExcel rule(QuestExcel definition) {
    var value =
        definition == null
            ? null
            : GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return value != null && value.getId() == definition.getFinishWayID() ? value : null;
  }

  private boolean unlocked(QuestExcel definition) {
    if (definition == null || definition.getUnlockParamList() == null) return false;
    var ids = definition.getUnlockParamList();
    if ("AutoUnlock".equals(definition.getUnlockType())) return ids.isEmpty();
    return "FinishMission".equals(definition.getUnlockType())
        && !ids.isEmpty()
        && ids.size() <= 32
        && new HashSet<>(ids).size() == ids.size()
        && ids.stream()
            .allMatch(id -> id != null && id > 0 && player.getMissionManager().finished(id));
  }

  private Object rewards(QuestExcel definition) {
    var reward = GameData.getRewardExcelMap().get(definition.getRewardID());
    if (definition.getRewardID() <= 0
        || reward == null
        || reward.getId() != definition.getRewardID()
        || reward.getRewards() == null) return null;
    var items = new TreeMap<Integer, Integer>();
    for (var item : reward.getRewards()) {
      if (item.getIntKey() <= 0 || item.getIntValue() <= 0) return null;
      items.put(item.getIntKey(), item.getIntValue());
    }
    return List.of(definition.getRewardID(), items);
  }

  private Source source(int id, Instant now) {
    var definition = GameData.getQuestExcelMap().get(id);
    var rule = rule(definition);
    if (id <= 0
        || definition == null
        || definition.getId() != id
        || definition.getQuestType() != 3
        || !unlocked(definition)
        || !rogue(rule) && !blockedGrid(rule)) return null;
    var owners =
        DailyCycleCatalog.cycles.values().stream()
            .filter(c -> c != null && c.QuestList() != null && c.QuestList().contains(id))
            .toList();
    if (owners.size() != 1) return null;
    var cycle = owners.getFirst();
    if (cycle.CycleID() <= 0
        || DailyCycleCatalog.cycles.get(cycle.CycleID()) != cycle
        || !List.of(id).equals(cycle.QuestList())
        || cycle.Cycledays() != 1
        || cycle.MinLevel() <= 0
        || cycle.MaxLevel() < cycle.MinLevel()
        || player.getLevel() < cycle.MinLevel()
        || player.getLevel() > cycle.MaxLevel()
        || cycle.WeekDayList() == null
        || cycle.WeekDayList().size() != 7
        || !new HashSet<>(cycle.WeekDayList()).equals(Set.of(1, 2, 3, 4, 5, 6, 7))) return null;
    var schedule = DailyCycleCatalog.schedules.get(cycle.ScheduleDataID());
    if (cycle.ScheduleDataID() <= 0 || schedule == null || schedule.ID() != cycle.ScheduleDataID())
      return null;
    try {
      var begin = LocalDateTime.parse(schedule.BeginTime(), TIME).toInstant(CHS);
      var end = LocalDateTime.parse(schedule.EndTime(), TIME).toInstant(CHS);
      if (!begin.isBefore(end) || now.isBefore(begin) || !now.isBefore(end)) return null;
    } catch (DateTimeException | NullPointerException invalid) {
      return null;
    }
    var reward = rewards(definition);
    if (reward == null) return null;
    return new Source(
        id, rule.getProgress(), spec(definition, rule, cycle, schedule, reward), rogue(rule));
  }

  private Parent parent(int id, Instant now) {
    var definition = GameData.getQuestExcelMap().get(id);
    var rule = rule(definition);
    if (id <= 0
        || definition == null
        || definition.getId() != id
        || definition.getQuestType() != 5
        || !unlocked(definition)
        || !bare(rule)
        || !"FinishQuest".equals(rule.getFinishType())
        || !"ListContain".equals(rule.getParamType())
        || rule.getParamInt1() != 0) return null;
    var ids = rule.getParamIntList();
    if (ids.isEmpty()
        || ids.size() > 32
        || new HashSet<>(ids).size() != ids.size()
        || ids.stream().anyMatch(child -> child == null || child <= 0 || child == id)) return null;
    var owners =
        DailyCycleCatalog.dailies.values().stream()
            .filter(d -> d != null && d.QuestList() != null && d.QuestList().contains(id))
            .toList();
    if (owners.size() != 1) return null;
    var daily = owners.getFirst();
    if (daily.DailyID() <= 0
        || DailyCycleCatalog.dailies.get(daily.DailyID()) != daily
        || !List.of(id).equals(daily.QuestList())
        || daily.MinLevel() <= 0
        || daily.MaxLevel() < daily.MinLevel()
        || player.getLevel() < daily.MinLevel()
        || player.getLevel() > daily.MaxLevel()) return null;
    var sources = new ArrayList<Source>();
    for (int child : ids) {
      var candidate = source(child, now);
      var childDefinition = GameData.getQuestExcelMap().get(child);
      if (candidate == null
          || !definition.getUnlockType().equals(childDefinition.getUnlockType())
          || !definition.getUnlockParamList().equals(childDefinition.getUnlockParamList()))
        return null;
      sources.add(candidate);
    }
    var reward = rewards(definition);
    if (reward == null || sources.stream().filter(Source::rogue).count() < rule.getProgress())
      return null;
    return new Parent(
        id,
        rule.getProgress(),
        spec(definition, rule, daily, reward, sources),
        List.copyOf(sources));
  }

  private static String spec(Object... values) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(new Gson().toJson(values).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public boolean supportedDaily(int id, Instant now) {
    synchronized (player) {
      return parent(id, now) != null;
    }
  }

  private DailyCycleQuestState.Window window(Instant now) {
    var state = player.getGameplay();
    if (DailyManager.day(now) != state.getDailyDay()) return null;
    var windows = state.getDailyCycleQuests().getWindows();
    var current = windows == null ? null : windows.get(Long.toString(state.getDailyDay()));
    return current != null && current.getDay() == state.getDailyDay() ? current : null;
  }

  /**
   * Called with DailyManager's current day already established; never calls ensureDay recursively.
   */
  boolean refresh(Instant now) {
    synchronized (player) {
      var state = player.getGameplay();
      if (DailyManager.day(now) != state.getDailyDay()) return false;
      var supported = new TreeMap<Integer, Source>();
      for (var definition : GameData.getQuestExcelMap().values()) {
        var source = source(definition.getId(), now);
        if (source != null && source.rogue()) supported.put(source.id(), source);
      }
      if (supported.isEmpty()) return false;
      var windows = state.getDailyCycleQuests().getWindows();
      if (windows == null) return false;
      String key = Long.toString(state.getDailyDay());
      boolean changed = !windows.containsKey(key);
      if (changed) windows.put(key, new DailyCycleQuestState.Window(state.getDailyDay()));
      var current = window(now);
      if (!intact(current)) return changed;
      for (var source : supported.values()) {
        if (current.getQuests().containsKey(source.id())
            || current.getSourceSpecs().containsKey(source.id())) continue;
        current.getQuests().put(source.id(), new QuestState(source.id()));
        current.getSourceSpecs().put(source.id(), source.spec());
        changed = true;
      }
      for (var entry : state.getDailyQuests().entrySet()) {
        if (entry.getKey() == null) continue;
        var parent = parent(entry.getKey(), now);
        if (parent == null) continue;
        if (!current.getParentSpecs().containsKey(parent.id())
            && MissionStateRecords.doing(entry.getValue(), parent.id(), parent.goal())
            && entry.getValue().getProgress() == 0) {
          current.getParentSpecs().put(parent.id(), parent.spec());
          changed = true;
        }
        if (!parent.spec().equals(current.getParentSpecs().get(parent.id()))
            || !MissionStateRecords.doing(entry.getValue(), parent.id(), parent.goal())) continue;
        int count = completedSources(current, parent, now);
        changed |=
            entry
                .getValue()
                .advance(
                    count - entry.getValue().getProgress(), parent.goal(), now.getEpochSecond());
      }
      return changed;
    }
  }

  private static boolean intact(DailyCycleQuestState.Window current) {
    return current != null
        && current.getQuests() != null
        && current.getSourceSpecs() != null
        && current.getParentSpecs() != null;
  }

  private static boolean completed(QuestState state, Source source, Instant now) {
    return MissionStateRecords.completed(state, source.id(), source.goal())
        && state.getFinishTime() <= now.getEpochSecond()
        && DailyManager.day(Instant.ofEpochSecond(state.getFinishTime())) == DailyManager.day(now);
  }

  private static int completedSources(
      DailyCycleQuestState.Window current, Parent parent, Instant now) {
    int count = 0;
    for (var source : parent.sources())
      if (source.rogue()
          && source.spec().equals(current.getSourceSpecs().get(source.id()))
          && completed(current.getQuests().get(source.id()), source, now)) count++;
    return count;
  }

  boolean onRogueFinish(Instant now) {
    synchronized (player) {
      var current = window(now);
      if (!intact(current)) return false;
      boolean changed = false;
      for (var entry : current.getQuests().entrySet()) {
        if (entry.getKey() == null) continue;
        var source = source(entry.getKey(), now);
        if (source != null
            && source.rogue()
            && source.spec().equals(current.getSourceSpecs().get(source.id()))
            && MissionStateRecords.doing(entry.getValue(), source.id(), source.goal()))
          changed |= entry.getValue().advance(1, source.goal(), now.getEpochSecond());
      }
      return refresh(now) || changed;
    }
  }

  public QuestState claimState(int id, Instant now) {
    synchronized (player) {
      var current = window(now);
      var source = source(id, now);
      if (!intact(current)
          || source == null
          || !source.rogue()
          || !source.spec().equals(current.getSourceSpecs().get(id))) return null;
      var state = current.getQuests().get(id);
      return MissionStateRecords.doing(state, id, source.goal()) || completed(state, source, now)
          ? state
          : null;
    }
  }

  public List<QuestState> states(Instant now) {
    synchronized (player) {
      var current = window(now);
      if (!intact(current)) return List.of();
      return current.getQuests().keySet().stream()
          .filter(Objects::nonNull)
          .map(id -> claimState(id, now))
          .filter(Objects::nonNull)
          .sorted(Comparator.comparingInt(QuestState::getQuestId))
          .toList();
    }
  }

  boolean summaryClaimValid(int id, QuestState state, Instant now) {
    synchronized (player) {
      var current = window(now);
      var definition = GameData.getQuestExcelMap().get(id);
      var rule = rule(definition);
      boolean linked = rule != null && "FinishQuest".equals(rule.getFinishType());
      boolean saved = intact(current) && current.getParentSpecs().containsKey(id);
      if (!linked && !saved) return true;
      var parent = parent(id, now);
      if (!intact(current)
          || parent == null
          || !parent.spec().equals(current.getParentSpecs().get(id))) return false;
      if (MissionStateRecords.doing(state, id, parent.goal())) return true;
      return MissionStateRecords.completed(state, id, parent.goal())
          && completedSources(current, parent, now) >= parent.goal()
          && state.getFinishTime() <= now.getEpochSecond()
          && DailyManager.day(Instant.ofEpochSecond(state.getFinishTime()))
              == DailyManager.day(now);
    }
  }
}
