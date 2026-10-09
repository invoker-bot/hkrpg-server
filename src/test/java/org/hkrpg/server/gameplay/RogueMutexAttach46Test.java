package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.hkrpg.server.gameplay.RogueMutexAttach46.*;
import org.hkrpg.server.gameplay.RogueOwnedParameterLists46.References;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueMutexAttach46Test {
  record Call(String name, Object[] args) {}

  static class World {
    List<Call> calls = new ArrayList<>();
    Map<String, Consumer<Call>> hooks = new HashMap<>();
    Object entity = new Object(), gameWorld = new Object(), module = new Object();
    int globalResult, entityResult;
    boolean nullClosure, nullCallback;
    Closure lastClosure;
    CompletionCallback lastCallback;
    Binding binding = new Binding(new Object(), 0xe856200, 0x43b840);
    final RogueMutexAttach46.World bound =
        (RogueMutexAttach46.World)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {RogueMutexAttach46.World.class},
                (proxy, method, args) -> {
                  Object[] actual = args == null ? new Object[0] : args;
                  Call call = new Call(method.getName(), actual);
                  calls.add(call);
                  hooks.getOrDefault(call.name(), ignored -> {}).accept(call);
                  return switch (call.name()) {
                    case "allocateClosure" -> lastClosure = nullClosure ? null : new Closure();
                    case "equalPath" -> actual[0].equals(actual[1]);
                    case "ownerEntity" -> entity;
                    case "entityWorld" -> gameWorld;
                    case "worldEffectMutexModule" -> module;
                    case "globalMutex" -> globalResult;
                    case "entityMutex" -> entityResult;
                    case "allocateCompletionCallback" ->
                        lastCallback = nullCallback ? null : new CompletionCallback();
                    case "completionCallbackBinding" -> binding;
                    case "setCapacity" -> {
                      RogueFadeListCapacity46.setCapacity(
                          (References) actual[0],
                          (int) actual[1],
                          new RogueFadeListCapacity46.World() {
                            public Object[] allocate(int capacity) {
                              Call nested = new Call("allocate-array", new Object[] {capacity});
                              calls.add(nested);
                              hooks.getOrDefault(nested.name(), ignored -> {}).accept(nested);
                              return new Object[capacity];
                            }

                            public Object[] emptyArray() {
                              return new Object[0];
                            }

                            public void copy(Object[] a, int from, Object[] c, int to, int count) {
                              System.arraycopy(a, from, c, to, count);
                            }
                          });
                      yield null;
                    }
                    case "copy" -> {
                      System.arraycopy(
                          actual[0], (int) actual[1], actual[2], (int) actual[3], (int) actual[4]);
                      yield null;
                    }
                    default -> null;
                  };
                });

    List<String> names() {
      return calls.stream().map(Call::name).toList();
    }

    Call named(String name) {
      return calls.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }
  }

  static References refs(Object... values) {
    return new References(Arrays.copyOf(values, values.length + 2), values.length, 4);
  }

  static Parameter parameter(String path) {
    var p = new Parameter();
    p.effectPath = path;
    p.maxMutexCount = 7;
    p.minMutexTime = Float.intBitsToFloat(0x3f800001);
    return p;
  }

  static Effect effect(String path) {
    var e = new Effect();
    e.effectPath = path;
    return e;
  }

  static RogueModifierDestroy46.State state(Object... params) {
    var s = RogueModifierDestroy46Test.state();
    s.cleanup.mutexEffects = refs(params);
    s.cleanup.effects = refs();
    return s;
  }

  static References pending(RogueModifierDestroy46.State self) {
    return (References) self.cleanup.mutexEffects;
  }

  static References active(RogueModifierDestroy46.State self) {
    return (References) self.cleanup.effects;
  }

  static MonoEffect alive(Effect e) {
    var m = new MonoEffect();
    m.cachedPointer = 1;
    m.instanceId = 42;
    m.behavior = new Object();
    e.instance = m;
    e.instanceId = 42;
    return m;
  }

  @Test
  void nullAndEmptyListsReturnBeforeWorld() {
    var s = state(parameter("p"));
    s.cleanup.mutexEffects = null;
    RogueMutexAttach46.tryAttach(s, refs(effect("p")), true, null);
    s = state(parameter("p"));
    RogueMutexAttach46.tryAttach(s, null, true, null);
    RogueMutexAttach46.tryAttach(s, refs(), true, null);
    RogueMutexAttach46.tryAttach(state(), refs(effect("p")), true, null);
  }

  @Test
  void negativePendingOrIncomingCountDoesNotRepairState() {
    var s = state(parameter("p"));
    pending(s).size = -1;
    RogueMutexAttach46.tryAttach(s, refs(effect("p")), true, null);
    assertEquals(-1, pending(s).size);
    pending(s).size = 1;
    var source = refs(effect("p"));
    source.size = -1;
    RogueMutexAttach46.tryAttach(s, source, true, null);
    assertEquals(1, pending(s).size);
  }

  @Test
  void destroyPathAttachesAndRemovesWithoutOwnerOrMutexQuery() {
    var s = state(parameter("p"));
    var e = effect("p");
    var w = new World();
    s.cleanup.ownerAbilityComponent = null;
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    assertSame(e, active(s).entries[0]);
    assertEquals(1, e.reuseRefCount);
    assertEquals(0, pending(s).size);
    assertNull(pending(s).entries[0]);
    assertEquals(5, pending(s).version);
    assertEquals(5, active(s).version);
    assertEquals(List.of("allocateClosure"), w.names());
  }

  @Test
  void nullPendingParameterMatchesWithoutPathOrFadeMutexChecks() {
    var s = state((Object) null);
    var e = effect("other");
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(e), true, w.bound);
    assertSame(e, active(s).entries[0]);
    assertEquals(List.of("allocateClosure"), w.names());
  }

  @Test
  void candidateReverseOrderSkipsUsedEffectAndSelectsPrevious() {
    var s = state(parameter("p"));
    var first = effect("p");
    var used = effect("p");
    used.reuseRefCount = 2;
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(first, used), false, w.bound);
    assertSame(first, active(s).entries[0]);
    assertEquals(2, used.reuseRefCount);
    assertEquals(2, w.calls.size());
  }

  @Test
  void negativeReuseCountAlsoPreventsSelection() {
    var s = state(parameter("p"));
    var e = effect("p");
    e.reuseRefCount = -1;
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    assertEquals(0, active(s).size);
    assertEquals(1, pending(s).size);
  }

  @Test
  void pendingReverseOrderSharesOnlyOnePreviouslyUnusedEffect() {
    var first = parameter("p");
    var last = parameter("p");
    var s = state(first, last);
    var e = effect("p");
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    assertEquals(1, pending(s).size);
    assertSame(first, pending(s).entries[0]);
    assertNull(pending(s).entries[1]);
    assertEquals(1, active(s).size);
    assertEquals(1, e.reuseRefCount);
    assertEquals(2, w.calls.size());
  }

  @Test
  void pathIdentityBypassesStringBinding() {
    String path = new String("p");
    var s = state(parameter(path));
    var e = effect(path);
    var w = new World();
    w.hooks.put("equalPath", c -> fail("same reference path"));
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    assertEquals(1, active(s).size);
  }

  @Test
  void equalDistinctPathsUseEqualityBinding() {
    var s = state(parameter(new String("p")));
    var e = effect(new String("p"));
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    assertTrue(w.names().contains("equalPath"));
    assertEquals(1, active(s).size);
  }

  @Test
  void nullUnequalAndDifferentLengthPathsSkipEqualityBinding() {
    var s = state(parameter("long"));
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(effect(null), effect("s")), false, w.bound);
    assertEquals(List.of("allocateClosure", "allocateClosure"), w.names());
    assertEquals(1, pending(s).size);
  }

  @Test
  void bothNullPathsMatch() {
    var s = state(parameter(null));
    var e = effect(null);
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    assertEquals(1, active(s).size);
  }

  @Test
  void fadeGlobalMutexPreservesRawFloatMaximumAndModule() {
    var p = parameter("p");
    var s = state(p);
    var e = effect("p");
    var w = new World();
    w.module = null;
    RogueMutexAttach46.tryAttach(s, refs(e), true, w.bound);
    Object[] args = w.named("globalMutex").args();
    assertNull(args[0]);
    assertSame(p.effectPath, args[1]);
    assertEquals(Float.floatToRawIntBits(p.minMutexTime), Float.floatToRawIntBits((float) args[2]));
    assertEquals(7, args[3]);
    assertEquals(
        List.of(
            "allocateClosure",
            "ownerEntity",
            "entityWorld",
            "worldEffectMutexModule",
            "globalMutex"),
        w.names());
  }

  @Test
  void busyGlobalMutexDoesNotAttachOrRemove() {
    var s = state(parameter("p"));
    var e = effect("p");
    var w = new World();
    w.globalResult = -1;
    RogueMutexAttach46.tryAttach(s, refs(e), true, w.bound);
    assertEquals(1, pending(s).size);
    assertEquals(0, active(s).size);
    assertEquals(0, e.reuseRefCount);
  }

  @Test
  void entityMutexQueriesOwnerAgainAndPassesNullableCurrentEntity() {
    var p = parameter("p");
    p.mutexType = 1;
    var s = state(p);
    var e = effect("p");
    var w = new World();
    Object first = w.entity;
    w.hooks.put(
        "worldEffectMutexModule",
        c -> {
          w.entity = null;
          s.cleanup.ownerAbilityComponent = new Object();
        });
    RogueMutexAttach46.tryAttach(s, refs(e), true, w.bound);
    Object[] args = w.named("entityMutex").args();
    assertNull(args[4]);
    assertEquals(0, args[5]);
    assertSame(first, w.named("entityWorld").args()[0]);
    assertEquals(2, w.names().stream().filter("ownerEntity"::equals).count());
    assertEquals(1, active(s).size);
  }

  @Test
  void unknownMutexTypeStillQueriesModuleButAttachesWithoutPredicate() {
    var p = parameter("p");
    p.mutexType = 7;
    var s = state(p);
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(effect("p")), true, w.bound);
    assertTrue(w.names().contains("worldEffectMutexModule"));
    assertFalse(w.names().contains("globalMutex"));
    assertFalse(w.names().contains("entityMutex"));
    assertEquals(1, active(s).size);
  }

  @Test
  void fadeMissingOwnerStopsBeforeActiveVersion() {
    var s = state(parameter("p"));
    s.cleanup.ownerAbilityComponent = null;
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMutexAttach46.tryAttach(s, refs(effect("p")), true, w.bound));
    assertEquals(List.of("allocateClosure"), w.names());
    assertEquals(4, active(s).version);
  }

  @Test
  void closureAllocationPrecedesCurrentSourceBoundsAndKeepsOwnerWrite() {
    var s = state(parameter("p"));
    var source = refs(effect("p"));
    var w = new World();
    w.hooks.put("allocateClosure", c -> source.size = 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMutexAttach46.tryAttach(s, source, false, w.bound));
    assertSame(s, w.lastClosure.owner);
    assertNull(w.lastClosure.effectData);
    assertEquals(4, active(s).version);
  }

  @Test
  void nullClosureFailsBeforeSourceStorage() {
    var s = state(parameter("p"));
    var source = new References(null, 1, 0);
    var w = new World();
    w.nullClosure = true;
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> RogueMutexAttach46.tryAttach(s, source, false, w.bound));
    assertTrue(ex.getMessage().contains("closure"));
    assertEquals(4, active(s).version);
  }

  @Test
  void nullIncomingEffectFailsAfterClosureOwnerAndEffectWrite() {
    var s = state(parameter("p"));
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMutexAttach46.tryAttach(s, refs((Object) null), false, w.bound));
    assertSame(s, w.lastClosure.owner);
    assertNull(w.lastClosure.effectData);
  }

  @Test
  void equalityFailureLeavesBothListsAndReferenceCountUntouched() {
    var s = state(parameter(new String("p")));
    var e = effect(new String("p"));
    var w = new World();
    var ex = new IllegalStateException("path");
    w.hooks.put(
        "equalPath",
        c -> {
          throw ex;
        });
    assertSame(
        ex,
        assertThrows(
            IllegalStateException.class,
            () -> RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound)));
    assertEquals(4, active(s).version);
    assertEquals(1, pending(s).size);
    assertEquals(0, e.reuseRefCount);
  }

  @Test
  void activeVersionIncrementsBeforeNullStorageFailure() {
    var s = state(parameter("p"));
    active(s).entries = null;
    var e = effect("p");
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound));
    assertEquals(5, active(s).version);
    assertEquals(0, active(s).size);
    assertEquals(0, e.reuseRefCount);
  }

  @Test
  void growthUsesActualAddResizeAndCapacitySetterWithoutExtraVersion() {
    var s = state(parameter("p"));
    s.cleanup.effects = new References(new Object[0], 0, 9);
    var e = effect("p");
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    assertEquals(4, active(s).entries.length);
    assertSame(e, active(s).entries[0]);
    assertEquals(10, active(s).version);
  }

  @Test
  void capacityCallbackCanChangeCurrentClosureEffectBeforeReuseIncrement() {
    var s = state(parameter("p"));
    s.cleanup.effects = new References(new Object[0], 0, 9);
    var e = effect("p");
    var replacement = effect("new");
    var w = new World();
    w.hooks.put("allocate-array", c -> w.lastClosure.effectData = replacement);
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    assertSame(e, active(s).entries[0]);
    assertEquals(0, e.reuseRefCount);
    assertEquals(1, replacement.reuseRefCount);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void validEffectUsesHideAndOnlyFadeRevivesAndReplays(boolean fade) {
    var s = state((Object) null);
    var e = effect("p");
    var instance = alive(e);
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(e), fade, w.bound);
    assertEquals(fade, w.names().contains("revive"));
    assertEquals(fade, w.names().contains("replay"));
    assertSame(e, w.named("setupEffectHide").args()[1]);
    assertEquals(false, w.named("tryHideEffectInSkillUse").args()[2]);
    if (fade) {
      assertSame(instance.behavior, w.named("revive").args()[0]);
      assertEquals(false, w.named("replay").args()[1]);
    }
  }

  @Test
  void replayRereadsInstanceAfterReviveCallback() {
    var s = state((Object) null);
    var e = effect("p");
    alive(e);
    var replacement = new MonoEffect();
    replacement.behavior = new Object();
    var w = new World();
    w.hooks.put("revive", c -> e.instance = replacement);
    RogueMutexAttach46.tryAttach(s, refs(e), true, w.bound);
    assertSame(replacement.behavior, w.named("replay").args()[0]);
  }

  @Test
  void failedReviveKeepsActiveEntryAndReuseButDoesNotRemovePending() {
    var s = state((Object) null);
    var e = effect("p");
    alive(e);
    var w = new World();
    var ex = new IllegalStateException("revive");
    w.hooks.put(
        "revive",
        c -> {
          throw ex;
        });
    assertSame(
        ex,
        assertThrows(
            IllegalStateException.class,
            () -> RogueMutexAttach46.tryAttach(s, refs(e), true, w.bound)));
    assertEquals(1, active(s).size);
    assertSame(e, active(s).entries[0]);
    assertEquals(1, e.reuseRefCount);
    assertEquals(1, pending(s).size);
  }

  @Test
  void validPointerWithMismatchedIdSkipsHideAndFallbackCallback() {
    var s = state((Object) null);
    var e = effect("p");
    alive(e);
    e.instanceId = 0;
    e.parameters = new TriggerParameters();
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(e), true, w.bound);
    assertEquals(List.of("allocateClosure"), w.names());
    assertNull(e.parameters.completeCallback);
  }

  @Test
  void uncreatedEffectInstallsFreshCallbackWithLiveClosureTarget() {
    var s = state((Object) null);
    var e = effect("p");
    e.parameters = new TriggerParameters();
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    CompletionCallback callback = e.parameters.completeCallback;
    assertSame(w.lastCallback, callback);
    assertSame(w.lastClosure, callback.target);
    assertSame(callback.target, callback.methodCodeTarget);
    assertSame(w.binding.methodInfo(), callback.methodInfo);
    assertEquals(w.binding.methodPointer(), callback.methodPointer);
    assertEquals(callback.methodPointer, callback.invokePointer);
    assertEquals(w.binding.trampoline(), callback.trampoline);
  }

  @Test
  void destroyedInstanceWithZeroIdAlsoInstallsCallback() {
    var s = state((Object) null);
    var e = effect("p");
    e.instance = new MonoEffect();
    e.parameters = new TriggerParameters();
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    assertNotNull(e.parameters.completeCallback);
  }

  @Test
  void nullAllocatedCallbackReadsBindingBeforeFailureAndKeepsAttachment() {
    var s = state((Object) null);
    var e = effect("p");
    e.parameters = new TriggerParameters();
    var w = new World();
    w.nullCallback = true;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound));
    assertEquals("completionCallbackBinding", w.names().getLast());
    assertEquals(1, e.reuseRefCount);
    assertEquals(1, active(s).size);
    assertEquals(1, pending(s).size);
  }

  @Test
  void callbackAllocationMutationDoesNotReplaceCapturedParameterDestination() {
    var s = state((Object) null);
    var e = effect("p");
    var old = new TriggerParameters();
    e.parameters = old;
    var replacement = new TriggerParameters();
    var w = new World();
    w.hooks.put("allocateCompletionCallback", c -> e.parameters = replacement);
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    assertSame(w.lastCallback, old.completeCallback);
    assertNull(replacement.completeCallback);
  }

  @Test
  void callbackOwnerGuardPrecedesWorldAndEffectMayBeNull() {
    var c = new Closure();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMutexAttach46.invokeCompletionCallback(c, new Object(), null));
    c.owner = state();
    var w = new World();
    Object completion = new Object();
    RogueMutexAttach46.invokeCompletionCallback(c, completion, w.bound);
    assertSame(c.owner, w.named("asyncLoadEffectFinished").args()[0]);
    assertNull(w.named("asyncLoadEffectFinished").args()[1]);
    assertSame(completion, w.named("asyncLoadEffectFinished").args()[2]);
  }

  @Test
  void installedCallbackReadsChangedClosureEffectAtInvocation() {
    var s = state((Object) null);
    var e = effect("p");
    e.parameters = new TriggerParameters();
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    var callback = e.parameters.completeCallback;
    Effect replacement = effect("later");
    callback.target.effectData = replacement;
    RogueMutexAttach46.invokeCompletionCallback(callback.target, null, w.bound);
    assertSame(replacement, w.named("asyncLoadEffectFinished").args()[1]);
  }

  @Test
  void pendingRemovalRereadsListReplacedByHideCallback() {
    var s = state((Object) null);
    var e = effect("p");
    alive(e);
    var original = pending(s);
    var replacement = refs(parameter("new"));
    var w = new World();
    w.hooks.put("setupEffectHide", c -> s.cleanup.mutexEffects = replacement);
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    assertEquals(0, replacement.size);
    assertNull(replacement.entries[0]);
    assertEquals(1, original.size);
  }

  @Test
  void removalFailureKeepsDecrementBeforeShiftAndDoesNotIncrementVersion() {
    var p = parameter("p");
    var s = state(p, parameter("other"));
    var e = effect("p");
    var w = new World();
    var ex = new IllegalStateException("shift");
    w.hooks.put(
        "copy",
        c -> {
          throw ex;
        });
    assertSame(
        ex,
        assertThrows(
            IllegalStateException.class,
            () -> RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound)));
    assertEquals(1, pending(s).size);
    assertEquals(4, pending(s).version);
    assertSame(p, pending(s).entries[0]);
    assertEquals(1, e.reuseRefCount);
  }

  @Test
  void removalTailUsesCurrentArrayAndCountAfterCopyCallback() {
    var s = state(parameter("p"), parameter("other"));
    var e = effect("p");
    var replacement = new Object[] {"a", "b", "c"};
    var w = new World();
    w.hooks.put(
        "copy",
        c -> {
          pending(s).entries = replacement;
          pending(s).size = 2;
        });
    RogueMutexAttach46.tryAttach(s, refs(e), false, w.bound);
    assertNull(replacement[2]);
    assertEquals("b", replacement[1]);
    assertEquals(2, pending(s).size);
    assertEquals(5, pending(s).version);
  }

  @Test
  void oversizedPendingCountStopsBeforeWorldAllocation() {
    var s = state(parameter("p"));
    pending(s).size = 256;
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMutexAttach46.tryAttach(s, refs(effect("p")), false, w.bound));
    assertTrue(w.calls.isEmpty());
  }

  @Test
  void oversizedIncomingCountStopsBeforeClosureAllocation() {
    var s = state(parameter("p"));
    var source = refs(effect("p"));
    source.size = 256;
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueMutexAttach46.tryAttach(s, source, false, w.bound));
    assertTrue(w.calls.isEmpty());
  }

  @Test
  void activeAndPendingVersionWrapWithoutResettingOtherState() {
    var s = state((Object) null);
    active(s).version = Integer.MAX_VALUE;
    pending(s).version = Integer.MAX_VALUE;
    var w = new World();
    RogueMutexAttach46.tryAttach(s, refs(effect("p")), false, w.bound);
    assertEquals(Integer.MIN_VALUE, active(s).version);
    assertEquals(Integer.MIN_VALUE, pending(s).version);
  }

  @Test
  void actualOtherCompletionCallbackUsesActualMutexRetryAndActualCapacitySetter() {
    var s = state((Object) null);
    s.cleanup.candidate.state = 1;
    s.cleanup.effects = new References(new Object[0], 0, 0);
    var e = effect("p");
    var w = new World();
    var payload =
        new RogueBaseOtherModifierEvents46.FadeFinished(new Object(), refs(e), new Object());
    RogueBaseOtherModifierEvents46.onOtherFadeFinished(
        s,
        payload,
        (self, effects, fade) ->
            RogueMutexAttach46.tryAttach(self, (References) effects, fade, w.bound));
    assertSame(e, active(s).entries[0]);
    assertEquals(1, e.reuseRefCount);
    assertEquals(0, pending(s).size);
  }
}
