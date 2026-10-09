package org.hkrpg.server.gameplay;

import java.util.Objects;
import java.util.function.IntToLongFunction;

/** Explicit NK task-cell projection, with a caller-bound alias evaluator and cached value. */
public final class RogueResonanceTaskCell46 implements RogueResonanceCell46 {
  public static final int RESULT_KEY = RogueResonanceVariables46.nameKey("_ResultValue");

  @FunctionalInterface
  public interface AliasEvaluator {
    long evaluate(Object taskConfiguration, int outputKey);
  }

  private final Object taskConfiguration;
  private final RogueResonanceDynamicCell46 limits;
  private final RogueResonanceAdsorption46.Config adsorption;
  private volatile long cached;

  public RogueResonanceTaskCell46(
      Object taskConfiguration,
      RogueResonanceDynamicCell46.Scalar minimum,
      RogueResonanceDynamicCell46.Scalar maximum,
      RogueResonanceAdsorption46.Config adsorption) {
    this.taskConfiguration =
        Objects.requireNonNull(taskConfiguration, "Missing explicit task identity");
    limits = new RogueResonanceDynamicCell46(null, minimum, maximum);
    this.adsorption = adsorption;
  }

  private RogueResonanceTaskCell46(RogueResonanceTaskCell46 source) {
    taskConfiguration = source.taskConfiguration;
    limits = source.limits.copy();
    adsorption = source.adsorption;
    cached = source.cached;
  }

  public long read(IntToLongFunction provider, AliasEvaluator evaluator) {
    if (provider == null || evaluator == null) return cached;
    long result = evaluator.evaluate(taskConfiguration, RESULT_KEY);
    long snapped = RogueResonanceAdsorption46.apply(result, adsorption);
    long clipped = limits.clip(snapped, provider);
    cached = clipped;
    return clipped;
  }

  public long clip(long raw, IntToLongFunction provider) {
    return limits.clip(raw, provider);
  }

  public RogueResonanceVariables46.Bounds bounds(IntToLongFunction provider) {
    return limits.bounds(provider);
  }

  /** The matched NK setter logs the attempted write and never modifies its cache or task. */
  public void replaceValue(long raw) {
    if ((raw & 1) != 0) throw new IllegalArgumentException("Extended task-cell write is unmatched");
  }

  public RogueResonanceTaskCell46 copy() {
    return new RogueResonanceTaskCell46(this);
  }
}
