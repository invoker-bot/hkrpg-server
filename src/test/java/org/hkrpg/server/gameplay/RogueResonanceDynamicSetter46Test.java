package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RogueResonanceDynamicSetter46Test {
  private static final long ONE = 1L << 33;

  private static RogueResonanceDynamicCell46.Scalar fixed(long raw) {
    return RogueResonanceDynamicCell46.Scalar.fixed(raw);
  }

  private static RogueResonanceDynamicCell46.Scalar global(int key) {
    return RogueResonanceDynamicCell46.Scalar.fromPostfix(
        0, HexFormat.of().parseHex("010011"), new long[0], new int[] {key});
  }

  @Test
  void dynamicWriteReplacesPostfixWithFixedValue() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(1, ONE), null);
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            base, Map.of(2, new RogueResonanceDynamicCell46(global(1), null, null)));
    assertTrue(assertDoesNotThrow(() -> lookup.write(2, 3 * ONE)));
    lookup.write(1, 7 * ONE);
    assertEquals(3 * ONE, lookup.read(2));
  }

  @Test
  void clippedDynamicWriteNotifiesWithGetterValues() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(), changes::add);
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            base, Map.of(2, new RogueResonanceDynamicCell46(fixed(ONE), fixed(0), fixed(2 * ONE))));
    assertTrue(assertDoesNotThrow(() -> lookup.write(2, 4 * ONE)));
    assertEquals(List.of(new RogueResonanceVariables46.Change(2, ONE, 2 * ONE)), changes);
  }

  @Test
  void existingRoundTaskWritesTheSelectedDynamicCell() {
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), null),
            Map.of(
                RogueResonanceVariables46.nameKey("Count"),
                new RogueResonanceDynamicCell46(fixed(7 * ONE / 2), null, null)));
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Count");
    assertDoesNotThrow(() -> task.execute(lookup));
    assertEquals(4 * ONE, lookup.read(RogueResonanceVariables46.nameKey("Count")));
  }

  private static RogueResonanceDynamicCell46 cell(long raw) {
    return new RogueResonanceDynamicCell46(fixed(raw), null, null);
  }

  @Test
  void unchangedWriteStillNotifiesAndNullValueStartsAtZero() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), changes::add),
            Map.of(2, new RogueResonanceDynamicCell46(null, null, null)));
    lookup.write(2, 0);
    lookup.write(2, ONE);
    assertEquals(
        List.of(
            new RogueResonanceVariables46.Change(2, 0, 0),
            new RogueResonanceVariables46.Change(2, 0, ONE)),
        changes);
  }

  @Test
  void previousNotificationValueIsTheClippedGetterValue() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), changes::add),
            Map.of(2, new RogueResonanceDynamicCell46(fixed(3 * ONE), fixed(0), fixed(2 * ONE))));
    lookup.write(2, 0);
    assertEquals(List.of(new RogueResonanceVariables46.Change(2, 2 * ONE, 0)), changes);
  }

  @Test
  void dynamicBoundsKeepEvaluatingAfterValueIsReplaced() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(1, 5 * ONE), null);
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            base, Map.of(2, new RogueResonanceDynamicCell46(fixed(ONE), null, global(1))));
    lookup.write(2, 4 * ONE);
    base.write(1, 2 * ONE);
    assertEquals(2 * ONE, lookup.read(2));
    base.write(1, 5 * ONE);
    assertEquals(4 * ONE, lookup.read(2));
  }

  @Test
  void invertedRangesKeepLowerFirstClippingOnSetterAndGetter() {
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), null),
            Map.of(2, new RogueResonanceDynamicCell46(fixed(0), fixed(5 * ONE), fixed(2 * ONE))));
    lookup.write(2, 3 * ONE);
    // Setter stores five, but the getter's second pass sees equality to the lower bound
    // and then clips to two. The subsequent getter follows that same native lower-first rule.
    assertEquals(2 * ONE, lookup.read(2));
  }

  @Test
  void defaultExtendedRangesAcceptBothStandardEndpointWords() {
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), null), Map.of(2, cell(0)));
    lookup.write(2, Long.MIN_VALUE);
    assertEquals(Long.MIN_VALUE, lookup.read(2));
    lookup.write(2, Long.MAX_VALUE - 1);
    assertEquals(Long.MAX_VALUE - 1, lookup.read(2));
  }

  @Test
  void oddInputIsRejectedWithoutReplacingValueOrNotifying() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), changes::add),
            Map.of(2, cell(ONE)));
    assertThrows(IllegalArgumentException.class, () -> lookup.write(2, 1));
    assertEquals(ONE, lookup.read(2));
    assertTrue(changes.isEmpty());
  }

  private static RogueResonanceDynamicCell46.Scalar reciprocal(int key) {
    return RogueResonanceDynamicCell46.Scalar.fromPostfix(
        0, HexFormat.of().parseHex("000001000511"), new long[] {ONE}, new int[] {key});
  }

  @Test
  void failingPreviousGetterLeavesTheOriginalPostfixIntact() {
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(1, 0L), null);
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            base, Map.of(2, new RogueResonanceDynamicCell46(reciprocal(1), null, null)));
    assertThrows(IllegalArgumentException.class, () -> lookup.write(2, 3 * ONE));
    base.write(1, ONE);
    assertEquals(ONE, lookup.read(2));
    base.write(1, ONE / 2);
    assertEquals(2 * ONE, lookup.read(2));
  }

  @Test
  void failingBoundsLeaveValueAndObserverUntouched() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(1, 0L), changes::add);
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            base, Map.of(2, new RogueResonanceDynamicCell46(fixed(ONE), null, reciprocal(1))));
    assertThrows(IllegalArgumentException.class, () -> lookup.write(2, 3 * ONE));
    assertTrue(changes.isEmpty());
    base.write(1, ONE / 4);
    assertEquals(ONE, lookup.read(2));
  }

  @Test
  void observerExceptionKeepsWrittenValueAndRoundLifecycleRemainsIncomplete() {
    int key = RogueResonanceVariables46.nameKey("Count");
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(
                Map.of(),
                change -> {
                  throw new IllegalStateException("Notification failed");
                }),
            Map.of(key, cell(7 * ONE / 2)));
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Count");
    assertThrows(IllegalStateException.class, () -> task.execute(lookup));
    assertEquals(4 * ONE, lookup.read(key));
    assertEquals(0x7777, task.state());
  }

  @Test
  void observerSeesMutationAndMayReenterToUpdateALocalBound() {
    var ref = new AtomicReference<RogueResonanceVariables46>();
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var base =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(1, 3 * ONE),
            change -> {
              changes.add(change);
              if (change.key() == 2) {
                assertEquals(2 * ONE, ref.get().read(2));
                ref.get().write(1, ONE);
              }
            });
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            base, Map.of(2, new RogueResonanceDynamicCell46(fixed(0), null, global(1))));
    ref.set(lookup);
    lookup.write(2, 2 * ONE);
    assertEquals(2 * ONE, changes.getFirst().current());
    assertEquals(ONE, lookup.read(2));
    assertEquals(2, changes.size());
  }

  @Test
  void copiedCellTemplatesHaveIndependentWrittenValuesAcrossLookups() {
    var template = cell(ONE);
    var first =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), null), Map.of(2, template));
    var second =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), null), Map.of(2, template));
    first.write(2, 3 * ONE);
    assertEquals(ONE, second.read(2));
    assertEquals(ONE, template.read(null));
    assertEquals(3 * ONE, first.snapshot().get(2));
  }

  @Test
  void tagProviderAffectsBoundsButNotificationReadsTheCellInsteadOfItsOwnTagStack() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(1, ONE), changes::add),
            Map.of(2, new RogueResonanceDynamicCell46(fixed(0), global(1), null)));
    var tagged =
        RogueResonanceVariables46.withAiTagProjection(
            lookup, Map.of(1, List.of(5 * ONE), 2, List.of(9 * ONE)));
    tagged.write(2, 0);
    assertEquals(List.of(new RogueResonanceVariables46.Change(2, 5 * ONE, 5 * ONE)), changes);
    assertEquals(9 * ONE, tagged.readNativeFixed(2));
    assertEquals(5 * ONE, tagged.read(2));
  }

  @Test
  void nativeRoundUsesTagTopButWritesTheUnderlyingDynamicCell() {
    String name = "Count";
    int key = RogueResonanceVariables46.nameKey(name);
    var lookup =
        RogueResonanceVariables46.withDynamicCells(
            RogueResonanceVariables46.fromSourceDefaults(Map.of(), null), Map.of(key, cell(ONE)));
    var tagged =
        RogueResonanceVariables46.withAiTagProjection(lookup, Map.of(key, List.of(7 * ONE / 2)));
    var context = new RogueResonanceContext46(null, List.of(), tagged, null);
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, name);
    task.executeInContext(context, 1);
    assertEquals(4 * ONE, tagged.read(key));
    assertEquals(7 * ONE / 2, tagged.readNativeFixed(key));
    assertEquals(0x9999, task.state());
  }

  @Test
  void roundDoesNotAcquireAViewMonitorWhileAnotherWriterReentersTheBase() throws Exception {
    int key = RogueResonanceVariables46.nameKey("Count");
    var base = RogueResonanceVariables46.fromSourceDefaults(Map.of(), null);
    var lookup = RogueResonanceVariables46.withDynamicCells(base, Map.of(key, cell(ONE)));
    var owned = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var first =
        Thread.ofPlatform()
            .daemon()
            .start(
                () -> {
                  try {
                    synchronized (base) {
                      owned.countDown();
                      if (!proceed.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("No reentry trigger");
                      new RogueResonanceVariables46.RoundToIntTask(1221, "Count").execute(lookup);
                    }
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                });
    assertTrue(owned.await(2, TimeUnit.SECONDS));
    var second =
        Thread.ofPlatform()
            .daemon()
            .start(
                () -> {
                  try {
                    new RogueResonanceVariables46.RoundToIntTask(1221, "Count").execute(lookup);
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                });
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (second.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
        Thread.sleep(1);
      assertEquals(Thread.State.BLOCKED, second.getState());
      proceed.countDown();
      first.join(2000);
      second.join(2000);
      assertFalse(first.isAlive(), "Reentry blocked on a view monitor");
      assertFalse(second.isAlive(), "Writer remained blocked");
      assertNull(failure.get());
    } finally {
      proceed.countDown();
    }
  }
}
