package org.hkrpg.server.gameplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Authored commission rows and per-type quotas, separate from the activity objective pool. */
public final class DailyMissionCatalog {
  public record Mission(
      int ID, int DailyMissionType, int GroupID, int UnlockMainMission, int QuestID) {}

  public record Count(int ID, int DailyMissionType, int DailyCount) {}

  private record Tables(
      boolean configured, Map<Integer, Mission> missions, Map<Integer, Count> counts) {}

  private static volatile Tables tables = new Tables(false, Map.of(), Map.of());

  public static void load(Path root) {
    boolean missionsPresent =
        Files.isRegularFile(root.resolve("ExcelOutput/DailyMissionData.json"));
    boolean countsPresent = Files.isRegularFile(root.resolve("ExcelOutput/DailyMissionCount.json"));
    if (!missionsPresent && !countsPresent) {
      tables = new Tables(false, Map.of(), Map.of());
      return;
    }
    if (!missionsPresent || !countsPresent)
      throw new IllegalStateException("Daily mission catalog requires both authored tables");
    var missions = new TreeMap<Integer, Mission>();
    var counts = new TreeMap<Integer, Count>();
    var countIds = new HashSet<Integer>();
    GameplayDefinitions.rows(
        root,
        "DailyMissionCount",
        Count.class,
        count -> {
          if (count.ID() <= 0
              || count.DailyMissionType() <= 0
              || count.DailyCount() <= 0
              || count.DailyCount() > 16
              || !countIds.add(count.ID())
              || counts.putIfAbsent(count.DailyMissionType(), count) != null)
            throw new IllegalArgumentException("Invalid or duplicate daily mission quota");
        });
    GameplayDefinitions.rows(
        root,
        "DailyMissionData",
        Mission.class,
        row -> {
          if (row.ID() <= 0
              || row.DailyMissionType() <= 0
              || row.GroupID() <= 0
              || row.UnlockMainMission() <= 0
              || row.QuestID() <= 0
              || !counts.containsKey(row.DailyMissionType())
              || missions.putIfAbsent(row.ID(), row) != null)
            throw new IllegalArgumentException("Invalid or duplicate daily mission row");
        });
    if (missions.isEmpty() || counts.isEmpty())
      throw new IllegalStateException("Empty daily mission catalog");
    tables =
        new Tables(
            true, Collections.unmodifiableMap(missions), Collections.unmodifiableMap(counts));
  }

  public static boolean configured() {
    return tables.configured();
  }

  public static Mission mission(int id) {
    return tables.missions().get(id);
  }

  public static Count count(int type) {
    return tables.counts().get(type);
  }

  public static Collection<Mission> missions() {
    return tables.missions().values();
  }

  private DailyMissionCatalog() {}
}
