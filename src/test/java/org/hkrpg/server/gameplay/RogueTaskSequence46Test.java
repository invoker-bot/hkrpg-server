package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueTaskSequence46Test {
  private static class Child implements RogueTaskSequence46.Task {
    int state = RogueTaskSequence46.READY;
    Consumer<Child> begin = child -> child.state = RogueTaskSequence46.SUCCESS;
    Consumer<Float> tick = delta -> {};
    Runnable reset = () -> state = RogueTaskSequence46.READY;
    Runnable dispose = () -> {};

    public int state() {
      return state;
    }

    public void begin() {
      begin.accept(this);
    }

    public void tick(float delta) {
      tick.accept(delta);
    }

    public void reset() {
      reset.run();
    }

    public void dispose() {
      dispose.run();
    }
  }

  private static final class Skipped extends Child
      implements RogueTaskSequence46.UnstartedFailureTask {
    int notifications;
    Runnable notification = () -> {};

    public void onUnstartedFailure() {
      notifications++;
      notification.run();
    }
  }

  @Test
  void waitingChildGetsZeroTickAndNextChildStartsInTheSameBeginPass() {
    var order = new ArrayList<String>();
    var first = new Child();
    first.begin =
        c -> {
          order.add("begin-first");
          c.state = RogueTaskSequence46.EXECUTING;
        };
    first.tick =
        delta -> {
          assertEquals(0f, delta);
          order.add("tick-first");
          first.state = RogueTaskSequence46.SUCCESS;
        };
    var next = new Child();
    next.begin =
        c -> {
          order.add("begin-next");
          c.state = RogueTaskSequence46.SUCCESS;
        };
    var sequence = new RogueTaskSequence46.Sequence(List.of(first, next), false);
    sequence.begin();
    assertEquals(List.of("begin-first", "tick-first", "begin-next"), order);
    assertEquals(RogueTaskSequence46.SUCCESS, sequence.state());
  }

  @Test
  void failureDuringChildTickPropagatesOnTheNextPassAndOnlyThenNotifiesLaterReadyTasks() {
    var first = new Child();
    first.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    first.tick = delta -> first.state = RogueTaskSequence46.FAIL;
    var next = new Skipped();
    var sequence = new RogueTaskSequence46.Sequence(List.of(first, next), false);
    sequence.begin();
    assertEquals(RogueTaskSequence46.EXECUTING, sequence.state());
    assertEquals(0, next.notifications);
    sequence.tick(0.25f);
    assertEquals(RogueTaskSequence46.FAIL, sequence.state());
    assertEquals(1, next.notifications);
    assertEquals(RogueTaskSequence46.READY, next.state());
  }

  @Test
  void immediateFailureOnlyNotifiesLaterReadyTasksWithTheMatchedInterface() {
    var first = new Child();
    first.begin = c -> c.state = RogueTaskSequence46.FAIL;
    var ready = new Skipped();
    var completed = new Skipped();
    completed.state = RogueTaskSequence46.SUCCESS;
    var regular = new Child();
    regular.begin = c -> fail("Failure must not begin later regular child");
    var sequence =
        new RogueTaskSequence46.Sequence(List.of(first, ready, completed, regular), false);
    sequence.begin();
    assertEquals(RogueTaskSequence46.FAIL, sequence.state());
    assertEquals(1, ready.notifications);
    assertEquals(0, completed.notifications);
    assertEquals(RogueTaskSequence46.READY, regular.state());
  }

  private static final class Immediate extends Child implements RogueTaskSequence46.ImmediateTask {
    final List<Float> deltas = new ArrayList<>();

    public void finishImmediately(float delta) {
      deltas.add(delta);
      state = RogueTaskSequence46.SUCCESS;
    }
  }

  @Test
  void emptyAndAlreadyCompletedChildrenCompleteWithoutBeginningOrTicking() {
    var child = new Child();
    child.state = RogueTaskSequence46.SUCCESS;
    child.begin = c -> fail("Completed child must not begin");
    child.tick = delta -> fail("Completed child must not tick");
    var sequence = new RogueTaskSequence46.Sequence(List.of(child), false);
    sequence.begin();
    assertEquals(RogueTaskSequence46.SUCCESS, sequence.state());
    sequence.tick(Float.NaN);
    assertEquals(RogueTaskSequence46.SUCCESS, sequence.state());
    sequence = new RogueTaskSequence46.Sequence(List.of(), false);
    sequence.begin();
    assertEquals(RogueTaskSequence46.SUCCESS, sequence.state());
  }

  @Test
  void waitingChildGetsTheExactDeltaAndCompletedPrefixIsSkippedOnLaterPasses() {
    var order = new ArrayList<Float>();
    var prefix = new Child();
    var first = new Child();
    first.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    first.tick =
        delta -> {
          order.add(delta);
          if (delta > 0) first.state = RogueTaskSequence46.SUCCESS;
        };
    var next = new Child();
    var sequence = new RogueTaskSequence46.Sequence(List.of(prefix, first, next), false);
    sequence.begin();
    assertEquals(RogueTaskSequence46.EXECUTING, sequence.state());
    prefix.begin = c -> fail("Completed prefix cannot restart");
    sequence.tick(0.125f);
    assertEquals(List.of(0f, 0.125f), order);
    assertEquals(RogueTaskSequence46.SUCCESS, next.state());
    assertEquals(RogueTaskSequence46.SUCCESS, sequence.state());
  }

  @Test
  void performanceFlagUsesOnlyTheMatchedImmediateInterface() {
    var first = new Immediate();
    first.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    first.tick = delta -> fail("Matched immediate child must use immediate entry");
    var regular = new Child();
    regular.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    var deltas = new ArrayList<Float>();
    regular.tick =
        delta -> {
          deltas.add(delta);
          regular.state = RogueTaskSequence46.SUCCESS;
        };
    var sequence = new RogueTaskSequence46.Sequence(List.of(first, regular), true);
    sequence.begin();
    assertEquals(List.of(0f), first.deltas);
    assertEquals(List.of(0f), deltas);
    assertEquals(RogueTaskSequence46.SUCCESS, sequence.state());
  }

  @Test
  void immediateInterfaceWithoutPerformanceFlagUsesNormalTick() {
    var child = new Immediate();
    child.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    var deltas = new ArrayList<Float>();
    child.tick =
        delta -> {
          deltas.add(delta);
          child.state = RogueTaskSequence46.SUCCESS;
        };
    var sequence = new RogueTaskSequence46.Sequence(List.of(child), false);
    sequence.begin();
    assertTrue(child.deltas.isEmpty());
    assertEquals(List.of(0f), deltas);
  }

  @Test
  void parentTerminalStateDuringBeginStopsBeforeChildTickAndDoesNotNotifyLaterTasks() {
    var first = new Child();
    var skipped = new Skipped();
    var sequence = new RogueTaskSequence46.Sequence(List.of(first, skipped), false);
    first.begin =
        c -> {
          c.state = RogueTaskSequence46.EXECUTING;
          sequence.stopForChild(RogueTaskSequence46.FAIL);
        };
    first.tick = delta -> fail("Parent terminal state must stop before tick");
    sequence.begin();
    assertEquals(RogueTaskSequence46.FAIL, sequence.state());
    assertEquals(0, skipped.notifications);
    assertEquals(RogueTaskSequence46.READY, skipped.state());
  }

  @Test
  void parentFailureDuringNoncompletedTickNotifiesOnlyLaterReadyInterfaceChildren() {
    var first = new Child();
    var skipped = new Skipped();
    var sequence = new RogueTaskSequence46.Sequence(List.of(first, skipped), false);
    first.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    first.tick = delta -> sequence.stopForChild(RogueTaskSequence46.FAIL);
    sequence.begin();
    assertEquals(RogueTaskSequence46.FAIL, sequence.state());
    assertEquals(1, skipped.notifications);
  }

  @Test
  void completionAfterTickContinuesBeforeCheckingParentTerminalState() {
    var first = new Child();
    var next = new Child();
    var order = new ArrayList<String>();
    var sequence = new RogueTaskSequence46.Sequence(List.of(first, next), false);
    first.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    first.tick =
        delta -> {
          sequence.stopForChild(RogueTaskSequence46.FAIL);
          first.state = RogueTaskSequence46.SUCCESS;
        };
    next.begin =
        c -> {
          order.add("next-begin");
          c.state = RogueTaskSequence46.EXECUTING;
        };
    next.tick = delta -> fail("Parent fail is inspected after next begin, before next tick");
    sequence.begin();
    assertEquals(List.of("next-begin"), order);
    assertEquals(RogueTaskSequence46.FAIL, sequence.state());
  }

  @Test
  void allChildrenCompletedAfterTickSetsSuccessEvenIfChildAlsoSetParentFail() {
    var child = new Child();
    var sequence = new RogueTaskSequence46.Sequence(List.of(child), false);
    child.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    child.tick =
        delta -> {
          sequence.stopForChild(RogueTaskSequence46.FAIL);
          child.state = RogueTaskSequence46.SUCCESS;
        };
    sequence.begin();
    assertEquals(RogueTaskSequence46.SUCCESS, sequence.state());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 0x7777, 0x1234})
  void unsupportedOrUnchangedReadyStateAfterBeginThrowsInsteadOfInventingCompletion(int value) {
    var child = new Child();
    child.begin = c -> c.state = value;
    var sequence = new RogueTaskSequence46.Sequence(List.of(child), false);
    assertThrows(IllegalStateException.class, sequence::begin);
    assertEquals(RogueTaskSequence46.READY, sequence.state());
  }

  @Test
  void unknownStateAfterTickIsExaminedOnTheNextPass() {
    var child = new Child();
    child.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    child.tick = delta -> child.state = 123;
    var sequence = new RogueTaskSequence46.Sequence(List.of(child), false);
    sequence.begin();
    assertEquals(RogueTaskSequence46.EXECUTING, sequence.state());
    assertThrows(IllegalStateException.class, () -> sequence.tick(0.25f));
  }

  @Test
  void resetRunsAllChildrenInSourceOrderBeforeParentBecomesReady() {
    var first = new Child();
    var next = new Child();
    var order = new ArrayList<String>();
    var sequence = new RogueTaskSequence46.Sequence(List.of(first, next), false);
    sequence.begin();
    first.reset =
        () -> {
          assertEquals(RogueTaskSequence46.SUCCESS, sequence.state());
          first.state = RogueTaskSequence46.READY;
          order.add("first");
        };
    next.reset =
        () -> {
          assertEquals(RogueTaskSequence46.SUCCESS, sequence.state());
          next.state = RogueTaskSequence46.READY;
          order.add("next");
        };
    sequence.reset();
    assertEquals(List.of("first", "next"), order);
    assertEquals(RogueTaskSequence46.READY, sequence.state());
  }

  @Test
  void resetFailurePreservesEarlierChildChangesAndParentState() {
    var first = new Child();
    var next = new Child();
    var untouched = new Child();
    var sequence = new RogueTaskSequence46.Sequence(List.of(first, next, untouched), false);
    sequence.begin();
    var problem = new IllegalStateException("reset failed");
    next.reset =
        () -> {
          throw problem;
        };
    assertSame(problem, assertThrows(IllegalStateException.class, sequence::reset));
    assertEquals(RogueTaskSequence46.READY, first.state());
    assertEquals(RogueTaskSequence46.SUCCESS, next.state());
    assertEquals(RogueTaskSequence46.SUCCESS, untouched.state());
    assertEquals(RogueTaskSequence46.SUCCESS, sequence.state());
  }

  @Test
  void runOnceReturnsPreResetStateAndReportsWaitingBeforeReset() {
    var child = new Child();
    child.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    var sequence = new RogueTaskSequence46.Sequence(List.of(child), false);
    var reports = new ArrayList<Integer>();
    int result =
        sequence.runOnce(
            state -> {
              assertEquals(RogueTaskSequence46.EXECUTING, child.state());
              reports.add(state);
            });
    assertEquals(RogueTaskSequence46.EXECUTING, result);
    assertEquals(List.of(result), reports);
    assertEquals(RogueTaskSequence46.READY, sequence.state());
    assertEquals(RogueTaskSequence46.READY, child.state());
  }

  @ParameterizedTest
  @ValueSource(ints = {0x9999, 0xaaaa})
  void terminalRunOnceResultsDoNotEmitWaitingDiagnosticAndThenReset(int value) {
    var child = new Child();
    child.begin = c -> c.state = value;
    var sequence = new RogueTaskSequence46.Sequence(List.of(child), false);
    assertEquals(value, sequence.runOnce(state -> fail("Terminal result cannot report waiting")));
    assertEquals(RogueTaskSequence46.READY, sequence.state());
    assertEquals(RogueTaskSequence46.READY, child.state());
  }

  @Test
  void beginFailureInRunOnceHasNoImplicitResetFinally() {
    var child = new Child();
    var problem = new IllegalStateException("begin failed");
    child.begin =
        c -> {
          c.state = RogueTaskSequence46.EXECUTING;
          throw problem;
        };
    child.reset = () -> fail("Begin failure cannot automatically reset child");
    var sequence = new RogueTaskSequence46.Sequence(List.of(child), false);
    assertSame(
        problem, assertThrows(IllegalStateException.class, () -> sequence.runOnce(state -> {})));
    assertEquals(RogueTaskSequence46.EXECUTING, child.state());
    assertEquals(RogueTaskSequence46.READY, sequence.state());
  }

  @Test
  void waitingDiagnosticFailureStopsBeforeReset() {
    var child = new Child();
    child.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    child.reset = () -> fail("Diagnostic exception cannot be hidden by reset");
    var sequence = new RogueTaskSequence46.Sequence(List.of(child), false);
    var problem = new IllegalStateException("diagnostic failed");
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class,
            () ->
                sequence.runOnce(
                    state -> {
                      throw problem;
                    })));
    assertEquals(RogueTaskSequence46.EXECUTING, sequence.state());
  }

  @Test
  void nestedSequencesAdvanceChildrenAndResetTheirWholeTree() {
    var child = new Child();
    child.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    child.tick =
        delta -> {
          if (delta > 0) child.state = RogueTaskSequence46.SUCCESS;
        };
    var inner = new RogueTaskSequence46.Sequence(List.of(child), false);
    var outer = new RogueTaskSequence46.Sequence(List.of(inner), false);
    outer.begin();
    assertEquals(RogueTaskSequence46.EXECUTING, outer.state());
    outer.tick(0.5f);
    assertEquals(RogueTaskSequence46.SUCCESS, outer.state());
    assertEquals(RogueTaskSequence46.SUCCESS, inner.state());
    outer.reset();
    assertEquals(RogueTaskSequence46.READY, inner.state());
    assertEquals(RogueTaskSequence46.READY, child.state());
  }

  @Test
  void callbackAdapterRunsBoundTasksInsideTheActualExplicitEntityFrameAndThenResets() {
    var frames = new RogueBattleEventLifecycle46.Frames(List.of());
    var entity = new Object();
    var order = new ArrayList<String>();
    var child = new Child();
    child.begin =
        c -> {
          assertSame(entity, frames.current().eventEntity());
          order.add("before");
          c.state = RogueTaskSequence46.EXECUTING;
        };
    child.tick =
        delta -> {
          assertEquals(0f, delta);
          assertSame(entity, frames.current().eventEntity());
          order.add("tick");
          child.state = RogueTaskSequence46.SUCCESS;
        };
    var sequence = new RogueTaskSequence46.Sequence(List.of(child), false);
    var callback =
        RogueTaskSequence46.callback(sequence, state -> fail("This callback must finish"));
    var config =
        new RogueBattleEventLifecycle46.Configuration(
            RogueResonanceDynamicCell46.Scalar.fixed(41007L << 33), List.of(), callback, null);
    RogueResonanceLookup46 lookup =
        (scope, key) -> {
          throw new IllegalArgumentException("No implicit source");
        };
    var task = new RogueBattleEventLifecycle46.Task(config, lookup, frames);
    var row =
        new RogueBattleEventTable46.Config(
            41007, 3, 3, 0, 0, "event", null, List.of(), List.of(), 100L << 33, false, false, null,
            null, List.of(), null, 0, 0, 1);
    task.begin(
        Map.of(41007, row),
        0,
        null,
        (id, p, values, before) -> {
          before.accept(entity);
          order.add("setup");
          return entity;
        });
    assertEquals(List.of("before", "tick", "setup"), order);
    assertNull(frames.current());
    assertEquals(RogueTaskSequence46.READY, sequence.state());
    assertEquals(RogueTaskSequence46.READY, child.state());
    task.dispose();
    assertTrue(sequence.children().isEmpty());
  }

  @Test
  void failureNotificationExceptionStopsLaterNotificationsAndLeavesParentFail() {
    var first = new Child();
    first.begin = c -> c.state = RogueTaskSequence46.FAIL;
    var problem = new IllegalStateException("skipped notification failed");
    var skipped = new Skipped();
    skipped.notification =
        () -> {
          throw problem;
        };
    var later = new Skipped();
    var sequence = new RogueTaskSequence46.Sequence(List.of(first, skipped, later), false);
    assertSame(problem, assertThrows(IllegalStateException.class, sequence::begin));
    assertEquals(RogueTaskSequence46.FAIL, sequence.state());
    assertEquals(1, skipped.notifications);
    assertEquals(0, later.notifications);
  }

  @Test
  void disposeReleasesChildrenInOrderAndInvalidatesOnlyAfterAllSucceed() {
    var first = new Child();
    var next = new Child();
    var order = new ArrayList<String>();
    var sequence = new RogueTaskSequence46.Sequence(List.of(first, next), false);
    first.dispose = () -> order.add("first");
    next.dispose = () -> order.add("next");
    sequence.dispose();
    sequence.dispose();
    assertEquals(List.of("first", "next"), order);
    assertTrue(sequence.children().isEmpty());
    assertEquals(RogueTaskSequence46.READY, sequence.state());
    assertThrows(IllegalStateException.class, sequence::begin);
    assertThrows(IllegalStateException.class, sequence::reset);
  }

  @Test
  void disposeFailureRetainsAllBindingsForExplicitRetryAndDoesNotReleaseLaterChildren() {
    var first = new Child();
    var next = new Child();
    var order = new ArrayList<String>();
    var problem = new IllegalStateException("dispose failed");
    first.dispose =
        () -> {
          order.add("first");
          throw problem;
        };
    next.dispose = () -> order.add("next");
    var sequence = new RogueTaskSequence46.Sequence(List.of(first, next), false);
    assertSame(problem, assertThrows(IllegalStateException.class, sequence::dispose));
    assertEquals(List.of("first"), order);
    assertEquals(List.of(first, next), sequence.children());
    first.dispose = () -> order.add("first-retry");
    sequence.dispose();
    assertEquals(List.of("first", "first-retry", "next"), order);
    assertTrue(sequence.children().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(floats = {-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
  void activeWaitingSequenceRejectsUnsupportedDeltaBeforeChildRuns(float value) {
    var child = new Child();
    child.begin = c -> c.state = RogueTaskSequence46.EXECUTING;
    var sequence = new RogueTaskSequence46.Sequence(List.of(child), false);
    sequence.begin();
    child.tick = delta -> fail("Invalid finite delta cannot reach child");
    assertThrows(IllegalArgumentException.class, () -> sequence.tick(value));
    assertEquals(RogueTaskSequence46.EXECUTING, sequence.state());
  }

  @Test
  void constructionCopiesChildrenAndRejectsMissingOrExcessiveBindings() {
    var input = new ArrayList<Child>();
    var child = new Child();
    input.add(child);
    var sequence = new RogueTaskSequence46.Sequence(input, false);
    input.clear();
    assertEquals(List.of(child), sequence.children());
    assertThrows(UnsupportedOperationException.class, () -> sequence.children().clear());
    assertThrows(
        IllegalArgumentException.class, () -> new RogueTaskSequence46.Sequence(null, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueTaskSequence46.Sequence(java.util.Collections.nCopies(513, child), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueTaskSequence46.Sequence(java.util.Arrays.asList(child, null), false));
    assertThrows(IllegalArgumentException.class, () -> sequence.runOnce(null));
    assertThrows(
        IllegalArgumentException.class, () -> sequence.stopForChild(RogueTaskSequence46.READY));
    assertThrows(
        IllegalArgumentException.class, () -> RogueTaskSequence46.callback(null, state -> {}));
    assertThrows(
        IllegalArgumentException.class, () -> RogueTaskSequence46.callback(sequence, null));
  }
}
