package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RogueResonanceFunctions46Test {
  private static final long STANDARD_ONE = 1L << 33;
  private static final long EXTENDED_ONE = 1L << 26;

  static Stream<Long> extendedCases() {
    return Stream.of(
        1L,
        -1L,
        EXTENDED_ONE | 1,
        -EXTENDED_ONE | 1,
        3 * EXTENDED_ONE / 2 | 1,
        -3 * EXTENDED_ONE / 2 | 1,
        (0x3fffffffL << 26) | 1,
        ((0x3fffffffL + 1) << 26) | 1,
        (-0x3fffffffL << 26) | 1,
        ((-0x3fffffffL - 1) << 26) | 1,
        0x5d21db9ffc000001L,
        0xa2de246004000001L,
        0x5d21db9ffc000003L,
        0xa2de246003ffffffL,
        Long.MAX_VALUE,
        Long.MIN_VALUE | 1);
  }

  /** Independent integral oracle: no floating-point operations or native client execution. */
  private static long expected(long raw) {
    if ((raw & 1) == 0) {
      return Math.floorDiv(raw, STANDARD_ONE) * STANDARD_ONE;
    }
    long integral = Math.floorDiv(raw & ~1L, EXTENDED_ONE);
    integral = Math.min(99_999_999_999L, Math.max(-99_999_999_999L, integral));
    return Math.abs(integral) <= 0x3fffffffL
        ? integral * STANDARD_ONE
        : integral * EXTENDED_ONE | 1;
  }

  @ParameterizedTest
  @MethodSource("extendedCases")
  void extendedFloorMatchesNegativeFractionsConversionThresholdAndSaturation(long raw) {
    Long result;
    try {
      result = RogueResonanceFunctions46.invoke(RogueResonanceFunctions46.FLOOR_KEY, raw);
    } catch (IllegalArgumentException unmatched) {
      result = null;
    }
    assertEquals(Long.valueOf(expected(raw)), result);
  }

  @Test
  void standardFloorKeepsZeroNegativeFractionAndBothSignedEndpoints() {
    for (long raw :
        new long[] {
          0,
          2,
          -2,
          STANDARD_ONE,
          -STANDARD_ONE,
          3 * STANDARD_ONE / 2,
          -3 * STANDARD_ONE / 2,
          Long.MAX_VALUE & ~1L,
          Long.MIN_VALUE
        }) {
      assertEquals(expected(raw), RogueResonanceFunctions46.floor(raw));
    }
  }

  @Test
  void mixedSignedWordsMatchIntegralOracleAndStayIdempotent() {
    var random = new Random(46074380L);
    for (int i = 0; i < 4096; i++) {
      long raw = random.nextLong();
      long actual = RogueResonanceFunctions46.floor(raw);
      assertEquals(expected(raw), actual, Long.toHexString(raw));
      assertEquals(actual, RogueResonanceFunctions46.floor(actual));
    }
  }

  @Test
  void rawEncodingDoesNotPassThroughDoubleAtLargeExtendedIntegers() {
    long raw = (85_899_345_923L << 26) | 1;
    assertEquals(raw, RogueResonanceFunctions46.floor(raw));
    assertEquals(BigInteger.valueOf(85_899_345_923L), BigInteger.valueOf(raw).shiftRight(26));
  }

  @Test
  void unknownFunctionsAndArgumentCountsAreRejected() {
    for (int key : new int[] {0, -1, 0x710946, 0x710947}) {
      assertThrows(IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, 0));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceFunctions46.invoke(RogueResonanceFunctions46.FLOOR_KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceFunctions46.invoke(RogueResonanceFunctions46.FLOOR_KEY, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceFunctions46.invoke(RogueResonanceFunctions46.FLOOR_KEY, (long[]) null));
  }
}
