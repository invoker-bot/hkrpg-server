package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class RogueResonanceDynamicCell46Test {
  private static final long ONE = 1L << 33;

  private static RogueResonanceDynamicCell46.Scalar fixed(long raw) {
    return RogueResonanceDynamicCell46.Scalar.fixed(raw);
  }

  private static RogueResonanceDynamicCell46.Scalar global(int key) {
    return RogueResonanceDynamicCell46.Scalar.fromPostfix(
        1L, HexFormat.of().parseHex("010011"), new long[0], new int[] {key});
  }

  @Test
  void fixedDynamicBoundsClampSourceOnRead() {
    assertEquals(
        2 * ONE,
        new RogueResonanceDynamicCell46(fixed(3 * ONE), fixed(0), fixed(2 * ONE)).read(null));
  }

  @Test
  void nullValueIsClampedIntoBounds() {
    assertEquals(ONE, new RogueResonanceDynamicCell46(null, fixed(ONE), fixed(2 * ONE)).read(null));
  }

  @Test
  void postfixValueIsEvaluatedPerRead() {
    var value = new RogueResonanceDynamicCell46(global(71), null, null);
    assertEquals(3 * ONE, assertDoesNotThrow(() -> value.read(key -> 3 * ONE)));
  }

  @Test
  void lookupUsesDynamicCellFromScope() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(71, 3 * ONE), null);
    var lookup =
        assertDoesNotThrow(
            () ->
                RogueResonanceVariables46.withDynamicCells(
                    base, Map.of(72, new RogueResonanceDynamicCell46(global(71), null, null))));
    assertEquals(
        3 * ONE,
        new RogueResonanceContext46(null, java.util.List.of(), lookup, null).readInScope(1, 72));
  }

  private static RogueResonanceVariables46 project(
      Map<Integer, Long> fixedValues, Map<Integer, RogueResonanceDynamicCell46> dynamicValues) {
    return RogueResonanceVariables46.withDynamicCells(
        RogueResonanceVariables46.fromSourceDefaults(fixedValues, null), dynamicValues);
  }

  private static RogueResonanceDynamicCell46 cell(RogueResonanceDynamicCell46.Scalar value) {
    return new RogueResonanceDynamicCell46(value, null, null);
  }

  @Test
  void fixedScalarsNeverCallProvider() {
    assertEquals(
        ONE,
        fixed(ONE)
            .read(
                key -> {
                  fail("Fixed scalar called provider");
                  return 0;
                }));
    assertEquals(0, cell(null).read(null));
  }

  @Test
  void constantPostfixWorksWithoutProviderAndIgnoresStoredFixedWord() {
    var expression =
        RogueResonanceDynamicCell46.Scalar.fromPostfix(
            Long.MAX_VALUE, HexFormat.of().parseHex("000011"), new long[] {6 * ONE}, new int[0]);
    assertEquals(6 * ONE, expression.read(null));
    assertThrows(IllegalArgumentException.class, () -> global(71).read(null));
  }

  @Test
  void missingPostfixPointerUsesFixedWordRegardlessOfUnusedTables() {
    assertEquals(
        ONE, RogueResonanceDynamicCell46.Scalar.fromPostfix(ONE, null, null, null).read(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceDynamicCell46.Scalar.fromPostfix(1, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceDynamicCell46.Scalar.fromPostfix(
                ONE, new byte[0], new long[0], new int[0]));
  }

  @Test
  void absentExtendedRangesPreserveAllStandardEndpointWords() {
    assertEquals(Long.MIN_VALUE, cell(fixed(Long.MIN_VALUE)).read(null));
    assertEquals(Long.MAX_VALUE - 1, cell(fixed(Long.MAX_VALUE - 1)).read(null));
    assertEquals(
        new RogueResonanceVariables46.Bounds(-6710886399932891135L, 6710886399932891137L),
        cell(null).bounds(null));
  }

  @Test
  void allLimitsAreEvaluatedAfterValueInNativeOrder() {
    var reads = new ArrayList<Integer>();
    var c = new RogueResonanceDynamicCell46(global(1), global(2), global(3));
    assertEquals(
        2 * ONE,
        c.read(
            key -> {
              reads.add(key);
              return key * ONE;
            }));
    assertEquals(List.of(1, 2, 3), reads);
  }

  @Test
  void upperLimitFailureIsNotSkippedWhenLowerLimitWouldClip() {
    var reads = new ArrayList<Integer>();
    assertThrows(
        IllegalStateException.class,
        () ->
            new RogueResonanceDynamicCell46(global(1), global(2), global(3))
                .read(
                    key -> {
                      reads.add(key);
                      if (key == 3) throw new IllegalStateException("Unavailable upper input");
                      return key * ONE;
                    }));
    assertEquals(List.of(1, 2, 3), reads);
  }

  @Test
  void failedValueStopsBeforeEitherLimitAndRejectsOddResolverValues() {
    var reads = new ArrayList<Integer>();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RogueResonanceDynamicCell46(global(1), global(2), global(3))
                .read(
                    key -> {
                      reads.add(key);
                      return 1;
                    }));
    assertEquals(List.of(1), reads);
  }

  @Test
  void invertedLimitsRetainLowerFirstBranchAndEqualLimitsClipBothSides() {
    assertEquals(
        5 * ONE,
        new RogueResonanceDynamicCell46(fixed(3 * ONE), fixed(5 * ONE), fixed(2 * ONE)).read(null));
    assertEquals(
        2 * ONE,
        new RogueResonanceDynamicCell46(fixed(5 * ONE), fixed(5 * ONE), fixed(2 * ONE)).read(null));
    assertEquals(
        ONE, new RogueResonanceDynamicCell46(fixed(-ONE), fixed(ONE), fixed(ONE)).read(null));
    assertEquals(
        ONE, new RogueResonanceDynamicCell46(fixed(2 * ONE), fixed(ONE), fixed(ONE)).read(null));
  }

  @Test
  void seededDynamicClippingMatchesIndependentBigIntegerOrdering() {
    var random = new Random(0x46ce11);
    var c = new RogueResonanceDynamicCell46(global(1), global(2), global(3));
    for (int i = 0; i < 4096; i++) {
      long a = random.nextLong() & ~1L, b = random.nextLong() & ~1L, d = random.nextLong() & ~1L;
      var x = java.math.BigInteger.valueOf(a);
      long expected =
          x.compareTo(java.math.BigInteger.valueOf(b)) < 0
              ? b
              : x.compareTo(java.math.BigInteger.valueOf(d)) > 0 ? d : a;
      assertEquals(expected, c.read(key -> key == 1 ? a : key == 2 ? b : d));
    }
  }

  @Test
  void boundsReadsOnlyMinimumThenMaximum() {
    var reads = new ArrayList<Integer>();
    var bounds =
        new RogueResonanceDynamicCell46(global(1), global(2), global(3))
            .bounds(
                key -> {
                  reads.add(key);
                  return key * ONE;
                });
    assertEquals(new RogueResonanceVariables46.Bounds(2 * ONE, 3 * ONE), bounds);
    assertEquals(List.of(2, 3), reads);
  }

  @Test
  void localFixedReadsObserveWritesAndMissingLocalKeyIsNativeZero() {
    var lookup = project(Map.of(71, ONE), Map.of(72, cell(global(71)), 73, cell(global(99))));
    assertEquals(ONE, lookup.readNativeFixed(72));
    assertTrue(lookup.write(71, 4 * ONE));
    assertEquals(4 * ONE, lookup.read(72));
    assertEquals(0, lookup.read(73));
    assertEquals(0, lookup.readNativeFixed(99));
    assertThrows(IllegalArgumentException.class, () -> lookup.read(99));
  }

  @Test
  void cellProviderRemainsLocalInsteadOfMergingOtherContextFrames() {
    var lookup = project(Map.of(71, ONE), Map.of(72, cell(global(71))));
    var other = RogueResonanceVariables46.fromSourceDefaults(Map.of(71, 8 * ONE), null);
    var context = new RogueResonanceContext46(other, List.of(), lookup, null);
    assertEquals(8 * ONE, context.readInScope(0, 71));
    assertEquals(ONE, context.readInScope(1, 72));
    assertThrows(IllegalArgumentException.class, () -> context.readInScope(1, 99));
  }

  @Test
  void nestedCellsComputeCurrentDependencyOnceWithoutCaching() {
    var lookup = project(Map.of(71, ONE), Map.of(72, cell(global(71)), 73, cell(global(72))));
    assertEquals(ONE, lookup.read(73));
    lookup.write(71, 2 * ONE);
    assertEquals(2 * ONE, lookup.read(73));
  }

  @Test
  void cyclicReadRejectsAndFailureLeavesIndependentReadsUsable() {
    var lookup =
        project(Map.of(), Map.of(1, cell(global(2)), 2, cell(global(1)), 3, cell(fixed(ONE))));
    for (int i = 0; i < 2; i++) {
      assertThrows(IllegalArgumentException.class, () -> lookup.read(1));
      assertEquals(ONE, lookup.read(3));
    }
  }

  @Test
  void thirtyTwoCellChainWorksAndThirtyThreeRejectsWithoutPoisoningTheLookup() {
    var dynamic = new HashMap<Integer, RogueResonanceDynamicCell46>();
    dynamic.put(32, cell(fixed(ONE)));
    for (int i = 31; i >= 0; i--) dynamic.put(i, cell(global(i + 1)));
    var lookup = project(Map.of(), dynamic);
    assertEquals(ONE, lookup.read(1));
    assertThrows(IllegalArgumentException.class, () -> lookup.read(0));
    assertEquals(ONE, lookup.read(1));
  }

  @Test
  void tagProjectionIsAlsoTheProviderForDynamicCellsInThatContainer() {
    var lookup = project(Map.of(71, ONE), Map.of(72, cell(global(71))));
    var tagged =
        RogueResonanceVariables46.withAiTagProjection(
            lookup, Map.of(71, List.of(2 * ONE, 5 * ONE)));
    assertEquals(5 * ONE, tagged.readNativeFixed(72));
    assertEquals(5 * ONE, tagged.read(72));
    assertEquals(ONE, lookup.read(72));
    assertEquals(5 * ONE, tagged.readNativeFixed(71));
    assertEquals(ONE, tagged.read(71));
  }

  @Test
  void copiedDeclarationsAndPostfixTablesCannotChangeTheProjection() {
    byte[] code = HexFormat.of().parseHex("010011");
    int[] keys = {71};
    long[] constants = {};
    var dynamic = new HashMap<Integer, RogueResonanceDynamicCell46>();
    dynamic.put(72, cell(RogueResonanceDynamicCell46.Scalar.fromPostfix(0, code, constants, keys)));
    var lookup = project(Map.of(71, 3 * ONE), dynamic);
    dynamic.clear();
    code[0] = (byte) 255;
    keys[0] = 99;
    assertEquals(3 * ONE, lookup.read(72));
  }

  @Test
  void snapshotOverlaysValuesAndDynamicWritesLeaveTheBaseCellSeparate() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(71, ONE, 72, 0L), changes::add);
    var lookup = RogueResonanceVariables46.withDynamicCells(base, Map.of(72, cell(global(71))));
    assertEquals(Map.of(71, ONE, 72, ONE), lookup.snapshot());
    assertTrue(lookup.contains(72));
    assertTrue(lookup.write(72, 3 * ONE));
    assertEquals(0, base.read(72));
    assertEquals(List.of(new RogueResonanceVariables46.Change(72, ONE, 3 * ONE)), changes);
    assertTrue(lookup.write(71, 2 * ONE));
    assertEquals(2, changes.size());
    assertEquals(3 * ONE, lookup.read(72));
    assertFalse(lookup.write(99, ONE));
  }

  @Test
  void lookupBoundsUseLocalProviderAndDoNotReadCellValue() {
    var lookup =
        project(
            Map.of(71, ONE),
            Map.of(72, new RogueResonanceDynamicCell46(global(72), global(71), null)));
    assertEquals(
        new RogueResonanceVariables46.Bounds(ONE, 6710886399932891137L), lookup.bounds(72));
    assertThrows(IllegalArgumentException.class, () -> lookup.read(72));
  }

  @Test
  void invalidProjectionInputsAndUnknownPostfixRemainClosed() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(), null);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceVariables46.withDynamicCells(null, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceVariables46.withDynamicCells(base, null));
    var bad = new HashMap<Integer, RogueResonanceDynamicCell46>();
    bad.put(1, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceVariables46.withDynamicCells(base, bad));
    var many = new HashMap<Integer, RogueResonanceDynamicCell46>();
    for (int i = 0; i < 256; i++) many.put(i, cell(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueResonanceVariables46.withDynamicCells(base, many));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueResonanceDynamicCell46.Scalar.fromPostfix(
                0, new byte[] {(byte) 255}, new long[0], new int[0]));
  }

  @Test
  void readStackIsIsolatedAcrossThreadsAndReleasedAfterErrors() throws Exception {
    var lookup = project(Map.of(71, ONE), Map.of(72, cell(global(71)), 73, cell(global(73))));
    try (var executor = Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<java.util.concurrent.Callable<Long>>();
      for (int i = 0; i < 16; i++)
        calls.add(
            () -> {
              assertThrows(IllegalArgumentException.class, () -> lookup.read(73));
              return lookup.read(72);
            });
      for (var result : executor.invokeAll(calls)) assertEquals(ONE, result.get());
    }
  }

  @Test
  void combinedContainerKeyLimitCountsOverlapsOnce() {
    var fixedValues = new HashMap<Integer, Long>();
    for (int i = 0; i < 255; i++) fixedValues.put(i, 0L);
    assertEquals(ONE, project(fixedValues, Map.of(254, cell(fixed(ONE)))).read(254));
    assertThrows(
        IllegalArgumentException.class, () -> project(fixedValues, Map.of(255, cell(fixed(ONE)))));
  }

  @Test
  void taggedBoundsAndSnapshotUseTheSameContainerProvider() {
    var lookup =
        project(
            Map.of(71, ONE),
            Map.of(72, new RogueResonanceDynamicCell46(fixed(0), global(71), null)));
    var tagged =
        RogueResonanceVariables46.withAiTagProjection(lookup, Map.of(71, List.of(5 * ONE)));
    assertEquals(
        new RogueResonanceVariables46.Bounds(5 * ONE, 6710886399932891137L), tagged.bounds(72));
    assertEquals(Map.of(71, ONE, 72, 5 * ONE), tagged.snapshot());
    assertEquals(Map.of(71, ONE, 72, ONE), lookup.snapshot());
  }
}
