package org.hkrpg.server.gameplay;

import java.util.Arrays;
import java.util.Map;

/** Ordinary reference-pool transitions with explicit allocation and interface bindings. */
public final class RogueReferencePool46 {
  private RogueReferencePool46() {}

  public static final int MAX_CAPACITY = 4096;

  public record Method(int flags, int parameterCount) {}

  public static final class Type {
    public final Class<?> runtimeClass;
    public Integer capacity;
    public boolean extended;
    public Method[] methods;

    public Type(Class<?> runtimeClass, Integer capacity, boolean extended, Method[] methods) {
      this.runtimeClass = runtimeClass;
      this.capacity = capacity;
      this.extended = extended;
      this.methods = methods;
    }
  }

  public static final class Collection {
    public Object[] references;
    public Type referenceType;
    public boolean extended;
    public int count;

    public Collection(Object[] references, Type referenceType, boolean extended, int count) {
      this.references = references;
      this.referenceType = referenceType;
      this.extended = extended;
      this.count = count;
    }
  }

  public static final class Pool {
    public Map<Class<?>, Collection> collections;
    public boolean strictCheck;

    public Pool(Map<Class<?>, Collection> collections) {
      this.collections = collections;
    }
  }

  public interface World {
    Object allocate(Type type);

    void invokeInitializer(Object reference, Method method);

    boolean isReference(Object reference);

    boolean isReferenceEx(Object reference);

    void onAlloc(Object reference);

    void onRecycle(Object reference);

    void clear(Object reference);

    Type typeOf(Class<?> runtimeClass);
  }

  public static Collection createCollection(Type type) {
    require(type, "reference type");
    int capacity = type.capacity == null ? 16 : type.capacity;
    bound(capacity);
    return new Collection(new Object[capacity], type, type.extended, 0);
  }

  public static Collection getCollection(Pool pool, Class<?> runtimeClass, World world) {
    Map<Class<?>, Collection> captured =
        require(require(pool, "reference pool").collections, "pool collections");
    require(runtimeClass, "runtime type");
    if (captured.containsKey(runtimeClass)) return captured.get(runtimeClass);
    Collection created = createCollection(require(world, "pool bindings").typeOf(runtimeClass));
    Map<Class<?>, Collection> current = require(pool.collections, "current pool collections");
    if (current.containsKey(runtimeClass))
      throw new IllegalArgumentException("Duplicate reference collection");
    current.put(runtimeClass, created);
    return created;
  }

  public static Object acquire(Collection collection, Type declaredType, World world) {
    require(collection, "reference collection");
    Object reference;
    if (collection.count > 0) {
      Object[] captured = collection.references;
      int index = --collection.count;
      require(captured, "pooled reference storage");
      index(index, captured.length);
      reference = captured[index];
      Type type = require(declaredType, "declared reference type");
      Class<?> expected = require(type.runtimeClass, "declared runtime class");
      if (reference != null && !expected.isInstance(reference))
        throw new IllegalArgumentException("Pooled reference has an incompatible type");
      Object[] current = require(collection.references, "current pooled reference storage");
      index(collection.count, current.length);
      current[collection.count] = null;
    } else {
      Type type = require(declaredType, "declared reference type");
      require(type.runtimeClass, "declared runtime class");
      World bindings = require(world, "pool bindings");
      reference = bindings.allocate(type);
      Method[] methods = require(type.methods, "compiled methods");
      if (methods.length > 255)
        throw new IllegalArgumentException("Compiled method count exceeds limit");
      Method selected = null;
      for (Method method : methods) {
        require(method, "compiled method");
        if ((method.flags() & 2) != 0 && method.parameterCount() == 0) {
          selected = method;
          break;
        }
      }
      if (selected == null) reference = null;
      else bindings.invokeInitializer(reference, selected);
    }
    if (collection.extended) {
      require(reference, "extended reference");
      World bindings = require(world, "pool bindings");
      if (!bindings.isReferenceEx(reference))
        throw new IllegalArgumentException("Reference does not implement IReferenceEx");
      bindings.onAlloc(reference);
    }
    return reference;
  }

  public static void release(Collection collection, Object reference, Pool pool, World world) {
    require(collection, "reference collection");
    World bindings;
    if (collection.extended) {
      require(reference, "extended reference");
      bindings = require(world, "pool bindings");
      if (!bindings.isReferenceEx(reference))
        throw new IllegalArgumentException("Reference does not implement IReferenceEx");
      bindings.onRecycle(reference);
    }
    require(reference, "reference");
    bindings = require(world, "pool bindings");
    if (!bindings.isReference(reference))
      throw new IllegalArgumentException("Reference does not implement IReference");
    bindings.clear(reference);
    boolean strict = require(pool, "reference pool").strictCheck;
    int count = collection.count;
    Object[] captured = collection.references;
    if (strict && count > 0) {
      require(captured, "pooled reference storage");
      if (count > MAX_CAPACITY) throw new IllegalArgumentException("Duplicate scan exceeds limit");
      for (int i = 0; i < count; i++) {
        index(i, captured.length);
        if (captured[i] == reference)
          throw new IllegalArgumentException("Reference already recycled");
      }
    }
    require(captured, "pooled reference storage");
    int index = count;
    if (count < captured.length) collection.count = count + 1;
    else {
      int capacity = count * 2;
      bound(capacity);
      if (capacity != captured.length) collection.references = Arrays.copyOf(captured, capacity);
      captured = collection.references;
      index = collection.count;
      collection.count = index + 1;
      require(captured, "resized reference storage");
    }
    if (!bindings.isReference(reference))
      throw new IllegalArgumentException("Reference cannot be stored in IReference array");
    index(index, captured.length);
    captured[index] = reference;
  }

  public static Object acquireTyped(Collection typedCollection, Type declaredType, World world) {
    return acquire(require(typedCollection, "typed reference collection"), declaredType, world);
  }

  public static void releaseTyped(
      Pool pool, Collection typedCollection, Type declaredType, Object reference, World world) {
    require(reference, "reference");
    Type declared = require(declaredType, "declared reference type");
    Collection selected =
        reference.getClass() == require(declared.runtimeClass, "declared runtime class")
            ? require(typedCollection, "typed reference collection")
            : getCollection(pool, reference.getClass(), world);
    release(selected, reference, pool, world);
  }

  private static void bound(int capacity) {
    if (Integer.compareUnsigned(capacity, MAX_CAPACITY) > 0)
      throw new IllegalArgumentException("Reference capacity exceeds limit");
  }

  private static void index(int index, int length) {
    if (Integer.compareUnsigned(index, length) >= 0)
      throw new IllegalArgumentException("Reference index exceeds storage");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
