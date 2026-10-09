package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RogueResonanceMulti46Test {
  private static final long ONE = 1L << 33;
  private static final int MIN = 339799074;
  private static final int MAX = 696029444;
  private static final int CLAMP = -1355705909;

  private static Long invoke(int key, long... values) {
    try {
      return RogueResonanceFunctions46.invoke(key, values);
    } catch (IllegalArgumentException unmatched) {
      return null;
    }
  }

  @Test
  void minimumUsesFirstArgumentThenAllLaterArgumentsIncludingSingleton() {
    assertEquals(Long.valueOf(ONE), invoke(MIN, ONE));
    assertEquals(Long.valueOf(-ONE), invoke(MIN, ONE, 2 * ONE, -ONE));
    assertEquals(Long.valueOf(Long.MIN_VALUE), invoke(MIN, 0, Long.MIN_VALUE, Long.MAX_VALUE - 1));
  }

  @Test
  void maximumPreservesSignedStandardEndpointsAndLaterMaximum() {
    assertEquals(
        Long.valueOf(Long.MAX_VALUE - 1), invoke(MAX, Long.MIN_VALUE, -ONE, Long.MAX_VALUE - 1));
    assertEquals(Long.valueOf(-ONE), invoke(MAX, -ONE));
  }

  @Test
  void clampKeepsNativeLowerFirstOrderForInvertedBounds() {
    assertEquals(Long.valueOf(5 * ONE), invoke(CLAMP, 4 * ONE, 5 * ONE, 3 * ONE));
    assertEquals(Long.valueOf(3 * ONE), invoke(CLAMP, 6 * ONE, 5 * ONE, 3 * ONE));
    assertEquals(Long.valueOf(ONE / 2), invoke(CLAMP, ONE / 2, -ONE, ONE));
  }

  @Test
  void postfixMinimumAcceptsTwoNumericArgumentsInOriginalOrder() {
    Long actual;
    try {
      actual =
          RogueResonanceExpression46.compile(
                  HexFormat.of().parseHex("010000000001100211"),
                  new long[] {ONE, -ONE},
                  new int[] {MIN})
              .evaluate(Map.of());
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(-ONE), actual);
  }

  @Test
  void postfixClampDoesNotReverseLowerAndUpperWhenPoppingArguments() {
    Long actual;
    try {
      actual =
          RogueResonanceExpression46.compile(
                  HexFormat.of().parseHex("0100000000010002100311"),
                  new long[] {4 * ONE, 5 * ONE, 3 * ONE},
                  new int[] {CLAMP})
              .evaluate(Map.of());
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(5 * ONE), actual);
  }

  @Test
  void variadicCallerEvaluatesReferencesLeftToRightWithoutReadingFunctionKey() {
    var reads = new ArrayList<Integer>();
    Long actual;
    try {
      var program =
          RogueResonanceExpression46.compile(
              HexFormat.of().parseHex("0100010101020101100311"),
              new long[0],
              new int[] {MIN, 101, 102});
      actual =
          program.evaluateWithResolver(
              key -> {
                reads.add(key);
                return reads.size() * ONE;
              });
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(ONE), actual);
    assertEquals(List.of(101, 102, 101), reads);
  }

  @Test
  void seededExtremaMatchIndependentBigIntegerOrdering() {
    var random = new Random(460083);
    for (int sample = 0; sample < 4096; sample++) {
      long[] values = new long[1 + random.nextInt(32)];
      BigInteger lo = null, hi = null;
      for (int i = 0; i < values.length; i++) {
        values[i] = random.nextLong() & ~1L;
        var n = BigInteger.valueOf(values[i]);
        lo = lo == null ? n : lo.min(n);
        hi = hi == null ? n : hi.max(n);
      }
      assertEquals(lo.longValueExact(), RogueResonanceFunctions46.invoke(MIN, values));
      assertEquals(hi.longValueExact(), RogueResonanceFunctions46.invoke(MAX, values));
    }
  }

  @Test
  void variadicLimitIncludesLastOf255ArgumentsAndRejectsEmptyOrOversize() {
    long[] values = new long[255];
    values[254] = -ONE;
    assertEquals(-ONE, RogueResonanceFunctions46.invoke(MIN, values));
    values[254] = ONE;
    assertEquals(ONE, RogueResonanceFunctions46.invoke(MAX, values));
    for (int key : new int[] {MIN, MAX}) {
      assertThrows(IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key));
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueResonanceFunctions46.invoke(key, new long[256]));
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueResonanceFunctions46.invoke(key, (long[]) null));
    }
  }

  @Test
  void oddFirstAndLaterArgumentsDoNotBecomeStandardComparisons() {
    for (int key : new int[] {MIN, MAX}) {
      for (long[] values : new long[][] {{1, 0}, {0, 1}, {0, ONE, -1}}) {
        assertThrows(
            IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(key, values));
      }
    }
    for (long[] values : new long[][] {{1, 0, ONE}, {0, 1, ONE}, {0, 0, 1}}) {
      assertThrows(
          IllegalArgumentException.class, () -> RogueResonanceFunctions46.invoke(CLAMP, values));
    }
  }

  @Test
  void clampPreservesEndpointsEqualsAndLowerFirstInvertedRange() {
    for (long value : new long[] {Long.MIN_VALUE, -ONE, 0, ONE, Long.MAX_VALUE - 1}) {
      assertEquals(
          value,
          RogueResonanceFunctions46.invoke(CLAMP, value, Long.MIN_VALUE, Long.MAX_VALUE - 1));
      assertEquals(ONE, RogueResonanceFunctions46.invoke(CLAMP, value, ONE, ONE));
      assertEquals(
          value < ONE ? ONE : -ONE, RogueResonanceFunctions46.invoke(CLAMP, value, ONE, -ONE));
    }
  }

  @Test
  void clampFiniteContractRequiresExactlyThreeArguments() {
    for (int size : new int[] {0, 1, 2, 4, 255, 256}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueResonanceFunctions46.invoke(CLAMP, new long[size]));
    }
  }

  @Test
  void nestedMinMaxClampAndUnaryCallsKeepOriginalArgumentOrder() {
    // clamp(max(-2, min(3, 1)), -1, ceil(0.5)) = 1.
    var program =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("010001010000010200010002100210020003010300041001100311"),
            new long[] {-2 * ONE, 3 * ONE, ONE, -ONE, ONE / 2},
            new int[] {CLAMP, MAX, MIN, RogueResonanceFunctions46.CEIL_KEY});
    assertEquals(
        ONE,
        program.evaluateWithResolver(
            key -> {
              throw new AssertionError("callee read " + key);
            }));
  }

  @Test
  void malformedCallsRejectBeforeAnyResolverIsNeeded() {
    for (String hex :
        new String[] {
          "0100100011",
          "0100000010ff11",
          "01000000100211",
          "0100000010",
          "00000000100111",
          "010000000001100211",
          "0100000000010002100411"
        }) {
      // CLAMP has no single/two/four argument form, nor a constant callee.
      assertThrows(
          IllegalArgumentException.class,
          () ->
              RogueResonanceExpression46.compile(
                  HexFormat.of().parseHex(hex), new long[] {0, ONE, 2 * ONE}, new int[] {CLAMP}),
          hex);
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceExpression46.compile(
                HexFormat.of().parseHex("01000000100111"), new long[] {0}, new int[] {1502598494}));
  }

  @Test
  void everyMatchedFunctionKeyIsRejectedAsNumericGlobal() {
    for (int key :
        new int[] {
          MIN,
          MAX,
          CLAMP,
          RogueResonanceFunctions46.FLOOR_KEY,
          RogueResonanceFunctions46.ROUND_KEY,
          RogueResonanceFunctions46.CEIL_KEY,
          RogueResonanceFunctions46.ABS_KEY,
          RogueResonanceFunctions46.CLAMP01_KEY
        }) {
      var program =
          RogueResonanceExpression46.compile(new byte[] {1, 0, 17}, new long[0], new int[] {key});
      assertThrows(
          IllegalArgumentException.class,
          () ->
              program.evaluateWithResolver(
                  k -> {
                    fail("function key reached numeric resolver");
                    return 0;
                  }));
    }
  }

  @Test
  void numericArgumentFailureStopsLaterReferences() {
    var program =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("0100010101020103100311"),
            new long[0],
            new int[] {MAX, 101, 102, 103});
    var reads = new ArrayList<Integer>();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            program.evaluateWithResolver(
                key -> {
                  reads.add(key);
                  return key == 102 ? 1 : ONE;
                }));
    assertEquals(List.of(101, 102), reads);
  }

  @Test
  void compileCopiesTablesAndProgramBeforeLaterMutation() {
    byte[] code = HexFormat.of().parseHex("010000000001100211");
    long[] constants = {ONE, -ONE};
    int[] keys = {MIN};
    var program = RogueResonanceExpression46.compile(code, constants, keys);
    java.util.Arrays.fill(code, (byte) 0);
    java.util.Arrays.fill(constants, 0);
    keys[0] = MAX;
    assertEquals(-ONE, program.evaluate(Map.of()));
  }

  @Test
  void callerRetainsStackLimitWhileStandaloneAccepts255() {
    for (int count : new int[] {63, 64}) {
      var code = new ByteArrayOutputStream();
      code.write(1);
      code.write(0);
      for (int i = 0; i < count; i++) {
        code.write(0);
        code.write(0);
      }
      code.write(16);
      code.write(count);
      code.write(17);
      if (count == 63) {
        assertEquals(
            ONE,
            RogueResonanceExpression46.compile(
                    code.toByteArray(), new long[] {ONE}, new int[] {MIN})
                .evaluate(Map.of()));
      } else {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RogueResonanceExpression46.compile(
                    code.toByteArray(), new long[] {ONE}, new int[] {MIN}));
      }
    }
  }

  @Test
  void nestedCallerRetainsDepthLimit() {
    for (int calls : new int[] {31, 32}) {
      var code = new ByteArrayOutputStream();
      for (int i = 0; i < calls; i++) {
        code.write(1);
        code.write(0);
      }
      code.write(0);
      code.write(0);
      for (int i = 0; i < calls; i++) {
        code.write(16);
        code.write(1);
      }
      code.write(17);
      if (calls == 31) {
        assertEquals(
            ONE,
            RogueResonanceExpression46.compile(
                    code.toByteArray(), new long[] {ONE}, new int[] {MIN})
                .evaluate(Map.of()));
      } else {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RogueResonanceExpression46.compile(
                    code.toByteArray(), new long[] {ONE}, new int[] {MIN}));
      }
    }
  }
}
