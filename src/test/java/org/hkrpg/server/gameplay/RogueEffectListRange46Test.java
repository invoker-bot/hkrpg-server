package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.hkrpg.server.gameplay.RogueOwnedParameterLists46.References;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEffectListRange46Test {
  record Copy(Object[] source, int from, Object[] destination, int to, int count) {}

  static class World implements RogueEffectListRange46.World {
    List<String> calls = new ArrayList<>();
    List<Copy> copies = new ArrayList<>();
    boolean generic, nonGeneric;
    int count, cursor;
    Object[] values = new Object[0];
    Object iterator = new Object(), copiedSource;
    Object[] copiedDestination;
    int copiedIndex;
    Runnable classification = () -> {},
        countAction = () -> {},
        allocation = () -> {},
        copyAction = () -> {},
        copyTo = () -> {},
        enumerator = () -> {},
        move = () -> {},
        item = () -> {},
        disposal = () -> {};

    public boolean isGenericCollection(Object source) {
      calls.add("generic?");
      classification.run();
      return generic;
    }

    public boolean isNonGenericCollection(Object source) {
      calls.add("nongeneric?");
      return nonGeneric;
    }

    public int genericCount(Object source) {
      calls.add("generic-count");
      countAction.run();
      return count;
    }

    public int nonGenericCount(Object source) {
      calls.add("nongeneric-count");
      countAction.run();
      return count;
    }

    public void genericCopyTo(Object source, Object[] destination, int index) {
      calls.add("generic-copy");
      copySource(source, destination, index);
    }

    public void nonGenericCopyTo(Object source, Object[] destination, int index) {
      calls.add("nongeneric-copy");
      copySource(source, destination, index);
    }

    void copySource(Object source, Object[] destination, int index) {
      copiedSource = source;
      copiedDestination = destination;
      copiedIndex = index;
      System.arraycopy(values, 0, destination, index, values.length);
      copyTo.run();
    }

    public Object[] allocate(int capacity) {
      calls.add("allocate:" + capacity);
      allocation.run();
      return new Object[capacity];
    }

    public Object[] emptyArray() {
      calls.add("empty");
      return new Object[0];
    }

    public void copy(Object[] source, int from, Object[] destination, int to, int count) {
      calls.add("array-copy");
      copies.add(new Copy(source, from, destination, to, count));
      System.arraycopy(source, from, destination, to, count);
      copyAction.run();
    }

    public Object getEnumerator(Object source) {
      calls.add("get-iterator");
      enumerator.run();
      return iterator;
    }

    public boolean moveNext(Object iterator) {
      calls.add("move");
      assertSame(this.iterator, iterator);
      move.run();
      return cursor < values.length;
    }

    public Object current(Object iterator) {
      calls.add("current");
      assertSame(this.iterator, iterator);
      item.run();
      return values[cursor++];
    }

    public void dispose(Object iterator) {
      calls.add("dispose");
      assertSame(this.iterator, iterator);
      disposal.run();
    }
  }

  static World collection(boolean generic, Object... values) {
    var world = new World();
    world.generic = generic;
    world.nonGeneric = !generic;
    world.values = values;
    world.count = values.length;
    return world;
  }

  static References target(Object... values) {
    return new References(Arrays.copyOf(values, 8), values.length, 9);
  }

  static Object[] content(References list) {
    return Arrays.copyOf(list.entries, list.size);
  }

  @Test
  void nullRangeSourceFailsBeforeReceiverAndBindings() {
    var world = new World();
    world.classification = () -> fail("source guard precedes classification");
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectListRange46.insertRange(null, 0, null, world));
    assertTrue(world.calls.isEmpty());
  }

  @Test
  void invalidRangeIndexFailsBeforeClassification() {
    var list = target("a");
    var world = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectListRange46.insertRange(list, -1, new Object(), world));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectListRange46.insertRange(list, 2, new Object(), world));
    assertTrue(world.calls.isEmpty());
    assertEquals(9, list.version);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void collectionInsertsAtMiddleAndAdvancesOneVersion(boolean generic) {
    var list = target("a", "b");
    var world = collection(generic, "x", null);
    Object source = new Object();
    RogueEffectListRange46.insertRange(list, 1, source, world);
    assertArrayEquals(new Object[] {"a", "x", null, "b"}, content(list));
    assertEquals(10, list.version);
    assertSame(source, world.copiedSource);
    assertSame(list.entries, world.copiedDestination);
    assertEquals(1, world.copiedIndex);
    assertEquals(1, world.copies.size());
    assertEquals(
        generic
            ? List.of("generic?", "generic-count", "array-copy", "generic-copy")
            : List.of(
                "generic?", "nongeneric?", "nongeneric-count", "array-copy", "nongeneric-copy"),
        world.calls);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void collectionZeroCountDoesNotRequireStorage(boolean generic) {
    var list = new References(null, 0, 9);
    var world = collection(generic);
    RogueEffectListRange46.insertRange(list, 0, new Object(), world);
    assertNull(list.entries);
    assertEquals(0, list.size);
    assertEquals(10, list.version);
    assertTrue(world.copies.isEmpty());
    assertNull(world.copiedSource);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void collectionNegativeCountDoesNotRepairCountOrStorage(boolean generic) {
    var list = new References(null, 0, Integer.MAX_VALUE);
    var world = collection(generic);
    world.count = -3;
    RogueEffectListRange46.insertRange(list, 0, new Object(), world);
    assertNull(list.entries);
    assertEquals(0, list.size);
    assertEquals(Integer.MIN_VALUE, list.version);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void countCallbackMutationsControlCapacityAndShift(boolean generic) {
    var list = target("old");
    var world = collection(generic, "x");
    world.countAction =
        () -> {
          list.entries = new Object[] {"a", "b", null, null};
          list.size = 2;
          list.version = 40;
        };
    RogueEffectListRange46.insertRange(list, 1, new Object(), world);
    assertArrayEquals(new Object[] {"a", "x", "b"}, content(list));
    assertEquals(41, list.version);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void collectionGrowthUsesActualCapacitySetterAndCopiesPriorElements(boolean generic) {
    var list = new References(new Object[] {"a", "b"}, 2, 9);
    var world = collection(generic, "x", "y", "z");
    RogueEffectListRange46.insertRange(list, 1, new Object(), world);
    assertArrayEquals(new Object[] {"a", "x", "y", "z", "b"}, content(list));
    assertEquals(5, list.entries.length);
    assertEquals(10, list.version);
    assertEquals(2, world.copies.size());
    assertEquals(
        new Copy(world.copies.getFirst().source(), 0, list.entries, 0, 2), world.copies.getFirst());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void copyToUpdatesCurrentCountAndVersionRatherThanCapturedOriginal(boolean generic) {
    var list = target("a");
    var world = collection(generic, "x");
    world.copyTo =
        () -> {
          list.size = 4;
          list.version = 71;
        };
    RogueEffectListRange46.insertRange(list, 1, new Object(), world);
    assertEquals(5, list.size);
    assertEquals(72, list.version);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void copyToFailureKeepsShiftAndDoesNotAddCountOrVersion(boolean generic) {
    var list = target("a", "b");
    var world = collection(generic, "x");
    var failure = new IllegalStateException("copy source");
    world.copyTo =
        () -> {
          throw failure;
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueEffectListRange46.insertRange(list, 1, new Object(), world)));
    assertArrayEquals(new Object[] {"a", "x", "b", null, null, null, null, null}, list.entries);
    assertEquals(2, list.size);
    assertEquals(9, list.version);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void countFailureKeepsCallbackStateAndDoesNotAllocate(boolean generic) {
    var list = target("a");
    var world = collection(generic, "x");
    var failure = new IllegalStateException("count");
    world.countAction =
        () -> {
          list.size = 0;
          list.version = 42;
          throw failure;
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueEffectListRange46.insertRange(list, 0, new Object(), world)));
    assertEquals(0, list.size);
    assertEquals(42, list.version);
    assertTrue(world.copies.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void collectionSelfInsertionUsesThreeCopiesWithoutVirtualCopyTo(boolean generic) {
    var list = target("a", "b", "c");
    var world = collection(generic);
    world.count = 3;
    RogueEffectListRange46.insertRange(list, 1, list, world);
    assertArrayEquals(new Object[] {"a", "a", "b", "c", "b", "c"}, content(list));
    assertEquals(3, world.copies.size());
    assertNull(world.copiedSource);
    assertEquals(10, list.version);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void selfInsertionRereadsStorageAndCountBetweenCopyCalls(boolean generic) {
    var list = target("a", "b");
    var world = collection(generic);
    world.count = 2;
    Object[] replacement = {"p", "q", "r", "s", "t", "u", "v", "w"};
    world.copyAction =
        () -> {
          if (world.copies.size() == 2) {
            list.entries = replacement;
            list.size = 3;
          }
        };
    RogueEffectListRange46.insertRange(list, 1, list, world);
    Copy last = world.copies.getLast();
    assertSame(replacement, last.source());
    assertSame(replacement, last.destination());
    assertEquals(new Copy(replacement, 3, replacement, 2, 2), last);
    assertEquals(5, list.size);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void positiveCountRequiresStorageBeforeCopy(boolean generic) {
    var list = new References(null, 0, 9);
    var world = collection(generic, "a");
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectListRange46.insertRange(list, 0, new Object(), world));
    assertTrue(world.copies.isEmpty());
    assertEquals(9, list.version);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void oversizedCollectionStopsAtExplicitFinitePolicy(boolean generic) {
    var list = target();
    var world = collection(generic);
    world.count = 4097;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectListRange46.insertRange(list, 0, new Object(), world));
    assertEquals(0, list.size);
    assertEquals(9, list.version);
    assertTrue(world.copies.isEmpty());
  }

  @Test
  void genericClassificationSkipsNonGenericQuery() {
    var list = target();
    var world = collection(true);
    RogueEffectListRange46.insertRange(list, 0, new Object(), world);
    assertFalse(world.calls.contains("nongeneric?"));
  }

  @Test
  void iteratorMiddleInsertsNullableItemsAndAddsPerItemAndFinalVersions() {
    var list = target("a", "b");
    var world = new World();
    world.values = new Object[] {null, "x"};
    RogueEffectListRange46.insertRange(list, 1, new Object(), world);
    assertArrayEquals(new Object[] {"a", null, "x", "b"}, content(list));
    assertEquals(12, list.version);
    assertEquals(
        List.of(
            "generic?",
            "nongeneric?",
            "get-iterator",
            "move",
            "current",
            "array-copy",
            "move",
            "current",
            "array-copy",
            "move",
            "dispose"),
        world.calls);
  }

  @Test
  void iteratorAppendUsesAddRangeAndTwoVersionIncrements() {
    var list = target("a");
    var world = new World();
    world.values = new Object[] {null, "b"};
    RogueEffectListRange46.insertRange(list, 1, new Object(), world);
    assertArrayEquals(new Object[] {"a", null, "b"}, content(list));
    assertEquals(11, list.version);
    assertTrue(world.copies.isEmpty());
    assertEquals("dispose", world.calls.getLast());
  }

  @Test
  void emptyAppendStillAdvancesBothVersions() {
    var list = target();
    var world = new World();
    RogueEffectListRange46.insertRange(list, 0, new Object(), world);
    assertEquals(11, list.version);
    assertEquals(0, list.size);
  }

  @Test
  void classificationMutationControlsEnumerableMiddleOrAppendBranch() {
    var list = target("a", "b");
    var world = new World();
    world.classification = () -> list.size = 0;
    RogueEffectListRange46.insertRange(list, 1, new Object(), world);
    assertEquals(11, list.version);
    assertEquals(0, list.size);
  }

  @Test
  void iteratorGetterFailureDoesNotDisposeOrAdvanceMiddleVersion() {
    var list = target("a");
    var world = new World();
    var failure = new IllegalStateException("getter");
    world.enumerator =
        () -> {
          throw failure;
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueEffectListRange46.insertRange(list, 0, new Object(), world)));
    assertEquals(9, list.version);
    assertFalse(world.calls.contains("dispose"));
  }

  @Test
  void nullMiddleIteratorDoesNotDisposeAndDoesNotAdvanceVersion() {
    var list = target("a");
    var world = new World();
    world.iterator = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectListRange46.insertRange(list, 0, new Object(), world));
    assertEquals(9, list.version);
    assertFalse(world.calls.contains("dispose"));
  }

  @Test
  void iteratorFailureDisposesBeforeRethrowAndKeepsEarlierInsert() {
    var list = target("a");
    var world = new World();
    world.values = new Object[] {"x", "y"};
    var failure = new IllegalStateException("move");
    world.move =
        () -> {
          if (world.cursor == 1) throw failure;
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueEffectListRange46.insertRange(list, 0, new Object(), world)));
    assertArrayEquals(new Object[] {"x", "a"}, content(list));
    assertEquals(10, list.version);
    assertEquals("dispose", world.calls.getLast());
  }

  @Test
  void disposalFailureReplacesIterationFailureAndStopsFinalVersion() {
    var list = target("a");
    var world = new World();
    var first = new IllegalStateException("move");
    var last = new IllegalStateException("dispose");
    world.move =
        () -> {
          throw first;
        };
    world.disposal =
        () -> {
          throw last;
        };
    assertSame(
        last,
        assertThrows(
            IllegalStateException.class,
            () -> RogueEffectListRange46.insertRange(list, 0, new Object(), world)));
    assertEquals(9, list.version);
    assertEquals(0, last.getSuppressed().length);
  }

  @Test
  void normalDisposalMutationControlsFinalVersion() {
    var list = target("a");
    var world = new World();
    world.disposal =
        () -> {
          list.size = 7;
          list.version = 40;
        };
    RogueEffectListRange46.insertRange(list, 0, new Object(), world);
    assertEquals(7, list.size);
    assertEquals(41, list.version);
  }

  @Test
  void javaErrorOutsideNativeExceptionProjectionDoesNotDispose() {
    var list = target("a");
    var world = new World();
    var failure = new AssertionError("host failure");
    world.move =
        () -> {
          throw failure;
        };
    assertSame(
        failure,
        assertThrows(
            AssertionError.class,
            () -> RogueEffectListRange46.insertRange(list, 0, new Object(), world)));
    assertFalse(world.calls.contains("dispose"));
  }

  @Test
  void insertWithFreeStorageNeedsNoWorldAndKeepsNullItem() {
    var list = target("a");
    RogueEffectListRange46.insert(list, 1, null, null);
    assertArrayEquals(new Object[] {"a", null}, content(list));
    assertEquals(10, list.version);
  }

  @Test
  void insertShiftsCurrentStorageAfterCapacityCallback() {
    var list = new References(new Object[] {"a", "b"}, 2, 9);
    var world = new World();
    world.allocation =
        () -> {
          list.size = 1;
          list.version = 30;
        };
    RogueEffectListRange46.insert(list, 0, "x", world);
    assertArrayEquals(new Object[] {"x", "a"}, content(list));
    assertEquals(31, list.version);
  }

  @Test
  void insertFailureAfterShiftDoesNotAdvanceCountOrVersion() {
    var list = target("a");
    var world = new World();
    world.copyAction = () -> list.entries = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEffectListRange46.insert(list, 0, "x", world));
    assertNull(list.entries);
    assertEquals(1, list.size);
    assertEquals(9, list.version);
  }

  @Test
  void insertPublishesCountAndVersionOnlyAfterSuccessfulElementWrite() {
    var list = new References(new Object[0], -1, Integer.MAX_VALUE);
    assertThrows(
        IllegalArgumentException.class, () -> RogueEffectListRange46.insert(list, -1, null, null));
    assertEquals(-1, list.size);
    assertEquals(Integer.MAX_VALUE, list.version);
  }

  @Test
  void addRangeNullSourceAdvancesVersionBeforeFailure() {
    var list = target();
    assertThrows(
        IllegalArgumentException.class, () -> RogueEffectListRange46.addRange(list, null, null));
    assertEquals(10, list.version);
  }

  @Test
  void appendGrowthUsesCurrentCountAfterCapacityReturns() {
    var list = new References(new Object[] {"a"}, 1, 9);
    var world = new World();
    world.values = new Object[] {"x"};
    world.allocation = () -> list.size = 0;
    RogueEffectListRange46.addRange(list, new Object(), world);
    assertArrayEquals(new Object[] {"x"}, content(list));
    assertEquals(10, list.version);
  }

  @Test
  void appendStoreFailureKeepsCountIncrementAndDisposes() {
    var list = new References(new Object[0], -1, 9);
    var world = new World();
    world.values = new Object[] {"x"};
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectListRange46.addRange(list, new Object(), world));
    assertEquals(0, list.size);
    assertEquals(10, list.version);
    assertEquals("dispose", world.calls.getLast());
  }

  @Test
  void appendNullIteratorKeepsInitialVersionAndSkipsDispose() {
    var list = target();
    var world = new World();
    world.iterator = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEffectListRange46.addRange(list, new Object(), world));
    assertEquals(10, list.version);
    assertFalse(world.calls.contains("dispose"));
  }

  @Test
  void appendGetterFailureKeepsInitialVersionAndSkipsDispose() {
    var list = target();
    var world = new World();
    var failure = new IllegalStateException("get");
    world.enumerator =
        () -> {
          throw failure;
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueEffectListRange46.addRange(list, new Object(), world)));
    assertEquals(10, list.version);
    assertFalse(world.calls.contains("dispose"));
  }

  @Test
  void growthUsesUnsignedDoublingAndSignedMinimum() {
    assertEquals(4, RogueEffectListRange46.growth(0, 1));
    assertEquals(9, RogueEffectListRange46.growth(4, 9));
    assertEquals(0x7fefffff, RogueEffectListRange46.growth(0x40000000, 1));
    assertEquals(Integer.MAX_VALUE, RogueEffectListRange46.growth(1, Integer.MAX_VALUE));
  }

  @Test
  void actualRangePayloadUsesActualCollectionInsertionAndCapacity() {
    var payload = new RogueBaseOtherModifierEvents46.FadeFinished(null, null, null);
    var range = collection(true, "x", null, "y", "z", "last");
    var payloadWorld =
        new RogueOtherEventPayload46Test.World() {
          public void initializeEffects(References list) {
            RogueOtherEventPayload46.constructEffects(list, () -> new Object[0]);
          }

          public void insertRange(References list, int index, Object source) {
            RogueEffectListRange46.insertRange(list, index, source, range);
          }
        };
    RogueOtherEventPayload46.construct(payload, payloadWorld);
    Object entity = new Object(), modifier = new Object(), source = new Object();
    RogueOtherEventPayload46.initialize(payload, entity, source, modifier, payloadWorld);
    References list = (References) payload.effects;
    assertArrayEquals(range.values, content(list));
    assertEquals(2, list.version);
    assertSame(entity, payload.sourceEntity);
    assertSame(modifier, payload.modifier);
  }

  @Test
  void nullableCaughtNativeWrapperDisposesAndFallsThroughToAppend() {
    var list = target("a");
    var world = new World();
    world.values = new Object[] {"x"};
    int[] moves = {0};
    world.move =
        () -> {
          if (moves[0]++ == 0) throw new RogueEffectListRange46.ProjectedException(null);
        };
    RogueEffectListRange46.insertRange(list, 0, new Object(), world);
    assertArrayEquals(new Object[] {"a", "x"}, content(list));
    assertEquals(11, list.version);
    assertEquals(2, world.calls.stream().filter("dispose"::equals).count());
    assertEquals(2, world.calls.stream().filter("get-iterator"::equals).count());
  }

  @Test
  void projectedNativeFailureRethrowsOriginalAfterDisposal() {
    var list = target("a");
    var world = new World();
    var original = new IllegalStateException("native stored exception");
    world.move =
        () -> {
          throw new RogueEffectListRange46.ProjectedException(original);
        };
    assertSame(
        original,
        assertThrows(
            IllegalStateException.class,
            () -> RogueEffectListRange46.insertRange(list, 0, new Object(), world)));
    assertEquals("dispose", world.calls.getLast());
    assertEquals(9, list.version);
  }

  @Test
  void nullableAppendNativeWrapperDisposesAndReturnsWithoutMoreWrites() {
    var list = target();
    var world = new World();
    world.move =
        () -> {
          throw new RogueEffectListRange46.ProjectedException(null);
        };
    RogueEffectListRange46.addRange(list, new Object(), world);
    assertEquals("dispose", world.calls.getLast());
    assertEquals(10, list.version);
    assertEquals(0, list.size);
  }
}
