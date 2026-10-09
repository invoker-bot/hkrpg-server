package org.hkrpg.server.gameplay;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Optional source tables; absent cycle resources do not invent a reset schedule. */
public final class DailyCycleCatalog {
  public record Cycle(
      int CycleID,
      List<Integer> QuestList,
      int MinLevel,
      int MaxLevel,
      int Cycledays,
      List<Integer> WeekDayList,
      int ScheduleDataID) {}

  public record Daily(int DailyID, List<Integer> QuestList, int MinLevel, int MaxLevel) {}

  public record Schedule(int ID, String BeginTime, String EndTime) {}

  public static final Map<Integer, Cycle> cycles = new HashMap<>();
  public static final Map<Integer, Daily> dailies = new HashMap<>();
  public static final Map<Integer, Schedule> schedules = new HashMap<>();

  public static void load(Path root) {
    cycles.clear();
    dailies.clear();
    schedules.clear();
    GameplayDefinitions.rows(
        root, "CycleQuest", Cycle.class, row -> unique(cycles, row.CycleID(), row));
    GameplayDefinitions.rows(
        root, "DailyQuest", Daily.class, row -> unique(dailies, row.DailyID(), row));
    GameplayDefinitions.rows(
        root, "ScheduleDataQuest", Schedule.class, row -> unique(schedules, row.ID(), row));
  }

  private static <T> void unique(Map<Integer, T> map, int id, T value) {
    if (id <= 0 || map.putIfAbsent(id, value) != null)
      throw new IllegalArgumentException("Duplicate or invalid daily cycle resource: " + id);
  }

  private DailyCycleCatalog() {}
}
