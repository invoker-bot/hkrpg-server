package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueCharacterWeaknessStack46.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueCharacterWeaknessStack46Test {
  @ParameterizedTest
  @ValueSource(ints = {-1, 4, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void unknownOperationIsClosedBeforeSearchOrGrowth(int op) {
    var s = state();
    var a = s.overrideStack;
    assertThrows(IllegalArgumentException.class, () -> tryAvailable(s, op));
    assertThrows(IllegalArgumentException.class, () -> available(s, op));
    assertThrows(IllegalArgumentException.class, () -> reserve(s, op));
    assertThrows(IllegalArgumentException.class, () -> add(s, op, 1, null));
    assertSame(a, s.overrideStack);
  }

  @Test
  void nullStateIsClosedForEveryEntry() {
    assertThrows(IllegalArgumentException.class, () -> tryAvailable(null, 0));
    assertThrows(IllegalArgumentException.class, () -> available(null, 0));
    assertThrows(IllegalArgumentException.class, () -> reserve(null, 0));
    assertThrows(IllegalArgumentException.class, () -> add(null, 0, 1, null));
  }

  @Test
  void excessiveGrowthIsRejectedBeforePublishingEitherArray() {
    var s = state();
    s.overrideStack = new int[128];
    s.overrideOccupy = new boolean[128];
    var a = s.overrideStack;
    var b = s.overrideOccupy;
    assertThrows(IllegalArgumentException.class, () -> reserve(s, 0));
    assertSame(a, s.overrideStack);
    assertSame(b, s.overrideOccupy);
  }

  @Test
  void exhaustedSearchBeyondFiniteBoundaryIsClosed() {
    var s = state();
    s.overrideOccupy = new boolean[256];
    Arrays.fill(s.overrideOccupy, true);
    assertThrows(IllegalArgumentException.class, () -> tryAvailable(s, 0));
  }

  @Test
  void actualStackedReceiptCanWithdrawSlotAddedThroughThisEntry() {
    var s = state();
    Arrays.fill(s.overrideOccupy, false);
    int index = add(s, 0, 2, RogueCharacterWeakness46Test.world());
    var effects = RogueModifierStackedEffects46Test.state();
    effects.weaknesses.add(
        new RogueModifierStackedEffects46.Weakness(
            RogueModifierStackedEffects46Test.ABILITY, 0, index));
    var w =
        new RogueModifierStackedEffects46Test.World() {
          public Object weaknessComponent(Object c) {
            return s;
          }

          public void unstackWeakness(Object weakness, Object entity, int op, int receipt) {
            assertSame(s, weakness);
            RogueCharacterWeakness46.unstack(s, op, receipt, RogueCharacterWeakness46Test.world());
          }
        };
    RogueModifierStackedEffects46.popWeakness(effects, w);
    assertEquals(4, s.overrideMask);
    assertFalse(s.overrideOccupy[index]);
    assertTrue(effects.weaknesses.isEmpty());
  }

  @Test
  void finiteSuccessfulGrowthAllows254Slots() {
    var s = state();
    s.overrideStack = new int[127];
    s.overrideOccupy = new boolean[127];
    Arrays.fill(s.overrideOccupy, true);
    assertEquals(127, available(s, 0));
    assertEquals(254, s.overrideStack.length);
    assertEquals(254, s.overrideOccupy.length);
  }

  static RogueCharacterWeakness46.State state() {
    return RogueCharacterWeakness46Test.state();
  }

  static int[] stack(RogueCharacterWeakness46.State s, int op) {
    return RogueCharacterWeakness46Test.stack(s, op);
  }

  static boolean[] occupy(RogueCharacterWeakness46.State s, int op) {
    return RogueCharacterWeakness46Test.occupy(s, op);
  }

  static void stack(RogueCharacterWeakness46.State s, int op, int[] a) {
    RogueCharacterWeakness46Test.stack(s, op, a);
  }

  static void occupy(RogueCharacterWeakness46.State s, int op, boolean[] a) {
    RogueCharacterWeakness46Test.occupy(s, op, a);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void firstUnoccupiedSlotIsReturnedWithoutCheckingStack(int op) {
    var s = state();
    occupy(s, op, new boolean[] {true, false, false});
    stack(s, op, null);
    assertEquals(1, tryAvailable(s, op));
    assertEquals(1, available(s, op));
    assertNull(stack(s, op));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void fullOccupancyReturnsMinusOneWithoutGrowth(int op) {
    var s = state();
    var a = occupy(s, op);
    assertEquals(-1, tryAvailable(s, op));
    assertSame(a, occupy(s, op));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void reserveDoublesStackLengthAndPreservesBothArrays(int op) {
    var s = state();
    var a = stack(s, op);
    var b = occupy(s, op);
    reserve(s, op);
    assertEquals(4, stack(s, op).length);
    assertEquals(4, occupy(s, op).length);
    assertNotSame(a, stack(s, op));
    assertNotSame(b, occupy(s, op));
    assertEquals(a[0], stack(s, op)[0]);
    assertEquals(a[1], stack(s, op)[1]);
    assertTrue(occupy(s, op)[0]);
    assertTrue(occupy(s, op)[1]);
    assertFalse(occupy(s, op)[2]);
    assertEquals(0, stack(s, op)[2]);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void fullAvailableReservesThenReturnsFirstNewSlot(int op) {
    var s = state();
    assertEquals(2, available(s, op));
    assertEquals(4, stack(s, op).length);
    assertFalse(occupy(s, op)[2]);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void additionUsesActualSlotAndSameUnderlyingRefresh(int op) {
    var s = state();
    occupy(s, op)[1] = false;
    int result = add(s, op, 1, RogueCharacterWeakness46Test.world());
    assertEquals(1, result);
    assertTrue(occupy(s, op)[1]);
    assertEquals(1, stack(s, op)[1]);
    assertFalse(s.activeDirty);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void additionMissingStackRetainsOccupancyWrite(int op) {
    var s = state();
    occupy(s, op)[1] = false;
    stack(s, op, null);
    assertThrows(IllegalArgumentException.class, () -> add(s, op, 1, null));
    assertTrue(occupy(s, op)[1]);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void additionShortStackRetainsOccupancyWrite(int op) {
    var s = state();
    occupy(s, op)[1] = false;
    stack(s, op, new int[1]);
    assertThrows(IndexOutOfBoundsException.class, () -> add(s, op, 1, null));
    assertTrue(occupy(s, op)[1]);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void failedOccupancyCopyDoesNotPublishNewStack(int op) {
    var s = state();
    var original = stack(s, op);
    occupy(s, op, new boolean[5]);
    var originalOccupy = occupy(s, op);
    assertThrows(IndexOutOfBoundsException.class, () -> reserve(s, op));
    assertSame(original, stack(s, op));
    assertSame(originalOccupy, occupy(s, op));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void missingOccupancyCopyDoesNotPublishNewStack(int op) {
    var s = state();
    var original = stack(s, op);
    occupy(s, op, null);
    assertThrows(IllegalArgumentException.class, () -> reserve(s, op));
    assertSame(original, stack(s, op));
  }

  @Test
  void zeroSizedArraysRemainZeroAfterSingleReserveAndSearch() {
    var s = state();
    s.overrideStack = new int[0];
    s.overrideOccupy = new boolean[0];
    assertEquals(-1, available(s, 0));
    assertEquals(0, s.overrideStack.length);
    assertThrows(IndexOutOfBoundsException.class, () -> add(s, 0, 1, null));
  }

  @Test
  void reserveUsesStackLengthForBothArraysEvenWhenOccupancyIsShorter() {
    var s = state();
    s.overrideOccupy = new boolean[] {true};
    reserve(s, 0);
    assertArrayEquals(new boolean[] {true, false, false, false}, s.overrideOccupy);
    assertArrayEquals(new int[] {1, 2, 0, 0}, s.overrideStack);
  }

  @Test
  void reserveLeavesOtherCategoriesAndCurrentMasksUntouched() {
    var s = state();
    var other = s.attachStack;
    var otherOccupy = s.attachOccupy;
    s.overrideMask = 99;
    s.activeDirty = true;
    reserve(s, 0);
    assertSame(other, s.attachStack);
    assertSame(otherOccupy, s.attachOccupy);
    assertEquals(99, s.overrideMask);
    assertTrue(s.activeDirty);
  }

  @Test
  void addReturnsReusableIndexForActualUnstackAndNextAddition() {
    var s = state();
    Arrays.fill(s.overrideOccupy, false);
    int first = add(s, 0, 1, RogueCharacterWeakness46Test.world());
    assertEquals(0, first);
    RogueCharacterWeakness46.unstack(s, 0, first, RogueCharacterWeakness46Test.world());
    assertEquals(4, s.overrideMask);
    int second = add(s, 0, 2, RogueCharacterWeakness46Test.world());
    assertEquals(first, second);
    assertEquals(2, s.overrideMask);
    assertEquals(List.of(new RogueCharacterWeakness46.View(2, 1)), s.allWithState);
  }

  @Test
  void notificationFailureKeepsNewSlotAndUpdatedViewsWithoutReturningReceipt() {
    var s = state();
    Arrays.fill(s.overrideOccupy, false);
    s.notifyChangeEnable = true;
    var marker = new IllegalStateException("notify");
    var w =
        new RogueCharacterWeakness46.World() {
          public int[] damageTypes() {
            return new int[] {1, 2};
          }

          public void notifyWeaknessChanged(Object o, int id, boolean b) {
            throw marker;
          }
        };
    assertSame(marker, assertThrows(IllegalStateException.class, () -> add(s, 0, 1, w)));
    assertTrue(s.overrideOccupy[0]);
    assertEquals(1, s.overrideStack[0]);
    assertEquals(1, s.overrideMask);
    assertTrue(s.activeDirty);
  }

  @Test
  void zeroMaskStillOccupiesSlotAndSuppressesDefault() {
    var s = state();
    Arrays.fill(s.overrideOccupy, false);
    int index = add(s, 0, 0, null);
    assertEquals(0, index);
    assertTrue(s.overrideOccupy[0]);
    assertEquals(0, s.overrideMask);
  }
}
