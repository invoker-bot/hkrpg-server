package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

class RogueModifierSequences46Test {
  static final int READY = RogueTaskSequence46.READY,
      EXEC = RogueTaskSequence46.EXECUTING,
      SUCCESS = RogueTaskSequence46.SUCCESS,
      FAIL = RogueTaskSequence46.FAIL;
  static final Object ABILITY = new Object(), CONTEXT = new Object(), OTHER = new Object();

  static RogueModifierSequences46.State state() {
    var life = new RogueModifierLifetime46.State(new Object(), 5, 2);
    var modifier = new RogueModifierStackMode46.State(life, 7, 2);
    return new RogueModifierSequences46.State(
        new RogueModifierReplace46.State(new RogueModifierValueBinding46.State(modifier)));
  }

  static class Sequence implements RogueModifierSequences46.Sequence {
    int state = READY, steps, resets;
    IntUnaryOperator outcome = i -> EXEC;
    Runnable onReset = () -> {};
    final List<Float> deltas = new ArrayList<>();

    public int state() {
      return state;
    }

    public void state(int value) {
      state = value;
    }

    public int step(float delta) {
      deltas.add(delta);
      return outcome.applyAsInt(++steps);
    }

    public void reset() {
      resets++;
      onReset.run();
    }
  }

  static class World implements RogueModifierSequences46.World {
    final List<String> order = new ArrayList<>();
    Object context = CONTEXT, ability;
    boolean flushOnEnd;
    Runnable onReplace = () -> {}, onChanged = () -> {};

    public void sequenceEnded(RogueModifierSequences46.State state) {
      order.add("end");
      if (flushOnEnd) RogueModifierSequences46.endAndFlush(state, this);
    }

    public Object taskContext(RogueModifierSequences46.State state) {
      order.add("context");
      return context;
    }

    public void replaceContextAbility(Object context, Object ability) {
      assertSame(this.context, context);
      this.ability = ability;
      order.add("ability");
      onReplace.run();
    }

    public void afterAbilityVirtual(RogueModifierSequences46.State state) {
      order.add("changed");
      onChanged.run();
    }
  }

  @Test
  void nullStartSequenceDoesNotReadWorldOrIncrementCounters() {
    var s = state();
    RogueModifierSequences46.start(s, null, null);
    assertEquals(0, s.replacement.executingSequenceCount);
    assertEquals(0, s.tickingSequenceCount);
  }

  @Test
  void startingWaitStepsTwiceWithZeroDeltaAndKeepsBothCounters() {
    var s = state();
    var sequence = new Sequence();
    var world = new World();
    RogueModifierSequences46.start(s, sequence, world);
    assertEquals(List.of(0f, 0f), sequence.deltas);
    assertEquals(EXEC, sequence.state);
    assertEquals(1, s.replacement.executingSequenceCount);
    assertEquals(1, s.tickingSequenceCount);
    assertTrue(world.order.isEmpty());
  }

  @Test
  void successfulInitialStepResetsAndCallsEndWithoutAnotherStep() {
    var s = state();
    var sequence = new Sequence();
    sequence.outcome = i -> SUCCESS;
    var world = new World();
    RogueModifierSequences46.start(s, sequence, world);
    assertEquals(1, sequence.steps);
    assertEquals(1, sequence.resets);
    assertEquals(READY, sequence.state);
    assertEquals(0, s.tickingSequenceCount);
    assertEquals(1, s.replacement.executingSequenceCount);
    assertEquals(List.of("end"), world.order);
  }

  @Test
  void failedInitialStepFollowsTheSameResetAndEndBranch() {
    var s = state();
    var sequence = new Sequence();
    sequence.outcome = i -> FAIL;
    var world = new World();
    world.flushOnEnd = true;
    RogueModifierSequences46.start(s, sequence, world);
    assertEquals(1, sequence.steps);
    assertEquals(READY, sequence.state);
    assertEquals(0, s.replacement.executingSequenceCount);
    assertEquals(0, s.tickingSequenceCount);
  }

