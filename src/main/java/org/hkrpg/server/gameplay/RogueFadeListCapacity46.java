package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueOwnedParameterLists46.References;

/** Ordinary capacity setter of the compiled single-effect payload list. */
public final class RogueFadeListCapacity46 {
  public static final int LIMIT = 4096;

  private RogueFadeListCapacity46() {}

  public interface World {
    Object[] allocate(int capacity);

    Object[] emptyArray();

    void copy(
        Object[] source, int sourceIndex, Object[] destination, int destinationIndex, int count);
  }

  public static void setCapacity(References list, int capacity, World world) {
    require(list, "fade capacity list");
    if (list.size > capacity)
      throw new IllegalArgumentException("Fade capacity below current count");
    Object[] storage = require(list.entries, "fade capacity storage");
    if (storage.length == capacity) return;
    World bound = require(world, "fade capacity array bindings");
    Object[] replacement;
    if (capacity > 0) {
      finite(capacity);
      replacement = bound.allocate(capacity);
      int count = list.size;
      if (count > 0) {
        finite(count);
        bound.copy(list.entries, 0, replacement, 0, count);
      }
    } else replacement = bound.emptyArray();
    list.entries = replacement;
  }

  private static void finite(int value) {
    if (value > LIMIT) throw new IllegalArgumentException("Excessive finite fade capacity array");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
