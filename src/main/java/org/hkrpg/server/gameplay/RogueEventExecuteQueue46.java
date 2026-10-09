package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueEventFire46.ExecuteQueue;
import org.hkrpg.server.gameplay.RogueEventListenerWithdrawal46.PooledCallbacks;

/** Ordinary compiled FIFO transitions and ring-buffer capacity replacement. */
public final class RogueEventExecuteQueue46 {
  public static final int LIMIT = 4096;

  private RogueEventExecuteQueue46() {}

  public interface Arrays {
    PooledCallbacks[] allocate(int capacity);

    void copy(
        PooledCallbacks[] source,
        int sourceIndex,
        PooledCallbacks[] destination,
        int destinationIndex,
        int count);
  }

  public static PooledCallbacks dequeue(ExecuteQueue queue) {
    require(queue, "execute queue");
    int count = queue.count;
    if (count == 0) throw new IllegalArgumentException("Empty execute queue");
    PooledCallbacks[] storage = require(queue.entries, "execute queue storage");
    int head = queue.head;
    index(head, storage.length);
    PooledCallbacks result = storage[head];
    storage[head] = null;
    PooledCallbacks[] current = require(queue.entries, "current execute queue storage");
    int next = head + 1;
    queue.head = next == current.length ? 0 : next;
    queue.count = count - 1;
    queue.version++;
    return result;
  }

  public static void enqueue(ExecuteQueue queue, PooledCallbacks list, Arrays world) {
    require(queue, "execute queue");
    PooledCallbacks[] storage = require(queue.entries, "execute enqueue storage");
    int count = queue.count;
    if (count == storage.length) {
      int twice = count * 2;
      int plusFour = count + 4;
      int capacity = plusFour <= twice ? twice : plusFour;
      setCapacity(queue, capacity, world);
      storage = require(queue.entries, "resized execute queue storage");
    }
    int tail = queue.tail;
    index(tail, storage.length);
    storage[tail] = list;
    PooledCallbacks[] current = require(queue.entries, "current execute enqueue storage");
    int next = tail + 1;
    queue.tail = next == current.length ? 0 : next;
    queue.count++;
    queue.version++;
  }

  public static void setCapacity(ExecuteQueue queue, int capacity, Arrays world) {
    require(queue, "execute capacity queue");
    finite(capacity);
    Arrays bound = require(world, "execute capacity array bindings");
    PooledCallbacks[] replacement = bound.allocate(capacity);
    int count = queue.count;
    if (count > 0) {
      int head = queue.head;
      PooledCallbacks[] storage = queue.entries;
      if (head < queue.tail) {
        finite(count);
        bound.copy(storage, head, replacement, 0, count);
      } else {
        storage = require(storage, "wrapped execute queue storage");
        int firstCount = storage.length - head;
        finite(firstCount);
        bound.copy(storage, head, replacement, 0, firstCount);
        storage = require(queue.entries, "current wrapped execute queue storage");
        int secondCount = queue.tail;
        int destinationIndex = storage.length - queue.head;
        finite(secondCount);
        bound.copy(storage, 0, replacement, destinationIndex, secondCount);
      }
    }
    queue.entries = replacement;
    queue.head = 0;
    int currentCount = queue.count;
    queue.tail = currentCount == capacity ? 0 : currentCount;
    queue.version++;
  }

  private static void finite(int count) {
    if (count < 0 || count > LIMIT)
      throw new IllegalArgumentException("Excessive finite execute queue array");
  }

  private static void index(int value, int length) {
    if (Integer.compareUnsigned(value, length) >= 0)
      throw new IllegalArgumentException("Unmatched execute queue array slot");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
