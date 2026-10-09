package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.gameplay.RogueEffectHide46.Behavior;
import org.hkrpg.server.gameplay.RogueEffectHide46.TimerComponent;
import org.hkrpg.server.gameplay.RogueEffectTimer46.*;
import org.hkrpg.server.gameplay.RogueMutexAttach46.MonoEffect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEffectTimer46Test {
  final State self = new State();
  final TimerComponent owner = plugin();
  final Object next = new Object(), previous = new Object();
  final Fake world = new Fake();

  RogueEffectTimer46Test() {
    self.ownerMonoBehavior = owner;
    self.fadeDriver = new Object();
    self.flag72 = 9;
  }

  static TimerComponent plugin() {
    var p = new TimerComponent();
    p.initialized = true;
    p.fadeOutLerp = 2;
    p.ownerEffect = new MonoEffect();
    p.ownerEffect.behavior = new Behavior();
    return p;
  }

  static class Fake implements World {
    List<String> calls = new ArrayList<>();
    List<Object> callbacks = new ArrayList<>();
    boolean check = true, seenFlag;
    int seenBits;
    FollowComponent follow = new FollowComponent();
    Object type = new Object(), seenType, gameObject = new Object(), audioObject;
    TimerComponent queried, audioOwner;
    Behavior audioBehavior, finalBehavior;
    String audioName;
    Runnable onInvoke = () -> {},
        onCheck = () -> {},
        onFollow = () -> {},
        onLerp = () -> {},
        onObject = () -> {},
        onAudio = () -> {};
    String failAt;
    RuntimeException failure;

    Fake() {
      follow.cachedPointer = 17;
    }

    void call(String name) {
      calls.add(name);
      if (name.equals(failAt)) throw failure;
    }

    public void invoke(Object cb) {
      callbacks.add(cb);
      call("invoke");
      onInvoke.run();
    }

    public boolean fadeCheck(State s) {
      call("check");
      onCheck.run();
      return check;
    }

    public void fallback(State s) {
      call("fallback");
    }

    public Object followType() {
      call("type");
      return type;
    }

    public FollowComponent findFollow(TimerComponent p, Object t) {
      queried = p;
      seenType = t;
      call("follow");
      onFollow.run();
      return follow;
    }

    public void lerpOne(State s, float value) {
      seenBits = Float.floatToRawIntBits(value);
      call("one");
      onLerp.run();
    }

    public void lerpZero(State s, float value, boolean flag) {
      seenBits = Float.floatToRawIntBits(value);
      seenFlag = flag;
      call("zero");
      onLerp.run();
    }

    public Object gameObject(TimerComponent p) {
      audioOwner = p;
      call("object");
      onObject.run();
      return gameObject;
    }

    public void postAudio(Behavior b, String name, Object object) {
      audioBehavior = b;
      audioName = name;
      audioObject = object;
      call("audio");
      onAudio.run();
    }

    public void onFadeOut(Behavior b) {
      finalBehavior = b;
      call("fade");
    }
  }

  @Test
  void receiverIsRequiredBeforeFlagPublication() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectTimer46.schedule(null, next, -1, 0, false, world));
    assertEquals(List.of(), world.calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void nullOrUnknownOwnerFailsAfterFlagWrites(int which) {
    self.ownerMonoBehavior = which == 0 ? null : new Object();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectTimer46.schedule(self, next, -1, 0x1ff, false, world));
    assertEquals((byte) 0xff, self.flag73);
    assertEquals(0, self.flag72);
    assertNull(self.callback);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void uninitializedOwnerOrMissingDriverInvokesIncomingWithoutReplacingCurrentCallback(int which) {
    self.callback = previous;
    if (which == 0) owner.initialized = false;
    else self.fadeDriver = null;
    RogueEffectTimer46.schedule(self, next, -1, 1, false, world);
    assertEquals(List.of(next), world.callbacks);
    assertSame(previous, self.callback);
    assertEquals(1, self.flag73);
    assertEquals(0, self.flag72);
    assertEquals(List.of("invoke"), world.calls);
  }

  @Test
  void immediateBranchRequiresIncomingBeforeBindingAndPreservesFlags() {
    owner.initialized = false;
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> RogueEffectTimer46.schedule(self, null, -1, 0, false, null))
            .getMessage()
            .contains("callback"));
    assertEquals(0, self.flag72);
    assertNull(self.callback);
  }

  @Test
  void previousCallbackRunsBeforeIncomingPublicationAndItsFailureKeepsCurrentCallback() {
    self.callback = previous;
    world.onInvoke =
        () -> {
          assertSame(previous, self.callback);
          throw new IllegalStateException("previous");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueEffectTimer46.schedule(self, next, -1, 0, false, world));
    assertSame(previous, self.callback);
    assertEquals(List.of(previous), world.callbacks);
    assertEquals(0, self.flag72);
  }

  @Test
  void oldCallbackMutationIsOverwrittenByIncomingBeforeFadeCheck() {
    self.callback = previous;
    world.onInvoke = () -> self.callback = new Object();
    world.onCheck = () -> assertSame(next, self.callback);
    RogueEffectTimer46.schedule(self, next, -1, 0, false, world);
    assertSame(next, self.callback);
    assertEquals(List.of("invoke", "check", "fade"), world.calls);
  }

  @Test
  void absentPreviousAllowsIncomingPublicationBeforeMissingCheckBinding() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectTimer46.schedule(self, next, -1, 0, false, null));
    assertSame(next, self.callback);
    assertEquals(0, self.flag72);
  }

  @Test
  void activeBranchAllowsNullIncomingAndStoresItBeforeCheck() {
    self.callback = previous;
    RogueEffectTimer46.schedule(self, null, -1, 0, false, world);
    assertNull(self.callback);
    assertEquals(List.of(previous), world.callbacks);
  }

  @Test
  void falseCheckUsesFallbackAndStopsBeforeOwnerReread() {
    world.check = false;
    world.onCheck = () -> self.ownerMonoBehavior = null;
    RogueEffectTimer46.schedule(self, next, -1, 0, false, world);
    assertEquals(List.of("check", "fallback"), world.calls);
    assertSame(next, self.callback);
  }

  @Test
  void trueCheckRereadsOwnerAndRequiresIt() {
    world.onCheck = () -> self.ownerMonoBehavior = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectTimer46.schedule(self, next, -1, 0, false, world));
    assertEquals(List.of("check"), world.calls);
    assertSame(next, self.callback);
  }

  @Test
  void stopFollowUsesCapturedCurrentOwnerAndWritesValidatedFollowComponent() {
    owner.stopFollowOnFadeOut = true;
    RogueEffectTimer46.schedule(self, next, -1, 0, false, world);
    assertSame(owner, world.queried);
    assertSame(world.type, world.seenType);
    assertTrue(world.follow.stopFollow);
    assertEquals(List.of("check", "type", "follow", "fade"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void nullOrInvalidFollowDoesNotStopFinalFade(int which) {
    owner.stopFollowOnFadeOut = true;
    if (which == 0) world.follow = null;
    else world.follow.cachedPointer = 0;
    RogueEffectTimer46.schedule(self, next, -1, 0, false, world);
    assertEquals("fade", world.calls.getLast());
    if (world.follow != null) assertFalse(world.follow.stopFollow);
  }

  @Test
  void followLookupMayReplaceOwnerAndLerpUsesReplacement() {
    owner.stopFollowOnFadeOut = true;
    var replacement = plugin();
    replacement.fadeOutLerp = 1;
    world.onFollow = () -> self.ownerMonoBehavior = replacement;
    RogueEffectTimer46.schedule(self, next, -1, 0, false, world);
    assertEquals(List.of("check", "type", "follow", "one", "fade"), world.calls);
    assertSame(replacement.ownerEffect.behavior, world.finalBehavior);
  }

  @ParameterizedTest
  @ValueSource(ints = {0xbf800000, 0x80000000, 0x7fa12345, 0xff800000})
  void lerpOnePreservesRawFloatAndLerpZeroAlsoPreservesFlag(int bits) {
    owner.fadeOutLerp = 1;
    RogueEffectTimer46.schedule(self, next, Float.intBitsToFloat(bits), 0, true, world);
    assertEquals(bits, world.seenBits);
    owner.fadeOutLerp = 0;
    RogueEffectTimer46.schedule(self, next, Float.intBitsToFloat(bits), 0, true, world);
    assertEquals(bits, world.seenBits);
    assertTrue(world.seenFlag);
  }

  @ParameterizedTest
  @ValueSource(ints = {2, -1, Integer.MIN_VALUE})
  void otherRawLerpValuesDoNotInvokeEitherInterpolation(int value) {
    owner.fadeOutLerp = value;
    RogueEffectTimer46.schedule(self, next, -1, 0, false, world);
    assertEquals(List.of("check", "fade"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "effect-audio"})
  void nonemptyAudioUsesCapturedBehaviorAndNullableGameObject(String name) {
    owner.fadeOutAudioEffectName = name;
    world.gameObject = null;
    RogueEffectTimer46.schedule(self, next, -1, 0, false, world);
    assertEquals(
        name.isEmpty() ? List.of("check", "fade") : List.of("check", "object", "audio", "fade"),
        world.calls);
    if (!name.isEmpty()) {
      assertSame(owner.ownerEffect.behavior, world.audioBehavior);
      assertNull(world.audioObject);
      assertEquals(name, world.audioName);
    }
  }

  @Test
  void objectQueryMayReplaceOwnerButAudioKeepsCapturedBehaviorAndFinalFadeRereadsOwner() {
    owner.fadeOutAudioEffectName = "audio";
    var replacement = plugin();
    var captured = owner.ownerEffect.behavior;
    world.onObject =
        () -> {
          owner.ownerEffect.behavior = new Behavior();
          self.ownerMonoBehavior = replacement;
        };
    RogueEffectTimer46.schedule(self, next, -1, 0, false, world);
    assertSame(captured, world.audioBehavior);
    assertSame(replacement.ownerEffect.behavior, world.finalBehavior);
    assertSame(owner, world.audioOwner);
  }

  @Test
  void lerpMutationIsObservedByAudioAndFinalFade() {
    owner.fadeOutLerp = 0;
    var replacement = plugin();
    replacement.fadeOutAudioEffectName = "new";
    world.onLerp = () -> self.ownerMonoBehavior = replacement;
    RogueEffectTimer46.schedule(self, next, -1, 0, false, world);
    assertEquals("new", world.audioName);
    assertSame(replacement.ownerEffect.behavior, world.finalBehavior);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void absentOwnerEffectOrBehaviorSkipsAudioThenFailsAtFinalFade(int which) {
    owner.fadeOutAudioEffectName = "audio";
    if (which == 0) owner.ownerEffect = null;
    else owner.ownerEffect.behavior = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectTimer46.schedule(self, next, -1, 0, false, world));
    assertEquals(List.of("check"), world.calls);
    assertSame(next, self.callback);
  }

  @ParameterizedTest
  @ValueSource(strings = {"check", "follow", "one", "audio", "fade"})
  void failedBindingKeepsCallbackFlagsAndAlreadyChangedFollowState(String failAt) {
    owner.stopFollowOnFadeOut = true;
    owner.fadeOutLerp = 1;
    owner.fadeOutAudioEffectName = "audio";
    world.failAt = failAt;
    world.failure = new IllegalStateException(failAt);
    assertSame(
        world.failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueEffectTimer46.schedule(self, next, -1, 0, false, world)));
    assertSame(next, self.callback);
    assertEquals(0, self.flag72);
    assertEquals(failAt, world.calls.getLast());
    if (!failAt.equals("check") && !failAt.equals("follow")) assertTrue(world.follow.stopFollow);
  }

  @Test
  void actualWaitingSkillHideSchedulesActualFadeThenStoredActualCallbackHides() {
    var modifier = RogueMutexAttach46Test.state();
    modifier.effectAutoHideInSkill = true;
    var effect = new RogueMutexAttach46.Effect();
    effect.autoHideInSkill = true;
    var instance = owner.ownerEffect;
    instance.cachedPointer = 17;
    instance.instanceId = 22;
    instance.triggerParams = new RogueMutexAttach46.TriggerParameters();
    instance.triggerParams.aliveState = 3;
    effect.instance = instance;
    effect.instanceId = 22;
    owner.cachedPointer = 88;
    owner.timer = self;
    List<Boolean> activations = new ArrayList<>();
    var hiding =
        new RogueEffectHide46.World() {
          public void setActive(Behavior b, boolean active) {
            activations.add(active);
          }

          public RogueEffectHide46.SkillClosure allocateClosure() {
            return new RogueEffectHide46.SkillClosure();
          }

          public Object componentType() {
            return null;
          }

          public TimerComponent findComponent(MonoEffect i, Object t) {
            return owner;
          }

          public RogueEffectHide46.Callback allocateCallback() {
            return new RogueEffectHide46.Callback();
          }

          public RogueEffectHide46.Binding callbackBinding() {
            return new RogueEffectHide46.Binding(new Object(), 7, 9);
          }

          public void schedule(
              Object timer, RogueEffectHide46.Callback c, float v, int z, boolean f) {
            assertSame(self, timer);
            assertEquals(0xbf800000, Float.floatToRawIntBits(v));
            assertEquals(0, z);
            assertFalse(f);
            RogueEffectTimer46.schedule(self, c, v, z, f, world);
          }
        };
    RogueEffectHide46.tryHide(modifier, effect, true, hiding);
    assertEquals(List.of("check", "fade"), world.calls);
    assertEquals(List.of(), activations);
    var stored = (RogueEffectHide46.Callback) self.callback;
    RogueEffectHide46.invokeDelayed(stored.target, hiding);
    assertEquals(512, ((Behavior) instance.behavior).hideReason);
    assertEquals(List.of(false), activations);
  }
}
