package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RogueResonanceDefaults46Test {
  private static final long ONE = 1L << 33;
  private static final long LOWER = -6710886399932891135L;
  private static final long UPPER = 6710886399932891137L;

  static Stream<Long> standardCases() {
    return Stream.of(0L, ONE, -ONE, Long.MIN_VALUE, Long.MAX_VALUE & ~1L);
  }

  @ParameterizedTest
  @MethodSource("standardCases")
  void nativeDefaultBoundsInitializeAndWriteEveryStandardValue(long raw) {
    Long actual;
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    try {
      var lookup = RogueResonanceVariables46.fromSourceDefaults(Map.of(42, raw), changes::add);
      assertEquals(new RogueResonanceVariables46.Bounds(LOWER, UPPER), lookup.bounds(42));
      assertTrue(changes.isEmpty());
      assertTrue(lookup.write(42, raw));
      actual = lookup.read(42);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(raw), actual);
    assertEquals(List.of(new RogueResonanceVariables46.Change(42, raw, raw)), changes);
  }

  @Test
  void createsFourOriginalDeclarationsWithNativeBoundsAndRunsExplicitSourceChain()
      throws Exception {
    Long actual;
    try {
      var fixed = new HashMap<Integer, Long>();
      try (var stream =
          getClass().getResourceAsStream("/fixtures/rogue-resonance-variables-4.6.json")) {
        assertNotNull(stream);
        var profile =
            JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (var row : profile.getAsJsonObject("sourceDynamicValues").getAsJsonArray("floats")) {
          var cell = row.getAsJsonObject();
          assertEquals(0, cell.get("rawReadType").getAsInt());
          fixed.put(
              cell.get("key").getAsInt(),
              cell.get("fixedSignedRaw").getAsBigDecimal().longValueExact());
        }
      }
      var selected = RogueResonanceVariables46.fromSourceDefaults(fixed, null);
      assertEquals(4, selected.snapshot().size());
      for (int key : fixed.keySet())
        assertEquals(new RogueResonanceVariables46.Bounds(LOWER, UPPER), selected.bounds(key));
      new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12)
          .execute(selected, List.of(Map.of(12, 10 * ONE)));
      var multiplied =
          RogueResonanceExpression46.compile(
              HexFormat.of().parseHex("010000000411"),
              new long[] {100 * ONE},
              new int[] {909827949});
      new RogueResonanceWrite46.ExpressionTask(2791, "Extra_AttackTime", multiplied)
          .execute(Map.of(909827949, ONE / 64), List.of(selected), false);
      new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime").execute(selected);
      var floor =
          RogueResonanceExpression46.compile(
              HexFormat.of().parseHex("01000101010205100111"),
              new long[0],
              new int[] {1776456860, -978103959, 1555818884});
      actual = floor.evaluate(selected.snapshot());
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(5 * ONE), actual);
  }

  @Test
  void standardWordsAreStrictlyInsideNativeExtendedBoundsUsingIndependentDecimalValues() {
    var random = new Random(46074469L);
    var lower = BigDecimal.valueOf(LOWER & ~1L).divide(BigDecimal.valueOf(1L << 26));
    var upper = BigDecimal.valueOf(UPPER & ~1L).divide(BigDecimal.valueOf(1L << 26));
    assertEquals(new BigDecimal("-99999999999"), lower.stripTrailingZeros());
    assertEquals(new BigDecimal("99999999999"), upper.stripTrailingZeros());
    var lookup = RogueResonanceVariables46.fromSourceDefaults(Map.of(42, 0L), null);
    for (int i = 0; i < 4096; i++) {
      long raw = random.nextLong() & ~1L;
      var value = BigDecimal.valueOf(raw).divide(BigDecimal.valueOf(ONE));
      assertTrue(value.compareTo(lower) > 0 && value.compareTo(upper) < 0);
      lookup.write(42, raw);
      assertEquals(raw, lookup.read(42));
    }
  }

  @Test
  void sourceDefaultsDoNotOpenExtendedValuesCustomRangesOrMissingVariables() {
    for (long raw : new long[] {1L, -1L, LOWER, UPPER})
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueResonanceVariables46.fromSourceDefaults(Map.of(42, raw), null));
    var lookup = RogueResonanceVariables46.fromSourceDefaults(Map.of(42, 0L), null);
    assertThrows(IllegalArgumentException.class, () -> lookup.write(42, 1));
    assertEquals(0, lookup.read(42));
    assertThrows(
        IllegalArgumentException.class, () -> RogueResonanceVariables46.clamp(0, LOWER, UPPER));
    assertFalse(lookup.write(43, ONE));
    assertThrows(IllegalArgumentException.class, () -> lookup.bounds(43));
    assertEquals(Map.of(42, 0L), lookup.snapshot());
  }

  @Test
  void sourceDefaultInputIsDetachedAndMalformedDeclarationsRemainClosed() {
    var values = new HashMap<Integer, Long>();
    values.put(42, ONE);
    var lookup = RogueResonanceVariables46.fromSourceDefaults(values, null);
    values.clear();
    assertEquals(ONE, lookup.read(42));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceVariables46.fromSourceDefaults(null, null));
    values.put(42, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceVariables46.fromSourceDefaults(values, null));
    values.clear();
    values.put(null, 0L);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceVariables46.fromSourceDefaults(values, null));
    values.clear();
    for (int i = 0; i < 256; i++) values.put(i, 0L);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceVariables46.fromSourceDefaults(values, null));
    assertTrue(RogueResonanceVariables46.fromSourceDefaults(Map.of(), null).snapshot().isEmpty());
  }
}
