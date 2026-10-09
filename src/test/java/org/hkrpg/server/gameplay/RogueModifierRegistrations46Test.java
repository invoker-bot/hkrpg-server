package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueModifierRegistrations46Test {
  static final Object OWNER = new Object(),
      NEXT_OWNER = new Object(),
      VALUE = new Object(),
      DEFAULT = new Object();

  static RogueModifierRegistrations46.State state() {
    var c = RogueModifierCleanup46Test.state();
    c.ownerAbilityComponent = OWNER;
    return new RogueModifierRegistrations46.State(c);
  }

  static RogueModifierRegistrations46.Strings strings() {
    var s = new RogueModifierRegistrations46.Strings();
    s.slots =
        new RogueModifierRegistrations46.Slot[] {
          new RogueModifierRegistrations46.Slot(1, 0, "a"),
          new RogueModifierRegistrations46.Slot(-1, 0, "gone"),
          new RogueModifierRegistrations46.Slot(0, 0, "b"),
          new RogueModifierRegistrations46.Slot(1, 0, "tail")
        };
    s.buckets = new int[] {1, 2, 3, 4};
    s.bucketLength = 3;
    s.count = 2;
    s.lastIndex = 3;
    s.freeList = 1;
    s.version = 9;
    return s;
  }

  static void set(
      RogueModifierRegistrations46.State s,
      boolean binding,
      RogueModifierRegistrations46.Strings values) {
    if (binding) s.bindKeys = values;
    else s.names = values;
  }

  static void pop(RogueModifierRegistrations46.State s, boolean binding, World w) {
    if (binding) RogueModifierRegistrations46.popBindings(s, w);
    else RogueModifierRegistrations46.popNames(s, w);
  }

  static Runnable fail() {
    return () -> {
      throw new IllegalStateException("callback");
    };
  }

  static class World implements RogueModifierRegistrations46.World {
    final List<String> calls = new ArrayList<>();
    final List<Object> owners = new ArrayList<>();
    final Map<String, Object> names = new HashMap<>(Map.of("a", VALUE, "b", VALUE));
    final Map<String, Object> binds = new HashMap<>(Map.of("a", VALUE, "b", VALUE));
    Runnable onName = () -> {},
        onBind = () -> {},
        onLookup = () -> {},
        onRecycle = () -> {},
        onRemove = () -> {},
        onSort = () -> {},
        onAiLookup = () -> {},
        onUpperMap = () -> {},
        onUpper = () -> {};
    Object nameMap = names,
        bindMap = binds,
        entity = OWNER,
        gameWorld = OWNER,
        battle = OWNER,
        mode = OWNER,
        knowledge = OWNER,
        upper = OWNER;
    Object recycled;
    RogueModifierRegistrations46.Ai ai = new RogueModifierRegistrations46.Ai();
    final List<Integer> ids = new ArrayList<>();

    @Override
    public Object nameMapping(Object owner) {
      calls.add("nameMap");
      owners.add(owner);
      onName.run();
      return nameMap;
    }

    @Override
    public void removeName(Object mapping, String key) {
      calls.add("name:" + key);
      names.remove(key);
      onRemove.run();
    }

    @Override
    public Object bindMapping(Object owner) {
      calls.add("bindMap");
      owners.add(owner);
      onBind.run();
      return bindMap;
    }

    @Override
    public RogueModifierRegistrations46.Lookup lookupBinding(Object mapping, String key) {
      calls.add("lookup:" + key);
      onLookup.run();
      return new RogueModifierRegistrations46.Lookup(binds.containsKey(key), binds.get(key));
    }

    @Override
    public void recycleBinding(Object value) {
      calls.add("recycle");
      recycled = value;
      onRecycle.run();
    }

    @Override
    public void removeBinding(Object mapping, String key) {
      calls.add("remove:" + key);
      binds.remove(key);
      onRemove.run();
    }

    @Override
    public Object ownerEntity(Object owner) {
      calls.add("entity");
      return entity;
    }

    @Override
    public RogueModifierRegistrations46.Ai aiComponent(Object entity) {
      calls.add("ai");
      onAiLookup.run();
      return ai;
    }

    @Override
    public void sortAi(RogueOwnedParameterLists46.References list) {
      calls.add("sort");
      onSort.run();
    }

    @Override
    public Object defaultBehavior() {
      calls.add("default");
      return DEFAULT;
    }

    @Override
    public Object entityWorld(Object entity) {
      calls.add("world");
      return gameWorld;
    }

    @Override
    public Object worldBattleInstance(Object world) {
      calls.add("battle");
      return battle;
    }

    @Override
    public Object battleGameMode(Object battle) {
      calls.add("mode");
      return mode;
    }

    @Override
    public Object publicKnowledge(Object mode) {
      calls.add("knowledge");
      return knowledge;
    }

    @Override
    public Object upperMap(Object knowledge) {
      calls.add("upperMap");
      onUpperMap.run();
      return upper;
    }

    @Override
    public void removeUpper(Object map, int id) {
      calls.add("upper");
      ids.add(id);
      onUpper.run();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingSetSkipsWorldAndOwner(boolean binding) {
    var s = state();
    s.cleanup.ownerAbilityComponent = null;
    pop(s, binding, null);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void nonpositiveCountRetainsSetAndVersion(int count) {
    var s = state();
    s.names = strings();
    s.names.count = count;
    RogueModifierRegistrations46.popNames(s, null);
    assertEquals(9, s.names.version);
    assertEquals(3, s.names.lastIndex);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingOwnerSkipsClearAndCallbacks(boolean binding) {
    var s = state();
    var set = strings();
    set(s, binding, set);
    s.cleanup.ownerAbilityComponent = null;
    pop(s, binding, null);
    assertEquals(9, set.version);
    assertEquals(2, set.count);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aliveStringSlotsProcessedInStorageOrderThenCurrentSetCleared(boolean binding) {
    var s = state();
    var values = strings();
    set(s, binding, values);
    var w = new World();
    pop(s, binding, w);
    assertEquals(
        binding
            ? List.of(
                "bindMap",
                "lookup:a",
                "recycle",
                "remove:a",
                "bindMap",
                "lookup:b",
                "recycle",
                "remove:b")
            : List.of("nameMap", "name:a", "nameMap", "name:b"),
        w.calls);
    assertEquals(0, values.count);
    assertEquals(0, values.lastIndex);
    assertEquals(-1, values.freeList);
    assertEquals(10, values.version);
    assertNull(values.slots[0]);
    assertNull(values.slots[1]);
    assertNull(values.slots[2]);
    assertEquals("tail", values.slots[3].key());
    assertArrayEquals(new int[] {0, 0, 0, 4}, values.buckets);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void callbackVersionChangeDetectedBeforeNextMoveIncludingLast(boolean binding) {
    var s = state();
    var values = strings();
    values.lastIndex = 1;
    set(s, binding, values);
    var w = new World();
    w.onRemove = () -> values.version++;
    assertThrows(IllegalArgumentException.class, () -> pop(s, binding, w));
    assertEquals(10, values.version);
    assertEquals(2, values.count);
    assertFalse((binding ? w.binds : w.names).containsKey("a"));
    assertNotNull(values.slots[0]);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void replacementSetOnlyReceivesFinalClear(boolean binding) {
    var s = state();
    var old = strings();
    var next = strings();
    set(s, binding, old);
    var w = new World();
    w.onRemove = () -> set(s, binding, next);
    pop(s, binding, w);
    assertEquals(9, old.version);
    assertEquals(2, old.count);
    assertEquals(10, next.version);
    assertEquals(0, next.count);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ownerRereadBetweenKeys(boolean binding) {
    var s = state();
    set(s, binding, strings());
    var w = new World();
    w.onRemove = () -> s.cleanup.ownerAbilityComponent = NEXT_OWNER;
    pop(s, binding, w);
    assertEquals(2, w.owners.size());
    assertSame(OWNER, w.owners.get(0));
    assertSame(NEXT_OWNER, w.owners.get(1));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nullCurrentOwnerFailsAfterFirstRemovalWithoutClear(boolean binding) {
    var s = state();
    var values = strings();
    set(s, binding, values);
    var w = new World();
    w.onRemove = () -> s.cleanup.ownerAbilityComponent = null;
    assertThrows(IllegalArgumentException.class, () -> pop(s, binding, w));
    assertEquals(2, values.count);
    assertEquals(9, values.version);
    assertFalse((binding ? w.binds : w.names).containsKey("a"));
    assertTrue((binding ? w.binds : w.names).containsKey("b"));
  }

  @Test
  void absentNameMapFailsBeforeKeyRemoval() {
    var s = state();
    s.names = strings();
    var w = new World();
    w.nameMap = null;
    assertThrows(IllegalArgumentException.class, () -> RogueModifierRegistrations46.popNames(s, w));
    assertEquals(List.of("nameMap"), w.calls);
    assertEquals(9, s.names.version);
  }

  @Test
  void absentBindMapSkipsValuesThenClearsKeys() {
    var s = state();
    s.bindKeys = strings();
    var w = new World();
    w.bindMap = null;
    RogueModifierRegistrations46.popBindings(s, w);
    assertEquals(List.of("bindMap", "bindMap"), w.calls);
    assertEquals(0, s.bindKeys.count);
    assertEquals(10, s.bindKeys.version);
    assertEquals(2, w.binds.size());
  }

  @Test
  void nullBindMapDoesNotRefreshCapturedVersion() {
    var s = state();
    s.bindKeys = strings();
    var w = new World();
    w.bindMap = null;
    w.onBind = () -> s.bindKeys.version++;
    RogueModifierRegistrations46.popBindings(s, w);
    assertEquals(12, s.bindKeys.version);
    assertEquals(0, s.bindKeys.count);
  }

  @Test
  void absentBindKeyDoesNotRecycleButRemovalStillCalled() {
    var s = state();
    s.bindKeys = strings();
    var w = new World();
    w.binds.clear();
    RogueModifierRegistrations46.popBindings(s, w);
    assertEquals(
        List.of("bindMap", "lookup:a", "remove:a", "bindMap", "lookup:b", "remove:b"), w.calls);
    assertEquals(10, s.bindKeys.version);
  }

  @Test
  void recycleFailureKeepsMappingAndKeySet() {
    var s = state();
    s.bindKeys = strings();
    var w = new World();
    w.onRecycle = fail();
    assertThrows(IllegalStateException.class, () -> RogueModifierRegistrations46.popBindings(s, w));
    assertEquals(List.of("bindMap", "lookup:a", "recycle"), w.calls);
    assertTrue(w.binds.containsKey("a"));
    assertEquals(9, s.bindKeys.version);
  }

  @Test
  void nullLookupValueStillPassedToRecycleBeforeRemoval() {
    var s = state();
    s.bindKeys = strings();
    s.bindKeys.lastIndex = 1;
    var w = new World();
    w.binds.put("a", null);
    w.onRecycle =
        () -> {
          assertNull(w.recycled);
          throw new IllegalArgumentException("null reference");
        };
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierRegistrations46.popBindings(s, w));
    assertTrue(w.binds.containsKey("a"));
    assertEquals(List.of("bindMap", "lookup:a", "recycle"), w.calls);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void removingCurrentSetFailsOnlyAtFinalClear(boolean binding) {
    var s = state();
    set(s, binding, strings());
    var w = new World();
    w.onRemove = () -> set(s, binding, null);
    assertThrows(IllegalArgumentException.class, () -> pop(s, binding, w));
    assertTrue((binding ? w.binds : w.names).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void defaultZeroSlotHasNullKeyAndFailsAfterMappingLookup(boolean binding) {
    var s = state();
    var values = strings();
    values.slots[0] = null;
    set(s, binding, values);
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> pop(s, binding, w));
    assertEquals(binding ? List.of("bindMap") : List.of("nameMap"), w.calls);
    assertEquals(9, values.version);
  }

  @Test
  void allDeletedSlotsSkipWorldButFinalClearStillRuns() {
    var s = state();
    s.names = strings();
    s.names.slots[0] = new RogueModifierRegistrations46.Slot(-1, 0, null);
    s.names.slots[2] = new RogueModifierRegistrations46.Slot(-1, 0, null);
    RogueModifierRegistrations46.popNames(s, null);
    assertEquals(0, s.names.count);
    assertEquals(10, s.names.version);
  }

  @Test
  void missingSlotsFailsBeforeWorld() {
    var s = state();
    s.names = strings();
    s.names.slots = null;
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> RogueModifierRegistrations46.popNames(s, w));
    assertTrue(w.calls.isEmpty());
    assertEquals(9, s.names.version);
  }

  @Test
  void shortSlotsKeepsCompletedRemovalBeforeFailure() {
    var s = state();
    s.names = strings();
    s.names.slots = Arrays.copyOf(s.names.slots, 1);
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> RogueModifierRegistrations46.popNames(s, w));
    assertEquals(List.of("nameMap", "name:a"), w.calls);
    assertEquals(9, s.names.version);
  }

  @Test
  void missingBucketsFailsAfterAllSlotClearsBeforeHeaderOrVersion() {
    var values = strings();
    values.buckets = null;
    assertThrows(IllegalArgumentException.class, () -> RogueModifierRegistrations46.clear(values));
    assertNull(values.slots[0]);
    assertNull(values.slots[2]);
    assertEquals(2, values.count);
    assertEquals(3, values.lastIndex);
    assertEquals(9, values.version);
  }

  @Test
  void badBucketPrefixLeavesSlotsClearedWithoutReset() {
    var values = strings();
    values.bucketLength = 5;
    assertThrows(IndexOutOfBoundsException.class, () -> RogueModifierRegistrations46.clear(values));
    assertNull(values.slots[0]);
    assertEquals(9, values.version);
    assertEquals(2, values.count);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void nonpositiveLastIndexSkipsAllStorageAndHeaderReset(int last) {
    var values = strings();
    values.lastIndex = last;
    values.slots = null;
    values.buckets = null;
    RogueModifierRegistrations46.clear(values);
    assertEquals(2, values.count);
    assertEquals(1, values.freeList);
    assertEquals(last, values.lastIndex);
    assertEquals(10, values.version);
  }

  @Test
  void clearVersionWrapsOnSuccess() {
    var values = strings();
    values.version = Integer.MAX_VALUE;
    RogueModifierRegistrations46.clear(values);
    assertEquals(Integer.MIN_VALUE, values.version);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 4097, Integer.MAX_VALUE})
  void bucketLimitFailsAfterSlotPrefixCleared(int bucketLength) {
    var values = strings();
    values.bucketLength = bucketLength;
    assertThrows(IllegalArgumentException.class, () -> RogueModifierRegistrations46.clear(values));
    assertNull(values.slots[0]);
    assertEquals(9, values.version);
  }

  @Test
  void stringEnumerationLimitFailsBeforeMappingEffects() {
    var s = state();
    s.names = strings();
    s.names.lastIndex = 256;
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> RogueModifierRegistrations46.popNames(s, w));
    assertTrue(w.calls.isEmpty());
    assertEquals(9, s.names.version);
  }

  @Test
  void absentAiCacheSkipsWorldAndOwner() {
    var s = state();
    s.cleanup.ownerAbilityComponent = null;
    RogueModifierRegistrations46.popAi(s, null);
  }

  @Test
  void absentAiOwnerClearsCacheWithoutWorld() {
    var s = state();
    s.crazyBehavior = VALUE;
    s.cleanup.ownerAbilityComponent = null;
    RogueModifierRegistrations46.popAi(s, null);
    assertNull(s.crazyBehavior);
  }

  @Test
  void absentOwnerEntityClearsCache() {
    var s = state();
    s.crazyBehavior = VALUE;
    var w = new World();
    w.entity = null;
    RogueModifierRegistrations46.popAi(s, w);
    assertNull(s.crazyBehavior);
    assertEquals(List.of("entity"), w.calls);
  }

  @Test
  void absentAiComponentClearsCache() {
    var s = state();
    s.crazyBehavior = VALUE;
    var w = new World();
    w.ai = null;
    RogueModifierRegistrations46.popAi(s, w);
    assertNull(s.crazyBehavior);
    assertEquals(List.of("entity", "ai"), w.calls);
  }

  @Test
  void removalUsesCurrentCacheAfterComponentLookup() {
    var s = state();
    s.crazyBehavior = VALUE;
    var other = new Object();
    var w = new World();
    w.ai.behaviors = new RogueOwnedParameterLists46.References(new Object[] {VALUE, other}, 2, 0);
    w.onAiLookup = () -> s.crazyBehavior = other;
    RogueModifierRegistrations46.popAi(s, w);
    assertSame(VALUE, w.ai.current);
    assertEquals(1, w.ai.behaviors.size);
    assertEquals(1, w.ai.behaviors.version);
    assertNull(s.crazyBehavior);
  }

  @Test
  void identityRemovalSelectsRemainingFirstAndRetainsTailClear() {
    var s = state();
    s.crazyBehavior = VALUE;
    var next = new Object();
    var w = new World();
    w.ai.behaviors = new RogueOwnedParameterLists46.References(new Object[] {VALUE, next}, 2, 8);
    RogueModifierRegistrations46.popAi(s, w);
    assertSame(next, w.ai.current);
    assertNull(w.ai.behaviors.entries[1]);
    assertEquals(9, w.ai.behaviors.version);
    assertNull(s.crazyBehavior);
    assertEquals(List.of("entity", "ai"), w.calls);
  }

  @Test
  void removingLastAiEntryUsesExplicitSharedDefault() {
    var s = state();
    s.crazyBehavior = VALUE;
    var w = new World();
    w.ai.behaviors = new RogueOwnedParameterLists46.References(new Object[] {VALUE}, 1, 0);
    RogueModifierRegistrations46.popAi(s, w);
    assertSame(DEFAULT, w.ai.current);
    assertEquals(List.of("entity", "ai", "default"), w.calls);
    assertNull(s.crazyBehavior);
  }

  @Test
  void missingAiListAlsoUsesDefault() {
    var s = state();
    s.crazyBehavior = VALUE;
    var w = new World();
    RogueModifierRegistrations46.popAi(s, w);
    assertSame(DEFAULT, w.ai.current);
    assertNull(s.crazyBehavior);
  }

  @Test
  void removalStorageFailureKeepsCacheAndCurrentSelection() {
    var s = state();
    s.crazyBehavior = VALUE;
    var w = new World();
    w.ai.current = NEXT_OWNER;
    w.ai.behaviors = new RogueOwnedParameterLists46.References(null, 1, 0);
    assertThrows(IllegalArgumentException.class, () -> RogueModifierRegistrations46.popAi(s, w));
    assertSame(VALUE, s.crazyBehavior);
    assertSame(NEXT_OWNER, w.ai.current);
  }

  @Test
  void sortFailureKeepsRemovedPrefixButDoesNotClearCacheOrPublishSelection() {
    var s = state();
    s.crazyBehavior = VALUE;
    var w = new World();
    w.ai.current = NEXT_OWNER;
    w.ai.behaviors =
        new RogueOwnedParameterLists46.References(new Object[] {VALUE, OWNER, NEXT_OWNER}, 3, 0);
    w.onSort = fail();
    assertThrows(IllegalStateException.class, () -> RogueModifierRegistrations46.popAi(s, w));
    assertEquals(2, w.ai.behaviors.size);
    assertNull(w.ai.behaviors.entries[2]);
    assertSame(VALUE, s.crazyBehavior);
    assertSame(NEXT_OWNER, w.ai.current);
  }

  @Test
  void sortReplacementReadForFinalSelectionAndNewCacheCleared() {
    var s = state();
    s.crazyBehavior = VALUE;
    var w = new World();
    w.ai.behaviors =
        new RogueOwnedParameterLists46.References(new Object[] {VALUE, OWNER, NEXT_OWNER}, 3, 0);
    var old = w.ai.behaviors;
    w.onSort =
        () -> {
          w.ai.behaviors = new RogueOwnedParameterLists46.References(new Object[] {DEFAULT}, 1, 9);
          s.crazyBehavior = NEXT_OWNER;
        };
    RogueModifierRegistrations46.popAi(s, w);
    assertSame(DEFAULT, w.ai.current);
    assertEquals(2, old.size);
    assertNull(s.crazyBehavior);
  }

  @Test
  void sortEmptyReplacementFailsBeforeCurrentPublicationAndCacheClear() {
    var s = state();
    s.crazyBehavior = VALUE;
    var w = new World();
    w.ai.behaviors =
        new RogueOwnedParameterLists46.References(new Object[] {VALUE, OWNER, NEXT_OWNER}, 3, 0);
    w.onSort =
        () -> w.ai.behaviors = new RogueOwnedParameterLists46.References(new Object[0], 0, 0);
    assertThrows(IllegalArgumentException.class, () -> RogueModifierRegistrations46.popAi(s, w));
    assertSame(VALUE, s.crazyBehavior);
    assertNull(w.ai.current);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, -1, Integer.MIN_VALUE})
  void belowTwoNonzeroAiCountReadsFirstWithoutSort(int count) {
    var ai = new RogueModifierRegistrations46.Ai();
    ai.behaviors = new RogueOwnedParameterLists46.References(new Object[] {VALUE}, count, 0);
    assertSame(VALUE, RogueModifierRegistrations46.selectAi(ai, null));
  }

  @Test
  void firstNullAiSlotAllowed() {
    var ai = new RogueModifierRegistrations46.Ai();
    ai.behaviors = new RogueOwnedParameterLists46.References(new Object[] {null}, 1, 0);
    assertNull(RogueModifierRegistrations46.selectAi(ai, null));
  }

  @Test
  void firstAiSlotMissingFails() {
    var ai = new RogueModifierRegistrations46.Ai();
    ai.behaviors = new RogueOwnedParameterLists46.References(new Object[0], 1, 0);
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierRegistrations46.selectAi(ai, null));
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE})
  void signedPriorityComparisonHandlesExtremesAndEquality(int value) {
    var a = new RogueModifierRegistrations46.Behavior(value);
    var b = new RogueModifierRegistrations46.Behavior(value);
    assertEquals(0, RogueModifierRegistrations46.compareBehavior(a, b));
    assertEquals(
        Integer.compare(value, 0),
        RogueModifierRegistrations46.compareBehavior(
            a, new RogueModifierRegistrations46.Behavior(0)));
  }

  @Test
  void missingOtherBehaviorFailsInsteadOfInventingDefaultPriority() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierRegistrations46.compareBehavior(
                new RogueModifierRegistrations46.Behavior(1), null));
  }

  static RogueModifierRegistrations46.UpperIds upperIds() {
    var h = new RogueModifierRegistrations46.UpperIds();
    h.data = new RogueModifierRegistrations46.UpperData();
    h.data.values = new int[] {1, -1, 3, 4};
    h.data.size = 3;
    h.data.version = 7;
    return h;
  }

  @Test
  void absentUpperHolderSkipsWorld() {
    RogueModifierRegistrations46.popUpperGroups(state(), null);
  }

  @Test
  void zeroUpperCountSkipsOwnerWorldAndRetainsStorage() {
    var s = state();
    s.upperGroups = upperIds();
    s.upperGroups.data.size = 0;
    s.cleanup.ownerAbilityComponent = null;
    RogueModifierRegistrations46.popUpperGroups(s, null);
    assertEquals(7, s.upperGroups.data.version);
  }

  @Test
  void missingUpperDataFailsBeforeOwnerCheck() {
    var s = state();
    s.upperGroups = new RogueModifierRegistrations46.UpperIds();
    s.cleanup.ownerAbilityComponent = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierRegistrations46.popUpperGroups(s, null));
  }

  @Test
  void missingUpperOwnerRetainsList() {
    var s = state();
    s.upperGroups = upperIds();
    s.cleanup.ownerAbilityComponent = null;
    RogueModifierRegistrations46.popUpperGroups(s, null);
    assertEquals(3, s.upperGroups.data.size);
  }

  @Test
  void upperIdsRemoveInOrderWithoutClearingListOrUnsignedBits() {
    var s = state();
    s.upperGroups = upperIds();
    var w = new World();
    RogueModifierRegistrations46.popUpperGroups(s, w);
    assertEquals(List.of(1, -1, 3), w.ids);
    assertEquals(3, s.upperGroups.data.size);
    assertEquals(7, s.upperGroups.data.version);
    assertArrayEquals(new int[] {1, -1, 3, 4}, s.upperGroups.data.values);
  }

  @Test
  void missingEntityWorldSkipsRemovalAndRetainsIds() {
    var s = state();
    s.upperGroups = upperIds();
    var w = new World();
    w.gameWorld = null;
    RogueModifierRegistrations46.popUpperGroups(s, w);
    assertTrue(w.ids.isEmpty());
    assertEquals(List.of("entity", "world"), w.calls);
  }

  @Test
  void missingUpperMapSkipsEnumerationStorage() {
    var s = state();
    s.upperGroups = upperIds();
    s.upperGroups.data.values = null;
    var w = new World();
    w.upper = null;
    RogueModifierRegistrations46.popUpperGroups(s, w);
    assertTrue(w.ids.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void requiredWorldChainNullFailsBeforeRemoval(int step) {
    var s = state();
    s.upperGroups = upperIds();
    var w = new World();
    if (step == 0) w.entity = null;
    else if (step == 1) w.battle = null;
    else if (step == 2) w.mode = null;
    else w.knowledge = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierRegistrations46.popUpperGroups(s, w));
    assertTrue(w.ids.isEmpty());
    assertEquals(3, s.upperGroups.data.size);
  }

  @Test
  void replacementUpperHolderBeforeEnumerationIsReadCurrent() {
    var s = state();
    s.upperGroups = upperIds();
    var old = s.upperGroups;
    var next = upperIds();
    next.data.values = new int[] {9};
    next.data.size = 1;
    var w = new World();
    w.onUpperMap = () -> s.upperGroups = next;
    RogueModifierRegistrations46.popUpperGroups(s, w);
    assertEquals(List.of(9), w.ids);
    assertEquals(3, old.data.size);
    assertEquals(1, next.data.size);
  }

  @Test
  void replacementUpperHolderDuringEnumerationDoesNotSwitchCapturedData() {
    var s = state();
    s.upperGroups = upperIds();
    var old = s.upperGroups;
    var w = new World();
    w.onUpper = () -> s.upperGroups = upperIds();
    RogueModifierRegistrations46.popUpperGroups(s, w);
    assertEquals(List.of(1, -1, 3), w.ids);
    assertEquals(3, old.data.size);
  }

  @Test
  void upperVersionChangeAfterRemovalFailsBeforeNextMoveIncludingLast() {
    var s = state();
    s.upperGroups = upperIds();
    s.upperGroups.data.size = 1;
    var w = new World();
    w.onUpper = () -> s.upperGroups.data.version++;
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierRegistrations46.popUpperGroups(s, w));
    assertEquals(List.of(1), w.ids);
    assertEquals(1, s.upperGroups.data.size);
    assertEquals(8, s.upperGroups.data.version);
  }

  @Test
  void upperSameVersionShrinkTerminatesCurrentEnumeration() {
    var s = state();
    s.upperGroups = upperIds();
    var w = new World();
    w.onUpper = () -> s.upperGroups.data.size = 1;
    RogueModifierRegistrations46.popUpperGroups(s, w);
    assertEquals(List.of(1), w.ids);
  }

  @Test
  void upperCurrentArrayReadAfterEachCallback() {
    var s = state();
    s.upperGroups = upperIds();
    var w = new World();
    w.onUpper = () -> s.upperGroups.data.values = new int[] {9, 8, 7};
    RogueModifierRegistrations46.popUpperGroups(s, w);
    assertEquals(List.of(1, 8, 7), w.ids);
  }

  @Test
  void upperWideRawSlotsReadLowUnsignedWordWithCapturedRepresentation() {
    var s = state();
    s.upperGroups = upperIds();
    s.upperGroups.data.wideStorage = true;
    s.upperGroups.data.wideValues =
        new long[] {0xabcdef0000000001L, 0x12345678ffffffffL, 0x2222222200000003L};
    var w = new World();
    w.onUpper = () -> s.upperGroups.data.wideStorage = false;
    RogueModifierRegistrations46.popUpperGroups(s, w);
    assertEquals(List.of(1, -1, 3), w.ids);
  }

  @Test
  void missingUpperArrayKeepsIdsAndVersion() {
    var s = state();
    s.upperGroups = upperIds();
    s.upperGroups.data.values = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierRegistrations46.popUpperGroups(s, new World()));
    assertEquals(3, s.upperGroups.data.size);
    assertEquals(7, s.upperGroups.data.version);
  }

  @Test
  void upperShortArrayFailsAfterEarlierRemovals() {
    var s = state();
    s.upperGroups = upperIds();
    s.upperGroups.data.values = new int[] {1};
    var w = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierRegistrations46.popUpperGroups(s, w));
    assertEquals(List.of(1), w.ids);
    assertEquals(3, s.upperGroups.data.size);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 256, Integer.MAX_VALUE})
  void finiteUpperCountLimitRejectsBeforeFirstRemoval(int count) {
    var s = state();
    s.upperGroups = upperIds();
    s.upperGroups.data.size = count;
    var w = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierRegistrations46.popUpperGroups(s, w));
    assertTrue(w.ids.isEmpty());
    assertEquals(count, s.upperGroups.data.size);
  }

  @Test
  void upperRemovalFailureDoesNotClearOrTruncateSourceIds() {
    var s = state();
    s.upperGroups = upperIds();
    var w = new World();
    w.onUpper = fail();
    assertThrows(
        IllegalStateException.class, () -> RogueModifierRegistrations46.popUpperGroups(s, w));
    assertEquals(List.of(1), w.ids);
    assertEquals(3, s.upperGroups.data.size);
    assertEquals(7, s.upperGroups.data.version);
  }

  @Test
  void secondUpperCallAttemptsSameIdsAgain() {
    var s = state();
    s.upperGroups = upperIds();
    var w = new World();
    RogueModifierRegistrations46.popUpperGroups(s, w);
    RogueModifierRegistrations46.popUpperGroups(s, w);
    assertEquals(List.of(1, -1, 3, 1, -1, 3), w.ids);
    assertEquals(3, s.upperGroups.data.size);
  }

  @Test
  void bindingReleaseUsesActualReferencePoolBeforeRemovingMappingAndClearingKeys() {
    var s = state();
    s.bindKeys = strings();
    s.bindKeys.lastIndex = 1;
    var registry = RogueReferencePool46Test.pool();
    var declared =
        new RogueReferencePool46.Type(
            Object.class, null, false, new RogueReferencePool46.Method[0]);
    var stored = RogueReferencePool46.createCollection(declared);
    var nativePool =
        new RogueReferencePool46Test.World() {
          @Override
          public void clear(Object value) {
            assertSame(VALUE, value);
          }
        };
    var w =
        new World() {
          @Override
          public void recycleBinding(Object value) {
            assertTrue(binds.containsKey("a"));
            assertEquals(2, s.bindKeys.count);
            RogueReferencePool46.releaseTyped(registry, stored, declared, value, nativePool);
            assertSame(value, stored.references[0]);
            assertEquals(1, stored.count);
          }
        };
    RogueModifierRegistrations46.popBindings(s, w);
    assertSame(VALUE, stored.references[0]);
    assertFalse(w.binds.containsKey("a"));
    assertEquals(0, s.bindKeys.count);
  }

  @Test
  void actualPoolClearFailureKeepsBindingMappingAndKeySet() {
    var s = state();
    s.bindKeys = strings();
    s.bindKeys.lastIndex = 1;
    var registry = RogueReferencePool46Test.pool();
    var declared =
        new RogueReferencePool46.Type(
            Object.class, null, false, new RogueReferencePool46.Method[0]);
    var stored = RogueReferencePool46.createCollection(declared);
    var nativePool =
        new RogueReferencePool46Test.World() {
          @Override
          public void clear(Object value) {
            throw new IllegalStateException("opaque binding clear");
          }
        };
    var w =
        new World() {
          @Override
          public void recycleBinding(Object value) {
            RogueReferencePool46.releaseTyped(registry, stored, declared, value, nativePool);
          }
        };
    assertThrows(IllegalStateException.class, () -> RogueModifierRegistrations46.popBindings(s, w));
    assertTrue(w.binds.containsKey("a"));
    assertEquals(0, stored.count);
    assertEquals(2, s.bindKeys.count);
    assertEquals(9, s.bindKeys.version);
  }

  @Test
  void nullBindingValueRejectedByActualPoolBeforeMappingRemoval() {
    var s = state();
    s.bindKeys = strings();
    s.bindKeys.lastIndex = 1;
    var registry = RogueReferencePool46Test.pool();
    var w =
        new World() {
          @Override
          public void recycleBinding(Object value) {
            RogueReferencePool46.releaseTyped(registry, null, null, value, null);
          }
        };
    w.binds.put("a", null);
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierRegistrations46.popBindings(s, w));
    assertTrue(w.binds.containsKey("a"));
    assertNull(w.binds.get("a"));
    assertEquals(9, s.bindKeys.version);
  }
}
