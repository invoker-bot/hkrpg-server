package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;
import org.junit.jupiter.api.Test;

class RogueResonanceInteger46Test {
  private static final long ONE = 1L << 33;
  private static final int INT = -1226284721,
      MOD = 1502598494,
      LEFT = -1825696445,
      RIGHT = 965494012,
      AND = -1226285001,
      OR = 381169821,
      XOR = 1502599105;

  private static Long available(int key, long... args) {
    try {
      return RogueResonanceFunctions46.invoke(key, args);
    } catch (IllegalArgumentException closed) {
      return null;
    }
  }

  private static int nearest(long raw) {
    return new BigDecimal(BigInteger.valueOf(raw))
        .divide(BigDecimal.valueOf(ONE), 0, RoundingMode.HALF_EVEN)
        .intValueExact();
  }

  private static void result(int key, long expected, long... args) {
    assertEquals(Long.valueOf(expected), available(key, args));
  }

  @Test
  void intUsesStrictApproximationBeforeFloatFallback() {
    result(INT, ONE, ONE - 8586);
    result(INT, 0, ONE - 8588);
    result(INT, -ONE, -ONE + 8586);
    result(INT, 0, -ONE + 8588);
  }

  @Test
  void intKeepsExactLargeIntegerButQuantizesFractionalFloat32() {
    result(INT, 16777217L * ONE, 16777217L * ONE);
    result(INT, 16777218L * ONE, 16777217L * ONE + ONE / 4);
    result(INT, -16777218L * ONE, -16777217L * ONE - ONE / 4);
  }

  @Test
  void moduloPreservesSignedFractionalRemainder() {
    result(MOD, -3 * ONE / 2, -11 * ONE / 2, 2 * ONE);
    result(MOD, 3 * ONE / 2, 11 * ONE / 2, -2 * ONE);
    result(MOD, Long.MIN_VALUE % (-2), Long.MIN_VALUE, -2);
  }

  @Test
  void leftShiftRoundsValueButFloorsCountAndUses32BitShiftMask() {
    result(LEFT, 4 * ONE, 5 * ONE / 2, 7 * ONE / 4);
    result(LEFT, 3 * ONE, 3 * ONE, 32 * ONE);
  }

  @Test
  void rightShiftIsSignedAndFloorsNegativeCountBeforeMask() {
    result(RIGHT, -2 * ONE, -7 * ONE / 2, 7 * ONE / 4);
    result(RIGHT, 0, 2 * ONE, -ONE / 2);
  }

  @Test
  void andRoundsEveryArgumentIncludingSingleton() {
    result(AND, 0, 3 * ONE / 2, 15 * ONE / 2);
    result(AND, 2 * ONE, 3 * ONE / 2);
  }

  @Test
  void orAndXorFoldAllArgumentsAfterBankersRounding() {
    result(OR, 6 * ONE, 7 * ONE / 2, 3 * ONE / 2);
    result(XOR, 4 * ONE, 3 * ONE / 2, 7 * ONE / 2, 3 * ONE / 2);
  }

  @Test
  void seededModuloMatchesIndependentBigIntegerRemainder() {
    var random = new Random(460086);
    for (int i = 0; i < 4096; i++) {
      long a = random.nextLong() & ~1L, b = random.nextLong() & ~1L;
      if (b == 0) b = 2;
      assertEquals(
          BigInteger.valueOf(a).remainder(BigInteger.valueOf(b)).longValueExact(),
          RogueResonanceFunctions46.invoke(MOD, a, b));
    }
  }

