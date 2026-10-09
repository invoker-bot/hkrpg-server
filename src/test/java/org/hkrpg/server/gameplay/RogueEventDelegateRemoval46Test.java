package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.gameplay.RogueBaseModifierDestroy46.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEventDelegateRemoval46Test {
  static final Object TYPE = new Object();
  static final Object[] METHODS = {new Object(), new Object(), new Object(), new Object()};

  static Callback callback(int method) {
    var callback = new Callback();
    callback.methodInfo = METHODS[method];
    callback.methodPointer = method;
    return callback;
  }

  static Callback chain(Callback... values) {
    Callback callback = callback(0);
    callback.delegates = values;
    return callback;
  }

  static class World implements RogueEventDelegateRemoval46.World {
    List<String> order = new ArrayList<>();
    List<Callback> comparedLeft = new ArrayList<>(), allocated = new ArrayList<>();
    List<Object> comparedRight = new ArrayList<>();
    Runnable onType = () -> {},
        onArrayEquals = () -> {},
        onSearch = () -> {},
        onAllocate = () -> {},
        onArrayAllocation = () -> {},
        onPrefix = () -> {},
        onRange = () -> {},
        onEquals = () -> {};
    int typeCalls, comparisons;
    boolean differentTypes, nullClone, nullArray;
    String fail;

    void phase(String name) {
      order.add(name);
      if (name.equals(fail)) throw new IllegalStateException(name);
    }

    public Object methodObject(Callback value) {
      return value.methodInfo;
    }

    public boolean virtualEquals(Callback left, Object right) {
      comparedLeft.add(left);
      comparedRight.add(right);
      comparisons++;
      phase("equals");
      onEquals.run();
      return RogueEventDelegateEquality46.multicastEquals(left, right, this);
    }

    public Object runtimeType(Callback value) {
      phase("type");
      typeCalls++;
      onType.run();
      return differentTypes && typeCalls == 2 ? METHODS[3] : TYPE;
    }

    public Callback virtualRemove(Callback source, Callback value) {
      phase("remove");
      return RogueEventDelegateRemoval46.multicastRemoveImpl(source, value, this);
    }

    public boolean arrayEquals(Callback[] source, Callback[] value) {
      phase("arrayEquals");
      onArrayEquals.run();
      return source == value;
    }

    public int lastSingleIndex(Callback[] source, Callback value, int start, int count) {
      phase("search");
      onSearch.run();
      assertEquals(source.length - 1, start);
      assertEquals(source.length, count);
      for (int i = start; i >= 0; i--)
        if (source[i] != null && virtualEquals(source[i], value)) return i;
      return -1;
    }

    public Callback allocateLike(Callback source) {
      phase("allocate");
      onAllocate.run();
      if (nullClone) return null;
      Callback result = new Callback();
      allocated.add(result);
      return result;
    }

    public Callback[] allocateInvocationArray(int count) {
      phase("array");
      onArrayAllocation.run();
      return nullArray ? null : new Callback[count];
    }

    public void copyPrefix(Callback[] source, Callback[] destination, int count) {
      phase("prefix");
      System.arraycopy(source, 0, destination, 0, count);
      onPrefix.run();
    }

    public void copyRange(
        Callback[] source, int start, Callback[] destination, int target, int count) {
      phase("range");
      System.arraycopy(source, start, destination, target, count);
      onRange.run();
    }
  }

  @Test
  void nullSourceReturnsBeforeValueWorldOrType() {
    assertNull(RogueEventDelegateRemoval46.remove(null, callback(1), null));
  }

  @Test
  void nullValueReturnsSourceBeforeWorld() {
    Callback source = callback(1);
    assertSame(source, RogueEventDelegateRemoval46.remove(source, null, null));
  }

  @Test
  void publicRemovalLooksUpBothTypesBeforeVirtualBody() {
    var world = new World();
    assertNull(RogueEventDelegateRemoval46.remove(callback(1), callback(1), world));
    assertEquals(List.of("type", "type", "remove"), world.order);
  }

  @Test
  void runtimeTypeMismatchRejectsBeforeVirtualBody() {
    var world = new World();
    world.differentTypes = true;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventDelegateRemoval46.remove(callback(1), callback(1), world));
    assertEquals(List.of("type", "type"), world.order);
  }

  @Test
  void typeLookupFailureStopsSecondLookupAndRemoval() {
    var world = new World();
    world.fail = "type";
    assertThrows(
        IllegalStateException.class,
        () -> RogueEventDelegateRemoval46.remove(callback(1), callback(1), world));
    assertEquals(0, world.typeCalls);
    assertEquals(List.of("type"), world.order);
  }

  @Test
  void baseRemoveUsesVirtualEqualityWithNullAndNonCallbackValues() {
    var world = new World();
    Callback source = callback(1);
    assertSame(source, RogueEventDelegateRemoval46.delegateRemoveImpl(source, null, world));
    assertSame(source, RogueEventDelegateRemoval46.delegateRemoveImpl(source, new Object(), world));
    assertNull(RogueEventDelegateRemoval46.delegateRemoveImpl(source, callback(1), world));
    assertEquals(3, world.comparisons);
  }

  @Test
  void directMulticastNullValueReturnsWithoutWorld() {
    Callback source = callback(1);
    assertSame(source, RogueEventDelegateRemoval46.multicastRemoveImpl(source, null, null));
  }

  @Test
  void unmatchedProjectionIsRejectedBeforeComparing() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventDelegateRemoval46.multicastRemoveImpl(callback(1), new Object(), null));
  }

  @Test
  void singletonMatchesUseActualMulticastEquality() {
    var world = new World();
    Callback source = callback(1);
    assertNull(RogueEventDelegateRemoval46.multicastRemoveImpl(source, callback(1), world));
    assertSame(source, RogueEventDelegateRemoval46.multicastRemoveImpl(source, callback(2), world));
  }

  @Test
  void singletonRemovedWhenAnyCapturedValueInvocationMatches() {
    var world = new World();
    Callback source = callback(1);
    Callback right = chain(callback(2), callback(1), null);
    assertNull(RogueEventDelegateRemoval46.multicastRemoveImpl(source, right, world));
    assertTrue(world.allocated.isEmpty());
  }

  @Test
  void singletonDoesNotMatchEmptyOrDifferentInvocationArray() {
    Callback source = callback(1);
    var world = new World();
    assertSame(source, RogueEventDelegateRemoval46.multicastRemoveImpl(source, chain(), null));
    assertSame(
        source,
        RogueEventDelegateRemoval46.multicastRemoveImpl(source, chain(callback(2), null), world));
  }

  @Test
  void equalArrayReferenceIsRemovedBeforeElementComparisonOrAllocation() {
    Callback source = chain((Callback) null), right = chain();
    right.delegates = source.delegates;
    var world = new World();
    assertNull(RogueEventDelegateRemoval46.multicastRemoveImpl(source, right, world));
    assertEquals(List.of("arrayEquals"), world.order);
  }

  @Test
  void arraysTooShortReturnBeforeWorldOrElements() {
    assertEquals(
        -1, RogueEventDelegateRemoval46.lastIndexOf(new Callback[1], new Callback[2], null));
  }

  @Test
  void twoEmptyArraysHaveIndexZeroWithoutWorld() {
    assertEquals(
        0, RogueEventDelegateRemoval46.lastIndexOf(new Callback[0], new Callback[0], null));
  }

  @Test
  void shorterEmptyNeedlePreservesBoundsFailure() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventDelegateRemoval46.lastIndexOf(new Callback[1], new Callback[0], null));
  }

  @Test
  void equalLengthComparisonInvokesSourceAndStopsAtFirstMismatch() {
    Callback a = callback(1), b = callback(2);
    var world = new World();
    assertEquals(
        -1,
        RogueEventDelegateRemoval46.lastIndexOf(
            new Callback[] {a, null}, new Callback[] {b, null}, world));
    assertEquals(List.of(a), world.comparedLeft);
    assertEquals(List.of(b), world.comparedRight);
  }

  @Test
  void unequalLengthComparisonInvokesNeedleAtLastPositionFirst() {
    Callback a = callback(1), b = callback(2), needle = callback(1);
    var world = new World();
    assertEquals(
        0,
        RogueEventDelegateRemoval46.lastIndexOf(
            new Callback[] {a, b}, new Callback[] {needle}, world));
    assertEquals(List.of(needle, needle), world.comparedLeft);
    assertEquals(List.of(b, a), world.comparedRight);
  }

  @Test
  void nullSourceAtEqualLengthFailsButRightNullIsCompared() {
    var world = new World();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueEventDelegateRemoval46.lastIndexOf(
                new Callback[] {null}, new Callback[] {callback(1)}, world));
    assertEquals(
        -1,
        RogueEventDelegateRemoval46.lastIndexOf(
            new Callback[] {callback(1)}, new Callback[] {null}, world));
  }

  @Test
  void shorterNullNeedleFailsBeforeVirtualComparison() {
    var world = new World();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueEventDelegateRemoval46.lastIndexOf(
                new Callback[] {null, null}, new Callback[] {null}, world));
    assertTrue(world.comparedLeft.isEmpty());
  }

  @Test
  void shorterNeedleMayCompareNullSourceValue() {
    var world = new World();
    assertEquals(
        -1,
        RogueEventDelegateRemoval46.lastIndexOf(
            new Callback[] {null, null}, new Callback[] {callback(1)}, world));
    assertEquals(2, world.comparisons);
    assertNull(world.comparedRight.get(0));
  }

  @Test
  void lastOverlappingMatchingSequenceIsSelected() {
    var world = new World();
    Callback a = callback(1);
    assertEquals(
        2,
        RogueEventDelegateRemoval46.lastIndexOf(
            new Callback[] {a, a, a, a}, new Callback[] {callback(1), callback(1)}, world));
    assertEquals(2, world.comparisons);
  }

  @Test
  void virtualComparisonMutationChangesLaterCapturedArrayElements() {
    var world = new World();
    Callback[] source = {callback(1), callback(2)}, needle = {callback(1), callback(1)};
    world.onEquals = () -> source[1] = callback(1);
    assertEquals(0, RogueEventDelegateRemoval46.lastIndexOf(source, needle, world));
  }

  @Test
  void missingSingleMatchReturnsOriginalWithoutAllocation() {
    Callback source = chain(callback(1), callback(2));
    var world = new World();
    assertSame(source, RogueEventDelegateRemoval46.multicastRemoveImpl(source, callback(3), world));
    assertTrue(world.allocated.isEmpty());
  }

  @Test
  void twoItemSingleRemovalReturnsOtherExistingCallback() {
    Callback first = callback(1), second = callback(2);
    var world = new World();
    assertSame(
        second,
        RogueEventDelegateRemoval46.multicastRemoveImpl(chain(first, second), callback(1), world));
    assertSame(
        first,
        RogueEventDelegateRemoval46.multicastRemoveImpl(chain(first, second), callback(2), world));
    assertTrue(world.allocated.isEmpty());
  }

  @Test
  void singletonInvocationArraySingleRemovalRaisesSourceStateFailure() {
    var world = new World();
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueEventDelegateRemoval46.multicastRemoveImpl(
                chain(callback(1)), callback(1), world));
    assertTrue(world.allocated.isEmpty());
  }

  @Test
  void searchCanReplaceCurrentArrayBeforeTwoItemChoice() {
    Callback old = callback(1), chosen = callback(3);
    Callback source = chain(old, callback(2), old);
    var world = new World();
    world.onSearch = () -> source.delegates = new Callback[] {chosen, old};
    assertSame(chosen, RogueEventDelegateRemoval46.multicastRemoveImpl(source, callback(1), world));
  }

  @Test
  void singleRemovalSelectsLastMatchAndPreservesRemainingOrder() {
    Callback a = callback(1), b = callback(2), c = callback(1);
    Callback source = chain(a, b, c, callback(3));
    var world = new World();
    Callback result = RogueEventDelegateRemoval46.multicastRemoveImpl(source, callback(1), world);
    assertArrayEquals(new Callback[] {a, b, source.delegates[3]}, result.delegates);
    assertArrayEquals(new Callback[] {a, b, c, source.delegates[3]}, source.delegates);
    assertNotSame(source, result);
  }

  @Test
  void cloneCopiesOnlySourceTargetMethodAndTrampolineAndUsesSelfCodeTarget() {
    Callback source = chain(callback(1), callback(2), callback(3));
    var world = new World();
    source.target = RogueModifierDestroy46Test.state();
    source.methodPointer = 91;
    source.invokePointer = 92;
    source.trampolineRva = 93;
    source.data = new RogueBaseModifierDestroy46.CallbackData();
    Callback result = RogueEventDelegateRemoval46.multicastRemoveImpl(source, callback(2), world);
    assertSame(source.target, result.target);
    assertSame(source.methodInfo, result.methodInfo);
    assertSame(result, result.methodCodeTarget);
    assertEquals(93, result.invokePointer);
    assertEquals(93, result.trampolineRva);
    assertEquals(0, result.methodPointer);
    assertNull(result.data);
  }

  @Test
  void matchingDifferentArrayProducesAllocatedEmptyInvocationArray() {
    Callback source = chain(callback(1), callback(2));
    var world = new World();
    Callback result =
        RogueEventDelegateRemoval46.multicastRemoveImpl(
            source, chain(callback(1), callback(2)), world);
    assertNotNull(result);
    assertEquals(0, result.delegates.length);
    assertSame(result, result.methodCodeTarget);
  }

  @Test
  void sequenceRemovalLeavingOneStillAllocatesAnArrayWrapper() {
    Callback a = callback(1), b = callback(2), c = callback(3);
    var world = new World();
    Callback result =
        RogueEventDelegateRemoval46.multicastRemoveImpl(
            chain(a, b, c), chain(callback(1), callback(2)), world);
    assertNotSame(c, result);
    assertArrayEquals(new Callback[] {c}, result.delegates);
  }

  @Test
  void arrayEqualityCallbackCanReplaceListsBeforeActualSubsequenceSearch() {
    Callback source = chain(callback(1), callback(2)), right = chain(callback(3));
    var world = new World();
    world.onArrayEquals =
        () -> {
          source.delegates = new Callback[] {callback(3), callback(1)};
          right.delegates = new Callback[] {callback(3)};
        };
    Callback result = RogueEventDelegateRemoval46.multicastRemoveImpl(source, right, world);
    assertArrayEquals(new Callback[] {source.delegates[1]}, result.delegates);
  }

  @Test
  void allocationCallbackUpdatesCopiedFieldsAndCurrentArrayLength() {
    Callback source = chain(callback(1), callback(2), callback(3));
    var world = new World();
    world.onAllocate =
        () -> {
          source.methodInfo = METHODS[3];
          source.trampolineRva = 44;
          source.delegates = new Callback[] {callback(1), callback(2), callback(3), callback(1)};
        };
    Callback result = RogueEventDelegateRemoval46.multicastRemoveImpl(source, callback(2), world);
    assertSame(METHODS[3], result.methodInfo);
    assertEquals(44, result.invokePointer);
    assertEquals(3, result.delegates.length);
  }

  @Test
  void prefixCallbackChangesCurrentSourceAndDestinationForSuffix() {
    Callback source = chain(callback(1), callback(2), callback(3));
    var world = new World();
    Callback a = callback(3), b = callback(1);
    world.onPrefix =
        () -> {
          source.delegates = new Callback[] {a, callback(2), b};
          world.allocated.get(0).delegates = new Callback[2];
        };
    Callback result = RogueEventDelegateRemoval46.multicastRemoveImpl(source, callback(2), world);
    assertNull(result.delegates[0]);
    assertSame(b, result.delegates[1]);
  }

  @Test
  void prefixFailureLeavesPublishedCloneAndCopiedPrefix() {
    Callback source = chain(callback(1), callback(2), callback(3));
    var world = new World();
    world.onPrefix =
        () -> {
          throw new IllegalStateException("after prefix");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueEventDelegateRemoval46.multicastRemoveImpl(source, callback(2), world));
    Callback result = world.allocated.get(0);
    assertSame(source.delegates[0], result.delegates[0]);
    assertNull(result.delegates[1]);
    assertFalse(world.order.contains("range"));
  }

  @Test
  void sequenceSuffixReadsCurrentValueBeforeCurrentSource() {
    Callback source = chain(callback(1), callback(2), callback(3)), right = chain(callback(2));
    var world = new World();
    world.onPrefix =
        () -> {
          right.delegates = null;
          source.delegates = null;
        };
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> RogueEventDelegateRemoval46.multicastRemoveImpl(source, right, world));
    assertTrue(error.getMessage().contains("suffix value"));
    assertFalse(world.order.contains("range"));
  }

  @Test
  void nullCloneFailsBeforeArrayAllocation() {
    var world = new World();
    world.nullClone = true;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueEventDelegateRemoval46.multicastRemoveImpl(
                chain(callback(1), callback(2), callback(3)), callback(2), world));
    assertFalse(world.order.contains("array"));
  }

  @Test
  void nullArrayIsPublishedBeforeCopyPrimitiveFailure() {
    var world = new World();
    world.nullArray = true;
    assertThrows(
        NullPointerException.class,
        () ->
            RogueEventDelegateRemoval46.multicastRemoveImpl(
                chain(callback(1), callback(2), callback(3)), callback(2), world));
    assertNull(world.allocated.get(0).delegates);
    assertTrue(world.order.contains("prefix"));
    assertFalse(world.order.contains("range"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"search", "allocate", "array", "prefix", "range"})
  void failingStagePreservesSourceAndStopsRemainingStages(String phase) {
    var world = new World();
    world.fail = phase;
    Callback source = chain(callback(1), callback(2), callback(3));
    Callback[] captured = source.delegates;
    assertThrows(
        IllegalStateException.class,
        () -> RogueEventDelegateRemoval46.multicastRemoveImpl(source, callback(2), world));
    assertSame(captured, source.delegates);
    assertEquals(phase, world.order.getLast());
  }

  @Test
  void finiteSearchAccepts255AndRejects256WhenTraversed() {
    Callback[] source = new Callback[255];
    java.util.Arrays.fill(source, callback(1));
    assertEquals(
        254,
        RogueEventDelegateRemoval46.lastIndexOf(source, new Callback[] {callback(1)}, new World()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueEventDelegateRemoval46.lastIndexOf(
                new Callback[256], new Callback[] {callback(1)}, new World()));
  }

  @Test
  void nativeBaseWithdrawalRemovesFreshCallbacksFromExistingMulticastChains() {
    var state = RogueModifierDestroy46Test.state();
    var removalWorld = new World();
    Callback first = new Callback(), second = new Callback();
    first.target = state;
    second.target = state;
    first.methodInfo = RogueBaseModifierDestroy46Test.FIRST_INFO;
    second.methodInfo = RogueBaseModifierDestroy46Test.SECOND_INFO;
    Callback other = callback(3);
    Callback[] subscriptions = {chain(first, other), chain(other, second)};
    var baseWorld =
        new RogueBaseModifierDestroy46Test.World() {
          @Override
          public void unregisterEventListener(
              Object manager, int event, int uid, Callback callback) {
            super.unregisterEventListener(manager, event, uid, callback);
            int index = event - 207;
            subscriptions[index] =
                RogueEventDelegateRemoval46.remove(subscriptions[index], callback, removalWorld);
          }
        };
    RogueBaseModifierDestroy46.destroy(state, 9, baseWorld);
    assertSame(other, subscriptions[0]);
    assertSame(other, subscriptions[1]);
    assertEquals(2, baseWorld.withdrawn.size());
    assertNotSame(first, baseWorld.allocated.get(0));
    assertNotSame(second, baseWorld.allocated.get(1));
  }
}
