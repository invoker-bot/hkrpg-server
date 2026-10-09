package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueDepartedTransition46Test {
  static final Object ENTITY = new Object(),
      NEXT = new Object(),
      GAME_WORLD = new Object(),
      BATTLE = new Object(),
      MODE = new Object(),
      PROCESSOR = new Object();

  static RogueOwnedParameterLists46.AbilityState state() {
    var s = new RogueOwnedParameterLists46.AbilityState();
    s.ownerEntity = ENTITY;
    return s;
  }

  static class World implements RogueDepartedTransition46.World {
    final List<String> order = new ArrayList<>();
    final RogueDepartedTransition46.Event payload = new RogueDepartedTransition46.Event();
    RogueDepartedTransition46.Event acquired = payload;
    int missing = -1, entityEvent, globalEvent;
    Object globalSource;
    String faultAt;
    Runnable onEntity = () -> {}, onGlobal = () -> {}, onRelease = () -> {}, onAcquire = () -> {};

    void step(String name) {
      order.add(name);
      if (name.equals(faultAt)) throw new IllegalStateException(name);
    }

    public RogueDepartedTransition46.Event acquireEvent() {
      step("acquire");
      onAcquire.run();
      return acquired;
    }

    public Object entityWorld(Object entity) {
      assertSame(ENTITY, entity);
      assertSame(ENTITY, payload.ownerEntity);
      step("world");
      return missing == 0 ? null : GAME_WORLD;
    }

    public Object worldBattleInstance(Object world) {
      assertSame(GAME_WORLD, world);
      step("battle");
      return missing == 1 ? null : BATTLE;
    }

    public Object battleGameMode(Object battle) {
      assertSame(BATTLE, battle);
      step("mode");
      return missing == 2 ? null : MODE;
    }

    public Object eventProcessor(Object mode) {
      assertSame(MODE, mode);
      step("processor");
      return missing == 3 ? null : PROCESSOR;
    }

    public void dispatchEntity(
        Object processor,
        Object entity,
        int eventType,
        RogueDepartedTransition46.Event event,
        Object source,
        int mask) {
      assertSame(PROCESSOR, processor);
      assertSame(ENTITY, entity);
      assertSame(payload, event);
      assertNull(source);
      assertEquals(0, mask);
      entityEvent = eventType;
      step("entityEvent");
      onEntity.run();
    }

    public void dispatchGlobal(
        Object processor,
        int eventType,
        RogueDepartedTransition46.Event event,
        Object source,
        int mask,
        Object extra) {
      assertSame(PROCESSOR, processor);
      assertSame(payload, event);
      assertEquals(0, mask);
      assertNull(extra);
      globalSource = source;
      globalEvent = eventType;
      step("globalEvent");
      onGlobal.run();
    }

    public void releaseEvent(RogueDepartedTransition46.Event event) {
      assertSame(payload, event);
      step("release");
      onRelease.run();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void entityThenGlobalThenReleaseUsesActualStartOrEndEventIds(boolean previous) {
    var s = state();
    var w = new World();
    RogueDepartedTransition46.dispatch(s, previous, w);
    assertEquals(previous ? 227 : 226, w.entityEvent);
    assertEquals(previous ? 229 : 228, w.globalEvent);
    assertEquals(
        List.of(
            "acquire",
            "world",
            "battle",
            "mode",
            "processor",
            "entityEvent",
            "globalEvent",
            "release"),
        w.order);
    assertSame(ENTITY, w.payload.ownerEntity);
    assertSame(ENTITY, w.globalSource);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void optionalBattleModeOrProcessorAbsenceSkipsDispatchAndStillReleases(int missing) {
    var w = new World();
    w.missing = missing;
    RogueDepartedTransition46.dispatch(state(), false, w);
    List<String> prefix =
        new ArrayList<>(
            List.of("acquire", "world", "battle", "mode", "processor").subList(0, missing + 2));
    prefix.add("release");
    assertEquals(prefix, w.order);
    assertEquals(0, w.entityEvent);
    assertEquals(0, w.globalEvent);
  }

  @Test
  void missingOwnerWritesNullIntoAcquiredPayloadBeforeFailureWithoutRelease() {
    var s = state();
    s.ownerEntity = null;
    var w = new World();
    w.payload.ownerEntity = NEXT;
    assertThrows(
        IllegalArgumentException.class, () -> RogueDepartedTransition46.dispatch(s, false, w));
    assertNull(w.payload.ownerEntity);
    assertEquals(List.of("acquire"), w.order);
  }

  @Test
  void missingWorldReferenceKeepsAssignedEventOwnerWithoutRelease() {
    var w = new World();
    w.missing = 0;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueDepartedTransition46.dispatch(state(), false, w));
    assertSame(ENTITY, w.payload.ownerEntity);
    assertEquals(List.of("acquire", "world"), w.order);
  }

  @Test
  void missingAcquiredEventStopsBeforeOwnerAssignmentAndWorldQueries() {
    var w = new World();
    w.acquired = null;
    w.payload.ownerEntity = NEXT;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueDepartedTransition46.dispatch(state(), false, w));
    assertSame(NEXT, w.payload.ownerEntity);
    assertEquals(List.of("acquire"), w.order);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"acquire", "world", "battle", "mode", "processor", "entityEvent", "globalEvent"})
  void failedPrefixDoesNotExecuteReleaseOrLaterDispatch(String failure) {
    var w = new World();
    w.faultAt = failure;
    assertThrows(
        IllegalStateException.class, () -> RogueDepartedTransition46.dispatch(state(), true, w));
    List<String> all =
        List.of("acquire", "world", "battle", "mode", "processor", "entityEvent", "globalEvent");
    assertEquals(all.subList(0, all.indexOf(failure) + 1), w.order);
    assertFalse(w.order.contains("release"));
  }

  @Test
  void releaseFailurePreservesBothCompletedDispatches() {
    var w = new World();
    w.faultAt = "release";
    assertThrows(
        IllegalStateException.class, () -> RogueDepartedTransition46.dispatch(state(), false, w));
    assertEquals(226, w.entityEvent);
    assertEquals(228, w.globalEvent);
    assertEquals("release", w.order.getLast());
  }

  @Test
  void entityCallbackChangesOwnerUsedByGlobalButNotCapturedEntityArgument() {
    var s = state();
    var w = new World();
    w.onEntity = () -> s.ownerEntity = NEXT;
    RogueDepartedTransition46.dispatch(s, false, w);
    assertSame(NEXT, w.globalSource);
    assertSame(ENTITY, w.payload.ownerEntity);
    assertSame(NEXT, s.ownerEntity);
  }

  @Test
  void entityCallbackClearedOwnerIsPassedToGlobalWithoutAdditionalOwnerRequirement() {
    var s = state();
    var w = new World();
    w.onEntity = () -> s.ownerEntity = null;
    RogueDepartedTransition46.dispatch(s, false, w);
    assertNull(w.globalSource);
    assertNull(s.ownerEntity);
    assertEquals("release", w.order.getLast());
  }

  @Test
  void payloadOwnerReplacementDuringEntityDispatchSurvivesGlobalAndRelease() {
    var w = new World();
    w.onEntity = () -> w.payload.ownerEntity = NEXT;
    w.onGlobal = () -> assertSame(NEXT, w.payload.ownerEntity);
    RogueDepartedTransition46.dispatch(state(), false, w);
    assertSame(NEXT, w.payload.ownerEntity);
  }

  @Test
  void acquisitionCallbackOwnerChangeIsObservedBeforePayloadAssignment() {
    var s = state();
    var w = new World();
    w.onAcquire = () -> s.ownerEntity = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueDepartedTransition46.dispatch(s, false, w));
    assertNull(w.payload.ownerEntity);
    assertEquals(List.of("acquire"), w.order);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualRegistrationPresenceTransitionDispatchesMatchingPayloadPair(boolean previous) {
    var p = new RogueOwnedParameterLists46.Departed();
    var s = RogueOwnedParameterLists46Test.ability(previous ? new Object[] {p} : new Object[] {});
    s.ownerEntity = ENTITY;
    var eventWorld = new World();
    var registrationWorld =
        new RogueOwnedParameterLists46Test.World() {
          public Object visibilityComponent(Object entity) {
            assertSame(ENTITY, entity);
            order.add("visibilityComponent");
            return null;
          }

          public void departedTransition(
              RogueOwnedParameterLists46.AbilityState state, boolean old) {
            order.add("transition");
            RogueDepartedTransition46.dispatch(state, old, eventWorld);
          }
        };
    RogueOwnedParameterLists46.refreshDeparted(
        s, previous ? p : null, previous ? null : p, registrationWorld);
    assertEquals(previous ? 0 : 1, s.departed.size);
    assertEquals(previous ? 227 : 226, eventWorld.entityEvent);
    assertEquals(previous ? 229 : 228, eventWorld.globalEvent);
    assertEquals(List.of("visibilityComponent", "transition"), registrationWorld.order);
    assertEquals("release", eventWorld.order.getLast());
  }

  @Test
  void eventFailureKeepsTheRealRegistrationRemovalAndVisibilityPrefix() {
    var p = new RogueOwnedParameterLists46.Departed();
    var s = RogueOwnedParameterLists46Test.ability(p);
    s.ownerEntity = ENTITY;
    var eventWorld = new World();
    eventWorld.faultAt = "entityEvent";
    var registrationWorld =
        new RogueOwnedParameterLists46Test.World() {
          public Object visibilityComponent(Object entity) {
            return null;
          }

          public void departedTransition(
              RogueOwnedParameterLists46.AbilityState state, boolean old) {
            RogueDepartedTransition46.dispatch(state, old, eventWorld);
          }
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueOwnedParameterLists46.refreshDeparted(s, p, null, registrationWorld));
    assertEquals(0, s.departed.size);
    assertNull(s.departed.entries[0]);
    assertEquals(6, s.departed.version);
    assertEquals(227, eventWorld.entityEvent);
    assertEquals(0, eventWorld.globalEvent);
    assertFalse(eventWorld.order.contains("release"));
  }

  @Test
  void explicitStateAndWorldAreRequiredBeforeAcquisition() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueDepartedTransition46.dispatch(null, false, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueDepartedTransition46.dispatch(state(), false, null));
  }
}