  @Test
  void seededBitFoldsMatchIndependentDecimalIntegerConversion() {
    var random = new Random(460094);
    for (int i = 0; i < 4096; i++) {
      long[] args = new long[1 + random.nextInt(8)];
      for (int j = 0; j < args.length; j++) args[j] = (random.nextLong() & ~1L) >> 1 << 1;
      for (int key : new int[] {AND, OR, XOR}) {
        BigInteger value = BigInteger.valueOf(nearest(args[0]));
        for (int j = 1; j < args.length; j++) {
          var next = BigInteger.valueOf(nearest(args[j]));
          value = key == AND ? value.and(next) : key == OR ? value.or(next) : value.xor(next);
        }
        final long[] inputs = args;
        if (value.abs().compareTo(BigInteger.ONE.shiftLeft(30)) >= 0) {
          assertThrows(
              IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, inputs));
        } else
          assertEquals(
              value.shiftLeft(33).longValueExact(), RogueResonanceFunctions46.invoke(key, args));
      }
    }
  }

  @Test
  void seededShiftsMatchIndependent32BitTwosComplement() {
    var random = new Random(460090);
    var modulus = BigInteger.ONE.shiftLeft(32);
    for (int i = 0; i < 4096; i++) {
      long a = random.nextLong() & ~1L, b = random.nextLong() & ~1L;
      var n = BigInteger.valueOf(nearest(a));
      int bits =
          BigDecimal.valueOf(b)
                  .divide(BigDecimal.valueOf(ONE), 0, RoundingMode.FLOOR)
                  .intValueExact()
              & 31;
      for (int key : new int[] {LEFT, RIGHT}) {
        var value = key == LEFT ? n.shiftLeft(bits).mod(modulus) : n.shiftRight(bits);
        if (key == LEFT && value.testBit(31)) value = value.subtract(modulus);
        if (value.abs().compareTo(BigInteger.ONE.shiftLeft(30)) >= 0)
          assertThrows(
              IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, a, b));
        else
          assertEquals(
              value.shiftLeft(33).longValueExact(), RogueResonanceFunctions46.invoke(key, a, b));
      }
    }
  }

  @Test
  void seededIntMatchesIndependentBinary24BitQuantizationAndEpsilon() {
    var random = new Random(460082);
    for (int i = 0; i < 4096; i++) {
      long raw = (random.nextLong() & ~1L) / 2 & ~1L;
      var original = BigInteger.valueOf(raw);
      var rounded = BigInteger.valueOf(nearest(raw)).shiftLeft(33);
      long expected;
      if (rounded.subtract(original).abs().compareTo(BigInteger.valueOf(8588)) < 0)
        expected = rounded.longValueExact();
      else {
        var magnitude = original.abs();
        int drop = Math.max(0, magnitude.bitLength() - 24);
        if (drop > 0) {
          var divisor = BigInteger.ONE.shiftLeft(drop);
          var qr = magnitude.divideAndRemainder(divisor);
          int cmp = qr[1].shiftLeft(1).compareTo(divisor);
          magnitude =
              (cmp > 0 || cmp == 0 && qr[0].testBit(0) ? qr[0].add(BigInteger.ONE) : qr[0])
                  .shiftLeft(drop);
        }
        var integer = magnitude.divide(BigInteger.valueOf(ONE));
        if (raw < 0) integer = integer.negate();
        expected = integer.shiftLeft(33).longValueExact();
      }
      assertEquals(expected, RogueResonanceFunctions46.invoke(INT, raw));
    }
  }

  @Test
  void intPreservesNegativeStandardEndpointOnlyInApproximateReturn() {
    result(INT, Long.MIN_VALUE, Long.MIN_VALUE);
    result(INT, Long.MIN_VALUE, Long.MIN_VALUE + 8586);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceFunctions46.invoke(INT, Long.MIN_VALUE + 8588));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceFunctions46.invoke(INT, Long.MAX_VALUE - 1));
  }

  @Test
  void extendedBitResultsAreRejectedEvenWhenJavaIntWouldWrap() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceFunctions46.invoke(LEFT, ONE, 30 * ONE));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceFunctions46.invoke(LEFT, ONE, 31 * ONE));
    result(LEFT, 0, 2 * ONE, 31 * ONE);
    for (int key : new int[] {AND, OR, XOR})
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueResonanceFunctions46.invoke(key, Long.MIN_VALUE));
  }

  @Test
  void shiftAndBitInputsRetainMinimumStandardIntegerBeforeResultEncoding() {
    result(RIGHT, -ONE, Long.MIN_VALUE, 30 * ONE);
    result(AND, 0, Long.MIN_VALUE, 0);
    result(XOR, 0, Long.MIN_VALUE, Long.MIN_VALUE);
  }

  @Test
  void moduloZeroAndExtendedInputsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(MOD, 0, 0));
    for (int key : new int[] {INT, MOD, LEFT, RIGHT, AND, OR, XOR}) {
      if (key == INT)
        assertThrows(
            IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, 1));
      else {
        assertThrows(
            IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, 1, ONE));
        assertThrows(
            IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, ONE, 1));
      }
    }
  }

  @Test
  void finiteArgumentCountsRejectIgnoredNativeExtrasAndOversize() {
    for (int key : new int[] {INT, MOD, LEFT, RIGHT, AND, OR, XOR}) {
      assertThrows(IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key));
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueResonanceFunctions46.invoke(key, (long[]) null));
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueResonanceFunctions46.invoke(key, new long[256]));
    }
    assertThrows(IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(INT, 0, 0));
    for (int key : new int[] {MOD, LEFT, RIGHT}) {
      assertThrows(
          IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, ONE));
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueResonanceFunctions46.invoke(key, ONE, ONE, ONE));
    }
  }

  @Test
  void bitFoldsReadLastOf255AndPreserveInputArray() {
    long[] values = new long[255];
    java.util.Arrays.fill(values, 7 * ONE);
    values[254] = 4 * ONE;
    long[] copy = values.clone();
    result(AND, 4 * ONE, values);
    assertArrayEquals(copy, values);
    java.util.Arrays.fill(values, 0);
    values[254] = ONE;
    result(OR, ONE, values);
    result(XOR, ONE, values);
  }

  @Test
  void postfixIntegerFunctionsDoNotReadCalleeAndPreserveNumericReferenceOrder() {
    var program =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("0100010101020103100311"),
            new long[0],
            new int[] {XOR, 101, 102, 103});
    var reads = new ArrayList<Integer>();
    assertEquals(
        0,
        program.evaluateWithResolver(
            k -> {
              reads.add(k);
              return reads.size() * ONE;
            }));
    assertEquals(List.of(101, 102, 103), reads);
  }

  @Test
  void postfixIntAndShiftCanNestThroughActualRegisteredKeys() {
    var program =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("01000101000010010001100211"),
            new long[] {16777217L * ONE + ONE / 4, 0},
            new int[] {LEFT, INT});
    assertEquals(16777218L * ONE, program.evaluate(Map.of()));
  }

  @Test
  void allFifteenRegisteredKeysRemainFunctionsWhenUsedAsNumericGlobal() {
    for (int key :
        new int[] {
          INT,
          MOD,
          LEFT,
          RIGHT,
          AND,
          OR,
          XOR,
          339799074,
          696029444,
          -1355705909,
          1776456860,
          1554761690,
          -514975797,
          1099313850,
          368963114
        }) {
      var program =
          RogueResonanceExpression46.compile(new byte[] {1, 0, 17}, new long[0], new int[] {key});
      assertThrows(
          IllegalArgumentException.class,
          () ->
              program.evaluateWithResolver(
                  k -> {
                    fail("callee as numeric");
                    return 0;
                  }));
    }
  }

  @Test
  void malformedUnknownCalleeAndWrongUnaryArityRejectBeforeEvaluation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceExpression46.compile(
                HexFormat.of().parseHex("01000000100111"), new long[] {0}, new int[] {0x710946}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceExpression46.compile(
                HexFormat.of().parseHex("010000000001100211"), new long[] {0, 0}, new int[] {INT}));
  }

  @Test
  void laterNumericFailureStopsBitFoldArgumentReads() {
    var program =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("0100010101020103100311"),
            new long[0],
            new int[] {AND, 101, 102, 103});
    var reads = new ArrayList<Integer>();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            program.evaluateWithResolver(
                k -> {
                  reads.add(k);
                  return k == 102 ? 1 : ONE;
                }));
    assertEquals(List.of(101, 102), reads);
  }

  @Test
  void positiveEndpointCanBeRoundedBeforeBitOperationReturnsStandardResult() {
    result(RIGHT, ONE, Long.MAX_VALUE - 1, 30 * ONE);
    result(AND, 0, Long.MAX_VALUE - 1, 0);
    result(XOR, 0, Long.MAX_VALUE - 1, Long.MAX_VALUE - 1);
  }

  @Test
  void unknownFunctionsNeverBecomeZero() {
    for (int key : new int[] {0, -1, 0x710946})
      assertThrows(
          IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, ONE, ONE));
  }
}
