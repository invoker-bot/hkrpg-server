package org.hkrpg.server.gameplay;

import java.util.function.IntToLongFunction;

/** Explicit standard-input projection of the matched FJN dynamic value cell. */
public final class RogueResonanceDynamicCell46 implements RogueResonanceCell46 {
  private static final long SOURCE_MINIMUM = -6710886399932891135L;
  private static final long SOURCE_MAXIMUM = 6710886399932891137L;

  public record Scalar(long fixedRaw, RogueResonanceExpression46.Program postfix) {
    public Scalar {
      if (postfix == null && (fixedRaw & 1) != 0)
        throw new IllegalArgumentException("Extended fixed DynamicFloat is unmatched");
    }

    public static Scalar fixed(long raw) {
      return new Scalar(raw, null);
    }

    public static Scalar fromPostfix(long fixedRaw, byte[] code, long[] constants, int[] keys) {
      if (code == null) return fixed(fixedRaw);
      var program = RogueResonanceExpression46.compile(code, constants, keys);
      if (program == null) throw new IllegalArgumentException("Unmatched DynamicFloat postfix");
      return new Scalar(fixedRaw, program);
    }

    public long read(IntToLongFunction provider) {
      if (postfix == null) return fixedRaw;
      return postfix.evaluateWithResolver(
          provider == null
              ? key -> {
                throw new IllegalArgumentException("Missing DynamicFloat numeric provider");
              }
              : provider);
    }
  }

  private volatile Scalar value;
  private final Scalar minimum;
  private final Scalar maximum;
  private final RogueResonanceAdsorption46.Config adsorption;

  public RogueResonanceDynamicCell46(Scalar value, Scalar minimum, Scalar maximum) {
    this(value, minimum, maximum, null);
  }

  public RogueResonanceDynamicCell46(
      Scalar value, Scalar minimum, Scalar maximum, RogueResonanceAdsorption46.Config adsorption) {
    this.adsorption = adsorption;
    this.value = value;
    this.minimum = minimum;
    this.maximum = maximum;
  }

  public long read(IntToLongFunction provider) {
    Scalar current = value;
    long raw = current == null ? 0 : current.read(provider);
    return clip(raw, provider);
  }

  public long read(IntToLongFunction provider, RogueResonanceTaskCell46.AliasEvaluator evaluator) {
    return read(provider);
  }

  public long clip(long raw, IntToLongFunction provider) {
    if ((raw & 1) != 0) throw new IllegalArgumentException("Extended dynamic write is unmatched");
    // The native getter evaluates both limits, even when the lower limit already clips the value.
    long lower = minimum == null ? SOURCE_MINIMUM : minimum.read(provider);
    long upper = maximum == null ? SOURCE_MAXIMUM : maximum.read(provider);
    // All standard words lie strictly inside the absent-range extended limits. Do not send
    // those limits through the standard-only clamp or reinterpret their tagged words as longs.
    if (minimum != null && raw < lower) return lower;
    return maximum != null && raw > upper ? upper : raw;
  }

  public RogueResonanceDynamicCell46 copy() {
    return new RogueResonanceDynamicCell46(value, minimum, maximum, adsorption);
  }

  /** Native setter applies adsorption before creating the replacement fixed scalar. */
  public void replaceValue(long raw) {
    value = Scalar.fixed(RogueResonanceAdsorption46.apply(raw, adsorption));
  }

  public RogueResonanceVariables46.Bounds bounds(IntToLongFunction provider) {
    long lower = minimum == null ? SOURCE_MINIMUM : minimum.read(provider);
    long upper = maximum == null ? SOURCE_MAXIMUM : maximum.read(provider);
    return new RogueResonanceVariables46.Bounds(lower, upper);
  }
}
