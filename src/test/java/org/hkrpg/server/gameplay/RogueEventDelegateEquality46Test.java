package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEventDelegateEquality46Test {
  static final Object METHOD = new Object(), TYPE = new Object();

  static RogueBaseModifierDestroy46.Callback callback(RogueModifierDestroy46.State target) {
    var c = new RogueBaseModifierDestroy46.Callback();
    c.target = target;
    c.methodInfo = METHOD;
    return c;
  }

  static RogueBaseModifierDestroy46.CallbackData data(String name, Object type, boolean curried) {
    var d = new RogueBaseModifierDestroy46.CallbackData();
    d.methodName = name;
    d.targetType = type;
    d.curriedFirstArgument = curried;
    return d;
  }

  static class World implements RogueEventDelegateEquality46.World {
    final List<RogueBaseModifierDestroy46.Callback> methods = new ArrayList<>();
    final List<Object[]> virtualCalls = new ArrayList<>();
    final Map<RogueBaseModifierDestroy46.Callback, Object> methodResults = new IdentityHashMap<>();
    Consumer<RogueBaseModifierDestroy46.Callback> onMethod = c -> {};
    Consumer<Object[]> onVirtual = args -> {};
    Boolean forcedVirtual;

    public Object methodObject(RogueBaseModifierDestroy46.Callback c) {
      methods.add(c);
      onMethod.accept(c);
      return methodResults.containsKey(c) ? methodResults.get(c) : c.methodInfo;
    }

    public boolean virtualEquals(RogueBaseModifierDestroy46.Callback c, Object other) {
      Object[] args = {c, other};
      virtualCalls.add(args);
      onVirtual.accept(args);
      return forcedVirtual != null
          ? forcedVirtual
          : RogueEventDelegateEquality46.multicastEquals(c, other, this);
    }
  }

  @Test
  void nullAndUnmatchedOtherReturnFalseBeforeMethodLookup() {
    var c = callback(null);
    assertFalse(RogueEventDelegateEquality46.delegateEquals(c, null, null));
    assertFalse(RogueEventDelegateEquality46.delegateEquals(c, new Object(), null));
  }

  @Test
  void differentTargetIdentityReturnsBeforeWorldEvenWhenMethodPointersMatch() {
    var c = callback(RogueModifierDestroy46Test.state());
    var r = callback(RogueModifierDestroy46Test.state());
    c.methodPointer = r.methodPointer = 10;
    assertFalse(RogueEventDelegateEquality46.delegateEquals(c, r, null));
  }

  @Test
  void targetAndResolvedMethodIdentityMatchInsteadOfDelegateReference() {
    var s = RogueModifierDestroy46Test.state();
    var c = callback(s);
    var r = callback(s);
    var w = new World();
    assertNotSame(c, r);
    assertTrue(RogueEventDelegateEquality46.delegateEquals(c, r, w));
    assertEquals(List.of(r, c), w.methods);
  }

  @Test
  void nullTargetsAndNullResolvedMethodsCompareEqual() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    w.methodResults.put(c, null);
    w.methodResults.put(r, null);
    assertTrue(RogueEventDelegateEquality46.delegateEquals(c, r, w));
  }

  @Test
  void unequalResolvedMethodsReturnBeforeReadingEqualData() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    w.methodResults.put(r, new Object());
    c.data = r.data = data("same", TYPE, false);
    assertFalse(RogueEventDelegateEquality46.delegateEquals(c, r, w));
    assertEquals(List.of(r, c), w.methods);
  }

  @Test
  void firstResolvedMethodIsCapturedBeforeSecondLookupChangesOtherMethod() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    w.onMethod =
        item -> {
          if (item == c) r.methodInfo = new Object();
        };
    assertTrue(RogueEventDelegateEquality46.delegateEquals(c, r, w));
    assertNotSame(c.methodInfo, r.methodInfo);
  }

  @Test
  void targetChangedDuringMethodLookupIsNotRechecked() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    w.onMethod =
        item -> {
          if (item == r) c.target = RogueModifierDestroy46Test.state();
        };
    assertTrue(RogueEventDelegateEquality46.delegateEquals(c, r, w));
    assertNotSame(c.target, r.target);
  }

  @Test
  void dataIsRereadAfterBothMethodLookups() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    w.onMethod =
        item -> {
          if (item == c) c.data = data("p", TYPE, false);
        };
    assertFalse(RogueEventDelegateEquality46.delegateEquals(c, r, w));
  }

  @Test
  void firstMethodFailurePreventsSecondLookup() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    w.onMethod =
        item -> {
          throw new IllegalStateException("first");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueEventDelegateEquality46.delegateEquals(c, r, w));
    assertEquals(List.of(r), w.methods);
  }

  @Test
  void secondMethodFailurePreservesFirstLookupAndStopsComparison() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    w.onMethod =
        item -> {
          if (item == c) throw new IllegalStateException("second");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueEventDelegateEquality46.delegateEquals(c, r, w));
    assertEquals(List.of(r, c), w.methods);
  }

  @Test
  void oneMissingDataOnlyTestsOtherTargetTypeAndIgnoresNameAndCurrying() {
    for (boolean rightData : new boolean[] {true, false}) {
      var c = callback(null);
      var r = callback(null);
      var w = new World();
      if (rightData) r.data = data("named", null, true);
      else c.data = data("named", null, true);
      assertTrue(RogueEventDelegateEquality46.delegateEquals(c, r, w));
      if (rightData) r.data.targetType = TYPE;
      else c.data.targetType = TYPE;
      assertFalse(RogueEventDelegateEquality46.delegateEquals(c, r, w));
    }
  }

  @Test
  void dataTargetTypeUsesIdentityAndNameUsesOrdinalContent() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    c.data = data(new String("方法\ud83c\udf19"), TYPE, false);
    r.data = data(new String("方法\ud83c\udf19"), TYPE, true);
    assertTrue(RogueEventDelegateEquality46.delegateEquals(c, r, w));
    r.data.targetType = new Object();
    assertFalse(RogueEventDelegateEquality46.delegateEquals(c, r, w));
    r.data.targetType = TYPE;
    r.data.methodName = "不同";
    assertFalse(RogueEventDelegateEquality46.delegateEquals(c, r, w));
  }

  @Test
  void nullMethodNamesAndNullTargetTypesCompareEqualIgnoringCurriedFlag() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    c.data = data(null, null, false);
    r.data = data(null, null, true);
    assertTrue(RogueEventDelegateEquality46.delegateEquals(c, r, w));
  }

  @Test
  void multicastRejectsBaseMismatchBeforeInvocationListAccess() {
    var c = callback(null);
    var r = callback(RogueModifierDestroy46Test.state());
    c.delegates = new RogueBaseModifierDestroy46.Callback[256];
    r.delegates = new RogueBaseModifierDestroy46.Callback[256];
    assertFalse(RogueEventDelegateEquality46.multicastEquals(c, r, null));
  }

  @Test
  void twoNullInvocationListsCompareEqualAfterBaseMethodLookup() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    assertTrue(RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertEquals(List.of(r, c), w.methods);
    assertTrue(w.virtualCalls.isEmpty());
  }

  @Test
  void missingAndEmptyInvocationListsAreDistinctInBothDirections() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    c.delegates = new RogueBaseModifierDestroy46.Callback[0];
    assertFalse(RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertFalse(RogueEventDelegateEquality46.multicastEquals(r, c, w));
  }

  @Test
  void bothPresentEmptyListsCompareEqualWithoutVirtualChildCall() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    c.delegates = r.delegates = new RogueBaseModifierDestroy46.Callback[0];
    assertTrue(RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertTrue(w.virtualCalls.isEmpty());
  }

  @Test
  void differentInitialListLengthsReturnFalseBeforeChildCall() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    c.delegates = new RogueBaseModifierDestroy46.Callback[1];
    r.delegates = new RogueBaseModifierDestroy46.Callback[2];
    assertFalse(RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertTrue(w.virtualCalls.isEmpty());
  }

  @Test
  void invocationOrderComparesCurrentRightItemUsingLeftVirtualMethod() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    var a = callback(null);
    var b = callback(null);
    var x = callback(null);
    var y = callback(null);
    c.delegates = new RogueBaseModifierDestroy46.Callback[] {a, b};
    r.delegates = new RogueBaseModifierDestroy46.Callback[] {x, y};
    assertTrue(RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertEquals(2, w.virtualCalls.size());
    assertArrayEquals(new Object[] {a, x}, w.virtualCalls.getFirst());
    assertArrayEquals(new Object[] {b, y}, w.virtualCalls.getLast());
  }

  @Test
  void childFalseStopsWithoutReadingCurrentLeftListAgain() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    c.delegates = new RogueBaseModifierDestroy46.Callback[] {callback(null)};
    r.delegates = new RogueBaseModifierDestroy46.Callback[] {callback(null)};
    w.forcedVirtual = false;
    w.onVirtual = args -> c.delegates = null;
    assertFalse(RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertEquals(1, w.virtualCalls.size());
  }

  @Test
  void childTrueStillRequiresCurrentLeftListAfterLastComparison() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    c.delegates = new RogueBaseModifierDestroy46.Callback[] {callback(null)};
    r.delegates = new RogueBaseModifierDestroy46.Callback[] {callback(null)};
    w.forcedVirtual = true;
    w.onVirtual = args -> c.delegates = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertEquals(1, w.virtualCalls.size());
  }

  @Test
  void childTrueReadsReplacementLeftLengthAndRightItemOnNextIteration() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    var a = callback(null);
    var b = callback(null);
    var replacementLeft = callback(null);
    var replacementRight = callback(null);
    c.delegates = new RogueBaseModifierDestroy46.Callback[] {a, b};
    r.delegates = new RogueBaseModifierDestroy46.Callback[] {a, b};
    w.forcedVirtual = true;
    w.onVirtual =
        args -> {
          if (w.virtualCalls.size() == 1) {
            c.delegates = new RogueBaseModifierDestroy46.Callback[] {a, replacementLeft};
            r.delegates = new RogueBaseModifierDestroy46.Callback[] {a, replacementRight};
          }
        };
    assertTrue(RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertArrayEquals(new Object[] {replacementLeft, replacementRight}, w.virtualCalls.getLast());
  }

  @Test
  void shrinkingLeftListAfterTrueEndsWithoutComparingRemainingOriginalItems() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    var a = callback(null);
    c.delegates = new RogueBaseModifierDestroy46.Callback[] {a, a};
    r.delegates = new RogueBaseModifierDestroy46.Callback[] {a, a};
    w.forcedVirtual = true;
    w.onVirtual = args -> c.delegates = new RogueBaseModifierDestroy46.Callback[0];
    assertTrue(RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertEquals(1, w.virtualCalls.size());
  }

  @Test
  void shrinkingRightListAfterFirstTrueFailsNextItemBounds() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    var a = callback(null);
    c.delegates = new RogueBaseModifierDestroy46.Callback[] {a, a};
    r.delegates = new RogueBaseModifierDestroy46.Callback[] {a, a};
    w.forcedVirtual = true;
    w.onVirtual = args -> r.delegates = new RogueBaseModifierDestroy46.Callback[0];
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertEquals(1, w.virtualCalls.size());
  }

  @Test
  void nullCurrentRightListAfterFirstTrueFailsBeforeNextVirtualCall() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    var a = callback(null);
    c.delegates = new RogueBaseModifierDestroy46.Callback[] {a, a};
    r.delegates = new RogueBaseModifierDestroy46.Callback[] {a, a};
    w.forcedVirtual = true;
    w.onVirtual = args -> r.delegates = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertEquals(1, w.virtualCalls.size());
  }

  @Test
  void nullLeftItemFailsWhileNullRightItemIsPassedToVirtualComparison() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    c.delegates = new RogueBaseModifierDestroy46.Callback[] {null};
    r.delegates = new RogueBaseModifierDestroy46.Callback[] {null};
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertTrue(w.virtualCalls.isEmpty());
    c.delegates[0] = callback(null);
    assertFalse(RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertNull(w.virtualCalls.getFirst()[1]);
  }

  @Test
  void virtualComparisonFailurePreservesListReplacementAndStopsPrefix() {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    var a = callback(null);
    c.delegates = new RogueBaseModifierDestroy46.Callback[] {a, a};
    r.delegates = new RogueBaseModifierDestroy46.Callback[] {a, a};
    var replacement = new RogueBaseModifierDestroy46.Callback[] {a};
    w.onVirtual =
        args -> {
          c.delegates = replacement;
          throw new IllegalStateException("virtual");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueEventDelegateEquality46.multicastEquals(c, r, w));
    assertSame(replacement, c.delegates);
    assertEquals(1, w.virtualCalls.size());
  }

  @ParameterizedTest
  @ValueSource(ints = {255, 256})
  void finiteInvocationBoundaryPreservesExactLengthAndRejectsExcess(int length) {
    var c = callback(null);
    var r = callback(null);
    var w = new World();
    var a = callback(null);
    c.delegates = new RogueBaseModifierDestroy46.Callback[length];
    r.delegates = new RogueBaseModifierDestroy46.Callback[length];
    java.util.Arrays.fill(c.delegates, a);
    java.util.Arrays.fill(r.delegates, a);
    if (length == 255) {
      assertTrue(RogueEventDelegateEquality46.multicastEquals(c, r, w));
      assertEquals(255, w.virtualCalls.size());
    } else {
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueEventDelegateEquality46.multicastEquals(c, r, w));
      assertTrue(w.virtualCalls.isEmpty());
    }
  }

  @Test
  void independentlyAllocatedBaseWithdrawalDelegatesCompareEqualByMatchedMethodAndTarget() {
    var s = RogueModifierDestroy46Test.state();
    var base = new RogueBaseModifierDestroy46Test.World();
    RogueBaseModifierDestroy46.destroy(s, 4, base);
    RogueBaseModifierDestroy46.destroy(s, 4, base);
    var w = new World();
    var first = base.withdrawn.get(0).callback();
    var repeated = base.withdrawn.get(2).callback();
    var fade = base.withdrawn.get(1).callback();
    assertNotSame(first, repeated);
    assertTrue(RogueEventDelegateEquality46.multicastEquals(first, repeated, w));
    assertFalse(RogueEventDelegateEquality46.multicastEquals(first, fade, w));
  }

  @Test
  void missingMethodLookupWorldRejectedOnlyAfterTargetIdentityMatch() {
    var c = callback(null);
    var r = callback(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventDelegateEquality46.delegateEquals(c, r, null));
  }

  @Test
  void nullReceiverRejectedAsUnmatchedProjection() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventDelegateEquality46.multicastEquals(null, null, null));
  }
}
