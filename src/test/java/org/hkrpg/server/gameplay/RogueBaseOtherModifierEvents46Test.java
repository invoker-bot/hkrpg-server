package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RogueBaseOtherModifierEvents46Test {
  static class World implements RogueBaseOtherModifierEvents46.World {
    List<RogueModifierDestroy46.State> receivers = new ArrayList<>();
    List<Object> effects = new ArrayList<>();
    List<Boolean> flags = new ArrayList<>();
    Runnable action = () -> {};

    public void virtualEffectSlot21(
        RogueModifierDestroy46.State self, Object currentEffects, boolean fadeFinished) {
      receivers.add(self);
      effects.add(currentEffects);
      flags.add(fadeFinished);
      action.run();
    }
  }

  static Object event(boolean fade, Object modifier, Object effects) {
    return fade
        ? new RogueBaseOtherModifierEvents46.FadeFinished(modifier, effects, new Object())
        : new RogueBaseOtherModifierEvents46.Destroyed(modifier, effects, new Object());
  }

  static void invoke(
      boolean fade,
      RogueModifierDestroy46.State self,
      Object event,
      RogueBaseOtherModifierEvents46.World world) {
    if (fade) RogueBaseOtherModifierEvents46.onOtherFadeFinished(self, event, world);
    else RogueBaseOtherModifierEvents46.onOtherDestroy(self, event, world);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nullOrUnknownEventReturnsBeforeReceiverAndWorld(boolean fade) {
    invoke(fade, null, null, null);
    invoke(fade, null, new Object(), null);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void otherKnownPayloadHierarchyDoesNotMatch(boolean fade) {
    invoke(fade, null, event(!fade, new Object(), new Object()), null);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void selfPayloadReturnsBeforeStateOrWorldEvenForNullIdentity(boolean fade) {
    invoke(fade, null, event(fade, null, new Object()), null);
    var self = RogueModifierDestroy46Test.state();
    self.cleanup.candidate.state = 1;
    invoke(fade, self, event(fade, self, new Object()), null);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void validOtherPayloadNeedsReceiverAfterSelfIdentityGate(boolean fade) {
    assertThrows(
        IllegalArgumentException.class,
        () -> invoke(fade, null, event(fade, new Object(), null), null));
  }

  @ParameterizedTest
  @CsvSource({
    "false,-2147483648",
    "false,-1",
    "false,0",
    "false,2",
    "false,2147483647",
    "true,-2147483648",
    "true,-1",
    "true,0",
    "true,2",
    "true,2147483647"
  })
  void onlyExactlyActiveStateMayDispatch(boolean fade, int state) {
    var self = RogueModifierDestroy46Test.state();
    self.cleanup.candidate.state = state;
    invoke(fade, self, event(fade, new Object(), null), null);
    assertEquals(state, self.cleanup.candidate.state);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void activeStateForwardsSameEffectsAndCorrectFlag(boolean fade) {
    var self = RogueModifierDestroy46Test.state();
    self.cleanup.candidate.state = 1;
    Object effects = new Object();
    var world = new World();
    invoke(fade, self, event(fade, new Object(), effects), world);
    assertEquals(List.of(self), world.receivers);
    assertEquals(List.of(effects), world.effects);
    assertEquals(List.of(fade), world.flags);
    assertEquals(1, self.cleanup.candidate.state);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nullOtherModifierAndNullEffectsStillDispatchForActiveReceiver(boolean fade) {
    var self = RogueModifierDestroy46Test.state();
    self.cleanup.candidate.state = 1;
    var world = new World();
    invoke(fade, self, event(fade, null, null), world);
    assertEquals(1, world.receivers.size());
    assertNull(world.effects.get(0));
    assertEquals(fade, world.flags.get(0));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void activeOtherCallbackNeedsWorldAtDispatch(boolean fade) {
    var self = RogueModifierDestroy46Test.state();
    self.cleanup.candidate.state = 1;
    assertThrows(
        IllegalArgumentException.class,
        () -> invoke(fade, self, event(fade, new Object(), null), null));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void completedCallbackChangesSharedStateAndNextCallbackIsSkipped(boolean fade) {
    var self = RogueModifierDestroy46Test.state();
    self.cleanup.candidate.state = 1;
    var world = new World();
    world.action = () -> self.cleanup.candidate.state = 2;
    Object payload = event(fade, new Object(), new Object());
    invoke(fade, self, payload, world);
    invoke(fade, self, payload, world);
    assertEquals(1, world.receivers.size());
    assertEquals(2, self.cleanup.candidate.state);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void effectDispatchExceptionPropagatesAndKeepsMutatedPrefix(boolean fade) {
    var self = RogueModifierDestroy46Test.state();
    self.cleanup.candidate.state = 1;
    var world = new World();
    world.action =
        () -> {
          self.cleanup.candidate.state = 2;
          throw new IllegalStateException("effects");
        };
    assertThrows(
        IllegalStateException.class,
        () -> invoke(fade, self, event(fade, new Object(), null), world));
    assertEquals(2, self.cleanup.candidate.state);
    assertEquals(1, world.receivers.size());
  }

  @Test
  void destructionPayloadSubclassMatchesButFadePayloadSubclassIsSeparate() {
    var self = RogueModifierDestroy46Test.state();
    self.cleanup.candidate.state = 1;
    var world = new World();
    Object effects = new Object();
    var payload = new RogueBaseOtherModifierEvents46.Destroyed(new Object(), effects, null) {};
    RogueBaseOtherModifierEvents46.onOtherDestroy(self, payload, world);
    RogueBaseOtherModifierEvents46.onOtherFadeFinished(self, payload, world);
    assertEquals(1, world.receivers.size());
    assertSame(effects, world.effects.get(0));
  }

  @Test
  void fadePayloadSubclassUsesTrueFlag() {
    var self = RogueModifierDestroy46Test.state();
    self.cleanup.candidate.state = 1;
    var world = new World();
    RogueBaseOtherModifierEvents46.onOtherFadeFinished(
        self, new RogueBaseOtherModifierEvents46.FadeFinished(new Object(), null, null) {}, world);
    assertEquals(List.of(true), world.flags);
  }

  @Test
  void sourceEntityIsNotReadAsEffectsOrReceiverIdentity() {
    var self = RogueModifierDestroy46Test.state();
    self.cleanup.candidate.state = 1;
    Object effects = new Object();
    var world = new World();
    var payload = new RogueBaseOtherModifierEvents46.Destroyed(null, effects, self);
    RogueBaseOtherModifierEvents46.onOtherDestroy(self, payload, world);
    assertSame(effects, world.effects.get(0));
    assertSame(self, world.receivers.get(0));
  }

  @Test
  void baseFactoryCallbacksDispatchPayloadsAndFreshDelegateMatchesWithdrawalIdentity() {
    var self = RogueModifierDestroy46Test.state();
    self.cleanup.candidate.state = 1;
    var effectsWorld = new World();
    var baseWorld = new RogueBaseModifierDestroy46Test.World();
    RogueBaseModifierDestroy46.destroy(self, 4, baseWorld);
    var destroyCallback = baseWorld.allocated.get(0);
    var fadeCallback = baseWorld.allocated.get(1);
    assertSame(self, destroyCallback.target);
    assertSame(self, fadeCallback.target);
    assertSame(RogueBaseModifierDestroy46Test.FIRST_INFO, destroyCallback.methodInfo);
    assertSame(RogueBaseModifierDestroy46Test.SECOND_INFO, fadeCallback.methodInfo);
    Object firstEffects = new Object(), secondEffects = new Object();
    RogueBaseOtherModifierEvents46.onOtherDestroy(
        destroyCallback.target,
        new RogueBaseOtherModifierEvents46.Destroyed(new Object(), firstEffects, null),
        effectsWorld);
    RogueBaseOtherModifierEvents46.onOtherFadeFinished(
        fadeCallback.target,
        new RogueBaseOtherModifierEvents46.FadeFinished(new Object(), secondEffects, null),
        effectsWorld);
    assertEquals(List.of(firstEffects, secondEffects), effectsWorld.effects);
    assertEquals(List.of(false, true), effectsWorld.flags);
    var freshBaseWorld = new RogueBaseModifierDestroy46Test.World();
    RogueBaseModifierDestroy46.destroy(self, 4, freshBaseWorld);
    var removalWorld = new RogueEventDelegateRemoval46Test.World();
    assertNotSame(destroyCallback, freshBaseWorld.allocated.get(0));
    assertNull(
        RogueEventDelegateRemoval46.remove(
            destroyCallback, freshBaseWorld.allocated.get(0), removalWorld));
    assertEquals(4, self.deathSource);
  }
}
