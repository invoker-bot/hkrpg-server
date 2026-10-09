package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RogueModifierDestroy46Test {
  static final Object ENTITY = new Object(),
      GAME_WORLD = new Object(),
      BATTLE = new Object(),
      MODE = new Object(),
      COMPONENT = new Object(),
      PRESHOW = new Object();

  static RogueModifierDestroy46.State state() {
    var s = new RogueModifierDestroy46.State(RogueModifierCleanup46Test.state());
    s.children = new RogueModifierDestroy46.Children();
    return s;
  }

  static RogueOwnedParameterLists46.References refs(Object... children) {
    return new RogueOwnedParameterLists46.References(children, children.length, 7);
  }

  record Call(String name, Object[] args) {}

  static class World {
    final List<Call> calls = new ArrayList<>();
    final Map<String, Consumer<Call>> hooks = new HashMap<>();
    Object entity = ENTITY,
        gameWorld = GAME_WORLD,
        battle = BATTLE,
        mode = MODE,
        component = COMPONENT;
    byte before = 0, after = 1, mute = 0, contains = 1, muteResult = 0;
    int queries, preshowType = 27;
    final RogueModifierDestroy46.World bound =
        (RogueModifierDestroy46.World)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {RogueModifierDestroy46.World.class},
                (proxy, method, arguments) -> {
                  Object[] args = arguments == null ? new Object[0] : arguments;
                  var call = new Call(method.getName(), args);
                  calls.add(call);
                  hooks.getOrDefault(call.name(), ignored -> {}).accept(call);
                  switch (call.name()) {
                    case "ownerEntity":
                      return entity;
                    case "ownerContainsBehavior":
                      assertNull(args[2]);
                      assertNull(args[3]);
                      assertEquals(false, args[4]);
                      return (int) args[1] == 2 ? (queries++ == 0 ? before : after) : muteResult;
                    case "muteModifierGlobalListen":
                      if (args.length == 1) return mute;
                      mute = (byte) args[1];
                      return null;
                    case "containsBehavior":
                      assertEquals(86, args[1]);
                      assertNull(args[2]);
                      return contains;
                    case "entityWorld":
                      return gameWorld;
                    case "worldBattleInstance":
                      return battle;
                    case "battleGameMode":
                      return mode;
                    case "destroyNotificationComponent":
                      return component;
                    case "entityType":
                      return preshowType;
                    case "onModifierDestroy":
                    case "detachAllEffects":
                    case "removeOnHitEffectOverride":
                    case "updateModifiersEffectHideByPriority":
                    case "markBehaviorFlagVisualDirty":
                    case "manuallyAddOpCountAndTriggerApply":
                    case "traceDestroyed":
                    case "notifyGlobal":
                    case "makeGroupEntityDie":
                    case "makeLevelEntityDie":
                    case "baseDestroy":
                      return null;
                    default:
                      throw new AssertionError("Unexpected destruction call: " + call.name());
                  }
                });

    List<String> names() {
      return calls.stream().map(Call::name).toList();
    }

    Call last(String name) {
      return calls.stream().filter(c -> c.name().equals(name)).reduce((a, b) -> b).orElseThrow();
    }

    void fail(String name) {
      hooks.put(
          name,
          call -> {
            throw new IllegalStateException(name);
          });
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 3, Integer.MAX_VALUE})
  void stateAboveAliveReturnsWithoutWorldOwnerOrChildRequirements(int value) {
    var s = state();
    s.cleanup.candidate.state = value;
    s.cleanup.ownerAbilityComponent = null;
    s.children = null;
    s.deathSource = 9;
    RogueModifierDestroy46.destroy(s, 4, null);
    assertEquals(9, s.deathSource);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void signedStateAtOrBelowAliveRunsCleanupWithoutAliveEvent(int value) {
    var s = state();
    var w = new World();
    s.cleanup.candidate.state = value;
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertFalse(w.names().contains("onModifierDestroy"));
    assertTrue(w.names().contains("detachAllEffects"));
    assertEquals(2, s.cleanup.candidate.state);
    assertEquals(4, s.deathSource);
  }

  @Test
  void aliveDestructionOrdersSharedCleanupOperationTraceNotificationAndBase() {
    var s = state();
    var w = new World();
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertEquals(
        List.of(
            "ownerEntity",
            "ownerContainsBehavior",
            "muteModifierGlobalListen",
            "onModifierDestroy",
            "detachAllEffects",
            "ownerContainsBehavior",
            "entityWorld",
            "worldBattleInstance",
            "battleGameMode",
            "manuallyAddOpCountAndTriggerApply",
            "traceDestroyed",
            "destroyNotificationComponent",
            "notifyGlobal",
            "baseDestroy"),
        w.names());
    assertNull(s.cleanup.effects);
    assertNull(s.cleanup.mutexEffects);
    assertEquals(0, s.cleanup.sequence.replacement.executingSequenceCount);
    assertEquals(798, w.last("manuallyAddOpCountAndTriggerApply").args()[1]);
    var notify = w.last("notifyGlobal").args();
    assertEquals(878, notify[0]);
    assertSame(s.cleanup, notify[1]);
    assertEquals(true, notify[2]);
    assertArrayEquals(new Object[] {"buff", ENTITY, 4}, w.last("traceDestroyed").args());
    assertArrayEquals(new Object[] {s, 4}, w.last("baseDestroy").args());
  }

  @Test
  void initialOwnerFailureDoesNotPublishDeathState() {
    var s = state();
    s.cleanup.ownerAbilityComponent = null;
    var w = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertEquals(1, s.cleanup.candidate.state);
    assertTrue(w.calls.isEmpty());
  }

  @Test
  void initialBehaviorFailureDoesNotPublishReasonStateOrClearEffects() {
    var s = state();
    var w = new World();
    w.fail("ownerContainsBehavior");
    assertThrows(IllegalStateException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertEquals(1, s.cleanup.candidate.state);
    assertEquals(0, s.deathSource);
    assertNotNull(s.cleanup.effects);
  }

  @Test
  void ownerIsRereadAfterStateWritesAndInitialBehaviorCallback() {
    var s = state();
    var w = new World();
    w.hooks.put(
        "ownerContainsBehavior",
        c -> {
          if (w.queries == 0) s.cleanup.ownerAbilityComponent = null;
        });
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertEquals(2, s.cleanup.candidate.state);
    assertEquals(4, s.deathSource);
    assertNotNull(s.cleanup.effects);
  }

  @Test
  void muteRefreshCapturesCurrentOwnerAfterModifierBehaviorCallback() {
    var s = state();
    var w = new World();
    w.mute = 1;
    w.muteResult = (byte) 254;
    Object replacementOwner = new Object();
    w.hooks.put("containsBehavior", c -> s.cleanup.ownerAbilityComponent = replacementOwner);
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertSame(replacementOwner, w.last("muteModifierGlobalListen").args()[0]);
    assertEquals((byte) 254, w.mute);
  }

  @Test
  void absentModifierBehaviorKeepsMuteAndSkipsOwnerBehaviorEightySix() {
    var s = state();
    var w = new World();
    w.mute = 1;
    w.contains = 0;
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertEquals(1, w.mute);
    assertEquals(2, w.calls.stream().filter(c -> c.name().equals("ownerContainsBehavior")).count());
  }

  @Test
  void eventFailureLeavesIncrementedCounterAndDestroyStateWithoutCleanup() {
    var s = state();
    var w = new World();
    w.fail("onModifierDestroy");
    assertThrows(IllegalStateException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertEquals(1, s.cleanup.sequence.replacement.executingSequenceCount);
    assertEquals(2, s.cleanup.candidate.state);
    assertNotNull(s.cleanup.effects);
    RogueModifierDestroy46.destroy(s, 8, null);
    assertEquals(4, s.deathSource);
  }

  @Test
  void aliveEventCounterWrapsThenDecrementsCurrentCallbackValue() {
    var s = state();
    var w = new World();
    s.cleanup.sequence.replacement.executingSequenceCount = Integer.MAX_VALUE;
    w.hooks.put(
        "onModifierDestroy",
        c -> {
          assertEquals(Integer.MIN_VALUE, s.cleanup.sequence.replacement.executingSequenceCount);
          s.cleanup.sequence.replacement.executingSequenceCount = 41;
        });
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertEquals(40, s.cleanup.sequence.replacement.executingSequenceCount);
  }

  @Test
  void previousStateCapturedBeforeBehaviorCallbackStillControlsEvent() {
    var s = state();
    var w = new World();
    w.hooks.put(
        "ownerContainsBehavior",
        c -> {
          if (w.queries == 0) s.cleanup.candidate.state = 0;
        });
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertTrue(w.names().contains("onModifierDestroy"));
  }

  @Test
  void actualCleanupFailurePreservesEffectsAndStopsPostBehavior() {
    var s = state();
    var w = new World();
    w.fail("detachAllEffects");
    assertThrows(IllegalStateException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertNotNull(s.cleanup.effects);
    assertEquals(1, w.queries);
    assertFalse(w.names().contains("baseDestroy"));
  }

  @Test
  void actualCleanupMissingConfigFailsAfterClearingEffects() {
    var s = state();
    var w = new World();
    s.cleanup.config = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertNull(s.cleanup.effects);
    assertNull(s.cleanup.mutexEffects);
    assertEquals(1, w.queries);
  }

  @Test
  void sharedCleanupRemovesOverrideAndRefreshesPriorityThenBehaviorBeforePostQuery() {
    var s = state();
    var w = new World();
    s.cleanup.hitEffectOverride = 3;
    s.cleanup.config.effectPriority = "p";
    s.cleanup.config.behaviorFlags = COMPONENT;
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    var n = w.names();
    assertTrue(n.indexOf("detachAllEffects") < n.indexOf("removeOnHitEffectOverride"));
    assertTrue(
        n.indexOf("removeOnHitEffectOverride") < n.indexOf("updateModifiersEffectHideByPriority"));
    assertTrue(
        n.indexOf("updateModifiersEffectHideByPriority")
            < n.indexOf("markBehaviorFlagVisualDirty"));
    assertTrue(n.indexOf("markBehaviorFlagVisualDirty") < n.lastIndexOf("ownerContainsBehavior"));
    assertEquals(-1, s.cleanup.hitEffectOverride);
  }

  @ParameterizedTest
  @CsvSource({
    "0,1,true",
    "1,0,true",
    "2,3,true",
    "3,2,true",
    "0,2,false",
    "2,0,false",
    "-1,-2,true",
    "-1,0,false"
  })
  void operationIsTriggeredOnlyWhenRawByteXorEqualsOne(int before, int after, boolean expected) {
    var s = state();
    var w = new World();
    w.before = (byte) before;
    w.after = (byte) after;
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertEquals(expected, w.names().contains("manuallyAddOpCountAndTriggerApply"));
  }

  @Test
  void optionalModeChainSkipsOperationButStillTracesNotifiesAndCallsBase() {
    for (int missing = 0; missing < 3; missing++) {
      var s = state();
      var w = new World();
      if (missing == 0) w.gameWorld = null;
      if (missing == 1) w.battle = null;
      if (missing == 2) w.mode = null;
      RogueModifierDestroy46.destroy(s, 4, w.bound);
      assertFalse(w.names().contains("manuallyAddOpCountAndTriggerApply"));
      assertTrue(w.names().contains("baseDestroy"));
    }
  }

  @Test
  void initialEntityRemainsCapturedAfterOwnerReplacementAndNameIsRereadForTrace() {
    var s = state();
    var w = new World();
    Object changedOwner = new Object();
    w.hooks.put(
        "onModifierDestroy",
        c -> {
          s.cleanup.ownerAbilityComponent = changedOwner;
          s.cleanup.candidate.name = "new";
        });
    w.hooks.put(
        "traceDestroyed",
        c -> {
          assertArrayEquals(new Object[] {"new", ENTITY, 4}, c.args());
        });
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertSame(ENTITY, w.last("destroyNotificationComponent").args()[0]);
  }

  @Test
  void nullCapturedEntityIsTracedBeforeRequiredEntityFailure() {
    var s = state();
    var w = new World();
    w.entity = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertEquals("traceDestroyed", w.names().getLast());
    assertNull(w.last("traceDestroyed").args()[1]);
    assertNull(s.cleanup.effects);
  }

  @Test
  void absentNotificationComponentSkipsGlobalNotification() {
    var s = state();
    var w = new World();
    w.component = null;
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertFalse(w.names().contains("notifyGlobal"));
    assertTrue(w.names().contains("baseDestroy"));
  }

  @Test
  void notificationFailureStopsPreshowChildrenAndBaseAfterCleanup() {
    var s = state();
    var w = new World();
    s.preshowEntity = PRESHOW;
    w.fail("notifyGlobal");
    assertThrows(IllegalStateException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertNull(s.cleanup.effects);
    assertFalse(w.names().contains("entityType"));
    assertFalse(w.names().contains("baseDestroy"));
  }

  @ParameterizedTest
  @ValueSource(ints = {27, 0, -1, Integer.MAX_VALUE})
  void preshowEntityUsesCapturedOwnerWorldAndExactTypeTwentySeven(int type) {
    var s = state();
    var w = new World();
    s.preshowEntity = PRESHOW;
    w.preshowType = type;
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    String name = type == 27 ? "makeGroupEntityDie" : "makeLevelEntityDie";
    var args = w.last(name).args();
    assertSame(GAME_WORLD, args[0]);
    assertSame(PRESHOW, args[1]);
    if (type != 27) assertNull(args[2]);
    assertSame(ENTITY, w.last("entityWorld").args()[0]);
    assertSame(PRESHOW, w.last("entityType").args()[0]);
    assertSame(PRESHOW, s.preshowEntity);
  }

  @Test
  void preshowWorldMissingFailsAfterNotificationWithoutClearingPreshow() {
    var s = state();
    var w = new World();
    s.preshowEntity = PRESHOW;
    w.gameWorld = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertTrue(w.names().contains("notifyGlobal"));
    assertSame(PRESHOW, s.preshowEntity);
    assertFalse(w.names().contains("entityType"));
  }

  @Test
  void childHolderIsRequiredEvenWithoutChildrenAfterPriorSideEffects() {
    var s = state();
    var w = new World();
    s.children = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertTrue(w.names().contains("notifyGlobal"));
    assertFalse(w.names().contains("baseDestroy"));
  }

  @Test
  void emptyOrNegativeChildListsSkipStorageAndVersionMutation() {
    for (int count : new int[] {0, -1, Integer.MIN_VALUE}) {
      var s = state();
      var w = new World();
      var l = new RogueOwnedParameterLists46.References(null, count, 7);
      s.children.entries = l;
      RogueModifierDestroy46.destroy(s, 4, w.bound);
      assertEquals(count, l.size);
      assertEquals(7, l.version);
      assertTrue(w.names().contains("baseDestroy"));
    }
  }

  @Test
  void recursiveChildrenUseCurrentParentReasonWhileBaseKeepsOriginalReason() {
    var s = state();
    var first = state();
    var second = state();
    var w = new World();
    s.children.entries = refs(first, second);
    w.hooks.put(
        "baseDestroy",
        c -> {
          if (c.args()[0] == first) s.deathSource = 9;
        });
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertEquals(4, first.deathSource);
    assertEquals(9, second.deathSource);
    assertEquals(4, w.last("baseDestroy").args()[1]);
    assertSame(s, w.last("baseDestroy").args()[0]);
    assertEquals(0, s.children.entries.size);
    assertEquals(8, s.children.entries.version);
    assertArrayEquals(new Object[] {null, null}, s.children.entries.entries);
  }

  @Test
  void duplicateChildReferenceReturnsOnSecondDestructionThenClearsParentList() {
    var s = state();
    var child = state();
    var w = new World();
    s.children.entries = refs(child, child);
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertEquals(2, w.calls.stream().filter(c -> c.name().equals("onModifierDestroy")).count());
    assertEquals(0, s.children.entries.size);
  }

  @Test
  void childFailureKeepsCurrentListHeadersAndAlreadyDestroyedPrefix() {
    var s = state();
    var child = state();
    var w = new World();
    s.children.entries = refs(child, null);
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertEquals(2, child.cleanup.candidate.state);
    assertEquals(2, s.children.entries.size);
    assertEquals(7, s.children.entries.version);
    assertSame(child, s.children.entries.entries[0]);
    assertFalse(w.calls.stream().anyMatch(c -> c.name().equals("baseDestroy") && c.args()[0] == s));
  }

  @Test
  void childCallbackReplacingHolderIsObservedBeforeNextChildAndFinalClear() {
    var s = state();
    var child = state();
    var w = new World();
    var original = refs(child);
    s.children.entries = original;
    var replacement = new RogueModifierDestroy46.Children();
    replacement.entries = refs(child);
    w.hooks.put(
        "baseDestroy",
        c -> {
          if (c.args()[0] == child) s.children = replacement;
        });
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertEquals(1, original.size);
    assertEquals(7, original.version);
    assertSame(child, original.entries[0]);
    assertEquals(0, replacement.entries.size);
    assertEquals(8, replacement.entries.version);
  }

  @Test
  void childCallbackDroppingHolderStopsBeforeBase() {
    var s = state();
    var child = state();
    var w = new World();
    var l = refs(child);
    s.children.entries = l;
    w.hooks.put(
        "baseDestroy",
        c -> {
          if (c.args()[0] == child) s.children = null;
        });
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertEquals(1, l.size);
    assertEquals(7, l.version);
  }

  @Test
  void finalClearPublishesVersionAndZeroSizeBeforeMissingStorageFailure() {
    var s = state();
    var child = state();
    var w = new World();
    var l = refs(child);
    s.children.entries = l;
    w.hooks.put(
        "baseDestroy",
        c -> {
          if (c.args()[0] == child) l.entries = null;
        });
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertEquals(0, l.size);
    assertEquals(8, l.version);
    assertFalse(w.calls.stream().anyMatch(c -> c.name().equals("baseDestroy") && c.args()[0] == s));
  }

  @Test
  void exactFiniteChildLimitClearsAllSlotsAndWrapsVersion() {
    var s = state();
    var w = new World();
    Object[] entries = new Object[255];
    for (int i = 0; i < entries.length; i++) {
      var c = state();
      c.cleanup.candidate.state = 2;
      entries[i] = c;
    }
    s.children.entries = new RogueOwnedParameterLists46.References(entries, 255, Integer.MAX_VALUE);
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertEquals(Integer.MIN_VALUE, s.children.entries.version);
    assertEquals(0, s.children.entries.size);
    for (var child : entries) assertNull(child);
  }

  @Test
  void excessiveFiniteChildCountFailsAfterPriorDestructionSideEffects() {
    var s = state();
    var w = new World();
    s.children.entries = new RogueOwnedParameterLists46.References(new Object[256], 256, 7);
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertEquals(2, s.cleanup.candidate.state);
    assertEquals(256, s.children.entries.size);
    assertFalse(w.names().contains("baseDestroy"));
  }

  @Test
  void exhaustedCostCallsActualDestructionUsingSameCleanupAndCandidate() {
    var s = state();
    var w = new World();
    var stack = new RogueModifierStackEvent46.State(s.cleanup.sequence.replacement);
    stack.stackedAdditionalTeamBp =
        new ArrayList<>(List.of(new RogueModifierStackedEffects46.AdditionalBp(null, 7)));
    var cost = new RogueAdditionalBpCost46.State(s.cleanup, stack);
    RogueAdditionalBpCost46.cost(
        cost,
        7,
        (cleanup, reason) -> {
          assertSame(s.cleanup, cleanup);
          RogueModifierDestroy46.destroy(s, reason, w.bound);
        });
    assertTrue(stack.stackedAdditionalTeamBp.isEmpty());
    assertEquals(4, s.deathSource);
    assertEquals(2, s.cleanup.candidate.state);
    assertNull(s.cleanup.effects);
    assertTrue(w.names().contains("baseDestroy"));
  }

  @Test
  void costDestructionEventFailureKeepsConsumedRecordsAndIncrementedLifecycleCounter() {
    var s = state();
    var w = new World();
    w.fail("onModifierDestroy");
    var stack = new RogueModifierStackEvent46.State(s.cleanup.sequence.replacement);
    stack.stackedAdditionalTeamBp =
        new ArrayList<>(List.of(new RogueModifierStackedEffects46.AdditionalBp(null, 7)));
    var cost = new RogueAdditionalBpCost46.State(s.cleanup, stack);
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueAdditionalBpCost46.cost(
                cost, 7, (cleanup, reason) -> RogueModifierDestroy46.destroy(s, reason, w.bound)));
    assertTrue(stack.stackedAdditionalTeamBp.isEmpty());
    assertEquals(1, s.cleanup.sequence.replacement.executingSequenceCount);
    assertNotNull(s.cleanup.effects);
    int prior = w.calls.size();
    RogueAdditionalBpCost46.cost(cost, 7, null);
    assertEquals(prior, w.calls.size());
  }

  @Test
  void exactFiniteDepthCompletesButNextDepthFailsBeforeChildPublication() {
    for (int length : new int[] {255, 256}) {
      var states = new RogueModifierDestroy46.State[length];
      for (int i = 0; i < length; i++) states[i] = state();
      for (int i = 0; i < length - 1; i++) states[i].children.entries = refs(states[i + 1]);
      var w = new World();
      if (length == 255) {
        RogueModifierDestroy46.destroy(states[0], 4, w.bound);
        assertEquals(2, states[length - 1].cleanup.candidate.state);
        assertSame(states[0], w.last("baseDestroy").args()[0]);
      } else {
        assertThrows(
            IllegalArgumentException.class,
            () -> RogueModifierDestroy46.destroy(states[0], 4, w.bound));
        assertEquals(2, states[254].cleanup.candidate.state);
        assertEquals(1, states[255].cleanup.candidate.state);
        assertEquals(1, states[0].children.entries.size);
        assertFalse(w.names().contains("baseDestroy"));
      }
    }
  }

  @Test
  void childCycleStopsAtSharedDestructionStateThenClearsBothLists() {
    var s = state();
    var child = state();
    var w = new World();
    s.children.entries = refs(child);
    child.children.entries = refs(s);
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertEquals(0, s.children.entries.size);
    assertEquals(0, child.children.entries.size);
    assertEquals(2, w.calls.stream().filter(c -> c.name().equals("onModifierDestroy")).count());
  }

  @Test
  void callbackNegativeCurrentCountStillClearsHeaderAfterEnteredPositiveLoop() {
    var s = state();
    var child = state();
    var w = new World();
    var list = refs(child);
    s.children.entries = list;
    w.hooks.put(
        "baseDestroy",
        c -> {
          if (c.args()[0] == child) list.size = -1;
        });
    RogueModifierDestroy46.destroy(s, 4, w.bound);
    assertEquals(0, list.size);
    assertEquals(8, list.version);
    assertSame(child, list.entries[0]);
  }

  @Test
  void callbackNullCurrentChildListStopsWithoutClearingOriginalStorage() {
    var s = state();
    var child = state();
    var w = new World();
    var original = refs(child);
    s.children.entries = original;
    w.hooks.put(
        "baseDestroy",
        c -> {
          if (c.args()[0] == child) s.children.entries = null;
        });
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertEquals(1, original.size);
    assertEquals(7, original.version);
    assertSame(child, original.entries[0]);
  }

  @Test
  void baseFailureKeepsClearedChildListAndPublishedDestroyState() {
    var s = state();
    var child = state();
    var w = new World();
    s.children.entries = refs(child);
    w.hooks.put(
        "baseDestroy",
        c -> {
          if (c.args()[0] == s) throw new IllegalStateException("root base");
        });
    assertThrows(IllegalStateException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertEquals(0, s.children.entries.size);
    assertEquals(8, s.children.entries.version);
    assertEquals(2, s.cleanup.candidate.state);
    assertNull(s.cleanup.effects);
  }

  @Test
  void actualSpecialPointRefreshConsumesAndDestroysSharedModifier() {
    var module = RogueSpecialBpPoints46Test.state(1);
    var s = state();
    var w = new World();
    module.points.head.modifier = s.cleanup;
    module.points.head.registrationId = 7;
    var stack = new RogueModifierStackEvent46.State(s.cleanup.sequence.replacement);
    stack.stackedAdditionalTeamBp =
        new ArrayList<>(List.of(new RogueModifierStackedEffects46.AdditionalBp(null, 7)));
    var cost = new RogueAdditionalBpCost46.State(s.cleanup, stack);
    RogueSpecialBpPoints46.refresh(
        module,
        (cleanup, id) -> {
          assertSame(s.cleanup, cleanup);
          assertEquals(0, module.points.head.special);
          assertNull(module.points.head.modifier);
          RogueAdditionalBpCost46.cost(
              cost, id, (same, reason) -> RogueModifierDestroy46.destroy(s, reason, w.bound));
        });
    assertTrue(stack.stackedAdditionalTeamBp.isEmpty());
    assertEquals(2, s.cleanup.candidate.state);
    assertEquals(4, s.deathSource);
    assertTrue(w.names().contains("baseDestroy"));
    assertEquals(1, module.points.size);
  }

  @Test
  void postCleanupBehaviorFailureKeepsEffectsClearedAndStopsTraceAndChildren() {
    var s = state();
    var w = new World();
    w.hooks.put(
        "ownerContainsBehavior",
        c -> {
          if (w.queries > 0) throw new IllegalStateException("after");
        });
    assertThrows(IllegalStateException.class, () -> RogueModifierDestroy46.destroy(s, 4, w.bound));
    assertNull(s.cleanup.effects);
    assertEquals(0, s.cleanup.sequence.replacement.executingSequenceCount);
    assertFalse(w.names().contains("traceDestroyed"));
    assertFalse(w.names().contains("baseDestroy"));
  }

  @Test
  void nullStateRejectedWithoutWorld() {
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierDestroy46.destroy(null, 4, null));
  }
}
