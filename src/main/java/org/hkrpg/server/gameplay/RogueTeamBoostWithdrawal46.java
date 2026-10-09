package org.hkrpg.server.gameplay;

import java.util.Arrays;

/**
 * Finite ordinary withdrawal over explicit linked nodes and struct lists; admission stays external.
 */
public final class RogueTeamBoostWithdrawal46 {
  private RogueTeamBoostWithdrawal46() {}

  public static final class State {
    public AdditionalList additional;
    public BoostEntries boost;
  }

  public static final class AdditionalList {
    public Node head;
    public int size, version;
  }

  public static final class Node {
    public byte hasRegistration;
    public int registrationId;
    public Node previous, next;
    public AdditionalList owner;
  }

  public static final class ChildEntities {
    public Object[] entries;
    public int size, version;
  }

  public record BoostEntry(
      Object modifier, int registrationId, ChildEntities children, int amount, int decorateType) {}

  public static final class BoostEntries {
    public BoostEntry[] entries;
    public int size, version;
  }

  private static final BoostEntry ZERO = new BoostEntry(null, 0, null, 0, 0);

  public static void removeAdditional(State s, int registrationId) {
    AdditionalList captured = required(required(s).additional);
    Node node = captured.head;
    int examined = 0;
    while (node != null) {
      bounded(++examined);
      if ((node.hasRegistration & 1) != 0 && node.registrationId == registrationId) {
        removeNode(captured, node);
        return;
      }
      Node next = node.next;
      if (next == null) return;
      AdditionalList owner = required(node.owner);
      if (next == owner.head) return;
      node = next;
    }
  }

  public static void removeNode(AdditionalList list, Node node) {
    required(list);
    required(node);
    if (node.owner != list) throw new IllegalArgumentException("Foreign additional BP node");
    Node next = node.next;
    if (next == node) {
      list.head = null;
    } else {
      required(next).previous = node.previous;
      Node previous = required(node.previous);
      previous.next = next;
      if (list.head == node) list.head = node.next;
    }
    node.previous = null;
    node.next = null;
    node.owner = null;
    list.size--;
    list.version++;
  }

  public static void removeBoost(State s, Object modifier) {
    BoostEntries initial = required(s).boost;
    if (initial == null || initial.size <= 0) return;
    int size = initial.size;
    bounded(size);
    for (int index = size - 1; index >= 0; index--) {
      BoostEntries current = required(s.boost);
      index(index, current.size);
      BoostEntry[] entries = required(current.entries);
      index(index, entries.length);
      BoostEntry entry = entries[index] == null ? ZERO : entries[index];
      if (entry.modifier() != modifier) continue;
      clearChildren(entry);
      removeAt(required(s.boost), index);
    }
  }

  public static void clearChildren(BoostEntry entry) {
    ChildEntities children = required(entry).children();
    if (children == null) return;
    children.version++;
    int size = children.size;
    children.size = 0;
    if (size <= 0) return;
    bounded(size);
    Arrays.fill(required(children.entries), 0, size, null);
  }

  public static void removeAt(BoostEntries list, int index) {
    required(list);
    index(index, list.size);
    int nextSize = list.size - 1;
    list.size = nextSize;
    if (nextSize > index) {
      bounded(nextSize);
      BoostEntry[] entries = required(list.entries);
      System.arraycopy(entries, index + 1, entries, index, nextSize - index);
    }
    BoostEntry[] current = required(list.entries);
    index(nextSize, current.length);
    current[nextSize] = null;
    list.version++;
  }

  private static void index(int index, int size) {
    if (Integer.compareUnsigned(index, size) >= 0)
      throw new IndexOutOfBoundsException("Invalid unsigned BP entry index");
    if (Integer.compareUnsigned(index, 255) >= 0)
      throw new IllegalArgumentException("Excessive finite BP entry index");
  }

  private static void bounded(int size) {
    if (size > 255) throw new IllegalArgumentException("Excessive finite BP withdrawal");
  }

  private static <T> T required(T value) {
    if (value == null) throw new IllegalArgumentException("Missing explicit BP withdrawal state");
    return value;
  }
}
