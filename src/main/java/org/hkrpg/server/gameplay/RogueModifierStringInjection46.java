package org.hkrpg.server.gameplay;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Map;

/** Versioned string parameters and explicitly bound dictionaries; native factories stay closed. */
public final class RogueModifierStringInjection46 {
  private RogueModifierStringInjection46() {}

  public record StringInjection(int key, String value) {}

  public interface StringDictionary {
    void put(int hash, String value);
  }

  public static StringDictionary javaMap(Map<Integer, String> map) {
    if (map == null) throw new IllegalArgumentException("Missing explicit string map");
    return map::put;
  }

  /** All mutations, including replacement, advance the captured native-style list version. */
  public static final class Injections extends AbstractList<StringInjection> {
    private final ArrayList<StringInjection> entries;
    private int version;

    public Injections(Collection<? extends StringInjection> entries) {
      if (entries == null) throw new IllegalArgumentException("Missing explicit string parameters");
      this.entries = new ArrayList<>(entries);
    }

    @Override
    public StringInjection get(int index) {
      return entries.get(index);
    }

    @Override
    public int size() {
      return entries.size();
    }

    @Override
    public StringInjection set(int index, StringInjection value) {
      StringInjection previous = entries.set(index, value);
      version++;
      modCount++;
      return previous;
    }

    @Override
    public void add(int index, StringInjection value) {
      entries.add(index, value);
      version++;
      modCount++;
    }

    @Override
    public StringInjection remove(int index) {
      StringInjection previous = entries.remove(index);
      version++;
      modCount++;
      return previous;
    }

    @Override
    public void clear() {
      entries.clear();
      version++;
      modCount++;
    }
  }

  public static void applyStringInjections(RogueModifierReplace46.State state, Object injections) {
    if (state == null)
      throw new IllegalArgumentException("Missing explicit string injection state");
    if (injections == null) return;
    if (!(injections instanceof Injections list))
      throw new IllegalArgumentException("Missing explicit versioned string injection list");
    if (list.isEmpty()) return;
    int version = list.version;
    for (int index = 0; ; ) {
      if (list.version != version)
        throw new ConcurrentModificationException("String injection list changed");
      if (index >= list.size()) return;
      if (index >= 255) throw new IllegalArgumentException("Excessive finite string injections");
      if (!(state.dynamicStrings instanceof StringDictionary dictionary))
        throw new IllegalArgumentException("Missing explicit string injection dictionary");
      StringInjection entry = list.get(index);
      if (entry == null) throw new IllegalArgumentException("Unmatched string injection element");
      index++;
      dictionary.put(entry.key(), entry.value());
    }
  }
}
