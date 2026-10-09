package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RogueResonanceExpression46Test {
  private static final long ONE = 1L << 33;

  private static RogueResonanceExpression46.Program expression(
      String code, long[] constants, int... keys) {
    return RogueResonanceExpression46.compile(HexFormat.of().parseHex(code), constants, keys);
  }

  static Stream<long[]> roundingCases() {
    return Stream.of(
        new long[] {2 * ONE, 3 * ONE, 5726623062L},
        new long[] {-2 * ONE, 3 * ONE, -5726623062L},
        new long[] {3 * ONE, 5 * ONE, 5153960756L},
        new long[] {-3 * ONE, 5 * ONE, -5153960756L},
        new long[] {2, 2 * ONE, 2},
        new long[] {-2, 2 * ONE, -2});
  }

  @ParameterizedTest
  @MethodSource("roundingCases")
  void divisionRoundsMagnitudeHalfUpAtTheNativeQ32Quantum(long[] values) {
    var program = expression("000000010511", new long[] {values[0], values[1]});
    assertEquals(values[2], program.evaluate(Map.of()));
  }

  @Test
  void bothOriginalProgramsComputeWithExplicitSyntheticNumericInputs() throws Exception {
    try (var input =
        getClass().getResourceAsStream("/fixtures/rogue-resonance-floor-programs-4.6.json")) {
      assertNotNull(input);
      var rows =
          JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
              .getAsJsonObject()
              .getAsJsonArray("expressions");
      assertEquals(2, rows.size());
      for (int i = 0; i < rows.size(); i++) {
        var row = rows.get(i).getAsJsonObject();
        long[] constants = new long[row.getAsJsonArray("fixedSignedConstants").size()];
        for (int j = 0; j < constants.length; j++)
          constants[j] =
              row.getAsJsonArray("fixedSignedConstants").get(j).getAsBigDecimal().longValueExact();
        int[] keys = new int[row.getAsJsonArray("variableKeys").size()];
        for (int j = 0; j < keys.length; j++)
          keys[j] = row.getAsJsonArray("variableKeys").get(j).getAsBigDecimal().intValueExact();
        var program = expression(row.get("rawPostfixBytes").getAsString(), constants, keys);
        assertEquals(
            (i == 0 ? 3 : 66) * ONE,
            program.evaluate(
                Map.of(-978103959, 200 * ONE, -1803181471, 80 * ONE, 1555818884, 3 * ONE)));
        assertEquals(
            (i == 0 ? -2 : -67) * ONE,
            program.evaluate(
                Map.of(-978103959, -200 * ONE, -1803181471, 80 * ONE, 1555818884, 3 * ONE)));
        assertThrows(IllegalArgumentException.class, () -> program.evaluate(Map.of()));
      }
    }
  }

  @Test
  void compiledProgramKeepsOperandOrderAndOwnsItsInputTables() {
    byte[] code = HexFormat.of().parseHex("000000010511");
    long[] values = {6 * ONE, 2 * ONE};
    var program = RogueResonanceExpression46.compile(code, values, new int[0]);
    code[0] = 17;
    values[0] = 0;
    assertEquals(3 * ONE, program.evaluate(Map.of()));
    assertEquals(
        ONE / 2, expression("000000010511", new long[] {2 * ONE, 4 * ONE}).evaluate(Map.of()));
    assertEquals(
        -3 * ONE, expression("000000010511", new long[] {6 * ONE, -2 * ONE}).evaluate(Map.of()));
  }

  @Test
  void absentNullAndExtendedContextOrArithmeticResultsAreRejected() {
    var global = expression("010011", new long[0], 123);
    assertThrows(IllegalArgumentException.class, () -> global.evaluate(Map.of()));
    assertThrows(IllegalArgumentException.class, () -> global.evaluate(null));
    assertThrows(IllegalArgumentException.class, () -> global.evaluate(Map.of(123, 1L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> expression("000011", new long[] {1}).evaluate(Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            expression("010011", new long[0], RogueResonanceFunctions46.FLOOR_KEY)
                .evaluate(Map.of(RogueResonanceFunctions46.FLOOR_KEY, ONE)));
    for (long[] values :
        new long[][] {{ONE, 0}, {Long.MAX_VALUE & ~1L, 2}, {Long.MIN_VALUE, ONE}}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> expression("000000010511", values).evaluate(Map.of()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> expression("000000010211", new long[] {0x3fffffffL * ONE, ONE}).evaluate(Map.of()));
  }

  @Test
  void invalidNumericOpcodeStackTablesCallsAndReturnAreRejectedBeforeEvaluation() {
    for (String code :
        new String[] {
          "",
          "11",
          "0011",
          "000111",
          "0211",
          "0511",
          "0000",
          "00001100",
          "0000000011",
          "01000000100011",
          "01000000100211",
          "00000000100111",
          "010000001001",
          "00001211",
          "000000001211",
          "00000000ff11"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> expression(code, new long[] {0}, RogueResonanceFunctions46.FLOOR_KEY),
          code);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> expression("01000000100111", new long[] {0}, 0x710946));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceExpression46.compile(null, new long[0], new int[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceExpression46.compile(new byte[256], new long[0], new int[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceExpression46.compile(new byte[] {17}, new long[256], new int[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceExpression46.compile(new byte[] {17}, new long[0], new int[256]));
  }

  @Test
  void allOriginalProgramPrefixesTrailingBytesAndStackDepthOverflowAreRejected() {
    for (String code : new String[] {"01000101000002010205100111", "01000101010205100111"}) {
      byte[] raw = HexFormat.of().parseHex(code);
      for (int end = 0; end < raw.length; end++) {
        byte[] prefix = java.util.Arrays.copyOf(raw, end);
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RogueResonanceExpression46.compile(
                    prefix,
                    new long[] {100 * ONE},
                    new int[] {RogueResonanceFunctions46.FLOOR_KEY, 1, 2}));
      }
      byte[] trailing = java.util.Arrays.copyOf(raw, raw.length + 1);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              RogueResonanceExpression46.compile(
                  trailing,
                  new long[] {100 * ONE},
                  new int[] {RogueResonanceFunctions46.FLOOR_KEY, 1, 2}));
    }
    String tooDeep = "0000" + "000002".repeat(32) + "11";
    assertThrows(IllegalArgumentException.class, () -> expression(tooDeep, new long[] {0}));
    assertThrows(
        IllegalArgumentException.class, () -> expression("0000".repeat(65) + "11", new long[] {0}));
  }

  @Test
  void invalidContextEntriesCountsAndReuseDoNotSupplyPreviousValues() {
    var global = expression("010011", new long[0], 123);
    var values = new HashMap<Integer, Long>();
    values.put(123, null);
    assertThrows(IllegalArgumentException.class, () -> global.evaluate(values));
    values.clear();
    values.put(null, ONE);
    assertThrows(IllegalArgumentException.class, () -> global.evaluate(values));
    values.clear();
    for (int i = 0; i < 256; i++) values.put(i, 0L);
    assertThrows(IllegalArgumentException.class, () -> global.evaluate(values));
    assertEquals(ONE, global.evaluate(Map.of(123, ONE)));
    assertThrows(IllegalArgumentException.class, () -> global.evaluate(Map.of()));
    assertEquals(-ONE, global.evaluate(Map.of(123, -ONE)));
  }
}
