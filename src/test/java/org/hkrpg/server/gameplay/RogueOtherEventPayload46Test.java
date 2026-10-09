package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hkrpg.server.gameplay.RogueBaseOtherModifierEvents46.Destroyed;
import org.hkrpg.server.gameplay.RogueBaseOtherModifierEvents46.FadeFinished;
import org.hkrpg.server.gameplay.RogueOwnedParameterLists46.References;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueOtherEventPayload46Test {
  static class World implements RogueOtherEventPayload46.World {
    List<String> calls = new ArrayList<>();
    References allocated = new References(new Object[3], 0, 0), inserted;
    Object insertedSource, queriedEntity;
    int insertionIndex, id;
    Runnable construction = () -> {},
        clearAction = () -> {},
        insertAction = () -> {},
        query = () -> {};

    public References allocateEffects() {
      calls.add("allocate");
      return allocated;
    }

    public void initializeEffects(References list) {
      calls.add("initialize");
      construction.run();
    }

    public void clear(Object[] storage, int start, int count) {
      calls.add("clear");
      Arrays.fill(storage, start, start + count, null);
      clearAction.run();
    }

    public void insertRange(References list, int index, Object source) {
      calls.add("insert");
      inserted = list;
      insertedSource = source;
      insertionIndex = index;
      insertAction.run();
    }

    public int runtimeId(Object entity) {
      calls.add("id");
      queriedEntity = entity;
      query.run();
      return id;
    }
  }

  static Object event(boolean fade) {
    return fade
        ? new FadeFinished(new Object(), new References(new Object[3], 2, 5), new Object())
        : new Destroyed(new Object(), new References(new Object[3], 2, 5), new Object());
  }

  static Object effects(Object event) {
    return event instanceof FadeFinished e ? e.effects : ((Destroyed) event).effects;
  }

  static void effects(Object event, Object value) {
    if (event instanceof FadeFinished e) e.effects = value;
    else ((Destroyed) event).effects = value;
  }

  static Object modifier(Object event) {
    return event instanceof FadeFinished e ? e.modifier : ((Destroyed) event).modifier;
  }

  static Object source(Object event) {
    return event instanceof FadeFinished e ? e.sourceEntity : ((Destroyed) event).sourceEntity;
  }

  static void source(Object event, Object value) {
    if (event instanceof FadeFinished e) e.sourceEntity = value;
    else ((Destroyed) event).sourceEntity = value;
  }

  static Object initialize(
      boolean fade, Object event, Object entity, Object range, Object modifier, World world) {
    return fade
        ? RogueOtherEventPayload46.initialize((FadeFinished) event, entity, range, modifier, world)
        : RogueOtherEventPayload46.initialize((Destroyed) event, entity, range, modifier, world);
  }

  static void construct(boolean fade, Object event, World world) {
    if (fade) RogueOtherEventPayload46.construct((FadeFinished) event, world);
    else RogueOtherEventPayload46.construct((Destroyed) event, world);
  }

  static int id(boolean fade, Object event, World world) {
    return fade
        ? RogueOtherEventPayload46.sourceRuntimeId((FadeFinished) event, world)
        : RogueOtherEventPayload46.sourceRuntimeId((Destroyed) event, world);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void constructorPublishesOnlyEffectsAfterInitializerAndKeepsOtherFields(boolean fade) {
    var world = new World();
    Object event = event(fade),
        oldEffects = effects(event),
        oldModifier = modifier(event),
        oldSource = source(event);
    world.construction = () -> assertSame(oldEffects, effects(event));
    construct(fade, event, world);
    assertSame(world.allocated, effects(event));
    assertSame(oldModifier, modifier(event));
    assertSame(oldSource, source(event));
    assertEquals(List.of("allocate", "initialize"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nullConstructorReceiverStillAllocatesAndInitializesBeforeFinalWriteFailure(boolean fade) {
    var world = new World();
    assertThrows(IllegalArgumentException.class, () -> construct(fade, null, world));
    assertEquals(List.of("allocate", "initialize"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nullAllocationStillPassesInitializerAndPublishesNull(boolean fade) {
    var world = new World();
    world.allocated = null;
    Object event = event(fade);
    construct(fade, event, world);
    assertNull(effects(event));
    assertEquals(List.of("allocate", "initialize"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void constructorFailureKeepsInitializerPrefixWithoutPublishingAllocated(boolean fade) {
    var world = new World();
    Object event = event(fade), changed = new Object();
    world.construction =
        () -> {
          effects(event, changed);
          throw new IllegalStateException("ctor");
        };
    assertThrows(IllegalStateException.class, () -> construct(fade, event, world));
    assertSame(changed, effects(event));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nullInitializerReceiverFailsBeforeBindings(boolean fade) {
    var world = new World();
    assertThrows(
        IllegalArgumentException.class, () -> initialize(fade, null, null, null, null, world));
    assertTrue(world.calls.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sourceWritePrecedesUnmatchedListFailureAndKeepsModifier(boolean fade) {
    var world = new World();
    Object event = event(fade), changed = new Object(), old = modifier(event);
    effects(event, new Object());
    assertThrows(
        IllegalArgumentException.class, () -> initialize(fade, event, changed, null, null, world));
    assertSame(changed, source(event));
    assertSame(old, modifier(event));
    assertTrue(world.calls.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void clearResetsCountAndAdvancesOneVersionBeforeRangeBinding(boolean fade) {
    var world = new World();
    Object event = event(fade), range = new Object(), replacement = new Object();
    References list = (References) effects(event);
    world.insertAction =
        () -> {
          assertEquals(0, list.size);
          assertEquals(6, list.version);
          list.size = 3;
          list.version = 10;
        };
    assertSame(event, initialize(fade, event, null, range, replacement, world));
    assertSame(range, world.insertedSource);
    assertEquals(0, world.insertionIndex);
    assertSame(replacement, modifier(event));
    assertEquals(10, list.version);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void clearReplacingPayloadListUsesReplacementCurrentIndexAndVersion(boolean fade) {
    var world = new World();
    Object event = event(fade);
    References old = (References) effects(event),
        replacement = new References(new Object[4], 1, 20);
    world.clearAction = () -> effects(event, replacement);
    initialize(fade, event, null, null, null, world);
    assertSame(replacement, world.inserted);
    assertEquals(1, world.insertionIndex);
    assertEquals(20, replacement.version);
    assertEquals(6, old.version);
    assertEquals(0, old.size);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void clearFailureKeepsSourceResetVersionAndOldModifier(boolean fade) {
    var world = new World();
    Object event = event(fade), entity = new Object(), old = modifier(event);
    References list = (References) effects(event);
    world.clearAction =
        () -> {
          throw new IllegalStateException("clear");
        };
    assertThrows(
        IllegalStateException.class, () -> initialize(fade, event, entity, null, null, world));
    assertSame(entity, source(event));
    assertEquals(0, list.size);
    assertEquals(6, list.version);
    assertSame(old, modifier(event));
    assertFalse(world.calls.contains("insert"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void insertionFailureKeepsItsWritesWithoutModifierLastWrite(boolean fade) {
    var world = new World();
    Object event = event(fade), old = modifier(event);
    References list = (References) effects(event);
    world.insertAction =
        () -> {
          list.size = 1;
          list.version = 9;
          throw new IllegalStateException("insert");
        };
    assertThrows(
        IllegalStateException.class, () -> initialize(fade, event, null, null, null, world));
    assertEquals(1, list.size);
    assertEquals(9, list.version);
    assertSame(old, modifier(event));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void emptyOldListSkipsClearButStillAdvancesOneVersionAndForwardsNullRange(boolean fade) {
    var world = new World();
    Object event = event(fade);
    References list = (References) effects(event);
    list.size = 0;
    initialize(fade, event, null, null, null, world);
    assertEquals(List.of("insert"), world.calls);
    assertNull(world.insertedSource);
    assertEquals(6, list.version);
    assertNull(modifier(event));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void negativeOldCountIsResetAndVersionWrapsBeforeRange(boolean fade) {
    var world = new World();
    Object event = event(fade);
    References list = (References) effects(event);
    list.size = -1;
    list.version = Integer.MAX_VALUE;
    initialize(fade, event, null, null, null, world);
    assertEquals(0, world.insertionIndex);
    assertEquals(Integer.MIN_VALUE, list.version);
    assertEquals(List.of("insert"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nullSourceEntityRejectedByQueryBeforeWorld(boolean fade) {
    var world = new World();
    Object event = event(fade);
    source(event, null);
    assertThrows(IllegalArgumentException.class, () -> id(fade, event, world));
    assertTrue(world.calls.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void queryPassesCurrentEntityAndKeepsRawUnsignedBits(boolean fade) {
    var world = new World();
    world.id = -1;
    Object event = event(fade), entity = new Object();
    source(event, entity);
    assertEquals(-1, id(fade, event, world));
    assertSame(entity, world.queriedEntity);
    assertEquals(List.of("id"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void queryCapturesEntityBeforeBindingChangesPayloadEntity(boolean fade) {
    var world = new World();
    Object event = event(fade), old = source(event);
    world.query = () -> source(event, null);
    id(fade, event, world);
    assertSame(old, world.queriedEntity);
    assertNull(source(event));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ordinaryConstantEventTypeDoesNotReadNullableReceiver(boolean fade) {
    assertEquals(
        fade ? 208 : 207,
        fade
            ? RogueOtherEventPayload46.eventType((FadeFinished) null)
            : RogueOtherEventPayload46.eventType((Destroyed) null));
  }

  @Test
  void actualFadePayloadQueriesDriveRealDictionaryAndOtherEventCallback() {
    var payloadWorld = new World();
    payloadWorld.id = 7;
    var payload = (FadeFinished) event(true);
    var receiver = RogueModifierDestroy46Test.state();
    receiver.cleanup.candidate.state = 1;
    var listener = new RogueBaseOtherModifierEvents46Test.World();
    var state =
        RogueEventFire46Test.state(
            (Runnable)
                () ->
                    RogueBaseOtherModifierEvents46.onOtherFadeFinished(
                        receiver, payload, listener));
    var world =
        new RogueEventFire46Test.World() {
          public int sourceRuntimeId(Object event) {
            return RogueOtherEventPayload46.sourceRuntimeId((FadeFinished) event, payloadWorld);
          }

          public int eventType(Object event) {
            return RogueOtherEventPayload46.eventType((FadeFinished) event);
          }

          public int findEntryIndex(
              org.hkrpg.server.gameplay.RogueEventListenerWithdrawal46.Dictionary dictionary,
              org.hkrpg.server.gameplay.RogueEventListenerWithdrawal46.Key key) {
            return RogueEventDictionaryLookup46.find(dictionary, key, null);
          }
        };
    RogueEventFire46.fire(state, payload, world);
    assertEquals(List.of(true), listener.flags);
    assertSame(payload.effects, listener.effects.getFirst());
    assertSame(payload.sourceEntity, payloadWorld.queriedEntity);
    assertEquals(1, state.executeListPool.count);
  }

  @Test
  void actualFireNullSourceFailsBeforeDictionaryOrExecutePool() {
    var payloadWorld = new World();
    var payload = (Destroyed) event(false);
    payload.sourceEntity = null;
    var state = RogueEventFire46Test.state();
    state.entityEvents = null;
    state.executeListPool = null;
    var world =
        new RogueEventFire46Test.World() {
          public int sourceRuntimeId(Object event) {
            return RogueOtherEventPayload46.sourceRuntimeId((Destroyed) event, payloadWorld);
          }

          public int eventType(Object event) {
            fail("Source ID fails before type query");
            return 207;
          }
        };
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventFire46.fire(state, payload, world));
    assertTrue(world.calls.isEmpty());
    assertTrue(payloadWorld.calls.isEmpty());
  }

  @Test
  void defaultListConstructorPublishesSharedArrayWithoutResettingSizeOrVersion() {
    Object[] empty = new Object[0];
    var list = new References(new Object[3], -7, 41);
    RogueOtherEventPayload46.constructEffects(list, () -> empty);
    assertSame(empty, list.entries);
    assertEquals(-7, list.size);
    assertEquals(41, list.version);
  }

  @Test
  void defaultListConstructorRequiresReceiverBeforeSharedArrayBinding() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueOtherEventPayload46.constructEffects(
                null,
                () -> {
                  fail("receiver guard precedes shared array binding");
                  return null;
                }));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueOtherEventPayload46.constructEffects(null, null));
  }

  @Test
  void defaultListConstructorFailureKeepsBindingMutationsWithoutPublishing() {
    Object[] changed = new Object[1];
    var list = new References(new Object[3], 0, 2);
    var failure = new IllegalStateException("shared empty class initialization");
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                RogueOtherEventPayload46.constructEffects(
                    list,
                    () -> {
                      list.entries = changed;
                      list.size = 4;
                      list.version = 7;
                      throw failure;
                    })));
    assertSame(changed, list.entries);
    assertEquals(4, list.size);
    assertEquals(7, list.version);
  }

  @Test
  void defaultListConstructorPermitsNullSharedStorage() {
    var list = new References(new Object[1], 0, 3);
    RogueOtherEventPayload46.constructEffects(list, () -> null);
    assertNull(list.entries);
    assertEquals(3, list.version);
  }

  @Test
  void actualPayloadConstructorUsesActualDefaultListConstructor() {
    Object[] shared = new Object[0];
    var world =
        new World() {
          public void initializeEffects(References list) {
            calls.add("initialize");
            RogueOtherEventPayload46.constructEffects(list, () -> shared);
          }
        };
    var payload = (FadeFinished) event(true);
    RogueOtherEventPayload46.construct(payload, world);
    assertSame(world.allocated, payload.effects);
    assertSame(shared, ((References) payload.effects).entries);
    assertEquals(List.of("allocate", "initialize"), world.calls);
  }
}
