package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.gameplay.RogueEventListenerWithdrawal46.PooledCallbacks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEventPooledCallbacks46Test {
  static class World implements RogueEventPooledCallbacks46.World {
    List<String> calls = new ArrayList<>();
    Object allocated = new Object(), captured, result;
    PooledCallbacks rangeSource;
    boolean branch, allocationBranch, initializationBranch, releaseFlag;
    int index, size;
    Runnable branchAction = () -> {},
        allocationAction = () -> {},
        initializationAction = () -> {},
        forwarding = () -> {};

    public boolean firstConstructorBranch() {
      calls.add("branch");
      branchAction.run();
      return branch;
    }

    public Object allocateInner(boolean first) {
      calls.add("allocate");
      allocationBranch = first;
      allocationAction.run();
      return allocated;
    }

    public void initializeInner(Object collection, boolean first) {
      calls.add("initialize");
      initializationBranch = first;
      captured = collection;
      initializationAction.run();
    }

    public void addRange(Object collection, PooledCallbacks source) {
      calls.add("range");
      captured = collection;
      rangeSource = source;
      forwarding.run();
    }

    public int count(Object collection) {
      calls.add("count");
      captured = collection;
      forwarding.run();
      return size;
    }

    public Object at(Object collection, int position) {
      calls.add("item");
      captured = collection;
      index = position;
      forwarding.run();
      return result;
    }

    public void release(Object collection, boolean flag) {
      calls.add("release");
      captured = collection;
      releaseFlag = flag;
      forwarding.run();
    }
  }

  static PooledCallbacks list() {
    var list = new PooledCallbacks();
    list.collection = new Object();
    return list;
  }

  static void forward(int method, PooledCallbacks list, World world) {
    switch (method) {
      case 0 -> RogueEventPooledCallbacks46.addRange(list, null, world);
      case 1 -> RogueEventPooledCallbacks46.count(list, world);
      case 2 -> RogueEventPooledCallbacks46.at(list, -1, world);
      case 3 -> RogueEventPooledCallbacks46.release(list, true, world);
      default -> throw new AssertionError();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void eachConstructorBranchPublishesOnlyAfterInitialization(boolean first) {
    var world = new World();
    world.branch = first;
    var list = list();
    Object old = list.collection;
    world.initializationAction = () -> assertSame(old, list.collection);
    RogueEventPooledCallbacks46.initialize(list, world);
    assertSame(world.allocated, list.collection);
    assertEquals(first, world.allocationBranch);
    assertEquals(first, world.initializationBranch);
    assertEquals(List.of("branch", "allocate", "initialize"), world.calls);
  }

  @Test
  void nullConstructorReceiverFailsBeforeWorld() {
    var world = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventPooledCallbacks46.initialize(null, world));
    assertTrue(world.calls.isEmpty());
  }

  @Test
  void branchFailureKeepsOldCollection() {
    var world = new World();
    var list = list();
    Object old = list.collection;
    world.branchAction =
        () -> {
          throw new IllegalStateException("branch");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueEventPooledCallbacks46.initialize(list, world));
    assertSame(old, list.collection);
    assertEquals(List.of("branch"), world.calls);
  }

  @Test
  void allocationFailureKeepsItsMutationAndSkipsInitialization() {
    var world = new World();
    var list = list();
    Object replacement = new Object();
    world.allocationAction =
        () -> {
          list.collection = replacement;
          throw new IllegalStateException("allocation");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueEventPooledCallbacks46.initialize(list, world));
    assertSame(replacement, list.collection);
    assertFalse(world.calls.contains("initialize"));
  }

  @Test
  void selectedBranchIsCapturedBeforeAllocationChangesSelector() {
    var world = new World();
    world.branch = true;
    world.allocationAction = () -> world.branch = false;
    RogueEventPooledCallbacks46.initialize(list(), world);
    assertTrue(world.allocationBranch);
    assertTrue(world.initializationBranch);
  }

  @Test
  void nullAllocationIsForwardedToConstructorBeforeNullPublication() {
    var world = new World();
    world.allocated = null;
    var list = list();
    RogueEventPooledCallbacks46.initialize(list, world);
    assertNull(world.captured);
    assertNull(list.collection);
  }

  @Test
  void initializationFailurePreservesItsWritesWithoutPublishingAllocated() {
    var world = new World();
    var list = list();
    Object replacement = new Object();
    world.initializationAction =
        () -> {
          list.collection = replacement;
          throw new IllegalStateException("initialize");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueEventPooledCallbacks46.initialize(list, world));
    assertSame(replacement, list.collection);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void nullWrapperFailsBeforeForwarding(int method) {
    var world = new World();
    assertThrows(IllegalArgumentException.class, () -> forward(method, null, world));
    assertTrue(world.calls.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void nullInnerFailsBeforeWorld(int method) {
    var world = new World();
    var list = new PooledCallbacks();
    assertThrows(IllegalArgumentException.class, () -> forward(method, list, world));
    assertTrue(world.calls.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void forwardingCapturesInnerBeforeItsReplacement(int method) {
    var world = new World();
    var list = list();
    Object old = list.collection, replacement = new Object();
    world.forwarding = () -> list.collection = replacement;
    forward(method, list, world);
    assertSame(old, world.captured);
    assertSame(replacement, list.collection);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void forwardingFailureKeepsBindingMutation(int method) {
    var world = new World();
    var list = list();
    world.forwarding =
        () -> {
          list.collection = null;
          throw new IllegalStateException("forward");
        };
    assertThrows(IllegalStateException.class, () -> forward(method, list, world));
    assertNull(list.collection);
  }

  @Test
  void rangeForwardsSameNullableSourceWithoutTakingItsInner() {
    var world = new World();
    var source = new PooledCallbacks();
    RogueEventPooledCallbacks46.addRange(list(), source, world);
    assertSame(source, world.rangeSource);
    RogueEventPooledCallbacks46.addRange(list(), null, world);
    assertNull(world.rangeSource);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 4097, Integer.MAX_VALUE})
  void countForwardsRawResultWithoutClamping(int value) {
    var world = new World();
    world.size = value;
    assertEquals(value, RogueEventPooledCallbacks46.count(list(), world));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, Integer.MIN_VALUE, 4097, Integer.MAX_VALUE})
  void itemForwardsRawIndexAndExactNullableResult(int value) {
    var world = new World();
    Object expected = new Object();
    world.result = expected;
    assertSame(expected, RogueEventPooledCallbacks46.at(list(), value, world));
    assertEquals(value, world.index);
    world.result = null;
    assertNull(RogueEventPooledCallbacks46.at(list(), value, world));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void releaseForwardsExactFlagAndKeepsWrapperField(boolean flag) {
    var world = new World();
    var list = list();
    Object old = list.collection;
    RogueEventPooledCallbacks46.release(list, flag, world);
    assertEquals(flag, world.releaseFlag);
    assertSame(old, list.collection);
  }

  static class Processor extends World {
    public Object allocateInner(boolean first) {
      return new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> values(Object processor) {
      return (List<Object>) processor;
    }

    public void addRange(Object collection, PooledCallbacks source) {
      values(collection).addAll(values(source.collection));
    }

    public int count(Object collection) {
      return values(collection).size();
    }

    public Object at(Object collection, int position) {
      return values(collection).get(position);
    }

    public void release(Object collection, boolean flag) {
      releaseFlag = flag;
      values(collection).clear();
    }
  }

  static RogueEventFire46Test.World fireWorld(Processor processor) {
    var arrays = new RogueEventExecuteQueue46Test.Arrays();
    return new RogueEventFire46Test.World() {
      public PooledCallbacks allocateExecuteList() {
        return new PooledCallbacks();
      }

      public void initializeExecuteList(PooledCallbacks list) {
        RogueEventPooledCallbacks46.initialize(list, processor);
      }

      public void copyCallbacks(PooledCallbacks destination, PooledCallbacks source) {
        lastExecution = destination;
        RogueEventPooledCallbacks46.addRange(destination, source, processor);
      }

      public int callbackCount(PooledCallbacks list) {
        return RogueEventPooledCallbacks46.count(list, processor);
      }

      public Object callbackAt(PooledCallbacks list, int index) {
        return RogueEventPooledCallbacks46.at(list, index, processor);
      }

      public void releaseCallbacks(PooledCallbacks list, boolean rawFlag) {
        RogueEventPooledCallbacks46.release(list, rawFlag, processor);
      }

      public PooledCallbacks dequeue(RogueEventFire46.ExecuteQueue queue) {
        return RogueEventExecuteQueue46.dequeue(queue);
      }

      public void enqueue(RogueEventFire46.ExecuteQueue queue, PooledCallbacks list) {
        RogueEventExecuteQueue46.enqueue(queue, list, arrays);
      }
    };
  }

  @Test
  void actualFireWrapperAndQueueExecuteAndReuseSamePooledList() {
    var processor = new Processor();
    var world = fireWorld(processor);
    Object callback = new Object();
    var state = RogueEventFire46Test.state(callback);
    state.executeListPool = RogueEventExecuteQueue46Test.queue(0, 0, 0, 0, 0);
    RogueEventFire46.fire(state, new Object(), world);
    PooledCallbacks first = world.lastExecution;
    assertTrue(processor.releaseFlag);
    assertEquals(1, state.executeListPool.count);
    assertEquals(0, RogueEventPooledCallbacks46.count(first, processor));
    RogueEventFire46.fire(state, new Object(), world);
    assertSame(first, world.lastExecution);
    assertEquals(List.of(callback, callback), world.invoked);
    assertEquals(1, state.executeListPool.count);
  }

  @Test
  void actualFireWithWrapperFailureDoesNotReleaseOrEnqueue() {
    var processor = new Processor();
    var world = fireWorld(processor);
    var state = RogueEventFire46Test.state(new Object());
    state.executeListPool = RogueEventExecuteQueue46Test.queue(0, 0, 0, 0, 0);
    world.invokeAction =
        () -> {
          throw new IllegalStateException("callback");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueEventFire46.fire(state, new Object(), world));
    assertEquals(1, RogueEventPooledCallbacks46.count(world.lastExecution, processor));
    assertEquals(0, state.executeListPool.count);
    assertFalse(processor.releaseFlag);
  }
}
