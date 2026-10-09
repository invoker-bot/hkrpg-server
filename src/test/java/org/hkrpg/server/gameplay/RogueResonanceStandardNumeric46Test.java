package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RogueResonanceStandardNumeric46Test {
  private static final long ONE = 1L << 33;

  static Stream<Object[]> missingOperations() {
    return Stream.of(
        new Object[] {"000000010311", new long[] {7 * ONE, 2 * ONE}, 5 * ONE},
        new Object[] {"000000010311", new long[] {2 * ONE, 7 * ONE}, -5 * ONE},
        new Object[] {"000000010411", new long[] {3 * ONE / 2, ONE / 2}, 3 * ONE / 4},
        new Object[] {"000000010411", new long[] {-3 * ONE / 2, ONE / 2}, -3 * ONE / 4},
        new Object[] {"000000010411", new long[] {2, 2}, 0L},
        new Object[] {"000000010411", new long[] {-2, 2}, -2L},
        new Object[] {"00000e11", new long[] {ONE / 2}, -ONE / 2},
        new Object[] {"00000e11", new long[] {-ONE / 2}, ONE / 2});
  }

  @ParameterizedTest
  @MethodSource("missingOperations")
  void observedMissingNumericOperationsProduceExactRawValues(
      String code, long[] constants, long expected) {
    Long actual;
    try {
      actual =
          RogueResonanceExpression46.compile(HexFormat.of().parseHex(code), constants, new int[0])
              .evaluate(Map.of());
    } catch (IllegalArgumentException missing) {
      actual = null;
    }
    assertEquals(Long.valueOf(expected), actual);
  }

  @Test
  void all592OriginalProgramsCompileWithTheirOwnTablesWithoutGuessingContext() throws Exception {
    try (var input =
        getClass().getResourceAsStream("/fixtures/rogue-resonance-standard-numeric-4.6.json")) {
      assertNotNull(input);
      var rows =
          JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
              .getAsJsonObject()
              .getAsJsonArray("expressions");
      assertEquals(592, rows.size());
      int compiled = 0;
      for (var item : rows) {
        var row = item.getAsJsonObject();
        long[] constants = new long[row.getAsJsonArray("fixedSignedConstants").size()];
        for (int i = 0; i < constants.length; i++)
          constants[i] =
              row.getAsJsonArray("fixedSignedConstants").get(i).getAsBigDecimal().longValueExact();
        int[] keys = new int[row.getAsJsonArray("variableKeys").size()];
        for (int i = 0; i < keys.length; i++)
          keys[i] = row.getAsJsonArray("variableKeys").get(i).getAsBigDecimal().intValueExact();
        try {
          assertNotNull(
              RogueResonanceExpression46.compile(
                  HexFormat.of().parseHex(row.get("rawPostfixBytes").getAsString()),
                  constants,
                  keys));
          compiled++;
        } catch (IllegalArgumentException missingOperation) {
          // Count every source format that cannot yet compile, without synthesizing values.
        }
      }
      assertEquals(592, compiled);
    }
  }

  @Test
  void signedMultiplicationMatchesIndependentDecimalFloorQuantization() {
    var random = new Random(46068474L);
    for (int i = 0; i < 4096; i++) {
      long a = (random.nextLong() % (1L << 42)) & ~1L;
      long b = (random.nextLong() % (1L << 42)) & ~1L;
      long expected =
          BigDecimal.valueOf(a)
              .multiply(BigDecimal.valueOf(b))
              .divide(BigDecimal.valueOf(1L << 34), 0, RoundingMode.FLOOR)
              .toBigIntegerExact()
              .shiftLeft(1)
              .longValueExact();
      var program =
          RogueResonanceExpression46.compile(
              HexFormat.of().parseHex("000000010411"), new long[] {a, b}, new int[0]);
      assertEquals(expected, program.evaluate(Map.of()));
    }
  }

  @Test
  void unmatchedExtendedOperandsOrResultsAndNegatedMinValueAreRejected() {
    for (String code : new String[] {"000000010311", "000000010411"}) {
      var tagged =
          RogueResonanceExpression46.compile(
              HexFormat.of().parseHex(code), new long[] {1, ONE}, new int[0]);
      assertThrows(IllegalArgumentException.class, () -> tagged.evaluate(Map.of()));
    }
    for (Object[] input :
        new Object[][] {
          {"000000010311", new long[] {Long.MIN_VALUE, 2}},
          {"000000010411", new long[] {0x3fffffffL * ONE, 2 * ONE}},
          {"00000e11", new long[] {Long.MIN_VALUE}}
        }) {
      var program =
          RogueResonanceExpression46.compile(
              HexFormat.of().parseHex((String) input[0]), (long[]) input[1], new int[0]);
      assertThrows(IllegalArgumentException.class, () -> program.evaluate(Map.of()));
    }
  }

  @Test
  void unaryDepthAndMissingNumericVariableRemainStrict() {
    var program =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("01000e11"), new long[0], new int[] {-1});
    assertThrows(IllegalArgumentException.class, () -> program.evaluate(Map.of()));
    assertEquals(-ONE, program.evaluate(Map.of(-1, ONE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceExpression46.compile(
                HexFormat.of().parseHex("0000" + "0e".repeat(32) + "11"),
                new long[] {0},
                new int[0]));
    assertEquals(
        0,
        RogueResonanceExpression46.compile(
                HexFormat.of().parseHex("0000" + "0e".repeat(31) + "11"),
                new long[] {0},
                new int[0])
            .evaluate(Map.of()));
  }

  @Test
  void everyDistinctOriginalProgramPrefixAndTrailingByteIsRejected() throws Exception {
    try (var input =
        getClass().getResourceAsStream("/fixtures/rogue-resonance-standard-numeric-4.6.json")) {
      assertNotNull(input);
      var rows =
          JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
              .getAsJsonObject()
              .getAsJsonArray("expressions");
      var checked = new HashSet<String>();
      for (var item : rows) {
        var row = item.getAsJsonObject();
        String hex = row.get("rawPostfixBytes").getAsString();
        if (!checked.add(hex)) continue;
        long[] constants = new long[row.getAsJsonArray("fixedSignedConstants").size()];
        for (int i = 0; i < constants.length; i++)
          constants[i] =
              row.getAsJsonArray("fixedSignedConstants").get(i).getAsBigDecimal().longValueExact();
        int[] keys = new int[row.getAsJsonArray("variableKeys").size()];
        for (int i = 0; i < keys.length; i++)
          keys[i] = row.getAsJsonArray("variableKeys").get(i).getAsBigDecimal().intValueExact();
        byte[] raw = HexFormat.of().parseHex(hex);
        for (int i = 0; i < raw.length; i++) {
          byte[] prefix = java.util.Arrays.copyOf(raw, i);
          assertThrows(
              IllegalArgumentException.class,
              () -> RogueResonanceExpression46.compile(prefix, constants, keys));
        }
        byte[] trailing = java.util.Arrays.copyOf(raw, raw.length + 1);
        assertThrows(
            IllegalArgumentException.class,
            () -> RogueResonanceExpression46.compile(trailing, constants, keys));
      }
      assertTrue(checked.size() >= 32);
    }
  }
}