  @Test
  void unknownInitialResultIsNormalizedToExecutingBeforeSecondStep() {
    var s = state();
    var sequence = new Sequence();
    sequence.outcome = i -> i == 1 ? -77 : SUCCESS;
    RogueModifierSequences46.start(s, sequence, new World());
    assertEquals(2, sequence.steps);
    assertEquals(1, sequence.resets);
    assertEquals(READY, sequence.state);
  }

  @Test
  void counterIncrementWrapsBeforeFirstStep() {
    var s = state();
    s.replacement.executingSequenceCount = Integer.MAX_VALUE;
    s.tickingSequenceCount = Integer.MAX_VALUE;
    var sequence = new Sequence();
    sequence.outcome =
        i -> {
          assertEquals(Integer.MIN_VALUE, s.replacement.executingSequenceCount);
          assertEquals(Integer.MIN_VALUE, s.tickingSequenceCount);
          return EXEC;
        };
    RogueModifierSequences46.start(s, sequence, null);
    assertEquals(2, sequence.steps);
  }

  @Test
  void firstStepFaultKeepsIncrementsAndOldSequenceState() {
    var s = state();
    var sequence = new Sequence();
    sequence.outcome =
        i -> {
          throw new IllegalStateException("step");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueModifierSequences46.start(s, sequence, null));
    assertEquals(1, s.tickingSequenceCount);
    assertEquals(1, s.replacement.executingSequenceCount);
    assertEquals(READY, sequence.state);
    assertEquals(0, sequence.resets);
  }

  @Test
  void secondStepFaultKeepsNormalizedExecutingStateAndCounters() {
    var s = state();
    var sequence = new Sequence();
    sequence.outcome =
        i -> {
          if (i == 2) throw new IllegalStateException("second");
          return 0;
        };
    assertThrows(
        IllegalStateException.class, () -> RogueModifierSequences46.start(s, sequence, null));
    assertEquals(EXEC, sequence.state);
    assertEquals(1, s.tickingSequenceCount);
    assertEquals(0, sequence.resets);
  }

  @Test
  void laterTickPassesActualFloatDelta() {
    var s = state();
    var sequence = new Sequence();
    sequence.state = EXEC;
    RogueModifierSequences46.tick(s, sequence, 0.125f, null);
    assertEquals(List.of(0.125f), sequence.deltas);
  }

  @Test
  void rawBoundSequenceReceivesNegativeAndNonFiniteDeltaWithoutInventedNativeGate() {
    var s = state();
    var sequence = new Sequence();
    sequence.state = EXEC;
    for (float d : new float[] {-1f, Float.NaN, Float.POSITIVE_INFINITY})
      RogueModifierSequences46.tick(s, sequence, d, null);
    assertEquals(3, sequence.steps);
    assertEquals(-1f, sequence.deltas.get(0));
    assertTrue(Float.isNaN(sequence.deltas.get(1)));
  }

  @Test
  void readyOrUnknownTickStatesSkipStepResetAndWorld() {
    var s = state();
    var sequence = new Sequence();
    for (int initial : new int[] {READY, 0, -1, Integer.MAX_VALUE}) {
      sequence.state = initial;
      RogueModifierSequences46.tick(s, sequence, 1f, null);
      assertEquals(initial, sequence.state);
    }
    assertEquals(0, sequence.steps);
    assertEquals(0, sequence.resets);
    assertEquals(0, s.tickingSequenceCount);
  }

  @Test
  void unknownTickResultIsPreservedAndDoesNotFinish() {
    var s = state();
    var sequence = new Sequence();
    sequence.state = EXEC;
    sequence.outcome = i -> -3;
    RogueModifierSequences46.tick(s, sequence, 1f, null);
    assertEquals(-3, sequence.state);
    assertEquals(0, sequence.resets);
  }

