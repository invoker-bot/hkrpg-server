package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueOwnedParameterLists46Test {
  static final Object ENTITY = new Object(), COMPONENT = new Object(), NEXT = new Object();

  static RogueOwnedParameterLists46.References list(Object... values) {
    return new RogueOwnedParameterLists46.References(values.clone(), values.length, 5);
  }

  static RogueOwnedParameterLists46.AbilityState ability(Object... values) {
    var s = new RogueOwnedParameterLists46.AbilityState();
    s.ownerEntity = ENTITY;
    s.departed = list(values);
    return s;
  }

  static RogueOwnedParameterLists46.ModeState mode(Object... values) {
    var s = new RogueOwnedParameterLists46.ModeState();
    s.unselectable = list(values);
    return s;
  }

  static class World implements RogueOwnedParameterLists46.World {
    final List<String> order = new ArrayList<>();
    boolean enabled, previouslyDeparted;
    Object visibility = COMPONENT, ability = COMPONENT;
    Runnable onVisibility = () -> {},
        onTransition = () -> {},
        onRefresh = () -> {},
        onError = () -> {};

    public Object visibilityComponent(Object entity) {
      assertSame(ENTITY, entity);
      order.add("visibilityComponent");
      return visibility;
    }

    public void setVisibility(Object component, boolean flag, boolean immediate, int reason) {
      assertSame(COMPONENT, component);
      assertTrue(immediate);
      assertEquals(30, reason);
      enabled = flag;
      order.add("visibility");
      onVisibility.run();
    }

    public void departedTransition(RogueOwnedParameterLists46.AbilityState s, boolean old) {
      previouslyDeparted = old;
      order.add("transition");
      onTransition.run();
    }

    public void crossEntityReplacementError(Object old, Object next) {
      assertSame(ENTITY, old);
      assertSame(NEXT, next);
      order.add("error");
      onError.run();
    }

    public Object abilityComponent(Object entity) {
      assertSame(ENTITY, entity);
      order.add("abilityComponent");
      return ability;
    }

    public void refreshSelfUnselectable(Object component) {
      assertSame(COMPONENT, component);
      order.add("refresh");
      onRefresh.run();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void removeFirstIdentityShiftsEffectivePrefixAndClearsOnlyTail(int index) {
    Object a = new Object(), b = new Object(), c = new Object();
    Object[] original = {a, b, c};
    var s = list(a, b, c, NEXT);
    s.size = 3;
    assertTrue(RogueOwnedParameterLists46.removeFirst(s, original[index]));
    List<Object> wanted = new ArrayList<>(List.of(a, b, c));
    wanted.remove(index);
    assertEquals(wanted, List.of(s.entries[0], s.entries[1]));
    assertNull(s.entries[2]);
    assertSame(NEXT, s.entries[3]);
    assertEquals(2, s.size);
    assertEquals(6, s.version);
  }

  @Test
  void duplicateIdentityRemovesOnlyFirstRegistration() {
    var s = list(ENTITY, NEXT, ENTITY);
    assertTrue(RogueOwnedParameterLists46.removeFirst(s, ENTITY));
    assertArrayEquals(new Object[] {NEXT, ENTITY, null}, s.entries);
    assertEquals(2, s.size);
  }

  @Test
  void equalValuesWithDistinctIdentitiesRemainRegistered() {
    var a = new String("x");
    var b = new String("x");
    var s = list(a);
    assertFalse(RogueOwnedParameterLists46.removeFirst(s, b));
    assertSame(a, s.entries[0]);
    assertEquals(5, s.version);
  }

  @Test
  void nullReferenceCanBeRemovedAndMissingIdentityLeavesHeadersUntouched() {
    var s = list(ENTITY, null, NEXT);
    assertFalse(RogueOwnedParameterLists46.removeFirst(s, COMPONENT));
    assertEquals(3, s.size);
    assertEquals(5, s.version);
    assertTrue(RogueOwnedParameterLists46.removeFirst(s, null));
    assertArrayEquals(new Object[] {ENTITY, NEXT, null}, s.entries);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 3, 256})
  void malformedSearchCountDoesNotMutateHeaders(int count) {
    var s = list(ENTITY, NEXT);
    s.size = count;
    assertThrows(
        IllegalArgumentException.class, () -> RogueOwnedParameterLists46.removeFirst(s, ENTITY));
    assertEquals(count, s.size);
    assertEquals(5, s.version);
  }

  @Test
  void removeRequiresStorageEvenForZeroSize() {
    var s = new RogueOwnedParameterLists46.References(null, 0, 5);
    assertThrows(
        IllegalArgumentException.class, () -> RogueOwnedParameterLists46.removeFirst(s, ENTITY));
    assertEquals(0, s.size);
    assertEquals(5, s.version);
  }

  @Test
  void emptyBackingStorageGrowsToFourAndPublishesOneEntry() {
    var s = list();
    RogueOwnedParameterLists46.add(s, ENTITY);
    assertEquals(4, s.entries.length);
    assertSame(ENTITY, s.entries[0]);
    assertEquals(1, s.size);
    assertEquals(6, s.version);
  }

  @Test
  void fullCapacityDoublesAndPreservesExistingReferences() {
    var s = list(ENTITY, NEXT);
    RogueOwnedParameterLists46.add(s, COMPONENT);
    assertEquals(4, s.entries.length);
    assertArrayEquals(new Object[] {ENTITY, NEXT, COMPONENT, null}, s.entries);
    assertEquals(3, s.size);
    assertEquals(6, s.version);
  }

  @Test
  void spareCapacityKeepsArrayIdentityAndAllowsNullEntry() {
    var s = list(ENTITY, NEXT, COMPONENT);
    s.size = 1;
    Object[] storage = s.entries;
    RogueOwnedParameterLists46.add(s, null);
    assertSame(storage, s.entries);
    assertNull(s.entries[1]);
    assertSame(COMPONENT, s.entries[2]);
    assertEquals(2, s.size);
  }

  @Test
  void missingAddStoragePreservesTheVersionIncrement() {
    var s = new RogueOwnedParameterLists46.References(null, 0, Integer.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> RogueOwnedParameterLists46.add(s, ENTITY));
    assertEquals(Integer.MIN_VALUE, s.version);
    assertEquals(0, s.size);
  }

  @Test
  void negativeAddCountPublishesNextCountBeforeUnsignedStorageFailure() {
    var s = list(ENTITY);
    s.size = -1;
    assertThrows(IllegalArgumentException.class, () -> RogueOwnedParameterLists46.add(s, NEXT));
    assertEquals(0, s.size);
    assertEquals(6, s.version);
    assertSame(ENTITY, s.entries[0]);
  }

  @Test
  void compiledCapacityLimitKeepsOldStorageAndCountAfterVersionChange() {
    var s = new RogueOwnedParameterLists46.References(new Object[128], 128, 5);
    Object[] old = s.entries;
    assertThrows(IllegalArgumentException.class, () -> RogueOwnedParameterLists46.add(s, ENTITY));
    assertSame(old, s.entries);
    assertEquals(128, s.size);
    assertEquals(6, s.version);
  }

  @Test
  void invalidGrowthCountDoesNotPublishANewArray() {
    var s = list(ENTITY);
    s.size = 2;
    var old = s.entries;
    assertThrows(IllegalArgumentException.class, () -> RogueOwnedParameterLists46.add(s, NEXT));
    assertSame(old, s.entries);
    assertEquals(2, s.size);
    assertEquals(6, s.version);
  }

  @Test
  void successfulRemoveVersionWrapsAfterStorageWasCleared() {
    var s = list(ENTITY);
    s.version = Integer.MAX_VALUE;
    RogueOwnedParameterLists46.removeFirst(s, ENTITY);
    assertNull(s.entries[0]);
    assertEquals(Integer.MIN_VALUE, s.version);
  }

  @Test
  void identicalDepartedParametersSkipListOwnerAndWorld() {
    var s = new RogueOwnedParameterLists46.AbilityState();
    var p = new RogueOwnedParameterLists46.Departed();
    RogueOwnedParameterLists46.refreshDeparted(s, p, p, null);
    RogueOwnedParameterLists46.refreshDeparted(s, null, null, null);
  }

  @Test
  void firstDepartedRegistrationHidesAndDispatchesTransitionAfterVisibility() {
    var s = ability();
    var p = new RogueOwnedParameterLists46.Departed();
    p.hide = true;
    var w = new World();
    RogueOwnedParameterLists46.refreshDeparted(s, null, p, w);
    assertFalse(w.enabled);
    assertFalse(w.previouslyDeparted);
    assertEquals(List.of("visibilityComponent", "visibility", "transition"), w.order);
    assertSame(p, s.departed.entries[0]);
  }

  @Test
  void lastDepartedRemovalShowsAndDispatchesPreviousTrue() {
    var p = new RogueOwnedParameterLists46.Departed();
    p.hide = true;
    var s = ability(p);
    var w = new World();
    RogueOwnedParameterLists46.refreshDeparted(s, p, null, w);
    assertTrue(w.enabled);
    assertTrue(w.previouslyDeparted);
    assertEquals(0, s.departed.size);
  }

  @Test
  void replacingOneDepartedRegistrationDoesNotEmitCountPresenceTransition() {
    var a = new RogueOwnedParameterLists46.Departed();
    var b = new RogueOwnedParameterLists46.Departed();
    b.hide = true;
    var s = ability(a);
    var w = new World();
    RogueOwnedParameterLists46.refreshDeparted(s, a, b, w);
    assertEquals(List.of("visibilityComponent", "visibility"), w.order);
    assertFalse(w.enabled);
    assertSame(b, s.departed.entries[0]);
    assertEquals(7, s.departed.version);
  }

  @Test
  void absentDepartedRemovalStillRefreshesVisibilityWithoutChangingVersion() {
    var s = ability();
    var p = new RogueOwnedParameterLists46.Departed();
    var w = new World();
    RogueOwnedParameterLists46.refreshDeparted(s, p, null, w);
    assertEquals(5, s.departed.version);
    assertTrue(w.enabled);
    assertEquals(List.of("visibilityComponent", "visibility"), w.order);
  }

  @Test
  void firstBooleanFlagDoesNotHideTheEntity() {
    var p = new RogueOwnedParameterLists46.Departed();
    p.firstFlag = true;
    var s = ability(p);
    var w = new World();
    RogueOwnedParameterLists46.refreshDepartedVisibility(s, w);
    assertTrue(w.enabled);
  }

  @Test
  void firstHiddenParameterStopsBeforeMalformedTrailingEntry() {
    var p = new RogueOwnedParameterLists46.Departed();
    p.hide = true;
    var s = ability(p, null);
    var w = new World();
    RogueOwnedParameterLists46.refreshDepartedVisibility(s, w);
    assertFalse(w.enabled);
  }

  @Test
  void noHiddenParameterRequiresEachCapturedPrefixEntry() {
    var s = ability(new RogueOwnedParameterLists46.Departed(), null);
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueOwnedParameterLists46.refreshDepartedVisibility(s, w));
    assertTrue(w.order.isEmpty());
  }

  @Test
  void emptyDepartedReferencesNeedNoStorageButStillRequireOwner() {
    var s = ability();
    s.departed.entries = null;
    var w = new World();
    RogueOwnedParameterLists46.refreshDepartedVisibility(s, w);
    assertTrue(w.enabled);
    s.ownerEntity = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueOwnedParameterLists46.refreshDepartedVisibility(s, w));
  }

  @Test
  void absentVisibilityComponentSkipsSettingButDoesNotSkipCountTransition() {
    var s = ability();
    var w = new World();
    w.visibility = null;
    RogueOwnedParameterLists46.refreshDeparted(
        s, null, new RogueOwnedParameterLists46.Departed(), w);
    assertEquals(List.of("visibilityComponent", "transition"), w.order);
  }

  @Test
  void visibilityFailureKeepsRemovalAndPreventsTransitionCallback() {
    var p = new RogueOwnedParameterLists46.Departed();
    var s = ability(p);
    var w = new World();
    w.onVisibility =
        () -> {
          throw new IllegalStateException("visibility");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueOwnedParameterLists46.refreshDeparted(s, p, null, w));
    assertEquals(0, s.departed.size);
    assertEquals(List.of("visibilityComponent", "visibility"), w.order);
  }

  @Test
  void visibilityCallbackReplacementIsRereadForTransitionPresence() {
    var p = new RogueOwnedParameterLists46.Departed();
    var s = ability(p);
    var w = new World();
    w.onVisibility = () -> s.departed = list(p);
    RogueOwnedParameterLists46.refreshDeparted(s, p, null, w);
    assertEquals(List.of("visibilityComponent", "visibility"), w.order);
    assertEquals(1, s.departed.size);
  }

  @Test
  void sameUnselectableReferenceSkipsAllWorldAndListAccess() {
    var s = new RogueOwnedParameterLists46.ModeState();
    var p = new RogueOwnedParameterLists46.Unselectable(null);
    RogueOwnedParameterLists46.refreshUnselectable(s, p, p, null);
    RogueOwnedParameterLists46.refreshUnselectable(s, null, null, null);
  }

  @Test
  void crossEntityReplacementLogsWithoutTouchingEitherParameterOrList() {
    var a = new RogueOwnedParameterLists46.Unselectable(ENTITY);
    var b = new RogueOwnedParameterLists46.Unselectable(NEXT);
    var s = new RogueOwnedParameterLists46.ModeState();
    var w = new World();
    RogueOwnedParameterLists46.refreshUnselectable(s, a, b, w);
    assertEquals(List.of("error"), w.order);
    assertNull(s.unselectable);
  }

  @Test
  void crossEntityErrorFailureAlsoLeavesListUntouched() {
    var a = new RogueOwnedParameterLists46.Unselectable(ENTITY);
    var b = new RogueOwnedParameterLists46.Unselectable(NEXT);
    var s = mode(a);
    var w = new World();
    w.onError =
        () -> {
          throw new IllegalStateException("log");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueOwnedParameterLists46.refreshUnselectable(s, a, b, w));
    assertSame(a, s.unselectable.entries[0]);
    assertEquals(5, s.unselectable.version);
  }

  @Test
  void unselectableRemovalRefreshesEntityAfterListMutation() {
    var p = new RogueOwnedParameterLists46.Unselectable(ENTITY);
    var s = mode(p);
    var w = new World();
    w.onRefresh = () -> assertEquals(0, s.unselectable.size);
    RogueOwnedParameterLists46.refreshUnselectable(s, p, null, w);
    assertEquals(List.of("abilityComponent", "refresh"), w.order);
    assertNull(s.unselectable.entries[0]);
  }

  @Test
  void sameEntityReplacementRemovesFirstThenAddsNewReference() {
    var a = new RogueOwnedParameterLists46.Unselectable(ENTITY);
    var b = new RogueOwnedParameterLists46.Unselectable(ENTITY);
    var s = mode(a, a);
    var w = new World();
    RogueOwnedParameterLists46.refreshUnselectable(s, a, b, w);
    assertArrayEquals(new Object[] {a, b}, s.unselectable.entries);
    assertEquals(7, s.unselectable.version);
  }

  @Test
  void nullParameterEntityFailsOnlyAfterTheRemovalPrefix() {
    var p = new RogueOwnedParameterLists46.Unselectable(null);
    var s = mode(p);
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueOwnedParameterLists46.refreshUnselectable(s, p, null, w));
    assertEquals(0, s.unselectable.size);
    assertEquals(6, s.unselectable.version);
    assertTrue(w.order.isEmpty());
  }

  @Test
  void missingAbilityComponentSkipsSelfRefreshAfterRegistration() {
    var p = new RogueOwnedParameterLists46.Unselectable(ENTITY);
    var s = mode();
    var w = new World();
    w.ability = null;
    RogueOwnedParameterLists46.refreshUnselectable(s, null, p, w);
    assertEquals(1, s.unselectable.size);
    assertEquals(List.of("abilityComponent"), w.order);
  }

  @Test
  void selfRefreshFailureDoesNotRollBackTheCompletedRemoval() {
    var p = new RogueOwnedParameterLists46.Unselectable(ENTITY);
    var s = mode(p);
    var w = new World();
    w.onRefresh =
        () -> {
          throw new IllegalStateException("refresh");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueOwnedParameterLists46.refreshUnselectable(s, p, null, w));
    assertEquals(0, s.unselectable.size);
    assertEquals(6, s.unselectable.version);
  }

  @Test
  void missingBindingsAreRequiredOnlyAfterListMutation() {
    var p = new RogueOwnedParameterLists46.Unselectable(ENTITY);
    var s = mode(p);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueOwnedParameterLists46.refreshUnselectable(s, p, null, null));
    assertEquals(0, s.unselectable.size);
  }

  @Test
  void actualModifierWithdrawalsUseNullReplacementAndMutateBothRegistrationLists() {
    var departed = new RogueOwnedParameterLists46.Departed();
    departed.hide = true;
    var unselectable = new RogueOwnedParameterLists46.Unselectable(ENTITY);
    var abilityState = ability(departed);
    var modeState = mode(unselectable);
    var transitionWorld = new World();
    var owned = RogueModifierOwnedEffects46Test.state();
    owned.departed = departed;
    owned.unselectable = unselectable;
    List<Object> recycled = new ArrayList<>();
    var withdrawalWorld =
        new RogueModifierOwnedEffects46Test.World(owned) {
          public void refreshDeparted(Object owner, Object parameter, Object replacement) {
            assertNull(owned.departed);
            assertSame(departed, parameter);
            assertNull(replacement);
            RogueOwnedParameterLists46.refreshDeparted(
                abilityState, departed, null, transitionWorld);
          }

          public void refreshUnselectable(Object gameMode, Object parameter, Object replacement) {
            assertNull(owned.unselectable);
            assertSame(unselectable, parameter);
            assertNull(replacement);
            RogueOwnedParameterLists46.refreshUnselectable(
                modeState, unselectable, null, transitionWorld);
          }

          public void recycle(Object parameter) {
            recycled.add(parameter);
          }
        };
    RogueModifierOwnedEffects46.detachDeparted(owned, withdrawalWorld);
    RogueModifierOwnedEffects46.detachUnselectable(owned, withdrawalWorld);
    assertEquals(List.of(departed, unselectable), recycled);
    assertEquals(0, abilityState.departed.size);
    assertEquals(0, modeState.unselectable.size);
    assertTrue(transitionWorld.enabled);
    assertEquals(
        List.of("visibilityComponent", "visibility", "transition", "abilityComponent", "refresh"),
        transitionWorld.order);
  }

  @Test
  void actualModifierClearAndListRemovalSurviveVisibilityFailureBeforeRecycle() {
    var departed = new RogueOwnedParameterLists46.Departed();
    var abilityState = ability(departed);
    var owned = RogueModifierOwnedEffects46Test.state();
    owned.departed = departed;
    var transitionWorld = new World();
    transitionWorld.onVisibility =
        () -> {
          throw new IllegalStateException("visibility");
        };
    var withdrawalWorld =
        new RogueModifierOwnedEffects46Test.World(owned) {
          public void refreshDeparted(Object owner, Object parameter, Object replacement) {
            assertSame(departed, parameter);
            assertNull(replacement);
            RogueOwnedParameterLists46.refreshDeparted(
                abilityState, departed, null, transitionWorld);
          }

          public void recycle(Object parameter) {
            fail("Visibility failure must stop recycling");
          }
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueModifierOwnedEffects46.detachDeparted(owned, withdrawalWorld));
    assertNull(owned.departed);
    assertEquals(0, abilityState.departed.size);
    assertEquals(6, abilityState.departed.version);
  }
}
