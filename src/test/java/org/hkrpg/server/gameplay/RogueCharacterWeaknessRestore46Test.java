package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueCharacterWeakness46.*;
import static org.hkrpg.server.gameplay.RogueCharacterWeakness46Test.*;
import static org.hkrpg.server.gameplay.RogueCharacterWeaknessRestore46.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueCharacterWeaknessRestore46Test {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void resetClearsOnlyRequestedBitsOfOccupiedSlotsAndKeepsOccupancy(int op) {
    var s = state();
    stack(s, op, new int[] {7, 7});
    occupy(s, op, new boolean[] {true, false});
    reset(s, op, 2, world());
    assertArrayEquals(new int[] {5, 7}, stack(s, op));
    assertArrayEquals(new boolean[] {true, false}, occupy(s, op));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void shortOccupancyKeepsCompletedSlotWritesWithoutRefreshing(int op) {
    var s = state();
    stack(s, op, new int[] {7, 7});
    occupy(s, op, new boolean[] {true});
    assertThrows(IndexOutOfBoundsException.class, () -> reset(s, op, 2, null));
    assertArrayEquals(new int[] {5, 7}, stack(s, op));
    assertFalse(s.activeDirty);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void resetEmptyStackDoesNotRequireOccupancy(int op) {
    var s = state();
    stack(s, op, new int[0]);
    occupy(s, op, null);
    reset(s, op, 7, world());
    assertEquals(0, stack(s, op).length);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void resetMissingStackFailsBeforeChangingAnyMask(int op) {
    var s = state();
    stack(s, op, null);
    s.allMask = 93;
    assertThrows(IllegalArgumentException.class, () -> reset(s, op, -1, null));
    assertEquals(93, s.allMask);
    assertFalse(s.activeDirty);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void resetAllBitsLeavesOccupiedZeroSlots(int op) {
    var s = state();
    stack(s, op, new int[] {7});
    occupy(s, op, new boolean[] {true});
    reset(s, op, -1, world());
    assertEquals(0, stack(s, op)[0]);
    assertTrue(occupy(s, op)[0]);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void resetOtherCategoriesAreUntouched(int op) {
    var s = state();
    int other = (op + 1) % 4;
    int[] saved = stack(s, other).clone();
    reset(s, op, -1, world());
    assertArrayEquals(saved, stack(s, other));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 4, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void unknownResetOperationFailsBeforeAccessingArrays(int op) {
    var s = state();
    assertThrows(IllegalArgumentException.class, () -> reset(s, op, 1, null));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 256, 257, 2, 512})
  void copyOptionsUseSeparateLowAndHighBooleanBits(int flags) {
    var source = state();
    var target = state();
    source.overrideStack[0] = 1;
    source.attachStack[0] = 2;
    source.protectStack[0] = 4;
    source.secretStack[0] = 3;
    target.protectStack[0] = 64;
    target.secretStack[0] = 64;
    copyFrom(target, source, flags, world());
    assertEquals((flags & 1) != 0 ? 4 : 64, target.protectStack[0]);
    assertEquals((flags & 256) != 0 ? 3 : 64, target.secretStack[0]);
    assertEquals(1, target.overrideStack[0]);
    assertEquals(2, target.attachStack[0]);
  }

  @Test
  void smallerSourceReusesTargetArraysAndZerosEveryTail() {
    var source = state();
    var target = state();
    source.overrideStack = new int[] {2};
    source.overrideOccupy = new boolean[] {true};
    target.overrideStack = new int[] {9, 9, 9};
    target.overrideOccupy = new boolean[] {true, true, true};
    var a = target.overrideStack;
    var b = target.overrideOccupy;
    copyFrom(target, source, 0, world());
    assertSame(a, target.overrideStack);
    assertSame(b, target.overrideOccupy);
    assertArrayEquals(new int[] {2, 0, 0}, a);
    assertArrayEquals(new boolean[] {true, false, false}, b);
  }

  @Test
  void largerSourceReplacesArraysWithIndependentExactLengthCopies() {
    var source = state();
    var target = state();
    source.overrideStack = new int[] {1, 2, 4};
    source.overrideOccupy = new boolean[] {true, true, true};
    var a = target.overrideStack;
    var b = target.overrideOccupy;
    copyFrom(target, source, 0, world());
    assertNotSame(a, target.overrideStack);
    assertNotSame(b, target.overrideOccupy);
    assertNotSame(source.overrideStack, target.overrideStack);
    assertNotSame(source.overrideOccupy, target.overrideOccupy);
    source.overrideStack[0] = 64;
    source.overrideOccupy[0] = false;
    assertEquals(1, target.overrideStack[0]);
    assertTrue(target.overrideOccupy[0]);
  }

  @Test
  void failedOccupancyCopyKeepsCopiedMasksAndStackButDoesNotStartAttach() {
    var source = state();
    var target = state();
    source.overrideMask = 29;
    source.defaultMask = 64;
    source.overrideStack = new int[] {4};
    source.overrideOccupy = null;
    target.attachMask = 91;
    assertThrows(IllegalArgumentException.class, () -> copyFrom(target, source, 0, null));
    assertEquals(29, target.overrideMask);
    assertEquals(64, target.defaultMask);
    assertArrayEquals(new int[] {4, 0}, target.overrideStack);
    assertEquals(91, target.attachMask);
  }

  @Test
  void missingTargetArrayDoesNotAutomaticallyAllocateFromSource() {
    var source = state();
    var target = state();
    target.overrideStack = null;
    source.defaultMask = 32;
    assertThrows(IllegalArgumentException.class, () -> copyFrom(target, source, 0, null));
    assertNull(target.overrideStack);
    assertEquals(32, target.defaultMask);
  }

  @Test
  void missingViewListKeepsFinalDirtyAndActiveZeroBeforeAllMaskClear() {
    var source = state();
    var target = state();
    target.allWithState = null;
    target.allMask = 97;
    assertThrows(IllegalArgumentException.class, () -> copyFrom(target, source, 0, world()));
    assertTrue(target.activeDirty);
    assertEquals(0, target.activeMask);
    assertEquals(97, target.allMask);
  }

  @Test
  void viewClearFailureKeepsOldAllMaskAndCopiedStacks() {
    var source = state();
    var target = state();
    target.allMask = 87;
    var marker = new IllegalStateException("clear");
    target.allWithState =
        new ArrayList<>() {
          public void clear() {
            throw marker;
          }
        };
    assertSame(
        marker,
        assertThrows(IllegalStateException.class, () -> copyFrom(target, source, 0, world())));
    assertTrue(target.activeDirty);
    assertEquals(0, target.activeMask);
    assertEquals(87, target.allMask);
  }

  @Test
  void copyPerformsTwoViewClearsAndKeepsTargetOwnerAndNotificationSetting() {
    var source = state();
    var target = state();
    int[] calls = {0};
    Object owner = new Object();
    target.owner = owner;
    target.allWithState =
        new ArrayList<>() {
          public void clear() {
            calls[0]++;
            super.clear();
          }
        };
    copyFrom(target, source, 0, world());
    assertEquals(2, calls[0]);
    assertSame(owner, target.owner);
    assertFalse(target.notifyChangeEnable);
    assertFalse(target.activeDirty);
  }

  @Test
  void copyNotificationFailureKeepsCopiedStateAndDirty() {
    var source = state();
    var target = state();
    target.notifyChangeEnable = true;
    var marker = new IllegalStateException("notify");
    var w =
        new World() {
          public int[] damageTypes() {
            return new int[] {1, 2, 4};
          }

          public void notifyWeaknessChanged(Object o, int i, boolean b) {
            throw marker;
          }
        };
    assertSame(
        marker, assertThrows(IllegalStateException.class, () -> copyFrom(target, source, 0, w)));
    assertTrue(target.activeDirty);
    assertNotSame(source.overrideStack, target.overrideStack);
  }

  @Test
  void selfCopyPreservesExistingArraysAndRebuildsViews() {
    var s = state();
    var a = s.overrideStack;
    copyFrom(s, s, 257, world());
    assertSame(a, s.overrideStack);
    assertFalse(s.activeDirty);
  }

  @Test
  void copyDoesNotTouchSpecialWeaknessList() {
    var source = state();
    var target = state();
    source.specialWeaknesses = new ArrayList<>(List.of(1));
    var special = new ArrayList<>(List.of(9));
    target.specialWeaknesses = special;
    copyFrom(target, source, 257, world());
    assertSame(special, target.specialWeaknesses);
    assertEquals(List.of(9), special);
  }

  @Test
  void initializeDefaultCombinesSignedRawMasksThenUsesActualFallback() {
    var s = state();
    Arrays.fill(s.overrideOccupy, false);
    initializeDefault(s, new int[] {1, 2, Integer.MIN_VALUE, 1}, world());
    assertEquals(Integer.MIN_VALUE | 3, s.defaultMask);
    assertEquals(s.defaultMask, s.overrideMask);
  }

  @Test
  void nullDefaultCatalogMeansZero() {
    var s = state();
    s.defaultMask = 8;
    Arrays.fill(s.overrideOccupy, false);
    initializeDefault(s, null, world());
    assertEquals(0, s.defaultMask);
  }

  @Test
  void defaultChangeDoesNotOverrideOccupiedZeroSlot() {
    var s = state();
    Arrays.fill(s.overrideStack, 0);
    initializeDefault(s, new int[] {4}, null);
    assertEquals(4, s.defaultMask);
    assertEquals(0, s.overrideMask);
  }

  @Test
  void defaultRefreshFailureKeepsAlreadyWrittenDefaultMask() {
    var s = state();
    s.overrideStack = null;
    assertThrows(
        IllegalArgumentException.class, () -> initializeDefault(s, new int[] {2, 4}, null));
    assertEquals(6, s.defaultMask);
  }

  @Test
  void addingSpecialIsUniqueAndDoesNotRefreshNormalWeaknesses() {
    var s = state();
    s.specialWeaknesses = new ArrayList<>(List.of(1));
    s.activeDirty = true;
    s.allMask = 83;
    updateSpecial(s, new SpecialEvent(true, 2));
    updateSpecial(s, new SpecialEvent(true, 2));
    assertEquals(List.of(1, 2), s.specialWeaknesses);
    assertTrue(s.activeDirty);
    assertEquals(83, s.allMask);
  }

  @Test
  void disablingSpecialRemovesOnlyFirstOccurrence() {
    var s = state();
    s.specialWeaknesses = new ArrayList<>(List.of(2, 1, 2));
    updateSpecial(s, new SpecialEvent(false, 2));
    assertEquals(List.of(1, 2), s.specialWeaknesses);
  }

  @Test
  void specialContainsCallbackReplacementAndCurrentEventTypeAreRespected() {
    var s = state();
    var event = new SpecialEvent(true, 1);
    var next = new ArrayList<Integer>();
    s.specialWeaknesses =
        new ArrayList<>() {
          public boolean contains(Object o) {
            assertEquals(1, o);
            s.specialWeaknesses = next;
            event.type = 2;
            return false;
          }
        };
    updateSpecial(s, event);
    assertEquals(List.of(2), next);
  }

  @Test
  void specialRemovalRereadsListAndEventType() {
    var s = state();
    var event = new SpecialEvent(false, 1);
    var next = new ArrayList<>(List.of(2));
    s.specialWeaknesses =
        new ArrayList<>() {
          public boolean contains(Object o) {
            s.specialWeaknesses = next;
            event.type = 2;
            return true;
          }
        };
    updateSpecial(s, event);
    assertTrue(next.isEmpty());
  }

  @Test
  void missingSpecialContainerNeverInstallsDefaults() {
    var s = state();
    assertThrows(IllegalArgumentException.class, () -> updateSpecial(s, new SpecialEvent(true, 1)));
    assertNull(s.specialWeaknesses);
  }

  @Test
  void finiteDefaultCatalogFailureDoesNotPublishMask() {
    var s = state();
    s.defaultMask = 91;
    assertThrows(IllegalArgumentException.class, () -> initializeDefault(s, new int[256], null));
    assertEquals(91, s.defaultMask);
  }

  @Test
  void finiteCopyFailurePreservesPriorMaskWrite() {
    var source = state();
    var target = state();
    source.overrideMask = 87;
    source.overrideStack = new int[256];
    assertThrows(IllegalArgumentException.class, () -> copyFrom(target, source, 0, null));
    assertEquals(87, target.overrideMask);
  }

  @Test
  void nullInputsAreClosedBeforeMutation() {
    var s = state();
    assertThrows(IllegalArgumentException.class, () -> reset(null, 0, 1, null));
    assertThrows(IllegalArgumentException.class, () -> initializeDefault(null, null, null));
    assertThrows(IllegalArgumentException.class, () -> copyFrom(s, null, 0, null));
    assertThrows(IllegalArgumentException.class, () -> updateSpecial(s, null));
  }
}
