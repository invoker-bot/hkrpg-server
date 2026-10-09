package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RogueResonanceVariables46Test {
  private static final long ONE = 1L << 33;
  private static final int EXTRA_ATTACK = 1555818884;

  static Stream<Arguments> setterCases() {
    return Stream.of(
        Arguments.of(-3 * ONE, -ONE),
        Arguments.of(-ONE, -ONE),
        Arguments.of(0L, 0L),
        Arguments.of(ONE, ONE),
        Arguments.of(3 * ONE, ONE));
  }

  @ParameterizedTest
  @MethodSource("setterCases")
  void setterClampsExistingCellAndNotifiesAfterWriting(long requested, long expected) {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    Long actual;
    try {
      var lookup =
          new RogueResonanceVariables46(
              Map.of(42, new RogueResonanceVariables46.Value(0, -ONE, ONE)), changes::add);
      assertTrue(lookup.write(42, requested));
      actual = lookup.read(42);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(expected), actual);
    assertEquals(java.util.List.of(new RogueResonanceVariables46.Change(42, 0, expected)), changes);
  }

  static Stream<Arguments> roundCases() {
    return Stream.of(
        Arguments.of(ONE / 2, 0L),
        Arguments.of(ONE + ONE / 2, 2 * ONE),
        Arguments.of(-ONE - ONE / 2, -2 * ONE),
        Arguments.of(3 * ONE + ONE / 2, 3 * ONE));
  }

  @ParameterizedTest
  @MethodSource("roundCases")
  void actualTaskVariantRoundsThenUsesSameLookupClampAndObserver(long raw, long expected) {
    Long actual;
    Integer state;
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    try {
      var lookup =
          new RogueResonanceVariables46(
              Map.of(EXTRA_ATTACK, new RogueResonanceVariables46.Value(raw, -3 * ONE, 3 * ONE)),
              changes::add);
      var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
      assertEquals(0x7777, task.state());
      task.execute(lookup);
      actual = lookup.read(EXTRA_ATTACK);
      state = task.state();
    } catch (IllegalArgumentException unmatched) {
      actual = null;
      state = null;
    }
    assertEquals(Long.valueOf(expected), actual);
    assertEquals(Integer.valueOf(0x9999), state);
    assertEquals(
        java.util.List.of(new RogueResonanceVariables46.Change(EXTRA_ATTACK, raw, expected)),
        changes);
  }

  @Test
  void originalNamedKeyMatchesNativeRoundTaskConstructor() {
    Integer actual;
    try {
      actual = RogueResonanceVariables46.nameKey("Extra_AttackTime");
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Integer.valueOf(EXTRA_ATTACK), actual);
  }

  @Test
  void roundsOriginalExtraAttackBeforeOriginalFloorExpression() {
    Long actual;
    try {
      var lookup =
          new RogueResonanceVariables46(
              Map.of(
                  EXTRA_ATTACK,
                  new RogueResonanceVariables46.Value(ONE + ONE / 2, 0, 10 * ONE),
                  -978103959,
                  new RogueResonanceVariables46.Value(10 * ONE, 0, 100 * ONE)),
              null);
      new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime").execute(lookup);
      var floor =
          RogueResonanceExpression46.compile(
              HexFormat.of().parseHex("01000101010205100111"),
              new long[0],
              new int[] {1776456860, -978103959, EXTRA_ATTACK});
      actual = floor.evaluate(lookup.snapshot());
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(5 * ONE), actual);
  }

  @Test
  void invertedBoundsRetainNativeLowerFirstControlFlow() {
    Long actual;
    try {
      actual = RogueResonanceVariables46.clamp(0, 2 * ONE, -2 * ONE);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(2 * ONE), actual);
    assertEquals(-2 * ONE, RogueResonanceVariables46.clamp(3 * ONE, 2 * ONE, -2 * ONE));
  }

  @Test
  void missingWriteDoesNotCreateCellOrNotifyAndMissingReadAndTaskFail() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var lookup = new RogueResonanceVariables46(Map.of(), changes::add);
    assertFalse(lookup.write(EXTRA_ATTACK, ONE));
    assertTrue(lookup.snapshot().isEmpty());
    assertTrue(changes.isEmpty());
    assertThrows(IllegalArgumentException.class, () -> lookup.read(EXTRA_ATTACK));
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    assertThrows(IllegalArgumentException.class, () -> task.execute(lookup));
    assertEquals(0x7777, task.state());
  }

  @Test
  void snapshotAndConstructorInputAreDetached() {
    var source = new HashMap<Integer, RogueResonanceVariables46.Value>();
    source.put(42, new RogueResonanceVariables46.Value(0, -ONE, ONE));
    var lookup = new RogueResonanceVariables46(source, null);
    source.clear();
    var snapshot = lookup.snapshot();
    assertThrows(UnsupportedOperationException.class, () -> snapshot.put(42, ONE));
    lookup.write(42, ONE);
    assertEquals(0, snapshot.get(42));
    assertEquals(ONE, lookup.read(42));
  }

  @Test
  void observerReadsCommittedValueEvenWhenValueDidNotChange() {
    var reference = new RogueResonanceVariables46[1];
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    reference[0] =
        new RogueResonanceVariables46(
            Map.of(42, new RogueResonanceVariables46.Value(0, -ONE, ONE)),
            change -> {
              assertEquals(change.current(), reference[0].read(change.key()));
              changes.add(change);
            });
    reference[0].write(42, 0);
    reference[0].write(42, 0);
    assertEquals(2, changes.size());
  }

  @Test
  void observerFailureKeepsWriteButDoesNotCompleteTask() {
    var lookup =
        new RogueResonanceVariables46(
            Map.of(EXTRA_ATTACK, new RogueResonanceVariables46.Value(ONE + ONE / 2, 0, 3 * ONE)),
            change -> {
              throw new IllegalStateException("observer failed");
            });
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    assertThrows(IllegalStateException.class, () -> task.execute(lookup));
    assertEquals(2 * ONE, lookup.read(EXTRA_ATTACK));
    assertEquals(0x7777, task.state());
  }

  @Test
  void unsupportedRoundDoesNotWriteNotifyOrComplete() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    long raw = Long.MIN_VALUE;
    var lookup =
        new RogueResonanceVariables46(
            Map.of(
                EXTRA_ATTACK,
                new RogueResonanceVariables46.Value(raw, Long.MIN_VALUE, Long.MAX_VALUE & ~1L)),
            changes::add);
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    assertThrows(IllegalArgumentException.class, () -> task.execute(lookup));
    assertEquals(raw, lookup.read(EXTRA_ATTACK));
    assertTrue(changes.isEmpty());
    assertEquals(0x7777, task.state());
  }

  @Test
  void keyHashMatchesAllOriginalLocalNames() {
    assertEquals(-978103959, RogueResonanceVariables46.nameKey("_CurrentSP"));
    assertEquals(-38167523, RogueResonanceVariables46.nameKey("AttackTime"));
    assertEquals(-1803181471, RogueResonanceVariables46.nameKey("DelayCount_Levelup7"));
    assertEquals(1776456860, RogueResonanceVariables46.nameKey("floor"));
    assertEquals(1554761690, RogueResonanceVariables46.nameKey("round"));
  }

  @Test
  void rejectsMalformedCellsKeysTasksAndExtendedWrites() {
    assertThrows(IllegalArgumentException.class, () -> new RogueResonanceVariables46(null, null));
    var tooMany = new HashMap<Integer, RogueResonanceVariables46.Value>();
    for (int i = 0; i < 256; i++) tooMany.put(i, new RogueResonanceVariables46.Value(0, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceVariables46(tooMany, null));
    var nullCell = new HashMap<Integer, RogueResonanceVariables46.Value>();
    nullCell.put(42, null);
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceVariables46(nullCell, null));
    nullCell.clear();
    nullCell.put(null, new RogueResonanceVariables46.Value(0, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceVariables46(nullCell, null));
    for (var cell :
        new RogueResonanceVariables46.Value[] {
          new RogueResonanceVariables46.Value(1, 0, 0),
          new RogueResonanceVariables46.Value(0, -1, 0),
          new RogueResonanceVariables46.Value(0, 0, 1)
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new RogueResonanceVariables46(Map.of(42, cell), null));
    }
    var lookup =
        new RogueResonanceVariables46(
            Map.of(42, new RogueResonanceVariables46.Value(0, -ONE, ONE)), null);
    assertThrows(IllegalArgumentException.class, () -> lookup.write(42, 1));
    assertEquals(0, lookup.read(42));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueResonanceVariables46.RoundToIntTask(2791, "Extra_AttackTime"));
    for (String name : new String[] {null, "", "A\u0000B", "角色", "x".repeat(256)}) {
      assertThrows(IllegalArgumentException.class, () -> RogueResonanceVariables46.nameKey(name));
    }
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    assertThrows(IllegalArgumentException.class, () -> task.execute(null));
    assertEquals(0x7777, task.state());
  }

  @Test
  void signedStandardClampMatchesIndependentOrderedComparisons() {
    var random = new Random(46068532L);
    for (int i = 0; i < 4096; i++) {
      long raw = random.nextLong() & ~1L;
      long lower = random.nextLong() & ~1L;
      long upper = random.nextLong() & ~1L;
      long expected = raw < lower ? lower : raw > upper ? upper : raw;
      assertEquals(expected, RogueResonanceVariables46.clamp(raw, lower, upper));
    }
  }

  @Test
  void concurrentWritesHaveOrderedOldNewNotifications() throws Exception {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var lookup =
        new RogueResonanceVariables46(
            Map.of(42, new RogueResonanceVariables46.Value(0, 0, 2 * ONE)), changes::add);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return lookup.write(42, ONE);
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return lookup.write(42, 2 * ONE);
              });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertTrue(first.get(5, TimeUnit.SECONDS));
      assertTrue(second.get(5, TimeUnit.SECONDS));
    }
    assertEquals(2, changes.size());
    assertEquals(0, changes.getFirst().previous());
    assertEquals(changes.getFirst().current(), changes.getLast().previous());
    assertEquals(lookup.read(42), changes.getLast().current());
  }
}
