package org.hkrpg.server.gameplay;

import com.google.gson.JsonArray;
import java.util.*;

/** This server's basic chest economy; original MazeChest data identifies the tier and world. */
final class WorldChestRewards {
  static final int VERSION = 1;

  static Map<Integer, Integer> recipe(int world, JsonArray types) {
    if (types == null) return null;
    var tags = new HashSet<String>();
    for (var value : types)
      if (!value.isJsonPrimitive() || !tags.add(value.getAsString())) return null;
    var levels =
        tags.stream()
            .filter(
                t ->
                    Set.of("CHEST_LOW_LEVEL", "CHEST_MIDDLE_LEVEL", "CHEST_HIGH_LEVEL").contains(t))
            .toList();
    if (levels.size() != 1) return null;
    if (world == 101)
      return tags.equals(Set.of("CHEST_WORLD_ZERO", "CHEST_TREASURE_NORMAl", "CHEST_LOW_LEVEL"))
          ? Map.of(1, 5, 2, 500, 22, 5, 120000, 15, 212, 3, 232, 1)
          : null;
    if (world != 501 || !tags.contains("CHEST_WORLD_FOUR")) return null;
    return switch (levels.getFirst()) {
      case "CHEST_LOW_LEVEL" -> Map.of(1, 5, 2, 500, 22, 5, 120004, 15, 212, 3, 232, 1);
      case "CHEST_MIDDLE_LEVEL" -> Map.of(1, 20, 2, 2500, 22, 20, 120004, 15, 212, 3, 232, 1);
      case "CHEST_HIGH_LEVEL" -> Map.of(1, 30, 22, 30, 120004, 15, 212, 3, 232, 1);
      default -> null;
    };
  }

  private WorldChestRewards() {}
}
