package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueBindingPayload46Test {
  static final Object A = new Object(), B = new Object(), C = new Object();

  static RogueBindingPayload46.State dirty() {
    var s = RogueBindingPayload46.create();
    s.key = "old";
    s.first = new RogueBindingPayload46.Member(A, B, C);
    s.second = new RogueBindingPayload46.Member(B, C, A);
    s.third = new RogueBindingPayload46.Member(C, A, B);
    s.fourth = new RogueBindingPayload46.Member(A, C, B);
    s.context = A;
    s.behaviorFlags = B;
    return s;
  }

  static void zero(RogueBindingPayload46.State s, String empty) {
    assertSame(empty, s.key);
    assertEquals(RogueBindingPayload46.Member.ZERO, s.first);
    assertEquals(RogueBindingPayload46.Member.ZERO, s.second);
    assertEquals(RogueBindingPayload46.Member.ZERO, s.third);
    assertEquals(RogueBindingPayload46.Member.ZERO, s.fourth);
    assertNull(s.context);
    assertNull(s.behaviorFlags);
  }

  @Test
  void creationOnlyUsesFreshAllocationDefaultsWithoutStringEmptyAssignment() {
    var a = RogueBindingPayload46.create();
    var b = RogueBindingPayload46.create();
    assertNotSame(a, b);
    zero(a, null);
    zero(b, null);
    a.key = "x";
    assertNull(b.key);
  }

  @Test
  void matchedConstructorDoesNotClearAnExistingPayload() {
    var s = dirty();
    var first = s.first;
    RogueBindingPayload46.initialize(s);
    assertSame(first, s.first);
    assertEquals("old", s.key);
    assertSame(A, s.context);
    assertSame(B, s.behaviorFlags);
  }

  @Test
  void matchedRetConstructorAcceptsNullWithoutAccess() {
    assertDoesNotThrow(() -> RogueBindingPayload46.initialize(null));
  }

  @Test
  void clearUsesExactSharedEmptyReferenceAndZerosAllMembers() {
    var s = dirty();
    String empty = new String("");
    RogueBindingPayload46.clear(s, new RogueBindingPayload46.Catalog(empty));
    zero(s, empty);
  }

  @Test
  void clearReadsCurrentCatalogValueEachTime() {
    var s = dirty();
    String a = new String(""), b = new String("");
    var catalog = new RogueBindingPayload46.Catalog(a);
    RogueBindingPayload46.clear(s, catalog);
    assertSame(a, s.key);
    catalog.emptyString = b;
    RogueBindingPayload46.clear(s, catalog);
    zero(s, b);
  }

  @Test
  void explicitlyNullCatalogFieldIsNotReplacedByInventedString() {
    var s = dirty();
    RogueBindingPayload46.clear(s, new RogueBindingPayload46.Catalog(null));
    zero(s, null);
  }

  @Test
  void missingCatalogFailsBeforeAnyFieldWrite() {
    var s = dirty();
    var first = s.first;
    var fourth = s.fourth;
    assertThrows(IllegalArgumentException.class, () -> RogueBindingPayload46.clear(s, null));
    assertEquals("old", s.key);
    assertSame(first, s.first);
    assertSame(fourth, s.fourth);
    assertSame(A, s.context);
    assertSame(B, s.behaviorFlags);
  }

  @Test
  void missingPayloadFailsWithoutCatalogMutation() {
    var catalog = new RogueBindingPayload46.Catalog("");
    assertThrows(IllegalArgumentException.class, () -> RogueBindingPayload46.clear(null, catalog));
    assertEquals("", catalog.emptyString);
  }

  @Test
  void clearingDoesNotMutateDynamicFloatReferencesOrOldValueRecords() {
    var s = dirty();
    var old = s.first;
    RogueBindingPayload46.clear(s, new RogueBindingPayload46.Catalog(""));
    assertSame(A, old.first());
    assertSame(B, old.second());
    assertSame(C, old.third());
    assertNotSame(old, s.first);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void overwrittenMemberNullProjectionsAreReplacedByZeroValue(int index) {
    var s = dirty();
    if (index == 0) s.first = null;
    else if (index == 1) s.second = null;
    else if (index == 2) s.third = null;
    else s.fourth = null;
    RogueBindingPayload46.clear(s, new RogueBindingPayload46.Catalog(""));
    zero(s, "");
  }

  @Test
  void repeatedClearRetainsSharedValueAndLeavesOtherPayloadIndependent() {
    var a = dirty();
    var b = dirty();
    var catalog = new RogueBindingPayload46.Catalog("");
    RogueBindingPayload46.clear(a, catalog);
    RogueBindingPayload46.clear(a, catalog);
    zero(a, "");
    assertEquals("old", b.key);
    assertSame(A, b.context);
  }

  @Test
  void catalogFailureCanBeRetriedWithNoLostPayload() {
    var s = dirty();
    assertThrows(IllegalArgumentException.class, () -> RogueBindingPayload46.clear(s, null));
    String empty = new String("");
    RogueBindingPayload46.clear(s, new RogueBindingPayload46.Catalog(empty));
    zero(s, empty);
  }

  static class PoolHarness implements RogueReferencePool46.World {
    final RogueBindingPayload46.Catalog catalog = new RogueBindingPayload46.Catalog(new String(""));
    final RogueReferencePool46.Type type =
        new RogueReferencePool46.Type(
            RogueBindingPayload46.State.class,
            null,
            false,
            new RogueReferencePool46.Method[] {new RogueReferencePool46.Method(2, 0)});
    final RogueReferencePool46.Collection stored = RogueReferencePool46.createCollection(type);
    final RogueReferencePool46.Pool registry = new RogueReferencePool46.Pool(new HashMap<>());
    int allocations, clears;
    boolean failClear;

    @Override
    public Object allocate(RogueReferencePool46.Type declared) {
      allocations++;
      return RogueBindingPayload46.create();
    }

    @Override
    public void invokeInitializer(Object value, RogueReferencePool46.Method method) {
      RogueBindingPayload46.initialize((RogueBindingPayload46.State) value);
    }

    @Override
    public boolean isReference(Object value) {
      return value instanceof RogueBindingPayload46.State;
    }

    @Override
    public boolean isReferenceEx(Object value) {
      return false;
    }

    @Override
    public void onAlloc(Object value) {
      throw new AssertionError("not extended");
    }

    @Override
    public void onRecycle(Object value) {
      throw new AssertionError("not extended");
    }

    @Override
    public void clear(Object value) {
      clears++;
      RogueBindingPayload46.clear((RogueBindingPayload46.State) value, failClear ? null : catalog);
    }

    @Override
    public RogueReferencePool46.Type typeOf(Class<?> type) {
      throw new AssertionError("exact typed pool");
    }

    RogueBindingPayload46.State acquire() {
      return (RogueBindingPayload46.State) RogueReferencePool46.acquireTyped(stored, type, this);
    }

    void release(Object value) {
      RogueReferencePool46.releaseTyped(registry, stored, type, value, this);
    }
  }

  @Test
  void actualPoolAllocationClearReleaseAndReuseKeepSameBindingPayload() {
    var h = new PoolHarness();
    var s = h.acquire();
    assertNull(s.key);
    s.key = "a";
    s.first = new RogueBindingPayload46.Member(A, B, C);
    s.context = A;
    h.release(s);
    assertEquals(1, h.stored.count);
    zero(s, h.catalog.emptyString);
    assertSame(s, h.acquire());
    assertEquals(1, h.allocations);
    assertEquals(1, h.clears);
    assertEquals(0, h.stored.count);
  }

  @Test
  void actualPoolClearFailureKeepsPayloadOutOfPoolThenRetrySucceeds() {
    var h = new PoolHarness();
    var s = dirty();
    h.failClear = true;
    assertThrows(IllegalArgumentException.class, () -> h.release(s));
    assertEquals(0, h.stored.count);
    assertEquals("old", s.key);
    h.failClear = false;
    h.release(s);
    assertEquals(1, h.stored.count);
    zero(s, h.catalog.emptyString);
  }

  @Test
  void strictDuplicateFailureStillRunsActualBindingClearAgain() {
    var h = new PoolHarness();
    h.registry.strictCheck = true;
    var s = dirty();
    h.release(s);
    s.key = "changed";
    s.context = A;
    assertThrows(IllegalArgumentException.class, () -> h.release(s));
    assertEquals(1, h.stored.count);
    assertEquals(2, h.clears);
    zero(s, h.catalog.emptyString);
  }

  @Test
  void bindingWithdrawalUsesActualClearPoolThenRemovesMappingAndKeys() {
    var h = new PoolHarness();
    var value = dirty();
    var state = RogueModifierRegistrations46Test.state();
    state.bindKeys = RogueModifierRegistrations46Test.strings();
    state.bindKeys.lastIndex = 1;
    var w =
        new RogueModifierRegistrations46Test.World() {
          @Override
          public void recycleBinding(Object payload) {
            assertSame(value, payload);
            assertTrue(binds.containsKey("a"));
            assertEquals(2, state.bindKeys.count);
            h.release(payload);
            zero(value, h.catalog.emptyString);
            assertEquals(1, h.stored.count);
          }
        };
    w.binds.put("a", value);
    RogueModifierRegistrations46.popBindings(state, w);
    assertFalse(w.binds.containsKey("a"));
    assertEquals(0, state.bindKeys.count);
    assertSame(value, h.stored.references[0]);
    zero(value, h.catalog.emptyString);
  }

  @Test
  void bindingClearCatalogFailureRetainsActualMappingAndKeySet() {
    var h = new PoolHarness();
    h.failClear = true;
    var value = dirty();
    var state = RogueModifierRegistrations46Test.state();
    state.bindKeys = RogueModifierRegistrations46Test.strings();
    state.bindKeys.lastIndex = 1;
    var w =
        new RogueModifierRegistrations46Test.World() {
          @Override
          public void recycleBinding(Object payload) {
            h.release(payload);
          }
        };
    w.binds.put("a", value);
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierRegistrations46.popBindings(state, w));
    assertSame(value, w.binds.get("a"));
    assertEquals("old", value.key);
    assertEquals(2, state.bindKeys.count);
    assertEquals(9, state.bindKeys.version);
    assertEquals(0, h.stored.count);
  }

  @Test
  void repeatedActualBindingsReuseClearedObjectWithoutFurtherAllocation() {
    var h = new PoolHarness();
    var first = h.acquire();
    h.release(first);
    var second = h.acquire();
    second.key = "b";
    second.fourth = new RogueBindingPayload46.Member(C, B, A);
    h.release(second);
    assertSame(first, second);
    zero(second, h.catalog.emptyString);
    assertEquals(1, h.allocations);
    assertEquals(2, h.clears);
    assertEquals(1, h.stored.count);
  }
}
