package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.game.player.Player;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Original activity relation and story prerequisites, plus actual operator group windows. */
public final class LocalLegendAccess {
  public static final int PANEL = 50070;

  private record Access(int minimumLevel, Set<Integer> modules, List<Integer> missions) {}

  private static volatile Access current;

  private static JsonObject row(Path root, String name, String identity)
      throws java.io.IOException {
    var path = root.resolve("ExcelOutput/" + name + ".json");
    if (Files.size(path) > 4 * 1024 * 1024)
      throw new IllegalArgumentException("Oversized activity table");
    var rows = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    if (rows.size() > 2048) throw new IllegalArgumentException("Oversized activity table");
    JsonObject found = null;
    for (var value : rows) {
      var entry = value.getAsJsonObject();
      if (positive(entry.get(identity)) != PANEL) continue;
      if (found != null)
        throw new IllegalArgumentException("Duplicate Local Legend activity identity");
      found = entry;
    }
    if (found == null) throw new IllegalArgumentException("Missing Local Legend activity relation");
    return found;
  }

  private static int positive(JsonElement value) {
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber()
        || !value.getAsString().matches("[1-9][0-9]*"))
      throw new IllegalArgumentException("Invalid activity identity");
    return Integer.parseInt(value.getAsString());
  }

  public static synchronized void load(Path root) {
    current = null;
    if (LocalLegendCatalog.levels().isEmpty()) return;
    try {
      var config = row(root, "ActivityConfig", "ActivityID");
      if (positive(config.get("ActivityPanelID")) != PANEL)
        throw new IllegalArgumentException("Unmatched activity panel");
      var modules = new HashSet<Integer>();
      var values = config.getAsJsonArray("ActivityModuleIDList");
      if (values.isEmpty() || values.size() > 64)
        throw new IllegalArgumentException("Invalid activity modules");
      for (var value : values)
        if (!modules.add(positive(value)))
          throw new IllegalArgumentException("Duplicate activity module");
      for (var level : LocalLegendCatalog.levels()) {
        var group = LocalLegendCatalog.group(level.group());
        if (group == null || !modules.contains(group.module()))
          throw new IllegalArgumentException("Foreign Local Legend group module");
      }
      var panel = row(root, "ActivityPanel", "PanelID");
      var condition = panel.get("UnlockConditions");
      if (condition == null
          || !condition.isJsonPrimitive()
          || !condition.getAsJsonPrimitive().isString())
        throw new IllegalArgumentException("Invalid activity unlock condition");
      var match =
          Pattern.compile("\\[PlayerLevel:([1-9][0-9]*)\\]").matcher(condition.getAsString());
      if (!match.matches())
        throw new IllegalArgumentException("Unsupported Local Legend panel unlock condition");
      int minimum = Integer.parseInt(match.group(1));
      var prerequisites =
          row(root, "ActivityPanelCondition", "PanelID").getAsJsonArray("PreConditions");
      if (prerequisites.isEmpty() || prerequisites.size() > 32)
        throw new IllegalArgumentException("Invalid activity prerequisites");
      var missions = new LinkedHashSet<Integer>();
      for (var value : prerequisites) {
        var prerequisite = value.getAsJsonObject();
        var type = prerequisite.get("Type");
        var param = prerequisite.get("Param");
        if (type == null
            || !type.isJsonPrimitive()
            || !type.getAsJsonPrimitive().isString()
            || !type.getAsString().equals("FinishMainMission")
            || param == null
            || !param.isJsonPrimitive()
            || !param.getAsJsonPrimitive().isString()
            || !param.getAsString().matches("[1-9][0-9]*")
            || !missions.add(Integer.parseInt(param.getAsString())))
          throw new IllegalArgumentException("Unsupported activity prerequisite");
      }
      current = new Access(minimum, Set.copyOf(modules), List.copyOf(missions));
    } catch (java.io.IOException | RuntimeException invalid) {
      throw new IllegalStateException("Invalid Local Legend activity access resources", invalid);
    }
  }

  public static boolean available(Player player, LocalLegendCatalog.Level level, Instant time) {
    synchronized (player) {
      var source = current;
      if (source == null
          || level == null
          || time == null
          || LocalLegendCatalog.level(level.group(), level.difficulty()) != level
          || !player.getGameplay().getLocalLegendProgress().valid()
          || player.getLevel() < source.minimumLevel()
          || source.missions().stream()
              .anyMatch(id -> !MissionStateRecords.mainCompleted(player, id))) return false;
      var group = LocalLegendCatalog.group(level.group());
      if (group == null || !source.modules().contains(group.module())) return false;
      long now = time.getEpochSecond();
      long active =
          GameplayDefinitions.windows.stream()
              .filter(w -> w.panelId() == PANEL && w.moduleId() == group.module())
              .filter(
                  w ->
                      w.beginTime() > 0
                          && w.endTime() > w.beginTime()
                          && w.beginTime() <= now
                          && now < w.endTime())
              .count();
      return active == 1
          && (level.difficulty() == 1
              ? level.unlockQuest() == 0
              : level.unlockQuest() > 0
                  && QuestCompletionRecords.completed(player, level.unlockQuest()));
    }
  }

  private LocalLegendAccess() {}
}
