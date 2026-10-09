package org.hkrpg.server.gameplay;

import java.util.List;

/**
 * Raw ordering and standard root-cell injections; mixed cell arithmetic and live worlds stay
 * closed.
 */
public final class RogueModifierInjection46 {
  private RogueModifierInjection46() {}

  public record FloatInjection(int key, long raw) {}

  public static int compareRaw(long first, long second) {
    if ((first & 1) == 0) {
      if ((second & 1) == 0) return Long.compare(first, second);
      return compareStandardToExtended(first, second);
    }
    if ((second & 1) == 0) return -compareStandardToExtended(second, first);
    return Long.compare(first & ~1L, second & ~1L);
  }

  private static int compareStandardToExtended(long standard, long extended) {
    int comparison = Long.compare(standard >> 7, extended & ~1L);
    return comparison != 0 ? comparison : (standard & 0x7f) == 0 ? 0 : 1;
  }

  public static long clampRaw(long raw, long minimum, long maximum) {
    if (compareRaw(raw, minimum) < 0) return minimum;
    return compareRaw(raw, maximum) > 0 ? maximum : raw;
  }

  public static void applyFloatInjections(RogueModifierReplace46.State state, Object injections) {
    if (state == null || !(injections instanceof List<?> list))
      throw new IllegalArgumentException("Missing explicit numeric injection list");
    for (int index = 0; index < list.size(); index++) {
      if (index >= 255) throw new IllegalArgumentException("Excessive finite numeric injections");
      Object entry = list.get(index);
      if (!(entry instanceof FloatInjection injection))
        throw new IllegalArgumentException("Unmatched fixed numeric injection element");
      Object current = state.values.dynamicValues;
      if (!(current instanceof RogueResonanceVariables46 variables))
        throw new IllegalArgumentException("Missing explicit root numeric injection container");
      variables.injectSourceStandard(injection.key(), injection.raw());
    }
  }
}
