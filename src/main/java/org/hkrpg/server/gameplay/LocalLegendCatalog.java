package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.nio.file.*;
import java.util.*;

/** Original identities, target order and thresholds; resource presence never opens an activity. */
public final class LocalLegendCatalog {
  public record Goal(int id, long threshold, String ability) {}

  public record Group(int id, int module) {}

  public record Reward(int quest, String taskType, int sort) {}

  public record Level(
      int group,
      int difficulty,
      int event,
      int unlockQuest,
      int battleArea,
      List<Goal> goals,
      List<Integer> trials) {
    public Level {
      goals = List.copyOf(goals);
      trials = List.copyOf(trials);
    }

    public StageExcel stage(int world) {
      var stage = SceneEventBattles.resolve(event, world);
      return stage != null && stage.getStageType() == StageType.LocalLegend ? stage : null;
    }

    /** Category 5 is from the pinned native statistics converter, not a config enum ordinal. */
    public OptionalInt reportedMask(BattleStatistics stats) {
      if (stats == null) return OptionalInt.empty();
      var categories = new HashSet<Integer>();
      int mask = 0;
      for (var entry : stats.getBattleTargetInfo()) {
        int key = entry.getKey();
        // The pinned native converter also emits category 6 (client achievements).
        // Achievement categories remain separate from category 5 Local Legend stars.
        if (key < 1 || key > 6 || !categories.add(key)) return OptionalInt.empty();
        var identities = new HashSet<Integer>();
        for (var target : entry.getValue().getBattleTargetList()) {
          int id = target.getId();
          if (id <= 0 || !identities.add(id)) return OptionalInt.empty();
          if (key != 5) continue;
          int index = -1;
          for (int i = 0; i < goals.size(); i++) if (goals.get(i).id() == id) index = i;
          if (index < 0) return OptionalInt.empty();
          // Reported total_progress cannot lower the original resource threshold.
          if (Integer.toUnsignedLong(target.getProgress()) >= goals.get(index).threshold())
            mask |= 1 << index;
        }
      }
      return OptionalInt.of(mask);
    }

    public int allowedMask() {
      return goals.size() == 32 ? -1 : (1 << goals.size()) - 1;
    }

    public boolean validMask(int mask) {
      return (mask & ~allowedMask()) == 0;
    }

    public boolean complete(int mask) {
      return validMask(mask) && mask == allowedMask();
    }

    public String identity() {
      return group
          + ":"
          + difficulty
          + ":"
          + event
          + ":"
          + unlockQuest
          + ":"
          + battleArea
          + ":"
          + goals
          + ":"
          + trials;
    }
  }

  private record Snapshot(
      Map<Integer, Group> groups, Map<String, Level> levels, Map<Integer, Reward> rewards) {}

