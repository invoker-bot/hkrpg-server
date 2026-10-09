package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueModifierUnstack46Test {
  static final List<String> ORDER =
      List.of(
          "properties",
          "weakness",
          "additionalBp",
          "boostCallback",
          "specialBp",
          "name",
          "binding",
          "ai",
          "upper",
          "character",
          "departed",
          "unselectable");
  static final Object OWNER = new Object(),
      TEAM_BP = new Object(),
      TEAM_BOOST = new Object(),
      TEAM_SPECIAL = new Object(),
      DEFAULT = new Object(),
      CHARACTER = new Object(),
      DEPARTED = new Object(),
      UNSELECTABLE = new Object();

  static RogueModifierRegistrations46.Strings keys(String key) {
    var s = new RogueModifierRegistrations46.Strings();
    s.slots =
        new RogueModifierRegistrations46.Slot[] {new RogueModifierRegistrations46.Slot(1, -1, key)};
    s.buckets = new int[] {1};
    s.bucketLength = 1;
    s.count = 1;
    s.lastIndex = 1;
    s.version = 7;
    return s;
  }

  static final class Fixture {
    final RogueModifierCleanup46.State cleanup = RogueModifierCleanup46Test.state();
    final RogueModifierStackedEffects46.State stacked;
    final RogueModifierRegistrations46.State registrations;
    final RogueModifierOwnedEffects46.State owned;
    final RogueModifierUnstack46.State state;
    final RogueModifierRegistrations46.Ai ai = new RogueModifierRegistrations46.Ai();
    final RogueBindingPayload46.State binding = RogueBindingPayload46.create();
    final List<String> order = new ArrayList<>();
    final List<Object> recycled = new ArrayList<>();
    final Map<String, Runnable> callbacks = new HashMap<>();
    final RogueModifierUnstack46.World world;
    boolean missingSpecialModule;
    Object specialModifier;
    int globalEvents;

    Fixture() {
      cleanup.ownerAbilityComponent = OWNER;
      var stack = new RogueModifierStackEvent46.State(cleanup.sequence.replacement);
      stack.parameterFrames = new ArrayDeque<>();
      stack.stackedAdditionalTeamBp =
          new ArrayList<>(List.of(new RogueModifierStackedEffects46.AdditionalBp(TEAM_BP, 28)));
      stacked = new RogueModifierStackedEffects46.State(stack);
      stacked.weaknesses =
          new ArrayList<>(List.of(new RogueModifierStackedEffects46.Weakness(OWNER, 2, 5)));
      stacked.boostContexts = new RogueModifierStackedEffects46.BoostContexts();
      stacked.boostContexts.add(TEAM_BOOST);
      registrations = new RogueModifierRegistrations46.State(cleanup);
      registrations.names = keys("override");
      registrations.bindKeys = keys("binding");
      registrations.crazyBehavior = new RogueModifierRegistrations46.Behavior(44);
      ai.current = registrations.crazyBehavior;
      ai.behaviors =
          new RogueOwnedParameterLists46.References(
              new Object[] {registrations.crazyBehavior}, 1, 8);
      registrations.upperGroups = new RogueModifierRegistrations46.UpperIds();
      registrations.upperGroups.data = new RogueModifierRegistrations46.UpperData();
      registrations.upperGroups.data.values = new int[] {33};
      registrations.upperGroups.data.size = 1;
      registrations.upperGroups.data.version = 2;
      owned = new RogueModifierOwnedEffects46.State(cleanup);
      owned.characterChange = CHARACTER;
      owned.departed = DEPARTED;
      owned.unselectable = UNSELECTABLE;
      state = new RogueModifierUnstack46.State(stacked, registrations, owned);
      state.bpDirty = new RogueModifierUnstack46.BpDirty(TEAM_SPECIAL, new Object(), -9);
      binding.key = "dirty";
      binding.context = OWNER;
      world =
          (RogueModifierUnstack46.World)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {RogueModifierUnstack46.World.class},
                  (p, m, a) -> {
                    switch (m.getName()) {
                      case "popStackedProperties":
                        assertSame(stack, a[0]);
                        step("properties");
                        return null;
                      case "weaknessComponent":
                      case "abilityEntity":
                        return a[0];
                      case "unstackWeakness":
                        assertEquals(2, a[2]);
                        assertEquals(5, a[3]);
                        step("weakness");
                        return null;
                      case "teamBoostPointModule":
                        return a[0] == TEAM_SPECIAL && missingSpecialModule ? null : a[0];
                      case "removeAdditionalTeamBp":
                        assertSame(TEAM_BP, a[0]);
                        assertEquals(28, a[1]);
                        step("additionalBp");
                        return null;
                      case "removeBoostCallback":
                        assertSame(TEAM_BOOST, a[0]);
                        assertSame(stack.replacement, a[1]);
                        step("boostCallback");
                        return null;
                      case "removeSpecialBp":
                        assertSame(TEAM_SPECIAL, a[0]);
                        specialModifier = a[1];
                        step("specialBp");
                        return null;
                      case "nameMapping":
                      case "bindMapping":
                        return a[0];
                      case "removeName":
                        assertEquals("override", a[1]);
                        step("name");
                        return null;
                      case "lookupBinding":
                        assertEquals("binding", a[1]);
                        return new RogueModifierRegistrations46.Lookup(true, binding);
                      case "recycleBinding":
                        assertSame(binding, a[0]);
                        RogueBindingPayload46.clear(binding, new RogueBindingPayload46.Catalog(""));
                        return null;
                      case "removeBinding":
                        assertEquals("binding", a[1]);
                        step("binding");
                        return null;
                      case "ownerEntity":
                      case "entityWorld":
                      case "worldBattleInstance":
                      case "battleGameMode":
                      case "publicKnowledge":
                      case "upperMap":
                        return a[0];
                      case "aiComponent":
                        return ai;
                      case "defaultBehavior":
                        step("ai");
                        return DEFAULT;
                      case "removeUpper":
                        assertEquals(33, a[1]);
                        step("upper");
                        return null;
                      case "detachCharacterChange":
                        assertSame(CHARACTER, a[1]);
                        assertEquals(true, a[2]);
                        step("character");
                        return null;
                      case "refreshDeparted":
                        assertSame(DEPARTED, a[1]);
                        assertNull(a[2]);
                        step("departed");
                        return null;
                      case "refreshUnselectable":
                        assertSame(UNSELECTABLE, a[1]);
                        assertNull(a[2]);
                        step("unselectable");
                        return null;
                      case "recycle":
                        recycled.add(a[0]);
                        return null;
                      case "notifyGlobal":
                        assertEquals(990, a[0]);
                        assertNull(a[1]);
                        assertEquals(true, a[2]);
                        globalEvents++;
                        return null;
                      default:
                        throw new AssertionError(m.getName());
                    }
                  });
    }

    void step(String name) {
      order.add(name);
      callbacks.getOrDefault(name, () -> {}).run();
    }

    void run() {
      RogueModifierUnstack46.unstack(state, world);
    }
  }

  @Test
  void allExistingWithdrawalsRunInNativeOrderWithSameModifier() {
    Fixture f = new Fixture();
    f.run();
    assertEquals(ORDER, f.order);
    assertSame(f.cleanup.sequence.replacement, f.specialModifier);
    assertTrue(f.stacked.weaknesses.isEmpty());
    assertTrue(f.stacked.stack.stackedAdditionalTeamBp.isEmpty());
    assertTrue(f.stacked.boostContexts.isEmpty());
    assertSame(RogueModifierUnstack46.BpDirty.ZERO, f.state.bpDirty);
    assertEquals(0, f.registrations.names.count);
    assertEquals(0, f.registrations.bindKeys.count);
    assertSame(DEFAULT, f.ai.current);
    assertNull(f.registrations.crazyBehavior);
    assertEquals(0, f.ai.behaviors.size);
    assertEquals(1, f.registrations.upperGroups.data.size);
    assertEquals(2, f.registrations.upperGroups.data.version);
    assertNull(f.owned.characterChange);
    assertNull(f.owned.departed);
    assertNull(f.owned.unselectable);
    assertEquals(List.of(CHARACTER, DEPARTED, UNSELECTABLE), f.recycled);
    assertEquals(1, f.globalEvents);
    assertEquals("", f.binding.key);
    assertNull(f.binding.context);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "properties",
        "weakness",
        "additionalBp",
        "boostCallback",
        "specialBp",
        "name",
        "binding",
        "ai",
        "upper",
        "character",
        "departed",
        "unselectable"
      })
  void exceptionAtEachStageStopsLaterStagesWithoutFinallyCleanup(String stage) {
    Fixture f = new Fixture();
    var oldDirty = f.state.bpDirty;
    f.callbacks.put(
        stage,
        () -> {
          throw new IllegalStateException(stage);
        });
    assertThrows(IllegalStateException.class, f::run);
    int index = ORDER.indexOf(stage);
    assertEquals(ORDER.subList(0, index + 1), f.order);
    if (index <= 4) assertSame(oldDirty, f.state.bpDirty);
    else assertSame(RogueModifierUnstack46.BpDirty.ZERO, f.state.bpDirty);
    if (index < 9) assertSame(CHARACTER, f.owned.characterChange);
    else assertNull(f.owned.characterChange);
    if (index < 10) assertSame(DEPARTED, f.owned.departed);
    else assertNull(f.owned.departed);
    if (index < 11) assertSame(UNSELECTABLE, f.owned.unselectable);
    else assertNull(f.owned.unselectable);
    if (index < 7) assertEquals(1, f.ai.behaviors.size);
    else assertEquals(0, f.ai.behaviors.size);
    if (index >= 6) {
      assertEquals("", f.binding.key);
      assertNull(f.binding.context);
    }
  }

  @Test
  void propertiesFailureLeavesEveryKnownWithdrawalUnstarted() {
    Fixture f = new Fixture();
    f.callbacks.put(
        "properties",
        () -> {
          throw new IllegalStateException();
        });
    assertThrows(IllegalStateException.class, f::run);
    assertEquals(1, f.stacked.weaknesses.size());
    assertEquals(1, f.stacked.stack.stackedAdditionalTeamBp.size());
    assertEquals(1, f.stacked.boostContexts.size());
    assertEquals(1, f.registrations.names.count);
    assertEquals("dirty", f.binding.key);
    assertTrue(f.recycled.isEmpty());
    assertEquals(0, f.globalEvents);
  }

  @Test
  void bindingFailureKeepsClearedPayloadAndUnclearedSourceKeys() {
    Fixture f = new Fixture();
    f.callbacks.put(
        "binding",
        () -> {
          throw new IllegalStateException();
        });
    assertThrows(IllegalStateException.class, f::run);
    assertEquals("", f.binding.key);
    assertNull(f.binding.context);
    assertEquals(1, f.registrations.bindKeys.count);
    assertEquals(7, f.registrations.bindKeys.version);
    assertEquals(0, f.registrations.names.count);
  }

  @Test
  void aiSelectionFailureKeepsRemovedListAndOldCurrentAndModifierCache() {
    Fixture f = new Fixture();
    Object old = f.registrations.crazyBehavior;
    f.callbacks.put(
        "ai",
        () -> {
          throw new IllegalStateException();
        });
    assertThrows(IllegalStateException.class, f::run);
    assertSame(old, f.ai.current);
    assertSame(old, f.registrations.crazyBehavior);
    assertNull(f.ai.behaviors.entries[0]);
    assertEquals(9, f.ai.behaviors.version);
  }

  @Test
  void characterFailureAlreadyClearsCharacterCacheAndDoesNotRecycle() {
    Fixture f = new Fixture();
    f.callbacks.put(
        "character",
        () -> {
          throw new IllegalStateException();
        });
    assertThrows(IllegalStateException.class, f::run);
    assertNull(f.owned.characterChange);
    assertSame(DEPARTED, f.owned.departed);
    assertTrue(f.recycled.isEmpty());
    assertEquals(0, f.globalEvents);
  }

  @Test
  void laterFailureKeepsEarlierRecyclesAndCharacterNotification() {
    Fixture f = new Fixture();
    f.callbacks.put(
        "unselectable",
        () -> {
          throw new IllegalStateException();
        });
    assertThrows(IllegalStateException.class, f::run);
    assertEquals(List.of(CHARACTER, DEPARTED), f.recycled);
    assertEquals(1, f.globalEvents);
    assertNull(f.owned.unselectable);
  }

  @Test
  void nullTeamPreservesOtherEmbeddedDirtyMembersWithoutWorld() {
    Fixture f = new Fixture();
    var old = new RogueModifierUnstack46.BpDirty(null, OWNER, 71);
    f.state.bpDirty = old;
    RogueModifierUnstack46.popSpecialBp(f.state, null);
    assertSame(old, f.state.bpDirty);
    assertTrue(f.order.isEmpty());
  }

  @Test
  void absentModuleClearsWholeDirtyValueAndContinuesTail() {
    Fixture f = new Fixture();
    f.missingSpecialModule = true;
    f.run();
    var expected = new ArrayList<>(ORDER);
    expected.remove("specialBp");
    assertEquals(expected, f.order);
    assertSame(RogueModifierUnstack46.BpDirty.ZERO, f.state.bpDirty);
  }

  @Test
  void removalFailurePreservesWholeEmbeddedValue() {
    Fixture f = new Fixture();
    var old = f.state.bpDirty;
    f.callbacks.put(
        "specialBp",
        () -> {
          throw new IllegalStateException();
        });
    assertThrows(
        IllegalStateException.class, () -> RogueModifierUnstack46.popSpecialBp(f.state, f.world));
    assertSame(old, f.state.bpDirty);
  }

  @Test
  void dirtyCallbackReplacementIsClearedAtNormalTail() {
    Fixture f = new Fixture();
    f.callbacks.put(
        "specialBp",
        () -> f.state.bpDirty = new RogueModifierUnstack46.BpDirty(OWNER, DEPARTED, 100));
    RogueModifierUnstack46.popSpecialBp(f.state, f.world);
    assertSame(RogueModifierUnstack46.BpDirty.ZERO, f.state.bpDirty);
  }

  @Test
  void callbackReplacementSurvivesFailureWithoutRollback() {
    Fixture f = new Fixture();
    var next = new RogueModifierUnstack46.BpDirty(OWNER, DEPARTED, 100);
    f.callbacks.put(
        "specialBp",
        () -> {
          f.state.bpDirty = next;
          throw new IllegalStateException();
        });
    assertThrows(
        IllegalStateException.class, () -> RogueModifierUnstack46.popSpecialBp(f.state, f.world));
    assertSame(next, f.state.bpDirty);
  }

  @Test
  void specialRemovalReceivesSharedModifierInsteadOfStaleEmbeddedModifier() {
    Fixture f = new Fixture();
    Object embedded = f.state.bpDirty.modifier();
    RogueModifierUnstack46.popSpecialBp(f.state, f.world);
    assertSame(f.cleanup.sequence.replacement, f.specialModifier);
    assertNotSame(embedded, f.specialModifier);
  }

  @Test
  void invalidNullValueProjectionFailsBeforeWorld() {
    Fixture f = new Fixture();
    f.state.bpDirty = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierUnstack46.popSpecialBp(f.state, null));
    assertTrue(f.order.isEmpty());
  }

  @Test
  void missingSpecialWorldKeepsDirtyValue() {
    Fixture f = new Fixture();
    var old = f.state.bpDirty;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierUnstack46.popSpecialBp(f.state, null));
    assertSame(old, f.state.bpDirty);
  }

  @Test
  void twoDifferentCleanupStatesCannotBeJoined() {
    Fixture f = new Fixture();
    var other = new RogueModifierOwnedEffects46.State(RogueModifierCleanup46Test.state());
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierUnstack46.State(f.stacked, f.registrations, other));
  }

  @Test
  void differentReplacementCannotBeJoinedEvenWithSameOwner() {
    Fixture f = new Fixture();
    var other =
        new RogueModifierStackedEffects46.State(
            new RogueModifierStackEvent46.State(RogueModifierSequences46Test.state().replacement));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierUnstack46.State(other, f.registrations, f.owned));
  }

  @Test
  void sharedReplacementWithDifferentStackedWrapperCanBeJoined() {
    Fixture f = new Fixture();
    var other =
        new RogueModifierStackedEffects46.State(
            new RogueModifierStackEvent46.State(f.cleanup.sequence.replacement));
    assertSame(other, new RogueModifierUnstack46.State(other, f.registrations, f.owned).stacked);
  }

  @Test
  void missingProjectionStatesAreRejected() {
    Fixture f = new Fixture();
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierUnstack46.State(null, f.registrations, f.owned));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierUnstack46.State(f.stacked, null, f.owned));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierUnstack46.State(f.stacked, f.registrations, null));
  }

  @Test
  void missingEntryWorldDoesNotRunPropertiesOrWithdrawals() {
    Fixture f = new Fixture();
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierUnstack46.unstack(f.state, null));
    assertTrue(f.order.isEmpty());
    assertEquals(1, f.stacked.weaknesses.size());
  }

  @Test
  void propertyCallbackCanInstallCurrentTailRegistration() {
    Fixture f = new Fixture();
    f.owned.departed = null;
    f.callbacks.put("properties", () -> f.owned.departed = DEPARTED);
    f.run();
    assertEquals(ORDER, f.order);
    assertNull(f.owned.departed);
  }

  @Test
  void propertyCallbackCanRemoveCurrentTailRegistration() {
    Fixture f = new Fixture();
    f.callbacks.put("properties", () -> f.owned.departed = null);
    f.run();
    var expected = new ArrayList<>(ORDER);
    expected.remove("departed");
    assertEquals(expected, f.order);
    assertFalse(f.recycled.contains(DEPARTED));
  }

  @Test
  void earlierCallbackCanReplaceDirtyValueUsedByLaterStage() {
    Fixture f = new Fixture();
    f.callbacks.put("boostCallback", () -> f.state.bpDirty = RogueModifierUnstack46.BpDirty.ZERO);
    f.run();
    assertFalse(f.order.contains("specialBp"));
    assertSame(RogueModifierUnstack46.BpDirty.ZERO, f.state.bpDirty);
  }

  @Test
  void repeatSuccessDoesNotRecycleOrNotifyAgainButUpperSourceIsStillVisited() {
    Fixture f = new Fixture();
    f.run();
    f.order.clear();
    f.run();
    assertEquals(List.of("properties", "upper"), f.order);
    assertEquals(3, f.recycled.size());
    assertEquals(1, f.globalEvents);
  }

  @Test
  void actualCleanupTailRunsOnlyAfterCompleteUnstack() {
    Fixture f = new Fixture();
    f.cleanup.candidate.state = 2;
    f.cleanup.sequence.replacement.executingSequenceCount = 1;
    f.cleanup.sequence.tickingSequenceCount = 1;
    f.cleanup.pendingDispose = true;
    var cleanupWorld =
        (RogueModifierCleanup46.World)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {RogueModifierCleanup46.World.class},
                (p, m, a) -> {
                  switch (m.getName()) {
                    case "unstack":
                      assertSame(f.cleanup, a[0]);
                      f.run();
                      return null;
                    case "detachAllEffects":
                      assertEquals(ORDER, f.order);
                      assertEquals(true, a[1]);
                      f.order.add("effects");
                      return null;
                    case "disposeBody":
                      assertNull(f.cleanup.effects);
                      assertNull(f.cleanup.mutexEffects);
                      assertFalse(f.cleanup.pendingDispose);
                      f.order.add("dispose");
                      return null;
                    default:
                      throw new AssertionError(m.getName());
                  }
                });
    RogueModifierCleanup46.endTurnSequence(f.cleanup, cleanupWorld);
    var expected = new ArrayList<>(ORDER);
    expected.add("effects");
    expected.add("dispose");
    assertEquals(expected, f.order);
  }

  @Test
  void actualCleanupFailureDoesNotClearEffectsOrDisposeAfterUnstackError() {
    Fixture f = new Fixture();
    f.cleanup.candidate.state = 2;
    f.cleanup.sequence.replacement.executingSequenceCount = 1;
    f.cleanup.sequence.tickingSequenceCount = 1;
    f.cleanup.pendingDispose = true;
    f.callbacks.put(
        "departed",
        () -> {
          throw new IllegalStateException();
        });
    Object effects = f.cleanup.effects;
    var cleanupWorld =
        (RogueModifierCleanup46.World)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {RogueModifierCleanup46.World.class},
                (p, m, a) -> {
                  if (m.getName().equals("unstack")) {
                    f.run();
                    return null;
                  }
                  throw new AssertionError(m.getName());
                });
    assertThrows(
        IllegalStateException.class,
        () -> RogueModifierCleanup46.endTurnSequence(f.cleanup, cleanupWorld));
    assertSame(effects, f.cleanup.effects);
    assertTrue(f.cleanup.pendingDispose);
    assertSame(UNSELECTABLE, f.owned.unselectable);
    assertEquals(1, f.globalEvents);
  }
}
