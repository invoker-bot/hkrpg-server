package org.hkrpg.server.gameplay;

import java.util.Arrays;

/** Finite ordinary event payload construction and clear, with an explicit shared enum catalog. */
public final class RogueEventPayload46 {
  private RogueEventPayload46() {}

  public static final class Catalog {
    public Object noneEnum;

    public Catalog(Object noneEnum) {
      this.noneEnum = noneEnum;
    }
  }

  public record ValueEntry(int hash, int next, int key, Object value) {}

  public static final class Values {
    public int[] buckets;
    public ValueEntry[] entries;
    public int count, version, freeList = -1, freeCount;
  }

  public static final class Context {
    public Values values;
  }

  public static final class State extends RogueDepartedTransition46.Event {
    public Object entity,
        target,
        arbitraryArray,
        hitEvent,
        attack,
        trigger,
        breakEvent,
        skill,
        attackInfo,
        createEntity,
        component,
        otherEntity,
        eventEnum;
    public RogueOwnedParameterLists46.References entities;
    public Context context;
    public String[] strings;
    public long[] values;
    public int propertyReason, modifierEvent, flags;
  }

  public static State create(Catalog catalog) {
    State state = new State();
    initialize(state, catalog);
    return state;
  }

  public static void initialize(State state, Catalog catalog) {
    require(state, "event payload");
    state.entities = new RogueOwnedParameterLists46.References(new Object[0], 0, 0);
    state.eventEnum = require(catalog, "event enum catalog").noneEnum;
    state.values = new long[3];
    state.strings = new String[3];
  }

  public static void clear(State state, Catalog catalog) {
    require(state, "event payload");
    state.ownerEntity = null;
    state.entity = null;
    state.trigger = null;
    state.component = null;
    state.otherEntity = null;
    clearEntities(require(state.entities, "payload entity references"));
    state.flags = 0;
    state.hitEvent = null;
    state.arbitraryArray = null;
    state.eventEnum = require(catalog, "event enum catalog").noneEnum;
    state.breakEvent = null;
    state.propertyReason = 0;
    state.modifierEvent = 0;
    state.attackInfo = null;
    state.createEntity = null;
    for (int i = 0; i < 3; i++) {
      long[] numbers = require(state.values, "payload values");
      if (i >= numbers.length) throw new IllegalArgumentException("Missing payload value slot");
      numbers[i] = 0;
    }
    for (int i = 0; i < 3; i++) {
      String[] strings = require(state.strings, "payload strings");
      if (i >= strings.length) throw new IllegalArgumentException("Missing payload string slot");
      strings[i] = null;
    }
    Context captured = state.context;
    if (captured != null) clearValues(require(captured.values, "payload context values"));
    state.target = null;
    state.skill = null;
    state.attack = null;
  }

  private static void clearEntities(RogueOwnedParameterLists46.References list) {
    list.version++;
    int previous = list.size;
    list.size = 0;
    if (previous <= 0) return;
    bound(previous, 255, "entity reference count");
    Arrays.fill(require(list.entries, "payload entity storage"), 0, previous, null);
  }

  private static void clearValues(Values dictionary) {
    int previous = dictionary.count;
    if (previous > 0) {
      int[] buckets = require(dictionary.buckets, "payload value buckets");
      bound(buckets.length, 4096, "value bucket capacity");
      Arrays.fill(buckets, 0);
      dictionary.count = 0;
      dictionary.freeList = -1;
      dictionary.freeCount = 0;
      bound(previous, 255, "value entry count");
      Arrays.fill(require(dictionary.entries, "payload value entries"), 0, previous, null);
    }
    dictionary.version++;
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
