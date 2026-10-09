package org.hkrpg.server.gameplay;

/** Ordinary event-key lookup and pooled callback withdrawal over explicit finite containers. */
public final class RogueEventListenerWithdrawal46 {
  public static final int LIMIT = 4096;

  private RogueEventListenerWithdrawal46() {}

  public record Key(int entityId, int event) {
    public long raw() {
      return ((long) event << 32) | Integer.toUnsignedLong(entityId);
    }
  }

  public static final class PooledCallbacks {
    public Object collection;
  }

  public static final class Entry {
    public int hashCode, next;
    public Key key;
    public PooledCallbacks callbacks;
  }

  public static final class Dictionary {
    public int[] buckets;
    public Object comparer;
    public Entry[] entries;
  }

  public static final class State {
    public Dictionary entityEvents;
    public RogueEventFire46.ExecuteQueue executeListPool;
  }

  public interface World {
    int findEntryIndex(Dictionary dictionary, Key key);

    boolean removeCollectionMember(Object collection, RogueBaseModifierDestroy46.Callback callback);
  }

  public static void unregister(
      State state,
      int event,
      int entityId,
      RogueBaseModifierDestroy46.Callback callback,
      World world) {
    require(state, "event listener manager");
    Dictionary dictionary = state.entityEvents;
    if (dictionary == null) return;
    World bound = require(world, "event dictionary lookup world");
    int index = bound.findEntryIndex(dictionary, new Key(entityId, event));
    if (index < 0) return;
    Entry[] entries = require(dictionary.entries, "current captured event dictionary entries");
    if (entries.length > LIMIT || index >= entries.length)
      throw new IllegalArgumentException("Unmatched finite event dictionary index");
    Entry entry = require(entries[index], "explicit event dictionary entry");
    remove(require(entry.callbacks, "event pooled callback list"), callback, bound);
  }

  public static boolean remove(
      PooledCallbacks callbacks, RogueBaseModifierDestroy46.Callback callback, World world) {
    Object collection =
        require(require(callbacks, "pooled callbacks").collection, "pooled callback collection");
    return require(world, "pooled callback interface world")
        .removeCollectionMember(collection, callback);
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
