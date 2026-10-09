package org.hkrpg.server.gameplay;

import java.util.OptionalInt;
import java.util.function.Supplier;

/** Explicit modifier integer cells; live parameter dictionaries and effect instances stay bound. */
public final class RogueModifierNumbers46 {
  private RogueModifierNumbers46() {}

  @FunctionalInterface
  public interface Dynamic {
    long evaluate(Object context);
  }

  public record Triplet(Dynamic absolute, Dynamic percent, Dynamic additive) {}

  public record Config(
      Dynamic count, Dynamic maxLayer, Dynamic lifeTime, Dynamic layerAddWhenStack) {}

  public record Overrides(
      Triplet maxLayer, Triplet lifeTime, Triplet layerAddWhenStack, Supplier<Object> context) {
    public Overrides {
      if (maxLayer == null || lifeTime == null || layerAddWhenStack == null || context == null)
        throw new IllegalArgumentException("Missing explicit override cells");
    }
  }

  public record Resolved(
      OptionalInt count,
      OptionalInt maxLayer,
      OptionalInt lifeTime,
      OptionalInt layerAddWhenStack) {}

  public static OptionalInt integer(
      Dynamic source,
      Supplier<Object> sourceContext,
      Triplet override,
      Supplier<Object> overrideContext) {
    if (overrideContext == null || overrideContext.get() == null)
      return ordinary(source, sourceContext);
    if (override == null) throw new IllegalArgumentException("Missing explicit override triplet");
    if (override.absolute() != null)
      return OptionalInt.of((int) (read(override.absolute(), overrideContext) >> 33));
    if (source == null) return OptionalInt.empty();
    if (override.percent() == null && override.additive() == null)
      return ordinary(source, sourceContext);
    long base = read(source, sourceContext);
    long factor =
        override.percent() == null
            ? 1L << 33
            : RogueResonanceExpression46.addStandard(
                1L << 33, read(override.percent(), overrideContext));
    long add = override.additive() == null ? 0L : read(override.additive(), overrideContext);
    return OptionalInt.of(
        RogueResonanceFunctions46.roundToInt(
            RogueResonanceExpression46.addStandard(
                RogueResonanceExpression46.multiplyStandard(base, factor), add)));
  }

  public static Resolved resolve(
      Config config, Supplier<Object> sourceContext, Overrides overrides) {
    if (config == null) throw new IllegalArgumentException("Missing explicit source config");
    return new Resolved(
        ordinary(config.count(), sourceContext),
        integer(
            config.maxLayer(),
            sourceContext,
            overrides == null ? null : overrides.maxLayer(),
            overrides == null ? null : overrides.context()),
        integer(
            config.lifeTime(),
            sourceContext,
            overrides == null ? null : overrides.lifeTime(),
            overrides == null ? null : overrides.context()),
        integer(
            config.layerAddWhenStack(),
            sourceContext,
            overrides == null ? null : overrides.layerAddWhenStack(),
            overrides == null ? null : overrides.context()));
  }

  private static OptionalInt ordinary(Dynamic source, Supplier<Object> sourceContext) {
    return source == null
        ? OptionalInt.empty()
        : OptionalInt.of(RogueResonanceFunctions46.roundToInt(read(source, sourceContext)));
  }

  private static long read(Dynamic dynamic, Supplier<Object> context) {
    Object selected = context == null ? null : context.get();
    if (selected == null) throw new IllegalArgumentException("Missing current numeric context");
    long raw = dynamic.evaluate(selected);
    if ((raw & 1L) != 0L)
      throw new IllegalArgumentException("Extended modifier integer input is unmatched");
    return raw;
  }
}
