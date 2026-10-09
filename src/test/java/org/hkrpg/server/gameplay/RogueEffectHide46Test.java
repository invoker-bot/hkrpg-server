package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.gameplay.RogueEffectHide46.*;
import org.hkrpg.server.gameplay.RogueModifierDestroy46.State;
import org.hkrpg.server.gameplay.RogueMutexAttach46.Effect;
import org.hkrpg.server.gameplay.RogueMutexAttach46.MonoEffect;
import org.hkrpg.server.gameplay.RogueMutexAttach46.TriggerParameters;
import org.hkrpg.server.gameplay.RogueOwnedParameterLists46.References;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEffectHide46Test {
  private final State self = RogueMutexAttach46Test.state();
  private final Effect effect = new Effect();
  private final MonoEffect instance = new MonoEffect();
  private final Behavior behavior = new Behavior();
  private final Fake world = new Fake();

  RogueEffectHide46Test() {
    self.cleanup.effects = new References(new Object[0], 0, 0);
    self.effectAutoHideInSkill = true;
    effect.autoHideInSkill = true;
    effect.instanceId = 0x80000001;
    effect.instancePadding = 123;
    effect.instance = instance;
    instance.instanceId = effect.instanceId;
    instance.cachedPointer = 77;
    instance.triggerParams = new TriggerParameters();
    instance.triggerParams.aliveState = 3;
    instance.behavior = behavior;
    world.component.cachedPointer = 90;
    world.component.timer = world.timer;
  }

  private static class Fake implements World {
    final List<String> calls = new ArrayList<>();
    SkillClosure closure = new SkillClosure();
    TimerComponent component = new TimerComponent();
    Callback callback = new Callback();
    Binding binding = new Binding(new Object(), 21, 31);
    final Object type = new Object(), timer = new Object();
    Object observedType, observedTimer;
    MonoEffect lookupInstance;
    Callback scheduled;
    float value;
    int zero;
    boolean flag;
    Runnable onLookup = () -> {}, onAllocate = () -> {}, onBind = () -> {};
    RuntimeException failure;
    String failAt;

    void call(String name) {
      calls.add(name);
      if (name.equals(failAt)) throw failure;
    }

    public void setActive(Behavior b, boolean active) {
      call("active:" + active);
    }

    public SkillClosure allocateClosure() {
      call("closure");
      return closure;
    }

    public Object componentType() {
      call("type");
      return type;
    }

    public TimerComponent findComponent(MonoEffect i, Object t) {
      call("lookup");
      lookupInstance = i;
      observedType = t;
      onLookup.run();
      return component;
    }

    public Callback allocateCallback() {
      call("callback");
      onAllocate.run();
      return callback;
    }

    public Binding callbackBinding() {
      call("binding");
      onBind.run();
      return binding;
    }

    public void schedule(Object t, Callback c, float v, int z, boolean f) {
      observedTimer = t;
      scheduled = c;
      value = v;
      zero = z;
      flag = f;
      call("schedule");
    }
  }

  @ParameterizedTest
  @CsvSource({
    "0,0,false",
    "128,0,true",
    "129,1,false",
    "512,512,false",
    "640,512,false",
    "-1,-129,false"
  })
  void clearingModifierReasonOnlyReactivatesWhenItWasTheOnlyReason(
      int before, int after, boolean active) {
    behavior.hideReason = before;
    RogueEffectHide46.setup(self, effect, active ? world : null);
    assertEquals(after, behavior.hideReason);
    assertEquals(active ? List.of("active:true") : List.of(), world.calls);
  }

  @ParameterizedTest
  @CsvSource({"true,false", "false,true", "true,true"})
  void eitherModifierHideFlagAlwaysDeactivatesIncludingAlreadySetReason(
      boolean hide, boolean priority) {
    self.effectHide = hide;
    self.effectPriorityHide = priority;
    behavior.hideReason = 0x280;
    RogueEffectHide46.setup(self, effect, world);
    assertEquals(0x280, behavior.hideReason);
    assertEquals(List.of("active:false"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void setupInvalidEffectReturnsBeforeReceiverAndWorld(int which) {
    switch (which) {
      case 0 -> effect.instance = null;
      case 1 -> instance.cachedPointer = 0;
      case 2 -> effect.instanceId++;
      case 3 -> instance.triggerParams = null;
      case 4 -> instance.triggerParams.aliveState = 6;
      case 5 -> {
        RogueEffectHide46.setup(null, null, null);
        return;
      }
    }
    RogueEffectHide46.setup(null, effect, null);
    assertEquals(0, behavior.hideReason);
  }

  @Test
  void setupAllowsFiveAndNegativeAliveStates() {
    self.effectHide = true;
    for (int state : new int[] {5, -1, Integer.MIN_VALUE}) {
      instance.triggerParams.aliveState = state;
      RogueEffectHide46.setup(self, effect, world);
    }
    assertEquals(List.of("active:false", "active:false", "active:false"), world.calls);
  }

  @Test
  void setupReadsReceiverBeforeDemandingBehavior() {
    instance.behavior = null;
    assertTrue(
        assertThrows(
                IllegalArgumentException.class, () -> RogueEffectHide46.setup(null, effect, world))
            .getMessage()
            .contains("modifier"));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class, () -> RogueEffectHide46.setup(self, effect, world))
            .getMessage()
            .contains("behavior"));
    assertEquals(List.of(), world.calls);
  }

  @Test
  void setupMissingBindingOrActiveFailureKeepsReasonWrite() {
    self.effectHide = true;
    assertThrows(IllegalArgumentException.class, () -> RogueEffectHide46.setup(self, effect, null));
    assertEquals(128, behavior.hideReason);
    self.effectHide = false;
    world.failAt = "active:true";
    world.failure = new IllegalStateException("active");
    assertSame(
        world.failure,
        assertThrows(
            IllegalStateException.class, () -> RogueEffectHide46.setup(self, effect, world)));
    assertEquals(0, behavior.hideReason);
  }

  @Test
  void disabledOwnerStillAllocatesAndPublishesOwnerBeforeNullableEffectReturn() {
    self.effectAutoHideInSkill = false;
    RogueEffectHide46.tryHide(self, null, true, world);
    assertEquals(List.of("closure"), world.calls);
    assertSame(self, world.closure.owner);
  }

  @Test
  void closureAllocationAndNullReceiverFailureOrderIsPreserved() {
    world.closure.owner = self;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEffectHide46.tryHide(null, null, true, world));
    assertNull(world.closure.owner);
    world.closure = null;
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> RogueEffectHide46.tryHide(null, null, true, world))
            .getMessage()
            .contains("closure"));
    assertEquals(List.of("closure", "closure"), world.calls);
  }

  @Test
  void enabledOwnerDemandsEffectButDisabledEffectDoesNotReadInstance() {
    assertThrows(
        IllegalArgumentException.class, () -> RogueEffectHide46.tryHide(self, null, false, world));
    effect.autoHideInSkill = false;
    effect.instance = null;
    world.closure.instancePadding = -3;
    RogueEffectHide46.tryHide(self, effect, false, world);
    assertEquals(-3, world.closure.instancePadding);
    assertEquals(List.of("closure", "closure"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void skillInvalidInstanceReturnsAfterValueSnapshotBeforeComponentLookup(int which) {
    switch (which) {
      case 0 -> effect.instance = null;
      case 1 -> instance.cachedPointer = 0;
      case 2 -> instance.instanceId++;
      case 3 -> instance.triggerParams = null;
      case 4 -> instance.triggerParams.aliveState = 5;
    }
    RogueEffectHide46.tryHide(self, effect, false, world);
    assertEquals(effect.instanceId, world.closure.instanceId);
    assertEquals(123, world.closure.instancePadding);
    assertSame(effect.instance, world.closure.instance);
    assertEquals(List.of("closure"), world.calls);
  }

  @Test
  void skillFourAndNegativeStateStillLookupWhenWaitIsFalse() {
    for (int state : new int[] {4, -1, Integer.MIN_VALUE}) {
      instance.triggerParams.aliveState = state;
      RogueEffectHide46.tryHide(self, effect, false, world);
    }
    assertEquals(512, behavior.hideReason);
    assertSame(instance, world.lookupInstance);
    assertSame(world.type, world.observedType);
    assertEquals(
        List.of(
            "closure",
            "type",
            "lookup",
            "active:false",
            "closure",
            "type",
            "lookup",
            "active:false",
            "closure",
            "type",
            "lookup",
            "active:false"),
        world.calls);
  }

  @Test
  void lookupUsesCapturedInstanceButImmediateHideUsesCurrentClosureInstanceWithoutRevalidation() {
    MonoEffect replacement = new MonoEffect();
    Behavior changed = new Behavior();
    replacement.behavior = changed;
    world.onLookup =
        () -> {
          world.closure.instance = replacement;
          effect.instance = null;
        };
    RogueEffectHide46.tryHide(self, effect, false, world);
    assertSame(instance, world.lookupInstance);
    assertEquals(0, behavior.hideReason);
    assertEquals(512, changed.hideReason);
  }

  @Test
  void lookupMayClearCurrentInstanceAndPreventsAnyHide() {
    world.onLookup = () -> world.closure.instance = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectHide46.tryHide(self, effect, false, world));
    assertEquals(List.of("closure", "type", "lookup"), world.calls);
    assertEquals(0, behavior.hideReason);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void waitUsesImmediateHideForChangedStateOrAbsentComponent(int which) {
    world.onLookup =
        () -> {
          switch (which) {
            case 0 -> instance.triggerParams = null;
            case 1 -> instance.triggerParams.aliveState = 4;
            case 2 -> world.component = null;
            case 3 -> world.component.cachedPointer = 0;
          }
        };
    RogueEffectHide46.tryHide(self, effect, true, world);
    assertEquals(512, behavior.hideReason);
    assertEquals(List.of("closure", "type", "lookup", "active:false"), world.calls);
  }

  @Test
  void schedulingCapturesTimerBeforeAllocationAndPreservesCallbackFieldsAndRawArguments() {
    world.onAllocate = () -> world.component.timer = new Object();
    RogueEffectHide46.tryHide(self, effect, true, world);
    assertSame(world.timer, world.observedTimer);
    assertSame(world.callback, world.scheduled);
    assertSame(world.binding.methodInfo(), world.callback.methodInfo);
    assertEquals(21, world.callback.methodPointer);
    assertEquals(21, world.callback.invokePointer);
    assertEquals(31, world.callback.trampoline);
    assertSame(world.closure, world.callback.target);
    assertSame(world.closure, world.callback.methodCodeTarget);
    assertEquals(0xbf800000, Float.floatToRawIntBits(world.value));
    assertEquals(0, world.zero);
    assertFalse(world.flag);
    assertEquals(0, behavior.hideReason);
    assertEquals(
        List.of("closure", "type", "lookup", "callback", "binding", "schedule"), world.calls);
  }

  @Test
  void nullTimerIsDemandedAfterCallbackInitializationDespiteAllocationMutation() {
    world.component.timer = null;
    world.onAllocate = () -> world.component.timer = world.timer;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEffectHide46.tryHide(self, effect, true, world));
    assertSame(world.closure, world.callback.target);
    assertEquals(31, world.callback.trampoline);
    assertEquals(List.of("closure", "type", "lookup", "callback", "binding"), world.calls);
    assertEquals(0, behavior.hideReason);
  }

  @Test
  void bindingIsReadAfterNullAllocationAndBeforeAllocationFailure() {
    world.callback = null;
    world.binding = null;
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> RogueEffectHide46.tryHide(self, effect, true, world))
            .getMessage()
            .contains("method binding"));
    assertEquals(List.of("closure", "type", "lookup", "callback", "binding"), world.calls);
  }

  @Test
  void missingBindingDoesNotPartiallyInitializeCallback() {
    world.binding = null;
    world.callback.methodPointer = 777;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEffectHide46.tryHide(self, effect, true, world));
    assertEquals(777, world.callback.methodPointer);
    assertNull(world.callback.target);
  }

  @Test
  void schedulingFailureDoesNotImmediatelyHideOrRetryAndKeepsInitializedDelegate() {
    world.failAt = "schedule";
    world.failure = new IllegalStateException("timer");
    assertSame(
        world.failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueEffectHide46.tryHide(self, effect, true, world)));
    assertSame(world.closure, world.callback.target);
    assertEquals(0, behavior.hideReason);
    assertEquals(1, world.calls.stream().filter("schedule"::equals).count());
  }

  @Test
  void immediateHideFailurePreservesReasonAndCallbackMutation() {
    behavior.hideReason = 129;
    world.failAt = "active:false";
    world.failure = new IllegalStateException("active");
    assertSame(
        world.failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueEffectHide46.tryHide(self, effect, false, world)));
    assertEquals(641, behavior.hideReason);
  }

  @Test
  void cleanupNullListStopsDelayedHideBeforeInstanceAndWorld() {
    world.closure.owner = self;
    self.cleanup.effects = null;
    RogueEffectHide46.invokeDelayed(world.closure, null);
    assertEquals(List.of(), world.calls);
    world.closure.owner = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEffectHide46.invokeDelayed(world.closure, null));
  }

  @Test
  void delayedHideUsesCurrentInstanceWithoutRecheckingFlagsIdPointerOrAliveState() {
    RogueEffectHide46.tryHide(self, effect, true, world);
    MonoEffect replacement = new MonoEffect();
    Behavior changed = new Behavior();
    replacement.behavior = changed;
    world.closure.instance = replacement;
    self.effectAutoHideInSkill = false;
    ((References) self.cleanup.effects).size = -1;
    ((References) self.cleanup.effects).entries = null;
    RogueEffectHide46.invokeDelayed(world.closure, world);
    assertEquals(512, changed.hideReason);
    assertEquals(0, behavior.hideReason);
    assertEquals("active:false", world.calls.getLast());
  }

  @Test
  void delayedMissingWorldKeepsSkillReason() {
    world.closure.owner = self;
    world.closure.instance = instance;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEffectHide46.invokeDelayed(world.closure, null));
    assertEquals(512, behavior.hideReason);
  }

  @Test
  void actualAsyncCompletionPublishesSharedInstanceThenRunsBothActualHideBodies() {
    effect.instance = null;
    effect.instanceId = 0;
    self.effectHide = true;
    RogueAsyncEffectFinish46.finish(
        self,
        effect,
        instance,
        new RogueAsyncEffectFinish46.World() {
          public void setFinish(Object b, boolean a, boolean c, boolean d) {
            fail("active list branch");
          }

          public void setupEffectHide(State s, Effect e) {
            RogueEffectHide46.setup(s, e, world);
          }

          public void tryHideEffectInSkillUse(State s, Effect e, boolean v) {
            RogueEffectHide46.tryHide(s, e, v, world);
          }
        });
    assertSame(instance, effect.instance);
    assertEquals(instance.instanceId, effect.instanceId);
    assertEquals(0, effect.instancePadding);
    assertEquals(640, behavior.hideReason);
    assertEquals(List.of("active:false", "closure", "type", "lookup", "active:false"), world.calls);
    assertEquals(0, effect.reuseRefCount);
  }
}
