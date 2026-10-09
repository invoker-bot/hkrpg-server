package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.gameplay.RogueBaseModifierDestroy46.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEventSingleArraySearch46Test {
  static final Object COMPARER = new Object();

  static class World implements RogueEventSingleArraySearch46.World {
    List<String> order = new ArrayList<>();
    Object comparer = COMPARER;
    int result = 73;
    boolean findActual;
    Runnable onDefault = () -> {}, onSearch = () -> {};
    Callback[] receivedSource;
    Callback receivedValue;
    int receivedStart, receivedCount;
    RogueEventDelegateRemoval46Test.World equality = new RogueEventDelegateRemoval46Test.World();

    public Object defaultComparer() {
      order.add("default");
      onDefault.run();
      return comparer;
    }

    public int comparerLastIndexOf(
        Object selected, Callback[] source, Callback value, int start, int count) {
      order.add("search");
      assertSame(comparer, selected);
      receivedSource = source;
      receivedValue = value;
      receivedStart = start;
      receivedCount = count;
      onSearch.run();
      if (findActual) {
        for (int i = start; i > start - count; i--) {
          if (source[i] == null) {
            if (value == null) return i;
          } else if (equality.virtualEquals(source[i], value)) return i;
        }
        return -1;
      }
      return result;
    }
  }

  @Test
  void nullArrayIsRejectedBeforeStartCountOrWorld() {
    var world = new World();
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RogueEventSingleArraySearch46.lastIndexOf(
                    null, null, Integer.MIN_VALUE, -1, world));
    assertTrue(error.getMessage().contains("search array"));
    assertTrue(world.order.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0})
  void emptyArrayTwoAcceptedStartsReturnWithoutComparerOrWorld(int start) {
    assertEquals(
        -1, RogueEventSingleArraySearch46.lastIndexOf(new Callback[0], null, start, 0, null));
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -2, 1, Integer.MAX_VALUE})
  void emptyArrayOtherStartsAreRejectedBeforeCount(int start) {
    var world = new World();
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RogueEventSingleArraySearch46.lastIndexOf(new Callback[0], null, start, -1, world));
    assertTrue(error.getMessage().contains("start"));
    assertTrue(world.order.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 1, Integer.MAX_VALUE})
  void emptyArrayNonzeroCountRejectsBeforeComparer(int count) {
    var world = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventSingleArraySearch46.lastIndexOf(new Callback[0], null, -1, count, world));
    assertTrue(world.order.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 2, Integer.MAX_VALUE})
  void nonemptyStartMustBelongToArrayBeforeCountValidation(int start) {
    var world = new World();
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RogueEventSingleArraySearch46.lastIndexOf(new Callback[2], null, start, -1, world));
    assertTrue(error.getMessage().contains("start"));
    assertTrue(world.order.isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"0,-1", "0,2", "1,3", "1,2147483647"})
  void invalidNonemptyCountRejectsBeforeComparer(int start, int count) {
    var world = new World();
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RogueEventSingleArraySearch46.lastIndexOf(
                    new Callback[2], null, start, count, world));
    assertTrue(error.getMessage().contains("count"));
    assertTrue(world.order.isEmpty());
  }

  @Test
  void nonemptyZeroCountStillObtainsComparerAndDispatchesWithNullValue() {
    var world = new World();
    Callback[] source = new Callback[2];
    assertEquals(73, RogueEventSingleArraySearch46.lastIndexOf(source, null, 1, 0, world));
    assertEquals(List.of("default", "search"), world.order);
    assertSame(source, world.receivedSource);
    assertNull(world.receivedValue);
    assertEquals(1, world.receivedStart);
    assertEquals(0, world.receivedCount);
  }

  @Test
  void fullAndPartialSearchBoundsArePassedUnchanged() {
    var world = new World();
    Callback value = RogueEventDelegateRemoval46Test.callback(1);
    Callback[] source = new Callback[4];
    assertEquals(73, RogueEventSingleArraySearch46.lastIndexOf(source, value, 3, 4, world));
    assertSame(value, world.receivedValue);
    assertEquals(3, world.receivedStart);
    assertEquals(4, world.receivedCount);
    assertEquals(73, RogueEventSingleArraySearch46.lastIndexOf(source, value, 2, 2, world));
    assertEquals(2, world.receivedStart);
    assertEquals(2, world.receivedCount);
  }

  @Test
  void validNonemptySearchNeedsWorldAfterValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventSingleArraySearch46.lastIndexOf(new Callback[1], null, 0, 0, null));
  }

  @Test
  void missingDefaultComparerStopsVirtualSearch() {
    var world = new World();
    world.comparer = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventSingleArraySearch46.lastIndexOf(new Callback[1], null, 0, 1, world));
    assertEquals(List.of("default"), world.order);
  }

  @Test
  void getterFailureStopsVirtualSearchAndRetainsMutatedArray() {
    var world = new World();
    Callback[] source = new Callback[1];
    Callback changed = RogueEventDelegateRemoval46Test.callback(2);
    world.onDefault =
        () -> {
          source[0] = changed;
          throw new IllegalStateException("default");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueEventSingleArraySearch46.lastIndexOf(source, null, 0, 1, world));
    assertSame(changed, source[0]);
    assertEquals(List.of("default"), world.order);
  }

  @Test
  void comparerDispatchFailurePropagatesAndKeepsCompletedGetter() {
    var world = new World();
    world.onSearch =
        () -> {
          throw new IllegalStateException("search");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueEventSingleArraySearch46.lastIndexOf(new Callback[1], null, 0, 1, world));
    assertEquals(List.of("default", "search"), world.order);
  }

  @Test
  void getterMutationIsVisibleToActualDelegateComparisonAdapter() {
    var world = new World();
    world.findActual = true;
    Callback[] source = {RogueEventDelegateRemoval46Test.callback(2)};
    world.onDefault = () -> source[0] = RogueEventDelegateRemoval46Test.callback(1);
    assertEquals(
        0,
        RogueEventSingleArraySearch46.lastIndexOf(
            source, RogueEventDelegateRemoval46Test.callback(1), 0, 1, world));
  }

  @Test
  void emptyEarlyReturnSkipsThrowingGetter() {
    var world = new World();
    world.onDefault =
        () -> {
          throw new IllegalStateException("unexpected");
        };
    assertEquals(-1, RogueEventSingleArraySearch46.lastIndexOf(new Callback[0], null, 0, 0, world));
    assertTrue(world.order.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {-2147483648, -2, -1, 0, 2147483647})
  void virtualSearchResultIsReturnedWithoutSentinelNormalization(int result) {
    var world = new World();
    world.result = result;
    assertEquals(
        result, RogueEventSingleArraySearch46.lastIndexOf(new Callback[1], null, 0, 1, world));
  }

  @Test
  void largeArrayDoesNotApplyUnrelatedFiniteInvocationLimitToValidation() {
    var world = new World();
    Callback[] source = new Callback[4096];
    assertEquals(73, RogueEventSingleArraySearch46.lastIndexOf(source, null, 4095, 4096, world));
    assertEquals(4095, world.receivedStart);
    assertEquals(4096, world.receivedCount);
  }

  @Test
  void actualMulticastRemovalCallsNewBoundaryAndActualEqualityAdapter() {
    var searchWorld = new World();
    searchWorld.findActual = true;
    var removeWorld =
        new RogueEventDelegateRemoval46Test.World() {
          @Override
          public int lastSingleIndex(Callback[] source, Callback value, int start, int count) {
            return RogueEventSingleArraySearch46.lastIndexOf(
                source, value, start, count, searchWorld);
          }
        };
    Callback a = RogueEventDelegateRemoval46Test.callback(1),
        b = RogueEventDelegateRemoval46Test.callback(2);
    Callback source =
        RogueEventDelegateRemoval46Test.chain(a, b, RogueEventDelegateRemoval46Test.callback(1));
    Callback result =
        RogueEventDelegateRemoval46.multicastRemoveImpl(
            source, RogueEventDelegateRemoval46Test.callback(1), removeWorld);
    assertArrayEquals(new Callback[] {a, b}, result.delegates);
    assertEquals(List.of("default", "search"), searchWorld.order);
  }

  @Test
  void actualSearchRejectsInvalidMutableDelegateIndexBeforeCallerAllocation() {
    var searchWorld = new World();
    searchWorld.result = 19;
    var removeWorld =
        new RogueEventDelegateRemoval46Test.World() {
          @Override
          public int lastSingleIndex(Callback[] source, Callback value, int start, int count) {
            return RogueEventSingleArraySearch46.lastIndexOf(
                source, value, start, count, searchWorld);
          }
        };
    assertThrows(
        IndexOutOfBoundsException.class,
        () ->
            RogueEventDelegateRemoval46.multicastRemoveImpl(
                RogueEventDelegateRemoval46Test.chain(
                    RogueEventDelegateRemoval46Test.callback(1),
                    RogueEventDelegateRemoval46Test.callback(2),
                    RogueEventDelegateRemoval46Test.callback(3)),
                RogueEventDelegateRemoval46Test.callback(1),
                removeWorld));
    assertEquals(1, removeWorld.allocated.size());
    assertTrue(removeWorld.order.contains("prefix"));
    assertFalse(removeWorld.order.contains("range"));
  }
}