  @Test
  void successfulTickResetsBeforeCounterDecrementAndEnd() {
    var s = state();
    s.tickingSequenceCount = 8;
    var sequence = new Sequence();
    sequence.state = EXEC;
    sequence.outcome = i -> SUCCESS;
    sequence.onReset =
        () -> {
          assertEquals(SUCCESS, sequence.state);
          assertEquals(8, s.tickingSequenceCount);
        };
    var world =
        new World() {
          public void sequenceEnded(RogueModifierSequences46.State current) {
            assertEquals(READY, sequence.state);
            assertEquals(7, current.tickingSequenceCount);
            super.sequenceEnded(current);
          }
        };
    RogueModifierSequences46.tick(s, sequence, 2f, world);
    assertEquals(List.of("end"), world.order);
  }

  @Test
  void preexistingFailureTickResetsWithoutStepAndDecrementsBelowZero() {
    var s = state();
    var sequence = new Sequence();
    sequence.state = FAIL;
    RogueModifierSequences46.tick(s, sequence, 2f, new World());
    assertEquals(0, sequence.steps);
    assertEquals(-1, s.tickingSequenceCount);
    assertEquals(READY, sequence.state);
  }

  @Test
  void resetFailureLeavesTerminalStateAndCounters() {
    var s = state();
    s.tickingSequenceCount = 4;
    var sequence = new Sequence();
    sequence.state = SUCCESS;
    sequence.onReset =
        () -> {
          throw new IllegalStateException("reset");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueModifierSequences46.tick(s, sequence, 0f, new World()));
    assertEquals(4, s.tickingSequenceCount);
    assertEquals(SUCCESS, sequence.state);
  }

  @Test
  void sequenceEndFaultLeavesReadyStateAndCounterDecrement() {
    var s = state();
    var sequence = new Sequence();
    sequence.state = SUCCESS;
    var world =
        new World() {
          public void sequenceEnded(RogueModifierSequences46.State current) {
            throw new IllegalStateException("end");
          }
        };
    assertThrows(
        IllegalStateException.class, () -> RogueModifierSequences46.tick(s, sequence, 0f, world));
    assertEquals(READY, sequence.state);
    assertEquals(-1, s.tickingSequenceCount);
  }

