package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.gameplay.RogueEventFire46.ExecuteQueue;
import org.hkrpg.server.gameplay.RogueEventListenerWithdrawal46.PooledCallbacks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEventExecuteQueue46Test {
  static class Arrays implements RogueEventExecuteQueue46.Arrays {
    List<String> calls = new ArrayList<>();
    List<PooledCallbacks[]> sources = new ArrayList<>();
    PooledCallbacks[] allocated;
    int capacity, copies;
    boolean returnNull;
    Runnable allocationAction = () -> {}, firstCopyAction = () -> {}, secondCopyAction = () -> {};

    public PooledCallbacks[] allocate(int count) {
      calls.add("allocate");
      capacity = count;
      allocationAction.run();
      return returnNull ? null : (allocated = new PooledCallbacks[count]);
    }

    public void copy(
        PooledCallbacks[] source, int from, PooledCallbacks[] destination, int to, int count) {
      calls.add(from + ":" + to + ":" + count);
      sources.add(source);
      System.arraycopy(source, from, destination, to, count);
      if (++copies == 1) firstCopyAction.run();
      else secondCopyAction.run();
    }
  }

  static ExecuteQueue queue(int capacity, int count, int head, int tail, int version) {
    var queue = new ExecuteQueue();
    queue.entries = new PooledCallbacks[capacity];
    queue.count = count;
    queue.head = head;
    queue.tail = tail;
    queue.version = version;
    return queue;
  }

  static PooledCallbacks item() {
    return new PooledCallbacks();
  }

  @Test
  void emptyQueueFailsBeforeMissingStorage() {
    var queue = queue(0, 0, 0, 0, 5);
    queue.entries = null;
    assertThrows(IllegalArgumentException.class, () -> RogueEventExecuteQueue46.dequeue(queue));
    assertEquals(5, queue.version);
    assertEquals(0, queue.count);
  }

  @Test
  void nullQueueIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> RogueEventExecuteQueue46.dequeue(null));
  }

  @Test
  void nonemptyQueueMissingStorageKeepsCountAndVersion() {
    var queue = queue(0, 1, 0, 0, 5);
    queue.entries = null;
    assertThrows(IllegalArgumentException.class, () -> RogueEventExecuteQueue46.dequeue(queue));
    assertEquals(1, queue.count);
    assertEquals(5, queue.version);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 2, Integer.MAX_VALUE})
  void invalidHeadKeepsStorageAndCount(int head) {
    var queue = queue(2, 1, head, 0, 5);
    queue.entries[0] = item();
    assertThrows(IllegalArgumentException.class, () -> RogueEventExecuteQueue46.dequeue(queue));
    assertNotNull(queue.entries[0]);
    assertEquals(1, queue.count);
    assertEquals(5, queue.version);
  }

  @Test
  void dequeueReturnsExactFirstAndClearsSlot() {
    var queue = queue(3, 2, 0, 2, 5);
    var value = item();
    queue.entries[0] = value;
    assertSame(value, RogueEventExecuteQueue46.dequeue(queue));
    assertNull(queue.entries[0]);
    assertEquals(1, queue.head);
    assertEquals(1, queue.count);
    assertEquals(6, queue.version);
  }

  @Test
  void dequeueHeadWrapsAtArrayLength() {
    var queue = queue(2, 1, 1, 0, 0);
    queue.entries[1] = item();
    RogueEventExecuteQueue46.dequeue(queue);
    assertEquals(0, queue.head);
    assertEquals(0, queue.count);
  }

  @Test
  void nullPooledListIsReturnedAndSlotStillConsumed() {
    var queue = queue(2, 1, 0, 1, 0);
    assertNull(RogueEventExecuteQueue46.dequeue(queue));
    assertEquals(0, queue.count);
    assertEquals(1, queue.version);
  }

  @Test
  void negativeCountIsNotTreatedAsEmptyByPrimitiveDequeue() {
    var queue = queue(2, -1, 0, 1, 0);
    var value = item();
    queue.entries[0] = value;
    assertSame(value, RogueEventExecuteQueue46.dequeue(queue));
    assertEquals(-2, queue.count);
  }

  @Test
  void dequeueIntCountersWrap() {
    var queue = queue(1, Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE);
    RogueEventExecuteQueue46.dequeue(queue);
    assertEquals(Integer.MAX_VALUE, queue.count);
    assertEquals(Integer.MIN_VALUE, queue.version);
  }

  @Test
  void enqueueWithoutGrowthNeedsNoArrayBinding() {
    var queue = queue(3, 1, 0, 1, 5);
    var value = item();
    RogueEventExecuteQueue46.enqueue(queue, value, null);
    assertSame(value, queue.entries[1]);
    assertEquals(2, queue.tail);
    assertEquals(2, queue.count);
    assertEquals(6, queue.version);
  }

  @Test
  void enqueueNullListIsAStoredEntry() {
    var queue = queue(3, 0, 0, 0, 0);
    queue.entries[0] = item();
    RogueEventExecuteQueue46.enqueue(queue, null, null);
    assertNull(queue.entries[0]);
    assertEquals(1, queue.count);
  }

  @Test
  void enqueueTailWrapsAtArrayLength() {
    var queue = queue(3, 1, 0, 2, 0);
    RogueEventExecuteQueue46.enqueue(queue, null, null);
    assertEquals(0, queue.tail);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 2, Integer.MAX_VALUE})
  void invalidTailKeepsCountAndVersion(int tail) {
    var queue = queue(2, 1, 0, tail, 5);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventExecuteQueue46.enqueue(queue, item(), null));
    assertEquals(1, queue.count);
    assertEquals(5, queue.version);
  }

  @Test
  void corruptCountGreaterThanCapacityDoesNotTriggerGrowth() {
    var queue = queue(2, 8, 0, 1, 0);
    RogueEventExecuteQueue46.enqueue(queue, item(), null);
    assertEquals(9, queue.count);
    assertEquals(0, queue.tail);
  }

  @Test
  void enqueueIntCountersWrap() {
    var queue = queue(2, Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE);
    RogueEventExecuteQueue46.enqueue(queue, null, null);
    assertEquals(Integer.MIN_VALUE, queue.count);
    assertEquals(Integer.MIN_VALUE, queue.version);
  }

  @Test
  void emptyQueueGrowsByFourAndVersionsAdvanceTwice() {
    var arrays = new Arrays();
    var queue = queue(0, 0, 0, 0, 5);
    var value = item();
    RogueEventExecuteQueue46.enqueue(queue, value, arrays);
    assertEquals(4, arrays.capacity);
    assertEquals(1, queue.count);
    assertEquals(1, queue.tail);
    assertEquals(7, queue.version);
    assertSame(value, queue.entries[0]);
  }

  @Test
  void smallFullQueueUsesCountPlusFour() {
    var arrays = new Arrays();
    var queue = queue(2, 2, 0, 0, 0);
    RogueEventExecuteQueue46.enqueue(queue, null, arrays);
    assertEquals(6, arrays.capacity);
  }

  @Test
  void largeFullQueueUsesTwiceCount() {
    var arrays = new Arrays();
    var queue = queue(5, 5, 0, 0, 0);
    RogueEventExecuteQueue46.enqueue(queue, null, arrays);
    assertEquals(10, arrays.capacity);
  }

  @Test
  void wrappedGrowthCopiesTailAfterHeadAndPreservesFifo() {
    var arrays = new Arrays();
    var queue = queue(3, 3, 2, 2, 9);
    var first = item();
    var second = item();
    var third = item();
    var added = item();
    queue.entries[2] = first;
    queue.entries[0] = second;
    queue.entries[1] = third;
    RogueEventExecuteQueue46.enqueue(queue, added, arrays);
    assertEquals(List.of("allocate", "2:0:1", "0:1:2"), arrays.calls);
    assertSame(first, RogueEventExecuteQueue46.dequeue(queue));
    assertSame(second, RogueEventExecuteQueue46.dequeue(queue));
    assertSame(third, RogueEventExecuteQueue46.dequeue(queue));
    assertSame(added, RogueEventExecuteQueue46.dequeue(queue));
    assertEquals(15, queue.version);
  }

  @Test
  void contiguousCapacityChangeCopiesOneCurrentSegment() {
    var arrays = new Arrays();
    var queue = queue(4, 2, 1, 3, 9);
    var first = item();
    var second = item();
    queue.entries[1] = first;
    queue.entries[2] = second;
    RogueEventExecuteQueue46.setCapacity(queue, 8, arrays);
    assertEquals(List.of("allocate", "1:0:2"), arrays.calls);
    assertEquals(0, queue.head);
    assertEquals(2, queue.tail);
    assertSame(first, queue.entries[0]);
    assertSame(second, queue.entries[1]);
    assertEquals(10, queue.version);
  }

  @Test
  void exactCountCapacityPublishesTailZero() {
    var arrays = new Arrays();
    var queue = queue(3, 2, 0, 2, 0);
    RogueEventExecuteQueue46.setCapacity(queue, 2, arrays);
    assertEquals(0, queue.tail);
  }

  @Test
  void nonpositiveCountSkipsOldStorageAndCopies() {
    var arrays = new Arrays();
    var queue = queue(0, -2, 7, 8, 0);
    queue.entries = null;
    RogueEventExecuteQueue46.setCapacity(queue, 4, arrays);
    assertEquals(List.of("allocate"), arrays.calls);
    assertEquals(0, queue.head);
    assertEquals(-2, queue.tail);
  }

  @Test
  void allocationCanChangeCountBeforeCopyDecision() {
    var arrays = new Arrays();
    var queue = queue(0, 0, 0, 0, 5);
    var value = item();
    arrays.allocationAction =
        () -> {
          queue.entries = new PooledCallbacks[] {value};
          queue.count = 1;
          queue.tail = 1;
        };
    RogueEventExecuteQueue46.setCapacity(queue, 4, arrays);
    assertSame(value, queue.entries[0]);
    assertEquals(1, queue.tail);
    assertEquals(List.of("allocate", "0:0:1"), arrays.calls);
  }

  @Test
  void firstWrappedCopyCanReplaceStorageHeadTailAndCountForSecondCopy() {
    var arrays = new Arrays();
    var queue = queue(4, 3, 3, 2, 5);
    var first = item();
    var second = item();
    PooledCallbacks[] old = queue.entries;
    old[3] = first;
    PooledCallbacks[] replacement = new PooledCallbacks[5];
    replacement[0] = second;
    arrays.firstCopyAction =
        () -> {
          queue.entries = replacement;
          queue.head = 3;
          queue.tail = 1;
          queue.count = 2;
        };
    RogueEventExecuteQueue46.setCapacity(queue, 8, arrays);
    assertEquals(List.of(old, replacement), arrays.sources);
    assertEquals(List.of("allocate", "3:0:1", "0:2:1"), arrays.calls);
    assertSame(first, queue.entries[0]);
    assertSame(second, queue.entries[2]);
    assertEquals(2, queue.tail);
    assertEquals(2, queue.count);
  }

  @Test
  void secondCopyFailureDoesNotPublishReplacementOrResetHead() {
    var arrays = new Arrays();
    var queue = queue(3, 3, 2, 2, 5);
    var old = queue.entries;
    arrays.secondCopyAction =
        () -> {
          throw new IllegalStateException("second");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueEventExecuteQueue46.setCapacity(queue, 8, arrays));
    assertSame(old, queue.entries);
    assertEquals(2, queue.head);
    assertEquals(5, queue.version);
    assertEquals(2, arrays.copies);
  }

  @Test
  void firstCopyDroppingStorageStopsSecondAndKeepsPrefix() {
    var arrays = new Arrays();
    var queue = queue(3, 3, 2, 2, 5);
    arrays.firstCopyAction = () -> queue.entries = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventExecuteQueue46.setCapacity(queue, 8, arrays));
    assertNull(queue.entries);
    assertEquals(2, queue.head);
    assertEquals(5, queue.version);
    assertEquals(1, arrays.copies);
  }

  @Test
  void nullAllocationForEmptyQueueIsPublishedByCapacitySetter() {
    var arrays = new Arrays();
    arrays.returnNull = true;
    var queue = queue(0, 0, 0, 0, 5);
    RogueEventExecuteQueue46.setCapacity(queue, 4, arrays);
    assertNull(queue.entries);
    assertEquals(6, queue.version);
    assertEquals(0, queue.tail);
  }

  @Test
  void nullAllocationGrowthFailsAfterCapacityPrefixBeforeEnqueueCounters() {
    var arrays = new Arrays();
    arrays.returnNull = true;
    var queue = queue(0, 0, 0, 0, 5);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventExecuteQueue46.enqueue(queue, item(), arrays));
    assertNull(queue.entries);
    assertEquals(6, queue.version);
    assertEquals(0, queue.count);
  }

  @Test
  void growthArrayBindingFailureKeepsOldQueue() {
    var queue = queue(0, 0, 0, 0, 5);
    var old = queue.entries;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventExecuteQueue46.enqueue(queue, item(), null));
    assertSame(old, queue.entries);
    assertEquals(5, queue.version);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 4097, Integer.MAX_VALUE})
  void finiteCapacityPolicyStopsBeforeFactory(int capacity) {
    var arrays = new Arrays();
    var queue = queue(0, 0, 0, 0, 5);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventExecuteQueue46.setCapacity(queue, capacity, arrays));
    assertTrue(arrays.calls.isEmpty());
    assertEquals(5, queue.version);
  }

  @Test
  void enqueueAfterCapacityCallbacksUsesCurrentTailAndCount() {
    var arrays = new Arrays();
    var queue = queue(1, 1, 0, 0, 5);
    var value = item();
    arrays.firstCopyAction =
        () -> {
          queue.count = 2;
          queue.tail = 0;
        };
    RogueEventExecuteQueue46.enqueue(queue, value, arrays);
    assertSame(value, queue.entries[2]);
    assertEquals(3, queue.count);
    assertEquals(3, queue.tail);
    assertEquals(7, queue.version);
  }

  @Test
  void actualFireReturnsExecutionListThroughActualRingQueue() {
    var arrays = new Arrays();
    var state = RogueEventFire46Test.state(new Object());
    var pooled = RogueEventFire46Test.callbacks();
    state.executeListPool = queue(2, 1, 1, 0, 0);
    state.executeListPool.entries[1] = pooled;
    RogueEventFire46Test.World world =
        new RogueEventFire46Test.World() {
          public PooledCallbacks dequeue(ExecuteQueue queue) {
            return RogueEventExecuteQueue46.dequeue(queue);
          }

          public void enqueue(ExecuteQueue queue, PooledCallbacks list) {
            RogueEventExecuteQueue46.enqueue(queue, list, arrays);
          }
        };
    RogueEventFire46.fire(state, new Object(), world);
    assertSame(pooled, world.lastExecution);
    assertEquals(1, world.invoked.size());
    assertEquals(1, state.executeListPool.count);
    assertEquals(0, state.executeListPool.head);
    assertEquals(1, state.executeListPool.tail);
    assertSame(pooled, RogueEventExecuteQueue46.dequeue(state.executeListPool));
  }
}
