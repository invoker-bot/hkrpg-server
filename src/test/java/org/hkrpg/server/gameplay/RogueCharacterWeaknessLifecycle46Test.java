package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueCharacterWeakness46.*;
import static org.hkrpg.server.gameplay.RogueCharacterWeaknessLifecycle46.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueCharacterWeaknessLifecycle46Test {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void newComponentHasFourIndependentUnoccupiedSlotsForEachCategory(int op) {
    var s = create(new Object());
    assertEquals(4, RogueCharacterWeakness46Test.stack(s, op).length);
    assertArrayEquals(new int[4], RogueCharacterWeakness46Test.stack(s, op));
    assertArrayEquals(new boolean[4], RogueCharacterWeakness46Test.occupy(s, op));
  }

  @Test
  void creationSetsOwnerAndNotificationWhileLeavingMasksAndViewsEmpty() {
    Object owner = new Object();
    var s = create(owner);
    assertSame(owner, s.owner);
    assertTrue(s.notifyChangeEnable);
    assertEquals(
        0,
        s.defaultMask
            | s.overrideMask
            | s.protectMask
            | s.attachMask
            | s.secretMask
            | s.activeMask
            | s.allMask);
    assertFalse(s.activeDirty);
    assertTrue(s.allWithState.isEmpty());
    assertTrue(s.specialWeaknesses.isEmpty());
  }

  @Test
  void nullOwnerIsAllowedAtCreation() {
    assertNull(create(null).owner);
  }

  @Test
  void componentsDoNotShareMutableArraysOrLists() {
    var a = create(null);
    var b = create(null);
    a.overrideStack[0] = 64;
    a.specialWeaknesses.add(7);
    a.allWithState.add(new View(1, 1));
    assertEquals(0, b.overrideStack[0]);
    assertTrue(b.specialWeaknesses.isEmpty());
    assertTrue(b.allWithState.isEmpty());
    var arrays = Collections.newSetFromMap(new IdentityHashMap<int[], Boolean>());
    arrays.add(a.overrideStack);
    arrays.add(a.protectStack);
    arrays.add(a.attachStack);
    arrays.add(a.secretStack);
    assertEquals(4, arrays.size());
  }

  @Test
  void nativeCatalogIsSevenVerifiedTypesInTheirOriginalOrder() {
    assertArrayEquals(new int[] {1, 2, 4, 8, 16, 32, 64}, new Catalog().types);
  }

  @Test
  void catalogsAndTheirArraysAreExplicitIndependentInstances() {
    var a = new Catalog();
    var b = new Catalog();
    a.types[0] = 64;
    assertEquals(1, b.types[0]);
  }

  @Test
  void worldReadsCurrentCatalogReferenceAndPreservesNotificationArguments() {
    var c = new Catalog();
    Object owner = new Object();
    var seen = new ArrayList<Object>();
    var w =
        world(
            c,
            (o, id, immediate) -> {
              seen.add(o);
              seen.add(id);
              seen.add(immediate);
            });
    assertSame(c.types, w.damageTypes());
    c.types = new int[] {32};
    assertSame(c.types, w.damageTypes());
    w.notifyWeaknessChanged(owner, 0x453, true);
    assertEquals(List.of(owner, 0x453, true), seen);
  }

  @Test
  void nullCatalogFailsWhenCreatingWorld() {
    assertThrows(IllegalArgumentException.class, () -> world(null, null));
  }

  @Test
  void missingNotificationBindingFailsOnlyWhenNotificationIsRequested() {
    var w = world(new Catalog(), null);
    assertEquals(7, w.damageTypes().length);
    assertThrows(IllegalArgumentException.class, () -> w.notifyWeaknessChanged(null, 0x453, true));
  }

  @Test
  void newlyCreatedComponentSupportsRealDefaultAddResetUnstackAndSpecialPaths() {
    var s = create(null);
    var events = new ArrayList<Integer>();
    var w = world(new Catalog(), (o, id, b) -> events.add(id));
    RogueCharacterWeaknessRestore46.initializeDefault(s, new int[] {1, 2}, w);
    assertEquals(3, overrideMask(s));
    int index = RogueCharacterWeaknessStack46.add(s, OVERRIDE, 4, w);
    assertEquals(0, index);
    assertEquals(4, overrideMask(s));
    RogueCharacterWeaknessRestore46.reset(s, OVERRIDE, 4, w);
    assertEquals(0, overrideMask(s));
    assertTrue(s.overrideOccupy[index]);
    RogueCharacterWeakness46.unstack(s, OVERRIDE, index, w);
    assertEquals(3, overrideMask(s));
    RogueCharacterWeaknessRestore46.updateSpecial(
        s, new RogueCharacterWeaknessRestore46.SpecialEvent(true, 9));
    assertEquals(List.of(9), special(s));
    assertEquals(List.of(0x453, 0x453, 0x453, 0x453), events);
  }

  @Test
  void fifthAdditionUsesActualGrowthFromNativeInitialCapacity() {
    var s = create(null);
    var w = world(new Catalog(), (o, id, b) -> {});
    for (int i = 0; i < 5; i++)
      assertEquals(i, RogueCharacterWeaknessStack46.add(s, OVERRIDE, 1, w));
    assertEquals(8, s.overrideStack.length);
    assertEquals(8, s.overrideOccupy.length);
  }

  @Test
  void factoryComponentsCanBeCopiedWithoutManualTargetArrayInstallation() {
    var source = create(null);
    var target = create(null);
    var w = world(new Catalog(), (o, id, b) -> {});
    RogueCharacterWeaknessStack46.add(source, OVERRIDE, 1, w);
    RogueCharacterWeaknessRestore46.copyFrom(target, source, 257, w);
    assertEquals(1, overrideMask(target));
    assertTrue(target.overrideOccupy[0]);
    assertNotSame(source.overrideStack, target.overrideStack);
  }

  @Test
  void disposalClearsOwnerAndViewsBeforeNullingAllEightArrays() {
    var s = create(new Object());
    s.allWithState.add(new View(1, 1));
    dispose(s);
    assertNull(s.owner);
    assertTrue(s.allWithState.isEmpty());
    assertNull(s.overrideStack);
    assertNull(s.protectStack);
    assertNull(s.attachStack);
    assertNull(s.secretStack);
    assertNull(s.overrideOccupy);
    assertNull(s.protectOccupy);
    assertNull(s.attachOccupy);
    assertNull(s.secretOccupy);
  }

  @Test
  void disposalLeavesSpecialListMasksAndFlagsUntouched() {
    var s = create(null);
    s.specialWeaknesses.add(9);
    s.defaultMask = 1;
    s.overrideMask = 2;
    s.protectMask = 4;
    s.attachMask = 8;
    s.secretMask = 16;
    s.activeMask = 32;
    s.allMask = 64;
    s.activeDirty = true;
    var special = s.specialWeaknesses;
    dispose(s);
    assertSame(special, s.specialWeaknesses);
    assertEquals(List.of(9), special);
    assertEquals(
        127,
        s.defaultMask
            | s.overrideMask
            | s.protectMask
            | s.attachMask
            | s.secretMask
            | s.activeMask
            | s.allMask);
    assertTrue(s.activeDirty);
    assertTrue(s.notifyChangeEnable);
  }

  @Test
  void missingViewListIsAllowedAndStillReleasesArrays() {
    var s = create(null);
    s.allWithState = null;
    dispose(s);
    assertNull(s.overrideStack);
    assertNull(s.allWithState);
  }

  @Test
  void disposalViewFailureKeepsOwnerClearButRetainsArrays() {
    var s = create(new Object());
    var saved = s.overrideStack;
    var marker = new IllegalStateException("clear");
    s.allWithState =
        new ArrayList<>() {
          public void clear() {
            throw marker;
          }
        };
    assertSame(marker, assertThrows(IllegalStateException.class, () -> dispose(s)));
    assertNull(s.owner);
    assertSame(saved, s.overrideStack);
  }

  @Test
  void disposalClearsCapturedViewListEvenIfCallbackReplacesField() {
    var s = create(null);
    var next = new ArrayList<>(List.of(new View(2, 1)));
    var captured =
        new ArrayList<View>() {
          public void clear() {
            s.allWithState = next;
            super.clear();
          }
        };
    captured.add(new View(1, 1));
    s.allWithState = captured;
    dispose(s);
    assertTrue(captured.isEmpty());
    assertSame(next, s.allWithState);
    assertEquals(1, next.size());
    assertNull(s.overrideStack);
  }

  @Test
  void repeatedDisposalIsAllowed() {
    var s = create(null);
    dispose(s);
    dispose(s);
    assertNull(s.owner);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4, 8, 16, 32, 64})
  void positiveProtectionAndSecretIntersectionsAreDetected(int mask) {
    var s = create(null);
    s.protectMask = mask;
    s.secretMask = mask;
    assertTrue(isProtected(s, mask));
    assertTrue(isSecret(s, mask));
    assertTrue(hasSecret(s));
    assertFalse(isProtected(s, ~mask));
    assertFalse(isSecret(s, ~mask));
  }

  @Test
  void signBitOnlyIntersectionFailsPositiveTestsButHasSecretIsTrue() {
    var s = create(null);
    s.protectMask = Integer.MIN_VALUE;
    s.secretMask = Integer.MIN_VALUE;
    assertFalse(isProtected(s, -1));
    assertFalse(isSecret(s, -1));
    assertTrue(hasSecret(s));
  }

  @Test
  void zeroSecretIsAbsent() {
    assertFalse(hasSecret(create(null)));
  }

  @Test
  void gettersReadRawMasksWithoutRefreshingOrRequiringArrays() {
    var s = create(null);
    dispose(s);
    s.defaultMask = 1;
    s.overrideMask = 2;
    s.attachMask = 4;
    s.secretMask = 8;
    s.activeMask = 16;
    s.allMask = 32;
    s.activeDirty = true;
    assertEquals(1, defaultMask(s));
    assertEquals(2, overrideMask(s));
    assertEquals(4, attachedMask(s));
    assertEquals(8, secretMask(s));
    assertEquals(16, activeMask(s));
    assertEquals(32, allMask(s));
    assertTrue(s.activeDirty);
  }

  @Test
  void collectionGettersReturnCurrentReferencesIncludingNull() {
    var s = create(null);
    assertSame(s.allWithState, allViews(s));
    assertSame(s.specialWeaknesses, special(s));
    s.allWithState = null;
    s.specialWeaknesses = null;
    assertNull(allViews(s));
    assertNull(special(s));
  }

  @Test
  void everyLifecycleEntryRejectsNullState() {
    assertThrows(IllegalArgumentException.class, () -> dispose(null));
    assertThrows(IllegalArgumentException.class, () -> defaultMask(null));
    assertThrows(IllegalArgumentException.class, () -> overrideMask(null));
    assertThrows(IllegalArgumentException.class, () -> attachedMask(null));
    assertThrows(IllegalArgumentException.class, () -> secretMask(null));
    assertThrows(IllegalArgumentException.class, () -> activeMask(null));
    assertThrows(IllegalArgumentException.class, () -> allMask(null));
    assertThrows(IllegalArgumentException.class, () -> allViews(null));
    assertThrows(IllegalArgumentException.class, () -> special(null));
    assertThrows(IllegalArgumentException.class, () -> isProtected(null, 1));
    assertThrows(IllegalArgumentException.class, () -> isSecret(null, 1));
    assertThrows(IllegalArgumentException.class, () -> hasSecret(null));
  }
}
