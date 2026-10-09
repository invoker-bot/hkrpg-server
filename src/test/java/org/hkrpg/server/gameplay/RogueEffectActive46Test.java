package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.gameplay.RogueEffectActive46.*;
import org.hkrpg.server.gameplay.RogueEffectHide46.Behavior;
import org.hkrpg.server.gameplay.RogueMutexAttach46.MonoEffect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEffectActive46Test {
  private final Behavior behavior = new Behavior();
  private final MonoEffect owner = new MonoEffect();
  private final Fake world = new Fake();

  RogueEffectActive46Test() {
    behavior.ownerMonoBehavior = owner;
    behavior.effectInited = true;
    owner.isActive = true;
  }

  private static class Fake implements World {
    List<String> calls = new ArrayList<>();
    List<MonoEffect> owners = new ArrayList<>();
    GameObject first = gameObject(), second = gameObject();
    CharaPlugin plugin = new CharaPlugin();
    Object type = new Object(), seenType;
    GameObject seenObject;
    Runnable onInitialize = () -> {}, onGameObject = () -> {}, onVisible = () -> {};
    String failAt;
    RuntimeException failure;

    Fake() {
      plugin.cachedPointer = 99;
    }

    static GameObject gameObject() {
      var g = new GameObject();
      g.cachedPointer = 10;
      return g;
    }

    void call(String name) {
      calls.add(name);
      if (name.equals(failAt)) throw failure;
    }

    public void initialize(Behavior self) {
      call("init");
      onInitialize.run();
    }

    public GameObject gameObject(MonoEffect instance) {
      owners.add(instance);
      call("object");
      onGameObject.run();
      return owners.size() == 1 ? first : second;
    }

    public void setVisible(Behavior self, boolean active) {
      assertFalse(active);
      call("visible");
      onVisible.run();
    }

    public Object charaPluginType() {
      call("type");
      return type;
    }

    public CharaPlugin findCharaPlugin(GameObject object, Object type) {
      seenType = type;
      seenObject = object;
      call("plugin");
      return plugin;
    }

    public void enableCharaEffect(CharaPlugin plugin, boolean active, boolean immediate) {
      assertSame(this.plugin, plugin);
      assertFalse(active);
      assertTrue(immediate);
      call("enable");
    }
  }

  @Test
  void missingBehaviorPrecedesAllBindings() {
    assertThrows(
        IllegalArgumentException.class, () -> RogueEffectActive46.setActive(null, false, world));
    assertEquals(List.of(), world.calls);
  }

  @Test
  void initializationRunsBeforeOwnerReadAndMaySupplyCurrentOwnerWithoutSettingFlag() {
    behavior.effectInited = false;
    behavior.ownerMonoBehavior = null;
    world.onInitialize = () -> behavior.ownerMonoBehavior = owner;
    RogueEffectActive46.setActive(behavior, false, world);
    assertFalse(behavior.effectInited);
    assertFalse(owner.isActive);
    assertEquals(
        List.of("init", "object", "visible", "object", "type", "plugin", "enable"), world.calls);
  }

  @Test
  void initializationBindingIsRequiredEvenWhenRequestedStateAlreadyMatches() {
    behavior.effectInited = false;
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> RogueEffectActive46.setActive(behavior, true, null))
            .getMessage()
            .contains("initialization"));
    assertTrue(owner.isActive);
  }

  @Test
  void initializationFailurePreservesItsWritesAndStopsBeforeOwnerAccess() {
    behavior.effectInited = false;
    world.onInitialize =
        () -> {
          behavior.effectInited = true;
          behavior.ownerMonoBehavior = null;
          throw new IllegalStateException("init");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueEffectActive46.setActive(behavior, false, world));
    assertTrue(behavior.effectInited);
    assertNull(behavior.ownerMonoBehavior);
    assertEquals(List.of("init"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void nullOrUnknownOwnerStopsBeforeGameObjectLookup(int which) {
    behavior.ownerMonoBehavior = which == 0 ? null : new Object();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectActive46.setActive(behavior, false, world));
    assertEquals(List.of(), world.calls);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void equalStateReturnsWithoutWorldOrUnityPointerValidation(boolean active) {
    owner.isActive = active;
    owner.cachedPointer = 0;
    RogueEffectActive46.setActive(behavior, active, null);
    assertEquals(active, owner.isActive);
  }

  @Test
  void changingStatePublishesBeforeMissingGameObjectBinding() {
    assertThrows(
        IllegalArgumentException.class, () -> RogueEffectActive46.setActive(behavior, false, null));
    assertFalse(owner.isActive);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void missingOrInvalidFirstGameObjectStopsAfterOwnerPublication(int which) {
    if (which == 0) world.first = null;
    else world.first.cachedPointer = 0;
    RogueEffectActive46.setActive(behavior, false, world);
    assertFalse(owner.isActive);
    assertSame(owner, world.owners.getFirst());
    assertEquals(List.of("object"), world.calls);
  }

  @Test
  void activationVisibilityAndCharaEnablePreserveOrderAndArguments() {
    RogueEffectActive46.setActive(behavior, false, world);
    assertFalse(owner.isActive);
    assertEquals(List.of(owner, owner), world.owners);
    assertSame(world.second, world.seenObject);
    assertSame(world.type, world.seenType);
    assertEquals(List.of("object", "visible", "object", "type", "plugin", "enable"), world.calls);
  }

  @Test
  void trueActivationPassesTrueAndImmediateTrue() {
    owner.isActive = false;
    List<Boolean> values = new ArrayList<>();
    Fake activating =
        new Fake() {
          public void setVisible(Behavior self, boolean active) {
            values.add(active);
          }

          public void enableCharaEffect(CharaPlugin p, boolean active, boolean immediate) {
            values.add(active);
            assertTrue(immediate);
          }
        };
    RogueEffectActive46.setActive(behavior, true, activating);
    assertTrue(owner.isActive);
    assertEquals(List.of(true, true), values);
  }

  @Test
  void visibilityMayReplaceOwnerAndCurrentOwnerIsReread() {
    MonoEffect replacement = new MonoEffect();
    replacement.isActive = true;
    world.onVisible = () -> behavior.ownerMonoBehavior = replacement;
    RogueEffectActive46.setActive(behavior, false, world);
    assertEquals(List.of(owner, replacement), world.owners);
    assertFalse(owner.isActive);
    assertTrue(replacement.isActive);
  }

  @Test
  void firstGameObjectMayReplaceOwnerButVisibilityStillRunsBeforeOwnerIsReread() {
    world.onGameObject = () -> behavior.ownerMonoBehavior = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectActive46.setActive(behavior, false, world));
    assertFalse(owner.isActive);
    assertEquals(List.of("object", "visible"), world.calls);
  }

  @Test
  void clearedOwnerAfterVisibilityStopsBeforeSecondGameObject() {
    world.onVisible = () -> behavior.ownerMonoBehavior = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectActive46.setActive(behavior, false, world));
    assertEquals(1, world.owners.size());
    assertFalse(owner.isActive);
  }

  @Test
  void nullSecondGameObjectIsRequiredBeforePluginType() {
    world.second = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectActive46.setActive(behavior, false, world));
    assertEquals(List.of("object", "visible", "object"), world.calls);
  }

  @Test
  void secondGameObjectDoesNotReceiveExtraCachedPointerValidation() {
    world.second.cachedPointer = 0;
    RogueEffectActive46.setActive(behavior, false, world);
    assertSame(world.second, world.seenObject);
    assertEquals("enable", world.calls.getLast());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void missingOrInvalidCharaPluginReturnsWithoutEnable(int which) {
    if (which == 0) world.plugin = null;
    else world.plugin.cachedPointer = 0;
    RogueEffectActive46.setActive(behavior, false, world);
    assertEquals(List.of("object", "visible", "object", "type", "plugin"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"object", "visible", "type", "plugin", "enable"})
  void bindingFailureKeepsPublishedStateAndDoesNotRetry(String failAt) {
    world.failAt = failAt;
    world.failure = new IllegalStateException(failAt);
    assertSame(
        world.failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueEffectActive46.setActive(behavior, false, world)));
    assertFalse(owner.isActive);
    assertEquals(failAt, world.calls.getLast());
    assertEquals(1, world.calls.stream().filter(failAt::equals).count());
  }

  @Test
  void actualModifierAndSkillHidingShareActualActivationAndOnlyFirstChangeRunsVisibility() {
    var modifier = RogueMutexAttach46Test.state();
    modifier.effectHide = true;
    modifier.effectAutoHideInSkill = true;
    var effect = new RogueMutexAttach46.Effect();
    effect.autoHideInSkill = true;
    effect.instanceId = 17;
    effect.instance = owner;
    owner.instanceId = 17;
    owner.cachedPointer = 7;
    owner.triggerParams = new RogueMutexAttach46.TriggerParameters();
    owner.triggerParams.aliveState = 3;
    owner.behavior = behavior;
    var hiding =
        new RogueEffectHide46.World() {
          public void setActive(Behavior b, boolean a) {
            RogueEffectActive46.setActive(b, a, world);
          }

          public RogueEffectHide46.SkillClosure allocateClosure() {
            return new RogueEffectHide46.SkillClosure();
          }

          public Object componentType() {
            return null;
          }

          public RogueEffectHide46.TimerComponent findComponent(MonoEffect i, Object t) {
            return null;
          }

          public RogueEffectHide46.Callback allocateCallback() {
            fail("not waiting");
            return null;
          }

          public RogueEffectHide46.Binding callbackBinding() {
            fail("not waiting");
            return null;
          }

          public void schedule(Object t, RogueEffectHide46.Callback c, float v, int z, boolean f) {
            fail("not waiting");
          }
        };
    RogueEffectHide46.setup(modifier, effect, hiding);
    RogueEffectHide46.tryHide(modifier, effect, false, hiding);
    assertEquals(640, behavior.hideReason);
    assertFalse(owner.isActive);
    assertEquals(List.of("object", "visible", "object", "type", "plugin", "enable"), world.calls);
  }
}