  private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of());
  private static volatile Snapshot current = EMPTY;

  public static synchronized void load(Path root) {
    current = EMPTY;
    LocalLegendAchievementRules.clear();
    var names =
        List.of("LocalLegendStageConfig", "ActivityLocalLegendGroup", "ActivityLocalLegendReward");
    long present =
        names.stream()
            .filter(n -> Files.isRegularFile(root.resolve("ExcelOutput/" + n + ".json")))
            .count();
    if (present == 0) return;
    if (present != names.size())
      throw new IllegalStateException("Incomplete Local Legend resource tables");
    try {
      var groups = new HashMap<Integer, Group>();
      var modules = new HashSet<Integer>();
      for (var row : rows(root, "ActivityLocalLegendGroup", 64)) {
        var group = new Group(positive(row, "GroupID"), positive(row, "ActivityModuleID"));
        if (groups.putIfAbsent(group.id(), group) != null || !modules.add(group.module()))
          invalid();
      }
      var targets = new HashMap<Integer, JsonObject>();
      for (var row : rows(root, "BattleTargetConfig", 256))
        if (targets.putIfAbsent(positive(row, "ID"), row) != null) invalid();
      var levels = new HashMap<String, Level>();
      var events = new HashSet<Integer>();
      for (var row : rows(root, "LocalLegendStageConfig", 64)) {
        int group = positive(row, "GroupID");
        int difficulty =
            switch (text(row, "DifficultyLevel")) {
              case "Easy" -> 1;
              case "Hard" -> 2;
              case "Fearless" -> 3;
              default -> throw new IllegalArgumentException("Unknown Local Legend difficulty");
            };
        int event = positive(row, "EventID");
        var goals = new ArrayList<Goal>();
        for (int id : integers(row, "BattleTargetList", 32, false)) {
          var target = targets.get(id);
          if (target == null
              || !text(target, "Type").equals("ChallengeTarget")
              || !text(target, "ParamType").equals("GreaterEqual")) invalid();
          long threshold = integer(target, "TargetParam", false, 0xffffffffL);
          goals.add(new Goal(id, threshold, text(target, "AbilityName")));
        }
        var level =
            new Level(
                group,
                difficulty,
                event,
                row.has("UnlockQuest")
                    ? (int) integer(row, "UnlockQuest", true, Integer.MAX_VALUE)
                    : 0,
                positive(row, "BattleAreaID"),
                goals,
                integers(row, "TrialAvatarList", 32, true));
        if (!LocalLegendWinnerTargets.valid(level)
            || !groups.containsKey(group)
            || !events.add(event)
            || levels.putIfAbsent(key(group, difficulty), level) != null) invalid();
      }
      if (groups.isEmpty() || levels.isEmpty()) invalid();
      for (int group : groups.keySet())
        for (int difficulty = 1; difficulty <= 3; difficulty++)
          if (!levels.containsKey(key(group, difficulty))) invalid();
      var rewards = new HashMap<Integer, Reward>();
      for (var row : rows(root, "ActivityLocalLegendReward", 64)) {
        String type = text(row, "TaskType");
        if (!type.equals("EasyHard") && !type.equals("Fearless")) invalid();
        var reward =
            new Reward(
                positive(row, "ID"), type, (int) integer(row, "Sort", true, Integer.MAX_VALUE));
        if (rewards.putIfAbsent(reward.quest(), reward) != null) invalid();
      }
      if (rewards.isEmpty()) invalid();
      current = new Snapshot(Map.copyOf(groups), Map.copyOf(levels), Map.copyOf(rewards));
      LocalLegendAchievementRules.load(root);
    } catch (Exception invalid) {
      current = EMPTY;
      LocalLegendAchievementRules.clear();
      throw new IllegalStateException("Invalid Local Legend resource catalog", invalid);
    }
  }

  public static Level level(int group, int difficulty) {
    return current.levels().get(key(group, difficulty));
  }

  public static List<Level> levels() {
    return current.levels().values().stream()
        .sorted(Comparator.comparingInt(Level::group).thenComparingInt(Level::difficulty))
        .toList();
  }

  public static Group group(int id) {
    return current.groups().get(id);
  }

  public static List<Reward> rewards() {
    return current.rewards().values().stream()
        .sorted(Comparator.comparingInt(Reward::sort).thenComparingInt(Reward::quest))
        .toList();
  }

  private static String key(int group, int difficulty) {
    return group + ":" + difficulty;
  }

  private static void invalid() {
    throw new IllegalArgumentException("Invalid or duplicate Local Legend identity");
  }

  static List<JsonObject> rows(Path root, String name, int limit) throws Exception {
    var path = root.resolve("ExcelOutput/" + name + ".json");
    if (!Files.isRegularFile(path) || Files.size(path) > 2097152) invalid();
    var json = JsonParser.parseString(Files.readString(path));
    var rows =
        json.isJsonArray()
            ? json.getAsJsonArray().asList()
            : json.getAsJsonObject().asMap().values();
    if (rows.size() > limit) invalid();
    return rows.stream().map(JsonElement::getAsJsonObject).toList();
  }

  static long integer(JsonObject row, String name, boolean zero, long maximum) {
    var value = row.get(name);
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber()
        || !value.getAsString().matches(zero ? "0|[1-9][0-9]*" : "[1-9][0-9]*")) {
      invalid();
      return 0;
    }
    long parsed = Long.parseLong(value.getAsString());
    if (parsed > maximum) invalid();
    return parsed;
  }

  static int positive(JsonObject row, String name) {
    return (int) integer(row, name, false, Integer.MAX_VALUE);
  }

  static String text(JsonObject row, String name) {
    var value = row.get(name);
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isString()
        || value.getAsString().isBlank()) {
      invalid();
      return "";
    }
    return value.getAsString();
  }

  static List<Integer> integers(JsonObject row, String name, int maximum, boolean empty) {
    var values = row.getAsJsonArray(name);
    if (values == null || values.size() > maximum || !empty && values.isEmpty()) {
      invalid();
      return List.of();
    }
    var result = new ArrayList<Integer>();
    var seen = new HashSet<Integer>();
    for (var value : values) {
      var wrapper = new JsonObject();
      wrapper.add("id", value);
      int id = positive(wrapper, "id");
      if (!seen.add(id)) invalid();
      result.add(id);
    }
    return List.copyOf(result);
  }

  private LocalLegendCatalog() {}
}
