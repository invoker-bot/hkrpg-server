package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RogueModifierInjection46Test {
  static final long ONE = 1L << 33, MIN = -6710886399932891135L, MAX = 6710886399932891137L;

  static BigInteger units(long raw) {
    return (raw & 1) == 0 ? BigInteger.valueOf(raw) : BigInteger.valueOf(raw & ~1L).shiftLeft(7);
  }

  static RogueModifierReplace46.State state(RogueResonanceVariables46 variables) {
    var s = RogueModifierReplace46Test.state();
    s.values.dynamicValues = variables;
    return s;
  }

  static RogueResonanceVariables46 empty(
      java.util.function.Consumer<RogueResonanceVariables46.Change> observer) {
    return new RogueResonanceVariables46(Map.of(), observer);
  }

  static RogueModifierInjection46.FloatInjection input(int key, long raw) {
    return new RogueModifierInjection46.FloatInjection(key, raw);
  }

  @Test
  void orderingMatchesIndependentExactIntegerOracleAtSignedAndTaggedBoundaries() {
    long[] values = {
      Long.MIN_VALUE,
      Long.MIN_VALUE | 1,
      -259,
      -258,
      -257,
      -256,
      -255,
      -254,
      -2,
      -1,
      0,
      1,
      2,
      127,
      128,
      129,
      130,
      ONE - 2,
      ONE,
      ONE + 2,
      (1L << 26) | 1,
      Long.MAX_VALUE - 1,
      Long.MAX_VALUE
    };
    for (long a : values)
      for (long b : values)
        assertEquals(
            Integer.signum(units(a).compareTo(units(b))),
            RogueModifierInjection46.compareRaw(a, b),
            a + ":" + b);
  }

  @Test
  void orderingMatchesDeterministicRandomRawWordsWithoutPrecisionLoss() {
    var random = new Random(4609);
    for (int i = 0; i < 4096; i++) {
      long a = random.nextLong(), b = random.nextLong();
      assertEquals(
          Integer.signum(units(a).compareTo(units(b))), RogueModifierInjection46.compareRaw(a, b));
    }
  }

  @Test
  void mixedBoundaryResidualPreservesPositiveAndNegativeSubgridDifferences() {
    long extended = (1L << 26) | 1;
    assertEquals(0, RogueModifierInjection46.compareRaw(ONE, extended));
    assertEquals(1, RogueModifierInjection46.compareRaw(ONE + 2, extended));
    assertEquals(-1, RogueModifierInjection46.compareRaw(ONE - 2, extended));
    assertEquals(0, RogueModifierInjection46.compareRaw(-256, -1));
    assertEquals(1, RogueModifierInjection46.compareRaw(-254, -1));
    assertEquals(-1, RogueModifierInjection46.compareRaw(-258, -1));
  }

  @Test
  void clampPreservesOriginalModeAtEqualBoundary() {
    long extended = (1L << 26) | 1;
    assertEquals(ONE, RogueModifierInjection46.clampRaw(ONE, extended, extended));
    assertEquals(extended, RogueModifierInjection46.clampRaw(extended, ONE, ONE));
    assertEquals(0, RogueModifierInjection46.clampRaw(0, 1, 1));
    assertEquals(1, RogueModifierInjection46.clampRaw(1, 0, 0));
  }

  @Test
  void clampUsesLowerFirstEvenWithInvertedMixedBounds() {
    assertEquals(ONE, RogueModifierInjection46.clampRaw(0, ONE, -1));
    assertEquals(-1, RogueModifierInjection46.clampRaw(2 * ONE, ONE, -1));
  }

  @Test
  void sourceDefaultBoundsContainEveryStandardSignedWord() {
    for (long raw : new long[] {Long.MIN_VALUE, -ONE, -2, 0, 2, ONE, Long.MAX_VALUE - 1})
      assertEquals(raw, RogueModifierInjection46.clampRaw(raw, MIN, MAX));
  }

  @Test
  void clampMatchesIndependentOracleForAllModeCombinations() {
    var random = new Random(468);
    for (int i = 0; i < 1024; i++) {
      long raw = random.nextLong(), min = random.nextLong(), max = random.nextLong();
      long expected =
          units(raw).compareTo(units(min)) < 0
              ? min
              : units(raw).compareTo(units(max)) > 0 ? max : raw;
      assertEquals(expected, RogueModifierInjection46.clampRaw(raw, min, max));
    }
  }

  @Test
  void absentRootKeyIsCreatedWithExactNativeSourceBounds() {
    var vars = empty(null);
    vars.injectSourceStandard(42, ONE);
    assertEquals(ONE, vars.read(42));
    assertEquals(new RogueResonanceVariables46.Bounds(MIN, MAX), vars.bounds(42));
    assertTrue(vars.contains(42));
  }

  @Test
  void injectionNotificationHasNoPreviousValueEvenForExistingKey() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var vars = RogueResonanceVariables46.fromSourceDefaults(Map.of(42, ONE), changes::add);
    vars.injectSourceStandard(42, 2 * ONE);
    assertEquals(List.of(new RogueResonanceVariables46.Change(42, 0, 2 * ONE, false)), changes);
    assertFalse(changes.getFirst().previousPresent());
  }

  @Test
  void ordinaryWriteKeepsItsExistingPreviousValueNotification() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var vars = empty(changes::add);
    vars.injectSourceStandard(42, ONE);
    vars.write(42, 2 * ONE);
    assertEquals(new RogueResonanceVariables46.Change(42, ONE, 2 * ONE), changes.getLast());
    assertTrue(changes.getLast().previousPresent());
  }

  @Test
  void injectedValueCanExceedRetainedOldBoundsUntilNextOrdinaryWrite() {
    var vars =
        new RogueResonanceVariables46(
            Map.of(42, new RogueResonanceVariables46.Value(ONE, 0, 2 * ONE)), null);
    vars.injectSourceStandard(42, 5 * ONE);
    assertEquals(5 * ONE, vars.read(42));
    assertEquals(new RogueResonanceVariables46.Bounds(0, 2 * ONE), vars.bounds(42));
    vars.write(42, 5 * ONE);
    assertEquals(2 * ONE, vars.read(42));
  }

  @Test
  void invertedExistingBoundsAreRetainedWithoutReclampingInjection() {
    var vars =
        new RogueResonanceVariables46(
            Map.of(42, new RogueResonanceVariables46.Value(0, 2 * ONE, ONE)), null);
    vars.injectSourceStandard(42, 0);
    assertEquals(0, vars.read(42));
    assertEquals(new RogueResonanceVariables46.Bounds(2 * ONE, ONE), vars.bounds(42));
    vars.write(42, 0);
    assertEquals(2 * ONE, vars.read(42));
  }

  @Test
  void observerFaultLeavesReplacementAlreadyCommitted() {
    var vars =
        empty(
            change -> {
              throw new IllegalStateException("observer");
            });
    assertThrows(IllegalStateException.class, () -> vars.injectSourceStandard(42, ONE));
    assertEquals(ONE, vars.read(42));
    assertEquals(new RogueResonanceVariables46.Bounds(MIN, MAX), vars.bounds(42));
  }

  @Test
  void observerReentryReadsCommittedCellAndCanWriteWithoutSecondMonitor() {
    RogueResonanceVariables46[] holder = {null};
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    holder[0] =
        empty(
            change -> {
              changes.add(change);
              if (!change.previousPresent()) {
                assertEquals(ONE, holder[0].read(42));
                holder[0].write(42, 2 * ONE);
              }
            });
    holder[0].injectSourceStandard(42, ONE);
    assertEquals(2 * ONE, holder[0].read(42));
    assertEquals(
        List.of(
            new RogueResonanceVariables46.Change(42, 0, ONE, false),
            new RogueResonanceVariables46.Change(42, ONE, 2 * ONE)),
        changes);
  }

  @Test
  void unchangedInjectionStillNotifiesWithoutPreviousValue() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var vars = RogueResonanceVariables46.fromSourceDefaults(Map.of(42, ONE), changes::add);
    vars.injectSourceStandard(42, ONE);
    assertEquals(List.of(new RogueResonanceVariables46.Change(42, 0, ONE, false)), changes);
  }

  @Test
  void extendedCellInputIsRejectedWithoutDeclaringOrReplacingKey() {
    var vars = RogueResonanceVariables46.fromSourceDefaults(Map.of(42, ONE), null);
    assertThrows(IllegalArgumentException.class, () -> vars.injectSourceStandard(42, 1));
    assertThrows(IllegalArgumentException.class, () -> vars.injectSourceStandard(43, 1));
    assertEquals(ONE, vars.read(42));
    assertFalse(vars.contains(43));
  }

  @Test
  void projectionInjectionIsRejectedWithoutChangingBaseOrView() {
    var vars = RogueResonanceVariables46.fromSourceDefaults(Map.of(42, ONE), null);
    var projected = RogueResonanceVariables46.withAiTagProjection(vars, Map.of());
    assertThrows(IllegalArgumentException.class, () -> projected.injectSourceStandard(42, 2 * ONE));
    assertEquals(ONE, vars.read(42));
    var dynamic = RogueResonanceVariables46.withDynamicCells(vars, Map.of());
    assertThrows(IllegalArgumentException.class, () -> dynamic.injectSourceStandard(42, 2 * ONE));
    assertEquals(ONE, vars.read(42));
  }

  @Test
  void fullFiniteMapAllowsReplacementButRejectsAdditionalKey() {
    var input = new HashMap<Integer, Long>();
    for (int i = 0; i < 255; i++) input.put(i, 0L);
    var vars = RogueResonanceVariables46.fromSourceDefaults(input, null);
    vars.injectSourceStandard(0, ONE);
    assertEquals(ONE, vars.read(0));
    assertThrows(IllegalArgumentException.class, () -> vars.injectSourceStandard(256, ONE));
    assertFalse(vars.contains(256));
    assertEquals(255, vars.snapshot().size());
  }

  @Test
  void allInt32HashBitsAndExtremeStandardRawWordsArePreserved() {
    var vars = empty(null);
    vars.injectSourceStandard(Integer.MIN_VALUE, Long.MIN_VALUE);
    vars.injectSourceStandard(Integer.MAX_VALUE, Long.MAX_VALUE - 1);
    vars.injectSourceStandard(0, 0);
    assertEquals(Long.MIN_VALUE, vars.read(Integer.MIN_VALUE));
    assertEquals(Long.MAX_VALUE - 1, vars.read(Integer.MAX_VALUE));
    assertEquals(0, vars.read(0));
  }

  @Test
  void floatListCreatesMissingKeysInOrder() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var vars = empty(changes::add);
    RogueModifierInjection46.applyFloatInjections(
        state(vars), List.of(input(42, ONE), input(43, 2 * ONE)));
    assertEquals(Map.of(42, ONE, 43, 2 * ONE), vars.snapshot());
    assertEquals(
        List.of(42, 43), changes.stream().map(RogueResonanceVariables46.Change::key).toList());
  }

  @Test
  void emptyListDoesNotReadMissingDynamicContainer() {
    var s = RogueModifierReplace46Test.state();
    s.values.dynamicValues = null;
    RogueModifierInjection46.applyFloatInjections(s, List.of());
    assertNull(s.values.dynamicValues);
  }

  @Test
  void nullListAndForeignListObjectAreRejectedBeforeContainer() {
    var s = RogueModifierReplace46Test.state();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierInjection46.applyFloatInjections(s, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierInjection46.applyFloatInjections(s, new Object()));
  }

  @Test
  void wrongElementAfterValidElementKeepsEarlierInjection() {
    var vars = empty(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierInjection46.applyFloatInjections(
                state(vars), List.of(input(42, ONE), new Object())));
    assertEquals(Map.of(42, ONE), vars.snapshot());
  }

  @Test
  void extendedLaterElementKeepsEarlierInjectionAndDoesNotDeclareIt() {
    var vars = empty(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierInjection46.applyFloatInjections(
                state(vars), List.of(input(42, ONE), input(43, 1))));
    assertEquals(Map.of(42, ONE), vars.snapshot());
  }

  @Test
  void callbackAppendingToCapturedListIsVisitedByCountReread() {
    var list = new ArrayList<RogueModifierInjection46.FloatInjection>();
    list.add(input(42, ONE));
    var vars =
        empty(
            change -> {
              if (change.key() == 42) list.add(input(43, 2 * ONE));
            });
    RogueModifierInjection46.applyFloatInjections(state(vars), list);
    assertEquals(Map.of(42, ONE, 43, 2 * ONE), vars.snapshot());
  }

  @Test
  void callbackShrinkingCapturedListStopsAfterCurrentInjection() {
    var list = new ArrayList<RogueModifierInjection46.FloatInjection>();
    list.add(input(42, ONE));
    list.add(input(43, 2 * ONE));
    var vars = empty(change -> list.removeLast());
    RogueModifierInjection46.applyFloatInjections(state(vars), list);
    assertEquals(Map.of(42, ONE), vars.snapshot());
  }

  @Test
  void currentContainerIsRereadForEachListElement() {
    var second = empty(null);
    RogueModifierReplace46.State[] holder = {null};
    var first = empty(change -> holder[0].values.dynamicValues = second);
    holder[0] = state(first);
    RogueModifierInjection46.applyFloatInjections(
        holder[0], List.of(input(42, ONE), input(43, 2 * ONE)));
    assertEquals(Map.of(42, ONE), first.snapshot());
    assertEquals(Map.of(43, 2 * ONE), second.snapshot());
  }

  @Test
  void callbackChangingInitialListFieldDoesNotReplaceCapturedParameterList() {
    var vars = empty(null);
    var s = state(vars);
    var list = new ArrayList<RogueModifierInjection46.FloatInjection>();
    list.add(input(42, ONE));
    list.add(input(43, 2 * ONE));
    var changing = empty(change -> s.initialFloatInjections = List.of(input(99, ONE)));
    s.values.dynamicValues = changing;
    RogueModifierInjection46.applyFloatInjections(s, list);
    assertEquals(Map.of(42, ONE, 43, 2 * ONE), changing.snapshot());
    assertFalse(changing.contains(99));
  }

  @Test
  void observerFaultStopsLaterListItemsButKeepsFaultingCell() {
    var vars =
        empty(
            change -> {
              throw new IllegalStateException("observer");
            });
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueModifierInjection46.applyFloatInjections(
                state(vars), List.of(input(42, ONE), input(43, 2 * ONE))));
    assertEquals(Map.of(42, ONE), vars.snapshot());
  }

  @Test
  void finiteListLimitStopsObserverDrivenUnboundedAppendAfterCommittedPrefix() {
    var list = new ArrayList<RogueModifierInjection46.FloatInjection>();
    list.add(input(42, ONE));
    int[] count = {0};
    var vars =
        empty(
            change -> {
              count[0]++;
              list.add(input(42, ONE));
            });
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierInjection46.applyFloatInjections(state(vars), list));
    assertEquals(255, count[0]);
    assertEquals(ONE, vars.read(42));
  }

  @Test
  void actualReplacementFloatBindingInstallsIntoSameVariableContainer() {
    var vars = empty(null);
    var s = state(vars);
    var p = RogueModifierReplace46Test.parameters();
    p.base.floatInjections = List.of(input(42, ONE));
    var world =
        new RogueModifierReplace46Test.BoundWorld() {
          public void setupFloatInjections(RogueModifierReplace46.State state, Object list) {
            super.setupFloatInjections(state, list);
            RogueModifierInjection46.applyFloatInjections(state, list);
          }
        };
    RogueModifierReplace46.replace(s, RogueModifierReplace46Test.ABILITY, p, world);
    assertEquals(ONE, vars.read(42));
    assertSame(p.base.floatInjections, s.initialFloatInjections);
    assertEquals(
        List.of(
            "acquire",
            "layerAdd",
            "release",
            "flag7",
            "context",
            "ability",
            "abilityChanged",
            "float",
            "dispatch"),
        world.order);
  }

  @Test
  void injectedKeyEnablesExistingModifierValueBindingAndPreservesNotificationKinds() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var vars = empty(changes::add);
    var s = state(vars);
    RogueModifierInjection46.applyFloatInjections(s, List.of(input(42, ONE)));
    s.values.keys = new int[] {42};
    s.values.mask = 1;
    s.values.modifier.layer = 3;
    RogueModifierValueBinding46.notifyValue(
        s.values, 0, RogueModifierValueBinding46.standardVariables());
    assertEquals(3 * ONE, vars.read(42));
    assertEquals(
        List.of(
            new RogueResonanceVariables46.Change(42, 0, ONE, false),
            new RogueResonanceVariables46.Change(42, ONE, 3 * ONE)),
        changes);
  }

  @Test
  void missingContainerProjectionOrStateDoesNotFabricateInstallation() {
    var s = RogueModifierReplace46Test.state();
    s.values.dynamicValues = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierInjection46.applyFloatInjections(s, List.of(input(42, ONE))));
    s.values.dynamicValues = new Object();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierInjection46.applyFloatInjections(s, List.of(input(42, ONE))));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierInjection46.applyFloatInjections(null, List.of()));
  }
}
