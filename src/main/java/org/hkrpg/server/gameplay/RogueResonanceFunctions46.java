package org.hkrpg.server.gameplay;

/** Raw 4.6 FixPoint callbacks; battle variable contexts are supplied separately. */
public final class RogueResonanceFunctions46 {
  public static final int FLOOR_KEY = 1776456860;
  public static final int ROUND_KEY = 1554761690;
  public static final int CEIL_KEY = -514975797;
  public static final int ABS_KEY = 1099313850;
  public static final int CLAMP01_KEY = 368963114;
  public static final int MIN_KEY = 339799074;
  public static final int MAX_KEY = 696029444;
  public static final int CLAMP_KEY = -1355705909;
  public static final int INT_KEY = -1226284721;
  public static final int MOD_KEY = 1502598494;
  public static final int LEFTSHIFT_KEY = -1825696445;
  public static final int RIGHTSHIFT_KEY = 965494012;
  public static final int AND_KEY = -1226285001;
  public static final int OR_KEY = 381169821;
  public static final int XOR_KEY = 1502599105;

  private RogueResonanceFunctions46() {}

  static boolean isMatchedUnary(int key) {
    return key == FLOOR_KEY
        || key == ROUND_KEY
        || key == CEIL_KEY
        || key == ABS_KEY
        || key == CLAMP01_KEY
        || key == INT_KEY;
  }

  static boolean isMatchedFunction(int key) {
    return isMatchedUnary(key)
        || key == MIN_KEY
        || key == MAX_KEY
        || key == CLAMP_KEY
        || key == MOD_KEY
        || key == LEFTSHIFT_KEY
        || key == RIGHTSHIFT_KEY
        || key == AND_KEY
        || key == OR_KEY
        || key == XOR_KEY;
  }

  static boolean acceptsArguments(int key, int count) {
    if (key == MIN_KEY || key == MAX_KEY || key == AND_KEY || key == OR_KEY || key == XOR_KEY)
      return count >= 1 && count <= 255;
    if (key == CLAMP_KEY) return count == 3;
    if (key == MOD_KEY || key == LEFTSHIFT_KEY || key == RIGHTSHIFT_KEY) return count == 2;
    return isMatchedUnary(key) && count == 1;
  }

  /** Floor accepts both encodings; other callbacks retain their matched standard input range. */
  public static long invoke(int key, long... arguments) {
    if (arguments == null || !acceptsArguments(key, arguments.length)) {
      throw new IllegalArgumentException("Unmatched resonance function or argument count");
    }
    long[] values = arguments.clone();
    return switch (key) {
      case FLOOR_KEY -> floor(values[0]);
      case ROUND_KEY -> round(values[0]);
      case CEIL_KEY -> ceil(values[0]);
      case ABS_KEY -> abs(values[0]);
      case CLAMP01_KEY -> clamp01(values[0]);
      case MIN_KEY -> extrema(values, true);
      case MAX_KEY -> extrema(values, false);
      case CLAMP_KEY -> RogueResonanceVariables46.clamp(values[0], values[1], values[2]);
      case INT_KEY -> integer(values[0]);
      case MOD_KEY -> modulo(values[0], values[1]);
      case LEFTSHIFT_KEY -> shift(values[0], values[1], true);
      case RIGHTSHIFT_KEY -> shift(values[0], values[1], false);
      case AND_KEY, OR_KEY, XOR_KEY -> bitFold(key, values);
      default -> throw new IllegalArgumentException("Unmatched resonance function");
    };
  }

  /** Native int preserves approximately integral input, then uses float32 conversion. */
  private static long integer(long raw) {
    int nearest = nearestInteger(raw);
    if (nearest == 0x40000000)
      throw new IllegalArgumentException("Extended integer result is unmatched");
    long rounded = (long) nearest << 33;
    // This difference is bounded by half of ONE, including the negative endpoint.
    if (Math.abs(raw - rounded) < 8588) return rounded;
    int truncated = (int) ((float) raw * 0x1p-33f);
    return encodeInteger(truncated);
  }

