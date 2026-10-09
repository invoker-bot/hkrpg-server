package org.hkrpg.server.gameplay;

import java.nio.file.Path;
import java.util.*;

/** The classic module belongs to resource version 1; V2/V3 use separate protocols and tables. */
public final class MatchThreeCatalog {
  public record Version(
      int ActivityVersion, int ActivityID, int PVPModuleID, List<Integer> BirdIDList) {}

  public record Bird(int BirdID, int UnlockLevel) {}

  public record Level(int LevelID, int Mode, int UnlockID, int RewardID, int PlayerBirdID) {
    public String key() {
      return Mode + ":" + LevelID;
    }
  }

  public static final Map<Integer, Version> versions = new HashMap<>();
  public static final Map<Integer, Bird> birds = new HashMap<>();
  public static final Map<String, Level> levels = new HashMap<>();
  public static final Map<String, Integer> constants = new HashMap<>();

  public static void load(Path root) {
    versions.clear();
    birds.clear();
    levels.clear();
    constants.clear();
    GameplayDefinitions.rows(
        root, "MatchThreeVersion", Version.class, r -> unique(versions, r.ActivityVersion(), r));
    GameplayDefinitions.rows(root, "MatchThreeBird", Bird.class, r -> unique(birds, r.BirdID(), r));
    GameplayDefinitions.rows(root, "MatchThreeLevel", Level.class, r -> unique(levels, r.key(), r));
    for (String table : List.of("MatchThreeConstValueCommon", "MatchThreeConstValueClient"))
      GameplayDefinitions.rows(
          root,
          table,
          GameplayDefinitions.MusicConstant.class,
          r -> {
            if (r.Value() != null) unique(constants, r.ConstValueName(), r.Value().IntValue());
          });
  }

  private static <K, V> void unique(Map<K, V> map, K key, V value) {
    if (key == null || map.putIfAbsent(key, value) != null)
      throw new IllegalArgumentException("Duplicate MatchThree resource key: " + key);
  }

  public static List<Integer> roster() {
    var version = versions.get(1);
    if (version == null
        || version.ActivityID() != 50014
        || version.PVPModuleID() != 5001400
        || version.BirdIDList() == null
        || version.BirdIDList().isEmpty()
        || version.BirdIDList().stream()
            .anyMatch(id -> id == null || id <= 0 || !birds.containsKey(id))
        || new HashSet<>(version.BirdIDList()).size() != version.BirdIDList().size())
      return List.of();
    return version.BirdIDList().stream().sorted().toList();
  }

  private MatchThreeCatalog() {}
}
