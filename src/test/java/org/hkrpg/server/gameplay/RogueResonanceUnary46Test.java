package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RogueResonanceUnary46Test {
  private static final long ONE = 1L << 33;
  private static final int CEIL = -514975797;
  private static final int ABS = 1099313850;
  private static final int CLAMP01 = 368963114;

  private static Long invoke(int key, long raw) {
    try {
      return RogueResonanceFunctions46.invoke(key, raw);
    } catch (IllegalArgumentException unmatched) {
      return null;
    }
  }

  @Test
  void ceilingUsesSignedFractionsAndKeepsExactIntegers() {
    for (long[] pair :
        new long[][] {
          {2, ONE},
          {-2, 0},
          {3 * ONE / 2, 2 * ONE},
          {-3 * ONE / 2, -ONE},
          {ONE, ONE},
          {Long.MIN_VALUE, Long.MIN_VALUE}
        }) assertEquals(Long.valueOf(pair[1]), invoke(CEIL, pair[0]));
  }

  @Test
  void absoluteValuePreservesNativeNegationOverflowAtSignedMinimum() {
    for (long[] pair :
        new long[][] {
          {-ONE / 2, ONE / 2},
          {ONE, ONE},
          {0, 0},
          {Long.MIN_VALUE, Long.MIN_VALUE},
          {Long.MIN_VALUE + 2, Long.MAX_VALUE - 1}
        }) assertEquals(Long.valueOf(pair[1]), invoke(ABS, pair[0]));
  }

  @Test
  void clampZeroOneUsesEncodedConstantsAndKeepsInteriorRawWords() {
    for (long[] pair :
        new long[][] {
          {-2, 0},
          {0, 0},
          {2, 2},
          {ONE / 2, ONE / 2},
          {ONE, ONE},
          {ONE + 2, ONE},
          {Long.MIN_VALUE, 0},
          {Long.MAX_VALUE - 1, ONE}
        }) assertEquals(Long.valueOf(pair[1]), invoke(CLAMP01, pair[0]));
  }

  @Test
  void postfixCallerUsesRegisteredCeilingWithoutResolvingFunctionKeyAsVariable() {
    Long actual;
    try {
      var expression =
          RogueResonanceExpression46.compile(
              HexFormat.of().parseHex("01000000100111"),
              new long[] {-3 * ONE / 2},
              new int[] {CEIL});
      actual = expression.evaluate(Map.of());
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(-ONE), actual);
  }

  @Test
  void seededSignedStandardWordsMatchIndependentIntegralOracles() {
    var random = new java.util.Random(46074385L);
    var unit = java.math.BigInteger.valueOf(ONE);
    for (int i = 0; i < 4096; i++) {
      long raw = random.nextLong() & ~1L;
      var parts = java.math.BigInteger.valueOf(raw).divideAndRemainder(unit);
      var integral = parts[0];
      if (raw > 0 && parts[1].signum() != 0) integral = integral.add(java.math.BigInteger.ONE);
      var expected = integral.multiply(unit);
      if (expected.bitLength() > 63) assertNull(invoke(CEIL, raw));
      else assertEquals(Long.valueOf(expected.longValueExact()), invoke(CEIL, raw));
      var magnitude = java.math.BigInteger.valueOf(raw).abs();
      long expectedAbs = raw == Long.MIN_VALUE ? Long.MIN_VALUE : magnitude.longValueExact();
      assertEquals(Long.valueOf(expectedAbs), invoke(ABS, raw));
    }
  }

  @Test
  void supportedUnaryResultsAreIdempotentAcrossSignsAndUnitBoundaries() {
    for (int key : new int[] {CEIL, ABS, CLAMP01})
      for (long raw :
          new long[] {0, 2, -2, ONE - 2, ONE, ONE + 2, -ONE, -ONE - 2, Long.MIN_VALUE}) {
        long value = RogueResonanceFunctions46.invoke(key, raw);
        assertEquals(value, RogueResonanceFunctions46.invoke(key, value));
      }
  }

  @Test
  void ceilingRejectsOnlyUnmatchedExtendedResultAtPositiveStandardEndpoint() {
    long lastInteger = 0x7ffffffe00000000L;
    assertEquals(lastInteger, RogueResonanceFunctions46.invoke(CEIL, lastInteger));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceFunctions46.invoke(CEIL, lastInteger + 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceFunctions46.invoke(CEIL, Long.MAX_VALUE - 1));
    assertEquals(lastInteger, RogueResonanceFunctions46.invoke(CEIL, lastInteger - 2));
    assertEquals(Long.MIN_VALUE + ONE, RogueResonanceFunctions46.invoke(CEIL, Long.MIN_VALUE + 2));
  }

  @Test
  void newlyMatchedCallbacksKeepExtendedInputsClosed() {
    for (int key : new int[] {CEIL, ABS, CLAMP01})
      for (long raw : new long[] {1, -1, (1L << 26) | 1, Long.MAX_VALUE, Long.MIN_VALUE | 1})
        assertThrows(
            IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, raw));
  }

  @Test
  void allFiveCallbacksRequireOneArgumentAndOtherFunctionsStayClosed() {
    for (int key :
        new int[] {
          CEIL,
          ABS,
          CLAMP01,
          RogueResonanceFunctions46.FLOOR_KEY,
          RogueResonanceFunctions46.ROUND_KEY
        }) {
      assertThrows(IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key));
      assertThrows(
          IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, 0, 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueResonanceFunctions46.invoke(key, (long[]) null));
    }
    for (int key : new int[] {0, -1, 0x710946, 0x710947, 0x710948})
      assertThrows(IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, 0));
  }

  @Test
  void nestedPostfixCallbacksNeverReadTheirKeysFromNumericResolver() {
    var program =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("010001010102000010011001100111"),
            new long[] {-3 * ONE / 2},
            new int[] {CLAMP01, ABS, CEIL});
    assertEquals(
        ONE,
        program.evaluateWithResolver(
            key -> {
              throw new AssertionError("function lookup");
            }));
    assertEquals(ONE, program.evaluate(Map.of()));
  }

  @Test
  void registeredUnaryKeysCannotBeReadAsPlainNumericVariables() {
    for (int key : new int[] {CEIL, ABS, CLAMP01}) {
      var program =
          RogueResonanceExpression46.compile(
              HexFormat.of().parseHex("010011"), new long[0], new int[] {key});
      assertThrows(IllegalArgumentException.class, () -> program.evaluate(Map.of(key, ONE)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              program.evaluateWithResolver(
                  ignored -> {
                    throw new AssertionError("numeric lookup");
                  }));
    }
  }

  @Test
  void unaryPostfixCallerResolvesOnlyItsNumericArgumentOnce() {
    var calls = new java.util.ArrayList<Integer>();
    var program =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("01000101100111"), new long[0], new int[] {CEIL, 1234});
    assertEquals(
        -ONE,
        program.evaluateWithResolver(
            key -> {
              calls.add(key);
              return -3 * ONE / 2;
            }));
    assertEquals(java.util.List.of(1234), calls);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceExpression46.compile(
                HexFormat.of().parseHex("01000000100211"), new long[] {0}, new int[] {CEIL}));
  }
}