  /** This inline native rounding may precede a 32-bit operation without returning FixPoint. */
  private static int nearestInteger(long raw) {
    standard(raw);
    long integral = raw >> 33;
    long fraction = raw & 0x1ffffffffL;
    if (fraction > 0x100000000L || fraction == 0x100000000L && (integral & 1) != 0) integral++;
    return (int) integral;
  }

  private static long encodeInteger(int value) {
    if (Math.abs((long) value) >= 0x40000000L)
      throw new IllegalArgumentException("Extended integer result is unmatched");
    return (long) value << 33;
  }

  private static long modulo(long left, long right) {
    standard(left);
    standard(right);
    if (right == 0) throw new IllegalArgumentException("Resonance modulo by zero");
    return left % right;
  }

  private static long shift(long raw, long count, boolean left) {
    int value = nearestInteger(raw);
    standard(count);
    int bits = (int) (count >> 33);
    return encodeInteger(left ? value << bits : value >> bits);
  }

  private static long bitFold(int key, long[] values) {
    int result = nearestInteger(values[0]);
    for (int i = 1; i < values.length; i++) {
      int next = nearestInteger(values[i]);
      result = key == AND_KEY ? result & next : key == OR_KEY ? result | next : result ^ next;
    }
    return encodeInteger(result);
  }

  private static long extrema(long[] values, boolean minimum) {
    long result = values[0];
    standard(result);
    for (int i = 1; i < values.length; i++) {
      standard(values[i]);
      if (minimum ? values[i] < result : values[i] > result) result = values[i];
    }
    return result;
  }

  public static long ceil(long raw) {
    standard(raw);
    if ((raw & 0x1ffffffffL) == 0) return raw;
    long integral = raw >> 33;
    if (integral == 0x3fffffffL) {
      throw new IllegalArgumentException("Extended resonance ceiling result is unmatched");
    }
    return (integral + 1) << 33;
  }

  /** Native neg/cmovs keeps Long.MIN_VALUE on signed negation overflow. */
  public static long abs(long raw) {
    standard(raw);
    return Math.abs(raw);
  }

  public static long clamp01(long raw) {
    standard(raw);
    return raw < 0 ? 0 : raw > (1L << 33) ? (1L << 33) : raw;
  }

  private static void standard(long raw) {
    if ((raw & 1) != 0) throw new IllegalArgumentException("Extended function input is unmatched");
  }

  /** Standard native rounding chooses the even integer at an exact midpoint. */
  public static long round(long raw) {
    return (long) roundToInt(raw) << 33;
  }

  /** The opened range also matches the standard branch of native FixPoint.RoundToInt. */
  public static int roundToInt(long raw) {
    long integral = raw >> 33;
    if ((raw & 1) != 0 || Math.abs(integral) >= 0x40000000L) {
      throw new IllegalArgumentException("Unmatched resonance rounding input");
    }
    long fraction = raw & 0x1ffffffffL;
    if (fraction > 0x100000000L || fraction == 0x100000000L && (integral & 1) != 0) {
      integral++;
    }
    if (Math.abs(integral) >= 0x40000000L) {
      throw new IllegalArgumentException("Extended resonance rounding result is unmatched");
    }
    return (int) integral;
  }

  /** Return an encoded FixPoint, preserving the client's standard floor mask. */
  public static long floor(long raw) {
    if ((raw & 1) == 0) {
      return raw & 0xfffffffe00000000L;
    }
    long integralRaw = raw & 0xfffffffffc000000L;
    long result;
    if (integralRaw <= 0xa2de246003ffffffL) {
      result = 0xa2de246004000001L;
    } else if (integralRaw >= 0x5d21db9ffc000001L) {
      result = 0x5d21db9ffc000001L;
    } else {
      result = integralRaw + 1;
    }
    if ((result & 1) != 0 && Math.abs(result >> 26) <= 0x3fffffffL) {
      return (result & ~1L) << 7;
    }
    return result;
  }
}
