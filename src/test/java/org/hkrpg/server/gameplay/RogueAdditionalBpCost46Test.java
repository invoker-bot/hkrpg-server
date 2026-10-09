package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RogueAdditionalBpCost46Test {
  static RogueAdditionalBpCost46.State state(int... ids) {
    var c = RogueModifierCleanup46Test.state();
    var stack = new RogueModifierStackEvent46.State(c.sequence.replacement);
    var list = new ArrayList<RogueModifierStackedEffects46.AdditionalBp>();
    for (int id : ids) list.add(new RogueModifierStackedEffects46.AdditionalBp(new Object(), id));
    stack.stackedAdditionalTeamBp = list;
    return new RogueAdditionalBpCost46.State(c, stack);
  }

  static final class World implements RogueAdditionalBpCost46.World {
    final List<RogueModifierCleanup46.State> destroyed = new ArrayList<>();
    final List<Integer> reasons = new ArrayList<>();
    Runnable callback = () -> {};

    public void destroy(RogueModifierCleanup46.State modifier, int reason) {
      destroyed.add(modifier);
      reasons.add(reason);
      callback.run();
    }
  }

  @Test
  void absentInitialRecordsDoNotDestroy() {
    var s = state();
    s.stack.stackedAdditionalTeamBp = null;
    assertDoesNotThrow(() -> RogueAdditionalBpCost46.cost(s, 1, null));
  }

  @Test
  void initiallyEmptyRecordsDoNotDestroy() {
    var s = state();
    World w = new World();
    RogueAdditionalBpCost46.cost(s, 1, w);
    assertTrue(w.destroyed.isEmpty());
  }

  @Test
  void nonmatchingIdLeavesAllRecordsAndDoesNotRequireWorld() {
    var s = state(1, 2, 3);
    var records = new ArrayList<>(s.stack.stackedAdditionalTeamBp);
    RogueAdditionalBpCost46.cost(s, 4, null);
    assertEquals(records, s.stack.stackedAdditionalTeamBp);
  }

  @Test
  void matchingIdDeletesEveryMatchInReverseOrder() {
    var s = state(2, 1, 2, 1, 2);
    var a = s.stack.stackedAdditionalTeamBp.get(1);
    var b = s.stack.stackedAdditionalTeamBp.get(3);
    RogueAdditionalBpCost46.cost(s, 2, null);
    assertEquals(List.of(a, b), s.stack.stackedAdditionalTeamBp);
  }

  @Test
  void lastMatchingRecordRequestsExhaustedDestroyOnSharedCleanup() {
    var s = state(8);
    World w = new World();
    w.callback = () -> assertTrue(s.stack.stackedAdditionalTeamBp.isEmpty());
    RogueAdditionalBpCost46.cost(s, 8, w);
    assertEquals(List.of(s.cleanup), w.destroyed);
    assertEquals(List.of(4), w.reasons);
  }

  @Test
  void multipleMatchesExhaustOnlyAfterFinalRemoval() {
    var s = state(7, 7, 7);
    World w = new World();
    RogueAdditionalBpCost46.cost(s, 7, w);
    assertTrue(s.stack.stackedAdditionalTeamBp.isEmpty());
    assertEquals(1, w.destroyed.size());
  }

  @Test
  void missingDestructionWorldKeepsRemovedRecords() {
    var s = state(7);
    assertThrows(IllegalArgumentException.class, () -> RogueAdditionalBpCost46.cost(s, 7, null));
    assertTrue(s.stack.stackedAdditionalTeamBp.isEmpty());
  }

  @Test
  void destructionFailureDoesNotRestoreRecords() {
    var s = state(7, 7);
    World w = new World();
    w.callback =
        () -> {
          throw new IllegalStateException();
        };
    assertThrows(IllegalStateException.class, () -> RogueAdditionalBpCost46.cost(s, 7, w));
    assertTrue(s.stack.stackedAdditionalTeamBp.isEmpty());
    assertEquals(1, w.destroyed.size());
  }

  @Test
  void retryAfterDestructionFailureDoesNotInvokeDestructionAgain() {
    var s = state(7);
    World w = new World();
    w.callback =
        () -> {
          throw new IllegalStateException();
        };
    assertThrows(IllegalStateException.class, () -> RogueAdditionalBpCost46.cost(s, 7, w));
    assertDoesNotThrow(() -> RogueAdditionalBpCost46.cost(s, 7, w));
    assertEquals(1, w.destroyed.size());
  }

  @Test
  void zeroIdCanMatchExplicitDefaultValueRecordWithoutTeam() {
    var s = state();
    s.stack.stackedAdditionalTeamBp =
        new ArrayList<>(List.of(new RogueModifierStackedEffects46.AdditionalBp(null, 0)));
    World w = new World();
    RogueAdditionalBpCost46.cost(s, 0, w);
    assertEquals(1, w.destroyed.size());
  }

  @Test
  void negativeAndMinimumIdsAreComparedAsRawInt() {
    var s = state(-1, Integer.MIN_VALUE, -1);
    RogueAdditionalBpCost46.cost(s, -1, null);
    assertEquals(
        Integer.MIN_VALUE,
        ((RogueModifierStackedEffects46.AdditionalBp) s.stack.stackedAdditionalTeamBp.get(0))
            .registrationId());
    World w = new World();
    RogueAdditionalBpCost46.cost(s, Integer.MIN_VALUE, w);
    assertEquals(1, w.destroyed.size());
  }

  @Test
  void foreignRecordProjectionFailsWithoutInventedRemoval() {
    var s = state();
    s.stack.stackedAdditionalTeamBp = new ArrayList<>(List.of("unknown"));
    assertThrows(IllegalArgumentException.class, () -> RogueAdditionalBpCost46.cost(s, 0, null));
    assertEquals(List.of("unknown"), s.stack.stackedAdditionalTeamBp);
  }

  @Test
  void nullReferenceIsNotAnExplicitValueRecord() {
    var s = state();
    var l = new ArrayList<Object>();
    l.add(null);
    s.stack.stackedAdditionalTeamBp = l;
    assertThrows(IllegalArgumentException.class, () -> RogueAdditionalBpCost46.cost(s, 0, null));
    assertEquals(1, l.size());
  }

  @Test
  void reverseTraversalKeepsAlreadyRemovedSuffixOnEarlierInvalidRecord() {
    var s = state(1, 7);
    var l = new ArrayList<Object>(s.stack.stackedAdditionalTeamBp);
    l.set(0, "unknown");
    s.stack.stackedAdditionalTeamBp = l;
    assertThrows(IllegalArgumentException.class, () -> RogueAdditionalBpCost46.cost(s, 7, null));
    assertEquals(List.of("unknown"), l);
  }

  @Test
  void removeFailureKeepsUnremovedMatchingRecordAndStopsDestroy() {
    var s = state(7);
    var original = s.stack.stackedAdditionalTeamBp.get(0);
    s.stack.stackedAdditionalTeamBp =
        new ArrayList<Object>(s.stack.stackedAdditionalTeamBp) {
          @Override
          public Object remove(int i) {
            throw new IllegalStateException();
          }
        };
    World w = new World();
    assertThrows(IllegalStateException.class, () -> RogueAdditionalBpCost46.cost(s, 7, w));
    assertEquals(List.of(original), s.stack.stackedAdditionalTeamBp);
    assertTrue(w.destroyed.isEmpty());
  }

  @Test
  void explicitContainerReplacementBeforeRemoveUsesCurrentList() {
    var s = state(7);
    var original = s.stack.stackedAdditionalTeamBp.get(0);
    var replacement = new ArrayList<>(List.of(original));
    s.stack.stackedAdditionalTeamBp =
        new ArrayList<>(List.of(original)) {
          @Override
          public Object get(int i) {
            s.stack.stackedAdditionalTeamBp = replacement;
            return super.get(i);
          }
        };
    World w = new World();
    RogueAdditionalBpCost46.cost(s, 7, w);
    assertTrue(replacement.isEmpty());
    assertEquals(1, w.destroyed.size());
  }

  @Test
  void explicitContainerRemovalCanClearCurrentReferenceBeforeDestroyTail() {
    var s = state(7);
    s.stack.stackedAdditionalTeamBp =
        new ArrayList<Object>(s.stack.stackedAdditionalTeamBp) {
          @Override
          public Object remove(int i) {
            Object result = super.remove(i);
            s.stack.stackedAdditionalTeamBp = null;
            return result;
          }
        };
    World w = new World();
    RogueAdditionalBpCost46.cost(s, 7, w);
    assertEquals(1, w.destroyed.size());
    assertNull(s.stack.stackedAdditionalTeamBp);
  }

  @Test
  void destructionCallbackCanInstallNewRecordWithoutPostCallbackClear() {
    var s = state(7);
    World w = new World();
    var added = new RogueModifierStackedEffects46.AdditionalBp(null, 9);
    w.callback = () -> s.stack.stackedAdditionalTeamBp = new ArrayList<>(List.of(added));
    RogueAdditionalBpCost46.cost(s, 7, w);
    assertEquals(List.of(added), s.stack.stackedAdditionalTeamBp);
  }

  @Test
  void destructionCallbackExceptionKeepsItsNewRecords() {
    var s = state(7);
    World w = new World();
    var added = new RogueModifierStackedEffects46.AdditionalBp(null, 9);
    w.callback =
        () -> {
          s.stack.stackedAdditionalTeamBp = new ArrayList<>(List.of(added));
          throw new IllegalStateException();
        };
    assertThrows(IllegalStateException.class, () -> RogueAdditionalBpCost46.cost(s, 7, w));
    assertEquals(List.of(added), s.stack.stackedAdditionalTeamBp);
  }

  @Test
  void negativeHeaderDoesNotUnderflowReverseIndex() {
    var s = state();
    s.stack.stackedAdditionalTeamBp =
        new java.util.AbstractList<Object>() {
          public int size() {
            return Integer.MIN_VALUE;
          }

          public Object get(int i) {
            throw new AssertionError();
          }
        };
    World w = new World();
    assertDoesNotThrow(() -> RogueAdditionalBpCost46.cost(s, 7, w));
    assertTrue(w.destroyed.isEmpty());
  }

  @Test
  void finiteLimitRejectsOversizedHeaderBeforeEntryRead() {
    var s = state();
    s.stack.stackedAdditionalTeamBp =
        new java.util.AbstractList<Object>() {
          public int size() {
            return 256;
          }

          public Object get(int i) {
            throw new AssertionError();
          }
        };
    World w = new World();
    assertThrows(IllegalArgumentException.class, () -> RogueAdditionalBpCost46.cost(s, 7, w));
    assertTrue(w.destroyed.isEmpty());
  }

  @Test
  void exactFiniteLimitCanConsumeAllMatchingIds() {
    int[] ids = new int[255];
    java.util.Arrays.fill(ids, 7);
    var s = state(ids);
    World w = new World();
    RogueAdditionalBpCost46.cost(s, 7, w);
    assertTrue(s.stack.stackedAdditionalTeamBp.isEmpty());
    assertEquals(1, w.destroyed.size());
  }

  @Test
  void missingStateIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> RogueAdditionalBpCost46.cost(null, 1, null));
  }

  @Test
  void stateDoesNotCopyExistingStackList() {
    var s = state(7);
    var record = s.stack.stackedAdditionalTeamBp.get(0);
    var replaced = new ArrayList<>(List.of(record));
    s.stack.stackedAdditionalTeamBp = replaced;
    World w = new World();
    RogueAdditionalBpCost46.cost(s, 7, w);
    assertSame(replaced, s.stack.stackedAdditionalTeamBp);
    assertTrue(replaced.isEmpty());
  }

  @Test
  void constructorRequiresSameReplacementButAllowsSameSharedStackWrapper() {
    var s = state(7);
    var other =
        new RogueModifierStackEvent46.State(RogueModifierSequences46Test.state().replacement);
    assertThrows(
        IllegalArgumentException.class, () -> new RogueAdditionalBpCost46.State(s.cleanup, other));
    assertSame(s.stack, new RogueAdditionalBpCost46.State(s.cleanup, s.stack).stack);
  }

  @Test
  void constructorRejectsMissingStates() {
    var s = state();
    assertThrows(
        IllegalArgumentException.class, () -> new RogueAdditionalBpCost46.State(null, s.stack));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueAdditionalBpCost46.State(s.cleanup, null));
  }

  @Test
  void costDoesNotAddOwnLifecycleGuardBeforeNativeDestroyRequest() {
    var s = state(7);
    s.cleanup.candidate.state = 2;
    World w = new World();
    RogueAdditionalBpCost46.cost(s, 7, w);
    assertEquals(1, w.destroyed.size());
    assertEquals(2, s.cleanup.candidate.state);
  }

  @Test
  void actualPointRefreshConsumesMatchingRecordsAndRequestsDestroy() {
    var cost = state(29, 29);
    cost.cleanup.candidate.state = 1;
    var module = RogueSpecialBpPoints46Test.state(1);
    module.points.head.modifier = cost.cleanup;
    module.points.head.registrationId = 29;
    World w = new World();
    RogueSpecialBpPoints46.refresh(
        module,
        (m, id) -> {
          assertSame(cost.cleanup, m);
          RogueAdditionalBpCost46.cost(cost, id, w);
        });
    assertTrue(cost.stack.stackedAdditionalTeamBp.isEmpty());
    assertEquals(List.of(cost.cleanup), w.destroyed);
    assertEquals(0, module.points.head.special);
    assertNull(module.points.head.modifier);
  }

  @Test
  void actualPointRefreshKeepsOtherRegistrationWithoutDestruction() {
    var cost = state(29, 31);
    cost.cleanup.candidate.state = 1;
    var module = RogueSpecialBpPoints46Test.state(1);
    module.points.head.modifier = cost.cleanup;
    module.points.head.registrationId = 29;
    World w = new World();
    RogueSpecialBpPoints46.refresh(module, (m, id) -> RogueAdditionalBpCost46.cost(cost, id, w));
    assertEquals(
        31,
        ((RogueModifierStackedEffects46.AdditionalBp) cost.stack.stackedAdditionalTeamBp.get(0))
            .registrationId());
    assertTrue(w.destroyed.isEmpty());
  }

  @Test
  void actualRefreshDestroyFailureKeepsPointClearRecordsRemovedAndNoIntegerNotify() {
    var cost = state(29);
    cost.cleanup.candidate.state = 1;
    var module = RogueSpecialBpPoints46Test.state(1);
    module.points.head.modifier = cost.cleanup;
    module.points.head.registrationId = 29;
    World w = new World();
    w.callback =
        () -> {
          throw new IllegalStateException();
        };
    var notifications = new ArrayList<Integer>();
    var changes =
        new RogueSpecialBpWithdrawal46.World() {
          public void removeNormalPoint(RogueSpecialBpWithdrawal46.State s, boolean flag) {
            RogueSpecialBpPoints46.removePoint(s, flag);
          }

          public void refresh(RogueSpecialBpWithdrawal46.State s) {
            RogueSpecialBpPoints46.refresh(s, (m, id) -> RogueAdditionalBpCost46.cost(cost, id, w));
          }

          public void notifyGlobal(int event, int value, boolean immediate) {
            notifications.add(value);
          }
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueSpecialBpWithdrawal46.changed(module, 1, 0, changes));
    assertEquals(0, module.points.head.special);
    assertNull(module.points.head.modifier);
    assertTrue(cost.stack.stackedAdditionalTeamBp.isEmpty());
    assertTrue(notifications.isEmpty());
    assertEquals(1, w.destroyed.size());
  }
}
