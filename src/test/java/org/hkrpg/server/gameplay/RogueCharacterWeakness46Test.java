package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueCharacterWeakness46.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueCharacterWeakness46Test {
  @Test
  void viewCallbackReadsCurrentProtectAndSecretMasksButKeepsCapturedBase() {
    var s = state();
    s.overrideMask = 3;
    s.activeDirty = true;
    s.allWithState =
        new ArrayList<>() {
          public boolean add(View v) {
            boolean result = super.add(v);
            if (v.damageType() == 1) {
              s.overrideMask = 0;
              s.protectMask = 2;
            }
            return result;
          }
        };
    refreshActive(s, world());
    assertEquals(List.of(new View(1, 1), new View(2, 2)), s.allWithState);
    assertEquals(3, s.allMask);
  }

  @Test
  void currentCatalogIsRereadOnEveryIterationAndTermination() {
    var s = state();
    s.overrideMask = 3;
    s.activeDirty = true;
    int[] reads = {0};
    var w =
        new World() {
          public int[] damageTypes() {
            return ++reads[0] == 1 ? new int[] {1, 64} : new int[] {64, 2};
          }

          public void notifyWeaknessChanged(Object o, int id, boolean b) {}
        };
    refreshActive(s, w);
    assertEquals(3, reads[0]);
    assertEquals(List.of(new View(1, 1), new View(2, 1)), s.allWithState);
  }

  @Test
  void viewListReplacementIsUsedForNextAppend() {
    var s = state();
    s.overrideMask = 3;
    s.activeDirty = true;
    var next = new ArrayList<View>();
    var old =
        new ArrayList<View>() {
          public boolean add(View v) {
            boolean result = super.add(v);
            s.allWithState = next;
            return result;
          }
        };
    s.allWithState = old;
    refreshActive(s, world());
    assertEquals(List.of(new View(1, 1)), old);
    assertEquals(List.of(new View(2, 1)), next);
  }

  @Test
  void clearingViewListThenNullingItLeavesComputedActiveMaskOnly() {
    var s = state();
    s.overrideMask = 1;
    s.allMask = 99;
    s.activeDirty = true;
    s.allWithState =
        new ArrayList<>() {
          public void clear() {
            super.clear();
            s.allWithState = null;
          }
        };
    assertThrows(IllegalArgumentException.class, () -> refreshActive(s, world()));
    assertEquals(1, s.activeMask);
    assertEquals(0, s.allMask);
    assertTrue(s.activeDirty);
  }

  @Test
  void addFailureKeepsEarlierViewAndAllMaskPrefix() {
    var s = state();
    s.overrideMask = 3;
    s.activeDirty = true;
    s.allWithState =
        new ArrayList<>() {
          public boolean add(View v) {
            if (v.damageType() == 2) throw new IllegalStateException("add");
            return super.add(v);
          }
        };
    assertThrows(IllegalStateException.class, () -> refreshActive(s, world()));
    assertEquals(List.of(new View(1, 1)), s.allWithState);
    assertEquals(1, s.allMask);
    assertTrue(s.activeDirty);
  }

  @Test
  void notificationCallbackDirtyWriteIsClearedOnlyAfterSuccess() {
    var s = state();
    s.activeDirty = true;
    s.notifyChangeEnable = true;
    Object owner = new Object();
    s.owner = owner;
    var w =
        new World() {
          public int[] damageTypes() {
            return new int[0];
          }

          public void notifyWeaknessChanged(Object o, int id, boolean b) {
            assertSame(owner, o);
            s.activeDirty = true;
          }
        };
    refreshActive(s, w);
    assertFalse(s.activeDirty);
  }

  @Test
  void allMaskWriteDuringAppendIsObservedWhenCurrentTypeIsCombined() {
    var s = state();
    s.overrideMask = 1;
    s.activeDirty = true;
    s.allWithState =
        new ArrayList<>() {
          public boolean add(View v) {
            s.allMask = 64;
            return super.add(v);
          }
        };
    refreshActive(s, world());
    assertEquals(65, s.allMask);
  }

  @Test
  void fullStackGuardPreservesFirst255AggregatedEntries() {
    var s = state();
    s.attachStack = new int[256];
    Arrays.fill(s.attachStack, 1);
    s.attachOccupy = new boolean[256];
    Arrays.fill(s.attachOccupy, true);
    assertThrows(IllegalArgumentException.class, () -> refresh(s, 2));
    assertEquals(1, s.attachMask);
    assertFalse(s.activeDirty);
  }

  @Test
  void directUnstackFiniteIndexGuardDoesNotWriteOccupancy() {
    var s = state();
    s.overrideStack = new int[256];
    s.overrideOccupy = new boolean[256];
    s.overrideOccupy[255] = true;
    s.overrideStack[255] = 1;
    assertThrows(IllegalArgumentException.class, () -> unstack(s, 0, 255, null));
    assertTrue(s.overrideOccupy[255]);
    assertEquals(1, s.overrideStack[255]);
  }

  @Test
  void excessiveCatalogPreserves255ViewsAndDirtyState() {
    var s = state();
    s.overrideMask = 1;
    s.activeDirty = true;
    var w =
        new World() {
          public int[] damageTypes() {
            int[] types = new int[256];
            Arrays.fill(types, 1);
            return types;
          }

          public void notifyWeaknessChanged(Object o, int id, boolean b) {
            fail("Guard must stop before notification");
          }
        };
    assertThrows(IllegalArgumentException.class, () -> refreshActive(s, w));
    assertEquals(255, s.allWithState.size());
    assertEquals(1, s.allMask);
    assertTrue(s.activeDirty);
  }

  @Test
  void actualStackedWeaknessWithdrawalClearsUnderlyingSlotAndRestoresDefault() {
    var s = state();
    s.overrideStack = new int[] {2};
    s.overrideOccupy = new boolean[] {true};
    s.overrideMask = 2;
    s.defaultMask = 4;
    var effects = RogueModifierStackedEffects46Test.state();
    effects.weaknesses.add(
        new RogueModifierStackedEffects46.Weakness(
            RogueModifierStackedEffects46Test.ABILITY, 0, 0));
    var w =
        new RogueModifierStackedEffects46Test.World() {
          public Object weaknessComponent(Object c) {
            return s;
          }

          public void unstackWeakness(Object weakness, Object entity, int op, int index) {
            assertSame(s, weakness);
            unstack(s, op, index, world());
          }
        };
    RogueModifierStackedEffects46.popWeakness(effects, w);
    assertTrue(effects.weaknesses.isEmpty());
    assertFalse(s.overrideOccupy[0]);
    assertEquals(0, s.overrideStack[0]);
    assertEquals(4, s.overrideMask);
    assertEquals(List.of(new View(4, 1)), s.allWithState);
  }

  @Test
  void underlyingNotificationFailurePreservesStackedListAndSlotMutation() {
    var s = state();
    s.overrideMask = 3;
    s.notifyChangeEnable = true;
    var effects = RogueModifierStackedEffects46Test.state();
    effects.weaknesses.add(
        new RogueModifierStackedEffects46.Weakness(
            RogueModifierStackedEffects46Test.ABILITY, 0, 0));
    var changes =
        new World() {
          public int[] damageTypes() {
            return new int[] {1, 2};
          }

          public void notifyWeaknessChanged(Object o, int id, boolean b) {
            throw new IllegalStateException("notify");
          }
        };
    var w =
        new RogueModifierStackedEffects46Test.World() {
          public Object weaknessComponent(Object c) {
            return s;
          }

          public void unstackWeakness(Object weakness, Object entity, int op, int index) {
            unstack(s, op, index, changes);
          }
        };
    assertThrows(
        IllegalStateException.class, () -> RogueModifierStackedEffects46.popWeakness(effects, w));
    assertEquals(1, effects.weaknesses.size());
    assertFalse(s.overrideOccupy[0]);
    assertEquals(0, s.overrideStack[0]);
    assertEquals(2, s.overrideMask);
    assertTrue(s.activeDirty);
  }

  static State state() {
    var s = new State();
    s.overrideStack = new int[] {1, 2};
    s.protectStack = new int[] {4, 8};
    s.attachStack = new int[] {16, 32};
    s.secretStack = new int[] {64, 1};
    s.overrideOccupy = new boolean[] {true, true};
    s.protectOccupy = new boolean[] {true, true};
    s.attachOccupy = new boolean[] {true, true};
    s.secretOccupy = new boolean[] {true, true};
    s.allWithState = new ArrayList<>();
    s.defaultMask = 4;
    return s;
  }

  static World world() {
    return new World() {
      public int[] damageTypes() {
        return new int[] {1, 2, 4, 8, 16, 32, 64};
      }

      public void notifyWeaknessChanged(Object owner, int id, boolean immediate) {
        assertEquals(0x453, id);
        assertTrue(immediate);
      }
    };
  }

  static int[] stack(State s, int op) {
    return switch (op) {
      case 0 -> s.overrideStack;
      case 1 -> s.protectStack;
      case 2 -> s.attachStack;
      default -> s.secretStack;
    };
  }

  static boolean[] occupy(State s, int op) {
    return switch (op) {
      case 0 -> s.overrideOccupy;
      case 1 -> s.protectOccupy;
      case 2 -> s.attachOccupy;
      default -> s.secretOccupy;
    };
  }

  static void stack(State s, int op, int[] v) {
    switch (op) {
      case 0 -> s.overrideStack = v;
      case 1 -> s.protectStack = v;
      case 2 -> s.attachStack = v;
      default -> s.secretStack = v;
    }
  }

  static void occupy(State s, int op, boolean[] v) {
    switch (op) {
      case 0 -> s.overrideOccupy = v;
      case 1 -> s.protectOccupy = v;
      case 2 -> s.attachOccupy = v;
      default -> s.secretOccupy = v;
    }
  }

  static int mask(State s, int op) {
    return switch (op) {
      case 0 -> s.overrideMask;
      case 1 -> s.protectMask;
      case 2 -> s.attachMask;
      default -> s.secretMask;
    };
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void fourOperationsClearOnlyTheirOwnSlot(int op) {
    var s = state();
    unstack(s, op, 0, world());
    assertFalse(occupy(s, op)[0]);
    assertEquals(0, stack(s, op)[0]);
    for (int other = 0; other < 4; other++)
      if (other != op) {
        assertTrue(occupy(s, other)[0]);
        assertNotEquals(0, stack(s, other)[0]);
      }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void missingStackAfterOccupancyWriteKeepsClearedPrefix(int op) {
    var s = state();
    stack(s, op, null);
    assertThrows(IllegalArgumentException.class, () -> unstack(s, op, 0, null));
    assertFalse(occupy(s, op)[0]);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void stackBoundsFailureKeepsClearedOccupancy(int op) {
    var s = state();
    stack(s, op, new int[0]);
    assertThrows(IndexOutOfBoundsException.class, () -> unstack(s, op, 0, null));
    assertFalse(occupy(s, op)[0]);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void missingOccupancyDoesNotChangeStack(int op) {
    var s = state();
    int before = stack(s, op)[0];
    occupy(s, op, null);
    assertThrows(IllegalArgumentException.class, () -> unstack(s, op, 0, null));
    assertEquals(before, stack(s, op)[0]);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void unsignedInvalidIndexCannotClearFirstSlot(int index) {
    var s = state();
    assertThrows(IndexOutOfBoundsException.class, () -> unstack(s, 0, index, null));
    assertTrue(s.overrideOccupy[0]);
    assertEquals(1, s.overrideStack[0]);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 4, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void unknownOperationIsClosedBeforeWrites(int op) {
    var s = state();
    assertThrows(IllegalArgumentException.class, () -> unstack(s, op, 0, null));
    assertThrows(IllegalArgumentException.class, () -> refresh(s, op));
    assertTrue(s.overrideOccupy[0]);
    assertEquals(1, s.overrideStack[0]);
  }

  @Test
  void nullStateIsClosedForAllOperations() {
    assertThrows(IllegalArgumentException.class, () -> unstack(null, 0, 0, null));
    assertThrows(IllegalArgumentException.class, () -> refresh(null, 0));
    assertThrows(IllegalArgumentException.class, () -> refreshActive(null, null));
  }

  @Test
  void refreshOverrideUsesOnlyOccupiedMasksAndKeepsDefaultOut() {
    var s = state();
    s.overrideOccupy[1] = false;
    refresh(s, 0);
    assertEquals(1, s.overrideMask);
    assertTrue(s.activeDirty);
  }

  @Test
  void emptyOverrideOccupancyRestoresCurrentDefault() {
    var s = state();
    Arrays.fill(s.overrideOccupy, false);
    s.defaultMask = 0x80000080;
    refresh(s, 0);
    assertEquals(0x80000080, s.overrideMask);
  }

  @Test
  void occupiedZeroOverrideSuppressesDefaultFallback() {
    var s = state();
    s.overrideStack = new int[] {0};
    s.overrideOccupy = new boolean[] {true};
    refresh(s, 0);
    assertEquals(0, s.overrideMask);
    assertFalse(s.activeDirty);
  }

  @Test
  void attachAndProtectAggregateOccupiedMasks() {
    var s = state();
    s.attachOccupy[1] = false;
    s.protectOccupy[0] = false;
    refresh(s, 2);
    refresh(s, 1);
    assertEquals(16, s.attachMask);
    assertEquals(8, s.protectMask);
  }

  @Test
  void secretAggregateIsIntersectedWithAttachAndOverride() {
    var s = state();
    s.attachMask = 64;
    s.overrideMask = 2;
    refresh(s, 3);
    assertEquals(64, s.secretMask);
  }

  @Test
  void aggregateMissingStackClearsMaskBeforeFailing() {
    var s = state();
    s.overrideMask = 7;
    s.overrideStack = null;
    assertThrows(IllegalArgumentException.class, () -> refresh(s, 0));
    assertEquals(0, s.overrideMask);
    assertFalse(s.activeDirty);
  }

  @Test
  void shortOccupancyKeepsPartialAggregationWithoutSettingNewDirty() {
    var s = state();
    s.attachMask = 7;
    s.attachOccupy = new boolean[] {true};
    assertThrows(IndexOutOfBoundsException.class, () -> refresh(s, 2));
    assertEquals(16, s.attachMask);
    assertFalse(s.activeDirty);
  }

  @Test
  void unchangedRefreshDoesNotClearExistingDirty() {
    var s = state();
    s.overrideMask = 3;
    s.activeDirty = true;
    refresh(s, 0);
    assertEquals(3, s.overrideMask);
    assertTrue(s.activeDirty);
  }

  @Test
  void absentDirtySkipsWorldCatalogAndViewList() {
    var s = state();
    s.allWithState = null;
    refreshActive(s, null);
  }

  @Test
  void activeViewsUseSecretThenProtectThenActivePriorityAndCatalogOrder() {
    var s = state();
    s.overrideMask = 1 | 2 | 4;
    s.attachMask = 8;
    s.protectMask = 2 | 4;
    s.secretMask = 4;
    s.activeDirty = true;
    refreshActive(s, world());
    assertEquals(1 | 8, s.activeMask);
    assertEquals(1 | 2 | 4 | 8, s.allMask);
    assertEquals(
        List.of(new View(1, 1), new View(2, 2), new View(4, 3), new View(8, 1)), s.allWithState);
    assertFalse(s.activeDirty);
  }

  @Test
  void secretDoesNotRemoveBitFromActiveMask() {
    var s = state();
    s.overrideMask = 1;
    s.secretMask = 1;
    s.activeDirty = true;
    refreshActive(s, world());
    assertEquals(1, s.activeMask);
    assertEquals(List.of(new View(1, 3)), s.allWithState);
  }

  @Test
  void signedPositiveMaskCheckExcludesSignBitOnly() {
    var s = state();
    s.overrideMask = Integer.MIN_VALUE | 1;
    s.activeDirty = true;
    var w =
        new World() {
          public int[] damageTypes() {
            return new int[] {Integer.MIN_VALUE, 1};
          }

          public void notifyWeaknessChanged(Object o, int id, boolean b) {
            fail("Notify disabled");
          }
        };
    refreshActive(s, w);
    assertEquals(Integer.MIN_VALUE | 1, s.activeMask);
    assertEquals(1, s.allMask);
    assertEquals(List.of(new View(1, 1)), s.allWithState);
  }

  @Test
  void missingViewListFailsAfterActiveMaskButBeforeAllMaskClear() {
    var s = state();
    s.overrideMask = 3;
    s.protectMask = 2;
    s.allMask = 99;
    s.allWithState = null;
    s.activeDirty = true;
    assertThrows(IllegalArgumentException.class, () -> refreshActive(s, null));
    assertEquals(1, s.activeMask);
    assertEquals(99, s.allMask);
    assertTrue(s.activeDirty);
  }

  @Test
  void missingCatalogLeavesClearAndComputedMaskPrefix() {
    var s = state();
    s.overrideMask = 3;
    s.allMask = 99;
    s.allWithState.add(new View(64, 3));
    s.activeDirty = true;
    assertThrows(IllegalArgumentException.class, () -> refreshActive(s, null));
    assertEquals(3, s.activeMask);
    assertEquals(0, s.allMask);
    assertTrue(s.allWithState.isEmpty());
    assertTrue(s.activeDirty);
  }

  @Test
  void emptyCatalogStillClearsDirtyAndCanNotifyNullOwner() {
    var s = state();
    s.activeDirty = true;
    s.notifyChangeEnable = true;
    int[] calls = {0};
    var w =
        new World() {
          public int[] damageTypes() {
            return new int[0];
          }

          public void notifyWeaknessChanged(Object o, int id, boolean b) {
            assertNull(o);
            assertEquals(0x453, id);
            assertTrue(b);
            calls[0]++;
          }
        };
    refreshActive(s, w);
    assertEquals(1, calls[0]);
    assertFalse(s.activeDirty);
  }

  @Test
  void notifyFailureKeepsDirtyAndFinishedViews() {
    var s = state();
    s.overrideMask = 1;
    s.activeDirty = true;
    s.notifyChangeEnable = true;
    var marker = new IllegalStateException("notify");
    var w =
        new World() {
          public int[] damageTypes() {
            return new int[] {1};
          }

          public void notifyWeaknessChanged(Object o, int id, boolean b) {
            throw marker;
          }
        };
    assertSame(marker, assertThrows(IllegalStateException.class, () -> refreshActive(s, w)));
    assertTrue(s.activeDirty);
    assertEquals(List.of(new View(1, 1)), s.allWithState);
    assertEquals(1, s.allMask);
  }
}
