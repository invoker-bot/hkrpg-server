package org.hkrpg.server.gameplay;

import java.nio.file.Path;
import java.util.*;

/** Original maze levels; client group graphs are a separate, currently absent resource. */
public final class WolfBroCatalog {
  public record Level(
      int WolfBroID,
      int PlaneID,
      int FloorID,
      List<Integer> GroupIDList,
      List<Integer> MonsterGroupIDList,
      int ControlGroupID,
      int StartState,
      int CloseState) {
    public Level {
      if (GroupIDList != null)
        GroupIDList = Collections.unmodifiableList(new ArrayList<>(GroupIDList));
      if (MonsterGroupIDList != null)
        MonsterGroupIDList = Collections.unmodifiableList(new ArrayList<>(MonsterGroupIDList));
    }

    public boolean valid() {
      return WolfBroID > 0
          && PlaneID > 0
          && FloorID > 0
          && ControlGroupID > 0
          && StartState >= 0
          && CloseState >= 0
          && groups(GroupIDList)
          && groups(MonsterGroupIDList);
    }

    private static boolean groups(List<Integer> groups) {
      return groups != null
          && groups.size() <= 128
          && groups.stream().allMatch(g -> g != null && g > 0)
          && new HashSet<>(groups).size() == groups.size();
    }

    String signature() {
      return toString();
    }
  }

  public static final Map<Integer, Level> levels = new HashMap<>();

  public static void load(Path root) {
    levels.clear();
    GameplayDefinitions.rows(
        root,
        "MazePuzzleWolfBro",
        Level.class,
        level -> {
          if (!level.valid() || levels.putIfAbsent(level.WolfBroID(), level) != null)
            throw new IllegalStateException("Invalid or duplicate WolfBro level");
        });
  }

  public static Level level(int id) {
    var level = levels.get(id);
    return level != null && level.WolfBroID() == id && level.valid() ? level : null;
  }

  private WolfBroCatalog() {}
}
