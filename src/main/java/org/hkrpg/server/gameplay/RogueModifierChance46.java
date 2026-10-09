package org.hkrpg.server.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Finite chance overrides and matching; pools, actual resistance and random application stay bound.
 */
public final class RogueModifierChance46 {
  private RogueModifierChance46() {}

  public record Adjustment(
      String name,
      RogueModifierNumbers46.Triplet chance,
      Supplier<Object> context,
      List<Integer> behaviorFlags) {
    public Adjustment {
      if (chance == null) throw new IllegalArgumentException("Missing explicit chance triplet");
    }
  }

  @FunctionalInterface
  public interface Definition {
    boolean containsBehaviorFlag(int flag);
  }

  @FunctionalInterface
  public interface Component {
    Boolean mutedFlag(int flag);
  }

  /** Explicit binding: this class does not manufacture the game's native reference pool. */
  public interface AdjustmentPool {
    List<Adjustment> collect();

    void release(List<Adjustment> selected);
  }

  public static boolean matches(
      String name, Component component, Adjustment adjustment, Definition definition) {
    if (adjustment == null) return false;
    var reference = adjustment.name();
    if (reference != null && !reference.isEmpty()) return reference.equals(name);
    var flags = adjustment.behaviorFlags();
    if (flags == null) return false;
    if (definition == null) {
      if (!flags.isEmpty())
        throw new IllegalArgumentException("Missing definition for behavior flag");
      return false;
    }
    for (int i = 0; i < flags.size(); i++) {
      if (i >= 255 || flags.get(i) == null)
        throw new IllegalArgumentException("Invalid finite behavior flags");
      int flag = flags.get(i);
      if (definition.containsBehaviorFlag(flag)
          && (component == null || !Boolean.TRUE.equals(component.mutedFlag(flag)))) return true;
    }
    return false;
  }

  /** Preserve enumeration order and null when no entry matches, without inventing a live pool. */
  public static List<Adjustment> collect(
      String name, Component component, Iterable<Adjustment> mapping, Definition definition) {
    if (component == null || mapping == null) return null;
    List<Adjustment> selected = null;
    int visited = 0;
    for (var adjustment : mapping) {
      if (visited++ >= 255) throw new IllegalArgumentException("Unmatched finite mapping size");
      if (matches(name, component, adjustment, definition)) {
        if (selected == null) selected = new ArrayList<>();
        selected.add(adjustment);
      }
    }
    return selected;
  }

  public static long calculate(
      RogueModifierNumbers46.Dynamic source,
      Supplier<Object> sourceContext,
      RogueModifierNumbers46.Triplet override,
      Supplier<Object> overrideContext,
      List<Adjustment> adjustments) {
    long value = read(source, context(sourceContext));
    Object selected = overrideContext == null ? null : overrideContext.get();
    if (selected != null) {
      if (override == null) throw new IllegalArgumentException("Missing explicit chance override");
      if (override.absolute() != null) {
        value = read(override.absolute(), selected);
      } else if (value >= 0 && (override.percent() != null || override.additive() != null)) {
        long factor =
            override.percent() == null
                ? 1L << 33
                : RogueResonanceExpression46.addStandard(
                    1L << 33, read(override.percent(), context(overrideContext)));
        long add =
            override.additive() == null ? 0L : read(override.additive(), context(overrideContext));
        value =
            RogueResonanceExpression46.addStandard(
                RogueResonanceExpression46.multiplyStandard(value, factor), add);
      }
    }
    return aggregate(value, adjustments);
  }

  /** The original entry returns a selected list only after normal chance evaluation. */
  public static long evaluate(
      RogueModifierNumbers46.Dynamic source,
      Supplier<Object> sourceContext,
      RogueModifierNumbers46.Triplet override,
      Supplier<Object> overrideContext,
      AdjustmentPool pool) {
    if (pool == null)
      throw new IllegalArgumentException("Missing explicit adjustment pool binding");
    List<Adjustment> selected = pool.collect();
    long value = calculate(source, sourceContext, override, overrideContext, selected);
    if (selected != null) pool.release(selected);
    return value;
  }

  public static long aggregate(long value, List<Adjustment> adjustments) {
    standard(value);
    if (value < 0 || adjustments == null || adjustments.isEmpty()) return value;
    long factor = 1L << 33;
    long additive = 0L;
    for (int i = 0; i < adjustments.size(); i++) {
      if (i >= 255 || adjustments.get(i) == null)
        throw new IllegalArgumentException("Invalid finite chance adjustment");
      var adjustment = adjustments.get(i);
      var triple = adjustment.chance();
      if (triple.percent() == null && triple.additive() == null) continue;
      if (triple.percent() != null) {
        factor =
            RogueResonanceExpression46.addStandard(
                factor, read(triple.percent(), context(adjustment.context())));
      }
      if (triple.additive() != null) {
        additive =
            RogueResonanceExpression46.addStandard(
                additive, read(triple.additive(), context(adjustment.context())));
      }
    }
    return RogueResonanceExpression46.addStandard(
        RogueResonanceExpression46.multiplyStandard(value, factor), additive);
  }

  private static Object context(Supplier<Object> source) {
    Object value = source == null ? null : source.get();
    if (value == null) throw new IllegalArgumentException("Missing current chance context");
    return value;
  }

  private static long read(RogueModifierNumbers46.Dynamic dynamic, Object context) {
    if (dynamic == null || context == null)
      throw new IllegalArgumentException("Missing explicit chance cell");
    long value = dynamic.evaluate(context);
    standard(value);
    return value;
  }

  private static void standard(long value) {
    if ((value & 1L) != 0L)
      throw new IllegalArgumentException("Extended chance value is unmatched");
  }
}
