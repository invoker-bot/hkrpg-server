package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueModifierOwnedEffects46Test {
  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void componentReceivesNullReplacementReferenceRatherThanBoxedFalse(int kind) {
    var s = state();
    parameter(s, kind, PARAM);
    List<String> received = new ArrayList<>();
    var binding =
        (RogueModifierOwnedEffects46.World)
            Proxy.newProxyInstance(
                RogueModifierOwnedEffects46.World.class.getClassLoader(),
                new Class<?>[] {RogueModifierOwnedEffects46.World.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "ownerEntity" -> ENTITY;
                      case "entityWorld" -> GAME_WORLD;
                      case "worldBattleInstance" -> BATTLE;
                      case "battleGameMode" -> GAME_MODE;
                      case "refreshDeparted", "refreshUnselectable" -> {
                        assertSame(PARAM, args[1]);
                        assertNull(args[2], "The third native argument is a replacement reference");
                        received.add(method.getName());
                        yield null;
                      }
                      case "recycle" -> {
                        assertSame(PARAM, args[0]);
                        received.add("recycle");
                        yield null;
                      }
                      default ->
                          throw new AssertionError("Unexpected binding: " + method.getName());
                    });
    if (kind == 1) RogueModifierOwnedEffects46.detachDeparted(s, binding);
    else RogueModifierOwnedEffects46.detachUnselectable(s, binding);
    assertEquals(
        List.of(kind == 1 ? "refreshDeparted" : "refreshUnselectable", "recycle"), received);
  }

  static final Object PARAM = new Object(),
      NEXT = new Object(),
      ENTITY = new Object(),
      GAME_WORLD = new Object(),
      BATTLE = new Object(),
      GAME_MODE = new Object();

  static RogueModifierOwnedEffects46.State state() {
    return new RogueModifierOwnedEffects46.State(RogueModifierCleanup46Test.state());
  }

  static Object parameter(RogueModifierOwnedEffects46.State s, int kind) {
    return switch (kind) {
      case 0 -> s.characterChange;
      case 1 -> s.departed;
      default -> s.unselectable;
    };
  }

  static void parameter(RogueModifierOwnedEffects46.State s, int kind, Object value) {
    switch (kind) {
      case 0 -> s.characterChange = value;
      case 1 -> s.departed = value;
      default -> s.unselectable = value;
    }
  }

  static void detach(RogueModifierOwnedEffects46.State s, int kind, World world) {
    switch (kind) {
      case 0 -> RogueModifierOwnedEffects46.detachCharacterChange(s, true, world);
      case 1 -> RogueModifierOwnedEffects46.detachDeparted(s, world);
      default -> RogueModifierOwnedEffects46.detachUnselectable(s, world);
    }
  }

  static class World implements RogueModifierOwnedEffects46.World {
    final RogueModifierOwnedEffects46.State state;
    final List<String> order = new ArrayList<>();
    Runnable onRemove = () -> {}, onRecycle = () -> {}, onNotify = () -> {}, onQuery = () -> {};
    int missing = -1;
    boolean immediate;

    World(RogueModifierOwnedEffects46.State state) {
      this.state = state;
    }

    public void detachCharacterChange(Object owner, Object parameter, boolean flag) {
      assertSame(RogueModifierCleanup46Test.OWNER, owner);
      assertSame(PARAM, parameter);
      assertNull(state.characterChange);
      immediate = flag;
      order.add("character");
      onRemove.run();
    }

    public void refreshDeparted(Object owner, Object parameter, Object replacement) {
      assertSame(RogueModifierCleanup46Test.OWNER, owner);
      assertSame(PARAM, parameter);
      assertNull(state.departed);
      assertNull(replacement);
      order.add("departed");
      onRemove.run();
    }

    public Object ownerEntity(Object owner) {
      assertSame(RogueModifierCleanup46Test.OWNER, owner);
      assertNull(state.unselectable);
      order.add("entity");
      onQuery.run();
      return missing == 0 ? null : ENTITY;
    }

    public Object entityWorld(Object entity) {
      assertSame(ENTITY, entity);
      order.add("world");
      return missing == 1 ? null : GAME_WORLD;
    }

    public Object worldBattleInstance(Object world) {
      assertSame(GAME_WORLD, world);
      order.add("battle");
      return missing == 2 ? null : BATTLE;
    }

    public Object battleGameMode(Object battle) {
      assertSame(BATTLE, battle);
      order.add("mode");
      return missing == 3 ? null : GAME_MODE;
    }

    public void refreshUnselectable(Object mode, Object parameter, Object replacement) {
      assertSame(GAME_MODE, mode);
      assertSame(PARAM, parameter);
      assertNull(replacement);
      order.add("unselectable");
      onRemove.run();
    }

    public void recycle(Object parameter) {
      assertSame(PARAM, parameter);
      order.add("recycle");
      onRecycle.run();
    }

    public void notifyGlobal(int event, Object payload, boolean flag) {
      assertEquals(990, event);
      assertNull(payload);
      assertTrue(flag);
      order.add("notify");
      onNotify.run();
    }
  }

  static RuntimeException fault() {
    return new IllegalStateException("bound operation failed");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void absentParameterNeedsNeitherOwnerNorWorld(int kind) {
    var s = state();
    s.cleanup.ownerAbilityComponent = null;
    detach(s, kind, null);
    assertNull(parameter(s, kind));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void missingOwnerClearsCapturedParameterBeforeFailure(int kind) {
    var s = state();
    parameter(s, kind, PARAM);
    s.cleanup.ownerAbilityComponent = null;
    var w = new World(s);
    assertThrows(IllegalArgumentException.class, () -> detach(s, kind, w));
    assertNull(parameter(s, kind));
    assertTrue(w.order.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void missingWorldKeepsTheInitialReferenceClear(int kind) {
    var s = state();
    parameter(s, kind, PARAM);
    assertThrows(IllegalArgumentException.class, () -> detach(s, kind, null));
    assertNull(parameter(s, kind));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void characterRemovalForwardsCallerFlagBeforeRecycleAndNotification(boolean flag) {
    var s = state();
    s.characterChange = PARAM;
    var w = new World(s);
    RogueModifierOwnedEffects46.detachCharacterChange(s, flag, w);
    assertEquals(flag, w.immediate);
    assertEquals(List.of("character", "recycle", "notify"), w.order);
    assertNull(s.characterChange);
  }

  @Test
  void departedRemovalUsesNullReplacementAndDoesNotNotify() {
    var s = state();
    s.departed = PARAM;
    var w = new World(s);
    detach(s, 1, w);
    assertEquals(List.of("departed", "recycle"), w.order);
  }

  @Test
  void unselectableReadsEntityWorldBattleAndModeBeforeRemovalAndRecycle() {
    var s = state();
    s.unselectable = PARAM;
    var w = new World(s);
    detach(s, 2, w);
    assertEquals(List.of("entity", "world", "battle", "mode", "unselectable", "recycle"), w.order);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void missingWorldChainStopsAtTheFirstRequiredReference(int missing) {
    var s = state();
    s.unselectable = PARAM;
    var w = new World(s);
    w.missing = missing;
    assertThrows(IllegalArgumentException.class, () -> detach(s, 2, w));
    assertNull(s.unselectable);
    assertEquals(List.of("entity", "world", "battle", "mode").subList(0, missing + 1), w.order);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void componentRemovalFailureStopsBeforeRecycleWithNoReferenceRollback(int kind) {
    var s = state();
    parameter(s, kind, PARAM);
    var w = new World(s);
    var failure = fault();
    w.onRemove =
        () -> {
          throw failure;
        };
    assertSame(failure, assertThrows(RuntimeException.class, () -> detach(s, kind, w)));
    assertNull(parameter(s, kind));
    assertFalse(w.order.contains("recycle"));
    assertFalse(w.order.contains("notify"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void recycleFailurePreservesRemovalAndStopsNotification(int kind) {
    var s = state();
    parameter(s, kind, PARAM);
    var w = new World(s);
    var failure = fault();
    w.onRecycle =
        () -> {
          throw failure;
        };
    assertSame(failure, assertThrows(RuntimeException.class, () -> detach(s, kind, w)));
    assertNull(parameter(s, kind));
    assertEquals("recycle", w.order.getLast());
    assertFalse(w.order.contains("notify"));
  }

  @Test
  void notificationFailureDoesNotReinstateAlreadyRecycledCharacterParameter() {
    var s = state();
    s.characterChange = PARAM;
    var w = new World(s);
    var failure = fault();
    w.onNotify =
        () -> {
          throw failure;
        };
    assertSame(failure, assertThrows(RuntimeException.class, () -> detach(s, 0, w)));
    assertNull(s.characterChange);
    assertEquals(List.of("character", "recycle", "notify"), w.order);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void retryAfterFailureDoesNotRepeatRemovalOrRecycle(int kind) {
    var s = state();
    parameter(s, kind, PARAM);
    var w = new World(s);
    w.onRecycle =
        () -> {
          throw fault();
        };
    assertThrows(RuntimeException.class, () -> detach(s, kind, w));
    var prefix = List.copyOf(w.order);
    s.cleanup.ownerAbilityComponent = null;
    detach(s, kind, null);
    assertEquals(prefix, w.order);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void referenceInstalledDuringRemovalSurvivesAndOnlyOldParameterIsRecycled(int kind) {
    var s = state();
    parameter(s, kind, PARAM);
    var w = new World(s);
    w.onRemove = () -> parameter(s, kind, NEXT);
    detach(s, kind, w);
    assertSame(NEXT, parameter(s, kind));
    assertTrue(w.order.contains("recycle"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void referenceInstalledDuringRecycleSurvives(int kind) {
    var s = state();
    parameter(s, kind, PARAM);
    var w = new World(s);
    w.onRecycle = () -> parameter(s, kind, NEXT);
    detach(s, kind, w);
    assertSame(NEXT, parameter(s, kind));
  }

  @Test
  void characterNotificationReplacementSurvives() {
    var s = state();
    s.characterChange = PARAM;
    var w = new World(s);
    w.onNotify = () -> s.characterChange = NEXT;
    detach(s, 0, w);
    assertSame(NEXT, s.characterChange);
  }

  @Test
  void worldQueryReplacementSurvivesTheRemainingUnselectableWithdrawal() {
    var s = state();
    s.unselectable = PARAM;
    var w = new World(s);
    w.onQuery = () -> s.unselectable = NEXT;
    detach(s, 2, w);
    assertSame(NEXT, s.unselectable);
  }

  @Test
  void ownerChangedByQueryIsNotUsedAsAnEntityOrWorld() {
    var s = state();
    s.unselectable = PARAM;
    var w = new World(s);
    w.onQuery = () -> s.cleanup.ownerAbilityComponent = NEXT;
    detach(s, 2, w);
    assertSame(NEXT, s.cleanup.ownerAbilityComponent);
    assertEquals(List.of("entity", "world", "battle", "mode", "unselectable", "recycle"), w.order);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void siblingParametersAndCleanupFlagsAreNotModifiedByOneWithdrawal(int kind) {
    var s = state();
    s.characterChange = PARAM;
    s.departed = PARAM;
    s.unselectable = PARAM;
    s.cleanup.pendingDispose = true;
    var w = new World(s);
    detach(s, kind, w);
    for (int other = 0; other < 3; other++)
      if (other != kind) assertSame(PARAM, parameter(s, other));
    assertTrue(s.cleanup.pendingDispose);
    assertSame(RogueModifierCleanup46Test.EFFECTS, s.cleanup.effects);
  }

  @Test
  void actualTerminalTaskSequenceWithdrawsOwnedEffectsBeforeGenericCleanupAndDispose() {
    var s = state();
    s.characterChange = PARAM;
    s.departed = PARAM;
    s.unselectable = PARAM;
    s.cleanup.candidate.state = 2;
    s.cleanup.pendingDispose = true;
    var effectsWorld = new World(s);
    var cleanupWorld = new RogueModifierCleanup46Test.World();
    cleanupWorld.linked = s.cleanup;
    cleanupWorld.onUnstack =
        () -> {
          detach(s, 0, effectsWorld);
          detach(s, 1, effectsWorld);
          detach(s, 2, effectsWorld);
        };
    cleanupWorld.onDetach = () -> assertEquals("recycle", effectsWorld.order.getLast());
    RogueModifierSequences46.start(
        s.cleanup.sequence, new RogueTaskSequence46.Sequence(List.of(), false), cleanupWorld);
    assertEquals(List.of("end", "unstack", "detach", "dispose"), cleanupWorld.order);
    assertEquals(
        List.of(
            "character",
            "recycle",
            "notify",
            "departed",
            "recycle",
            "entity",
            "world",
            "battle",
            "mode",
            "unselectable",
            "recycle"),
        effectsWorld.order);
    assertEquals(0, s.cleanup.sequence.replacement.executingSequenceCount);
    assertEquals(0, s.cleanup.sequence.tickingSequenceCount);
    assertNull(s.cleanup.effects);
    assertFalse(s.cleanup.pendingDispose);
  }

  @Test
  void ownedWithdrawalFailurePreventsGenericCleanupAndPendingDispose() {
    var s = state();
    s.characterChange = PARAM;
    s.departed = PARAM;
    s.cleanup.candidate.state = 2;
    s.cleanup.pendingDispose = true;
    var effectsWorld = new World(s);
    effectsWorld.onRecycle =
        () -> {
          throw fault();
        };
    var cleanupWorld = new RogueModifierCleanup46Test.World();
    cleanupWorld.linked = s.cleanup;
    cleanupWorld.onUnstack =
        () -> {
          detach(s, 0, effectsWorld);
          detach(s, 1, effectsWorld);
        };
    assertThrows(
        RuntimeException.class,
        () ->
            RogueModifierSequences46.start(
                s.cleanup.sequence,
                new RogueTaskSequence46.Sequence(List.of(), false),
                cleanupWorld));
    assertEquals(List.of("end", "unstack"), cleanupWorld.order);
    assertNull(s.characterChange);
    assertSame(PARAM, s.departed);
    assertSame(RogueModifierCleanup46Test.EFFECTS, s.cleanup.effects);
    assertTrue(s.cleanup.pendingDispose);
    assertEquals(0, s.cleanup.sequence.replacement.executingSequenceCount);
  }

  @Test
  void stateRequiresExplicitSharedCleanupAndEachEntryRequiresState() {
    assertThrows(IllegalArgumentException.class, () -> new RogueModifierOwnedEffects46.State(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierOwnedEffects46.detachCharacterChange(null, true, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierOwnedEffects46.detachDeparted(null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierOwnedEffects46.detachUnselectable(null, null));
    var s = state();
    assertSame(s.cleanup.ownerAbilityComponent, RogueModifierCleanup46Test.OWNER);
  }
}
