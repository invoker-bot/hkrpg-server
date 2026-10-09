package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueOwnedParameterLists46.References;

/** Ordinary collection and iterator branches of the compiled effect-list range operations. */
public final class RogueEffectListRange46 {
  public static final int LIMIT = 4096;

  private RogueEffectListRange46() {}

  /** Explicit Java representation of a caught native wrapper; its stored exception may be null. */
  public static final class ProjectedException extends RuntimeException {
    public final RuntimeException original;

    public ProjectedException(RuntimeException original) {
      super(null, null, false, false);
      this.original = original;
    }
  }

  public interface World extends RogueFadeListCapacity46.World {
    boolean isGenericCollection(Object source);

    boolean isNonGenericCollection(Object source);

    int genericCount(Object source);

    int nonGenericCount(Object source);

    void genericCopyTo(Object source, Object[] destination, int index);

    void nonGenericCopyTo(Object source, Object[] destination, int index);

    Object getEnumerator(Object source);

    boolean moveNext(Object iterator);

    Object current(Object iterator);

    void dispose(Object iterator);
  }

  public static void insertRange(References list, int index, Object source, World world) {
    require(source, "range source");
    require(list, "range target");
    index(list, index);
    World bound = require(world, "range bindings");
    boolean generic = bound.isGenericCollection(source);
    if (generic || bound.isNonGenericCollection(source)) {
      int count = generic ? bound.genericCount(source) : bound.nonGenericCount(source);
      if (count > 0) {
        finite(count);
        int total = list.size + count;
        Object[] storage = require(list.entries, "range storage");
        if (total > storage.length)
          RogueFadeListCapacity46.setCapacity(list, growth(storage.length, total), bound);
        int shifted = list.size - index;
        if (shifted > 0) {
          finite(shifted);
          Object[] current = list.entries;
          bound.copy(current, index, current, index + count, shifted);
        }
        Object[] destination = list.entries;
        if (source == list) {
          finite(index);
          bound.copy(destination, 0, destination, index, index);
          Object[] current = list.entries;
          int remaining = list.size - index;
          finite(remaining);
          bound.copy(current, count + index, current, index + index, remaining);
        } else if (generic) bound.genericCopyTo(source, destination, index);
        else bound.nonGenericCopyTo(source, destination, index);
        list.size += count;
      }
    } else if (list.size > index) {
      Object iterator = bound.getEnumerator(source);
      RuntimeException failure = null;
      boolean completed = false;
      try {
        require(iterator, "range iterator");
        int steps = 0;
        while (bound.moveNext(iterator)) {
          finite(++steps);
          Object item = bound.current(iterator);
          insert(list, index, item, bound);
          index++;
        }
        completed = true;
      } catch (RuntimeException caught) {
        failure = caught instanceof ProjectedException projected ? projected.original : caught;
      }
      if (iterator != null) bound.dispose(iterator);
      if (failure != null) throw failure;
      if (!completed) addRange(list, source, bound);
    } else addRange(list, source, bound);
    list.version++;
  }

  public static void insert(References list, int index, Object value, World world) {
    require(list, "insert target");
    index(list, index);
    Object[] storage = require(list.entries, "insert storage");
    int count = list.size;
    if (count == storage.length)
      RogueFadeListCapacity46.setCapacity(list, growth(storage.length, count + 1), world);
    int shifted = list.size - index;
    if (shifted > 0) {
      finite(shifted);
      Object[] current = list.entries;
      require(world, "insert copy binding").copy(current, index, current, index + 1, shifted);
    }
    storage = require(list.entries, "insert current storage");
    element(storage, index);
    storage[index] = value;
    list.size++;
    list.version++;
  }

  public static void addRange(References list, Object source, World world) {
    require(list, "append target");
    list.version++;
    require(source, "append source");
    World bound = require(world, "append bindings");
    Object iterator = bound.getEnumerator(source);
    RuntimeException failure = null;
    try {
      require(iterator, "append iterator");
      int steps = 0;
      while (bound.moveNext(iterator)) {
        finite(++steps);
        Object value = bound.current(iterator);
        Object[] storage = require(list.entries, "append storage");
        int index = list.size;
        if (index == storage.length) {
          RogueFadeListCapacity46.setCapacity(list, growth(storage.length, index + 1), bound);
          storage = list.entries;
          index = list.size;
        }
        list.size = index + 1;
        storage = require(storage, "append current storage");
        element(storage, index);
        storage[index] = value;
      }
    } catch (RuntimeException caught) {
      failure = caught instanceof ProjectedException projected ? projected.original : caught;
    }
    if (iterator != null) bound.dispose(iterator);
    if (failure != null) throw failure;
  }

  public static int growth(int length, int minimum) {
    int doubled = length + length;
    int next =
        length == 0 ? 4 : Integer.compareUnsigned(doubled, 0x7fefffff) < 0 ? doubled : 0x7fefffff;
    return next <= minimum ? minimum : next;
  }

  private static void index(References list, int index) {
    if (Integer.compareUnsigned(list.size, index) < 0)
      throw new IllegalArgumentException("Effect insertion index exceeds current count");
  }

  private static void element(Object[] storage, int index) {
    if (Integer.compareUnsigned(index, storage.length) >= 0)
      throw new IllegalArgumentException("Effect insertion index outside storage");
  }

  private static void finite(int count) {
    if (count > LIMIT) throw new IllegalArgumentException("Excessive finite effect range");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
