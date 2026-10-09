package org.hkrpg.server.gameplay;

/** Seeded base Random and ordinary logic sample only; native counter events require bindings. */
public final class RogueLogicRandom46 {
  private static final double INVERSE_MAX = Double.longBitsToDouble(0x3e00000000200000L);
  private static final double SCALE = 8589934592.0;
  private final Seeded random;
  private final CounterBinding counters;
  private final int seed;
  private int calls;

  public interface CounterBinding {
    void notifyChange(int oldCount, int newCount);

    void publishGlobal(int newCount);
  }

  public RogueLogicRandom46(int seed, CounterBinding counters) {
    if (counters == null) throw new IllegalArgumentException("Missing explicit counter binding");
    this.seed = seed;
    this.counters = counters;
    random = new Seeded(seed);
  }

  public int seed() {
    return seed;
  }

  public int callCount() {
    return calls;
  }

  public long next() {
    double sample = random.nextDouble();
    int old = calls, next = old + 1;
    counters.notifyChange(old, next);
    calls = next;
    counters.publishGlobal(next);
    return ((long) (sample * SCALE)) & ~1L;
  }

  /**
   * Exact seeded base-class sequence; unseeded construction and subclass overrides are unmatched.
   */
  public static final class Seeded {
    private final int[] values = new int[56];
    private int next, nextPartner = 21;

    public Seeded(int seed) {
      int subtraction = seed == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(seed);
      int mj = 161803398 - subtraction;
      values[55] = mj;
      int mk = 1;
      for (int i = 1; i < 55; i++) {
        int ii = (21 * i) % 55;
        values[ii] = mk;
        mk = mj - mk;
        if (mk < 0) mk += Integer.MAX_VALUE;
        mj = values[ii];
      }
      for (int pass = 0; pass < 4; pass++) {
        for (int i = 1; i < 56; i++) {
          values[i] -= values[1 + (i + 30) % 55];
          if (values[i] < 0) values[i] += Integer.MAX_VALUE;
        }
      }
    }

    public int nextIntSample() {
      int current = next + 1, partner = nextPartner + 1;
      if (current >= 56) current = 1;
      if (partner >= 56) partner = 1;
      int value = values[current] - values[partner];
      if (value == Integer.MAX_VALUE) value--;
      if (value < 0) value += Integer.MAX_VALUE;
      values[current] = value;
      next = current;
      nextPartner = partner;
      return value;
    }

    public double nextDouble() {
      return nextIntSample() * INVERSE_MAX;
    }
  }
}
