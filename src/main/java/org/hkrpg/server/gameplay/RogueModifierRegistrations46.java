package org.hkrpg.server.gameplay;

import java.util.Arrays;

/** Ordinary modifier registration withdrawal with explicit world and mapping bindings. */
public final class RogueModifierRegistrations46 {
  private RogueModifierRegistrations46() {}

  public record Slot(int hash, int next, String key) {}

  public static final class Strings {
    public int[] buckets;
    public Slot[] slots;
    public int bucketLength, count, lastIndex, freeList = -1, version;
  }

  public static final class UpperData {
    public int[] values;
    public long[] wideValues;
    public int size, version;
    public boolean wideStorage;
  }

  public static final class UpperIds {
    public UpperData data;
  }

  public static final class State {
    public final RogueModifierCleanup46.State cleanup;
    public Strings names, bindKeys;
    public Object crazyBehavior;
    public UpperIds upperGroups;

    public State(RogueModifierCleanup46.State cleanup) {
      this.cleanup = cleanup;
    }
  }

  public record Lookup(boolean found, Object value) {}

  public static final class Behavior {
    public int priority;
    public int[] skillLayouts;
    public boolean firstFlag, secondFlag;

    public Behavior(int priority) {
      this.priority = priority;
    }
  }

  public static final class Ai {
    public RogueOwnedParameterLists46.References behaviors;
    public Object current;
  }

  public interface World {
    Object nameMapping(Object owner);

    void removeName(Object mapping, String key);

    Object bindMapping(Object owner);

    Lookup lookupBinding(Object mapping, String key);

    void recycleBinding(Object value);

    void removeBinding(Object mapping, String key);

    Object ownerEntity(Object owner);

    Ai aiComponent(Object entity);

    void sortAi(RogueOwnedParameterLists46.References list);

    Object defaultBehavior();

    Object entityWorld(Object entity);

    Object worldBattleInstance(Object world);

    Object battleGameMode(Object battle);

    Object publicKnowledge(Object gameMode);

    Object upperMap(Object knowledge);

    void removeUpper(Object upperMap, int rawUnsignedId);
  }

  public static void popNames(State state, World world) {
    popStrings(state, world, false);
  }

  public static void popBindings(State state, World world) {
    popStrings(state, world, true);
  }

  private static void popStrings(State state, World world, boolean bindings) {
    require(state, "modifier registrations");
    Strings captured = bindings ? state.bindKeys : state.names;
    if (captured == null || captured.count <= 0) return;
    if (owner(state) == null) return;
    int expected = captured.version, observed = expected, index = 0;
    while (true) {
      if ((bindings ? observed : captured.version) != expected)
        throw new IllegalArgumentException("Registration set modified during enumeration");
      int last = captured.lastIndex;
      if (index >= last) break;
      bound(last, 255, "registration prefix");
      Slot[] slots = require(captured.slots, "registration slots");
      Slot current = null;
      while (index < last) {
        if (index >= slots.length)
          throw new IllegalArgumentException("Registration slot exceeds storage");
        Slot slot = slots[index++];
        if (slot == null) slot = new Slot(0, 0, null);
        if (slot.hash() >= 0) {
          current = slot;
          break;
        }
      }
      if (current == null) break;
      Object currentOwner = require(owner(state), "owner ability");
      World bound = require(world, "registration world");
      Object mapping =
          bindings
              ? bound.bindMapping(currentOwner)
              : require(bound.nameMapping(currentOwner), "modifier name mapping");
      if (bindings && mapping == null) {
        observed = expected;
        continue;
      }
      String key = require(current.key(), "registration key");
      if (bindings) {
        Lookup lookup = require(bound.lookupBinding(mapping, key), "binding lookup");
        if (lookup.found()) bound.recycleBinding(lookup.value());
        bound.removeBinding(mapping, key);
        observed = captured.version;
      } else bound.removeName(mapping, key);
    }
    clear(require(bindings ? state.bindKeys : state.names, "current registration set"));
  }

