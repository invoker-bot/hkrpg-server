package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hkrpg.server.gameplay.RogueEventListenerWithdrawal46.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEventFire46Test {
  static class World implements RogueEventFire46.World {
    List<String> calls = new ArrayList<>();
    List<Object> invoked = new ArrayList<>(), delivered = new ArrayList<>();
    List<Dictionary> queried = new ArrayList<>();
    List<Key> keys = new ArrayList<>();
    int source = 7, type = 208, lookupCount, elementCount;
    int[] results = {0, 0};
    PooledCallbacks allocated = callbacks(), popped, copiedSource, lastExecution;
    RogueEventFire46.ExecuteQueue releasedQueue;
    Runnable sourceAction = () -> {}, typeAction = () -> {}, firstQuery = () -> {};
    Runnable secondQuery = () -> {}, constructor = () -> {}, copyAction = () -> {};
    Runnable elementAction = () -> {}, invokeAction = () -> {}, clearAction = () -> {};

    public int sourceRuntimeId(Object event) {
      calls.add("source");
      sourceAction.run();
      return source;
    }

    public int eventType(Object event) {
      calls.add("type");
      typeAction.run();
      return type;
    }

    public int findEntryIndex(Dictionary dictionary, Key key) {
      calls.add("find");
      queried.add(dictionary);
      keys.add(key);
      int position = lookupCount++;
      if (position == 0) firstQuery.run();
      else secondQuery.run();
      return results[Math.min(position, results.length - 1)];
    }

    @SuppressWarnings("unchecked")
    public PooledCallbacks dequeue(RogueEventFire46.ExecuteQueue queue) {
      calls.add("dequeue");
      queue.count--;
      return popped != null ? popped : ((ArrayDeque<PooledCallbacks>) queue.context).removeFirst();
    }

    public PooledCallbacks allocateExecuteList() {
      calls.add("allocate");
      return allocated;
    }

    public void initializeExecuteList(PooledCallbacks list) {
      calls.add("initialize");
      constructor.run();
    }

    public void copyCallbacks(PooledCallbacks destination, PooledCallbacks source) {
      calls.add("copy");
      copiedSource = source;
      lastExecution = destination;
      objects(destination).addAll(objects(source));
      copyAction.run();
    }

    public int callbackCount(PooledCallbacks list) {
      calls.add("count");
      return objects(list).size();
    }

    public Object callbackAt(PooledCallbacks list, int index) {
      calls.add("element");
      Object result = objects(list).get(index);
      elementCount++;
      elementAction.run();
      return result;
    }

    public void invokeCallback(Object callback, Object event) {
      calls.add("invoke");
      invoked.add(callback);
      delivered.add(event);
      if (callback instanceof Runnable action) action.run();
      invokeAction.run();
    }

    public void releaseCallbacks(PooledCallbacks list, boolean rawFlag) {
      assertTrue(rawFlag);
      calls.add("clear");
      objects(list).clear();
      clearAction.run();
    }

    @SuppressWarnings("unchecked")
    public void enqueue(RogueEventFire46.ExecuteQueue queue, PooledCallbacks list) {
      calls.add("enqueue");
      releasedQueue = queue;
      ((ArrayDeque<PooledCallbacks>) queue.context).addLast(list);
      queue.count++;
    }
  }

  static PooledCallbacks callbacks(Object... values) {
    var list = new PooledCallbacks();
    list.collection = new ArrayList<>(Arrays.asList(values));
    return list;
  }

  @SuppressWarnings("unchecked")
  static List<Object> objects(PooledCallbacks list) {
    return (List<Object>) list.collection;
  }

  static RogueEventFire46.ExecuteQueue queue(PooledCallbacks... values) {
    var queue = new RogueEventFire46.ExecuteQueue();
    queue.count = values.length;
    queue.context = new ArrayDeque<>(Arrays.asList(values));
    return queue;
  }

  static State state(Object... listeners) {
    var state = new State();
    state.executeListPool = queue();
    state.entityEvents = new Dictionary();
    var entry = new Entry();
    entry.callbacks = callbacks(listeners);
    entry.key = new Key(7, 208);
    entry.hashCode = RogueEventDictionaryLookup46.defaultHash(entry.key);
    entry.next = -1;
    state.entityEvents.entries = new Entry[] {entry};
    state.entityEvents.buckets = new int[] {1};
    return state;
  }

  @Test
  void nullEventFailsBeforeReceiverAndBindings() {
    var world = new World();
    assertThrows(IllegalArgumentException.class, () -> RogueEventFire46.fire(null, null, world));
    assertTrue(world.calls.isEmpty());
  }

  @Test
  void nullReceiverFailsAfterBothEventQueries() {
    var world = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventFire46.fire(null, new Object(), world));
    assertEquals(List.of("source", "type"), world.calls);
  }

  @Test
  void sourceQueryFailureStopsEventTypeAndDictionary() {
    var world = new World();
    world.sourceAction =
        () -> {
          throw new IllegalStateException("source");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueEventFire46.fire(state(), new Object(), world));
    assertEquals(List.of("source"), world.calls);
  }

  @Test
  void typeQueryCanChangeMapBeforeFirstLookup() {
    var world = new World();
    var state = state();
    var replacement = new Dictionary();
    world.results = new int[] {-1};
    world.typeAction = () -> state.entityEvents = replacement;
    RogueEventFire46.fire(state, new Object(), world);
    assertSame(replacement, world.queried.getFirst());
  }

  @Test
  void nullMapFailsAfterEventQueriesBeforeFind() {
    var world = new World();
    var state = state();
    state.entityEvents = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventFire46.fire(state, new Object(), world));
    assertEquals(List.of("source", "type"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, -2, Integer.MIN_VALUE})
  void firstNegativeLookupStopsWithoutQueueOrSecondLookup(int value) {
    var world = new World();
    var state = state();
    state.executeListPool = null;
    world.results = new int[] {value};
    RogueEventFire46.fire(state, new Object(), world);
    assertEquals(1, world.lookupCount);
  }

  @Test
  void firstLookupCanReplaceMapBeforeSecondQuery() {
    var world = new World();
    var state = state();
    Dictionary first = state.entityEvents, second = state().entityEvents;
    world.firstQuery = () -> state.entityEvents = second;
    RogueEventFire46.fire(state, new Object(), world);
    assertEquals(List.of(first, second), world.queried);
    assertSame(second.entries[0].callbacks, world.copiedSource);
  }

  @Test
  void mapClearedByFirstLookupFailsBeforeSecondLookup() {
    var world = new World();
    var state = state();
    world.firstQuery = () -> state.entityEvents = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventFire46.fire(state, new Object(), world));
    assertEquals(1, world.lookupCount);
    assertFalse(world.calls.contains("allocate"));
  }

  @Test
  void secondMissingEntryStopsBeforeQueue() {
    var world = new World();
    world.results = new int[] {0, -1};
    var state = state();
    state.executeListPool = null;
    RogueEventFire46.fire(state, new Object(), world);
    assertEquals(2, world.lookupCount);
  }

  @Test
  void secondLookupUsesCapturedDictionaryButCurrentEntryArray() {
    var world = new World();
    var state = state();
    var captured = state.entityEvents;
    var next = state(new Object()).entityEvents.entries;
    world.secondQuery =
        () -> {
          state.entityEvents = new Dictionary();
          captured.entries = next;
        };
    RogueEventFire46.fire(state, new Object(), world);
    assertSame(next[0].callbacks, world.copiedSource);
    assertEquals(1, world.invoked.size());
  }

  @Test
  void nullCurrentEntriesFailsBeforeQueue() {
    var world = new World();
    var state = state();
    world.secondQuery = () -> state.entityEvents.entries = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventFire46.fire(state, new Object(), world));
    assertFalse(world.calls.contains("allocate"));
  }

  @Test
  void positiveIndexOutsideCurrentArrayIsRejected() {
    var world = new World();
    world.results = new int[] {0, 1};
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventFire46.fire(state(), new Object(), world));
    assertFalse(world.calls.contains("allocate"));
  }

  @Test
  void nullEntryFailsBeforeQueue() {
    var world = new World();
    var state = state();
    state.entityEvents.entries[0] = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventFire46.fire(state, new Object(), world));
    assertFalse(world.calls.contains("allocate"));
  }

  @Test
  void missingQueueFailsAfterTwoQueriesWithoutCopy() {
    var world = new World();
    var state = state();
    state.executeListPool = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventFire46.fire(state, new Object(), world));
    assertEquals(2, world.lookupCount);
    assertFalse(world.calls.contains("copy"));
  }

  @Test
  void pooledExecutionUsesQueueFrontAndReturnsAtBack() {
    var world = new World();
    PooledCallbacks first = callbacks(), second = callbacks();
    var state = state();
    state.executeListPool = queue(first, second);
    Object event = new Object();
    RogueEventFire46.fire(state, event, world);
    assertSame(first, world.lastExecution);
    assertFalse(world.calls.contains("allocate"));
    assertSame(second, RogueEventFire46.getExecuteList(state, world));
    assertSame(first, RogueEventFire46.getExecuteList(state, world));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void nonpositiveQueueCountAllocatesAndInitializes(int count) {
    var world = new World();
    var state = state();
    state.executeListPool.count = count;
    assertSame(world.allocated, RogueEventFire46.getExecuteList(state, world));
    assertEquals(List.of("allocate", "initialize"), world.calls);
  }

  @Test
  void standaloneGetPreservesNullAllocationThroughInitializer() {
    var world = new World();
    world.allocated = null;
    assertNull(RogueEventFire46.getExecuteList(state(), world));
    assertEquals(List.of("allocate", "initialize"), world.calls);
  }

  @Test
  void fireNullAllocationFailsAfterInitializerBeforeCopy() {
    var world = new World();
    world.allocated = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventFire46.fire(state(), new Object(), world));
    assertTrue(world.calls.contains("initialize"));
    assertFalse(world.calls.contains("copy"));
  }

  @Test
  void initializerFailureDoesNotCopyOrReturnQueue() {
    var world = new World();
    world.constructor =
        () -> {
          throw new IllegalStateException("ctor");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueEventFire46.fire(state(), new Object(), world));
    assertFalse(world.calls.contains("copy"));
    assertFalse(world.calls.contains("enqueue"));
  }

  @Test
  void copyFailureKeepsPrefixAndDoesNotReleaseExecution() {
    var world = new World();
    world.copyAction =
        () -> {
          throw new IllegalStateException("copy");
        };
    Object callback = new Object();
    assertThrows(
        IllegalStateException.class,
        () -> RogueEventFire46.fire(state(callback), new Object(), world));
    assertSame(callback, objects(world.lastExecution).getFirst());
    assertFalse(world.calls.contains("clear"));
  }

  @Test
  void emptyCopyStillClearsAndReturnsExecution() {
    var world = new World();
    var state = state();
    RogueEventFire46.fire(state, new Object(), world);
    assertEquals(
        List.of(
            "source",
            "type",
            "find",
            "find",
            "allocate",
            "initialize",
            "copy",
            "count",
            "clear",
            "enqueue"),
        world.calls);
    assertEquals(1, state.executeListPool.count);
  }

  @Test
  void nullCallbackUsesOneReadAndNonNullCallbackUsesTwo() {
    var world = new World();
    Object callback = new Object(), event = new Object();
    RogueEventFire46.fire(state(null, callback), event, world);
    assertEquals(3, world.elementCount);
    assertEquals(List.of(callback), world.invoked);
    assertSame(event, world.delivered.getFirst());
  }

  @Test
  void secondElementReadObservesReplacement() {
    var world = new World();
    Object first = new Object(), replacement = new Object();
    world.elementAction =
        () -> {
          if (world.elementCount == 1) objects(world.lastExecution).set(0, replacement);
        };
    RogueEventFire46.fire(state(first), new Object(), world);
    assertEquals(List.of(replacement), world.invoked);
  }

  @Test
  void elementRemovedBetweenReadsFailsWithoutRelease() {
    var world = new World();
    world.elementAction =
        () -> {
          if (world.elementCount == 1) objects(world.lastExecution).set(0, null);
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventFire46.fire(state(new Object()), new Object(), world));
    assertTrue(world.invoked.isEmpty());
    assertFalse(world.calls.contains("clear"));
  }

  @Test
  void callbackCanShrinkCurrentExecutionToStopNextCallback() {
    var world = new World();
    world.invokeAction = () -> objects(world.lastExecution).clear();
    RogueEventFire46.fire(state(new Object(), new Object()), new Object(), world);
    assertEquals(1, world.invoked.size());
  }

  @Test
  void callbackCanAppendToCurrentExecutionAndNextCountObservesIt() {
    var world = new World();
    Object first = new Object(), second = new Object();
    world.invokeAction =
        () -> {
          if (world.invoked.size() == 1) objects(world.lastExecution).add(second);
        };
    RogueEventFire46.fire(state(first), new Object(), world);
    assertEquals(List.of(first, second), world.invoked);
  }

  @Test
  void listenerAddOrRemoveDuringExecutionDoesNotAlterCopiedCallbacks() {
    var world = new World();
    Object first = new Object(), second = new Object(), added = new Object();
    var state = state(first, second);
    world.invokeAction =
        () -> {
          var original = objects(state.entityEvents.entries[0].callbacks);
          original.clear();
          original.add(added);
        };
    RogueEventFire46.fire(state, new Object(), world);
    assertEquals(List.of(first, second), world.invoked);
  }

  @Test
  void invocationFailurePreservesCopyAndStopsRelease() {
    var world = new World();
    var state = state(new Object(), new Object());
    world.invokeAction =
        () -> {
          state.executeListPool.count = 8;
          throw new IllegalStateException("callback");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueEventFire46.fire(state, new Object(), world));
    assertEquals(1, world.invoked.size());
    assertEquals(2, objects(world.lastExecution).size());
    assertEquals(8, state.executeListPool.count);
    assertFalse(world.calls.contains("clear"));
  }

  @Test
  void clearFailurePreservesItsMutationsAndStopsEnqueue() {
    var world = new World();
    world.clearAction =
        () -> {
          throw new IllegalStateException("clear");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueEventFire46.fire(state(new Object()), new Object(), world));
    assertTrue(objects(world.lastExecution).isEmpty());
    assertFalse(world.calls.contains("enqueue"));
  }

  @Test
  void clearCanReplaceQueueAndReleaseUsesCurrentQueue() {
    var world = new World();
    var state = state();
    var replacement = queue();
    world.clearAction = () -> state.executeListPool = replacement;
    RogueEventFire46.fire(state, new Object(), world);
    assertSame(replacement, world.releasedQueue);
  }

  @Test
  void clearDroppingQueueFailsAfterClearing() {
    var world = new World();
    var state = state(new Object());
    world.clearAction = () -> state.executeListPool = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventFire46.fire(state, new Object(), world));
    assertTrue(objects(world.lastExecution).isEmpty());
    assertFalse(world.calls.contains("enqueue"));
  }

  @Test
  void standaloneReleaseNullManagerStillClearsFirst() {
    var world = new World();
    var list = callbacks(new Object());
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventFire46.releaseExecuteList(null, list, world));
    assertTrue(objects(list).isEmpty());
    assertEquals(List.of("clear"), world.calls);
  }

  @Test
  void standaloneReleaseNullListFailsBeforeManagerOrWorld() {
    var world = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventFire46.releaseExecuteList(null, null, world));
    assertTrue(world.calls.isEmpty());
  }

  @Test
  void signedKeyMembersPreserveExactPackedRawAndAreNotRequeried() {
    var world = new World();
    world.source = -1;
    world.type = Integer.MIN_VALUE;
    RogueEventFire46.fire(state(), new Object(), world);
    assertEquals(new Key(-1, Integer.MIN_VALUE), world.keys.getFirst());
    assertSame(world.keys.getFirst(), world.keys.getLast());
    assertEquals(0x80000000ffffffffL, world.keys.getFirst().raw());
  }

  @Test
  void realDictionaryLookupAndOtherFadeCallbackAreJointlyExecuted() {
    var receiver = RogueModifierDestroy46Test.state();
    receiver.cleanup.candidate.state = 1;
    var listener = new RogueBaseOtherModifierEvents46Test.World();
    var payload =
        new RogueBaseOtherModifierEvents46.FadeFinished(new Object(), new Object(), new Object());
    World world =
        new World() {
          public int findEntryIndex(Dictionary dictionary, Key key) {
            return RogueEventDictionaryLookup46.find(dictionary, key, null);
          }
        };
    var state =
        state(
            (Runnable)
                () ->
                    RogueBaseOtherModifierEvents46.onOtherFadeFinished(
                        receiver, payload, listener));
    RogueEventFire46.fire(state, payload, world);
    assertEquals(List.of(true), listener.flags);
    assertSame(payload.effects, listener.effects.getFirst());
    assertEquals(1, state.executeListPool.count);
  }

  @Test
  void recursiveFireAcquiresDifferentCopyAndOuterExecutionResumes() {
    var world = new World();
    var state = state();
    Object outer = new Object(), inner = new Object();
    var lists = new ArrayList<PooledCallbacks>();
    int[] depth = {0};
    world.allocated = callbacks();
    World nestedWorld =
        new World() {
          public PooledCallbacks allocateExecuteList() {
            calls.add("allocate");
            return callbacks();
          }
        };
    objects(state.entityEvents.entries[0].callbacks)
        .add(
            (Runnable)
                () -> {
                  lists.add(nestedWorld.lastExecution);
                  if (depth[0]++ == 0) RogueEventFire46.fire(state, inner, nestedWorld);
                });
    RogueEventFire46.fire(state, outer, nestedWorld);
    assertNotSame(lists.get(0), lists.get(1));
    assertEquals(List.of(outer, inner), nestedWorld.delivered);
    assertEquals(2, state.executeListPool.count);
  }
}
