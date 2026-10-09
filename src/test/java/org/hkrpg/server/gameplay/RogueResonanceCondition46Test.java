package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RogueResonanceCondition46Test {
  private static final long ONE = 1L << 33;

  private static Long available(String hex, long... constants) {
    try {
      return RogueResonanceExpression46.compile(HexFormat.of().parseHex(hex), constants, new int[0])
          .evaluate(Map.of());
    } catch (IllegalArgumentException closed) {
      return null;
    }
  }

  @Test
  void moduloOpcodeRetainsSignedRawRemainder() {
    assertEquals(Long.valueOf(-ONE / 2), available("000000010611", -3 * ONE / 2, ONE));
  }

  @Test
  void equalityOpcodeUsesStrictNativeApproximation() {
    assertEquals(Long.valueOf(ONE), available("000000010711", 0, 8586));
  }

  @Test
  void inequalityOpcodeIsComplementOfApproximation() {
    assertEquals(Long.valueOf(ONE), available("000000010811", 0, 8588));
  }

  @Test
  void lessOpcodeExcludesNearlyEqualValues() {
    assertEquals(Long.valueOf(0), available("000000010911", 0, 8586));
  }

  @Test
  void greaterOpcodeReturnsEncodedOneForTrue() {
    assertEquals(Long.valueOf(ONE), available("000000010a11", ONE, 0));
  }

  @Test
  void lessOrEqualOpcodeAcceptsNearlyEqualLargerLeft() {
    assertEquals(Long.valueOf(ONE), available("000000010b11", 8586, 0));
  }

  @Test
  void greaterOrEqualOpcodeAcceptsNearlyEqualSmallerLeft() {
    assertEquals(Long.valueOf(ONE), available("000000010c11", 0, 8586));
  }

  @Test
  void ternaryOpcodeUsesConditionThenTrueThenFalseOrder() {
    assertEquals(Long.valueOf(2 * ONE), available("0000000100020d11", ONE, 2 * ONE, 3 * ONE));
  }

  @Test
  void notOpcodeTestsAlmostZeroInsteadOfExactZero() {
    assertEquals(Long.valueOf(ONE), available("00000f11", 8586));
  }

  private static RogueResonanceExpression46.Program program(
      String hex, long[] constants, int... keys) {
    return RogueResonanceExpression46.compile(HexFormat.of().parseHex(hex), constants, keys);
  }

  @Test
  void seededComparisonsMatchIndependentBigIntegerDistanceAndSignedOrder() {
    var random = new Random(460012);
    for (int sample = 0; sample < 4096; sample++) {
      long a = random.nextLong() & ~1L, b = random.nextLong() & ~1L;
      if (sample % 2 == 0) {
        try {
          b = Math.addExact(a, 2L * (random.nextInt(9000) - 4500));
        } catch (ArithmeticException overflow) {
          b = a;
        }
      }
      var x = BigInteger.valueOf(a);
      var y = BigInteger.valueOf(b);
      boolean near = x.subtract(y).abs().compareTo(BigInteger.valueOf(8588)) < 0;
      int compare = x.compareTo(y);
      boolean[] expected = {
        near,
        !near,
        !near && compare < 0,
        !near && compare > 0,
        compare < 0 || near,
        compare > 0 || near
      };
      for (int op = 7; op <= 12; op++) {
        assertEquals(
            expected[op - 7] ? ONE : 0,
            program("00000001" + String.format("%02x", op) + "11", new long[] {a, b})
                .evaluate(Map.of()));
      }
    }
  }

  @Test
  void truthWindowIsStrictSymmetricAndSafeAtSignedEndpoints() {
    for (long value :
        new long[] {Long.MIN_VALUE, -8588, -8586, 0, 8586, 8588, Long.MAX_VALUE - 1}) {
      boolean small = BigInteger.valueOf(value).abs().compareTo(BigInteger.valueOf(8588)) < 0;
      assertEquals(small ? ONE : 0, program("00000f11", new long[] {value}).evaluate(Map.of()));
      assertEquals(
          small ? 3 * ONE : 2 * ONE,
          program("0000000100020d11", new long[] {value, 2 * ONE, 3 * ONE}).evaluate(Map.of()));
    }
  }

  @Test
  void ternaryReadsConditionThenBothBranchesExactlyOnceInSourceOrder() {
    var reads = new ArrayList<Integer>();
    var p = program("0100010101020d11", new long[0], 501, 502, 503);
    assertEquals(
        2 * ONE,
        p.evaluateWithResolver(
            key -> {
              reads.add(key);
              return reads.size() * ONE;
            }));
    assertEquals(List.of(501, 502, 503), reads);
  }

  @Test
  void eagerUnselectedBranchDivisionByZeroIsStillAnError() {
    var p = program("0000000100020003050d11", new long[] {ONE, 2 * ONE, ONE, 0});
    assertThrows(IllegalArgumentException.class, () -> p.evaluate(Map.of()));
  }

  @Test
  void branchFailureStopsLaterReadsEvenWhenConditionWouldSelectAnotherBranch() {
    var reads = new ArrayList<Integer>();
    var p = program("0100010101020d11", new long[0], 501, 502, 503);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            p.evaluateWithResolver(
                key -> {
                  reads.add(key);
                  return key == 502 ? 1 : 0;
                }));
    assertEquals(List.of(501, 502), reads);
  }

  @Test
  void comparisonResultsAreStandardFixpointOneInFurtherArithmetic() {
    assertEquals(2 * ONE, program("000000000700010211", new long[] {0, ONE}).evaluate(Map.of()));
    assertEquals(ONE, program("00000001070f11", new long[] {0, ONE}).evaluate(Map.of()));
  }

  @Test
  void notResultCanSelectTernaryAndNestedTernariesKeepBranchOrder() {
    assertEquals(
        2 * ONE,
        program("00000f000100020d11", new long[] {0, 2 * ONE, 3 * ONE}).evaluate(Map.of()));
    assertEquals(
        4 * ONE,
        program("000000010002000300040d0d11", new long[] {0, 2 * ONE, 0, 3 * ONE, 4 * ONE})
            .evaluate(Map.of()));
  }

  @Test
  void comparisonDistanceAcrossSignedOverflowNeverBecomesNearZero() {
    for (int op = 7; op <= 12; op++) {
      boolean expected = op == 8 || op == 9 || op == 11;
      assertEquals(
          expected ? ONE : 0,
          program(
                  "00000001" + String.format("%02x", op) + "11",
                  new long[] {Long.MIN_VALUE, Long.MAX_VALUE - 1})
              .evaluate(Map.of()));
      boolean equal = op == 7 || op == 11 || op == 12;
      assertEquals(
          equal ? ONE : 0,
          program(
                  "00000001" + String.format("%02x", op) + "11",
                  new long[] {Long.MIN_VALUE, Long.MIN_VALUE})
              .evaluate(Map.of()));
    }
  }

  @Test
  void nearValuesPermitBothInclusiveOrdersButNeitherStrictOrder() {
    for (int op : new int[] {9, 10, 11, 12})
      assertEquals(
          op >= 11 ? ONE : 0,
          program("00000001" + String.format("%02x", op) + "11", new long[] {-8586, 0})
              .evaluate(Map.of()));
  }

  @Test
  void moduloZeroAndOddInputsRemainRejectedByEveryNewOperation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> program("000000010611", new long[] {ONE, 0}).evaluate(Map.of()));
    for (int op = 6; op <= 12; op++) {
      var p = program("00000001" + String.format("%02x", op) + "11", new long[] {1, ONE});
      assertThrows(IllegalArgumentException.class, () -> p.evaluate(Map.of()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> program("00000f11", new long[] {1}).evaluate(Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> program("0000000100020d11", new long[] {ONE, ONE, 1}).evaluate(Map.of()));
  }

  @Test
  void malformedConditionStacksAndUnknownOpcodesRejectBeforeResolver() {
    for (String hex :
        new String[] {
          "0d11",
          "00000d11",
          "000000010d11",
          "0f11",
          "00001211",
          "0000ff11",
          "000000010d",
          "00000f1100"
        }) assertThrows(IllegalArgumentException.class, () -> program(hex, new long[] {0, ONE}));
  }

  @Test
  void newBranchProgramCopiesEveryInputTable() {
    byte[] code = HexFormat.of().parseHex("0100000000010d11");
    long[] constants = {2 * ONE, 3 * ONE};
    int[] keys = {501};
    var p = RogueResonanceExpression46.compile(code, constants, keys);
    java.util.Arrays.fill(code, (byte) 0);
    java.util.Arrays.fill(constants, 0);
    keys[0] = 0;
    assertEquals(2 * ONE, p.evaluate(Map.of(501, ONE)));
  }

  @Test
  void logicalNestingRetainsFiniteDepthLimit() {
    for (int count : new int[] {31, 32}) {
      String code = "0000" + "0f".repeat(count) + "11";
      if (count == 31) assertEquals(ONE, program(code, new long[] {0}).evaluate(Map.of()));
      else assertThrows(IllegalArgumentException.class, () -> program(code, new long[] {0}));
    }
  }
}
