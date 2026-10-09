package org.hkrpg.server.gameplay;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/** Finite standard values projected explicitly from the original status dictionaries. */
public final class RogueModifierResistance46 {
  private static final long ONE = 1L << 33;

  private RogueModifierResistance46() {}

  @FunctionalInterface
  public interface ValueMapping {
    OptionalLong value(int flag);
  }

  public static long behaviorMaximum(
      List<Integer> flags, ValueMapping mapping, List<Integer> anti) {
    if (flags == null || flags.isEmpty()) return 0;
    if (mapping == null) throw new IllegalArgumentException("Missing current resistance mapping");
    long maximum = 0;
    for (int i = 0; i < flags.size(); i++) {
      if (i >= 255 || flags.get(i) == null)
        throw new IllegalArgumentException("Invalid finite behavior list");
      int flag = flags.get(i);
      OptionalLong value = mapping.value(flag);
      if (value == null)
        throw new IllegalArgumentException("Missing explicit dictionary lookup result");
      if (value.isEmpty()) continue;
      long current = standard(value.getAsLong());
      if (anti != null && anti.contains(flag) && current < ONE) continue;
      maximum = Math.max(maximum, current);
    }
    return maximum;
  }

  public static long probabilityMaximum(List<Integer> flags, ValueMapping mapping) {
    return behaviorMaximum(flags, mapping, null);
  }

  public static long statusFactor(
      LongSupplier statusResistance,
      List<Integer> flags,
      ValueMapping mapping,
      List<Integer> anti) {
    if (statusResistance == null)
      throw new IllegalArgumentException("Missing explicit status property");
    long base =
        RogueResonanceExpression46.subtractStandard(ONE, standard(statusResistance.getAsLong()));
    long behavior = behaviorMaximum(flags, mapping, anti);
    return RogueResonanceExpression46.multiplyStandard(
        base, RogueResonanceExpression46.subtractStandard(ONE, behavior));
  }

  public static long extraHit(
      LongSupplier statusProbability, List<Integer> flags, ValueMapping mapping) {
    if (statusProbability == null)
      throw new IllegalArgumentException("Missing explicit hit property");
    long property = standard(statusProbability.getAsLong());
    return RogueResonanceExpression46.addStandard(property, probabilityMaximum(flags, mapping));
  }

  private static long standard(long value) {
    if ((value & 1L) != 0) throw new IllegalArgumentException("Extended status value is unmatched");
    return value;
  }
}
