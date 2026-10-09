package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueBaseModifierDestroy46.Callback;

/** Matched ordinary array search validation and default-comparer dispatch. */
public final class RogueEventSingleArraySearch46 {
  private RogueEventSingleArraySearch46() {}

  public interface World {
    Object defaultComparer();

    int comparerLastIndexOf(
        Object comparer, Callback[] source, Callback value, int start, int count);
  }

  public static int lastIndexOf(
      Callback[] source, Callback value, int start, int count, World world) {
    if (source == null) throw new IllegalArgumentException("Missing callback search array");
    if (source.length == 0) {
      if (start != -1 && start != 0)
        throw new IllegalArgumentException("Invalid empty callback search start");
      if (count != 0) throw new IllegalArgumentException("Invalid empty callback search count");
      return -1;
    }
    if (start < 0 || start >= source.length)
      throw new IllegalArgumentException("Invalid callback search start");
    if (count < 0 || start - count < -1)
      throw new IllegalArgumentException("Invalid callback search count");
    if (world == null) throw new IllegalArgumentException("Missing default-comparer search world");
    Object comparer = world.defaultComparer();
    if (comparer == null) throw new IllegalArgumentException("Missing default callback comparer");
    return world.comparerLastIndexOf(comparer, source, value, start, count);
  }
}
