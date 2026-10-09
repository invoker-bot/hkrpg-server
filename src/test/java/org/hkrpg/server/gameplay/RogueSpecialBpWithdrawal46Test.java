package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RogueSpecialBpWithdrawal46Test {
  static final Object MODIFIER = new Object(), OTHER = new Object();

  static RogueSpecialBpWithdrawal46.State state(int... values) {
    var s = new RogueSpecialBpWithdrawal46.State();
    s.fallbackMaximum = 10;
    s.dirty = new RogueSpecialBpWithdrawal46.DirtyEntries();
    s.dirty.size = values.length;
    s.dirty.version = 7;
    s.dirty.entries = new RogueModifierUnstack46.BpDirty[values.length + 1];
    for (int i = 0; i < values.length; i++)
      s.dirty.entries[i] =
          new RogueModifierUnstack46.BpDirty(OTHER, i == 0 ? MODIFIER : OTHER, values[i]);
    s.points = points(0, 0, 0, 0, 0);
    return s;
  }

  static RogueSpecialBpWithdrawal46.Points points(int... flags) {
    var list = new RogueSpecialBpWithdrawal46.Points();
    list.size = flags.length;
    var nodes = new RogueSpecialBpWithdrawal46.Point[flags.length];
    for (int i = 0; i < flags.length; i++) {
      nodes[i] = new RogueSpecialBpWithdrawal46.Point();
      nodes[i].special = (byte) flags[i];
      nodes[i].owner = list;
    }
    if (flags.length > 0) {
      list.head = nodes[0];
      for (int i = 0; i < flags.length; i++) nodes[i].next = nodes[(i + 1) % flags.length];
    }
    return list;
  }

  static class World implements RogueSpecialBpWithdrawal46.World {
    final List<String> order = new ArrayList<>();
    int event, value;
    boolean immediate;
    Runnable removing = () -> {}, refreshing = () -> {}, notifying = () -> {};

    public void removeNormalPoint(RogueSpecialBpWithdrawal46.State s, boolean flag) {
      assertFalse(flag);
      order.add("remove");
      removing.run();
    }

    public void refresh(RogueSpecialBpWithdrawal46.State s) {
      order.add("refresh");
      refreshing.run();
    }

    public void notifyGlobal(int event, int value, boolean immediate) {
      order.add("notify");
      this.event = event;
      this.value = value;
      this.immediate = immediate;
      notifying.run();
    }
  }

  @Test
  void absentDirtyListReturnsZeroEvenWithNegativeMaximum() {
    var s = state();
    s.dirty = null;
    s.fallbackMaximum = -9;
    assertEquals(0, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void emptyDirtyListDoesNotReadStorageOrApplyMaximum() {
    var s = state();
    s.dirty.entries = null;
    s.fallbackMaximum = -9;
    assertEquals(0, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void negativeCountSkipsStorageButStillAppliesMaximum() {
    var s = state();
    s.dirty.size = -1;
    s.dirty.entries = null;
    s.fallbackMaximum = -9;
    assertEquals(-9, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void signedSumUsesAllEffectiveSlotsAndIgnoresUnusedTail() {
    var s = state(3, -2, 4);
    s.dirty.entries[3] = new RogueModifierUnstack46.BpDirty(null, null, 99);
    assertEquals(5, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void nullValueSlotContributesDefaultZero() {
    var s = state(3, 5);
    s.dirty.entries[0] = null;
    assertEquals(5, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void optionalMaximumHasPriorityOverFallback() {
    var s = state(8);
    s.maximumPresent = true;
    s.optionalMaximum = 3;
    assertEquals(3, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void absentOptionalDoesNotUseStaleValue() {
    var s = state(8);
    s.optionalMaximum = -9;
    assertEquals(8, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void negativeMaximumAndNegativeSumRemainSigned() {
    var s = state(-4);
    s.fallbackMaximum = -9;
    assertEquals(-9, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void sumUsesNativeIntOverflowBeforeClamping() {
    var s = state(Integer.MAX_VALUE, 1);
    assertEquals(Integer.MIN_VALUE, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void missingPositiveStorageFailsBeforeChangingHeaders() {
    var s = state(1);
    s.dirty.entries = null;
    assertThrows(IllegalArgumentException.class, () -> RogueSpecialBpWithdrawal46.reserved(s));
    assertEquals(1, s.dirty.size);
    assertEquals(7, s.dirty.version);
  }

  @Test
  void shortPositiveStorageDoesNotSilentlySkipEntry() {
    var s = state(1, 2);
    s.dirty.entries = new RogueModifierUnstack46.BpDirty[1];
    assertThrows(IndexOutOfBoundsException.class, () -> RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void overLimitPositiveListIsRejectedWithoutWrites() {
    var s = state();
    s.dirty.size = 256;
    assertThrows(IllegalArgumentException.class, () -> RogueSpecialBpWithdrawal46.reserved(s));
    assertEquals(256, s.dirty.size);
    assertEquals(7, s.dirty.version);
  }

  @Test
  void exactLimitCanBeSummed() {
    var s = state();
    s.dirty.size = 255;
    s.dirty.entries = new RogueModifierUnstack46.BpDirty[255];
    assertEquals(0, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void spentCounterUsesOnlyFlagLowBit() {
    var s = state();
    s.points = points(0, 1, 2, 3, 255);
    assertEquals(3, RogueSpecialBpWithdrawal46.specialPointCount(s));
  }

  @Test
  void emptyHeadReturnsZeroRegardlessOfSize() {
    var s = state();
    s.points = points();
    s.points.size = 93;
    assertEquals(0, RogueSpecialBpWithdrawal46.specialPointCount(s));
  }

  @Test
  void nullNextEndsBeforeOwnerDereference() {
    var s = state();
    s.points = points(1);
    s.points.head.next = null;
    s.points.head.owner = null;
    assertEquals(1, RogueSpecialBpWithdrawal46.specialPointCount(s));
  }

  @Test
  void nonNullNextNeedsOwnerEvenForOneNodeCircle() {
    var s = state();
    s.points = points(1);
    s.points.head.owner = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpWithdrawal46.specialPointCount(s));
  }

  @Test
  void endOfCircleUsesCurrentNodeOwnerHead() {
    var s = state();
    s.points = points(1, 1);
    var foreign = new RogueSpecialBpWithdrawal46.Points();
    foreign.head = s.points.head.next;
    s.points.head.owner = foreign;
    assertEquals(1, RogueSpecialBpWithdrawal46.specialPointCount(s));
  }

  @Test
  void malformedCircleCannotLoopForever() {
    var s = state();
    s.points = points(1, 1);
    s.points.head.next.next = s.points.head.next;
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpWithdrawal46.specialPointCount(s));
  }

  @Test
  void exactLimitPointCircleCountsAllNodes() {
    var s = state();
    int[] flags = new int[255];
    java.util.Arrays.fill(flags, 1);
    s.points = points(flags);
    assertEquals(255, RogueSpecialBpWithdrawal46.specialPointCount(s));
  }

  @Test
  void missingPointListFails() {
    var s = state();
    s.points = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpWithdrawal46.specialPointCount(s));
  }

  @Test
  void removesFirstMatchingIdentityAndShiftsSuffixThenZeroesTail() {
    var s = state(4, 2, 3);
    s.dirty.entries[1] = new RogueModifierUnstack46.BpDirty(null, MODIFIER, 2);
    var duplicate = s.dirty.entries[1];
    World w = new World();
    RogueSpecialBpWithdrawal46.remove(s, MODIFIER, w);
    assertEquals(2, s.dirty.size);
    assertEquals(8, s.dirty.version);
    assertSame(duplicate, s.dirty.entries[0]);
    assertNull(s.dirty.entries[2]);
    assertEquals(List.of("refresh", "notify"), w.order);
    assertEquals(5, w.value);
    assertEquals(869, w.event);
    assertTrue(w.immediate);
  }

  @Test
  void noIdentityMatchDoesNotChangeListOrNotify() {
    var s = state(4);
    var original = s.dirty.entries[0];
    RogueSpecialBpWithdrawal46.remove(s, new Object(), null);
    assertEquals(1, s.dirty.size);
    assertEquals(7, s.dirty.version);
    assertSame(original, s.dirty.entries[0]);
  }

  @Test
  void nullModifierCanMatchDefaultValueSlot() {
    var s = state(1, 3);
    s.dirty.entries[0] = null;
    RogueSpecialBpWithdrawal46.remove(s, null, null);
    assertEquals(1, s.dirty.size);
    assertEquals(8, s.dirty.version);
    assertEquals(3, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void zeroValueRemovalDoesNotNotifyWhenEffectiveTotalUnchanged() {
    var s = state(0, 3);
    RogueSpecialBpWithdrawal46.remove(s, MODIFIER, null);
    assertEquals(1, s.dirty.size);
    assertEquals(8, s.dirty.version);
  }

  @Test
  void clampedRemovalCanLeaveEffectiveTotalUnchanged() {
    var s = state(4, 9);
    s.fallbackMaximum = 3;
    RogueSpecialBpWithdrawal46.remove(s, MODIFIER, null);
    assertEquals(1, s.dirty.size);
    assertEquals(3, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void lastEntryRemovalReturnsZeroWithoutApplyingNegativeMaximum() {
    var s = state(3);
    s.fallbackMaximum = -4;
    World w = new World();
    s.points = points();
    RogueSpecialBpWithdrawal46.remove(s, MODIFIER, w);
    assertEquals(0, w.value);
    assertEquals(0, s.dirty.size);
    assertEquals(List.of("refresh", "notify"), w.order);
  }

  @Test
  void absentDirtyListRemovalFailsAfterReadWithoutWorld() {
    var s = state();
    s.dirty = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpWithdrawal46.remove(s, MODIFIER, null));
  }

  @Test
  void emptyDirtyRemovalSkipsStorageAndWorld() {
    var s = state();
    s.dirty.entries = null;
    assertDoesNotThrow(() -> RogueSpecialBpWithdrawal46.remove(s, MODIFIER, null));
    assertEquals(7, s.dirty.version);
  }

  @Test
  void negativeDirtyRemovalSkipsStorageAndWorld() {
    var s = state();
    s.dirty.size = -8;
    s.dirty.entries = null;
    assertDoesNotThrow(() -> RogueSpecialBpWithdrawal46.remove(s, MODIFIER, null));
    assertEquals(-8, s.dirty.size);
    assertEquals(7, s.dirty.version);
  }

  @Test
  void missingChangeWorldFailsAfterListRemovalAndVersion() {
    var s = state(4, 2);
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpWithdrawal46.remove(s, MODIFIER, null));
    assertEquals(1, s.dirty.size);
    assertEquals(8, s.dirty.version);
    assertNull(s.dirty.entries[1]);
    assertEquals(2, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void refreshFailurePreservesRemovedListAndStopsNotify() {
    var s = state(4, 2);
    World w = new World();
    w.refreshing =
        () -> {
          throw new IllegalStateException();
        };
    assertThrows(
        IllegalStateException.class, () -> RogueSpecialBpWithdrawal46.remove(s, MODIFIER, w));
    assertEquals(1, s.dirty.size);
    assertEquals(8, s.dirty.version);
    assertEquals(List.of("refresh"), w.order);
  }

  @Test
  void notifyFailureDoesNotUndoRemovalOrRefresh() {
    var s = state(4, 2);
    World w = new World();
    w.notifying =
        () -> {
          throw new IllegalStateException();
        };
    assertThrows(
        IllegalStateException.class, () -> RogueSpecialBpWithdrawal46.remove(s, MODIFIER, w));
    assertEquals(1, s.dirty.size);
    assertEquals(List.of("refresh", "notify"), w.order);
  }

  @Test
  void capturedAfterTotalIsNotRecomputedAfterRefreshCallback() {
    var s = state(4, 2);
    World w = new World();
    w.refreshing = () -> s.dirty.entries[0] = new RogueModifierUnstack46.BpDirty(null, OTHER, 9);
    RogueSpecialBpWithdrawal46.remove(s, MODIFIER, w);
    assertEquals(2, w.value);
    assertEquals(9, RogueSpecialBpWithdrawal46.reserved(s));
  }

  @Test
  void removingNegativeRegistrationCanConsumeNormalPoints() {
    var s = state(-2, 5);
    s.fallbackMaximum = 6;
    World w = new World();
    RogueSpecialBpWithdrawal46.remove(s, MODIFIER, w);
    assertEquals(List.of("remove", "remove", "refresh", "notify"), w.order);
    assertEquals(5, w.value);
  }

  @ParameterizedTest
  @CsvSource({
    "6,3,5,5,2",
    "6,3,5,2,1",
    "6,3,5,1,0",
    "6,3,5,0,0",
    "6,0,6,5,5",
    "6,5,7,5,1",
    "6,9,10,5,0"
  })
  void changeFormulaRespectsAvailableNormalPointsAndReservedIncrease(
      int max, int before, int after, int available, int expected) {
    var s = state();
    s.fallbackMaximum = max;
    s.points = points(new int[available]);
    World w = new World();
    RogueSpecialBpWithdrawal46.changed(s, before, after, w);
    assertEquals(expected, w.order.stream().filter("remove"::equals).count());
    assertEquals(List.of("refresh", "notify"), w.order.subList(w.order.size() - 2, w.order.size()));
    assertEquals(after, w.value);
  }

  @Test
  void spentSpecialFlagsReduceAvailableNormalPoints() {
    var s = state();
    s.fallbackMaximum = 6;
    s.points = points(1, 1, 0, 0, 0);
    World w = new World();
    RogueSpecialBpWithdrawal46.changed(s, 3, 5, w);
    assertEquals(2, w.order.stream().filter("remove"::equals).count());
  }

  @Test
  void decreaseAndEqualChangeDoNotRequirePointStorage() {
    var s = state();
    s.points = null;
    World w = new World();
    RogueSpecialBpWithdrawal46.changed(s, 4, 2, w);
    RogueSpecialBpWithdrawal46.changed(s, 2, 2, w);
    assertEquals(List.of("refresh", "notify", "refresh", "notify"), w.order);
  }

  @Test
  void increaseRequiresPointsBeforeRefresh() {
    var s = state();
    s.points = null;
    World w = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpWithdrawal46.changed(s, 2, 4, w));
    assertTrue(w.order.isEmpty());
  }

  @Test
  void pointCountErrorDoesNotRefreshOrNotify() {
    var s = state();
    s.points.head.owner = null;
    World w = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpWithdrawal46.changed(s, 2, 4, w));
    assertTrue(w.order.isEmpty());
  }

  @Test
  void pointRemovalFailureStopsCapturedLoopAndNotification() {
    var s = state();
    s.fallbackMaximum = 6;
    World w = new World();
    w.removing =
        () -> {
          throw new IllegalStateException();
        };
    assertThrows(IllegalStateException.class, () -> RogueSpecialBpWithdrawal46.changed(s, 3, 5, w));
    assertEquals(List.of("remove"), w.order);
  }

  @Test
  void removeLoopCountStaysCapturedAcrossCallbackMaximumMutation() {
    var s = state();
    s.fallbackMaximum = 6;
    World w = new World();
    w.removing = () -> s.fallbackMaximum = 0;
    RogueSpecialBpWithdrawal46.changed(s, 3, 5, w);
    assertEquals(List.of("remove", "remove", "refresh", "notify"), w.order);
    assertEquals(5, w.value);
  }

  @Test
  void excessiveRemovalCountIsRejectedBeforeAnyNormalRemoval() {
    var s = state();
    s.fallbackMaximum = 600;
    s.points = points();
    s.points.size = 600;
    World w = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueSpecialBpWithdrawal46.changed(s, 0, 600, w));
    assertTrue(w.order.isEmpty());
  }

  @Test
  void notificationStillUsesSuppliedAfterValueIncludingNegative() {
    var s = state();
    s.points = null;
    World w = new World();
    RogueSpecialBpWithdrawal46.changed(s, 1, -7, w);
    assertEquals(-7, w.value);
    assertEquals(869, w.event);
    assertTrue(w.immediate);
  }

  static RogueModifierUnstack46.World integrated(
      RogueModifierUnstack46Test.Fixture f,
      RogueSpecialBpWithdrawal46.State module,
      World changes) {
    return (RogueModifierUnstack46.World)
        Proxy.newProxyInstance(
            RogueSpecialBpWithdrawal46Test.class.getClassLoader(),
            new Class<?>[] {RogueModifierUnstack46.World.class},
            (p, m, a) -> {
              if (m.getName().equals("removeSpecialBp")) {
                RogueSpecialBpWithdrawal46.remove(module, a[1], changes);
                f.step("specialBp");
                return null;
              }
              try {
                return m.invoke(f.world, a);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  @Test
  void actualUnstackUsesRealModuleRemovalBeforeDirtyZeroAndOtherWithdrawals() {
    var f = new RogueModifierUnstack46Test.Fixture();
    var module = state(4, 2);
    module.dirty.entries[0] =
        new RogueModifierUnstack46.BpDirty(null, f.cleanup.sequence.replacement, 4);
    World changes = new World();
    changes.refreshing =
        () -> {
          assertNotSame(RogueModifierUnstack46.BpDirty.ZERO, f.state.bpDirty);
          assertEquals(1, f.registrations.names.count);
        };
    RogueModifierUnstack46.unstack(f.state, integrated(f, module, changes));
    assertEquals(RogueModifierUnstack46Test.ORDER, f.order);
    assertEquals(1, module.dirty.size);
    assertEquals(8, module.dirty.version);
    assertSame(RogueModifierUnstack46.BpDirty.ZERO, f.state.bpDirty);
    assertEquals(2, changes.value);
  }

  @Test
  void actualUnstackModuleNotifyFailureKeepsDirtyAndStopsRegistrationTail() {
    var f = new RogueModifierUnstack46Test.Fixture();
    var module = state(4, 2);
    module.dirty.entries[0] =
        new RogueModifierUnstack46.BpDirty(null, f.cleanup.sequence.replacement, 4);
    World changes = new World();
    changes.notifying =
        () -> {
          throw new IllegalStateException();
        };
    var old = f.state.bpDirty;
    assertThrows(
        IllegalStateException.class,
        () -> RogueModifierUnstack46.unstack(f.state, integrated(f, module, changes)));
    assertSame(old, f.state.bpDirty);
    assertEquals(1, module.dirty.size);
    assertEquals(8, module.dirty.version);
    assertEquals(1, f.registrations.names.count);
    assertSame(RogueModifierUnstack46Test.CHARACTER, f.owned.characterChange);
  }

  @Test
  void retryAfterModuleNotificationFailureDoesNotDuplicateModuleRemoval() {
    var f = new RogueModifierUnstack46Test.Fixture();
    var module = state(4, 2);
    module.dirty.entries[0] =
        new RogueModifierUnstack46.BpDirty(null, f.cleanup.sequence.replacement, 4);
    World changes = new World();
    changes.notifying =
        () -> {
          throw new IllegalStateException();
        };
    var integrated = integrated(f, module, changes);
    assertThrows(
        IllegalStateException.class, () -> RogueModifierUnstack46.unstack(f.state, integrated));
    changes.notifying = () -> {};
    RogueModifierUnstack46.unstack(f.state, integrated);
    assertEquals(1, module.dirty.size);
    assertEquals(8, module.dirty.version);
    assertEquals(List.of("refresh", "notify"), changes.order);
    assertSame(RogueModifierUnstack46.BpDirty.ZERO, f.state.bpDirty);
    assertNull(f.owned.unselectable);
  }
}
