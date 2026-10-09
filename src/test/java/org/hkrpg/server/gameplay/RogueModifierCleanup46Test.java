package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RogueModifierCleanup46Test {
  static final Object OWNER = new Object(),
      EFFECTS = new Object(),
      MUTEX = new Object(),
      CONTEXT = new Object(),
      ABILITY = new Object();

  static RogueModifierCleanup46.State state() {
    var s =
        new RogueModifierCleanup46.State(
            RogueModifierSequences46Test.state(),
            new RogueModifierSearch46.Candidate("buff", 1, 5));
    s.effects = EFFECTS;
    s.mutexEffects = MUTEX;
    s.ownerAbilityComponent = OWNER;
    s.config = new RogueModifierCleanup46.Config();
    return s;
  }

  static class World implements RogueModifierCleanup46.World {
    final List<String> order = new ArrayList<>();
    RogueModifierCleanup46.State linked;
    int override, delay;
    Runnable onUnstack = () -> {},
        onDetach = () -> {},
        onRemove = () -> {},
        onPriority = () -> {},
        onBehavior = () -> {},
        onDispose = () -> {},
        onCaster = () -> {};

    public void sequenceEnded(RogueModifierSequences46.State sequence) {
      assertSame(linked.sequence, sequence);
      order.add("end");
      RogueModifierCleanup46.endTurnSequence(linked, this);
    }

    public Object taskContext(RogueModifierSequences46.State sequence) {
      order.add("context");
      return CONTEXT;
    }

    public void replaceContextAbility(Object context, Object ability) {
      assertSame(CONTEXT, context);
      assertSame(ABILITY, ability);
      order.add("ability");
    }

    public void afterAbilityVirtual(RogueModifierSequences46.State sequence) {
      order.add("caster");
      onCaster.run();
    }

    public void unstack(RogueModifierCleanup46.State state) {
      order.add("unstack");
      onUnstack.run();
    }

    public void detachAllEffects(RogueModifierCleanup46.State state, boolean immediate) {
      assertTrue(immediate);
      order.add("detach");
      onDetach.run();
    }

    public void removeOnHitEffectOverride(Object owner, int index, int delay) {
      assertSame(OWNER, owner);
      override = index;
      this.delay = delay;
      order.add("hit");
      onRemove.run();
    }

    public void updateModifiersEffectHideByPriority(Object owner) {
      assertSame(OWNER, owner);
      order.add("priority");
      onPriority.run();
    }

    public void markBehaviorFlagVisualDirty(Object owner) {
      assertSame(OWNER, owner);
      order.add("behavior");
      onBehavior.run();
    }

    public void disposeBody(RogueModifierCleanup46.State state) {
      order.add("dispose");
      onDispose.run();
    }
  }

  @Test
  void ignoreTickingUsesSignedRelationBetweenBothCurrentCounters() {
    var s = state();
    for (int[] pair :
        new int[][] {
          {1, 0},
          {1, 1},
          {0, 1},
          {-1, -2},
          {Integer.MIN_VALUE, Integer.MAX_VALUE},
          {Integer.MAX_VALUE, Integer.MIN_VALUE}
        }) {
      s.sequence.replacement.executingSequenceCount = pair[0];
      s.sequence.tickingSequenceCount = pair[1];
      assertEquals(pair[0] > pair[1], RogueModifierCleanup46.isExecutingIgnoreTicking(s));
    }
  }

  @Test
  void executingDisposalOnlySetsPendingAndDoesNotNeedWorld() {
    var s = state();
    s.sequence.replacement.executingSequenceCount = 1;
    RogueModifierCleanup46.requestDispose(s, null);
    assertTrue(s.pendingDispose);
    assertSame(EFFECTS, s.effects);
    assertSame(MUTEX, s.mutexEffects);
  }

  @Test
  void waitingOnlyDisposalInvokesBodyAndDoesNotSilentlyClearOldPending() {
    var s = state();
    s.pendingDispose = true;
    s.sequence.replacement.executingSequenceCount = 2;
    s.sequence.tickingSequenceCount = 2;
    var world = new World();
    RogueModifierCleanup46.requestDispose(s, world);
    assertEquals(List.of("dispose"), world.order);
    assertTrue(s.pendingDispose);
  }

  @Test
  void negativeCountsUseSameDisposalComparison() {
    var s = state();
    s.sequence.replacement.executingSequenceCount = -2;
    s.sequence.tickingSequenceCount = -1;
    var world = new World();
    RogueModifierCleanup46.requestDispose(s, world);
    assertEquals(List.of("dispose"), world.order);
    assertFalse(s.pendingDispose);
  }

  @Test
  void directDisposeBodyFaultPreservesExistingState() {
    var s = state();
    var world = new World();
    world.onDispose =
        () -> {
          throw new IllegalStateException("dispose");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueModifierCleanup46.requestDispose(s, world));
    assertSame(EFFECTS, s.effects);
    assertFalse(s.pendingDispose);
  }

  @Test
  void missingNonexecutingDisposeBindingRejectsWithoutFabricatedCompletion() {
    var s = state();
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierCleanup46.requestDispose(s, null));
    assertFalse(s.pendingDispose);
  }

  @Test
  void turnEndFlushesAbilityBeforeUnstackCleanupAndDispose() {
    var s = state();
    s.sequence.replacement.executingSequenceCount = 1;
    s.sequence.replacement.delayReplaceAbility = ABILITY;
    s.candidate.state = 2;
    s.pendingDispose = true;
    var world = new World();
    RogueModifierCleanup46.endTurnSequence(s, world);
    assertEquals(
        List.of("context", "ability", "caster", "unstack", "detach", "dispose"), world.order);
    assertEquals(0, s.sequence.replacement.executingSequenceCount);
    assertNull(s.sequence.replacement.delayReplaceAbility);
    assertFalse(s.pendingDispose);
    assertEquals(2, s.candidate.state);
  }

  @Test
  void remainingImmediateExecutionStopsAllTurnCleanupAfterBaseDecrement() {
    var s = state();
    s.sequence.replacement.executingSequenceCount = 3;
    s.sequence.tickingSequenceCount = 1;
    s.candidate.state = 2;
    s.pendingDispose = true;
    RogueModifierCleanup46.endTurnSequence(s, null);
    assertEquals(2, s.sequence.replacement.executingSequenceCount);
    assertTrue(s.pendingDispose);
    assertSame(EFFECTS, s.effects);
  }

  @Test
  void remainingWaitingExecutionDoesNotPreventToBeRemovedCleanup() {
    var s = state();
    s.sequence.replacement.executingSequenceCount = 3;
    s.sequence.tickingSequenceCount = 2;
    s.candidate.state = 2;
    var world = new World();
    RogueModifierCleanup46.endTurnSequence(s, world);
    assertEquals(List.of("unstack", "detach"), world.order);
    assertEquals(2, s.sequence.replacement.executingSequenceCount);
    assertNull(s.effects);
  }

  @Test
  void aliveOrOtherNativeStatesSkipRemovalAndAllowAbsentWorld() {
    for (int value : new int[] {0, 1, 3, -1}) {
      var s = state();
      s.candidate.state = value;
      s.sequence.replacement.executingSequenceCount = 1;
      RogueModifierCleanup46.endTurnSequence(s, null);
      assertEquals(0, s.sequence.replacement.executingSequenceCount);
      assertSame(EFFECTS, s.effects);
    }
  }

  @Test
  void removalDecisionIsReadAfterBaseCasterCallback() {
    var s = state();
    s.sequence.replacement.executingSequenceCount = 1;
    s.sequence.replacement.delayReplaceAbility = ABILITY;
    var world = new World();
    world.onCaster = () -> s.candidate.state = 2;
    RogueModifierCleanup46.endTurnSequence(s, world);
    assertEquals(List.of("context", "ability", "caster", "unstack", "detach"), world.order);
  }

  @Test
  void unstackFaultLeavesPendingAndEffectsAfterExecutionDecrement() {
    var s = state();
    s.sequence.replacement.executingSequenceCount = 1;
    s.candidate.state = 2;
    s.pendingDispose = true;
    var world = new World();
    world.onUnstack =
        () -> {
          throw new IllegalStateException("unstack");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueModifierCleanup46.endTurnSequence(s, world));
    assertEquals(0, s.sequence.replacement.executingSequenceCount);
    assertTrue(s.pendingDispose);
    assertSame(EFFECTS, s.effects);
    assertEquals(List.of("unstack"), world.order);
  }

  @Test
  void detachFaultRetainsBothListsAndOverride() {
    var s = state();
    s.hitEffectOverride = 4;
    var world = new World();
    world.onDetach =
        () -> {
          throw new IllegalStateException("detach");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueModifierCleanup46.cleanupAfterDestroy(s, world));
    assertSame(EFFECTS, s.effects);
    assertSame(MUTEX, s.mutexEffects);
    assertEquals(4, s.hitEffectOverride);
  }

  @Test
  void successfulDetachClearsCurrentFieldsEvenWhenCallbackReplacesThem() {
    var s = state();
    var world = new World();
    world.onDetach =
        () -> {
          s.effects = new Object();
          s.mutexEffects = new Object();
        };
    RogueModifierCleanup46.cleanupAfterDestroy(s, world);
    assertNull(s.effects);
    assertNull(s.mutexEffects);
    assertEquals(List.of("detach"), world.order);
  }

  @Test
  void negativeOverrideSkipsOwnerLookupIncludingMinimumValue() {
    for (int override : new int[] {-1, -2, Integer.MIN_VALUE}) {
      var s = state();
      s.hitEffectOverride = override;
      s.ownerAbilityComponent = null;
      var world = new World();
      RogueModifierCleanup46.cleanupAfterDestroy(s, world);
      assertEquals(override, s.hitEffectOverride);
      assertEquals(List.of("detach"), world.order);
    }
  }

  @Test
  void zeroAndMaximumOverridePassExactDelayThenClearAfterCallback() {
    for (int override : new int[] {0, Integer.MAX_VALUE}) {
      var s = state();
      s.hitEffectOverride = override;
      s.beforeBuffUIDelayCount = -17;
      var world = new World();
      world.onRemove =
          () -> {
            assertEquals(override, s.hitEffectOverride);
            assertNull(s.effects);
            s.hitEffectOverride = 8;
          };
      RogueModifierCleanup46.cleanupAfterDestroy(s, world);
      assertEquals(override, world.override);
      assertEquals(-17, world.delay);
      assertEquals(-1, s.hitEffectOverride);
    }
  }

  @Test
  void overrideRemovalFaultRetainsOverrideWithListsAlreadyCleared() {
    var s = state();
    s.hitEffectOverride = 3;
    var world = new World();
    world.onRemove =
        () -> {
          throw new IllegalStateException("hit");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueModifierCleanup46.cleanupAfterDestroy(s, world));
    assertNull(s.effects);
    assertNull(s.mutexEffects);
    assertEquals(3, s.hitEffectOverride);
    assertEquals(List.of("detach", "hit"), world.order);
  }

  @Test
  void missingOverrideOwnerFailsAfterListClearBeforeOverrideReset() {
    var s = state();
    s.hitEffectOverride = 0;
    s.ownerAbilityComponent = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierCleanup46.cleanupAfterDestroy(s, new World()));
    assertNull(s.effects);
    assertEquals(0, s.hitEffectOverride);
  }

  @Test
  void nonemptyPriorityIncludingWhitespaceUpdatesVisualBeforeBehavior() {
    for (String priority : new String[] {"priority", " "}) {
      var s = state();
      s.config.effectPriority = priority;
      s.config.behaviorFlags = new Object();
      var world = new World();
      RogueModifierCleanup46.cleanupAfterDestroy(s, world);
      assertEquals(List.of("detach", "priority", "behavior"), world.order);
    }
  }

  @Test
  void emptyOrMissingPrioritySkipsVisualAndDoesNotRequireOwner() {
    for (String priority : new String[] {null, ""}) {
      var s = state();
      s.config.effectPriority = priority;
      s.ownerAbilityComponent = null;
      var world = new World();
      RogueModifierCleanup46.cleanupAfterDestroy(s, world);
      assertEquals(List.of("detach"), world.order);
    }
  }

  @Test
  void missingFinalConfigFailsAfterDetachAndOverrideReset() {
    var s = state();
    s.hitEffectOverride = 2;
    s.config = null;
    var world = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierCleanup46.cleanupAfterDestroy(s, world));
    assertNull(s.effects);
    assertEquals(-1, s.hitEffectOverride);
    assertEquals(List.of("detach", "hit"), world.order);
  }

  @Test
  void priorityCallbackReplacesConfigAndLaterBehaviorUsesCurrentValue() {
    var s = state();
    s.config.effectPriority = "p";
    var world = new World();
    world.onPriority =
        () -> {
          s.config = new RogueModifierCleanup46.Config();
          s.config.behaviorFlags = new Object();
        };
    RogueModifierCleanup46.cleanupAfterDestroy(s, world);
    assertEquals(List.of("detach", "priority", "behavior"), world.order);
  }

  @Test
  void priorityCallbackClearingConfigFailsAtLaterConfigRead() {
    var s = state();
    s.config.effectPriority = "p";
    var world = new World();
    world.onPriority = () -> s.config = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierCleanup46.cleanupAfterDestroy(s, world));
    assertEquals(List.of("detach", "priority"), world.order);
    assertNull(s.effects);
  }

  @Test
  void nonnullEmptyBehaviorListStillMarksVisualDirty() {
    var s = state();
    s.config.behaviorFlags = List.of();
    var world = new World();
    RogueModifierCleanup46.cleanupAfterDestroy(s, world);
    assertEquals(List.of("detach", "behavior"), world.order);
  }

  @Test
  void priorityFaultStopsBeforeBehaviorButKeepsListClear() {
    var s = state();
    s.config.effectPriority = "p";
    s.config.behaviorFlags = new Object();
    var world = new World();
    world.onPriority =
        () -> {
          throw new IllegalStateException("priority");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueModifierCleanup46.cleanupAfterDestroy(s, world));
    assertEquals(List.of("detach", "priority"), world.order);
    assertNull(s.effects);
  }

  @Test
  void behaviorFaultPreservesEarlierOverrideRemoval() {
    var s = state();
    s.hitEffectOverride = 4;
    s.config.behaviorFlags = new Object();
    var world = new World();
    world.onBehavior =
        () -> {
          throw new IllegalStateException("behavior");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueModifierCleanup46.cleanupAfterDestroy(s, world));
    assertEquals(-1, s.hitEffectOverride);
    assertEquals(List.of("detach", "hit", "behavior"), world.order);
  }

  @Test
  void cleanupCallbackCanClearPendingAndSkipDisposal() {
    var s = state();
    s.sequence.replacement.executingSequenceCount = 1;
    s.candidate.state = 2;
    s.pendingDispose = true;
    var world = new World();
    world.onDetach = () -> s.pendingDispose = false;
    RogueModifierCleanup46.endTurnSequence(s, world);
    assertEquals(List.of("unstack", "detach"), world.order);
  }

  @Test
  void newImmediateExecutionDuringCleanupDefersDisposalAgain() {
    var s = state();
    s.sequence.replacement.executingSequenceCount = 1;
    s.candidate.state = 2;
    s.pendingDispose = true;
    var world = new World();
    world.onDetach = () -> s.sequence.replacement.executingSequenceCount = 1;
    RogueModifierCleanup46.endTurnSequence(s, world);
    assertTrue(s.pendingDispose);
    assertEquals(List.of("unstack", "detach"), world.order);
  }

  @Test
  void endClearsPendingBeforeDisposeBodyAndKeepsClearOnFault() {
    var s = state();
    s.sequence.replacement.executingSequenceCount = 1;
    s.pendingDispose = true;
    var world = new World();
    world.onDispose =
        () -> {
          assertFalse(s.pendingDispose);
          throw new IllegalStateException("dispose");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueModifierCleanup46.endTurnSequence(s, world));
    assertFalse(s.pendingDispose);
    assertEquals(List.of("dispose"), world.order);
  }

  @Test
  void baseFlushFaultStopsTurnCleanupWithoutClearingPendingAbility() {
    var s = state();
    s.sequence.replacement.executingSequenceCount = 1;
    s.sequence.replacement.delayReplaceAbility = ABILITY;
    s.candidate.state = 2;
    s.pendingDispose = true;
    var world = new World();
    world.onCaster =
        () -> {
          throw new IllegalStateException("caster");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueModifierCleanup46.endTurnSequence(s, world));
    assertEquals(0, s.sequence.replacement.executingSequenceCount);
    assertSame(ABILITY, s.sequence.replacement.delayReplaceAbility);
    assertTrue(s.pendingDispose);
    assertEquals(List.of("context", "ability", "caster"), world.order);
  }

  @Test
  void actualEmptyTaskSequenceRoutesToSameTurnCleanupAndDisposalState() {
    var s = state();
    s.candidate.state = 2;
    s.pendingDispose = true;
    var world = new World();
    world.linked = s;
    var sequence = new RogueTaskSequence46.Sequence(List.of(), false);
    RogueModifierSequences46.start(s.sequence, sequence, world);
    assertEquals(List.of("end", "unstack", "detach", "dispose"), world.order);
    assertEquals(0, s.sequence.replacement.executingSequenceCount);
    assertEquals(0, s.sequence.tickingSequenceCount);
    assertNull(s.effects);
    assertFalse(s.pendingDispose);
    assertEquals(2, s.candidate.state);
  }

  @Test
  void disposalCallbackNewPendingFlagIsNotClearedAgainAfterReturn() {
    var s = state();
    s.sequence.replacement.executingSequenceCount = 1;
    s.pendingDispose = true;
    var world = new World();
    world.onDispose = () -> s.pendingDispose = true;
    RogueModifierCleanup46.endTurnSequence(s, world);
    assertTrue(s.pendingDispose);
    assertEquals(List.of("dispose"), world.order);
  }

  @Test
  void missingSharedStateOrWorldIsRejectedAtExplicitBoundary() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RogueModifierCleanup46.State(null, new RogueModifierSearch46.Candidate("b", 1, 5)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierCleanup46.State(RogueModifierSequences46Test.state(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierCleanup46.isExecutingIgnoreTicking(null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierCleanup46.endTurnSequence(null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierCleanup46.cleanupAfterDestroy(null, null));
    var s = state();
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierCleanup46.cleanupAfterDestroy(s, null));
    assertSame(EFFECTS, s.effects);
  }
}
