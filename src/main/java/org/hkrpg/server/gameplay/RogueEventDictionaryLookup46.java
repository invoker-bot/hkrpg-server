package org.hkrpg.server.gameplay;

/** Ordinary bucket lookup for the exact packed entity/event key and explicit finite dictionary. */
public final class RogueEventDictionaryLookup46 {
  private RogueEventDictionaryLookup46() {}

  public interface World {
    int hashCode(Object comparer, RogueEventListenerWithdrawal46.Key key);

    boolean equals(
        Object comparer,
        RogueEventListenerWithdrawal46.Key stored,
        RogueEventListenerWithdrawal46.Key requested);
  }

  public static int defaultHash(RogueEventListenerWithdrawal46.Key key) {
    return keyHashCode(key) & 0x7fffffff;
  }

  public static int keyHashCode(RogueEventListenerWithdrawal46.Key key) {
    require(key, "event dictionary key");
    return key.entityId() * 23 + key.event() + 0x2321;
  }

  public static boolean keysEqual(
      RogueEventListenerWithdrawal46.Key self, RogueEventListenerWithdrawal46.Key other) {
    require(self, "event key receiver");
    require(other, "explicit compared event key");
    return self.entityId() == other.entityId() && self.event() == other.event();
  }

  public static int find(
      RogueEventListenerWithdrawal46.Dictionary dictionary,
      RogueEventListenerWithdrawal46.Key key,
      World world) {
    require(dictionary, "event dictionary");
    int[] buckets = dictionary.buckets;
    if (buckets == null) return -1;
    if (buckets.length > RogueEventListenerWithdrawal46.LIMIT)
      throw new IllegalArgumentException("Excessive finite event buckets");
    require(key, "explicit event dictionary key");
    var entries = dictionary.entries;
    Object comparer = dictionary.comparer;
    int hash =
        comparer == null
            ? defaultHash(key)
            : require(world, "event dictionary comparer world").hashCode(comparer, key)
                & 0x7fffffff;
    int bucket = hash % buckets.length;
    entries = require(entries, "captured event dictionary entries");
    if (entries.length > RogueEventListenerWithdrawal46.LIMIT)
      throw new IllegalArgumentException("Excessive finite event entries");
    int index = buckets[bucket] - 1;
    int collisions = comparer == null ? -1 : 0;
    while (Integer.compareUnsigned(index, entries.length) < 0) {
      var entry = entry(entries, index);
      if (entry.hashCode == hash) {
        var stored = require(entry.key, "explicit stored event key");
        if (comparer == null ? stored.raw() == key.raw() : world.equals(comparer, stored, key))
          return index;
      }
      if (comparer == null) collisions++;
      if (collisions >= entries.length)
        throw new IllegalArgumentException("Concurrent or cyclic event dictionary chain");
      index = entry(entries, index).next;
      if (comparer != null) collisions++;
    }
    return index;
  }

  private static RogueEventListenerWithdrawal46.Entry entry(
      RogueEventListenerWithdrawal46.Entry[] entries, int index) {
    return require(entries[index], "explicit event chain entry");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