  @Test
  void missingEndBindingFailsAfterResetAndCounterWrite() {
    var s = state();
    var sequence = new Sequence();
    sequence.state = SUCCESS;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierSequences46.tick(s, sequence, 0f, null));
    assertEquals(READY, sequence.state);
    assertEquals(-1, s.tickingSequenceCount);
  }

  @Test
  void nestedExecutionRetainsPendingAbilityUntilLastEnd() {
    var s = state();
    s.replacement.executingSequenceCount = 2;
    s.replacement.delayReplaceAbility = ABILITY;
    var world = new World();
    RogueModifierSequences46.endAndFlush(s, null);
    assertEquals(1, s.replacement.executingSequenceCount);
    assertSame(ABILITY, s.replacement.delayReplaceAbility);
    RogueModifierSequences46.endAndFlush(s, world);
    assertEquals(0, s.replacement.executingSequenceCount);
    assertNull(s.replacement.delayReplaceAbility);
    assertSame(ABILITY, world.ability);
    assertEquals(List.of("context", "ability", "changed"), world.order);
  }

  @Test
  void zeroOrNegativePreviousCountsStillFlushUsingSignedComparison() {
    for (int previous : new int[] {0, -1, Integer.MIN_VALUE}) {
      var s = state();
      s.replacement.executingSequenceCount = previous;
      s.replacement.delayReplaceAbility = ABILITY;
      var world = new World();
      RogueModifierSequences46.endAndFlush(s, world);
      assertEquals(previous - 1, s.replacement.executingSequenceCount);
      assertSame(ABILITY, world.ability);
      assertNull(s.replacement.delayReplaceAbility);
    }
  }

  @Test
  void missingPendingAbilitySkipsContextEvenWithCounterUnderflow() {
    var s = state();
    s.replacement.executingSequenceCount = Integer.MIN_VALUE;
    RogueModifierSequences46.endAndFlush(s, null);
    assertEquals(Integer.MAX_VALUE, s.replacement.executingSequenceCount);
  }

  @Test
  void missingFlushWorldKeepsPendingValueAfterDecrement() {
    var s = state();
    s.replacement.executingSequenceCount = 1;
    s.replacement.delayReplaceAbility = ABILITY;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierSequences46.endAndFlush(s, null));
    assertEquals(0, s.replacement.executingSequenceCount);
    assertSame(ABILITY, s.replacement.delayReplaceAbility);
  }

  @Test
  void missingTaskContextKeepsPendingValueAfterDecrement() {
    var s = state();
    s.replacement.executingSequenceCount = 1;
    s.replacement.delayReplaceAbility = ABILITY;
    var world = new World();
    world.context = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierSequences46.endAndFlush(s, world));
    assertEquals(0, s.replacement.executingSequenceCount);
    assertSame(ABILITY, s.replacement.delayReplaceAbility);
    assertEquals(List.of("context"), world.order);
  }

  @Test
  void contextGetterFaultKeepsPendingAbility() {
    var s = state();
    s.replacement.executingSequenceCount = 1;
    s.replacement.delayReplaceAbility = ABILITY;
    var world =
        new World() {
          public Object taskContext(RogueModifierSequences46.State current) {
            throw new IllegalStateException("context");
          }
        };
    assertThrows(IllegalStateException.class, () -> RogueModifierSequences46.endAndFlush(s, world));
    assertSame(ABILITY, s.replacement.delayReplaceAbility);
    assertEquals(0, s.replacement.executingSequenceCount);
  }

  @Test
  void abilitySetterFaultDoesNotRunAfterCallbackOrClearPending() {
    var s = state();
    s.replacement.delayReplaceAbility = ABILITY;
    var world = new World();
    world.onReplace =
        () -> {
          throw new IllegalStateException("setter");
        };
    assertThrows(IllegalStateException.class, () -> RogueModifierSequences46.endAndFlush(s, world));
    assertSame(ABILITY, s.replacement.delayReplaceAbility);
    assertSame(ABILITY, world.ability);
    assertEquals(List.of("context", "ability"), world.order);
  }

  @Test
  void afterCallbackFaultKeepsAbilityAndDoesNotRollbackContext() {
    var s = state();
    s.replacement.delayReplaceAbility = ABILITY;
    var world = new World();
    world.onChanged =
        () -> {
          throw new IllegalStateException("changed");
        };
    assertThrows(IllegalStateException.class, () -> RogueModifierSequences46.endAndFlush(s, world));
    assertSame(ABILITY, s.replacement.delayReplaceAbility);
    assertSame(ABILITY, world.ability);
    assertEquals(List.of("context", "ability", "changed"), world.order);
  }

  @Test
  void delayedAbilityIsCapturedBeforeContextCallbackMutatesPending() {
    var s = state();
    s.replacement.delayReplaceAbility = ABILITY;
    var world =
        new World() {
          public Object taskContext(RogueModifierSequences46.State current) {
            current.replacement.delayReplaceAbility = OTHER;
            return super.taskContext(current);
          }
        };
    RogueModifierSequences46.endAndFlush(s, world);
    assertSame(ABILITY, world.ability);
    assertNull(s.replacement.delayReplaceAbility);
  }

  @Test
  void successfulFinalCallbackClearsEvenNewlyQueuedPendingAbility() {
    var s = state();
    s.replacement.delayReplaceAbility = ABILITY;
    var world = new World();
    world.onChanged = () -> s.replacement.delayReplaceAbility = OTHER;
    RogueModifierSequences46.endAndFlush(s, world);
    assertNull(s.replacement.delayReplaceAbility);
  }

  @Test
  void resetCallbackMutationOfTickingCounterIsReadBeforeDecrement() {
    var s = state();
    var sequence = new Sequence();
    sequence.state = SUCCESS;
    sequence.onReset = () -> s.tickingSequenceCount = 11;
    RogueModifierSequences46.tick(s, sequence, 0f, new World());
    assertEquals(10, s.tickingSequenceCount);
  }

  @Test
  void actualChildSequenceCanWaitThenFinishAndFlushSameReplacementState() {
    var s = state();
    var world = new World();
    world.flushOnEnd = true;
    class Child implements RogueTaskSequence46.Task {
      int status = READY, ticks;

      public int state() {
        return status;
      }

      public void begin() {
        status = EXEC;
      }

      public void tick(float delta) {
        if (++ticks == 3) status = SUCCESS;
      }

      public void reset() {
        status = READY;
      }
    }
    var child = new Child();
    var sequence = new RogueTaskSequence46.Sequence(List.of(child), false);
    RogueModifierSequences46.start(s, sequence, world);
    assertEquals(2, child.ticks);
    assertEquals(1, s.tickingSequenceCount);
    var replaceWorld = new RogueModifierReplace46Test.BoundWorld();
    RogueModifierReplace46.replace(
        s.replacement, ABILITY, RogueModifierReplace46Test.parameters(), replaceWorld);
    assertSame(ABILITY, s.replacement.delayReplaceAbility);
    assertNull(replaceWorld.replacedAbility);
    RogueModifierSequences46.tick(s, sequence, 0.5f, world);
    assertEquals(3, child.ticks);
    assertEquals(READY, sequence.state());
    assertEquals(READY, child.state());
    assertEquals(0, s.tickingSequenceCount);
    assertEquals(0, s.replacement.executingSequenceCount);
    assertSame(ABILITY, world.ability);
    assertNull(s.replacement.delayReplaceAbility);
  }

  @Test
  void actualEmptySequenceFinishesOnFirstStepAndFlushesWithoutWaiting() {
    var s = state();
    var sequence = new RogueTaskSequence46.Sequence(List.of(), false);
    var world = new World();
    world.flushOnEnd = true;
    RogueModifierSequences46.start(s, sequence, world);
    assertEquals(READY, sequence.state());
    assertEquals(0, s.replacement.executingSequenceCount);
    assertEquals(0, s.tickingSequenceCount);
  }

  @Test
  void actualSequenceKeepsProjectFiniteDeltaAndStateGuards() {
    var sequence = new RogueTaskSequence46.Sequence(List.of(), false);
    assertThrows(IllegalArgumentException.class, () -> sequence.step(-1f));
    assertThrows(IllegalArgumentException.class, () -> sequence.step(Float.NaN));
    assertThrows(IllegalArgumentException.class, () -> sequence.state(-17));
    sequence.state(FAIL);
    assertEquals(FAIL, sequence.state());
  }

  @Test
  void nullTickSequenceSkipsWorldAndLeavesExistingCounts() {
    var s = state();
    s.tickingSequenceCount = 9;
    s.replacement.executingSequenceCount = 5;
    RogueModifierSequences46.tick(s, null, Float.NaN, null);
    assertEquals(9, s.tickingSequenceCount);
    assertEquals(5, s.replacement.executingSequenceCount);
  }

  @Test
  void missingSharedStateFailsBeforeExecutingUserBindings() {
    var sequence = new Sequence();
    assertThrows(IllegalArgumentException.class, () -> new RogueModifierSequences46.State(null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierSequences46.start(null, sequence, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierSequences46.tick(null, sequence, 0f, null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierSequences46.endAndFlush(null, null));
    assertEquals(0, sequence.steps);
  }
}
