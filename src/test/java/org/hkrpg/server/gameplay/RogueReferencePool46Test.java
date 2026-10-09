package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueReferencePool46Test {
  static class Payload {
    int clears;

    @Override
    public boolean equals(Object o) {
      return o instanceof Payload;
    }
  }

  static class Derived extends Payload {}

  static final RogueReferencePool46.Method INIT = new RogueReferencePool46.Method(2, 0);

  static RogueReferencePool46.Type type() {
    return new RogueReferencePool46.Type(
        Payload.class, null, false, new RogueReferencePool46.Method[] {INIT});
  }

  static RogueReferencePool46.Pool pool() {
    return new RogueReferencePool46.Pool(new HashMap<>());
  }

  static RogueReferencePool46.Collection collection(Object[] entries, int count) {
    return new RogueReferencePool46.Collection(entries, type(), false, count);
  }

  static class World implements RogueReferencePool46.World {
    final List<String> calls = new ArrayList<>();
    Object allocated = new Payload();
    Runnable initialize = () -> {},
        allocCallback = () -> {},
        recycleCallback = () -> {},
        clearCallback = () -> {},
        typeCallback = () -> {};
    boolean reference = true, extended = true;
    int checks;
    RogueReferencePool46.Type routed = type();
    RogueReferencePool46.Method invoked;

    @Override
    public Object allocate(RogueReferencePool46.Type t) {
      calls.add("allocate");
      return allocated;
    }

    @Override
    public void invokeInitializer(Object value, RogueReferencePool46.Method m) {
      calls.add("initialize");
      assertSame(allocated, value);
      invoked = m;
      initialize.run();
    }

    @Override
    public boolean isReference(Object value) {
      checks++;
      return reference;
    }

    @Override
    public boolean isReferenceEx(Object value) {
      return extended;
    }

    @Override
    public void onAlloc(Object value) {
      calls.add("onAlloc");
      allocCallback.run();
    }

    @Override
    public void onRecycle(Object value) {
      calls.add("onRecycle");
      recycleCallback.run();
    }

    @Override
    public void clear(Object value) {
      calls.add("clear");
      if (value instanceof Payload p) p.clears++;
      clearCallback.run();
    }

    @Override
    public RogueReferencePool46.Type typeOf(Class<?> c) {
      calls.add("typeOf");
      typeCallback.run();
      return routed;
    }
  }

  static void acquire(RogueReferencePool46.Collection c, World w) {
    RogueReferencePool46.acquire(c, type(), w);
  }

  static void release(
      RogueReferencePool46.Collection c, Object p, RogueReferencePool46.Pool pool, World w) {
    RogueReferencePool46.release(c, p, pool, w);
  }

  static Runnable fail() {
    return () -> {
      throw new IllegalStateException("callback");
    };
  }

  @Test
  void absentCapacityUsesSixteenAndIndependentStorage() {
    var t = type();
    var a = RogueReferencePool46.createCollection(t);
    var b = RogueReferencePool46.createCollection(t);
    assertEquals(16, a.references.length);
    assertEquals(0, a.count);
    assertSame(t, a.referenceType);
    assertFalse(a.extended);
    assertNotSame(a.references, b.references);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 16, 4096})
  void explicitCapacityRetained(int capacity) {
    var t = type();
    t.capacity = capacity;
    t.extended = true;
    var c = RogueReferencePool46.createCollection(t);
    assertEquals(capacity, c.references.length);
    assertTrue(c.extended);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, Integer.MIN_VALUE, 4097, Integer.MAX_VALUE})
  void unsupportedCapacityRejected(int capacity) {
    var t = type();
    t.capacity = capacity;
    assertThrows(IllegalArgumentException.class, () -> RogueReferencePool46.createCollection(t));
  }

  @Test
  void nullTypeFailsWithoutCollection() {
    assertThrows(IllegalArgumentException.class, () -> RogueReferencePool46.createCollection(null));
  }

  @Test
  void existingCollectionBypassesFactoryAndAllowsStoredNull() {
    var p = pool();
    var existing = collection(null, -1);
    p.collections.put(Payload.class, existing);
    assertSame(existing, RogueReferencePool46.getCollection(p, Payload.class, null));
    p.collections.put(Payload.class, null);
    assertNull(RogueReferencePool46.getCollection(p, Payload.class, null));
  }

  @Test
  void missingCollectionCreatedOnlyAfterConstructorSuccess() {
    var p = pool();
    var w = new World();
    var c = RogueReferencePool46.getCollection(p, Payload.class, w);
    assertSame(c, p.collections.get(Payload.class));
    assertEquals(List.of("typeOf"), w.calls);
  }

  @Test
  void constructionFailureDoesNotPublish() {
    var p = pool();
    var w = new World();
    w.routed.capacity = -1;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueReferencePool46.getCollection(p, Payload.class, w));
    assertTrue(p.collections.isEmpty());
  }

  @Test
  void nullMapFailsBeforeTypeFactory() {
    var p = pool();
    p.collections = null;
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueReferencePool46.getCollection(p, Payload.class, w));
    assertTrue(w.calls.isEmpty());
  }

  @Test
  void factoryReplacementOfDictionaryReceivesCollection() {
    var p = pool();
    Map<Class<?>, RogueReferencePool46.Collection> old = p.collections;
    var w = new World();
    w.typeCallback = () -> p.collections = new HashMap<>();
    var c = RogueReferencePool46.getCollection(p, Payload.class, w);
    assertTrue(old.isEmpty());
    assertSame(c, p.collections.get(Payload.class));
  }

  @Test
  void factoryRegistrationCollisionRetainsExistingValue() {
    var p = pool();
    var original = collection(null, 1);
    var w = new World();
    w.typeCallback = () -> p.collections.put(Payload.class, original);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueReferencePool46.getCollection(p, Payload.class, w));
    assertSame(original, p.collections.get(Payload.class));
  }

  @Test
  void factoryRemovingDictionaryFailsBeforePublication() {
    var p = pool();
    var w = new World();
    w.typeCallback = () -> p.collections = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueReferencePool46.getCollection(p, Payload.class, w));
    assertNull(p.collections);
  }

  @Test
  void reusePopsLastIdentityAndClearsOnlyTail() {
    Object first = new Payload(), last = new Payload(), extra = new Object();
    var c = collection(new Object[] {first, last, extra}, 2);
    var old = c.references;
    assertSame(last, RogueReferencePool46.acquire(c, type(), null));
    assertEquals(1, c.count);
    assertArrayEquals(new Object[] {first, null, extra}, old);
    assertSame(old, c.references);
  }

  @Test
  void derivedCompatibleReferenceCanBeReused() {
    var value = new Derived();
    var c = collection(new Object[] {value}, 1);
    assertSame(value, RogueReferencePool46.acquire(c, type(), null));
    assertEquals(0, c.count);
    assertNull(c.references[0]);
  }

  @Test
  void nullPooledSlotAllowedWithoutExtendedCallback() {
    var c = collection(new Object[] {null}, 1);
    assertNull(RogueReferencePool46.acquire(c, type(), null));
    assertEquals(0, c.count);
  }

  @Test
  void nullStorageRetainsDecrement() {
    var c = collection(null, 3);
    assertThrows(IllegalArgumentException.class, () -> acquire(c, new World()));
    assertEquals(2, c.count);
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 17, Integer.MAX_VALUE})
  void invalidPoppedIndexRetainsDecrementAndSlots(int count) {
    var value = new Payload();
    var c = collection(new Object[] {value}, count);
    assertThrows(IllegalArgumentException.class, () -> acquire(c, new World()));
    assertEquals(count - 1, c.count);
    assertSame(value, c.references[0]);
  }

  @Test
  void wrongPoppedTypeIsNotCleared() {
    Object wrong = new Object();
    var c = collection(new Object[] {wrong}, 1);
    assertThrows(IllegalArgumentException.class, () -> acquire(c, new World()));
    assertEquals(0, c.count);
    assertSame(wrong, c.references[0]);
  }

  @Test
  void missingDeclaredTypeKeepsPoppedReference() {
    var value = new Payload();
    var c = collection(new Object[] {value}, 1);
    assertThrows(IllegalArgumentException.class, () -> RogueReferencePool46.acquire(c, null, null));
    assertEquals(0, c.count);
    assertSame(value, c.references[0]);
  }

  @Test
  void missingDeclaredRuntimeClassKeepsPoppedReference() {
    var value = new Payload();
    var c = collection(new Object[] {value}, 1);
    var t = new RogueReferencePool46.Type(null, null, false, null);
    assertThrows(IllegalArgumentException.class, () -> RogueReferencePool46.acquire(c, t, null));
    assertEquals(0, c.count);
    assertSame(value, c.references[0]);
  }

  @Test
  void extendedReuseSeesTailAlreadyCleared() {
    var value = new Payload();
    var c = collection(new Object[] {value}, 1);
    c.extended = true;
    var w = new World();
    w.allocCallback =
        () -> {
          assertEquals(0, c.count);
          assertNull(c.references[0]);
        };
    assertSame(value, RogueReferencePool46.acquire(c, type(), w));
    assertEquals(List.of("onAlloc"), w.calls);
  }

  @Test
  void extendedNullFailsAfterTailCleared() {
    var c = collection(new Object[] {null}, 1);
    c.extended = true;
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> acquire(c, w));
    assertEquals(0, c.count);
    assertTrue(w.calls.isEmpty());
  }

  @Test
  void failedExtendedCompatibilityRetainsPop() {
    var value = new Payload();
    var c = collection(new Object[] {value}, 1);
    c.extended = true;
    var w = new World();
    w.extended = false;
    assertThrows(IllegalArgumentException.class, () -> acquire(c, w));
    assertEquals(0, c.count);
    assertNull(c.references[0]);
    assertTrue(w.calls.isEmpty());
  }

  @Test
  void allocationCallbackFailureDoesNotPushBack() {
    var c = collection(new Object[] {new Payload()}, 1);
    c.extended = true;
    var w = new World();
    w.allocCallback = fail();
    assertThrows(IllegalStateException.class, () -> acquire(c, w));
    assertEquals(0, c.count);
    assertNull(c.references[0]);
  }

  @Test
  void allocationCallbackRepopulationIsPreserved() {
    var original = new Payload();
    var next = new Payload();
    var c = collection(new Object[] {original}, 1);
    c.extended = true;
    var w = new World();
    w.allocCallback =
        () -> {
          c.references = new Object[] {next};
          c.count = 1;
        };
    assertSame(original, RogueReferencePool46.acquire(c, type(), w));
    assertSame(next, c.references[0]);
    assertEquals(1, c.count);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void nonpositiveCountConstructsAndRetainsStorage(int count) {
    Object marker = new Object();
    var c = collection(new Object[] {marker}, count);
    var w = new World();
    assertSame(w.allocated, RogueReferencePool46.acquire(c, type(), w));
    assertEquals(List.of("allocate", "initialize"), w.calls);
    assertEquals(count, c.count);
    assertSame(marker, c.references[0]);
  }

  @Test
  void firstBitTwoZeroParameterMethodSelected() {
    var a = new RogueReferencePool46.Method(0, 0);
    var b = new RogueReferencePool46.Method(2, 1);
    var first = new RogueReferencePool46.Method(6, 0);
    var last = new RogueReferencePool46.Method(2, 0);
    var t = type();
    t.methods = new RogueReferencePool46.Method[] {a, b, first, last};
    var w = new World();
    assertSame(w.allocated, RogueReferencePool46.acquire(collection(null, 0), t, w));
    assertSame(first, w.invoked);
  }

  @Test
  void noMatchingMethodAllocatesButReturnsNullWithoutCallback() {
    var t = type();
    t.methods = new RogueReferencePool46.Method[] {new RogueReferencePool46.Method(0, 0)};
    var w = new World();
    assertNull(RogueReferencePool46.acquire(collection(null, 0), t, w));
    assertEquals(List.of("allocate"), w.calls);
  }

  @Test
  void noMatchingMethodWithExtendedCollectionFailsAfterAllocation() {
    var t = type();
    t.methods = new RogueReferencePool46.Method[0];
    var c = collection(null, 0);
    c.extended = true;
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> RogueReferencePool46.acquire(c, t, w));
    assertEquals(List.of("allocate"), w.calls);
    assertEquals(0, c.count);
  }

  @Test
  void initializerFailureDoesNotCallOnAllocOrPublish() {
    var c = collection(null, 0);
    c.extended = true;
    var w = new World();
    w.initialize = fail();
    assertThrows(IllegalStateException.class, () -> acquire(c, w));
    assertEquals(List.of("allocate", "initialize"), w.calls);
    assertNull(c.references);
    assertEquals(0, c.count);
  }

  @Test
  void initializerUpdatesExtendedFlagBeforeAllocCallback() {
    var c = collection(null, 0);
    var w = new World();
    w.initialize = () -> c.extended = true;
    assertSame(w.allocated, RogueReferencePool46.acquire(c, type(), w));
    assertEquals(List.of("allocate", "initialize", "onAlloc"), w.calls);
  }

  @Test
  void nullMethodStorageFailsAfterAllocation() {
    var t = type();
    t.methods = null;
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueReferencePool46.acquire(collection(null, 0), t, w));
    assertEquals(List.of("allocate"), w.calls);
  }

  @Test
  void finiteMethodLimitFailsAfterAllocation() {
    var t = type();
    t.methods = new RogueReferencePool46.Method[256];
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueReferencePool46.acquire(collection(null, 0), t, w));
    assertEquals(List.of("allocate"), w.calls);
  }

  @Test
  void recycleCallbacksOrderedBeforeAppend() {
    var value = new Payload();
    var c = collection(new Object[2], 0);
    c.extended = true;
    var w = new World();
    w.recycleCallback = () -> assertEquals(0, c.count);
    w.clearCallback = () -> assertEquals(0, c.count);
    release(c, value, pool(), w);
    assertEquals(List.of("onRecycle", "clear"), w.calls);
    assertEquals(1, c.count);
    assertSame(value, c.references[0]);
    assertEquals(1, value.clears);
  }

  @Test
  void ordinaryReleaseStillClears() {
    var value = new Payload();
    var c = collection(new Object[1], 0);
    var w = new World();
    release(c, value, pool(), w);
    assertEquals(List.of("clear"), w.calls);
    assertEquals(1, c.count);
    assertSame(value, c.references[0]);
  }

  @Test
  void nullReferenceFailsBeforeCallbacks() {
    var c = collection(new Object[1], 0);
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> release(c, null, pool(), w));
    assertTrue(w.calls.isEmpty());
    assertEquals(0, c.count);
  }

  @Test
  void wrongExtendedInterfaceBypassesClearAndAppend() {
    var c = collection(new Object[1], 0);
    c.extended = true;
    var w = new World();
    w.extended = false;
    assertThrows(IllegalArgumentException.class, () -> release(c, new Payload(), pool(), w));
    assertTrue(w.calls.isEmpty());
    assertEquals(0, c.count);
  }

  @Test
  void onRecycleFailureBypassesClearAndAppend() {
    var c = collection(new Object[1], 0);
    c.extended = true;
    var w = new World();
    w.recycleCallback = fail();
    assertThrows(IllegalStateException.class, () -> release(c, new Payload(), pool(), w));
    assertEquals(List.of("onRecycle"), w.calls);
    assertEquals(0, c.count);
  }

  @Test
  void mandatoryClearCompatibilityFailsAfterOnRecycle() {
    var c = collection(new Object[1], 0);
    c.extended = true;
    var w = new World();
    w.reference = false;
    assertThrows(IllegalArgumentException.class, () -> release(c, new Payload(), pool(), w));
    assertEquals(List.of("onRecycle"), w.calls);
    assertEquals(0, c.count);
  }

  @Test
  void clearFailurePreservesCallbackChangesWithoutAppend() {
    var value = new Payload();
    var c = collection(new Object[1], 0);
    var w = new World();
    w.clearCallback = fail();
    assertThrows(IllegalStateException.class, () -> release(c, value, pool(), w));
    assertEquals(1, value.clears);
    assertEquals(0, c.count);
    assertNull(c.references[0]);
  }

  @Test
  void duplicateCheckHappensAfterClearEvenOnFailure() {
    var value = new Payload();
    var c = collection(new Object[] {value, null}, 1);
    var p = pool();
    p.strictCheck = true;
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> release(c, value, p, w));
    assertEquals(1, value.clears);
    assertEquals(1, c.count);
    assertSame(value, c.references[0]);
    assertEquals(List.of("clear"), w.calls);
  }

  @Test
  void equalButDifferentIdentityCanBeStoredUnderStrictCheck() {
    var a = new Payload();
    var b = new Payload();
    assertEquals(a, b);
    var c = collection(new Object[] {a, null}, 1);
    var p = pool();
    p.strictCheck = true;
    release(c, b, p, new World());
    assertEquals(2, c.count);
    assertSame(b, c.references[1]);
  }

  @Test
  void disabledStrictCheckAllowsDuplicateIdentity() {
    var value = new Payload();
    var c = collection(new Object[] {value, null}, 1);
    release(c, value, pool(), new World());
    assertEquals(2, c.count);
    assertSame(value, c.references[1]);
  }

  @Test
  void clearRemovalOfExistingIdentityAllowsRelease() {
    var value = new Payload();
    var c = collection(new Object[] {value, null}, 1);
    var p = pool();
    p.strictCheck = true;
    var w = new World();
    w.clearCallback =
        () -> {
          c.references[0] = null;
          c.count = 0;
        };
    release(c, value, p, w);
    assertEquals(1, c.count);
    assertSame(value, c.references[0]);
  }

  @Test
  void clearInsertionOfSameReferenceFailsDuplicateCheck() {
    var value = new Payload();
    var c = collection(new Object[1], 0);
    var p = pool();
    p.strictCheck = true;
    var w = new World();
    w.clearCallback =
        () -> {
          c.references[0] = value;
          c.count = 1;
        };
    assertThrows(IllegalArgumentException.class, () -> release(c, value, p, w));
    assertEquals(1, c.count);
  }

  @Test
  void clearEnablingStrictCheckAffectsCurrentRelease() {
    var value = new Payload();
    var c = collection(new Object[] {value, null}, 1);
    var p = pool();
    var w = new World();
    w.clearCallback = () -> p.strictCheck = true;
    assertThrows(IllegalArgumentException.class, () -> release(c, value, p, w));
    assertEquals(1, c.count);
  }

  @Test
  void clearReplacementOfStorageAndCountUsedForAppend() {
    var value = new Payload();
    var c = collection(new Object[1], 0);
    var old = c.references;
    var w = new World();
    w.clearCallback =
        () -> {
          c.references = new Object[3];
          c.count = 2;
        };
    release(c, value, pool(), w);
    assertEquals(3, c.count);
    assertSame(value, c.references[2]);
    assertNull(old[0]);
  }

  @Test
  void strictPrefixBoundsFailureDoesNotIncrementCount() {
    var c = collection(new Object[1], 2);
    var p = pool();
    p.strictCheck = true;
    var value = new Payload();
    assertThrows(IllegalArgumentException.class, () -> release(c, value, p, new World()));
    assertEquals(1, value.clears);
    assertEquals(2, c.count);
  }

  @Test
  void strictScanLimitFailureKeepsHeaderAndClear() {
    var c = collection(new Object[4096], 4097);
    var p = pool();
    p.strictCheck = true;
    var value = new Payload();
    assertThrows(IllegalArgumentException.class, () -> release(c, value, p, new World()));
    assertEquals(4097, c.count);
    assertEquals(1, value.clears);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, -1})
  void missingReleaseStorageFailsAfterClear(int count) {
    var c = collection(null, count);
    var value = new Payload();
    assertThrows(IllegalArgumentException.class, () -> release(c, value, pool(), new World()));
    assertEquals(1, value.clears);
    assertEquals(count, c.count);
  }

  @Test
  void growthDoublesCountAndCopiesFullOldArray() {
    var marker = new Payload();
    var value = new Payload();
    var c = collection(new Object[] {marker}, 1);
    var old = c.references;
    release(c, value, pool(), new World());
    assertEquals(2, c.references.length);
    assertNotSame(old, c.references);
    assertSame(marker, c.references[0]);
    assertSame(value, c.references[1]);
    assertEquals(2, c.count);
  }

  @Test
  void oversizedHeaderDoublesHeaderRatherThanOldCapacity() {
    var marker = new Object();
    var value = new Payload();
    var c = collection(new Object[] {marker}, 3);
    release(c, value, pool(), new World());
    assertEquals(6, c.references.length);
    assertSame(marker, c.references[0]);
    assertNull(c.references[1]);
    assertNull(c.references[2]);
    assertSame(value, c.references[3]);
    assertEquals(4, c.count);
  }

  @Test
  void emptyZeroCapacityDoesNotInventFourSlots() {
    var c = collection(new Object[0], 0);
    var old = c.references;
    var value = new Payload();
    assertThrows(IllegalArgumentException.class, () -> release(c, value, pool(), new World()));
    assertSame(old, c.references);
    assertEquals(0, c.references.length);
    assertEquals(1, c.count);
    assertEquals(1, value.clears);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, Integer.MIN_VALUE})
  void negativeIndexPublishesIncrementBeforeUnsignedFailure(int count) {
    var marker = new Object();
    var c = collection(new Object[] {marker}, count);
    var value = new Payload();
    assertThrows(IllegalArgumentException.class, () -> release(c, value, pool(), new World()));
    assertEquals(count + 1, c.count);
    assertSame(marker, c.references[0]);
    assertEquals(1, value.clears);
  }

  @ParameterizedTest
  @ValueSource(ints = {2049, 4096, Integer.MAX_VALUE})
  void growthCapacityLimitRetainsCountAfterClear(int count) {
    var c = collection(new Object[1], count);
    var old = c.references;
    var value = new Payload();
    assertThrows(IllegalArgumentException.class, () -> release(c, value, pool(), new World()));
    assertEquals(count, c.count);
    assertSame(old, c.references);
    assertEquals(1, value.clears);
  }

  @Test
  void storageCompatibilityFailureKeepsPublishedCount() {
    var c = collection(new Object[1], 0);
    var value = new Payload();
    var w = new World();
    w.clearCallback = () -> w.reference = false;
    assertThrows(IllegalArgumentException.class, () -> release(c, value, pool(), w));
    assertEquals(1, c.count);
    assertNull(c.references[0]);
    assertEquals(1, value.clears);
  }

  @Test
  void missingPoolFailsOnlyAfterClear() {
    var value = new Payload();
    var c = collection(new Object[1], 0);
    assertThrows(IllegalArgumentException.class, () -> release(c, value, null, new World()));
    assertEquals(1, value.clears);
    assertEquals(0, c.count);
  }

  @Test
  void retryAfterClearFailureCanAppendSameIdentity() {
    var c = collection(new Object[1], 0);
    var value = new Payload();
    var w = new World();
    w.clearCallback = fail();
    assertThrows(IllegalStateException.class, () -> release(c, value, pool(), w));
    w.clearCallback = () -> {};
    release(c, value, pool(), w);
    assertEquals(2, value.clears);
    assertEquals(1, c.count);
    assertSame(value, c.references[0]);
  }

  @Test
  void exactDeclaredTypeUsesTypedHolderWithoutDictionary() {
    var value = new Payload();
    var c = collection(new Object[1], 0);
    var p = pool();
    p.collections = null;
    var w = new World();
    RogueReferencePool46.releaseTyped(p, c, type(), value, w);
    assertEquals(List.of("clear"), w.calls);
    assertSame(value, c.references[0]);
  }

  @Test
  void derivedTypeUsesActualTypeCollectionEvenWhenTypedHolderMissing() {
    var p = pool();
    var actual = collection(new Object[1], 0);
    p.collections.put(Derived.class, actual);
    var value = new Derived();
    RogueReferencePool46.releaseTyped(p, null, type(), value, new World());
    assertSame(value, actual.references[0]);
    assertEquals(1, actual.count);
  }

  @Test
  void derivedTypeFactoryFailureDoesNotClearPayload() {
    var p = pool();
    var w = new World();
    w.routed.capacity = -1;
    var value = new Derived();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueReferencePool46.releaseTyped(p, null, type(), value, w));
    assertEquals(0, value.clears);
    assertTrue(p.collections.isEmpty());
  }

  @Test
  void typedNullReleaseRejectsBeforeAnyTypeOrPoolLookup() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueReferencePool46.releaseTyped(null, null, null, null, null));
  }

  @Test
  void exactTypedMissingHolderFailsBeforeClear() {
    var value = new Payload();
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueReferencePool46.releaseTyped(pool(), null, type(), value, w));
    assertTrue(w.calls.isEmpty());
  }

  @Test
  void typedAcquireUsesSuppliedHolderAndDeclaredType() {
    var value = new Payload();
    var c = collection(new Object[] {value}, 1);
    assertSame(value, RogueReferencePool46.acquireTyped(c, type(), null));
    assertNull(c.references[0]);
  }

  @Test
  void actualEventConstructorAndClearReuseSamePayloadContainers() {
    Object none = new Object();
    var catalog = new RogueEventPayload46.Catalog(none);
    var t =
        new RogueReferencePool46.Type(
            RogueEventPayload46.State.class, null, false, new RogueReferencePool46.Method[] {INIT});
    var w =
        new World() {
          @Override
          public Object allocate(RogueReferencePool46.Type type) {
            allocated = new RogueEventPayload46.State();
            return allocated;
          }

          @Override
          public void invokeInitializer(Object value, RogueReferencePool46.Method m) {
            RogueEventPayload46.initialize((RogueEventPayload46.State) value, catalog);
          }

          @Override
          public void clear(Object value) {
            RogueEventPayload46.clear((RogueEventPayload46.State) value, catalog);
          }
        };
    var c = RogueReferencePool46.createCollection(t);
    var p = pool();
    var first = (RogueEventPayload46.State) RogueReferencePool46.acquire(c, t, w);
    var entities = first.entities;
    var numbers = first.values;
    var strings = first.strings;
    first.ownerEntity = new Object();
    first.values[1] = 4;
    first.strings[2] = "x";
    RogueReferencePool46.releaseTyped(p, c, t, first, w);
    assertEquals(1, c.count);
    assertNull(first.ownerEntity);
    assertArrayEquals(new long[3], numbers);
    assertArrayEquals(new String[3], strings);
    assertSame(none, first.eventEnum);
    var second = RogueReferencePool46.acquire(c, t, w);
    assertSame(first, second);
    assertSame(entities, first.entities);
    assertSame(numbers, first.values);
    assertSame(strings, first.strings);
    assertEquals(0, c.count);
  }

  @Test
  void eventConstructorWritesOnlyItsDeclaredInitializationFields() {
    Object none = new Object();
    var s = new RogueEventPayload46.State();
    Object owner = new Object();
    s.ownerEntity = owner;
    s.flags = 3;
    RogueEventPayload46.initialize(s, new RogueEventPayload46.Catalog(none));
    assertSame(owner, s.ownerEntity);
    assertEquals(3, s.flags);
    assertSame(none, s.eventEnum);
    assertEquals(3, s.values.length);
    assertEquals(3, s.strings.length);
    assertEquals(0, s.entities.size);
  }

  @Test
  void eventConstructorFailurePreservesEntityListButNotArrays() {
    var s = new RogueEventPayload46.State();
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.initialize(s, null));
    assertNotNull(s.entities);
    assertNull(s.values);
    assertNull(s.strings);
  }

  @Test
  void realEventClearFailureDoesNotAppendAndKeepsClearedPrefix() {
    var catalog = new RogueEventPayload46.Catalog(new Object());
    var s = RogueEventPayload46.create(catalog);
    s.values = new long[] {1};
    s.ownerEntity = new Object();
    var c = collection(new Object[1], 0);
    var w =
        new World() {
          @Override
          public void clear(Object value) {
            RogueEventPayload46.clear((RogueEventPayload46.State) value, catalog);
          }
        };
    assertThrows(IllegalArgumentException.class, () -> release(c, s, pool(), w));
    assertEquals(0, c.count);
    assertNull(c.references[0]);
    assertNull(s.ownerEntity);
    assertEquals(0, s.values[0]);
  }

  static class EventHarness implements RogueDepartedTransition46.World {
    final Object none = new Object(), owner = new Object(), marker = new Object();
    final RogueEventPayload46.Catalog catalog = new RogueEventPayload46.Catalog(none);
    final RogueReferencePool46.Type declared =
        new RogueReferencePool46.Type(
            RogueEventPayload46.State.class, null, false, new RogueReferencePool46.Method[] {INIT});
    final RogueReferencePool46.Collection stored = RogueReferencePool46.createCollection(declared);
    final RogueReferencePool46.Pool registry = pool();
    final RogueOwnedParameterLists46.AbilityState ability =
        new RogueOwnedParameterLists46.AbilityState();
    final List<Integer> ids = new ArrayList<>();
    RogueEventPayload46.State event;
    int allocations, skip = -1;
    Runnable global = () -> {};
    final World nativeBindings =
        new World() {
          @Override
          public Object allocate(RogueReferencePool46.Type type) {
            allocations++;
            return new RogueEventPayload46.State();
          }

          @Override
          public void invokeInitializer(Object value, RogueReferencePool46.Method method) {
            RogueEventPayload46.initialize((RogueEventPayload46.State) value, catalog);
          }

          @Override
          public void clear(Object value) {
            RogueEventPayload46.clear((RogueEventPayload46.State) value, catalog);
          }
        };

    EventHarness() {
      ability.ownerEntity = owner;
    }

    @Override
    public RogueDepartedTransition46.Event acquireEvent() {
      event =
          (RogueEventPayload46.State)
              RogueReferencePool46.acquireTyped(stored, declared, nativeBindings);
      return event;
    }

    @Override
    public Object entityWorld(Object entity) {
      assertSame(owner, entity);
      return marker;
    }

    @Override
    public Object worldBattleInstance(Object world) {
      return skip == 0 ? null : marker;
    }

    @Override
    public Object battleGameMode(Object battle) {
      return skip == 1 ? null : marker;
    }

    @Override
    public Object eventProcessor(Object mode) {
      return skip == 2 ? null : marker;
    }

    @Override
    public void dispatchEntity(
        Object processor,
        Object entity,
        int id,
        RogueDepartedTransition46.Event value,
        Object source,
        int mask) {
      assertSame(event, value);
      assertSame(owner, event.ownerEntity);
      assertEquals(0, stored.count);
      assertNull(source);
      assertEquals(0, mask);
      event.values[0] = 7;
      event.strings[0] = "entity";
      ids.add(id);
    }

    @Override
    public void dispatchGlobal(
        Object processor,
        int id,
        RogueDepartedTransition46.Event value,
        Object source,
        int mask,
        Object extra) {
      assertSame(event, value);
      assertSame(owner, source);
      assertSame(owner, event.ownerEntity);
      assertEquals(7, event.values[0]);
      assertEquals("entity", event.strings[0]);
      assertEquals(0, stored.count);
      assertNull(extra);
      assertEquals(0, mask);
      ids.add(id);
      global.run();
    }

    @Override
    public void releaseEvent(RogueDepartedTransition46.Event value) {
      RogueReferencePool46.releaseTyped(registry, stored, declared, value, nativeBindings);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualDepartedTailDispatchesThenClearsAndRecyclesSamePayload(boolean previous) {
    var h = new EventHarness();
    RogueDepartedTransition46.dispatch(h.ability, previous, h);
    assertEquals(previous ? List.of(227, 229) : List.of(226, 228), h.ids);
    assertEquals(1, h.stored.count);
    assertSame(h.event, h.stored.references[0]);
    assertNull(h.event.ownerEntity);
    assertArrayEquals(new long[3], h.event.values);
    assertArrayEquals(new String[3], h.event.strings);
    assertSame(h.none, h.event.eventEnum);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void missingDispatchComponentStillClearsAndRecyclesPayload(int skip) {
    var h = new EventHarness();
    h.skip = skip;
    RogueDepartedTransition46.dispatch(h.ability, false, h);
    assertTrue(h.ids.isEmpty());
    assertEquals(1, h.stored.count);
    assertSame(h.event, h.stored.references[0]);
    assertNull(h.event.ownerEntity);
  }

  @Test
  void globalDispatchFailureDoesNotFinallyClearOrRecycle() {
    var h = new EventHarness();
    h.global = fail();
    assertThrows(
        IllegalStateException.class, () -> RogueDepartedTransition46.dispatch(h.ability, false, h));
    assertEquals(List.of(226, 228), h.ids);
    assertEquals(0, h.stored.count);
    assertNull(h.stored.references[0]);
    assertSame(h.owner, h.event.ownerEntity);
    assertEquals(7, h.event.values[0]);
  }

  @Test
  void actualDepartedReleaseClearFailureRetainsUnpooledPrefix() {
    var h = new EventHarness();
    h.global = () -> h.event.values = new long[] {8};
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueDepartedTransition46.dispatch(h.ability, false, h));
    assertEquals(0, h.stored.count);
    assertNull(h.stored.references[0]);
    assertNull(h.event.ownerEntity);
    assertEquals(0, h.event.values[0]);
    assertEquals("entity", h.event.strings[0]);
  }

  @Test
  void successiveActualDepartedTailsReuseOneAllocation() {
    var h = new EventHarness();
    RogueDepartedTransition46.dispatch(h.ability, false, h);
    var first = h.event;
    var refs = first.entities;
    RogueDepartedTransition46.dispatch(h.ability, true, h);
    assertSame(first, h.event);
    assertSame(refs, h.event.entities);
    assertEquals(1, h.allocations);
    assertEquals(List.of(226, 228, 227, 229), h.ids);
    assertEquals(1, h.stored.count);
  }
}
