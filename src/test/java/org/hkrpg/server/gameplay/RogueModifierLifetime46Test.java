package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RogueModifierLifetime46Test {
  static final Object ENTITY = new Object(),
      WORLD = new Object(),
      BATTLE = new Object(),
      MODE = new Object();

  static class BoundWorld implements RogueModifierLifetime46.World {
    final List<String> order = new ArrayList<>();
    int moment = 2, entityType = 7, lastStatus, lastEntityType;
    OptionalInt row = OptionalInt.empty(), limbo = OptionalInt.empty();
    Object entity, world, battle = BATTLE, mode = MODE;
    boolean locked, hud;
    String statusName;

    public int lifeStepMoment(RogueModifierLifetime46.State state) {
      order.add("moment");
      return moment;
    }

    public OptionalInt statusTypeByName(String name) {
      order.add("status");
      statusName = name;
      return row;
    }

    public Object contextEntity(RogueModifierLifetime46.State state) {
      order.add("entity");
      return entity;
    }

    public int entityType(Object value) {
      assertSame(entity, value);
      order.add("entityType");
      return entityType;
    }

    public boolean isInLockStepStatus(
        RogueModifierLifetime46.State state, int statusType, int type) {
      order.add("locked");
      lastStatus = statusType;
      lastEntityType = type;
      return locked;
    }

    public void valueChanged(RogueModifierLifetime46.State state, int type) {
      order.add("value" + type);
    }

    public boolean hasOwnerHud(RogueModifierLifetime46.State state) {
      order.add("hud");
      return hud;
    }

    public void notifyHud(RogueModifierLifetime46.State state) {
      order.add("notifyHud");
    }

    public void destroy(RogueModifierLifetime46.State state, int source) {
      order.add("destroy" + source);
    }

    public Object gameWorld(RogueModifierLifetime46.State state) {
      order.add("world");
      return world;
    }

    public Object battleInstance(Object value) {
      assertSame(world, value);
      order.add("battle");
      return battle;
    }

    public Object gameMode(Object value) {
      assertSame(battle, value);
      order.add("mode");
      return mode;
    }

    public OptionalInt limboEntityCount(Object value) {
      assertSame(mode, value);
      order.add("limbo");
      return limbo;
    }

    public void makeLimboEntitiesDie(Object value) {
      assertSame(mode, value);
      order.add("killLimbo");
    }
  }

  static RogueModifierLifetime46.State state(int initial, int current) {
    var state = new RogueModifierLifetime46.State(new Object(), initial, current);
    state.modifierName = "effect";
    return state;
  }

  @Test
  void permanentLifetimeUsesInitialSentinelEvenWhenCurrentLifeIsPositive() {
    var state = state(-1, 2);
    var world = new BoundWorld();
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(2, state.currentLife);
    assertEquals(List.of("moment"), world.order);
  }

  @Test
  void immediateStepBypassesSameRenewTurnRestriction() {
    var state = state(2, 2);
    state.renewTurn = 7;
    state.immediately = true;
    var world = new BoundWorld();
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(1, state.currentLife);
    assertEquals(List.of("moment", "status", "value3", "hud"), world.order);
  }

  @Test
  void valueChangePrecedesHudNotificationAndExpiryDestroy() {
    var state = state(1, 1);
    var world = new BoundWorld();
    world.hud = true;
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(
        List.of("moment", "status", "value3", "hud", "notifyHud", "destroy2", "world"),
        world.order);
  }

  @Test
  void ordinaryUnknownAndRoundEndMomentsDoNotStep() {
    for (int moment : List.of(0, 6)) {
      var state = state(2, 2);
      var world = new BoundWorld();
      world.moment = moment;
      RogueModifierLifetime46.process(state, 7, world);
      assertEquals(2, state.currentLife);
      assertEquals(List.of("moment"), world.order);
    }
  }

  @Test
  void allFiveOrdinaryMomentsAdvanceOnceWhenTurnDiffers() {
    for (int moment = 1; moment <= 5; moment++) {
      var state = state(2, 2);
      var world = new BoundWorld();
      world.moment = moment;
      RogueModifierLifetime46.process(state, 7, world);
      assertEquals(1, state.currentLife);
      assertEquals(List.of("moment", "status", "value3", "hud"), world.order);
    }
  }

  @Test
  void sameRenewTurnWithoutImmediateDoesNotAdvance() {
    var state = state(2, 2);
    state.renewTurn = 7;
    var world = new BoundWorld();
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(2, state.currentLife);
    assertEquals(List.of("moment"), world.order);
  }

  @Test
  void roundChangeAdvancesDespiteSameTurnAndNonImmediate() {
    var state = state(2, 2);
    state.renewTurn = 7;
    var world = new BoundWorld();
    world.moment = 6;
    RogueModifierLifetime46.roundRemainingChanged(state, world);
    assertEquals(1, state.currentLife);
    assertEquals(List.of("moment", "status", "value3", "hud"), world.order);
  }

  @Test
  void nonRoundModesDoNotAdvanceThroughRoundCallback() {
    for (int moment = 0; moment <= 5; moment++) {
      var state = state(2, 2);
      var world = new BoundWorld();
      world.moment = moment;
      RogueModifierLifetime46.roundRemainingChanged(state, world);
      assertEquals(2, state.currentLife);
      assertEquals(List.of("moment"), world.order);
    }
  }

  @Test
  void permanentInitialLifeAlsoSkipsRoundCallbackWork() {
    var state = state(-1, 0);
    var world = new BoundWorld();
    world.moment = 6;
    RogueModifierLifetime46.roundRemainingChanged(state, world);
    assertEquals(0, state.currentLife);
    assertEquals(List.of("moment"), world.order);
  }

  @Test
  void currentMinusOneIsNotThePermanentSentinel() {
    var state = state(2, -1);
    var world = new BoundWorld();
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(-2, state.currentLife);
    assertEquals("world", world.order.getLast());
    assertTrue(world.order.contains("destroy2"));
  }

  @Test
  void remainingLifeDecrementUsesSigned32Wrap() {
    var state = state(2, Integer.MIN_VALUE);
    var world = new BoundWorld();
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(Integer.MAX_VALUE, state.currentLife);
    assertFalse(world.order.contains("destroy2"));
  }

  @Test
  void nonemptyAndWhitespaceOverrideKeysAreUsedWithoutTrimming() {
    for (String key : List.of("override", " ")) {
      var state = state(2, 2);
      state.overrideStatusName = key;
      var world = new BoundWorld();
      RogueModifierLifetime46.process(state, 7, world);
      assertEquals(key, world.statusName);
    }
  }

  @Test
  void missingAndEmptyOverrideKeysUseOriginalName() {
    for (String key : java.util.Arrays.asList(null, "")) {
      var state = state(2, 2);
      state.overrideStatusName = key;
      var world = new BoundWorld();
      RogueModifierLifetime46.process(state, 7, world);
      assertEquals("effect", world.statusName);
    }
  }

  @Test
  void missingRowDoesNotQueryContextEntityOrLockState() {
    var state = state(2, 2);
    var world = new BoundWorld();
    world.locked = true;
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(1, state.currentLife);
    assertFalse(world.order.contains("entity"));
    assertFalse(world.order.contains("locked"));
  }

  @Test
  void absentContextEntityUsesTypeZeroForPresentStatusRow() {
    var state = state(2, 2);
    var world = new BoundWorld();
    world.row = OptionalInt.of(2);
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(2, world.lastStatus);
    assertEquals(0, world.lastEntityType);
    assertFalse(world.order.contains("entityType"));
    assertEquals(1, state.currentLife);
  }

  @Test
  void boundContextEntityTypeIsPassedToLockQuery() {
    var state = state(2, 2);
    var world = new BoundWorld();
    world.row = OptionalInt.of(1);
    world.entity = ENTITY;
    world.entityType = 9;
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(1, world.lastStatus);
    assertEquals(9, world.lastEntityType);
    assertEquals(
        List.of("moment", "status", "entity", "entityType", "locked", "value3", "hud"),
        world.order);
  }

  @Test
  void lockedStatusWithoutInstanceTagPreservesLifeButNotHudRefresh() {
    var state = state(2, 2);
    var world = new BoundWorld();
    world.row = OptionalInt.of(2);
    world.locked = true;
    world.hud = true;
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(2, state.currentLife);
    assertEquals(List.of("moment", "status", "entity", "locked", "hud", "notifyHud"), world.order);
  }

  @Test
  void nonzeroInstanceLockTagStillAllowsDecrementWhenSourceIsLocked() {
    for (int tag : List.of(1, 2, 3)) {
      var state = state(2, 2);
      state.lockStepStatusType = tag;
      var world = new BoundWorld();
      world.row = OptionalInt.of(2);
      world.locked = true;
      RogueModifierLifetime46.process(state, 7, world);
      assertEquals(1, state.currentLife);
      assertTrue(world.order.contains("value3"));
    }
  }

  @Test
  void preservedZeroLifeStillExpiresAfterHudRefresh() {
    var state = state(2, 0);
    var world = new BoundWorld();
    world.row = OptionalInt.of(2);
    world.locked = true;
    world.hud = true;
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(0, state.currentLife);
    assertFalse(world.order.contains("value3"));
    assertEquals(
        List.of("notifyHud", "destroy2", "world"),
        world.order.subList(world.order.size() - 3, world.order.size()));
  }

  @Test
  void changedValueCallbackSeesUpdatedLifeAndCanPreventLaterExpiry() {
    var state = state(1, 1);
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierLifetime46.State s, int type) {
            assertEquals(0, s.currentLife);
            super.valueChanged(s, type);
            s.currentLife = 2;
          }
        };
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(2, state.currentLife);
    assertFalse(world.order.contains("destroy2"));
  }

  @Test
  void hudNotificationMutationControlsLaterExpiryCheck() {
    var state = state(1, 1);
    var world =
        new BoundWorld() {
          public void notifyHud(RogueModifierLifetime46.State s) {
            super.notifyHud(s);
            s.currentLife = 2;
          }
        };
    world.hud = true;
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(2, state.currentLife);
    assertFalse(world.order.contains("destroy2"));
  }

  @Test
  void valueCallbackFailureKeepsDecrementButStopsHudAndDestroy() {
    var failure = new IllegalStateException("binding unavailable");
    var state = state(1, 1);
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierLifetime46.State s, int type) {
            super.valueChanged(s, type);
            throw failure;
          }
        };
    world.hud = true;
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class, () -> RogueModifierLifetime46.process(state, 7, world)));
    assertEquals(0, state.currentLife);
    assertEquals("value3", world.order.getLast());
    assertFalse(world.order.contains("destroy2"));
  }

  @Test
  void destroyFailureStopsPostExpiryWorldReads() {
    var failure = new IllegalStateException("destroy unavailable");
    var state = state(1, 1);
    var world =
        new BoundWorld() {
          public void destroy(RogueModifierLifetime46.State s, int source) {
            super.destroy(s, source);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class, () -> RogueModifierLifetime46.process(state, 7, world)));
    assertEquals("destroy2", world.order.getLast());
    assertFalse(world.order.contains("world"));
  }

  @Test
  void absentWorldAfterDestroySkipsBattleModeAndLimbo() {
    var state = state(1, 1);
    var world = new BoundWorld();
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals("world", world.order.getLast());
    assertFalse(world.order.contains("battle"));
  }

  @Test
  void presentWorldRequiresBattleThenMode() {
    var state = state(1, 1);
    var world = new BoundWorld();
    world.world = WORLD;
    world.battle = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierLifetime46.process(state, 7, world));
    assertEquals("battle", world.order.getLast());
    var missingMode = new BoundWorld();
    missingMode.world = WORLD;
    missingMode.mode = null;
    var request = state(1, 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierLifetime46.process(request, 7, missingMode));
    assertEquals("mode", missingMode.order.getLast());
  }

  @Test
  void absentOrZeroLimboContainerDoesNotKillEntities() {
    for (OptionalInt count : List.of(OptionalInt.empty(), OptionalInt.of(0))) {
      var state = state(1, 1);
      var world = new BoundWorld();
      world.world = WORLD;
      world.limbo = count;
      RogueModifierLifetime46.process(state, 7, world);
      assertEquals(
          List.of("world", "battle", "mode", "limbo"),
          world.order.subList(world.order.size() - 4, world.order.size()));
    }
  }

  @Test
  void positiveLimboCountKillsAfterExpiryAndAllWorldLookups() {
    var state = state(1, 1);
    var world = new BoundWorld();
    world.world = WORLD;
    world.limbo = OptionalInt.of(1);
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(
        List.of("destroy2", "world", "battle", "mode", "limbo", "killLimbo"),
        world.order.subList(world.order.size() - 6, world.order.size()));
  }

  @Test
  void nullAndNegativeLimboCountAreClosedAfterStateExpiry() {
    for (OptionalInt count : java.util.Arrays.asList(null, OptionalInt.of(-1))) {
      var state = state(1, 1);
      var world = new BoundWorld();
      world.world = WORLD;
      world.limbo = count;
      assertThrows(
          IllegalArgumentException.class, () -> RogueModifierLifetime46.process(state, 7, world));
      assertEquals("limbo", world.order.getLast());
      assertEquals(0, state.currentLife);
    }
  }

  @Test
  void missingLookupResultIsDistinctFromMissingStatusRow() {
    var state = state(2, 2);
    var world = new BoundWorld();
    world.row = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierLifetime46.process(state, 7, world));
    assertEquals(2, state.currentLife);
    assertEquals("status", world.order.getLast());
  }

  @Test
  void invalidMomentStopsBeforeStatusAndStateChanges() {
    for (int moment : List.of(-1, 7)) {
      var state = state(2, 2);
      var world = new BoundWorld();
      world.moment = moment;
      assertThrows(
          IllegalArgumentException.class, () -> RogueModifierLifetime46.process(state, 7, world));
      assertEquals(2, state.currentLife);
      assertEquals(List.of("moment"), world.order);
    }
  }

  @Test
  void missingStateWorldOrIdentityIsClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierLifetime46.process(null, 7, new BoundWorld()));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierLifetime46.process(state(2, 2), 7, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierLifetime46.roundRemainingChanged(null, new BoundWorld()));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueModifierLifetime46.State(null, 2, 2));
  }

  static class LockSource implements RogueModifierLifetime46.LockSource {
    final List<String> order = new ArrayList<>();
    int count;
    List<? extends List<Integer>> lists;

    public int count(int type) {
      order.add("count" + type);
      return count;
    }

    public List<? extends List<Integer>> entityTypeLists(int type) {
      order.add("lists" + type);
      return lists;
    }
  }

  @Test
  void unsupportedStatusTypesSkipAllLockDataReads() {
    for (int type : List.of(0, 3, -1, 99))
      assertFalse(RogueModifierLifetime46.statusLocked(type, 7, null));
  }

  @Test
  void knownStatusesRequireExplicitLockBinding() {
    for (int type : List.of(1, 2))
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueModifierLifetime46.statusLocked(type, 7, null));
  }

  @Test
  void positiveLockCountReturnsBeforeSourceListsAreRead() {
    for (int type : List.of(1, 2)) {
      var source = new LockSource();
      source.count = 1;
      assertTrue(RogueModifierLifetime46.statusLocked(type, 7, source));
      assertEquals(List.of("count" + type), source.order);
    }
  }

  @Test
  void zeroAndNegativeCountsStillInspectCurrentSourceLists() {
    for (int count : List.of(0, -1)) {
      var source = new LockSource();
      source.count = count;
      source.lists = List.of(List.of(8), List.of(7));
      assertTrue(RogueModifierLifetime46.statusLocked(2, 7, source));
      assertEquals(List.of("count2", "lists2"), source.order);
    }
  }

  @Test
  void nullOrEmptyOuterSourcesAreNotLocked() {
    for (List<? extends List<Integer>> lists :
        java.util.Arrays.asList(null, List.<List<Integer>>of(), List.of(List.<Integer>of()))) {
      var source = new LockSource();
      source.lists = lists;
      assertFalse(RogueModifierLifetime46.statusLocked(1, 7, source));
    }
  }

  @Test
  void exactEntityTypeMatchDoesNotTreatZeroAsWildcard() {
    var source = new LockSource();
    source.lists = List.of(List.of(0));
    assertFalse(RogueModifierLifetime46.statusLocked(1, 7, source));
    assertTrue(RogueModifierLifetime46.statusLocked(1, 0, source));
  }

  @Test
  void firstMatchingListStopsBeforeLaterMissingList() {
    var source = new LockSource();
    source.lists = java.util.Arrays.asList(List.of(7), null);
    assertTrue(RogueModifierLifetime46.statusLocked(1, 7, source));
  }

  @Test
  void missingInnerListAndNullEntityTypeAreClosed() {
    var missing = new LockSource();
    missing.lists = java.util.Arrays.asList((List<Integer>) null);
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierLifetime46.statusLocked(1, 7, missing));
    var value = new LockSource();
    value.lists = List.of(java.util.Arrays.asList((Integer) null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierLifetime46.statusLocked(2, 7, value));
  }

  @Test
  void unmatchedOuterAndTotalValueBudgetsAreClosedAfter255Visits() {
    var outer = new LockSource();
    outer.lists = java.util.Collections.nCopies(256, List.of());
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierLifetime46.statusLocked(1, 7, outer));
    var values = new LockSource();
    values.lists = List.of(java.util.Collections.nCopies(256, 8));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierLifetime46.statusLocked(1, 7, values));
    values.lists = List.of(java.util.Collections.nCopies(255, 8));
    assertFalse(RogueModifierLifetime46.statusLocked(1, 7, values));
  }

  @Test
  void earlyLockMatchDoesNotInspectUnvisitedOversizedTail() {
    var source = new LockSource();
    var values = new ArrayList<Integer>();
    values.add(7);
    values.addAll(java.util.Collections.nCopies(256, 8));
    source.lists = List.of(values);
    assertTrue(RogueModifierLifetime46.statusLocked(1, 7, source));
  }

  @Test
  void lockCounterFailurePreventsSourceListLookup() {
    var failure = new IllegalStateException("counter unavailable");
    var source =
        new LockSource() {
          public int count(int type) {
            super.count(type);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class, () -> RogueModifierLifetime46.statusLocked(1, 7, source)));
    assertEquals(List.of("count1"), source.order);
  }

  @Test
  void finiteLockSourceHelperCanPreserveBoundLifetimeStep() {
    var source = new LockSource();
    source.lists = List.of(List.of(7));
    var world =
        new BoundWorld() {
          public boolean isInLockStepStatus(
              RogueModifierLifetime46.State s, int type, int entityType) {
            super.isInLockStepStatus(s, type, entityType);
            return RogueModifierLifetime46.statusLocked(type, entityType, source);
          }
        };
    world.row = OptionalInt.of(2);
    world.entity = ENTITY;
    var state = state(2, 2);
    RogueModifierLifetime46.process(state, 7, world);
    assertEquals(2, state.currentLife);
    assertFalse(world.order.contains("value3"));
    assertEquals(List.of("count2", "lists2"), source.order);
  }
}
