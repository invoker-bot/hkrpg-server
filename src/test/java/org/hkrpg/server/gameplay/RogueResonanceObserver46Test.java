package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RogueResonanceObserver46Test {
  static final long ONE = 1L << 33;
  static final Object TARGET = new Object(), METHOD = new Object();
  static final RogueResonanceVariables46.Change CHANGE =
      new RogueResonanceVariables46.Change(7, ONE, 2 * ONE);

  static RogueResonanceObserver46.Listener listener(
      Object target,
      Object method,
      java.util.function.Consumer<RogueResonanceVariables46.Change> callback) {
    return new RogueResonanceObserver46.Listener(target, method, callback);
  }

  static RogueResonanceVariables46 variables(
      java.util.function.Consumer<RogueResonanceVariables46.Change> callback) {
    return RogueResonanceVariables46.fromSourceDefaults(Map.of(7, ONE), callback);
  }

  @Test
  void combinedListenersRunInRegistrationOrder() {
    var registry = new RogueResonanceObserver46();
    var calls = new ArrayList<Integer>();
    registry.combine(listener(TARGET, METHOD, c -> calls.add(1)));
    registry.combine(listener(new Object(), METHOD, c -> calls.add(2)));
    RogueResonanceObserver46.dispatch(registry.snapshot(), CHANGE);
    assertEquals(List.of(1, 2), calls);
  }

  @Test
  void duplicateInstanceMethodBindingsArePreserved() {
    var registry = new RogueResonanceObserver46();
    var calls = new ArrayList<Integer>();
    var a = listener(TARGET, METHOD, c -> calls.add(1));
    registry.combine(a);
    registry.combine(a);
    RogueResonanceObserver46.dispatch(registry.snapshot(), CHANGE);
    assertEquals(List.of(1, 1), calls);
  }

  @Test
  void removalUsesLastMatchingTargetAndMethod() {
    var registry = new RogueResonanceObserver46();
    var calls = new ArrayList<Integer>();
    registry.combine(listener(TARGET, METHOD, c -> calls.add(1)));
    registry.combine(listener(new Object(), METHOD, c -> calls.add(2)));
    registry.combine(listener(TARGET, METHOD, c -> calls.add(3)));
    assertTrue(registry.remove(listener(TARGET, METHOD, c -> fail("comparison token only"))));
    RogueResonanceObserver46.dispatch(registry.snapshot(), CHANGE);
    assertEquals(List.of(1, 2), calls);
  }

  @Test
  void removalDoesNotUseCallbackObjectIdentity() {
    var registry = new RogueResonanceObserver46();
    registry.combine(listener(TARGET, METHOD, c -> {}));
    assertTrue(registry.remove(listener(TARGET, METHOD, c -> {})));
    assertTrue(registry.snapshot().isEmpty());
  }

  @Test
  void equalButDifferentTargetObjectsDoNotMatch() {
    var registry = new RogueResonanceObserver46();
    registry.combine(listener(new String("target"), METHOD, c -> {}));
    assertFalse(registry.remove(listener(new String("target"), METHOD, c -> {})));
    assertEquals(1, registry.snapshot().size());
  }

  @Test
  void equalButDifferentMethodIdentityObjectsDoNotMatch() {
    var registry = new RogueResonanceObserver46();
    registry.combine(listener(TARGET, new String("method"), c -> {}));
    assertFalse(registry.remove(listener(TARGET, new String("method"), c -> {})));
    assertEquals(1, registry.snapshot().size());
  }

  @Test
  void unmatchedRemovalLeavesSameImmutableSnapshot() {
    var registry = new RogueResonanceObserver46();
    registry.combine(listener(TARGET, METHOD, c -> {}));
    var snapshot = registry.snapshot();
    assertFalse(registry.remove(listener(new Object(), METHOD, c -> {})));
    assertSame(snapshot, registry.snapshot());
  }

  @Test
  void oldSnapshotsRemainUsableAfterCombineAndRemove() {
    var registry = new RogueResonanceObserver46();
    var calls = new ArrayList<Integer>();
    var a = listener(TARGET, METHOD, c -> calls.add(1));
    registry.combine(a);
    var old = registry.snapshot();
    registry.combine(listener(new Object(), METHOD, c -> calls.add(2)));
    registry.remove(a);
    RogueResonanceObserver46.dispatch(old, CHANGE);
    assertEquals(List.of(1), calls);
    assertEquals(1, registry.snapshot().size());
  }

  @Test
  void snapshotsCannotBeModifiedByCallers() {
    var registry = new RogueResonanceObserver46();
    registry.combine(listener(TARGET, METHOD, c -> {}));
    assertThrows(UnsupportedOperationException.class, () -> registry.snapshot().clear());
  }

  @Test
  void emptySnapshotDispatchDoesNothing() {
    RogueResonanceObserver46.dispatch(List.of(), CHANGE);
  }

  @Test
  void listenerReceivesAllOptionalPreviousAndRawBits() {
    var registry = new RogueResonanceObserver46();
    var calls = new ArrayList<RogueResonanceVariables46.Change>();
    registry.combine(listener(TARGET, METHOD, calls::add));
    var absent =
        new RogueResonanceVariables46.Change(
            Integer.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, false);
    RogueResonanceObserver46.dispatch(registry.snapshot(), absent);
    assertEquals(List.of(absent), calls);
    assertSame(absent, calls.getFirst());
  }

  @Test
  void listenerFaultStopsFollowingInvocations() {
    var registry = new RogueResonanceObserver46();
    var calls = new ArrayList<Integer>();
    registry.combine(
        listener(
            TARGET,
            METHOD,
            c -> {
              calls.add(1);
              throw new IllegalStateException("first");
            }));
    registry.combine(listener(new Object(), METHOD, c -> calls.add(2)));
    assertThrows(
        IllegalStateException.class,
        () -> RogueResonanceObserver46.dispatch(registry.snapshot(), CHANGE));
    assertEquals(List.of(1), calls);
  }

  @Test
  void finiteLimitRejectsNextListenerWithoutLosingExistingList() {
    var registry = new RogueResonanceObserver46();
    var a = listener(TARGET, METHOD, c -> {});
    for (int i = 0; i < 255; i++) registry.combine(a);
    assertThrows(IllegalArgumentException.class, () -> registry.combine(a));
    assertEquals(255, registry.snapshot().size());
    assertTrue(registry.remove(a));
    registry.combine(a);
    assertEquals(255, registry.snapshot().size());
  }

  @Test
  void absentInstanceIdentityAndMissingInvocationInputsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> listener(null, METHOD, c -> {}));
    assertThrows(IllegalArgumentException.class, () -> listener(TARGET, null, c -> {}));
    assertThrows(IllegalArgumentException.class, () -> listener(TARGET, METHOD, null));
    var registry = new RogueResonanceObserver46();
    assertThrows(IllegalArgumentException.class, () -> registry.combine(null));
    assertThrows(IllegalArgumentException.class, () -> registry.remove(null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueResonanceObserver46.dispatch(null, CHANGE));
    assertThrows(
        IllegalArgumentException.class, () -> RogueResonanceObserver46.dispatch(List.of(), null));
  }

  @Test
  void rootWriteInvokesAttachedListenerAfterCellCommit() {
    var vars = variables(null);
    var calls = new ArrayList<RogueResonanceVariables46.Change>();
    vars.combineObserver(
        listener(
            TARGET,
            METHOD,
            c -> {
              assertEquals(2 * ONE, vars.read(7));
              calls.add(c);
            }));
    vars.write(7, 2 * ONE);
    assertEquals(List.of(CHANGE), calls);
  }

  @Test
  void rootInjectionInvokesListenerWithPreviousValueAbsent() {
    var vars = variables(null);
    var calls = new ArrayList<RogueResonanceVariables46.Change>();
    vars.combineObserver(listener(TARGET, METHOD, calls::add));
    vars.injectSourceStandard(7, ONE);
    assertEquals(List.of(new RogueResonanceVariables46.Change(7, 0, ONE, false)), calls);
  }

  @Test
  void constructorObserverRunsBeforeAppendedListeners() {
    var calls = new ArrayList<Integer>();
    var vars = variables(c -> calls.add(1));
    vars.combineObserver(listener(TARGET, METHOD, c -> calls.add(2)));
    vars.write(7, 2 * ONE);
    assertEquals(List.of(1, 2), calls);
  }

  @Test
  void removalDuringConstructorObserverDoesNotChangeCurrentInvocation() {
    RogueResonanceVariables46[] holder = {null};
    var calls = new ArrayList<Integer>();
    var a = listener(TARGET, METHOD, c -> calls.add(2));
    holder[0] =
        variables(
            c -> {
              calls.add(1);
              holder[0].removeObserver(a);
            });
    holder[0].combineObserver(a);
    holder[0].write(7, 2 * ONE);
    assertEquals(List.of(1, 2), calls);
    assertEquals(0, holder[0].attachedObserverCount());
    holder[0].write(7, 3 * ONE);
    assertEquals(List.of(1, 2, 1), calls);
  }

  @Test
  void additionDuringConstructorObserverWaitsUntilNextWrite() {
    RogueResonanceVariables46[] holder = {null};
    var calls = new ArrayList<Integer>();
    var a = listener(TARGET, METHOD, c -> calls.add(2));
    holder[0] =
        variables(
            c -> {
              calls.add(1);
              holder[0].combineObserver(a);
            });
    holder[0].write(7, 2 * ONE);
    assertEquals(List.of(1), calls);
    holder[0].write(7, 3 * ONE);
    assertEquals(List.of(1, 1, 2), calls);
    assertEquals(2, holder[0].attachedObserverCount());
  }

  @Test
  void additionDuringAttachedListenerWaitsUntilNextWrite() {
    var vars = variables(null);
    var calls = new ArrayList<Integer>();
    var added = listener(new Object(), METHOD, c -> calls.add(2));
    vars.combineObserver(
        listener(
            TARGET,
            METHOD,
            c -> {
              calls.add(1);
              vars.combineObserver(added);
            }));
    vars.write(7, 2 * ONE);
    assertEquals(List.of(1), calls);
    vars.write(7, 3 * ONE);
    assertEquals(List.of(1, 1, 2), calls);
  }

  @Test
  void removalDuringAttachedListenerDoesNotSkipCapturedFollowingListener() {
    var vars = variables(null);
    var calls = new ArrayList<Integer>();
    var second = listener(new Object(), METHOD, c -> calls.add(2));
    vars.combineObserver(
        listener(
            TARGET,
            METHOD,
            c -> {
              calls.add(1);
              vars.removeObserver(second);
            }));
    vars.combineObserver(second);
    vars.write(7, 2 * ONE);
    assertEquals(List.of(1, 2), calls);
    vars.write(7, 3 * ONE);
    assertEquals(List.of(1, 2, 1), calls);
  }

  @Test
  void nestedWriteUsesUpdatedListWhileOuterWriteFinishesOldSnapshot() {
    var vars = variables(null);
    var calls = new ArrayList<String>();
    var second = listener(new Object(), METHOD, c -> calls.add("second:" + c.current()));
    vars.combineObserver(
        listener(
            TARGET,
            METHOD,
            c -> {
              calls.add("first:" + c.current());
              if (c.current() == 2 * ONE) {
                vars.removeObserver(second);
                vars.write(7, 3 * ONE);
              }
            }));
    vars.combineObserver(second);
    vars.write(7, 2 * ONE);
    assertEquals(List.of("first:" + 2 * ONE, "first:" + 3 * ONE, "second:" + 2 * ONE), calls);
    assertEquals(3 * ONE, vars.read(7));
  }

  @Test
  void legacyFaultStopsAttachedListenersAndLeavesCommittedWrite() {
    var calls = new ArrayList<Integer>();
    var vars =
        variables(
            c -> {
              throw new IllegalStateException("legacy");
            });
    vars.combineObserver(listener(TARGET, METHOD, c -> calls.add(2)));
    assertThrows(IllegalStateException.class, () -> vars.write(7, 2 * ONE));
    assertEquals(2 * ONE, vars.read(7));
    assertTrue(calls.isEmpty());
  }

  @Test
  void attachedFaultStopsFollowingListenersAndKeepsInjectedCell() {
    var vars = variables(null);
    var calls = new ArrayList<Integer>();
    vars.combineObserver(
        listener(
            TARGET,
            METHOD,
            c -> {
              calls.add(1);
              throw new IllegalStateException("attached");
            }));
    vars.combineObserver(listener(new Object(), METHOD, c -> calls.add(2)));
    assertThrows(IllegalStateException.class, () -> vars.injectSourceStandard(7, 2 * ONE));
    assertEquals(2 * ONE, vars.read(7));
    assertEquals(List.of(1), calls);
  }

  @Test
  void removingAttachedListenerDoesNotRemoveConstructorObserver() {
    var calls = new ArrayList<Integer>();
    var vars = variables(c -> calls.add(1));
    var a = listener(TARGET, METHOD, c -> calls.add(2));
    vars.combineObserver(a);
    vars.removeObserver(a);
    vars.write(7, 2 * ONE);
    assertEquals(List.of(1), calls);
  }

  @Test
  void unchangedOrdinaryWriteKeepsExistingNotificationBehavior() {
    var calls = new ArrayList<RogueResonanceVariables46.Change>();
    var vars = variables(calls::add);
    vars.combineObserver(listener(TARGET, METHOD, calls::add));
    vars.write(7, ONE);
    assertEquals(ONE, vars.read(7));
    assertEquals(
        List.of(
            new RogueResonanceVariables46.Change(7, ONE, ONE),
            new RogueResonanceVariables46.Change(7, ONE, ONE)),
        calls);
  }

  @Test
  void duplicateRootBindingsCanBeRemovedOneAtATime() {
    var vars = variables(null);
    var calls = new ArrayList<Integer>();
    var a = listener(TARGET, METHOD, c -> calls.add(1));
    vars.combineObserver(a);
    vars.combineObserver(a);
    assertEquals(2, vars.attachedObserverCount());
    assertTrue(vars.removeObserver(a));
    vars.write(7, 2 * ONE);
    assertEquals(List.of(1), calls);
    assertTrue(vars.removeObserver(a));
    assertFalse(vars.removeObserver(a));
    assertEquals(0, vars.attachedObserverCount());
  }

  @Test
  void projectionMembershipRemainsClosedWithoutChangingRoot() {
    var vars = variables(null);
    var a = listener(TARGET, METHOD, c -> {});
    for (var view :
        List.of(
            RogueResonanceVariables46.withAiTagProjection(vars, Map.of()),
            RogueResonanceVariables46.withDynamicCells(vars, Map.of()))) {
      assertThrows(IllegalArgumentException.class, () -> view.combineObserver(a));
      assertThrows(IllegalArgumentException.class, () -> view.removeObserver(a));
      assertThrows(IllegalArgumentException.class, view::attachedObserverCount);
    }
    assertEquals(0, vars.attachedObserverCount());
  }

  @Test
  void rootMembershipMissingListenerDoesNotChangeCount() {
    var vars = variables(null);
    assertThrows(IllegalArgumentException.class, () -> vars.combineObserver(null));
    assertThrows(IllegalArgumentException.class, () -> vars.removeObserver(null));
    assertEquals(0, vars.attachedObserverCount());
  }

  @Test
  void actualModifierValueNotificationUsesSameRootListener() {
    var vars = variables(null);
    var calls = new ArrayList<RogueResonanceVariables46.Change>();
    vars.combineObserver(listener(TARGET, METHOD, calls::add));
    var s = RogueModifierReplace46Test.state();
    s.values.keys = new int[] {7};
    s.values.mask = 1;
    s.values.dynamicValues = vars;
    s.values.modifier.layer = 3;
    RogueModifierValueBinding46.notifyValue(
        s.values, 0, RogueModifierValueBinding46.standardVariables());
    assertEquals(List.of(new RogueResonanceVariables46.Change(7, ONE, 3 * ONE)), calls);
  }

  @Test
  void listenerCanRemoveItselfAndRegisterAnotherInsideSameRootMonitor() {
    var vars = variables(null);
    var calls = new ArrayList<Integer>();
    RogueResonanceObserver46.Listener[] self = {null};
    self[0] =
        listener(
            TARGET,
            METHOD,
            c -> {
              calls.add(1);
              vars.removeObserver(self[0]);
              vars.combineObserver(listener(new Object(), METHOD, next -> calls.add(2)));
            });
    vars.combineObserver(self[0]);
    vars.write(7, 2 * ONE);
    vars.write(7, 3 * ONE);
    assertEquals(List.of(1, 2), calls);
  }

  @Test
  void rootListenerLimitPreservesWrittenCellAndExistingMembership() {
    var vars = variables(null);
    var a = listener(TARGET, METHOD, c -> {});
    for (int i = 0; i < 255; i++) vars.combineObserver(a);
    assertThrows(IllegalArgumentException.class, () -> vars.combineObserver(a));
    vars.write(7, 2 * ONE);
    assertEquals(2 * ONE, vars.read(7));
    assertEquals(255, vars.attachedObserverCount());
  }
}
