package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RogueSpecialBpPoints46Test {
  static RogueSpecialBpWithdrawal46.State state(int... flags) {
    var s = RogueSpecialBpWithdrawal46Test.state();
    s.points = RogueSpecialBpWithdrawal46Test.points(flags);
    s.fallbackMaximum = flags.length;
    if (s.points.head != null) {
      var p = s.points.head;
      do {
        p.next.previous = p;
        p = p.next;
      } while (p != s.points.head);
    }
    s.points.version = 9;
    return s;
  }

  static RogueModifierCleanup46.State modifier(int state) {
    var m = RogueModifierCleanup46Test.state();
    m.candidate.state = state;
    return m;
  }

  static final class Costs implements RogueSpecialBpPoints46.World {
    final List<RogueModifierCleanup46.State> owners = new ArrayList<>();
    final List<Integer> registrationIds = new ArrayList<>();
    Runnable callback = () -> {};

    public void costAdditionalTeamBp(RogueModifierCleanup46.State m, int registrationId) {
      owners.add(m);
      registrationIds.add(registrationId);
      callback.run();
    }
  }

  @Test
  void emptyHeadNeedsNoLinks() {
    var s = state();
    s.points.size = 8;
    RogueSpecialBpPoints46.removePoint(s, false);
    assertEquals(8, s.points.size);
    assertEquals(9, s.points.version);
  }

  @Test
  void missingTailIsNoOp() {
    var s = state(0);
    s.points.head.previous = null;
    RogueSpecialBpPoints46.removePoint(s, true);
    assertEquals(1, s.points.size);
    assertEquals(9, s.points.version);
  }

  @Test
  void normalTailIsRemovedFirstWithVersionAndLinks() {
    var s = state(0, 0, 0);
    var head = s.points.head;
    var tail = head.previous;
    RogueSpecialBpPoints46.removePoint(s, false);
    assertEquals(2, s.points.size);
    assertEquals(10, s.points.version);
    assertSame(head, head.previous.next);
    assertNull(tail.next);
    assertNull(tail.previous);
    assertNull(tail.owner);
  }

  @Test
  void backwardScanSkipsSpecialTailAndRemovesNearestNormal() {
    var s = state(0, 0, 1);
    var middle = s.points.head.next;
    var tail = s.points.head.previous;
    RogueSpecialBpPoints46.removePoint(s, false);
    assertEquals(2, s.points.size);
    assertSame(tail, s.points.head.next);
    assertSame(s.points.head, tail.previous);
    assertNull(middle.owner);
    assertEquals(1, tail.special);
  }

  @Test
  void includeSpecialRemovesTailRegardlessOfFlag() {
    var s = state(0, 1);
    var tail = s.points.head.previous;
    RogueSpecialBpPoints46.removePoint(s, true);
    assertEquals(1, s.points.size);
    assertEquals(1, tail.special);
    assertNull(tail.owner);
    assertSame(s.points.head, s.points.head.next);
  }

  @Test
  void allSpecialPointsArePreservedWhenExcluded() {
    var s = state(1, 3, 1);
    var head = s.points.head;
    RogueSpecialBpPoints46.removePoint(s, false);
    assertSame(head, s.points.head);
    assertEquals(3, s.points.size);
    assertEquals(9, s.points.version);
  }

  @Test
  void flagTwoIsNormalBecauseOnlyLowBitIsTested() {
    var s = state(1, 2);
    var tail = s.points.head.previous;
    RogueSpecialBpPoints46.removePoint(s, false);
    assertEquals(1, s.points.size);
    assertNull(tail.owner);
    assertEquals(2, tail.special);
  }

  @Test
  void previousNullStopsBeforeOwnerReadForSpecialTail() {
    var s = state(1, 1);
    var tail = s.points.head.previous;
    tail.previous = null;
    tail.owner = null;
    RogueSpecialBpPoints46.removePoint(s, false);
    assertEquals(2, s.points.size);
  }

  @Test
  void specialTailNeedsOwnerEvenAtEndOfCircle() {
    var s = state(1);
    s.points.head.owner = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpPoints46.removePoint(s, false));
    assertEquals(1, s.points.size);
  }

  @Test
  void foreignNormalTailFailsBeforeUnlink() {
    var s = state(0, 0);
    var tail = s.points.head.previous;
    tail.owner = new RogueSpecialBpWithdrawal46.Points();
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpPoints46.removePoint(s, false));
    assertEquals(2, s.points.size);
    assertSame(tail, s.points.head.next);
  }

  @Test
  void singleNodeRemovalClearsHeadAndLinksButKeepsPayload() {
    var s = state(1);
    var p = s.points.head;
    p.modifier = modifier(1);
    p.registrationId = 33;
    RogueSpecialBpPoints46.removeNode(s.points, p);
    assertNull(s.points.head);
    assertEquals(0, s.points.size);
    assertEquals(10, s.points.version);
    assertNull(p.owner);
    assertNull(p.next);
    assertNull(p.previous);
    assertEquals(1, p.special);
    assertEquals(33, p.registrationId);
    assertNotNull(p.modifier);
  }

  @Test
  void removingHeadMovesHeadToNext() {
    var s = state(0, 0, 0);
    var head = s.points.head;
    var next = head.next;
    var tail = head.previous;
    RogueSpecialBpPoints46.removeNode(s.points, head);
    assertSame(next, s.points.head);
    assertSame(tail, next.previous);
    assertSame(next, tail.next);
  }

  @Test
  void missingNextFailsBeforeAnyLinkMutation() {
    var s = state(0, 0);
    var p = s.points.head;
    p.next = null;
    var previous = p.previous;
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpPoints46.removeNode(s.points, p));
    assertSame(previous, p.previous);
    assertSame(p, s.points.head);
    assertEquals(9, s.points.version);
  }

  @Test
  void missingPreviousFailsAfterNextPreviousWasCleared() {
    var s = state(0, 0);
    var p = s.points.head;
    var next = p.next;
    p.previous = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpPoints46.removeNode(s.points, p));
    assertNull(next.previous);
    assertSame(p, s.points.head);
    assertSame(s.points, p.owner);
    assertEquals(2, s.points.size);
    assertEquals(9, s.points.version);
  }

  @Test
  void foreignNodeDoesNotMutateLists() {
    var a = state(0);
    var b = state(0);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueSpecialBpPoints46.removeNode(a.points, b.points.head));
    assertEquals(1, a.points.size);
    assertEquals(1, b.points.size);
  }

  @Test
  void sizeAndVersionUseNativeIntWrap() {
    var s = state(0);
    s.points.size = Integer.MIN_VALUE;
    s.points.version = Integer.MAX_VALUE;
    RogueSpecialBpPoints46.removeNode(s.points, s.points.head);
    assertEquals(Integer.MAX_VALUE, s.points.size);
    assertEquals(Integer.MIN_VALUE, s.points.version);
  }

  @Test
  void malformedBackwardCircleIsFiniteAndKeepsHeaders() {
    var s = state(1, 1);
    var tail = s.points.head.previous;
    tail.previous = tail;
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpPoints46.removePoint(s, false));
    assertEquals(2, s.points.size);
    assertEquals(9, s.points.version);
  }

  @Test
  void missingModuleOrPointListFails() {
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpPoints46.removePoint(null, false));
    var s = state();
    s.points = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpPoints46.removePoint(s, false));
  }

  @Test
  void refreshWithNoSpecialPointsSkipsMaximumAndDirtyStorage() {
    var s = state(0, 0);
    s.dirty.entries = null;
    s.dirty.size = 77;
    s.maximumPresent = true;
    s.optionalMaximum = 99;
    assertDoesNotThrow(() -> RogueSpecialBpPoints46.refresh(s, null));
    assertEquals(9, s.points.version);
  }

  @Test
  void reservationCanPreventConversion() {
    var s = state(1, 0, 0);
    s.dirty = RogueSpecialBpWithdrawal46Test.state(1).dirty;
    RogueSpecialBpPoints46.refresh(s, null);
    assertEquals(1, s.points.head.special);
  }

  @Test
  void firstSpecialPointConvertsWithoutModifierOrWorld() {
    var s = state(1, 0);
    RogueSpecialBpPoints46.refresh(s, null);
    assertEquals(0, s.points.head.special);
    assertEquals(2, s.points.size);
    assertEquals(9, s.points.version);
  }

  @Test
  void forwardRefreshSkipsNormalAndConvertsOnlyRequiredSpecialCount() {
    var s = state(0, 1, 1, 1);
    s.fallbackMaximum = 3;
    Costs costs = new Costs();
    RogueSpecialBpPoints46.refresh(s, costs);
    assertEquals(0, s.points.head.next.special);
    assertEquals(0, s.points.head.next.next.special);
    assertEquals(1, s.points.head.previous.special);
    assertTrue(costs.owners.isEmpty());
  }

  @Test
  void refreshUsesPresentOptionalMaximum() {
    var s = state(1, 1, 1);
    s.maximumPresent = true;
    s.optionalMaximum = 1;
    s.fallbackMaximum = 99;
    RogueSpecialBpPoints46.refresh(s, null);
    assertEquals(0, s.points.head.special);
    assertEquals(1, s.points.head.next.special);
  }

  @Test
  void absentOptionalMaximumDoesNotUseItsStaleValue() {
    var s = state(1, 1);
    s.optionalMaximum = -99;
    RogueSpecialBpPoints46.refresh(s, null);
    assertEquals(0, s.points.head.special);
    assertEquals(0, s.points.head.next.special);
  }

  @Test
  void negativeMaximumLeavesSpecialPoints() {
    var s = state(1, 1);
    s.fallbackMaximum = -2;
    RogueSpecialBpPoints46.refresh(s, null);
    assertEquals(1, s.points.head.special);
    assertEquals(1, s.points.head.next.special);
  }

  @Test
  void convertingPreservesOtherValueFieldsAndClearsWholeFlagByte() {
    var s = state(3);
    var p = s.points.head;
    p.firstFlag = true;
    p.firstTag = new String("first");
    p.secondTag = new String("second");
    p.displayStyle = 37;
    p.registrationId = -17;
    p.modifier = modifier(2);
    var tag = p.firstTag;
    var second = p.secondTag;
    RogueSpecialBpPoints46.refresh(s, null);
    assertEquals(0, p.special);
    assertNull(p.modifier);
    assertTrue(p.firstFlag);
    assertSame(tag, p.firstTag);
    assertSame(second, p.secondTag);
    assertEquals(37, p.displayStyle);
    assertEquals(-17, p.registrationId);
  }

  @Test
  void inactiveModifierIsClearedWithoutCallingWorld() {
    var s = state(1);
    s.points.head.modifier = modifier(0);
    assertDoesNotThrow(() -> RogueSpecialBpPoints46.refresh(s, null));
    assertNull(s.points.head.modifier);
  }

  @Test
  void activeModifierUsesSharedCleanupStateAfterPointClear() {
    var s = state(1);
    var p = s.points.head;
    var m = modifier(1);
    p.modifier = m;
    p.registrationId = 27;
    Costs costs = new Costs();
    costs.callback =
        () -> {
          assertEquals(0, p.special);
          assertNull(p.modifier);
          assertEquals(1, m.candidate.state);
          assertSame(s.points, p.owner);
        };
    RogueSpecialBpPoints46.refresh(s, costs);
    assertEquals(List.of(m), costs.owners);
    assertEquals(List.of(27), costs.registrationIds);
  }

  @Test
  void costCallbackFailureKeepsCurrentValueClearAndLaterPointsSpecial() {
    var s = state(1, 1);
    var head = s.points.head;
    head.modifier = modifier(1);
    Costs costs = new Costs();
    costs.callback =
        () -> {
          throw new IllegalStateException();
        };
    assertThrows(IllegalStateException.class, () -> RogueSpecialBpPoints46.refresh(s, costs));
    assertEquals(0, head.special);
    assertNull(head.modifier);
    assertEquals(1, head.next.special);
    assertEquals(2, s.points.size);
    assertEquals(9, s.points.version);
  }

  @Test
  void missingWorldFailsAfterCurrentPointClear() {
    var s = state(1);
    var head = s.points.head;
    head.modifier = modifier(1);
    assertThrows(IllegalArgumentException.class, () -> RogueSpecialBpPoints46.refresh(s, null));
    assertEquals(0, head.special);
    assertNull(head.modifier);
  }

  @Test
  void callbackMaximumMutationDoesNotChangeCapturedConversionCount() {
    var s = state(1, 1);
    s.points.head.modifier = modifier(1);
    Costs costs = new Costs();
    costs.callback = () -> s.fallbackMaximum = 0;
    RogueSpecialBpPoints46.refresh(s, costs);
    assertEquals(0, s.points.head.special);
    assertEquals(0, s.points.head.next.special);
  }

  @Test
  void callbackCanChangeNextPointBeforeForwardTraversal() {
    var s = state(1, 1);
    var first = s.points.head;
    var oldNext = first.next;
    var next = new RogueSpecialBpWithdrawal46.Point();
    next.special = 1;
    next.next = first;
    next.owner = s.points;
    first.modifier = modifier(1);
    Costs costs = new Costs();
    costs.callback = () -> first.next = next;
    RogueSpecialBpPoints46.refresh(s, costs);
    assertEquals(0, next.special);
    assertEquals(1, oldNext.special);
  }

  @Test
  void callbackClearingNextEndsBeforeOwnerDereference() {
    var s = state(1, 1);
    var first = s.points.head;
    first.modifier = modifier(1);
    Costs costs = new Costs();
    costs.callback =
        () -> {
          first.next = null;
          first.owner = null;
        };
    assertDoesNotThrow(() -> RogueSpecialBpPoints46.refresh(s, costs));
    assertEquals(1, s.points.head.previous.special);
  }

  @Test
  void ownerIsCheckedEvenAfterLastRequiredConversion() {
    var s = state(1, 1);
    s.fallbackMaximum = 1;
    s.points.head.modifier = modifier(1);
    Costs costs = new Costs();
    costs.callback = () -> s.points.head.owner = null;
    assertThrows(IllegalArgumentException.class, () -> RogueSpecialBpPoints46.refresh(s, costs));
    assertEquals(0, s.points.head.special);
    assertEquals(1, s.points.head.next.special);
  }

  @Test
  void nextOwnerHeadCanEndConversionEarly() {
    var s = state(1, 1);
    var first = s.points.head;
    first.modifier = modifier(1);
    Costs costs = new Costs();
    var foreign = new RogueSpecialBpWithdrawal46.Points();
    foreign.head = first.next;
    costs.callback = () -> first.owner = foreign;
    RogueSpecialBpPoints46.refresh(s, costs);
    assertEquals(0, first.special);
    assertEquals(1, first.next.special);
  }

  @Test
  void failedCallbackRetryDoesNotRepeatClearedModifierCallback() {
    var s = state(1, 1);
    s.points.head.modifier = modifier(1);
    Costs costs = new Costs();
    costs.callback =
        () -> {
          throw new IllegalStateException();
        };
    assertThrows(IllegalStateException.class, () -> RogueSpecialBpPoints46.refresh(s, costs));
    costs.callback = () -> {};
    RogueSpecialBpPoints46.refresh(s, costs);
    assertEquals(1, costs.owners.size());
    assertEquals(0, s.points.head.next.special);
  }

  @Test
  void exactFinitePointLimitCanBeConverted() {
    int[] flags = new int[255];
    java.util.Arrays.fill(flags, 1);
    var s = state(flags);
    RogueSpecialBpPoints46.refresh(s, null);
    assertEquals(0, RogueSpecialBpWithdrawal46.specialPointCount(s));
    assertEquals(255, s.points.size);
    assertEquals(9, s.points.version);
  }

  @Test
  void malformedPointCounterFailsBeforeAnyValueClear() {
    var s = state(1, 1);
    s.points.head.next.next = s.points.head.next;
    assertThrows(IllegalArgumentException.class, () -> RogueSpecialBpPoints46.refresh(s, null));
    assertEquals(1, s.points.head.special);
    assertEquals(1, s.points.head.next.special);
  }

  static RogueSpecialBpWithdrawal46.World changes(
      RogueSpecialBpWithdrawal46.State state, Costs costs, List<Integer> notifications) {
    return new RogueSpecialBpWithdrawal46.World() {
      public void removeNormalPoint(RogueSpecialBpWithdrawal46.State s, boolean flag) {
        assertSame(state, s);
        RogueSpecialBpPoints46.removePoint(s, flag);
      }

      public void refresh(RogueSpecialBpWithdrawal46.State s) {
        assertSame(state, s);
        RogueSpecialBpPoints46.refresh(s, costs);
      }

      public void notifyGlobal(int event, int value, boolean immediate) {
        assertEquals(869, event);
        assertTrue(immediate);
        notifications.add(value);
      }
    };
  }

  @Test
  void actualSpecialChangeRemovesNormalTailBeforeRefreshingSpecials() {
    var s = state(1, 0, 0, 0, 0);
    s.fallbackMaximum = 5;
    Costs costs = new Costs();
    List<Integer> notifications = new ArrayList<>();
    RogueSpecialBpWithdrawal46.changed(s, 1, 3, changes(s, costs, notifications));
    assertEquals(3, s.points.size);
    assertEquals(11, s.points.version);
    assertEquals(0, RogueSpecialBpWithdrawal46.specialPointCount(s));
    assertEquals(List.of(3), notifications);
  }

  @Test
  void actualRegistrationRemovalRefreshesPointsAndNotifiesAfterCostCallback() {
    var s = state(1, 0, 0);
    var registration = RogueSpecialBpWithdrawal46Test.state(3);
    s.dirty = registration.dirty;
    s.fallbackMaximum = 4;
    s.points.head.modifier = modifier(1);
    s.points.head.registrationId = 29;
    Costs costs = new Costs();
    List<Integer> notifications = new ArrayList<>();
    costs.callback =
        () -> {
          assertTrue(notifications.isEmpty());
          assertEquals(0, s.dirty.size);
        };
    RogueSpecialBpWithdrawal46.remove(
        s, RogueSpecialBpWithdrawal46Test.MODIFIER, changes(s, costs, notifications));
    assertEquals(List.of(29), costs.registrationIds);
    assertEquals(List.of(0), notifications);
    assertEquals(0, s.points.head.special);
    assertNull(s.points.head.modifier);
  }

  @Test
  void actualChangeCostFailureStopsIntegerNotificationButKeepsPointClear() {
    var s = state(1);
    s.points.head.modifier = modifier(1);
    Costs costs = new Costs();
    costs.callback =
        () -> {
          throw new IllegalStateException();
        };
    List<Integer> notifications = new ArrayList<>();
    assertThrows(
        IllegalStateException.class,
        () -> RogueSpecialBpWithdrawal46.changed(s, 1, 0, changes(s, costs, notifications)));
    assertTrue(notifications.isEmpty());
    assertEquals(0, s.points.head.special);
    assertNull(s.points.head.modifier);
  }
}
