package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueEventListenerWithdrawal46.Dictionary;
import org.hkrpg.server.gameplay.RogueEventListenerWithdrawal46.Entry;
import org.hkrpg.server.gameplay.RogueEventListenerWithdrawal46.Key;
import org.hkrpg.server.gameplay.RogueEventListenerWithdrawal46.PooledCallbacks;
import org.hkrpg.server.gameplay.RogueEventListenerWithdrawal46.State;

/** Ordinary event execution with two current dictionary queries and a separate callback copy. */
public final class RogueEventFire46 {
  public static final int LIMIT = 4096;

  private RogueEventFire46() {}

  /** Known closed Queue&lt;PooledList&lt;OnEventCallBack&gt;&gt; count projection. */
  public static final class ExecuteQueue {
    public int count;
    public PooledCallbacks[] entries;
    public int head, tail, version;
    public Object context;
  }

  public interface World {
    int sourceRuntimeId(Object event);

    int eventType(Object event);

    int findEntryIndex(Dictionary dictionary, Key key);

    PooledCallbacks dequeue(ExecuteQueue queue);

    PooledCallbacks allocateExecuteList();

    void initializeExecuteList(PooledCallbacks list);

    void copyCallbacks(PooledCallbacks destination, PooledCallbacks source);

    int callbackCount(PooledCallbacks list);

    Object callbackAt(PooledCallbacks list, int index);

    void invokeCallback(Object callback, Object event);

    void releaseCallbacks(PooledCallbacks list, boolean rawFlag);

    void enqueue(ExecuteQueue queue, PooledCallbacks list);
  }

  public static void fire(State state, Object event, World world) {
    require(event, "runtime event");
    World bound = require(world, "event execution world");
    int source = bound.sourceRuntimeId(event);
    int type = bound.eventType(event);
    State current = require(state, "event manager");
    Key key = new Key(source, type);
    Dictionary first = require(current.entityEvents, "event presence dictionary");
    if (bound.findEntryIndex(first, key) < 0) return;
    Dictionary second = require(current.entityEvents, "current event dictionary");
    int index = bound.findEntryIndex(second, key);
    if (index < 0) return;
    Entry[] entries = require(second.entries, "current captured event entries");
    if (entries.length > LIMIT || Integer.compareUnsigned(index, entries.length) >= 0)
      throw new IllegalArgumentException("Unmatched finite event execution entry");
    PooledCallbacks listeners = require(entries[index], "event execution entry").callbacks;
    PooledCallbacks execution = require(getExecuteList(current, bound), "event execution list");
    bound.copyCallbacks(execution, listeners);
    int count = bound.callbackCount(execution);
    int position = 0;
    while (position < count) {
      if (position >= LIMIT) throw new IllegalArgumentException("Excessive finite event execution");
      if (bound.callbackAt(execution, position) != null) {
        Object callback = require(bound.callbackAt(execution, position), "current event callback");
        bound.invokeCallback(callback, event);
      }
      position++;
      count = bound.callbackCount(execution);
    }
    releaseExecuteList(current, execution, bound);
  }

  public static PooledCallbacks getExecuteList(State state, World world) {
    ExecuteQueue queue =
        require(require(state, "execute list manager").executeListPool, "execute queue");
    World bound = require(world, "execute queue world");
    if (queue.count > 0) return bound.dequeue(queue);
    PooledCallbacks list = bound.allocateExecuteList();
    bound.initializeExecuteList(list);
    return list;
  }

  public static void releaseExecuteList(State state, PooledCallbacks list, World world) {
    require(list, "released execute list");
    World bound = require(world, "execute release world");
    bound.releaseCallbacks(list, true);
    ExecuteQueue queue =
        require(require(state, "execute release manager").executeListPool, "current execute queue");
    bound.enqueue(queue, list);
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
