package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RogueModifierCasterChange46Test {
  static final Object A = new Object(),
      B = new Object(),
      C = new Object(),
      CONTEXT = new Object(),
      ABILITY = new Object();
  static final long ONE = 1L << 33;

  static class World implements RogueModifierCasterChange46.World {
    final List<String> order = new ArrayList<>();
    final IdentityHashMap<Object, RogueResonanceVariables46> values = new IdentityHashMap<>();
    Object context = CONTEXT, caster = B, ability;
    RogueModifierCasterChange46.OwnerRegistry registry =
        new RogueModifierCasterChange46.OwnerRegistry(new ArrayList<>());
    int contextReads, casterReads, componentReads;

    World() {
      values.put(A, variables());
      values.put(B, variables());
      values.put(C, variables());
    }

    static RogueResonanceVariables46 variables() {
      return RogueResonanceVariables46.fromSourceDefaults(Map.of(7, ONE), null);
    }

    public Object taskContext(RogueModifierCasterChange46.State s) {
      order.add("context");
      contextReads++;
      return context;
    }

    public Object casterEntity(Object context) {
      order.add("caster");
      casterReads++;
      return caster;
    }

    public Object casterComponent(Object caster) {
      order.add("component");
      componentReads++;
      return caster;
    }

    public RogueResonanceVariables46 casterVariables(Object component) {
      order.add("values");
      return values.get(component);
    }

    public Object contextAbility(Object context) {
      order.add("ability");
      return ability;
    }

    public RogueModifierCasterChange46.OwnerRegistry ownerRegistry(Object ability) {
      order.add("owner");
      return registry;
    }
  }

  static RogueModifierCasterChange46.State state(List<RogueResonanceVariables46.Change> changes) {
    var s =
        new RogueModifierCasterChange46.State(
            RogueModifierReplace46Test.state(),
            (state, key, c) -> {
              assertEquals(RogueModifierCasterChange46.scopedKey(c.key()), key);
              changes.add(c);
            });
    s.caster = A;
    s.listenScopeMask = 16;
    return s;
  }

  static void oldListener(RogueModifierCasterChange46.State s, World w) {
    w.values.get(A).combineObserver(s.listener());
  }

  @Test
  void cacheIsClearedBeforeCurrentContextAndCasterRead() {
    var s = state(new ArrayList<>());
    var w =
        new World() {
          public Object taskContext(RogueModifierCasterChange46.State s) {
            if (contextReads == 0) assertNull(s.caster);
            return super.taskContext(s);
          }
        };
    RogueModifierCasterChange46.changed(s, w);
    assertSame(B, s.caster);
    assertEquals(
        List.of(
            "context",
            "caster",
            "component",
            "values",
            "component",
            "values",
            "context",
            "ability"),
        w.order);
  }

  @Test
  void sameCasterSkipsMembershipButStillReadsCurrentAbility() {
    var s = state(new ArrayList<>());
    var w = new World();
    w.caster = A;
    w.ability = ABILITY;
    oldListener(s, w);
    RogueModifierCasterChange46.changed(s, w);
    assertEquals(0, w.componentReads);
    assertEquals(1, w.values.get(A).attachedObserverCount());
    assertEquals(List.of(s.replacement), w.registry.modifiers);
  }

  @Test
  void disabledListenScopeStillRefreshesCacheWithoutMembership() {
    var s = state(new ArrayList<>());
    s.listenScopeMask = 0;
    var w = new World();
    oldListener(s, w);
    RogueModifierCasterChange46.changed(s, w);
    assertSame(B, s.caster);
    assertEquals(0, w.componentReads);
    assertEquals(1, w.values.get(A).attachedObserverCount());
    assertEquals(0, w.values.get(B).attachedObserverCount());
  }

  @Test
  void differentEqualCasterObjectsUseReferenceIdentity() {
    var s = state(new ArrayList<>());
    s.caster = new String("entity");
    var w = new World();
    w.caster = new String("entity");
    RogueModifierCasterChange46.changed(s, w);
    assertEquals(2, w.componentReads);
    assertSame(w.caster, s.caster);
  }

  @Test
  void actualOldAndNewRootWritesVerifyListenerMigration() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var s = state(changes);
    var w = new World();
    oldListener(s, w);
    RogueModifierCasterChange46.changed(s, w);
    w.values.get(A).write(7, 2 * ONE);
    assertTrue(changes.isEmpty());
    w.values.get(B).write(7, 3 * ONE);
    assertEquals(List.of(new RogueResonanceVariables46.Change(7, ONE, 3 * ONE)), changes);
    assertEquals(0, w.values.get(A).attachedObserverCount());
    assertEquals(1, w.values.get(B).attachedObserverCount());
  }

  @Test
  void repeatedSameCasterDoesNotDuplicateAttachedListener() {
    var s = state(new ArrayList<>());
    var w = new World();
    RogueModifierCasterChange46.changed(s, w);
    RogueModifierCasterChange46.changed(s, w);
    assertEquals(1, w.values.get(B).attachedObserverCount());
  }

  @Test
  void absentPreviousCasterOnlyCombinesNewListener() {
    var s = state(new ArrayList<>());
    s.caster = null;
    var w = new World();
    RogueModifierCasterChange46.changed(s, w);
    assertEquals(1, w.componentReads);
    assertEquals(1, w.values.get(B).attachedObserverCount());
  }

  @Test
  void missingFirstCurrentCasterIsRetriedAfterOldRemoval() {
    var s = state(new ArrayList<>());
    var w =
        new World() {
          public Object casterEntity(Object context) {
            super.casterEntity(context);
            return casterReads == 1 ? null : B;
          }
        };
    oldListener(s, w);
    RogueModifierCasterChange46.changed(s, w);
    assertSame(B, s.caster);
    assertEquals(2, w.casterReads);
    assertEquals(0, w.values.get(A).attachedObserverCount());
    assertEquals(1, w.values.get(B).attachedObserverCount());
  }

  @Test
  void twoAbsentCurrentReadsRemoveOldWithoutCombiningNew() {
    var s = state(new ArrayList<>());
    var w = new World();
    w.caster = null;
    oldListener(s, w);
    RogueModifierCasterChange46.changed(s, w);
    assertNull(s.caster);
    assertEquals(2, w.casterReads);
    assertEquals(0, w.values.get(A).attachedObserverCount());
    assertEquals(1, w.componentReads);
  }

  @Test
  void bothAbsentCastersSkipRetryBecauseIdentityUnchanged() {
    var s = state(new ArrayList<>());
    s.caster = null;
    var w = new World();
    w.caster = null;
    RogueModifierCasterChange46.changed(s, w);
    assertEquals(1, w.casterReads);
    assertEquals(0, w.componentReads);
    assertEquals(2, w.contextReads);
  }

  @Test
  void missingOldVariablesSkipsRemovalAndStillCombinesNew() {
    var s = state(new ArrayList<>());
    var w = new World();
    w.values.remove(A);
    RogueModifierCasterChange46.changed(s, w);
    assertEquals(1, w.values.get(B).attachedObserverCount());
  }

  @Test
  void missingNewVariablesSkipsCombineAfterRemovingOld() {
    var s = state(new ArrayList<>());
    var w = new World();
    oldListener(s, w);
    w.values.remove(B);
    RogueModifierCasterChange46.changed(s, w);
    assertEquals(0, w.values.get(A).attachedObserverCount());
    assertSame(B, s.caster);
  }

  @Test
  void missingPreviousComponentFailsAfterCacheWriteAndBeforeRemoval() {
    var s = state(new ArrayList<>());
    var w =
        new World() {
          public Object casterComponent(Object caster) {
            super.casterComponent(caster);
            return caster == A ? null : caster;
          }
        };
    oldListener(s, w);
    assertThrows(IllegalArgumentException.class, () -> RogueModifierCasterChange46.changed(s, w));
    assertSame(B, s.caster);
    assertEquals(1, w.values.get(A).attachedObserverCount());
    assertFalse(w.order.contains("ability"));
  }

  @Test
  void missingNewComponentFailsAfterOldListenerWasRemoved() {
    var s = state(new ArrayList<>());
    var w =
        new World() {
          public Object casterComponent(Object caster) {
            super.casterComponent(caster);
            return caster == B ? null : caster;
          }
        };
    oldListener(s, w);
    assertThrows(IllegalArgumentException.class, () -> RogueModifierCasterChange46.changed(s, w));
    assertEquals(0, w.values.get(A).attachedObserverCount());
    assertSame(B, s.caster);
    assertFalse(w.order.contains("ability"));
  }

  @Test
  void initialContextFaultLeavesCacheClearedAndOldListenerAttached() {
    var s = state(new ArrayList<>());
    var w = new World();
    oldListener(s, w);
    w.context = null;
    assertThrows(IllegalArgumentException.class, () -> RogueModifierCasterChange46.changed(s, w));
    assertNull(s.caster);
    assertEquals(1, w.values.get(A).attachedObserverCount());
    assertEquals(0, w.casterReads);
  }

  @Test
  void finalContextFaultRetainsCompletedMigration() {
    var s = state(new ArrayList<>());
    var w =
        new World() {
          public Object taskContext(RogueModifierCasterChange46.State s) {
            var value = super.taskContext(s);
            return contextReads == 1 ? value : null;
          }
        };
    oldListener(s, w);
    assertThrows(IllegalArgumentException.class, () -> RogueModifierCasterChange46.changed(s, w));
    assertEquals(0, w.values.get(A).attachedObserverCount());
    assertEquals(1, w.values.get(B).attachedObserverCount());
    assertSame(B, s.caster);
  }

  @Test
  void retryContextFaultRetainsOldRemovalAndNullCache() {
    var s = state(new ArrayList<>());
    var w =
        new World() {
          public Object taskContext(RogueModifierCasterChange46.State s) {
            var value = super.taskContext(s);
            return contextReads == 1 ? value : null;
          }
        };
    w.caster = null;
    oldListener(s, w);
    assertThrows(IllegalArgumentException.class, () -> RogueModifierCasterChange46.changed(s, w));
    assertEquals(0, w.values.get(A).attachedObserverCount());
    assertNull(s.caster);
  }

  @Test
  void listenMaskIsReadAfterInitialProviderChanges() {
    var s = state(new ArrayList<>());
    s.listenScopeMask = 0;
    var w =
        new World() {
          public Object taskContext(RogueModifierCasterChange46.State actual) {
            if (contextReads == 0) actual.listenScopeMask = 16;
            return super.taskContext(actual);
          }
        };
    oldListener(s, w);
    RogueModifierCasterChange46.changed(s, w);
    assertEquals(0, w.values.get(A).attachedObserverCount());
    assertEquals(1, w.values.get(B).attachedObserverCount());
  }

  @Test
  void maskChangeInsideComponentCallbackDoesNotUndoEnteredMigration() {
    var s = state(new ArrayList<>());
    var w =
        new World() {
          public Object casterComponent(Object caster) {
            s.listenScopeMask = 0;
            return super.casterComponent(caster);
          }
        };
    oldListener(s, w);
    RogueModifierCasterChange46.changed(s, w);
    assertEquals(1, w.values.get(B).attachedObserverCount());
    assertEquals(0, s.listenScopeMask);
  }

  @Test
  void changedCacheInsideOldComponentReadControlsNewAttachment() {
    var s = state(new ArrayList<>());
    var w =
        new World() {
          public Object casterComponent(Object caster) {
            if (caster == A) s.caster = C;
            return super.casterComponent(caster);
          }
        };
    oldListener(s, w);
    RogueModifierCasterChange46.changed(s, w);
    assertSame(C, s.caster);
    assertEquals(0, w.values.get(B).attachedObserverCount());
    assertEquals(1, w.values.get(C).attachedObserverCount());
  }

  @Test
  void clearedCacheInsideOldValuesReadTriggersCasterRetry() {
    var s = state(new ArrayList<>());
    var w =
        new World() {
          public RogueResonanceVariables46 casterVariables(Object component) {
            if (component == A) s.caster = null;
            return super.casterVariables(component);
          }
        };
    oldListener(s, w);
    RogueModifierCasterChange46.changed(s, w);
    assertEquals(2, w.casterReads);
    assertSame(B, s.caster);
    assertEquals(1, w.values.get(B).attachedObserverCount());
  }

  @Test
  void projectedOldContainerIsRejectedWithoutCombiningNew() {
    var s = state(new ArrayList<>());
    var w = new World();
    var root = w.values.get(A);
    root.combineObserver(s.listener());
    w.values.put(A, RogueResonanceVariables46.withAiTagProjection(root, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> RogueModifierCasterChange46.changed(s, w));
    assertEquals(1, root.attachedObserverCount());
    assertEquals(0, w.values.get(B).attachedObserverCount());
  }

  @Test
  void noContextAbilitySkipsOwnerLookup() {
    var s = state(new ArrayList<>());
    var w =
        new World() {
          public RogueModifierCasterChange46.OwnerRegistry ownerRegistry(Object ability) {
            fail("no ability");
            return null;
          }
        };
    RogueModifierCasterChange46.changed(s, w);
    assertFalse(w.order.contains("owner"));
  }

  @Test
  void optionalMissingOwnerRegistryDoesNotFabricateList() {
    var s = state(new ArrayList<>());
    var w = new World();
    w.ability = ABILITY;
    w.registry = null;
    RogueModifierCasterChange46.changed(s, w);
    assertNull(w.registry);
    assertEquals("owner", w.order.getLast());
  }

  @Test
  void currentContextIsRereadBeforeAbilityLookup() {
    var s = state(new ArrayList<>());
    Object other = new Object();
    var w =
        new World() {
          public Object taskContext(RogueModifierCasterChange46.State s) {
            super.taskContext(s);
            return contextReads == 1 ? CONTEXT : other;
          }

          public Object contextAbility(Object context) {
            assertSame(other, context);
            return super.contextAbility(context);
          }
        };
    RogueModifierCasterChange46.changed(s, w);
    assertEquals(2, w.contextReads);
  }

  @Test
  void missingOwnerListFailsAfterMigrationAndCacheWrite() {
    var s = state(new ArrayList<>());
    var w = new World();
    w.ability = ABILITY;
    w.registry.modifiers = null;
    oldListener(s, w);
    assertThrows(IllegalArgumentException.class, () -> RogueModifierCasterChange46.changed(s, w));
    assertSame(B, s.caster);
    assertEquals(0, w.values.get(A).attachedObserverCount());
    assertEquals(1, w.values.get(B).attachedObserverCount());
  }

  @Test
  void ownerRegistrationScansBackwardsAndStopsAtLastSameReference() {
    Object modifier = new Object();
    var visited = new ArrayList<Integer>();
    var list =
        new ArrayList<Object>(List.of(new Object(), modifier, modifier, new Object())) {
          public Object get(int index) {
            visited.add(index);
            return super.get(index);
          }
        };
    RogueModifierCasterChange46.registerIdentity(
        new RogueModifierCasterChange46.OwnerRegistry(list), modifier);
    assertEquals(List.of(3, 2), visited);
    assertEquals(4, list.size());
  }

  @Test
  void ownerRegistrationDoesNotCollapseEqualDifferentObjects() {
    Object first = new String("modifier"), second = new String("modifier");
    var list = new ArrayList<Object>(List.of(first));
    RogueModifierCasterChange46.registerIdentity(
        new RogueModifierCasterChange46.OwnerRegistry(list), second);
    assertEquals(2, list.size());
    assertSame(second, list.getLast());
  }

  @Test
  void ownerRegistrationMissingInputsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierCasterChange46.registerIdentity(null, A));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierCasterChange46.registerIdentity(
                new RogueModifierCasterChange46.OwnerRegistry(null), A));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierCasterChange46.registerIdentity(
                new RogueModifierCasterChange46.OwnerRegistry(new ArrayList<>()), null));
  }

  @Test
  void fullOwnerListAllowsExistingIdentityButRejectsAdditional() {
    var entries = new ArrayList<Object>();
    for (int i = 0; i < 255; i++) entries.add(new Object());
    var registry = new RogueModifierCasterChange46.OwnerRegistry(entries);
    RogueModifierCasterChange46.registerIdentity(registry, entries.getLast());
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierCasterChange46.registerIdentity(registry, new Object()));
    assertEquals(255, entries.size());
  }

  @Test
  void capturedOwnerListIsUsedEvenIfGetCallbackReplacesRegistryList() {
    Object modifier = new Object();
    var registry = new RogueModifierCasterChange46.OwnerRegistry(null);
    var original =
        new ArrayList<Object>(List.of(A)) {
          public Object get(int index) {
            registry.modifiers = new ArrayList<>();
            return super.get(index);
          }
        };
    registry.modifiers = original;
    RogueModifierCasterChange46.registerIdentity(registry, modifier);
    assertEquals(List.of(A, modifier), original);
    assertTrue(registry.modifiers.isEmpty());
  }

  @Test
  void scopedKeyPreservesEveryInt32HashBit() {
    for (int hash : new int[] {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      long packed = RogueModifierCasterChange46.scopedKey(hash);
      assertEquals(4, (int) packed);
      assertEquals(hash, (int) (packed >>> 32));
    }
  }

  @Test
  void actualInjectionForwardsAbsentPreviousAndNegativeHash() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var s = state(changes);
    var w = new World();
    RogueModifierCasterChange46.changed(s, w);
    w.values.get(B).injectSourceStandard(Integer.MIN_VALUE, ONE);
    assertEquals(
        List.of(new RogueResonanceVariables46.Change(Integer.MIN_VALUE, 0, ONE, false)), changes);
  }

  @Test
  void missingStateHandlerAndWorldBindingsDoNotFabricateSuccess() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierCasterChange46.State(null, (s, k, c) -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierCasterChange46.State(RogueModifierReplace46Test.state(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierCasterChange46.changed(null, new World()));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierCasterChange46.changed(state(new ArrayList<>()), null));
  }

  @Test
  void actualReplacementAbilityCallbackMigratesSameModifierAndOwnerIdentity() {
    var s = state(new ArrayList<>());
    var w = new World();
    w.ability = ABILITY;
    oldListener(s, w);
    var replaceWorld =
        new RogueModifierReplace46Test.BoundWorld() {
          public void afterAbilityVirtual(RogueModifierReplace46.State actual) {
            super.afterAbilityVirtual(actual);
            assertSame(actual, s.replacement);
            RogueModifierCasterChange46.changed(s, w);
          }
        };
    RogueModifierReplace46.replace(
        s.replacement,
        RogueModifierReplace46Test.ABILITY,
        RogueModifierReplace46Test.parameters(),
        replaceWorld);
    assertSame(B, s.caster);
    assertEquals(List.of(s.replacement), w.registry.modifiers);
    assertEquals(0, w.values.get(A).attachedObserverCount());
    assertEquals(1, w.values.get(B).attachedObserverCount());
  }

  @Test
  void actualDeferredSequenceFlushMigratesAfterPendingAbilityReplaced() {
    var s = state(new ArrayList<>());
    var w = new World();
    oldListener(s, w);
    var sequence = new RogueModifierSequences46.State(s.replacement);
    s.replacement.executingSequenceCount = 1;
    s.replacement.delayReplaceAbility = ABILITY;
    var sequenceWorld = new RogueModifierSequences46Test.World();
    sequenceWorld.onChanged =
        () -> {
          assertSame(ABILITY, sequenceWorld.ability);
          RogueModifierCasterChange46.changed(s, w);
        };
    RogueModifierSequences46.endAndFlush(sequence, sequenceWorld);
    assertNull(s.replacement.delayReplaceAbility);
    assertEquals(0, s.replacement.executingSequenceCount);
    assertEquals(1, w.values.get(B).attachedObserverCount());
    assertEquals(0, w.values.get(A).attachedObserverCount());
  }

  @Test
  void ownerAbilityUsesCurrentValueChangedDuringMigration() {
    var s = state(new ArrayList<>());
    Object nextAbility = new Object();
    var w =
        new World() {
          public RogueResonanceVariables46 casterVariables(Object component) {
            ability = nextAbility;
            return super.casterVariables(component);
          }

          public RogueModifierCasterChange46.OwnerRegistry ownerRegistry(Object ability) {
            assertSame(nextAbility, ability);
            return super.ownerRegistry(ability);
          }
        };
    RogueModifierCasterChange46.changed(s, w);
    assertEquals(List.of(s.replacement), w.registry.modifiers);
  }

  @Test
  void rootListenerCapacityFaultRetainsOldRemovalAndNewCache() {
    var s = state(new ArrayList<>());
    var w = new World();
    oldListener(s, w);
    var other = new RogueResonanceObserver46.Listener(new Object(), new Object(), c -> {});
    for (int i = 0; i < 255; i++) w.values.get(B).combineObserver(other);
    assertThrows(IllegalArgumentException.class, () -> RogueModifierCasterChange46.changed(s, w));
    assertEquals(0, w.values.get(A).attachedObserverCount());
    assertEquals(255, w.values.get(B).attachedObserverCount());
    assertSame(B, s.caster);
  }

  @Test
  void forwardedHandlerFaultLeavesCommittedNewCasterValue() {
    var replacement = RogueModifierReplace46Test.state();
    var s =
        new RogueModifierCasterChange46.State(
            replacement,
            (state, key, c) -> {
              throw new IllegalStateException("handler");
            });
    s.listenScopeMask = 16;
    var w = new World();
    RogueModifierCasterChange46.changed(s, w);
    assertThrows(IllegalStateException.class, () -> w.values.get(B).write(7, 2 * ONE));
    assertEquals(2 * ONE, w.values.get(B).read(7));
    assertSame(B, s.caster);
  }
}
