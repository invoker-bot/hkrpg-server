package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueEventListenerWithdrawal46.PooledCallbacks;

/** Ordinary pooled-list wrapper construction and forwarding over its captured inner collection. */
public final class RogueEventPooledCallbacks46 {
  private RogueEventPooledCallbacks46() {}

  public interface World {
    boolean firstConstructorBranch();

    Object allocateInner(boolean firstBranch);

    void initializeInner(Object collection, boolean firstBranch);

    void addRange(Object collection, PooledCallbacks source);

    int count(Object collection);

    Object at(Object collection, int index);

    void release(Object collection, boolean rawFlag);
  }

  public static void initialize(PooledCallbacks list, World world) {
    require(list, "pooled callback wrapper");
    World bound = require(world, "pooled callback construction world");
    boolean first = bound.firstConstructorBranch();
    Object collection = bound.allocateInner(first);
    bound.initializeInner(collection, first);
    list.collection = collection;
  }

  public static void addRange(PooledCallbacks destination, PooledCallbacks source, World world) {
    Object collection = inner(destination);
    require(world, "pooled callback range world").addRange(collection, source);
  }

  public static int count(PooledCallbacks list, World world) {
    Object collection = inner(list);
    return require(world, "pooled callback count world").count(collection);
  }

  public static Object at(PooledCallbacks list, int index, World world) {
    Object collection = inner(list);
    return require(world, "pooled callback item world").at(collection, index);
  }

  public static void release(PooledCallbacks list, boolean rawFlag, World world) {
    Object collection = inner(list);
    require(world, "pooled callback release world").release(collection, rawFlag);
  }

  private static Object inner(PooledCallbacks list) {
    return require(
        require(list, "pooled callback wrapper").collection, "pooled callback inner collection");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
