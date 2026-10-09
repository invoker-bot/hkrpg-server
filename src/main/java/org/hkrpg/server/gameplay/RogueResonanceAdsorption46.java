package org.hkrpg.server.gameplay;

/** Finite standard-number branch of the matched PropertyAdsorptionConfig helper. */
public final class RogueResonanceAdsorption46 {
  private static final long ONE = 1L << 33;
  private static final long FRACTION_MASK = ONE - 2;
  private static final int[] POWERS = {
    1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000, 1_000_000_000
  };

  public record Config(long thresholdRaw, int fractionalDigit) {
    public Config {
      standard(thresholdRaw);
      if (fractionalDigit > 9)
        throw new IllegalArgumentException("Extended adsorption scale is unmatched");
    }
  }

  private RogueResonanceAdsorption46() {}

  private static void standard(long raw) {
    if ((raw & 1) != 0)
      throw new IllegalArgumentException("Extended adsorption input is unmatched");
  }

  public static long apply(long raw, Config config) {
    standard(raw);
    if (config == null) return raw;
    long scale = config.fractionalDigit() < 0 ? 0 : (long) POWERS[config.fractionalDigit()] << 33;
    long threshold = RogueResonanceExpression46.multiplyStandard(config.thresholdRaw(), scale);
    if (threshold <= 0) return raw;
    long fraction =
        RogueResonanceExpression46.multiplyStandard(raw & FRACTION_MASK, scale) & FRACTION_MASK;
    if (fraction < ONE / 2) {
      if (fraction > threshold) return raw;
      long correction = RogueResonanceExpression46.divideStandard(fraction, scale);
      return RogueResonanceExpression46.subtractStandard(raw, correction);
    }
    long distance = ONE - fraction;
    if (distance > threshold) return raw;
    long correction = RogueResonanceExpression46.divideStandard(distance, scale);
    return RogueResonanceExpression46.addStandard(raw, correction);
  }
}
