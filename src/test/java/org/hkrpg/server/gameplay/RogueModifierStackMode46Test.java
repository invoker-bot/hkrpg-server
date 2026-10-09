package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RogueModifierStackMode46Test {
  static class BoundWorld implements RogueModifierStackMode46.World {
    final List<String> order = new ArrayList<>();

    public void valueChanged(RogueModifierStackMode46.State state, int type) {
      order.add("value" + type);
    }

    public void onReplace(RogueModifierStackMode46.State state) {
      order.add("replace");
    }
  }

  static RogueModifierStackMode46.State state(int initial, int current, int count) {
    var lifetime = new RogueModifierLifetime46.State(new Object(), initial, current);
    lifetime.modifierName = "effect";
    return new RogueModifierStackMode46.State(lifetime, count, 2);
  }

  @Test
  void refreshChangesLifetimeWithoutCountOrReplacement() {
    var state = state(5, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 2, 8, 99, world);
    assertEquals(7, state.count);
    assertEquals(8, state.lifetime.currentLife);
    assertEquals(8, state.lifetime.initialLife);
    assertEquals(List.of("value3"), world.order);
  }

  @Test
  void prolongChecksInitialPermanentSentinel() {
    var state = state(-1, 3, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 3, 2, 99, world);
    assertEquals(-1, state.lifetime.currentLife);
    assertEquals(-1, state.lifetime.initialLife);
    assertEquals(7, state.count);
    assertEquals(List.of("value3"), world.order);
  }

  @Test
  void mergeReplacesWithoutChangingCount() {
    var state = state(5, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 6, 8, 99, world);
    assertEquals(7, state.count);
    assertEquals(8, state.lifetime.currentLife);
    assertEquals(8, state.lifetime.initialLife);
    assertEquals(List.of("value3", "replace"), world.order);
  }

  @Test
  void modesWithoutLifetimeBranchLeaveCoreFieldsAndCallbacksUntouched() {
    for (int mode : List.of(0, 1, 4, 9, 11, 13)) {
      var state = state(5, 2, 7);
      var world = new BoundWorld();
      RogueModifierStackMode46.applyMode(state, mode, -1, 99, world);
      assertEquals(5, state.lifetime.initialLife);
      assertEquals(2, state.lifetime.currentLife);
      assertEquals(7, state.count);
      assertEquals(2, state.layer);
      assertTrue(world.order.isEmpty());
    }
  }

  @Test
  void refreshWithEqualCurrentLifeStillResetsInitialWithoutNotification() {
    var state = state(9, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 2, 2, 99, world);
    assertEquals(2, state.lifetime.initialLife);
    assertEquals(7, state.count);
    assertTrue(world.order.isEmpty());
  }

  @Test
  void refreshCanReplaceOldPermanentLifeWithFiniteLife() {
    var state = state(-1, -1, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 2, 4, 99, world);
    assertEquals(4, state.lifetime.currentLife);
    assertEquals(4, state.lifetime.initialLife);
    assertEquals(List.of("value3"), world.order);
  }

  @Test
  void refreshCanCreatePermanentSentinel() {
    var state = state(9, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 2, -1, 99, world);
    assertEquals(-1, state.lifetime.currentLife);
    assertEquals(-1, state.lifetime.initialLife);
  }

  @Test
  void prolongAddsFiniteCurrentAndIncomingLife() {
    var state = state(9, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 3, 4, 99, world);
    assertEquals(6, state.lifetime.currentLife);
    assertEquals(6, state.lifetime.initialLife);
    assertEquals(7, state.count);
    assertEquals(List.of("value3"), world.order);
  }

  @Test
  void prolongIncomingPermanentOverridesFiniteLife() {
    var state = state(9, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 3, -1, 99, world);
    assertEquals(-1, state.lifetime.currentLife);
    assertEquals(-1, state.lifetime.initialLife);
  }

  @Test
  void prolongCurrentMinusOneIsFiniteWhenInitialIsFinite() {
    var state = state(5, -1, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 3, 2, 99, world);
    assertEquals(1, state.lifetime.currentLife);
    assertEquals(1, state.lifetime.initialLife);
  }

  @Test
  void prolongUsesUncheckedSignedInt32Addition() {
    var state = state(5, Integer.MAX_VALUE, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 3, 1, 99, world);
    assertEquals(Integer.MIN_VALUE, state.lifetime.currentLife);
    assertEquals(Integer.MIN_VALUE, state.lifetime.initialLife);
  }

  @Test
  void prolongKeepsNegativeValuesWithoutClamping() {
    var state = state(5, -5, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 3, -2, 99, world);
    assertEquals(-7, state.lifetime.currentLife);
    assertEquals(-7, state.lifetime.initialLife);
  }

  @Test
  void mergeKeepsInitialPermanentSentinelEvenWithFiniteCurrent() {
    var state = state(-1, 5, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 6, 8, 99, world);
    assertEquals(-1, state.lifetime.currentLife);
    assertEquals(-1, state.lifetime.initialLife);
    assertEquals(7, state.count);
    assertEquals(List.of("value3", "replace"), world.order);
  }

  @Test
  void mergeIncomingPermanentCreatesPermanentLife() {
    var state = state(5, 5, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 6, -1, 99, world);
    assertEquals(-1, state.lifetime.currentLife);
    assertEquals(-1, state.lifetime.initialLife);
  }

  @Test
  void mergeKeepsLargerExistingLifeButResetsInitialAndReplaces() {
    var state = state(2, 9, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 6, 4, 99, world);
    assertEquals(9, state.lifetime.currentLife);
    assertEquals(9, state.lifetime.initialLife);
    assertEquals(List.of("replace"), world.order);
  }

  @Test
  void mergeDoesNotTreatCurrentMinusOneAsPermanent() {
    var state = state(5, -1, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 6, 4, 99, world);
    assertEquals(4, state.lifetime.currentLife);
    assertEquals(4, state.lifetime.initialLife);
  }

  @Test
  void mergeEqualLifeStillCallsReplacementWithoutValueChange() {
    var state = state(5, 4, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 6, 4, 99, world);
    assertEquals(4, state.lifetime.initialLife);
    assertEquals(7, state.count);
    assertEquals(List.of("replace"), world.order);
  }

  @Test
  void allFourReplaceModesWriteLifeThenCountThenReplace() {
    for (int mode : List.of(5, 7, 8, 12)) {
      var state = state(5, 2, 7);
      var world = new BoundWorld();
      RogueModifierStackMode46.applyMode(state, mode, 4, 99, world);
      assertEquals(4, state.lifetime.currentLife);
      assertEquals(4, state.lifetime.initialLife);
      assertEquals(99, state.count);
      assertEquals(List.of("value3", "value1", "replace"), world.order);
    }
  }

  @Test
  void replaceEqualCountOnlyNotifiesLifeBeforeReplacement() {
    var state = state(5, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 5, 4, 7, world);
    assertEquals(List.of("value3", "replace"), world.order);
  }

  @Test
  void replaceEqualLifeOnlyNotifiesCountAndResetsInitial() {
    var state = state(5, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 5, 2, 99, world);
    assertEquals(2, state.lifetime.initialLife);
    assertEquals(List.of("value1", "replace"), world.order);
  }

  @Test
  void replaceEqualLifeAndCountStillCallsReplacement() {
    var state = state(5, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 5, 2, 7, world);
    assertEquals(2, state.lifetime.initialLife);
    assertEquals(List.of("replace"), world.order);
  }

  @Test
  void keepLifetimeReplacementLeavesBothLifeFieldsUntouched() {
    var state = state(9, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 10, 999, 99, world);
    assertEquals(9, state.lifetime.initialLife);
    assertEquals(2, state.lifetime.currentLife);
    assertEquals(99, state.count);
    assertEquals(List.of("value1", "replace"), world.order);
  }

  @Test
  void keepLifetimeReplacementPreservesInitialPermanentSentinel() {
    var state = state(-1, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 10, 999, 99, world);
    assertEquals(-1, state.lifetime.initialLife);
    assertEquals(2, state.lifetime.currentLife);
  }

  @Test
  void keepLifetimeEqualCountStillCallsReplacement() {
    var state = state(9, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 10, Integer.MIN_VALUE, 7, world);
    assertEquals(List.of("replace"), world.order);
  }

  @Test
  void lifeSetterWritesBeforeTypeThreeNotification() {
    var state = state(9, 2, 7);
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackMode46.State s, int type) {
            assertSame(state, s);
            assertEquals(3, type);
            assertEquals(11, s.lifetime.currentLife);
            super.valueChanged(s, type);
          }
        };
    RogueModifierStackMode46.setCurrentLife(state, 11, world);
    assertEquals(List.of("value3"), world.order);
  }

  @Test
  void countSetterWritesBeforeTypeOneNotification() {
    var state = state(9, 2, 7);
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackMode46.State s, int type) {
            assertEquals(1, type);
            assertEquals(-99, s.count);
            super.valueChanged(s, type);
          }
        };
    RogueModifierStackMode46.setCount(state, -99, world);
    assertEquals(List.of("value1"), world.order);
  }

  @Test
  void layerSetterWritesBeforeTypeZeroNotification() {
    var state = state(9, 2, 7);
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackMode46.State s, int type) {
            assertEquals(0, type);
            assertEquals(-99, s.layer);
            super.valueChanged(s, type);
          }
        };
    RogueModifierStackMode46.setLayer(state, -99, world);
    assertEquals(List.of("value0"), world.order);
  }

  @Test
  void unchangedSettersDoNotNotify() {
    var state = state(9, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.setCurrentLife(state, 2, world);
    RogueModifierStackMode46.setCount(state, 7, world);
    RogueModifierStackMode46.setLayer(state, 2, world);
    assertTrue(world.order.isEmpty());
  }

  @Test
  void currentLifeSetterDoesNotResetInitialLife() {
    var state = state(-1, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.setCurrentLife(state, 9, world);
    assertEquals(-1, state.lifetime.initialLife);
  }

  @Test
  void countAndLayerSettersDoNotChangeEitherLifeField() {
    var state = state(9, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.setCount(state, Integer.MIN_VALUE, world);
    RogueModifierStackMode46.setLayer(state, Integer.MAX_VALUE, world);
    assertEquals(9, state.lifetime.initialLife);
    assertEquals(2, state.lifetime.currentLife);
  }

  @Test
  void lifeNotificationMutationIsReadWhenResettingInitialLife() {
    var state = state(9, 2, 7);
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackMode46.State s, int type) {
            super.valueChanged(s, type);
            s.lifetime.currentLife = 19;
          }
        };
    RogueModifierStackMode46.applyMode(state, 2, 4, 99, world);
    assertEquals(19, state.lifetime.initialLife);
    assertEquals(19, state.lifetime.currentLife);
  }

  @Test
  void lifeNotificationMutationOfCountCanAvoidLaterCountNotification() {
    var state = state(9, 2, 7);
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackMode46.State s, int type) {
            super.valueChanged(s, type);
            if (type == 3) s.count = 99;
          }
        };
    RogueModifierStackMode46.applyMode(state, 5, 4, 99, world);
    assertEquals(List.of("value3", "replace"), world.order);
    assertEquals(99, state.count);
  }

  @Test
  void countNotificationLifeMutationOccursAfterInitialWasReset() {
    var state = state(9, 2, 7);
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackMode46.State s, int type) {
            super.valueChanged(s, type);
            if (type == 1) s.lifetime.currentLife = 19;
          }
        };
    RogueModifierStackMode46.applyMode(state, 5, 4, 99, world);
    assertEquals(4, state.lifetime.initialLife);
    assertEquals(19, state.lifetime.currentLife);
    assertEquals(List.of("value3", "value1", "replace"), world.order);
  }

  @Test
  void replacementMutationPersistsAndHasNoLaterInitialReset() {
    var state = state(9, 2, 7);
    var world =
        new BoundWorld() {
          public void onReplace(RogueModifierStackMode46.State s) {
            super.onReplace(s);
            s.lifetime.currentLife = 17;
            s.count = 27;
          }
        };
    RogueModifierStackMode46.applyMode(state, 5, 4, 99, world);
    assertEquals(4, state.lifetime.initialLife);
    assertEquals(17, state.lifetime.currentLife);
    assertEquals(27, state.count);
  }

  @Test
  void lifeNotificationFailureKeepsLifeWriteAndStopsBeforeInitialOrCount() {
    var state = state(9, 2, 7);
    var failure = new IllegalStateException("value3");
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackMode46.State s, int type) {
            super.valueChanged(s, type);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierStackMode46.applyMode(state, 5, 4, 99, world)));
    assertEquals(4, state.lifetime.currentLife);
    assertEquals(9, state.lifetime.initialLife);
    assertEquals(7, state.count);
    assertEquals(List.of("value3"), world.order);
  }

  @Test
  void countNotificationFailureKeepsBothPriorLifeWritesAndCount() {
    var state = state(9, 2, 7);
    var failure = new IllegalStateException("value1");
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackMode46.State s, int type) {
            super.valueChanged(s, type);
            if (type == 1) throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierStackMode46.applyMode(state, 5, 4, 99, world)));
    assertEquals(4, state.lifetime.currentLife);
    assertEquals(4, state.lifetime.initialLife);
    assertEquals(99, state.count);
    assertEquals(List.of("value3", "value1"), world.order);
  }

  @Test
  void replacementFailureKeepsLifeAndCountUpdates() {
    var state = state(9, 2, 7);
    var failure = new IllegalStateException("replace");
    var world =
        new BoundWorld() {
          public void onReplace(RogueModifierStackMode46.State s) {
            super.onReplace(s);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierStackMode46.applyMode(state, 5, 4, 99, world)));
    assertEquals(4, state.lifetime.currentLife);
    assertEquals(4, state.lifetime.initialLife);
    assertEquals(99, state.count);
    assertEquals(List.of("value3", "value1", "replace"), world.order);
  }

  @Test
  void layerNotificationFailureRetainsLayerWrite() {
    var state = state(9, 2, 7);
    var failure = new IllegalStateException("value0");
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackMode46.State s, int type) {
            super.valueChanged(s, type);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class, () -> RogueModifierStackMode46.setLayer(state, 9, world)));
    assertEquals(9, state.layer);
    assertEquals(List.of("value0"), world.order);
  }

  @Test
  void unmatchedModesFailBeforeMutatingCoreFields() {
    for (int mode : List.of(Integer.MIN_VALUE, -1, 14, Integer.MAX_VALUE)) {
      var state = state(9, 2, 7);
      var world = new BoundWorld();
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueModifierStackMode46.applyMode(state, mode, 4, 99, world));
      assertEquals(9, state.lifetime.initialLife);
      assertEquals(2, state.lifetime.currentLife);
      assertEquals(7, state.count);
      assertTrue(world.order.isEmpty());
    }
  }

  @Test
  void missingLifetimeOrExplicitBindingsFailClosed() {
    var state = state(9, 2, 7);
    var world = new BoundWorld();
    assertThrows(
        IllegalArgumentException.class, () -> new RogueModifierStackMode46.State(null, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierStackMode46.applyMode(null, 2, 4, 9, world));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierStackMode46.applyMode(state, 2, 4, 9, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierStackMode46.setCurrentLife(state, 4, null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierStackMode46.setCount(state, 4, null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierStackMode46.setLayer(state, 4, null));
  }

  @Test
  void mergedPermanentLifeSharesTheStateUsedByLifetimeProcessing() {
    var state = state(-1, 2, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 6, 4, 99, world);
    var lifetimeWorld = new RogueModifierLifetime46Test.BoundWorld();
    RogueModifierLifetime46.process(state.lifetime, 7, lifetimeWorld);
    assertEquals(-1, state.lifetime.currentLife);
    assertEquals(List.of("moment"), lifetimeWorld.order);
  }

  @Test
  void refreshedFiniteLifeCanAdvanceThroughLifetimeProcessing() {
    var state = state(-1, -1, 7);
    var world = new BoundWorld();
    RogueModifierStackMode46.applyMode(state, 2, 4, 99, world);
    var lifetimeWorld = new RogueModifierLifetime46Test.BoundWorld();
    RogueModifierLifetime46.process(state.lifetime, 7, lifetimeWorld);
    assertEquals(3, state.lifetime.currentLife);
    assertEquals(4, state.lifetime.initialLife);
    assertEquals(List.of("moment", "status", "value3", "hud"), lifetimeWorld.order);
  }

  @Test
  void modeBranchPreservesIdentityLayerRenewAndOtherLifetimeControls() {
    var state = state(9, 2, 7);
    var life = state.lifetime;
    var identity = life.identity;
    life.renewTurn = 27;
    life.immediately = true;
    life.lockStepStatusType = 2;
    life.overrideStatusName = "override";
    RogueModifierStackMode46.applyMode(state, 5, 4, 99, new BoundWorld());
    assertSame(life, state.lifetime);
    assertSame(identity, life.identity);
    assertEquals(2, state.layer);
    assertEquals(27, life.renewTurn);
    assertTrue(life.immediately);
    assertEquals(2, life.lockStepStatusType);
    assertEquals("effect", life.modifierName);
    assertEquals("override", life.overrideStatusName);
  }
}
