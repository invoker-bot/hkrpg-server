package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RogueResonanceTaskCell46Test {
  private static final long ONE = 1L << 33;

  @Test
  void getterExecutesBoundTaskAndCachesItsResult() {
    var identity = new Object();
    var cell = new RogueResonanceTaskCell46(identity, null, null, null);
    assertEquals(
        2 * ONE,
        cell.read(
            key -> 0,
            (task, key) -> {
              assertSame(identity, task);
              assertEquals(-1228589475, key);
              return 2 * ONE;
            }));
    assertEquals(2 * ONE, cell.read(key -> 0, null));
  }

  @Test
  void getterAdsorbsBeforeApplyingLimits() {
    var cell =
        new RogueResonanceTaskCell46(
            new Object(), null, null, new RogueResonanceAdsorption46.Config(ONE / 16, 1));
    assertEquals(9448928052L, cell.read(key -> 0, (task, key) -> ONE + ONE / 16));
  }

  @Test
  void lookupTaskWriteIgnoresAssignedValueAndNotifiesSecondGetterResult() {
    var calls = new int[] {0};
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var lookup =
        RogueResonanceVariables46.withProjectedCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), changes::add),
            Map.of(2, new RogueResonanceTaskCell46(new Object(), null, null, null)),
            (task, key) -> ++calls[0] * ONE);
    assertTrue(lookup.write(2, 50 * ONE));
    assertEquals(2, calls[0]);
    assertEquals(List.of(new RogueResonanceVariables46.Change(2, ONE, 2 * ONE)), changes);
  }

  private static RogueResonanceDynamicCell46.Scalar fixed(long value) {
    return RogueResonanceDynamicCell46.Scalar.fixed(value);
  }

  private static RogueResonanceDynamicCell46.Scalar global(int key) {
    return RogueResonanceDynamicCell46.Scalar.fromPostfix(
        0, new byte[] {1, 0, 17}, new long[0], new int[] {key});
  }

  @Test
  void taskThenBothBoundsAreEvaluatedInOrder() {
    var order = new ArrayList<String>();
    var cell = new RogueResonanceTaskCell46(new Object(), global(1), global(2), null);
    assertEquals(
        5 * ONE,
        cell.read(
            key -> {
              order.add("bound" + key);
              return key == 1 ? 5 * ONE : 6 * ONE;
            },
            (task, key) -> {
              order.add("task");
              return ONE;
            }));
    assertEquals(List.of("task", "bound1", "bound2"), order);
  }

  @Test
  void missingProviderSkipsTaskAdsorptionAndLimits() {
    var cell =
        new RogueResonanceTaskCell46(
            new Object(),
            global(1),
            global(2),
            new RogueResonanceAdsorption46.Config(1000 * ONE, 9));
    assertEquals(
        0,
        cell.read(
            null,
            (task, key) -> {
              fail("missing provider");
              return ONE;
            }));
  }

  @Test
  void missingAliasEvaluatorReturnsCacheWithoutReapplyingChangedLimits() {
    var cell = new RogueResonanceTaskCell46(new Object(), global(1), null, null);
    assertEquals(2 * ONE, cell.read(key -> 0, (task, key) -> 2 * ONE));
    assertEquals(
        2 * ONE,
        cell.read(
            key -> {
              fail("cached getter must not evaluate bounds");
              return 3 * ONE;
            },
            null));
  }

  @Test
  void everyBoundGetterReexecutesTask() {
    var n = new AtomicInteger();
    var cell = new RogueResonanceTaskCell46(new Object(), null, null, null);
    assertEquals(ONE, cell.read(key -> 0, (task, key) -> n.incrementAndGet() * ONE));
    assertEquals(2 * ONE, cell.read(key -> 0, (task, key) -> n.incrementAndGet() * ONE));
  }

  @Test
  void taskFailureRetainsPreviouslyCachedValue() {
    var cell = new RogueResonanceTaskCell46(new Object(), null, null, null);
    cell.read(key -> 0, (task, key) -> 2 * ONE);
    assertThrows(
        IllegalStateException.class,
        () ->
            cell.read(
                key -> 0,
                (task, key) -> {
                  throw new IllegalStateException("task");
                }));
    assertEquals(2 * ONE, cell.read(null, null));
  }

  @Test
  void adsorptionFailureDoesNotCommitTaskResultToCache() {
    var cell =
        new RogueResonanceTaskCell46(
            new Object(), null, null, new RogueResonanceAdsorption46.Config(1000 * ONE, 9));
    assertThrows(IllegalArgumentException.class, () -> cell.read(key -> 0, (task, key) -> 2 * ONE));
    assertEquals(0, cell.read(null, null));
  }

  @Test
  void upperBoundFailureLeavesCacheButKeepsTaskSideEffects() {
    var calls = new AtomicInteger();
    var cell = new RogueResonanceTaskCell46(new Object(), global(1), global(2), null);
    cell.read(key -> key * ONE, (task, key) -> ONE);
    assertThrows(
        IllegalStateException.class,
        () ->
            cell.read(
                key -> {
                  if (key == 2) throw new IllegalStateException("upper");
                  return 3 * ONE;
                },
                (task, key) -> {
                  calls.incrementAndGet();
                  return 5 * ONE;
                }));
    assertEquals(1, calls.get());
    assertEquals(ONE, cell.read(null, null));
  }

  @Test
  void invertedLimitsPreserveLowerFirstSourceBranch() {
    var cell = new RogueResonanceTaskCell46(new Object(), fixed(5 * ONE), fixed(2 * ONE), null);
    assertEquals(5 * ONE, cell.read(key -> 0, (task, key) -> 0));
    assertEquals(2 * ONE, cell.read(key -> 0, (task, key) -> 10 * ONE));
  }

  @Test
  void directSetterDoesNotReplaceTaskOrCache() {
    var cell = new RogueResonanceTaskCell46(new Object(), null, null, null);
    cell.read(key -> 0, (task, key) -> 2 * ONE);
    cell.replaceValue(50 * ONE);
    assertEquals(2 * ONE, cell.read(null, null));
    assertEquals(3 * ONE, cell.read(key -> 0, (task, key) -> 3 * ONE));
  }

  @Test
  void copiedCellRetainsIndependentCacheAndSameTaskIdentity() {
    var identity = new Object();
    var original = new RogueResonanceTaskCell46(identity, null, null, null);
    original.read(key -> 0, (task, key) -> 2 * ONE);
    var copy = original.copy();
    assertEquals(2 * ONE, copy.read(null, null));
    copy.read(
        key -> 0,
        (task, key) -> {
          assertSame(identity, task);
          return 3 * ONE;
        });
    assertEquals(2 * ONE, original.read(null, null));
    assertEquals(3 * ONE, copy.read(null, null));
  }

  @Test
  void mixedProjectionKeepsOrdinaryDynamicAndTaskCellsDistinct() {
    var calls = new AtomicInteger();
    var l =
        RogueResonanceVariables46.withProjectedCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(1, ONE), null),
            Map.of(
                2,
                new RogueResonanceTaskCell46(new Object(), null, null, null),
                3,
                new RogueResonanceDynamicCell46(global(1), null, null)),
            (task, key) -> calls.incrementAndGet() * 2 * ONE);
    assertEquals(ONE, l.read(3));
    assertEquals(0, calls.get());
    assertEquals(2 * ONE, l.read(2));
    l.write(3, 4 * ONE);
    assertEquals(4 * ONE, l.read(3));
    assertEquals(1, calls.get());
  }

  @Test
  void absentObserverSkipsSecondTaskGetterDuringWrite() {
    var calls = new AtomicInteger();
    var l =
        RogueResonanceVariables46.withProjectedCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), null),
            Map.of(2, new RogueResonanceTaskCell46(new Object(), null, null, null)),
            (task, key) -> calls.incrementAndGet() * ONE);
    l.write(2, 50 * ONE);
    assertEquals(1, calls.get());
  }

  @Test
  void roundCompletesEvenThoughTaskSetterIgnoresRoundedInput() {
    int k = RogueResonanceVariables46.nameKey("Count");
    var calls = new AtomicInteger();
    var l =
        RogueResonanceVariables46.withProjectedCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), null),
            Map.of(k, new RogueResonanceTaskCell46(new Object(), null, null, null)),
            (task, key) -> {
              calls.incrementAndGet();
              return 7 * ONE / 2;
            });
    var round = new RogueResonanceVariables46.RoundToIntTask(1221, "Count");
    round.execute(l);
    assertEquals(0x9999, round.state());
    assertEquals(2, calls.get());
    assertEquals(7 * ONE / 2, l.read(k));
  }

  @Test
  void aiTagBoundsUseRootProviderAndNotificationsUseTaskGetter() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var base =
        RogueResonanceVariables46.withProjectedCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(1, ONE), changes::add),
            Map.of(2, new RogueResonanceTaskCell46(new Object(), null, global(1), null)),
            (task, key) -> 10 * ONE);
    var tags =
        RogueResonanceVariables46.withAiTagProjection(
            base, Map.of(1, List.of(5 * ONE), 2, List.of(9 * ONE)));
    tags.write(2, 20 * ONE);
    assertEquals(List.of(new RogueResonanceVariables46.Change(2, 5 * ONE, 5 * ONE)), changes);
    assertEquals(9 * ONE, tags.readNativeFixed(2));
  }

  @Test
  void failureAfterOldGetterKeepsThatGettersCacheUpdate() {
    var reads = new AtomicInteger();
    var cell = new RogueResonanceTaskCell46(new Object(), global(1), null, null);
    assertEquals(5 * ONE, cell.read(key -> 0, (task, key) -> 5 * ONE));
    assertThrows(
        IllegalStateException.class,
        () ->
            cell.clip(
                50 * ONE,
                key -> {
                  reads.incrementAndGet();
                  throw new IllegalStateException("write minimum");
                }));
    assertEquals(1, reads.get());
    assertEquals(5 * ONE, cell.read(null, null));
  }

  @Test
  void snapshotEvaluatesTaskCellsAndKeepsFixedBaseSeparate() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(2, ONE), null);
    var l =
        RogueResonanceVariables46.withProjectedCells(
            base,
            Map.of(2, new RogueResonanceTaskCell46(new Object(), null, null, null)),
            (task, key) -> 2 * ONE);
    assertEquals(Map.of(2, 2 * ONE), l.snapshot());
    assertEquals(ONE, base.read(2));
  }

  @Test
  void cyclicTaskCallbackIsRejectedAndReadGuardRecovers() {
    var ref = new AtomicReference<RogueResonanceVariables46>();
    var recurse = new boolean[] {true};
    var l =
        RogueResonanceVariables46.withProjectedCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), null),
            Map.of(2, new RogueResonanceTaskCell46(new Object(), null, null, null)),
            (task, key) -> recurse[0] ? ref.get().read(2) : ONE);
    ref.set(l);
    assertThrows(IllegalArgumentException.class, () -> l.read(2));
    recurse[0] = false;
    assertEquals(ONE, l.read(2));
  }

  @Test
  void extendedAliasResultIsRejectedBeforeCacheAssignment() {
    var cell = new RogueResonanceTaskCell46(new Object(), null, null, null);
    assertThrows(IllegalArgumentException.class, () -> cell.read(key -> 0, (task, key) -> 1));
    assertEquals(0, cell.read(null, null));
  }
}
