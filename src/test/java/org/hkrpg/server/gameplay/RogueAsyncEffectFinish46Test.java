package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.gameplay.RogueMutexAttach46.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueAsyncEffectFinish46Test {
  record Call(String name, Object self, Object effect) {}

  static class World implements RogueAsyncEffectFinish46.World {
    List<Call> calls = new ArrayList<>();
    Runnable setFinish = () -> {}, hide = () -> {}, skill = () -> {};

    public void setFinish(Object behavior, boolean immediate, boolean fade, boolean unknown) {
      assertTrue(immediate);
      assertFalse(fade);
      assertFalse(unknown);
      calls.add(new Call("setFinish", behavior, null));
      setFinish.run();
    }

    public void setupEffectHide(RogueModifierDestroy46.State self, Effect effect) {
      calls.add(new Call("hide", self, effect));
      hide.run();
    }

    public void tryHideEffectInSkillUse(
        RogueModifierDestroy46.State self, Effect effect, boolean value) {
      assertFalse(value);
      calls.add(new Call("skill", self, effect));
      skill.run();
    }

    List<String> names() {
      return calls.stream().map(Call::name).toList();
    }
  }

  static MonoEffect instance() {
    var m = new MonoEffect();
    m.cachedPointer = 1;
    m.instanceId = 42;
    m.behavior = new Object();
    m.triggerParams = new TriggerParameters();
    return m;
  }

  static RogueModifierDestroy46.State state() {
    return RogueMutexAttach46Test.state();
  }

  static Effect effect() {
    var e = new Effect();
    e.instanceId = 7;
    e.instancePadding = 19;
    e.instance = new MonoEffect();
    e.reuseRefCount = 8;
    return e;
  }

  @Test
  void nullInstanceReturnsWithoutSelfEffectOrWorld() {
    RogueAsyncEffectFinish46.finish(null, null, null, null);
  }

  @Test
  void zeroNativePointerReturnsWithoutSelfEffectOrWorld() {
    var m = instance();
    m.cachedPointer = 0;
    RogueAsyncEffectFinish46.finish(null, null, m, null);
  }

  @Test
  void missingTriggerParamsReturnsWithoutReceiver() {
    var m = instance();
    m.triggerParams = null;
    RogueAsyncEffectFinish46.finish(null, null, m, null);
  }

  @ParameterizedTest
  @ValueSource(ints = {6, Integer.MAX_VALUE})
  void stateAboveFiveReturnsBeforeReceiver(int alive) {
    var m = instance();
    m.triggerParams.aliveState = alive;
    RogueAsyncEffectFinish46.finish(null, null, m, null);
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 5})
  void signedAliveStateAtOrBelowFiveUpdatesFieldsAndKeepsReuse(int alive) {
    var m = instance();
    m.triggerParams.aliveState = alive;
    var s = state();
    var e = effect();
    var w = new World();
    RogueAsyncEffectFinish46.finish(s, e, m, w);
    assertEquals(42, e.instanceId);
    assertEquals(0, e.instancePadding);
    assertSame(m, e.instance);
    assertEquals(8, e.reuseRefCount);
    assertEquals(List.of("hide", "skill"), w.names());
  }

  @Test
  void qualifiedInstanceRequiresModifierBeforeOtherBindings() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAsyncEffectFinish46.finish(null, null, instance(), null));
  }

  @Test
  void unloadingSetFinishsThenHidesWithoutChangingEffectInstanceFields() {
    var s = state();
    s.effectUnload = true;
    var m = instance();
    var e = effect();
    var old = e.instance;
    var w = new World();
    RogueAsyncEffectFinish46.finish(s, e, m, w);
    assertEquals(List.of("setFinish", "hide", "skill"), w.names());
    assertSame(m.behavior, w.calls.getFirst().self());
    assertEquals(7, e.instanceId);
    assertEquals(19, e.instancePadding);
    assertSame(old, e.instance);
  }

  @Test
  void missingActiveListSetFinishsWithoutRequiringEffect() {
    var s = state();
    s.cleanup.effects = null;
    var w = new World();
    RogueAsyncEffectFinish46.finish(s, null, instance(), w);
    assertEquals(List.of("setFinish", "hide", "skill"), w.names());
    assertNull(w.calls.get(1).effect());
    assertNull(w.calls.get(2).effect());
  }

  @Test
  void setFinishUsesCapturedIncomingInstanceBehavior() {
    var s = state();
    s.effectUnload = true;
    var m = instance();
    var old = m.behavior;
    var w = new World();
    w.setFinish = () -> m.behavior = new Object();
    RogueAsyncEffectFinish46.finish(s, null, m, w);
    assertSame(old, w.calls.getFirst().self());
  }

  @Test
  void nullSetFinishBehaviorFailsBeforeHide() {
    var s = state();
    s.effectUnload = true;
    var m = instance();
    m.behavior = null;
    var w = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueAsyncEffectFinish46.finish(s, null, m, w));
    assertTrue(w.calls.isEmpty());
  }

  @Test
  void setFinishFailureStopsHideAndPreservesCallbackMutations() {
    var s = state();
    s.effectUnload = true;
    var e = effect();
    var w = new World();
    var ex = new IllegalStateException("setFinish");
    w.setFinish =
        () -> {
          s.effectUnload = false;
          e.instanceId = 90;
          throw ex;
        };
    assertSame(
        ex,
        assertThrows(
            IllegalStateException.class,
            () -> RogueAsyncEffectFinish46.finish(s, e, instance(), w)));
    assertFalse(s.effectUnload);
    assertEquals(90, e.instanceId);
    assertEquals(List.of("setFinish"), w.names());
  }

  @Test
  void activeListPresenceDoesNotRequireArrayOrCountValidity() {
    var s = state();
    s.cleanup.effects =
        new org.hkrpg.server.gameplay.RogueOwnedParameterLists46.References(null, -5, 9);
    var e = effect();
    var m = instance();
    var w = new World();
    RogueAsyncEffectFinish46.finish(s, e, m, w);
    assertSame(m, e.instance);
    assertEquals(List.of("hide", "skill"), w.names());
  }

  @Test
  void activeBranchRequiresEffectBeforeWritingOrCallingHide() {
    var s = state();
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAsyncEffectFinish46.finish(s, null, instance(), w));
    assertTrue(w.calls.isEmpty());
  }

  @Test
  void activeFieldsPublishBeforeMissingWorldFailure() {
    var s = state();
    var m = instance();
    var e = effect();
    assertThrows(
        IllegalArgumentException.class, () -> RogueAsyncEffectFinish46.finish(s, e, m, null));
    assertEquals(42, e.instanceId);
    assertEquals(0, e.instancePadding);
    assertSame(m, e.instance);
  }

  @Test
  void hideFailureKeepsInstancePublicationAndStopsSkillHide() {
    var s = state();
    var m = instance();
    var e = effect();
    var w = new World();
    var ex = new IllegalStateException("hide");
    w.hide =
        () -> {
          throw ex;
        };
    assertSame(
        ex,
        assertThrows(
            IllegalStateException.class, () -> RogueAsyncEffectFinish46.finish(s, e, m, w)));
    assertSame(m, e.instance);
    assertEquals(List.of("hide"), w.names());
  }

  @Test
  void hideCallbackChangesAreVisibleToSkillHideWithoutRepeatingBranch() {
    var s = state();
    var e = effect();
    var w = new World();
    w.hide =
        () -> {
          s.effectUnload = true;
          e.instanceId = 77;
          s.cleanup.effects = null;
        };
    RogueAsyncEffectFinish46.finish(s, e, instance(), w);
    assertEquals(List.of("hide", "skill"), w.names());
    assertSame(e, w.calls.getLast().effect());
    assertEquals(77, ((Effect) w.calls.getLast().effect()).instanceId);
  }

  @Test
  void setFinishCallbackUsesCapturedNullableEffectForBothHideCalls() {
    var s = state();
    s.effectUnload = true;
    var w = new World();
    w.setFinish =
        () -> {
          s.effectUnload = false;
          s.cleanup.effects = null;
        };
    RogueAsyncEffectFinish46.finish(s, null, instance(), w);
    assertNull(w.calls.get(1).effect());
    assertNull(w.calls.get(2).effect());
  }

  @Test
  void rawUInt32InstanceIdBitsPreserved() {
    var s = state();
    var e = effect();
    var m = instance();
    m.instanceId = -1;
    RogueAsyncEffectFinish46.finish(s, e, m, new World());
    assertEquals(-1, e.instanceId);
    assertEquals(0, e.instancePadding);
  }

  @Test
  void actualInstalledClosureUsesActualAsyncFinishAndSharedEffectState() {
    var s = state();
    s.cleanup.mutexEffects = RogueMutexAttach46Test.refs((Object) null);
    var e = RogueMutexAttach46Test.effect("p");
    e.parameters = new TriggerParameters();
    var w = new RogueMutexAttach46Test.World();
    var finish = new World();
    w.hooks.put(
        "asyncLoadEffectFinished",
        call ->
            RogueAsyncEffectFinish46.finish(
                (RogueModifierDestroy46.State) call.args()[0],
                (Effect) call.args()[1],
                (MonoEffect) call.args()[2],
                finish));
    RogueMutexAttach46.tryAttach(s, RogueMutexAttach46Test.refs(e), false, w.bound);
    var callback = e.parameters.completeCallback;
    var m = instance();
    RogueMutexAttach46.invokeCompletionCallback(callback.target, m, new Object(), w.bound);
    assertSame(m, e.instance);
    assertEquals(42, e.instanceId);
    assertEquals(1, e.reuseRefCount);
    assertEquals(List.of("hide", "skill"), finish.names());
  }
}
