package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RogueResonanceAdsorption46Test {
  private static final long ONE = 1L << 33;

  private static RogueResonanceAdsorption46.Config config(long threshold, int digit) {
    return new RogueResonanceAdsorption46.Config(threshold, digit);
  }

  private static long snap(long raw, long threshold, int digit) {
    return RogueResonanceAdsorption46.apply(raw, config(threshold, digit));
  }

  private static RogueResonanceDynamicCell46.Scalar fixed(long raw) {
    return RogueResonanceDynamicCell46.Scalar.fixed(raw);
  }

  private static RogueResonanceDynamicCell46 cell(long raw, long threshold, int digit) {
    return new RogueResonanceDynamicCell46(fixed(raw), null, null, config(threshold, digit));
  }

  private static RogueResonanceVariables46 lookup(
      RogueResonanceDynamicCell46 cell,
      java.util.function.Consumer<RogueResonanceVariables46.Change> observer) {
    return RogueResonanceVariables46.withDynamicCells(
        RogueResonanceVariables46.fromSourceDefaults(Map.of(), observer), Map.of(2, cell));
  }

  @Test
  void fractionNearLowerIntegerSnapsDown() {
    assertEquals(10 * ONE, snap(10 * ONE + ONE / 16, ONE / 8, 0));
  }

  @Test
  void fractionNearUpperIntegerSnapsUp() {
    assertEquals(11 * ONE, snap(10 * ONE + 15 * ONE / 16, ONE / 8, 0));
  }

  @Test
  void selectedDynamicCellUsesNonNullAdsorptionOnWrite() {
    var l = lookup(cell(0, ONE / 16, 1), null);
    l.write(2, ONE + ONE / 16);
    assertEquals(9448928052L, l.read(2));
  }

  @Test
  void negativeValuesUseFloorFractionInBothDirections() {
    assertEquals(-ONE, snap(-15 * ONE / 16, ONE / 8, 0));
    assertEquals(0, snap(-ONE / 16, ONE / 8, 0));
  }

  @Test
  void lowerThresholdEqualityIsIncluded() {
    assertEquals(ONE, snap(ONE + ONE / 8, ONE / 8, 0));
  }

  @Test
  void upperThresholdEqualityIsIncluded() {
    assertEquals(2 * ONE, snap(2 * ONE - ONE / 8, ONE / 8, 0));
  }

  @Test
  void oneStandardUnitOutsideLowerThresholdDoesNotSnap() {
    long raw = ONE + ONE / 8 + 2;
    assertEquals(raw, snap(raw, ONE / 8, 0));
  }

  @Test
  void oneStandardUnitOutsideUpperThresholdDoesNotSnap() {
    long raw = 2 * ONE - ONE / 8 - 2;
    assertEquals(raw, snap(raw, ONE / 8, 0));
  }

  @Test
  void exactMidpointsChooseUpperIntegerIncludingNegativeValues() {
    assertEquals(ONE, snap(ONE / 2, ONE / 2, 0));
    assertEquals(0, snap(-ONE / 2, ONE / 2, 0));
    assertEquals(3 * ONE, snap(5 * ONE / 2, ONE, 0));
    assertEquals(-2 * ONE, snap(-5 * ONE / 2, ONE, 0));
  }

  @Test
  void twoDecimalScaleUsesNativeCorrectionQuantization() {
    assertEquals(8675833938L, snap(ONE + ONE / 128, ONE / 256, 2));
  }

  @Test
  void highestStandardScaleCanLoseSubUnitCorrection() {
    assertEquals(ONE + 8, snap(ONE + 8, 2, 9));
    assertEquals(ONE + 4, snap(ONE + 4, 2, 9));
  }

  @Test
  void zeroAndNegativeThresholdsLeaveValueUnchanged() {
    assertEquals(ONE / 16, snap(ONE / 16, 0, 0));
    assertEquals(ONE / 16, snap(ONE / 16, -ONE / 8, 1));
    assertEquals(Long.MIN_VALUE, snap(Long.MIN_VALUE, 0, 0));
    assertEquals(Long.MAX_VALUE - 1, snap(Long.MAX_VALUE - 1, 0, 0));
  }

  @Test
  void negativeDigitsProduceZeroScaleWithoutDivision() {
    assertEquals(ONE / 16, snap(ONE / 16, ONE, -1));
    assertEquals(ONE / 16, snap(ONE / 16, ONE, Integer.MIN_VALUE));
  }

  @Test
  void nullConfigLeavesAllStandardWordsUnchanged() {
    assertEquals(Long.MIN_VALUE, RogueResonanceAdsorption46.apply(Long.MIN_VALUE, null));
    assertEquals(Long.MAX_VALUE - 1, RogueResonanceAdsorption46.apply(Long.MAX_VALUE - 1, null));
    assertThrows(IllegalArgumentException.class, () -> RogueResonanceAdsorption46.apply(1, null));
  }

  @Test
  void malformedThresholdAndExtendedScaleAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> config(1, 0));
    assertThrows(IllegalArgumentException.class, () -> config(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> config(0, 10));
    assertThrows(IllegalArgumentException.class, () -> config(0, Integer.MAX_VALUE));
    assertDoesNotThrow(() -> config(2, 9));
  }

  @Test
  void extendedInputIsRejectedEvenForDisabledThreshold() {
    assertThrows(IllegalArgumentException.class, () -> snap(1, 0, 0));
  }

  @Test
  void extendedThresholdProductIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> snap(ONE, 1000 * ONE, 9));
  }

  @Test
  void roundingIntoExtendedRangeIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> snap(Long.MAX_VALUE - 1, ONE, 0));
  }

  @Test
  void lookupClipsBeforeAdsorptionAndGetterClipsSnappedValueAgain() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    long bound = ONE + ONE / 16;
    var maximum =
        RogueResonanceDynamicCell46.Scalar.fromPostfix(
            0, new byte[] {1, 0, 17}, new long[0], new int[] {1});
    var l =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(1, bound), changes::add),
            Map.of(
                2,
                new RogueResonanceDynamicCell46(fixed(0), fixed(0), maximum, config(ONE / 16, 1))));
    l.write(2, 5 * ONE);
    assertEquals(bound, l.read(2));
    assertEquals(List.of(new RogueResonanceVariables46.Change(2, 0, bound)), changes);
    l.write(1, 2 * ONE);
    assertEquals(9448928052L, l.read(2));
  }

  @Test
  void getterDoesNotSnapAndEachLookupRetainsItsOwnConfigTemplate() {
    var template = cell(ONE + ONE / 16, ONE / 16, 1);
    var first = lookup(template, null);
    var second = lookup(template, null);
    assertEquals(ONE + ONE / 16, first.read(2));
    first.write(2, ONE + ONE / 16);
    assertEquals(9448928052L, first.read(2));
    assertEquals(ONE + ONE / 16, second.read(2));
    second.write(2, ONE + ONE / 16);
    assertEquals(9448928052L, second.read(2));
    assertEquals(ONE + ONE / 16, template.read(null));
  }

  @Test
  void adsorptionFailureLeavesCellAndRoundCompletionUnchanged() {
    int key = RogueResonanceVariables46.nameKey("Count");
    var l =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), null),
            Map.of(key, cell(7 * ONE / 2, 1000 * ONE, 9)));
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Count");
    assertThrows(IllegalArgumentException.class, () -> task.execute(l));
    assertEquals(7 * ONE / 2, l.read(key));
    assertEquals(0x7777, task.state());
  }

  @Test
  void observerFailureKeepsAssignedSnappedValue() {
    var l =
        lookup(
            cell(0, ONE / 16, 1),
            change -> {
              throw new IllegalStateException("observer");
            });
    assertThrows(IllegalStateException.class, () -> l.write(2, ONE + ONE / 16));
    assertEquals(9448928052L, l.read(2));
  }

  @Test
  void unchangedWriteStillSnapsAndNotifies() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    long initial = ONE + ONE / 16;
    var l = lookup(cell(initial, ONE / 16, 1), changes::add);
    l.write(2, initial);
    assertEquals(List.of(new RogueResonanceVariables46.Change(2, initial, 9448928052L)), changes);
  }
}
