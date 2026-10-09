package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hkrpg.server.gameplay.RogueBaseOtherModifierEvents46.FadeFinished;
import org.hkrpg.server.gameplay.RogueOwnedParameterLists46.References;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueAttachEffectFade46Test {
  static class World implements RogueAttachEffectFade46.World {
    List<String> calls = new ArrayList<>();
    Object firstOwner = new Object(), secondOwner = firstOwner;
    Object firstEntity = new Object(), secondEntity = firstEntity;
    Object gameWorld = new Object(), manager = new Object(), pool = new Object();
    Object projected, delivered, capturedManager, recycled;
    FadeFinished event = event(3, 2, 9);
    int ownerCalls, entityCalls, capacity;
    Runnable acquireAction = () -> {}, clearAction = () -> {}, capacityAction = () -> {};
    Runnable dispatchAction = () -> {}, recycleAction = () -> {};
    boolean castMatches = true;

    public Object ownerAbilityComponent(RogueModifierDestroy46.State state) {
      calls.add("owner");
      return ownerCalls++ == 0 ? firstOwner : secondOwner;
    }

    public Object ownerEntity(Object component) {
      calls.add("entity");
      return entityCalls++ == 0 ? firstEntity : secondEntity;
    }

    public Object entityWorld(Object entity) {
      calls.add("world");
      return gameWorld;
    }

    public Object worldEventManager(Object world) {
      calls.add("manager");
      return manager;
    }

    public FadeFinished acquireEvent() {
      calls.add("acquire");
      acquireAction.run();
      return event;
    }

    public void clear(Object[] storage, int start, int count) {
      calls.add("clear");
      Arrays.fill(storage, start, start + count, null);
      clearAction.run();
    }

    public void setCapacity(References list, int value) {
      calls.add("capacity");
      capacity = value;
      list.entries = Arrays.copyOf(list.entries, value);
      capacityAction.run();
    }

    public Object runtimeEvent(FadeFinished value) {
      calls.add("cast");
      projected = value;
      return castMatches ? value : null;
    }

    public void dispatch(Object target, Object value) {
      calls.add("dispatch");
      capturedManager = target;
      delivered = value;
      dispatchAction.run();
    }

    public Object recyclePool(FadeFinished value) {
      calls.add("pool");
      return pool;
    }

    public void recycle(Object target, FadeFinished value) {
      assertSame(pool, target);
      calls.add("recycle");
      recycled = value;
      recycleAction.run();
    }
  }

  static FadeFinished event(int capacity, int count, int version) {
    Object[] storage = new Object[capacity];
    Arrays.fill(storage, new Object());
    return new FadeFinished(new Object(), new References(storage, count, version), new Object());
  }

  static References effects(FadeFinished event) {
    return (References) event.effects;
  }

  static RogueModifierDestroy46.State state() {
    return RogueModifierDestroy46Test.state();
  }

  @Test
  void nullInitializationFailsBeforeClear() {
    World world = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.initialize(null, null, null, null, world));
    assertTrue(world.calls.isEmpty());
  }

  @Test
  void unmatchedListKeepsSourceWriteAndOldModifier() {
    FadeFinished event = event(0, 0, 0);
    Object old = event.modifier, source = new Object();
    event.effects = new Object();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.initialize(event, source, null, null, null));
    assertSame(source, event.sourceEntity);
    assertSame(old, event.modifier);
  }

  @Test
  void clearThenSingleAppendKeepsStorageAndWritesModifierLast() {
    World world = new World();
    FadeFinished event = world.event;
    Object[] storage = effects(event).entries;
    Object effect = new Object(), modifier = new Object(), source = new Object();
    assertSame(event, RogueAttachEffectFade46.initialize(event, source, effect, modifier, world));
    assertSame(storage, effects(event).entries);
    assertSame(effect, storage[0]);
    assertNull(storage[1]);
    assertNotNull(storage[2]);
    assertEquals(1, effects(event).size);
    assertEquals(11, effects(event).version);
    assertSame(source, event.sourceEntity);
    assertSame(modifier, event.modifier);
    assertEquals(List.of("clear"), world.calls);
  }

  @Test
  void nullEffectIsARealSingleEntryAndNullSourceModifierRemainAllowed() {
    FadeFinished event = event(1, 0, 0);
    RogueAttachEffectFade46.initialize(event, null, null, null, null);
    assertEquals(1, effects(event).size);
    assertNull(effects(event).entries[0]);
    assertNull(event.sourceEntity);
    assertNull(event.modifier);
    assertEquals(2, effects(event).version);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void nonpositiveOldCountSkipsClearButBothVersionsAdvance(int oldCount) {
    FadeFinished event = event(1, oldCount, 7);
    RogueAttachEffectFade46.initialize(event, null, new Object(), null, null);
    assertEquals(1, effects(event).size);
    assertEquals(9, effects(event).version);
  }

  @Test
  void versionUsesIntWrapTwice() {
    FadeFinished event = event(1, 0, Integer.MAX_VALUE);
    RogueAttachEffectFade46.initialize(event, null, null, null, null);
    assertEquals(Integer.MIN_VALUE + 1, effects(event).version);
  }

  @Test
  void missingClearBindingRetainsResetAndFirstVersion() {
    FadeFinished event = event(1, 1, 7);
    Object old = event.modifier, source = new Object();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.initialize(event, source, null, null, null));
    assertEquals(0, effects(event).size);
    assertEquals(8, effects(event).version);
    assertSame(old, event.modifier);
    assertSame(source, event.sourceEntity);
  }

  @Test
  void clearFailureRetainsPrefixAndDoesNotWriteModifier() {
    World world = new World();
    Object old = world.event.modifier;
    world.clearAction =
        () -> {
          throw new IllegalStateException("clear");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueAttachEffectFade46.initialize(world.event, null, null, null, world));
    assertNull(effects(world.event).entries[0]);
    assertEquals(0, effects(world.event).size);
    assertEquals(10, effects(world.event).version);
    assertSame(old, world.event.modifier);
  }

  @Test
  void nullStorageIsPassedToClearAfterReset() {
    World world = new World();
    effects(world.event).entries = null;
    assertThrows(
        NullPointerException.class,
        () -> RogueAttachEffectFade46.initialize(world.event, null, null, null, world));
    assertEquals(List.of("clear"), world.calls);
    assertEquals(0, effects(world.event).size);
    assertEquals(10, effects(world.event).version);
  }

  @Test
  void oversizedOldCountFailsAfterResetAsFiniteProjectionPolicy() {
    FadeFinished event = event(4097, 4097, 5);
    Object old = event.modifier;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.initialize(event, null, null, null, new World()));
    assertEquals(0, effects(event).size);
    assertEquals(6, effects(event).version);
    assertSame(old, event.modifier);
  }

  @Test
  void clearCanReplaceListAndReplacementCountControlsAppend() {
    World world = new World();
    References old = effects(world.event), replacement = new References(new Object[3], 1, 20);
    Object value = new Object();
    world.clearAction = () -> world.event.effects = replacement;
    RogueAttachEffectFade46.initialize(world.event, null, value, null, world);
    assertEquals(10, old.version);
    assertEquals(0, old.size);
    assertEquals(21, replacement.version);
    assertEquals(2, replacement.size);
    assertSame(value, replacement.entries[1]);
    assertNull(replacement.entries[0]);
  }

  @Test
  void clearReplacingListWithNullFailsBeforeSecondVersion() {
    World world = new World();
    References old = effects(world.event);
    world.clearAction = () -> world.event.effects = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.initialize(world.event, null, null, null, world));
    assertEquals(10, old.version);
    assertEquals(0, old.size);
  }

  @Test
  void missingStorageAfterClearKeepsBothVersionsAndReset() {
    World world = new World();
    world.clearAction = () -> effects(world.event).entries = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.initialize(world.event, null, null, null, world));
    assertEquals(11, effects(world.event).version);
    assertEquals(0, effects(world.event).size);
  }

  @Test
  void emptyCapacityGrowsToFourWithoutExtraVersion() {
    World world = new World();
    FadeFinished event = event(0, 0, 8);
    Object value = new Object();
    RogueAttachEffectFade46.initialize(event, null, value, null, world);
    assertEquals(4, world.capacity);
    assertEquals(4, effects(event).entries.length);
    assertEquals(10, effects(event).version);
    assertSame(value, effects(event).entries[0]);
  }

  @Test
  void resizeSetterFailureKeepsBothVersionsAndOldModifier() {
    World world = new World();
    FadeFinished event = event(0, 0, 8);
    Object old = event.modifier;
    world.capacityAction =
        () -> {
          throw new IllegalStateException("capacity");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueAttachEffectFade46.initialize(event, null, null, null, world));
    assertEquals(10, effects(event).version);
    assertEquals(0, effects(event).size);
    assertEquals(4, effects(event).entries.length);
    assertSame(old, event.modifier);
  }

  @Test
  void resizeCapturedIndexSurvivesSetterChangingSize() {
    World world = new World();
    References list = new References(new Object[2], 2, 5);
    Object value = new Object();
    world.capacityAction = () -> list.size = 99;
    RogueAttachEffectFade46.addWithResize(list, value, world);
    assertEquals(4, world.capacity);
    assertEquals(3, list.size);
    assertEquals(5, list.version);
    assertSame(value, list.entries[2]);
  }

  @Test
  void resizeMinimumCapacityUsesCapturedNextCount() {
    World world = new World();
    References list = new References(new Object[1], 5, 0);
    RogueAttachEffectFade46.addWithResize(list, null, world);
    assertEquals(6, world.capacity);
    assertEquals(6, list.size);
  }

  @Test
  void setterDroppingStoragePublishesCountBeforeFailure() {
    World world = new World();
    References list = new References(new Object[0], 0, 9);
    world.capacityAction = () -> list.entries = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.addWithResize(list, null, world));
    assertEquals(1, list.size);
    assertEquals(9, list.version);
  }

  @Test
  void setterShrinkingStoragePublishesCountBeforeBoundsFailure() {
    World world = new World();
    References list = new References(new Object[0], 0, 9);
    world.capacityAction = () -> list.entries = new Object[0];
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.addWithResize(list, null, world));
    assertEquals(1, list.size);
  }

  @Test
  void negativeResizeIndexPublishesNextBeforeUnsignedFailure() {
    References list = new References(new Object[2], -1, 3);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.addWithResize(list, null, null));
    assertEquals(0, list.size);
    assertEquals(3, list.version);
  }

  @Test
  void missingResizeBindingDoesNotPublishNext() {
    References list = new References(new Object[0], 0, 3);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.addWithResize(list, null, null));
    assertEquals(0, list.size);
    assertEquals(3, list.version);
  }

  @Test
  void finiteGrowthBoundDoesNotAllocateOrPublish() {
    World world = new World();
    References list = new References(new Object[4096], 4096, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.addWithResize(list, null, world));
    assertEquals(4096, list.size);
    assertTrue(world.calls.isEmpty());
  }

  @Test
  void postWithoutManagerOrImmediateReturnsSameEventWithoutWorld() {
    FadeFinished event = event(1, 0, 0);
    assertSame(event, RogueAttachEffectFade46.post(event, false, null));
  }

  @Test
  void ordinaryPostDispatchesThenReturnsWithoutRecyclingOrClearingManager() {
    World world = new World();
    world.event.eventManager = world.manager;
    assertSame(world.event, RogueAttachEffectFade46.post(world.event, false, world));
    assertEquals(List.of("cast", "dispatch"), world.calls);
    assertSame(world.event, world.delivered);
    assertSame(world.manager, world.event.eventManager);
  }

  @Test
  void failedRuntimeInterfaceCastDispatchesNullThenRecyclesOriginalEvent() {
    World world = new World();
    world.event.eventManager = world.manager;
    world.castMatches = false;
    assertNull(RogueAttachEffectFade46.post(world.event, true, world));
    assertEquals(List.of("cast", "dispatch", "pool", "recycle"), world.calls);
    assertNull(world.delivered);
    assertSame(world.event, world.projected);
    assertSame(world.event, world.recycled);
  }

  @Test
  void immediateWithoutManagerStillRecycles() {
    World world = new World();
    assertNull(RogueAttachEffectFade46.post(world.event, true, world));
    assertEquals(List.of("pool", "recycle"), world.calls);
  }

  @Test
  void dispatchFailureStopsRecycleAndKeepsMutation() {
    World world = new World();
    world.event.eventManager = world.manager;
    world.dispatchAction =
        () -> {
          world.event.modifier = null;
          throw new IllegalStateException("dispatch");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueAttachEffectFade46.post(world.event, true, world));
    assertEquals(List.of("cast", "dispatch"), world.calls);
    assertNull(world.event.modifier);
  }

  @Test
  void dispatchReplacementManagerDoesNotChangeCapturedTarget() {
    World world = new World();
    Object old = world.manager;
    world.event.eventManager = old;
    world.dispatchAction = () -> world.event.eventManager = new Object();
    RogueAttachEffectFade46.post(world.event, true, world);
    assertSame(old, world.capturedManager);
    assertNotSame(old, world.event.eventManager);
  }

  @Test
  void nullPoolFailsAfterDispatchBeforeRecycle() {
    World world = new World();
    world.event.eventManager = world.manager;
    world.pool = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.post(world.event, true, world));
    assertEquals(List.of("cast", "dispatch", "pool"), world.calls);
  }

  @Test
  void recycleFailureKeepsDispatchedAndRecycledPrefix() {
    World world = new World();
    world.event.eventManager = world.manager;
    world.recycleAction =
        () -> {
          throw new IllegalStateException("recycle");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueAttachEffectFade46.post(world.event, true, world));
    assertSame(world.event, world.recycled);
    assertSame(world.event, world.delivered);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void firstOwnerEntityWorldOrManagerMissingReturnsBeforeAcquire(int missing) {
    World world = new World();
    if (missing == 0) world.firstOwner = null;
    if (missing == 1) world.firstEntity = null;
    if (missing == 2) world.gameWorld = null;
    if (missing == 3) world.manager = null;
    RogueAttachEffectFade46.onAttachEffectFadeFinished(state(), new Object(), world);
    assertEquals(missing + 1, world.calls.size());
    assertEquals(0, world.entityCalls > 0 && missing == 0 ? 1 : 0);
  }

  @Test
  void nullCallbackReceiverFailsBeforeBindings() {
    World world = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.onAttachEffectFadeFinished(null, null, world));
    assertTrue(world.calls.isEmpty());
  }

  @Test
  void nullAcquisitionFailsBeforeSecondOwnerRead() {
    World world = new World();
    world.event = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.onAttachEffectFadeFinished(state(), null, world));
    assertEquals(1, world.ownerCalls);
    assertEquals(List.of("owner", "entity", "world", "manager", "acquire"), world.calls);
  }

  @Test
  void missingSecondOwnerFailsAfterManagerWrite() {
    World world = new World();
    world.secondOwner = null;
    Object old = world.event.modifier;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAttachEffectFade46.onAttachEffectFadeFinished(state(), null, world));
    assertSame(world.manager, world.event.eventManager);
    assertSame(old, world.event.modifier);
    assertEquals(1, world.entityCalls);
  }

  @Test
  void callbackCapturesManagerThenUsesCurrentSecondEntityAndOneEffect() {
    World world = new World();
    Object firstManager = world.manager, entity = new Object(), effect = new Object();
    var state = state();
    world.secondOwner = new Object();
    world.secondEntity = entity;
    world.acquireAction = () -> world.manager = new Object();
    RogueAttachEffectFade46.onAttachEffectFadeFinished(state, effect, world);
    assertSame(firstManager, world.capturedManager);
    assertSame(entity, world.event.sourceEntity);
    assertSame(state, world.event.modifier);
    assertSame(effect, effects(world.event).entries[0]);
    assertEquals(1, effects(world.event).size);
    assertSame(world.event, world.recycled);
    assertEquals(
        List.of(
            "owner",
            "entity",
            "world",
            "manager",
            "acquire",
            "owner",
            "entity",
            "clear",
            "cast",
            "dispatch",
            "pool",
            "recycle"),
        world.calls);
  }

  @Test
  void missingSecondEntityIsInitializedAndDispatchedAsNullSource() {
    World world = new World();
    world.secondEntity = null;
    RogueAttachEffectFade46.onAttachEffectFadeFinished(state(), null, world);
    assertNull(world.event.sourceEntity);
    assertSame(world.event, world.recycled);
  }

  @Test
  void callbackDoesNotGateOnModifierActiveState() {
    World world = new World();
    var state = state();
    state.cleanup.candidate.state = 3;
    RogueAttachEffectFade46.onAttachEffectFadeFinished(state, null, world);
    assertSame(state, world.event.modifier);
    assertSame(world.event, world.recycled);
  }

  @Test
  void callbackInitializesActualPayloadThenOtherCallbackSeesSameListAndTrueFlag() {
    World world = new World();
    var source = state();
    var receiver = state();
    receiver.cleanup.candidate.state = 1;
    var listener = new RogueBaseOtherModifierEvents46Test.World();
    Object effect = new Object();
    world.dispatchAction =
        () ->
            RogueBaseOtherModifierEvents46.onOtherFadeFinished(receiver, world.delivered, listener);
    RogueAttachEffectFade46.onAttachEffectFadeFinished(source, effect, world);
    assertEquals(List.of(true), listener.flags);
    assertSame(world.event.effects, listener.effects.getFirst());
    assertSame(effect, effects(world.event).entries[0]);
    assertSame(world.event, world.recycled);
  }

  @Test
  void otherCallbackFailureStopsPoolLookupAndPreservesPayload() {
    World world = new World();
    var receiver = state();
    receiver.cleanup.candidate.state = 1;
    var listener = new RogueBaseOtherModifierEvents46Test.World();
    listener.action =
        () -> {
          receiver.cleanup.candidate.state = 2;
          throw new IllegalStateException("effect");
        };
    world.dispatchAction =
        () ->
            RogueBaseOtherModifierEvents46.onOtherFadeFinished(receiver, world.delivered, listener);
    assertThrows(
        IllegalStateException.class,
        () -> RogueAttachEffectFade46.onAttachEffectFadeFinished(state(), null, world));
    assertFalse(world.calls.contains("pool"));
    assertEquals(1, effects(world.event).size);
    assertEquals(2, receiver.cleanup.candidate.state);
    assertNull(world.recycled);
  }
}
