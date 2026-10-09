package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueModifierRangeLifecycle46.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RogueModifierRangeLifecycle46Test {
  static final Object OWNER = new Object();
  static final long ONE = 1L << 33;

  static class World extends RogueModifierCasterChange46Test.World
      implements RogueModifierRangeLifecycle46.World {
    Object ownerComponent = OWNER, owner = OWNER;
    final List<Object> exits = new ArrayList<>();
    Consumer<Object> exit = q -> {};

    World() {
      values.put(OWNER, variables());
    }

    public Object ownerAbilityComponent(State s) {
      order.add("ownerComponent");
      return ownerComponent;
    }

    public Object ownerEntity(Object c) {
      order.add("ownerEntity");
      return owner;
    }

    public void executeSequence(Object q) {
      exits.add(q);
      exit.accept(q);
    }
  }

  static State state(RogueModifierDynamicRange46.Entry... entries) {
    var range = RogueModifierDynamicRange46Test.state(entries);
    var groups = new Groups();
    groups.put(scopedKey(7, 4), range.listenerGroups.get(scopedKey(7, 4)));
    range.listenerGroups = groups;
    range.replacement.values.dynamicValues = World.variables();
    return new State(range, new RogueModifierDynamicRange46Test.World());
  }

  static void attach(State s, World w) {
    s.caster.listenScopeMask = 52;
    s.caster.caster = RogueModifierCasterChange46Test.B;
    w.values.get(OWNER).combineObserver(s.ownerListener());
    w.values.get(RogueModifierCasterChange46Test.B).combineObserver(s.caster.listener());
    ((RogueResonanceVariables46) s.range.replacement.values.dynamicValues)
        .combineObserver(s.modifierListener());
  }

  static RogueModifierDynamicRange46.Entry active() {
    var e = RogueModifierDynamicRange46Test.entry();
    e.inRange = true;
    return e;
  }

  @Test
  void scopeHashAndDistinctListenerMethodsArePreserved() {
    var s = state();
    assertSame(s.range.replacement, s.caster.replacement);
    assertEquals(0x8000000000000002L, scopedKey(Integer.MIN_VALUE, 2));
    assertEquals(0x8000000000000004L, scopedKey(Integer.MIN_VALUE, 4));
    assertEquals(0x8000000000000005L, scopedKey(Integer.MIN_VALUE, 5));
    var registry = new RogueResonanceObserver46();
    registry.combine(s.ownerListener());
    registry.combine(s.modifierListener());
    registry.combine(s.caster.listener());
    assertTrue(registry.remove(s.ownerListener()));
    assertFalse(registry.remove(s.ownerListener()));
    assertEquals(List.of(s.modifierListener(), s.caster.listener()), registry.snapshot());
  }

  @Test
  void unknownScopeIsClosed() {
    assertThrows(IllegalArgumentException.class, () -> scopedKey(7, 0));
    assertThrows(IllegalArgumentException.class, () -> scopedKey(7, 6));
  }

  @Test
  void noMaskSkipsAllWorldReads() {
    var s = state();
    var w = new World();
    removeRootListeners(s, w);
    assertTrue(w.order.isEmpty());
  }

  @Test
  void unknownMaskBitsAreClearedWithoutInventingListeners() {
    var s = state();
    var w = new World();
    s.caster.listenScopeMask = 1;
    removeRootListeners(s, w);
    assertEquals(0, s.caster.listenScopeMask);
    assertTrue(w.order.isEmpty());
  }

  @Test
  void ownerCasterModifierUnsubscribeInNativeOrder() {
    var s = state();
    var w = new World();
    attach(s, w);
    removeRootListeners(s, w);
    assertEquals(
        List.of("ownerComponent", "ownerEntity", "component", "values", "component", "values"),
        w.order);
    assertEquals(0, w.values.get(OWNER).attachedObserverCount());
    assertEquals(0, w.values.get(RogueModifierCasterChange46Test.B).attachedObserverCount());
    assertEquals(
        0,
        ((RogueResonanceVariables46) s.range.replacement.values.dynamicValues)
            .attachedObserverCount());
    assertEquals(0, s.caster.listenScopeMask);
  }

  @Test
  void missingOwnerComponentPreservesMaskAndOtherListeners() {
    var s = state();
    var w = new World();
    attach(s, w);
    w.ownerComponent = null;
    assertThrows(IllegalArgumentException.class, () -> removeRootListeners(s, w));
    assertEquals(52, s.caster.listenScopeMask);
    assertEquals(1, w.values.get(RogueModifierCasterChange46Test.B).attachedObserverCount());
  }

  @Test
  void missingOwnerEntityIsRequired() {
    var s = state();
    var w = new World();
    attach(s, w);
    w.owner = null;
    assertThrows(IllegalArgumentException.class, () -> removeRootListeners(s, w));
    assertEquals(52, s.caster.listenScopeMask);
  }

  @Test
  void missingOwnerNumericComponentIsRequired() {
    var s = state();
    var w =
        new World() {
          public Object casterComponent(Object e) {
            return e == OWNER ? null : super.casterComponent(e);
          }
        };
    attach(s, w);
    assertThrows(IllegalArgumentException.class, () -> removeRootListeners(s, w));
    assertEquals(52, s.caster.listenScopeMask);
  }

  @Test
  void missingOwnerRootIsRequired() {
    var s = state();
    var w = new World();
    attach(s, w);
    w.values.remove(OWNER);
    assertThrows(IllegalArgumentException.class, () -> removeRootListeners(s, w));
    assertEquals(52, s.caster.listenScopeMask);
  }

  @Test
  void ownerCallbackMaskChangesAreObservedBeforeCasterBranch() {
    var s = state();
    var w =
        new World() {
          public RogueResonanceVariables46 casterVariables(Object c) {
            if (c == OWNER) s.caster.listenScopeMask = 0;
            return super.casterVariables(c);
          }
        };
    attach(s, w);
    removeRootListeners(s, w);
    assertEquals(1, w.values.get(RogueModifierCasterChange46Test.B).attachedObserverCount());
    assertEquals(
        1,
        ((RogueResonanceVariables46) s.range.replacement.values.dynamicValues)
            .attachedObserverCount());
    assertEquals(0, s.caster.listenScopeMask);
  }

  @Test
  void cachedCasterAvoidsContextRead() {
    var s = state();
    var w = new World();
    attach(s, w);
    w.context = null;
    removeRootListeners(s, w);
    assertEquals(0, w.contextReads);
  }

  @Test
  void absentCachedCasterRereadsAndCachesGetterResult() {
    var s = state();
    var w = new World();
    attach(s, w);
    s.caster.caster = null;
    removeRootListeners(s, w);
    assertSame(RogueModifierCasterChange46Test.B, s.caster.caster);
    assertEquals(1, w.contextReads);
    assertEquals(1, w.casterReads);
  }

  @Test
  void nullCasterGetterSkipsCasterComponentAndCompletesOtherBranches() {
    var s = state();
    var w = new World();
    attach(s, w);
    s.caster.caster = null;
    w.caster = null;
    removeRootListeners(s, w);
    assertNull(s.caster.caster);
    assertEquals(1, w.componentReads);
    assertEquals(0, s.caster.listenScopeMask);
    assertEquals(1, w.values.get(RogueModifierCasterChange46Test.B).attachedObserverCount());
  }

  @Test
  void missingCasterContextFailsAfterOwnerRemoval() {
    var s = state();
    var w = new World();
    attach(s, w);
    s.caster.caster = null;
    w.context = null;
    assertThrows(IllegalArgumentException.class, () -> removeRootListeners(s, w));
    assertEquals(0, w.values.get(OWNER).attachedObserverCount());
    assertEquals(52, s.caster.listenScopeMask);
  }

  @Test
  void missingCasterNumericComponentFailsAfterOwnerRemoval() {
    var s = state();
    var w =
        new World() {
          public Object casterComponent(Object e) {
            return e == RogueModifierCasterChange46Test.B ? null : super.casterComponent(e);
          }
        };
    attach(s, w);
    assertThrows(IllegalArgumentException.class, () -> removeRootListeners(s, w));
    assertEquals(0, w.values.get(OWNER).attachedObserverCount());
    assertEquals(52, s.caster.listenScopeMask);
  }

  @Test
  void missingCasterRootIsOptional() {
    var s = state();
    var w = new World();
    attach(s, w);
    w.values.remove(RogueModifierCasterChange46Test.B);
    assertDoesNotThrow(() -> removeRootListeners(s, w));
    assertEquals(0, s.caster.listenScopeMask);
  }

  @Test
  void missingModifierRootFailsAfterOtherListenersRemoved() {
    var s = state();
    var w = new World();
    attach(s, w);
    s.range.replacement.values.dynamicValues = null;
    assertThrows(IllegalArgumentException.class, () -> removeRootListeners(s, w));
    assertEquals(
        0,
        w.values.get(OWNER).attachedObserverCount()
            + w.values.get(RogueModifierCasterChange46Test.B).attachedObserverCount());
    assertEquals(52, s.caster.listenScopeMask);
  }

  @Test
  void modifierMaskIsRereadAfterCasterValuesBinding() {
    var s = state();
    var w =
        new World() {
          public RogueResonanceVariables46 casterVariables(Object c) {
            if (c == RogueModifierCasterChange46Test.B) s.caster.listenScopeMask = 16;
            return super.casterVariables(c);
          }
        };
    attach(s, w);
    removeRootListeners(s, w);
    assertEquals(
        1,
        ((RogueResonanceVariables46) s.range.replacement.values.dynamicValues)
            .attachedObserverCount());
    assertEquals(0, s.caster.listenScopeMask);
  }

  @Test
  void duplicateRegistrationRemovesOnlyLastMatchingOccurrence() {
    var s = state();
    var w = new World();
    attach(s, w);
    w.values.get(OWNER).combineObserver(s.ownerListener());
    w.values.get(RogueModifierCasterChange46Test.B).combineObserver(s.caster.listener());
    ((RogueResonanceVariables46) s.range.replacement.values.dynamicValues)
        .combineObserver(s.modifierListener());
    removeRootListeners(s, w);
    assertEquals(1, w.values.get(OWNER).attachedObserverCount());
    assertEquals(1, w.values.get(RogueModifierCasterChange46Test.B).attachedObserverCount());
    assertEquals(
        1,
        ((RogueResonanceVariables46) s.range.replacement.values.dynamicValues)
            .attachedObserverCount());
  }

  @Test
  void activeRangesExitWithoutChangingRangeStateOrCounter() {
    var e = active();
    e.exitDepth = 127;
    var s = state(e);
    var w = new World();
    w.exit =
        q -> {
          assertTrue(e.inRange);
          assertEquals(127, e.exitDepth);
        };
    exitActiveRanges(s, w);
    assertEquals(List.of(e.exitSequence), w.exits);
    assertTrue(e.inRange);
    assertEquals(127, e.exitDepth);
    assertNull(s.range.listenerGroups);
  }

  @Test
  void inactiveRangesAndMissingExitSequencesSkip() {
    var a = RogueModifierDynamicRange46Test.entry();
    var b = active();
    b.exitSequence = null;
    var s = state(a, b);
    var w = new World();
    exitActiveRanges(s, w);
    assertTrue(w.exits.isEmpty());
    assertNull(s.range.listenerGroups);
  }

  @Test
  void missingDictionaryReturnsWithoutWorldCalls() {
    var s = state();
    s.range.listenerGroups = null;
    var w = new World();
    exitActiveRanges(s, w);
    assertTrue(w.exits.isEmpty());
  }

  @Test
  void ordinaryDictionaryIsClosedAfterActualListenerRetirement() {
    var s = state();
    var w = new World();
    attach(s, w);
    s.range.listenerGroups = new HashMap<>();
    assertThrows(IllegalArgumentException.class, () -> retire(s, w));
    assertEquals(0, s.caster.listenScopeMask);
    assertNotNull(s.range.listenerGroups);
    assertEquals(0, w.values.get(OWNER).attachedObserverCount());
  }

  @Test
  void failingExitKeepsCurrentDictionaryAfterActualListenersRemoved() {
    var e = active();
    var s = state(e);
    var groups = s.range.listenerGroups;
    var w = new World();
    attach(s, w);
    w.exit =
        q -> {
          throw new IllegalStateException();
        };
    assertThrows(IllegalStateException.class, () -> retire(s, w));
    assertEquals(0, s.caster.listenScopeMask);
    assertSame(groups, s.range.listenerGroups);
    assertTrue(e.inRange);
    assertEquals(0, e.exitDepth);
  }

  @Test
  void dictionaryReplacementDuringExitIsClearedAfterCapturedIteration() {
    var a = active();
    var b = active();
    var c = active();
    var s = state(a, b);
    var replacement = new Groups();
    replacement.put(scopedKey(7, 2), List.of(c));
    var w = new World();
    w.exit = q -> s.range.listenerGroups = replacement;
    exitActiveRanges(s, w);
    assertEquals(2, w.exits.size());
    assertTrue(c.inRange);
    assertNull(s.range.listenerGroups);
  }

  @Test
  void replacementOfExistingValueInvalidatesCapturedVersion() {
    var e = active();
    var s = state(e);
    var groups = (Groups) s.range.listenerGroups;
    var w = new World();
    w.exit = q -> groups.put(scopedKey(7, 4), List.of(e));
    assertThrows(IllegalArgumentException.class, () -> exitActiveRanges(s, w));
    assertSame(groups, s.range.listenerGroups);
    assertEquals(1, w.exits.size());
  }

  @Test
  void dictionaryAdditionInvalidatesVersionAfterCurrentGroup() {
    var a = active();
    var b = active();
    var s = state(a, b);
    var groups = (Groups) s.range.listenerGroups;
    var w = new World();
    w.exit = q -> groups.put(scopedKey(8, 4), List.of());
    assertThrows(IllegalArgumentException.class, () -> exitActiveRanges(s, w));
    assertEquals(2, w.exits.size());
    assertSame(groups, s.range.listenerGroups);
  }

  @Test
  void listAppendIsObservedWithinCapturedGroup() {
    var a = active();
    var b = active();
    var s = state(a);
    var w = new World();
    w.exit =
        q -> {
          if (w.exits.size() == 1) s.range.listenerGroups.get(scopedKey(7, 4)).add(b);
        };
    exitActiveRanges(s, w);
    assertEquals(2, w.exits.size());
    assertNull(s.range.listenerGroups);
  }

  @Test
  void listClearStopsCurrentGroupWithoutDictionaryVersionChange() {
    var a = active();
    var b = active();
    var s = state(a, b);
    var w = new World();
    w.exit = q -> s.range.listenerGroups.get(scopedKey(7, 4)).clear();
    exitActiveRanges(s, w);
    assertEquals(1, w.exits.size());
    assertNull(s.range.listenerGroups);
  }

  @Test
  void currentRangeStateIsReadForEachEntry() {
    var a = active();
    var b = active();
    var s = state(a, b);
    var w = new World();
    w.exit = q -> b.inRange = false;
    exitActiveRanges(s, w);
    assertEquals(1, w.exits.size());
    assertFalse(b.inRange);
  }

  @Test
  void missingGroupFailsAfterMaskClear() {
    var s = state();
    var groups = (Groups) s.range.listenerGroups;
    groups.put(scopedKey(7, 4), null);
    var w = new World();
    attach(s, w);
    assertThrows(IllegalArgumentException.class, () -> retire(s, w));
    assertEquals(0, s.caster.listenScopeMask);
    assertSame(groups, s.range.listenerGroups);
  }

  @Test
  void missingEntryFailsAfterSuccessfulPrefix() {
    var e = active();
    var s = state(e, (RogueModifierDynamicRange46.Entry) null);
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> exitActiveRanges(s, w));
    assertEquals(List.of(e.exitSequence), w.exits);
    assertNotNull(s.range.listenerGroups);
  }

  @Test
  void projectedModifierObserverMembershipStaysClosed() {
    var s = state();
    var w = new World();
    attach(s, w);
    var root = (RogueResonanceVariables46) s.range.replacement.values.dynamicValues;
    s.range.replacement.values.dynamicValues =
        RogueResonanceVariables46.withAiTagProjection(root, Map.of());
    assertThrows(IllegalArgumentException.class, () -> removeRootListeners(s, w));
    assertEquals(52, s.caster.listenScopeMask);
    assertEquals(0, w.values.get(OWNER).attachedObserverCount());
  }

  @Test
  void retirementDisablesActualCasterRootNotifications() {
    var e = active();
    var range = RogueModifierDynamicRange46Test.state(e);
    range.listenerGroups = new Groups();
    range.listenerGroups.put(scopedKey(7, 4), new ArrayList<>(List.of(e)));
    range.replacement.values.dynamicValues = World.variables();
    var rw = new RogueModifierDynamicRange46Test.World();
    var s = new State(range, rw);
    var w = new World();
    s.caster.listenScopeMask = 16;
    RogueModifierCasterChange46.changed(s.caster, w);
    w.values.get(RogueModifierCasterChange46Test.B).write(7, 2 * ONE);
    assertEquals(List.of(RogueModifierDynamicRange46Test.CHANGE), rw.sequences);
    retire(s, w);
    w.values.get(RogueModifierCasterChange46Test.B).write(7, 3 * ONE);
    assertEquals(1, rw.sequences.size());
    assertEquals(List.of(e.exitSequence), w.exits);
    assertNull(range.listenerGroups);
  }

  @Test
  void ownerAndModifierRootsDispatchDifferentActualScopes() {
    var ownerEntry = RogueModifierDynamicRange46Test.entry();
    var modifierEntry = RogueModifierDynamicRange46Test.entry();
    var range = RogueModifierDynamicRange46Test.state();
    range.replacement.values.dynamicValues = World.variables();
    range.listenerGroups = new Groups();
    range.listenerGroups.put(scopedKey(7, 5), List.of(ownerEntry));
    range.listenerGroups.put(scopedKey(7, 2), List.of(modifierEntry));
    var rw = new RogueModifierDynamicRange46Test.World();
    var s = new State(range, rw);
    var w = new World();
    attach(s, w);
    w.values.get(OWNER).write(7, 2 * ONE);
    assertTrue(ownerEntry.inRange);
    assertFalse(modifierEntry.inRange);
    ((RogueResonanceVariables46) s.range.replacement.values.dynamicValues).write(7, 2 * ONE);
    assertTrue(modifierEntry.inRange);
    retire(s, w);
    w.values.get(OWNER).write(7, 3 * ONE);
    assertEquals(2, rw.sequences.size());
  }

  @Test
  void groupsExposeNoUnversionedValueReplacement() {
    var groups = new Groups();
    groups.put(1L, List.of());
    assertThrows(
        UnsupportedOperationException.class,
        () -> groups.entrySet().iterator().next().setValue(List.of()));
    assertThrows(UnsupportedOperationException.class, () -> groups.entrySet().clear());
    assertEquals(1, groups.version());
  }

  @Test
  void groupsReadAndAbsentRemovePreserveVersion() {
    var groups = new Groups();
    groups.put(1L, List.of());
    assertTrue(groups.containsKey(1L));
    assertEquals(List.of(), groups.get(1L));
    assertNull(groups.remove(2L));
    assertEquals(1, groups.version());
    assertEquals(List.of(), groups.remove(1L));
    assertEquals(2, groups.version());
  }

  @Test
  void excessiveGroupAdmissionRetainsExistingEntries() {
    var groups = new Groups();
    for (long i = 0; i < 255; i++) groups.put(i, List.of());
    assertThrows(IllegalArgumentException.class, () -> groups.put(255L, List.of()));
    assertEquals(255, groups.size());
    assertEquals(255, groups.version());
    groups.put(0L, List.of());
    assertEquals(256, groups.version());
  }

  @Test
  void excessiveEntryRetirementRetainsSuccessfulPrefix() {
    var entries = new ArrayList<RogueModifierDynamicRange46.Entry>();
    for (int i = 0; i < 256; i++) entries.add(active());
    var s = state();
    s.range.listenerGroups.put(scopedKey(7, 4), entries);
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> exitActiveRanges(s, w));
    assertEquals(255, w.exits.size());
    assertNotNull(s.range.listenerGroups);
  }

  @Test
  void missingExplicitLifecycleBindingIsClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new State(null, new RogueModifierDynamicRange46Test.World()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new State(RogueModifierDynamicRange46Test.state(), null));
    assertThrows(IllegalArgumentException.class, () -> retire(null, new World()));
  }
}
