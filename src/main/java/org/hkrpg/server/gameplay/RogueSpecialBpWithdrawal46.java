package org.hkrpg.server.gameplay;

/** Finite ordinary special BP totals, withdrawal and change notification over explicit states. */
public final class RogueSpecialBpWithdrawal46 {
  public static final int CHANGED_EVENT = 869;
  public static final int LIMIT = 255;

  private RogueSpecialBpWithdrawal46() {}

  public static final class DirtyEntries {
    public RogueModifierUnstack46.BpDirty[] entries;
    public int size, version;
  }

  public static final class Points {
    public Point head;
    public int size, version;
  }

  public static final class Point {
    public byte special;
    public Point previous, next;
    public boolean firstFlag;
    public String firstTag, secondTag;
    public int displayStyle, registrationId;
    public RogueModifierCleanup46.State modifier;
    public Points owner;
  }

  public static final class State {
    public DirtyEntries dirty;
    public Points points;
    public int optionalMaximum, fallbackMaximum;
    public boolean maximumPresent;
  }

  public interface World {
    void removeNormalPoint(State state, boolean value);

    void refresh(State state);

    void notifyGlobal(int event, int boxedIntValue, boolean immediate);
  }

  public static int reserved(State state) {
    DirtyEntries list = require(state, "special BP module").dirty;
    if (list == null || list.size == 0) return 0;
    int count = list.size, sum = 0;
    if (count > 0) {
      bound(count);
      var entries = require(list.entries, "special BP entries");
      for (int index = 0; index < count; index++) {
        index(index, entries.length);
        var entry = entries[index];
        sum += entry == null ? 0 : entry.value();
      }
    }
    return Math.min(sum, maximum(state));
  }

  public static int specialPointCount(State state) {
    Points list = require(require(state, "special BP module").points, "BP points");
    Point point = list.head;
    int count = 0, examined = 0;
    while (point != null) {
      bound(++examined);
      count += point.special & 1;
      Point next = point.next;
      if (next == null) return count;
      Points owner = require(point.owner, "BP point owner");
      if (next == owner.head) return count;
      point = next;
    }
    return count;
  }

  public static void remove(State state, Object modifier, World world) {
    int before = reserved(state);
    DirtyEntries list = require(state.dirty, "special BP registrations");
    int count = list.size;
    if (count <= 0) return;
    bound(count);
    var entries = require(list.entries, "special BP entries");
    for (int i = 0; i < count; i++) {
      index(i, entries.length);
      var entry = entries[i];
      if ((entry == null ? null : entry.modifier()) != modifier) continue;
      int next = list.size - 1;
      list.size = next;
      if (next > i) System.arraycopy(entries, i + 1, entries, i, next - i);
      var current = require(list.entries, "current special BP entries");
      index(list.size, current.length);
      current[list.size] = null;
      list.version++;
      int after = reserved(state);
      if (before != after) changed(state, before, after, world);
      return;
    }
  }

  public static void changed(State state, int before, int after, World world) {
    require(state, "special BP module");
    World bound = require(world, "special BP change world");
    if (after > before) {
      int available = require(state.points, "BP points").size - specialPointCount(state);
      int maximum = maximum(state);
      int unreserved = Math.max(maximum - after, 0);
      int excess = available - unreserved;
      if (excess > 0) {
        int newlyReserved = Math.max(maximum - before, 0) - unreserved;
        int remove = Math.min(excess, newlyReserved);
        if (remove > 0) {
          bound(remove);
          for (int i = 0; i < remove; i++) bound.removeNormalPoint(state, false);
        }
      }
    }
    bound.refresh(state);
    bound.notifyGlobal(CHANGED_EVENT, after, true);
  }

  private static int maximum(State state) {
    return state.maximumPresent ? state.optionalMaximum : state.fallbackMaximum;
  }

  private static void bound(int count) {
    if (count > LIMIT) throw new IllegalArgumentException("Excessive finite special BP entries");
  }

  private static void index(int index, int length) {
    if (Integer.compareUnsigned(index, length) >= 0)
      throw new IndexOutOfBoundsException("Special BP entry index");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
