package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RogueResonanceRound46Test {
  private static final int ROUND_KEY = 1554761690;
  private static final long ONE = 1L << 33;
  private static final long HALF = ONE / 2;

  static Stream<Arguments> nativeRoundingCases() {
    return Stream.of(
        Arguments.of(0L, 0L),
        Arguments.of(2L, 0L),
        Arguments.of(-2L, 0L),
        Arguments.of(HALF, 0L),
        Arguments.of(-HALF, 0L),
        Arguments.of(ONE + HALF, 2 * ONE),
        Arguments.of(-ONE - HALF, -2 * ONE),
        Arguments.of(2 * ONE + HALF, 2 * ONE),
        Arguments.of(-2 * ONE - HALF, -2 * ONE),
        Arguments.of(3 * ONE + HALF, 4 * ONE),
        Arguments.of(-3 * ONE - HALF, -4 * ONE),
        Arguments.of(HALF - 2, 0L),
        Arguments.of(HALF + 2, ONE),
        Arguments.of(-HALF - 2, -ONE),
        Arguments.of(-HALF + 2, 0L),
        Arguments.of(ONE, ONE),
        Arguments.of(-ONE, -ONE));
  }

  @ParameterizedTest
  @MethodSource("nativeRoundingCases")
  void registeredRoundRetainsSignedFractionsAndEvenMidpoints(long raw, long expected) {
    Long actual;
    try {
      actual = RogueResonanceFunctions46.invoke(ROUND_KEY, raw);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(expected), actual);
    assertEquals((int) (expected >> 33), RogueResonanceFunctions46.roundToInt(raw));
  }

  @Test
  void roundCallCompilesAndEvaluatesExplicitNumericInput() {
    Long actual;
    try {
      var program =
          RogueResonanceExpression46.compile(
              HexFormat.of().parseHex("01000101100111"), new long[0], new int[] {ROUND_KEY, 42});
      actual = program.evaluate(Map.of(42, ONE + HALF));
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(2 * ONE), actual);
  }

  @Test
  void standardRandomWordsAgreeWithIndependentDecimalHalfEvenOracle() {
    var random = new Random(46074381L);
    for (int i = 0; i < 4096; i++) {
      long raw = (random.nextLong() % (1L << 55)) & ~1L;
      long expected =
          BigDecimal.valueOf(raw)
                  .divide(BigDecimal.valueOf(ONE))
                  .setScale(0, RoundingMode.HALF_EVEN)
                  .longValueExact()
              * ONE;
      assertEquals(expected, RogueResonanceFunctions46.invoke(ROUND_KEY, raw));
    }
  }

  @Test
  void unmatchedExtendedEncodingRangesAndArgumentCountsRemainClosed() {
    for (long raw :
        new long[] {
          1,
          -1,
          Long.MIN_VALUE,
          Long.MAX_VALUE & ~1L,
          0x40000000L * ONE,
          -0x40000000L * ONE,
          0x3fffffffL * ONE + HALF
        }) {
      assertThrows(
          IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(ROUND_KEY, raw));
    }
    assertThrows(IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(ROUND_KEY));
    assertThrows(
        IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(ROUND_KEY, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceFunctions46.invoke(ROUND_KEY, (long[]) null));
    for (int key : new int[] {0, -1, 0x710946, 0x710947}) {
      assertThrows(IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, 0));
    }
    assertEquals(0x3fffffffL * ONE, RogueResonanceFunctions46.invoke(ROUND_KEY, 0x3fffffffL * ONE));
    assertEquals(
        -0x3fffffffL * ONE, RogueResonanceFunctions46.invoke(ROUND_KEY, -0x3fffffffL * ONE));
  }

  @Test
  void functionHashCannotBeUsedAsNumericVariableAndInvalidCallsDoNotCompile() {
    var value =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("010011"), new long[0], new int[] {ROUND_KEY});
    assertThrows(IllegalArgumentException.class, () -> value.evaluate(Map.of(ROUND_KEY, ONE)));
    for (String code : new String[] {"01000000100011", "01000000100211", "00000000100111"}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              RogueResonanceExpression46.compile(
                  HexFormat.of().parseHex(code), new long[] {0}, new int[] {ROUND_KEY}));
    }
  }
}