  public static void clear(Strings set) {
    require(set, "registration set");
    int last = set.lastIndex;
    if (last > 0) {
      bound(last, 255, "registration prefix");
      Arrays.fill(require(set.slots, "registration slots"), 0, last, null);
      bound(set.bucketLength, 4096, "registration bucket prefix");
      Arrays.fill(require(set.buckets, "registration buckets"), 0, set.bucketLength, 0);
      set.count = 0;
      set.lastIndex = 0;
      set.freeList = -1;
    }
    set.version++;
  }

  public static int compareBehavior(Behavior left, Behavior right) {
    return Integer.compare(
        require(left, "AI behavior").priority, require(right, "other AI behavior").priority);
  }

  public static Object selectAi(Ai ai, World world) {
    require(ai, "AI component");
    RogueOwnedParameterLists46.References captured = ai.behaviors;
    if (captured == null || captured.size == 0) return require(world, "AI world").defaultBehavior();
    if (captured.size >= 2) {
      require(world, "AI world").sortAi(captured);
      captured = require(ai.behaviors, "current AI behavior list");
      if (captured.size == 0)
        throw new IllegalArgumentException("AI behavior list became empty after sort");
    }
    Object[] values = require(captured.entries, "AI behavior storage");
    if (values.length == 0) throw new IllegalArgumentException("Missing first AI behavior");
    return values[0];
  }

  public static void popAi(State state, World world) {
    require(state, "modifier registrations");
    if (state.crazyBehavior == null) return;
    Object owner = owner(state);
    if (owner != null) {
      World bound = require(world, "AI world");
      Object entity = bound.ownerEntity(owner);
      if (entity != null) {
        Ai ai = bound.aiComponent(entity);
        if (ai != null) {
          Object captured = state.crazyBehavior;
          if (ai.behaviors != null) RogueOwnedParameterLists46.removeFirst(ai.behaviors, captured);
          ai.current = selectAi(ai, bound);
        }
      }
    }
    state.crazyBehavior = null;
  }

  public static void popUpperGroups(State state, World world) {
    require(state, "modifier registrations");
    UpperIds initial = state.upperGroups;
    if (initial == null) return;
    if (require(initial.data, "upper group list").size == 0 || owner(state) == null) return;
    World bound = require(world, "upper group world");
    Object entity = require(bound.ownerEntity(owner(state)), "owner entity");
    Object entityWorld = bound.entityWorld(entity);
    if (entityWorld == null) return;
    Object battle = require(bound.worldBattleInstance(entityWorld), "world battle");
    Object mode = require(bound.battleGameMode(battle), "battle game mode");
    Object knowledge = require(bound.publicKnowledge(mode), "battle public knowledge");
    Object map = bound.upperMap(knowledge);
    if (map == null) return;
    UpperData captured =
        require(require(state.upperGroups, "current upper groups").data, "upper group list");
    int version = captured.version, index = 0;
    boolean wide = captured.wideStorage;
    while (true) {
      if (captured.version != version)
        throw new IllegalArgumentException("Upper group list modified during enumeration");
      int count = captured.size;
      if (Integer.compareUnsigned(index, count) >= 0) return;
      bound(count, 255, "upper group count");
      int key;
      if (wide) {
        long[] values = require(captured.wideValues, "upper group wide slots");
        if (index >= values.length)
          throw new IllegalArgumentException("Upper group index exceeds storage");
        key = (int) values[index++];
      } else {
        int[] values = require(captured.values, "upper group values");
        if (index >= values.length)
          throw new IllegalArgumentException("Upper group index exceeds storage");
        key = values[index++];
      }
      bound.removeUpper(map, key);
    }
  }

  private static Object owner(State state) {
    return require(state.cleanup, "modifier cleanup").ownerAbilityComponent;
  }

  private static void bound(int value, int limit, String name) {
    if (Integer.compareUnsigned(value, limit) > 0)
      throw new IllegalArgumentException("Compiled " + name + " exceeds limit");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
