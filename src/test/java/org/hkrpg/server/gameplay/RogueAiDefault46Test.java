package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class RogueAiDefault46Test {
  private static final class Bound implements RogueAiDefault46.World {
    final List<String> calls = new ArrayList<>();
    RogueModifierRegistrations46.Behavior allocated = new RogueModifierRegistrations46.Behavior(0);
    RogueAiDefault46.PriorityCatalog priorities = priorities(17);
    int[] layouts = new int[1];
    Runnable allocating = () -> {}, reading = () -> {}, arrayAllocating = () -> {};

    @Override
    public RogueModifierRegistrations46.Behavior allocateBehavior() {
      calls.add("behavior");
      allocating.run();
      return allocated;
    }

    @Override
    public RogueAiDefault46.PriorityCatalog priorityCatalog() {
      calls.add("config");
      reading.run();
      return priorities;
    }

    @Override
    public int[] allocateSkillLayouts(int length) {
      calls.add("array:" + length);
      arrayAllocating.run();
      return layouts;
    }
  }

  private static RogueAiDefault46.PriorityCatalog priorities(int value) {
    var p = new RogueAiDefault46.Priority();
    p.keys = new HashMap<>(Map.of("PROG_Default", value));
    var c = new RogueAiDefault46.PriorityCatalog();
    c.entries = new HashMap<>(Map.of("CrazyAIBehaviorPriority", p));
    return c;
  }

  private static RogueAiDefault46.Priority record(Bound w) {
    return w.priorities.entries.get("CrazyAIBehaviorPriority");
  }

  private static RogueAiDefault46.Catalog catalog() {
    var c = new RogueAiDefault46.Catalog();
    c.sharedDefault = new RogueModifierRegistrations46.Behavior(-7);
    return c;
  }

  private static RogueModifierRegistrations46.World selectionWorld(
      RogueAiDefault46.Catalog catalog, RogueModifierRegistrations46.Ai ai) {
    return (RogueModifierRegistrations46.World)
        Proxy.newProxyInstance(
            RogueModifierRegistrations46Test.class.getClassLoader(),
            new Class<?>[] {RogueModifierRegistrations46.World.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "defaultBehavior" -> catalog.sharedDefault;
                  case "ownerEntity" -> args[0];
                  case "aiComponent" -> ai;
                  default -> throw new AssertionError(method.getName());
                });
  }

  @Test
  void constructorKeepsFreshAllocationDefaults() {
    var b = new RogueModifierRegistrations46.Behavior(0);
    RogueAiDefault46.initialize(b);
    assertEquals(0, b.priority);
    assertNull(b.skillLayouts);
    assertFalse(b.firstFlag);
    assertFalse(b.secondFlag);
  }

  @Test
  void constructorDoesNotResetExistingFields() {
    var b = new RogueModifierRegistrations46.Behavior(-19);
    b.skillLayouts = new int[] {2, 0};
    b.firstFlag = true;
    int[] original = b.skillLayouts;
    RogueAiDefault46.initialize(b);
    assertEquals(-19, b.priority);
    assertSame(original, b.skillLayouts);
    assertTrue(b.firstFlag);
    assertFalse(b.secondFlag);
  }

  @Test
  void emptyConstructorDoesNotDereferenceNull() {
    assertDoesNotThrow(() -> RogueAiDefault46.initialize(null));
  }

  @Test
  void defaultComesFromConfigurationAndNormalEnum() {
    Bound w = new Bound();
    var c = catalog();
    var result = RogueAiDefault46.initializeCatalog(c, w);
    assertSame(w.allocated, result);
    assertSame(result, c.sharedDefault);
    assertEquals(17, result.priority);
    assertSame(w.layouts, result.skillLayouts);
    assertArrayEquals(new int[] {1}, result.skillLayouts);
    assertTrue(result.firstFlag);
    assertTrue(result.secondFlag);
    assertEquals(List.of("behavior", "config", "array:1"), w.calls);
  }

  @Test
  void negativeAndMinimumPrioritiesAreNotClamped() {
    Bound w = new Bound();
    w.priorities = priorities(Integer.MIN_VALUE);
    assertEquals(Integer.MIN_VALUE, RogueAiDefault46.initializeCatalog(catalog(), w).priority);
  }

  @Test
  void maximumPriorityIsNotClamped() {
    Bound w = new Bound();
    w.priorities = priorities(Integer.MAX_VALUE);
    assertEquals(Integer.MAX_VALUE, RogueAiDefault46.initializeCatalog(catalog(), w).priority);
  }

  @Test
  void zeroIsAnExplicitConfiguredValue() {
    Bound w = new Bound();
    w.priorities = priorities(0);
    assertEquals(0, RogueAiDefault46.initializeCatalog(catalog(), w).priority);
  }

  @Test
  void readsConfigurationAfterAllocationCallback() {
    Bound w = new Bound();
    w.allocating = () -> w.priorities = priorities(-23);
    assertEquals(-23, RogueAiDefault46.initializeCatalog(catalog(), w).priority);
  }

  @Test
  void allocationFailureDoesNotReplacePublishedObject() {
    Bound w = new Bound();
    var c = catalog();
    var old = c.sharedDefault;
    w.allocating =
        () -> {
          throw new IllegalStateException("allocate");
        };
    assertThrows(IllegalStateException.class, () -> RogueAiDefault46.initializeCatalog(c, w));
    assertSame(old, c.sharedDefault);
    assertEquals(List.of("behavior"), w.calls);
  }

  @Test
  void nullAllocationStillReadsAndValidatesPriorityBeforeFailing() {
    Bound w = new Bound();
    w.allocated = null;
    var c = catalog();
    var old = c.sharedDefault;
    assertThrows(IllegalArgumentException.class, () -> RogueAiDefault46.initializeCatalog(c, w));
    assertEquals(List.of("behavior", "config"), w.calls);
    assertSame(old, c.sharedDefault);
  }

  @Test
  void missingConfigTakesPrecedenceOverMissingAllocation() {
    Bound w = new Bound();
    w.allocated = null;
    w.priorities = null;
    var e =
        assertThrows(
            IllegalArgumentException.class, () -> RogueAiDefault46.initializeCatalog(catalog(), w));
    assertTrue(e.getMessage().contains("priority catalog"));
    assertEquals(List.of("behavior", "config"), w.calls);
  }

  @Test
  void configFailureKeepsAllocatedDefaultFieldsAndOldPublishedReference() {
    Bound w = new Bound();
    var c = catalog();
    var old = c.sharedDefault;
    w.reading =
        () -> {
          throw new IllegalStateException("config");
        };
    assertThrows(IllegalStateException.class, () -> RogueAiDefault46.initializeCatalog(c, w));
    assertEquals(0, w.allocated.priority);
    assertNull(w.allocated.skillLayouts);
    assertFalse(w.allocated.firstFlag);
    assertSame(old, c.sharedDefault);
    assertEquals(List.of("behavior", "config"), w.calls);
  }

  @Test
  void arrayAllocationObservesPriorityBeforePublication() {
    Bound w = new Bound();
    var c = catalog();
    var old = c.sharedDefault;
    w.arrayAllocating =
        () -> {
          assertEquals(17, w.allocated.priority);
          assertNull(w.allocated.skillLayouts);
          assertFalse(w.allocated.firstFlag);
          assertSame(old, c.sharedDefault);
        };
    RogueAiDefault46.initializeCatalog(c, w);
  }

  @Test
  void arrayFailureRetainsPriorityPrefixAndOldCatalog() {
    Bound w = new Bound();
    var c = catalog();
    var old = c.sharedDefault;
    w.arrayAllocating =
        () -> {
          throw new IllegalStateException("array");
        };
    assertThrows(IllegalStateException.class, () -> RogueAiDefault46.initializeCatalog(c, w));
    assertEquals(17, w.allocated.priority);
    assertNull(w.allocated.skillLayouts);
    assertFalse(w.allocated.firstFlag);
    assertFalse(w.allocated.secondFlag);
    assertSame(old, c.sharedDefault);
  }

  @Test
  void nullArrayDoesNotPublishOrSetFlags() {
    Bound w = new Bound();
    w.layouts = null;
    var c = catalog();
    var old = c.sharedDefault;
    assertThrows(IllegalArgumentException.class, () -> RogueAiDefault46.initializeCatalog(c, w));
    assertEquals(17, w.allocated.priority);
    assertNull(w.allocated.skillLayouts);
    assertFalse(w.allocated.secondFlag);
    assertSame(old, c.sharedDefault);
  }

  @Test
  void zeroLengthArrayFailsAfterPriorityAndBeforeFlagsOrPublication() {
    Bound w = new Bound();
    w.layouts = new int[0];
    var c = catalog();
    var old = c.sharedDefault;
    assertThrows(IndexOutOfBoundsException.class, () -> RogueAiDefault46.initializeCatalog(c, w));
    assertEquals(17, w.allocated.priority);
    assertNull(w.allocated.skillLayouts);
    assertSame(old, c.sharedDefault);
  }

  @Test
  void sourceWritesOnlyFirstElementOfExplicitArray() {
    Bound w = new Bound();
    w.layouts = new int[] {8, 9};
    RogueAiDefault46.initializeCatalog(catalog(), w);
    assertArrayEquals(new int[] {1, 9}, w.layouts);
  }

  @Test
  void failedReinitializationKeepsExistingArrayAndFlagsAfterPriorityPrefix() {
    Bound w = new Bound();
    int[] oldArray = {2};
    w.allocated.skillLayouts = oldArray;
    w.allocated.secondFlag = true;
    w.layouts = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueAiDefault46.initializeCatalog(catalog(), w));
    assertSame(oldArray, w.allocated.skillLayouts);
    assertFalse(w.allocated.firstFlag);
    assertTrue(w.allocated.secondFlag);
    assertEquals(17, w.allocated.priority);
  }

  @Test
  void successfulPublishOverridesCallbackCatalogReplacementLast() {
    Bound w = new Bound();
    var c = catalog();
    var temporary = new RogueModifierRegistrations46.Behavior(31);
    w.arrayAllocating = () -> c.sharedDefault = temporary;
    var result = RogueAiDefault46.initializeCatalog(c, w);
    assertSame(result, c.sharedDefault);
    assertNotSame(temporary, c.sharedDefault);
  }

  @Test
  void failurePreservesCallbackCatalogReplacementWithoutInventedRollback() {
    Bound w = new Bound();
    var c = catalog();
    var temporary = new RogueModifierRegistrations46.Behavior(31);
    w.arrayAllocating =
        () -> {
          c.sharedDefault = temporary;
          throw new IllegalStateException("array");
        };
    assertThrows(IllegalStateException.class, () -> RogueAiDefault46.initializeCatalog(c, w));
    assertSame(temporary, c.sharedDefault);
  }

  @Test
  void retryUsesNewConfigurationAndDoesNotReuseFailedArray() {
    Bound w = new Bound();
    var c = catalog();
    w.layouts = new int[0];
    assertThrows(IndexOutOfBoundsException.class, () -> RogueAiDefault46.initializeCatalog(c, w));
    w.layouts = new int[1];
    w.priorities = priorities(91);
    var result = RogueAiDefault46.initializeCatalog(c, w);
    assertEquals(91, result.priority);
    assertSame(w.layouts, result.skillLayouts);
    assertSame(result, c.sharedDefault);
  }

  @Test
  void explicitCatalogsStayIndependent() {
    Bound a = new Bound(), b = new Bound();
    b.priorities = priorities(81);
    var ca = catalog();
    var cb = catalog();
    RogueAiDefault46.initializeCatalog(ca, a);
    RogueAiDefault46.initializeCatalog(cb, b);
    assertNotSame(ca.sharedDefault, cb.sharedDefault);
    assertEquals(17, ca.sharedDefault.priority);
    assertEquals(81, cb.sharedDefault.priority);
  }

  @Test
  void missingWorldCannotAllocateOrOverwriteCatalog() {
    var c = catalog();
    var old = c.sharedDefault;
    assertThrows(IllegalArgumentException.class, () -> RogueAiDefault46.initializeCatalog(c, null));
    assertSame(old, c.sharedDefault);
  }

  @Test
  void missingProjectionCatalogCannotStartAllocation() {
    Bound w = new Bound();
    assertThrows(IllegalArgumentException.class, () -> RogueAiDefault46.initializeCatalog(null, w));
    assertTrue(w.calls.isEmpty());
  }

  @Test
  void missingOuterMapDoesNotReturnZero() {
    var c = new RogueAiDefault46.PriorityCatalog();
    assertThrows(IllegalArgumentException.class, () -> RogueAiDefault46.lookupPriority(c, "x"));
  }

  @Test
  void missingOuterKeyFailsWithActualRequestedKey() {
    var c = priorities(17);
    var e =
        assertThrows(
            NoSuchElementException.class, () -> RogueAiDefault46.lookupPriority(c, "unknown"));
    assertEquals("unknown", e.getMessage());
  }

  @Test
  void presentNullOuterRecordFailsInsteadOfUsingAnotherEntry() {
    var c = priorities(17);
    c.entries.put("CrazyAIBehaviorPriority", null);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAiDefault46.lookupPriority(c, "CrazyAIBehaviorPriority"));
  }

  @Test
  void missingInnerMapFails() {
    Bound w = new Bound();
    record(w).keys = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAiDefault46.lookupPriority(w.priorities, "CrazyAIBehaviorPriority"));
  }

  @Test
  void otherInnerKeyDoesNotReplaceProgDefault() {
    Bound w = new Bound();
    record(w).keys = Map.of("Default", 89);
    var e =
        assertThrows(
            NoSuchElementException.class,
            () -> RogueAiDefault46.lookupPriority(w.priorities, "CrazyAIBehaviorPriority"));
    assertEquals("PROG_Default", e.getMessage());
  }

  @Test
  void nullBoxedValueIsAnInvalidProjection() {
    Bound w = new Bound();
    record(w).keys.put("PROG_Default", null);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAiDefault46.lookupPriority(w.priorities, "CrazyAIBehaviorPriority"));
  }

  @Test
  void caseSensitiveConfigKeyDoesNotMatchLowercase() {
    var c = priorities(17);
    assertThrows(
        NoSuchElementException.class,
        () -> RogueAiDefault46.lookupPriority(c, "crazyAIBehaviorPriority"));
  }

  @Test
  void lookupAcceptsEqualStringContentsAndReturnsOnlyProgDefault() {
    Bound w = new Bound();
    record(w).keys.put("other", -80);
    assertEquals(
        17, RogueAiDefault46.lookupPriority(w.priorities, new String("CrazyAIBehaviorPriority")));
  }

  @Test
  void nullPriorityKeyIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueAiDefault46.lookupPriority(priorities(17), null));
  }

  @Test
  void lookupFailureDoesNotOverwriteDirtyAllocatedBehavior() {
    Bound w = new Bound();
    w.allocated.priority = 55;
    record(w).keys.clear();
    var c = catalog();
    var old = c.sharedDefault;
    assertThrows(NoSuchElementException.class, () -> RogueAiDefault46.initializeCatalog(c, w));
    assertEquals(55, w.allocated.priority);
    assertSame(old, c.sharedDefault);
    assertEquals(List.of("behavior", "config"), w.calls);
  }

  @Test
  void defaultCatalogFeedsActualAiSelectionByIdentity() {
    Bound w = new Bound();
    var c = catalog();
    var initialized = RogueAiDefault46.initializeCatalog(c, w);
    var ai = new RogueModifierRegistrations46.Ai();
    assertSame(initialized, RogueModifierRegistrations46.selectAi(ai, selectionWorld(c, ai)));
    ai.behaviors = new RogueOwnedParameterLists46.References(null, 0, 9);
    assertSame(initialized, RogueModifierRegistrations46.selectAi(ai, selectionWorld(c, ai)));
  }

  @Test
  void nonemptyActualAiListKeepsRegisteredBehaviorInsteadOfDefault() {
    Bound w = new Bound();
    var c = catalog();
    RogueAiDefault46.initializeCatalog(c, w);
    var ai = new RogueModifierRegistrations46.Ai();
    var registered = new RogueModifierRegistrations46.Behavior(-50);
    ai.behaviors = new RogueOwnedParameterLists46.References(new Object[] {registered}, 1, 2);
    assertSame(registered, RogueModifierRegistrations46.selectAi(ai, selectionWorld(c, ai)));
  }

  @Test
  void configuredDefaultUsesExistingSignedComparatorWithoutOverflow() {
    Bound w = new Bound();
    w.priorities = priorities(Integer.MIN_VALUE);
    var result = RogueAiDefault46.initializeCatalog(catalog(), w);
    assertEquals(
        -1,
        RogueModifierRegistrations46.compareBehavior(
            result, new RogueModifierRegistrations46.Behavior(Integer.MAX_VALUE)));
    assertEquals(
        1,
        RogueModifierRegistrations46.compareBehavior(
            new RogueModifierRegistrations46.Behavior(Integer.MAX_VALUE), result));
  }

  @Test
  void actualWithdrawalOfLastRegisteredBehaviorRestoresConstructedDefault() {
    Bound w = new Bound();
    var c = catalog();
    var shared = RogueAiDefault46.initializeCatalog(c, w);
    var state = RogueModifierRegistrations46Test.state();
    var registered = new RogueModifierRegistrations46.Behavior(-99);
    state.crazyBehavior = registered;
    var ai = new RogueModifierRegistrations46.Ai();
    ai.current = registered;
    ai.behaviors = new RogueOwnedParameterLists46.References(new Object[] {registered}, 1, 7);
    RogueModifierRegistrations46.popAi(state, selectionWorld(c, ai));
    assertSame(shared, ai.current);
    assertNull(state.crazyBehavior);
    assertEquals(0, ai.behaviors.size);
    assertEquals(8, ai.behaviors.version);
    assertNull(ai.behaviors.entries[0]);
    assertArrayEquals(new int[] {1}, shared.skillLayouts);
    assertEquals(17, shared.priority);
  }

  @Test
  void withdrawalAfterFailedCatalogRefreshUsesPreviousPublishedDefault() {
    Bound w = new Bound();
    var c = catalog();
    var previous = RogueAiDefault46.initializeCatalog(c, w);
    w.allocated = new RogueModifierRegistrations46.Behavior(0);
    w.layouts = null;
    assertThrows(IllegalArgumentException.class, () -> RogueAiDefault46.initializeCatalog(c, w));
    var state = RogueModifierRegistrations46Test.state();
    var ai = new RogueModifierRegistrations46.Ai();
    state.crazyBehavior = new Object();
    ai.current = state.crazyBehavior;
    ai.behaviors =
        new RogueOwnedParameterLists46.References(new Object[] {state.crazyBehavior}, 1, 0);
    RogueModifierRegistrations46.popAi(state, selectionWorld(c, ai));
    assertSame(previous, ai.current);
    assertSame(previous, c.sharedDefault);
    assertNull(state.crazyBehavior);
  }

  @Test
  void removingOneOfTwoBehaviorsKeepsRemainingActualRegistration() {
    Bound w = new Bound();
    var c = catalog();
    RogueAiDefault46.initializeCatalog(c, w);
    var state = RogueModifierRegistrations46Test.state();
    var ai = new RogueModifierRegistrations46.Ai();
    var removed = new RogueModifierRegistrations46.Behavior(19);
    var remaining = new RogueModifierRegistrations46.Behavior(41);
    state.crazyBehavior = removed;
    ai.current = removed;
    ai.behaviors =
        new RogueOwnedParameterLists46.References(new Object[] {removed, remaining}, 2, 8);
    RogueModifierRegistrations46.popAi(state, selectionWorld(c, ai));
    assertSame(remaining, ai.current);
    assertEquals(1, ai.behaviors.size);
    assertEquals(9, ai.behaviors.version);
    assertSame(remaining, ai.behaviors.entries[0]);
    assertNull(ai.behaviors.entries[1]);
    assertNull(state.crazyBehavior);
  }
}
