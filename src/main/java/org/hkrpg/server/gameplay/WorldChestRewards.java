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
    String worldTag =
        switch (world) {
          case 101 -> "CHEST_WORLD_ZERO";
          case 201 -> "CHEST_WORLD_ONE";
          case 301 -> "CHEST_WORLD_TWO";
          case 401 -> "CHEST_WORLD_THREE";
          case 501 -> "CHEST_WORLD_FOUR";
          case 601 -> "CHEST_WORLD_PLANARCADIA";
          case 602 -> "CHEST_WORLD_ASTROPOLIS";
          default -> null;
        };
    if (worldTag == null || !tags.contains(worldTag)) return null;
    int material = world == 602 ? 120006 : 120000 + (world / 100 - 1);
    return switch (levels.getFirst()) {
      case "CHEST_LOW_LEVEL" -> Map.of(1, 5, 2, 500, 22, 5, material, 15, 212, 3, 232, 1);
      case "CHEST_MIDDLE_LEVEL" -> Map.of(1, 20, 2, 2500, 22, 20, material, 15, 212, 3, 232, 1);
      case "CHEST_HIGH_LEVEL" -> Map.of(1, 30, 22, 30, material, 15, 212, 3, 232, 1);
      default -> null;
    };
  }

  private WorldChestRewards() {}
}
