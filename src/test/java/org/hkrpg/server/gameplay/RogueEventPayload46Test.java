package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEventPayload46Test {
  static final Object NONE = new Object(), OLD = new Object(), NEXT = new Object();
  static final RogueEventPayload46.ValueEntry VALUE =
      new RogueEventPayload46.ValueEntry(7, 8, 9, OLD);

  static RogueEventPayload46.Catalog catalog() {
    return new RogueEventPayload46.Catalog(NONE);
  }

  static RogueEventPayload46.State state() {
    var s = RogueEventPayload46.create(catalog());
    s.ownerEntity = OLD;
    s.entity = OLD;
    s.target = OLD;
    s.arbitraryArray = OLD;
    s.hitEvent = OLD;
    s.attack = OLD;
    s.trigger = OLD;
    s.breakEvent = OLD;
    s.skill = OLD;
    s.attackInfo = OLD;
    s.createEntity = OLD;
    s.component = OLD;
    s.otherEntity = OLD;
    s.eventEnum = OLD;
    s.entities = new RogueOwnedParameterLists46.References(new Object[] {OLD, OLD, NEXT}, 2, 8);
    s.values = new long[] {11, 12, 13, 14};
    s.strings = new String[] {"a", "b", "c", "tail"};
    s.propertyReason = 1;
    s.modifierEvent = 2;
    s.flags = 3;
    s.context = new RogueEventPayload46.Context();
    s.context.values = new RogueEventPayload46.Values();
    s.context.values.buckets = new int[] {4, 5, 6, 7};
    s.context.values.entries = new RogueEventPayload46.ValueEntry[] {VALUE, VALUE, VALUE};
    s.context.values.count = 2;
    s.context.values.version = 9;
    s.context.values.freeList = 4;
    s.context.values.freeCount = 1;
    return s;
  }

  static void assertEarlyReferencesCleared(RogueEventPayload46.State s) {
    assertNull(s.ownerEntity);
    assertNull(s.entity);
    assertNull(s.trigger);
    assertNull(s.component);
    assertNull(s.otherEntity);
  }

  static void assertTailReferencesRetained(RogueEventPayload46.State s) {
    assertSame(OLD, s.target);
    assertSame(OLD, s.skill);
    assertSame(OLD, s.attack);
  }

  @Test
  void constructorCreatesIndependentEmptyEntityStorageAndThreeValueAndStringSlots() {
    var c = catalog();
    var a = RogueEventPayload46.create(c);
    var b = RogueEventPayload46.create(c);
    assertNotSame(a, b);
    assertSame(NONE, a.eventEnum);
    assertEquals(0, a.entities.size);
    assertEquals(0, a.entities.version);
    assertEquals(0, a.entities.entries.length);
    assertArrayEquals(new long[3], a.values);
    assertArrayEquals(new String[3], a.strings);
    assertNotSame(a.entities, b.entities);
    assertNotSame(a.entities.entries, b.entities.entries);
    assertNotSame(a.values, b.values);
    assertNotSame(a.strings, b.strings);
    assertNull(a.ownerEntity);
    assertNull(a.context);
    assertEquals(0, a.flags);
  }

  @Test
  void catalogNoneIsAReferenceAndCanBeNullWithoutInventingASentinel() {
    var c = new RogueEventPayload46.Catalog(null);
    var s = RogueEventPayload46.create(c);
    assertNull(s.eventEnum);
    c.noneEnum = NEXT;
    RogueEventPayload46.clear(s, c);
    assertSame(NEXT, s.eventEnum);
    c.noneEnum = null;
    RogueEventPayload46.clear(s, c);
    assertNull(s.eventEnum);
  }

  @Test
  void clearPreservesContainerIdentitiesAndOnlyClearsEffectiveReferenceAndFixedThreeSlotPrefixes() {
    var s = state();
    var entities = s.entities;
    var entries = entities.entries;
    var numbers = s.values;
    var strings = s.strings;
    var context = s.context;
    var values = context.values;
    var buckets = values.buckets;
    var cells = values.entries;
    RogueEventPayload46.clear(s, catalog());
    assertEarlyReferencesCleared(s);
    assertSame(entities, s.entities);
    assertSame(entries, s.entities.entries);
    assertArrayEquals(new Object[] {null, null, NEXT}, entries);
    assertEquals(0, entities.size);
    assertEquals(9, entities.version);
    assertSame(numbers, s.values);
    assertArrayEquals(new long[] {0, 0, 0, 14}, numbers);
    assertSame(strings, s.strings);
    assertArrayEquals(new String[] {null, null, null, "tail"}, strings);
    assertSame(context, s.context);
    assertSame(values, s.context.values);
    assertSame(buckets, values.buckets);
    assertSame(cells, values.entries);
    assertArrayEquals(new int[4], buckets);
    assertArrayEquals(new RogueEventPayload46.ValueEntry[] {null, null, VALUE}, cells);
    assertEquals(0, values.count);
    assertEquals(10, values.version);
    assertEquals(-1, values.freeList);
    assertEquals(0, values.freeCount);
    assertNull(s.target);
    assertNull(s.skill);
    assertNull(s.attack);
    assertNull(s.hitEvent);
    assertNull(s.arbitraryArray);
    assertNull(s.breakEvent);
    assertNull(s.attackInfo);
    assertNull(s.createEntity);
    assertSame(NONE, s.eventEnum);
    assertEquals(0, s.flags);
    assertEquals(0, s.propertyReason);
    assertEquals(0, s.modifierEvent);
  }

  @Test
  void missingEntityListStopsBeforeFlagsCatalogArrayAndTailClear() {
    var s = state();
    s.entities = null;
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertEarlyReferencesCleared(s);
    assertEquals(3, s.flags);
    assertSame(OLD, s.hitEvent);
    assertSame(OLD, s.eventEnum);
    assertArrayEquals(new long[] {11, 12, 13, 14}, s.values);
    assertTailReferencesRetained(s);
  }

  @Test
  void missingEntityStorageKeepsVersionAndZeroCountBeforeTheFailure() {
    var s = state();
    s.entities.entries = null;
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertEarlyReferencesCleared(s);
    assertEquals(0, s.entities.size);
    assertEquals(9, s.entities.version);
    assertEquals(3, s.flags);
    assertTailReferencesRetained(s);
  }

  @Test
  void retryAfterEntityStorageFailureSkipsNowEmptyStorageAndClearsTheRest() {
    var s = state();
    s.entities.entries = null;
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    RogueEventPayload46.clear(s, catalog());
    assertNull(s.entities.entries);
    assertEquals(10, s.entities.version);
    assertNull(s.attack);
    assertArrayEquals(new long[] {0, 0, 0, 14}, s.values);
    assertSame(NONE, s.eventEnum);
  }

  @ParameterizedTest
  @ValueSource(ints = {-2, -1, 0})
  void nonpositiveEntityCountSkipsStorageAndStillAdvancesVersionAndZeroesSize(int size) {
    var s = state();
    s.entities.size = size;
    s.entities.entries = null;
    RogueEventPayload46.clear(s, catalog());
    assertEquals(0, s.entities.size);
    assertEquals(9, s.entities.version);
    assertNull(s.target);
  }

  @Test
  void entityVersionWrapsBeforeStorageChecks() {
    var s = state();
    s.entities.version = Integer.MAX_VALUE;
    s.entities.entries = null;
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertEquals(Integer.MIN_VALUE, s.entities.version);
    assertEquals(0, s.entities.size);
  }

  @Test
  void missingEntityPrefixKeepsStorageUnmodifiedAfterHeaderClear() {
    var s = state();
    s.entities.size = 4;
    assertThrows(IndexOutOfBoundsException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertEquals(0, s.entities.size);
    assertEquals(9, s.entities.version);
    assertArrayEquals(new Object[] {OLD, OLD, NEXT}, s.entities.entries);
    assertEquals(3, s.flags);
  }

  @Test
  void compiledEntityLimitAppliesAfterTheNativeHeaderPrefix() {
    var s = state();
    s.entities.size = 256;
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertEquals(0, s.entities.size);
    assertEquals(9, s.entities.version);
    assertSame(OLD, s.entities.entries[0]);
    assertEquals(3, s.flags);
  }

  @Test
  void missingCatalogOccursAfterFlagsAndEarlyOptionalReferencesWereCleared() {
    var s = state();
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, null));
    assertEquals(0, s.flags);
    assertNull(s.hitEvent);
    assertNull(s.arbitraryArray);
    assertSame(OLD, s.eventEnum);
    assertSame(OLD, s.breakEvent);
    assertEquals(1, s.propertyReason);
    assertTailReferencesRetained(s);
  }

  @Test
  void missingNumberArrayStopsBeforeStringContextAndTailClear() {
    var s = state();
    s.values = null;
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertSame(NONE, s.eventEnum);
    assertEquals(0, s.propertyReason);
    assertEquals(0, s.modifierEvent);
    assertNull(s.attackInfo);
    assertNull(s.createEntity);
    assertArrayEquals(new String[] {"a", "b", "c", "tail"}, s.strings);
    assertEquals(2, s.context.values.count);
    assertTailReferencesRetained(s);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void shortNumberArraysKeepEachWrittenZeroPrefixAndLeaveLaterStagesUntouched(int length) {
    var s = state();
    s.values = new long[length];
    Arrays.fill(s.values, 71);
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertArrayEquals(new long[length], s.values);
    assertEquals("a", s.strings[0]);
    assertEquals(2, s.context.values.count);
    assertTailReferencesRetained(s);
  }

  @Test
  void missingStringArrayOccursAfterAllThreeNumericWrites() {
    var s = state();
    s.strings = null;
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertArrayEquals(new long[] {0, 0, 0, 14}, s.values);
    assertEquals(2, s.context.values.count);
    assertTailReferencesRetained(s);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void shortStringArraysKeepWrittenNullPrefixAndLeaveContextAndTailUntouched(int length) {
    var s = state();
    s.strings = new String[length];
    Arrays.fill(s.strings, "x");
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertArrayEquals(new String[length], s.strings);
    assertArrayEquals(new long[] {0, 0, 0, 14}, s.values);
    assertEquals(2, s.context.values.count);
    assertTailReferencesRetained(s);
  }

  @Test
  void absentContextSkipsValueDictionaryAndCompletesTailClear() {
    var s = state();
    s.context = null;
    RogueEventPayload46.clear(s, catalog());
    assertNull(s.context);
    assertNull(s.target);
    assertNull(s.skill);
    assertNull(s.attack);
  }

  @Test
  void missingContextDictionaryStopsAfterArrayWritesAndBeforeTailClear() {
    var s = state();
    s.context.values = null;
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertArrayEquals(new long[] {0, 0, 0, 14}, s.values);
    assertArrayEquals(new String[] {null, null, null, "tail"}, s.strings);
    assertTailReferencesRetained(s);
  }

  @Test
  void missingBucketsDoesNotPublishTheDictionaryReset() {
    var s = state();
    s.context.values.buckets = null;
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertEquals(2, s.context.values.count);
    assertEquals(9, s.context.values.version);
    assertEquals(4, s.context.values.freeList);
    assertSame(VALUE, s.context.values.entries[0]);
    assertTailReferencesRetained(s);
  }

  @Test
  void missingValueEntriesKeepsClearedBucketsCountAndFreeVectorWithoutVersionChange() {
    var s = state();
    s.context.values.entries = null;
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertArrayEquals(new int[4], s.context.values.buckets);
    assertEquals(0, s.context.values.count);
    assertEquals(-1, s.context.values.freeList);
    assertEquals(0, s.context.values.freeCount);
    assertEquals(9, s.context.values.version);
    assertTailReferencesRetained(s);
  }

  @Test
  void retryAfterEntryFailureSkipsNowZeroCountAndCompletesTailClear() {
    var s = state();
    s.context.values.entries = null;
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    s.context.values.buckets = null;
    RogueEventPayload46.clear(s, catalog());
    assertEquals(10, s.context.values.version);
    assertNull(s.context.values.entries);
    assertNull(s.attack);
  }

  @Test
  void shortValueEntryStorageKeepsBucketAndHeaderPrefixBeforeBoundsFailure() {
    var s = state();
    s.context.values.entries = new RogueEventPayload46.ValueEntry[] {VALUE};
    assertThrows(IndexOutOfBoundsException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertSame(VALUE, s.context.values.entries[0]);
    assertEquals(0, s.context.values.count);
    assertArrayEquals(new int[4], s.context.values.buckets);
    assertEquals(9, s.context.values.version);
    assertTailReferencesRetained(s);
  }

  @ParameterizedTest
  @ValueSource(ints = {-2, -1, 0})
  void nonpositiveValueCountSkipsStorageWithoutResettingCountOrFreeVector(int count) {
    var s = state();
    s.context.values.count = count;
    s.context.values.buckets = null;
    s.context.values.entries = null;
    RogueEventPayload46.clear(s, catalog());
    assertEquals(count, s.context.values.count);
    assertEquals(10, s.context.values.version);
    assertEquals(4, s.context.values.freeList);
    assertEquals(1, s.context.values.freeCount);
    assertNull(s.attack);
  }

  @Test
  void valueDictionaryVersionWrapsEvenWhenCountIsZero() {
    var s = state();
    s.context.values.count = 0;
    s.context.values.version = Integer.MAX_VALUE;
    RogueEventPayload46.clear(s, catalog());
    assertEquals(Integer.MIN_VALUE, s.context.values.version);
  }

  @Test
  void compiledValueCountLimitKeepsBucketsAndCountResetBeforeFailure() {
    var s = state();
    s.context.values.count = 256;
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertArrayEquals(new int[4], s.context.values.buckets);
    assertEquals(0, s.context.values.count);
    assertEquals(-1, s.context.values.freeList);
    assertEquals(9, s.context.values.version);
    assertTailReferencesRetained(s);
  }

  @Test
  void compiledBucketCapacityLimitStopsBeforeDictionaryHeaderReset() {
    var s = state();
    s.context.values.buckets = new int[4097];
    Arrays.fill(s.context.values.buckets, 7);
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(s, catalog()));
    assertEquals(7, s.context.values.buckets[0]);
    assertEquals(2, s.context.values.count);
    assertEquals(4, s.context.values.freeList);
    assertEquals(9, s.context.values.version);
  }

  @Test
  void currentCatalogReferenceIsReadAtClearWithoutReplacingRetainedStorage() {
    var s = state();
    var c = catalog();
    c.noneEnum = NEXT;
    RogueEventPayload46.clear(s, c);
    assertSame(NEXT, s.eventEnum);
    assertEquals(0, s.entities.size);
    assertEquals(4, s.values.length);
    assertEquals(4, s.strings.length);
  }

  @Test
  void repeatedSuccessfulClearRetainsReusableArraysAndAdvancesBothContainerVersions() {
    var s = state();
    var c = catalog();
    RogueEventPayload46.clear(s, c);
    RogueEventPayload46.clear(s, c);
    assertEquals(10, s.entities.version);
    assertEquals(11, s.context.values.version);
    assertEquals(0, s.entities.size);
    assertEquals(0, s.context.values.count);
    assertEquals(14, s.values[3]);
    assertEquals("tail", s.strings[3]);
  }

  @Test
  void fullPayloadIsTheSameEventInstanceThroughoutActualDepartedTailAndClearOnRelease() {
    var payload = RogueEventPayload46.create(catalog());
    payload.values[0] = 91;
    payload.strings[0] = "x";
    var s = new RogueOwnedParameterLists46.AbilityState();
    s.ownerEntity = OLD;
    List<Integer> events = new ArrayList<>();
    var world =
        new RogueDepartedTransition46.World() {
          public RogueDepartedTransition46.Event acquireEvent() {
            return payload;
          }

          public Object entityWorld(Object entity) {
            assertSame(OLD, entity);
            return NEXT;
          }

          public Object worldBattleInstance(Object gameWorld) {
            return NEXT;
          }

          public Object battleGameMode(Object battle) {
            return NEXT;
          }

          public Object eventProcessor(Object mode) {
            return NEXT;
          }

          public void dispatchEntity(
              Object processor,
              Object entity,
              int type,
              RogueDepartedTransition46.Event event,
              Object source,
              int mask) {
            assertSame(payload, event);
            assertSame(OLD, event.ownerEntity);
            events.add(type);
          }

          public void dispatchGlobal(
              Object processor,
              int type,
              RogueDepartedTransition46.Event event,
              Object source,
              int mask,
              Object extra) {
            assertSame(payload, event);
            assertSame(OLD, source);
            events.add(type);
          }

          public void releaseEvent(RogueDepartedTransition46.Event event) {
            assertSame(payload, event);
            RogueEventPayload46.clear(payload, catalog());
          }
        };
    RogueDepartedTransition46.dispatch(s, false, world);
    assertEquals(List.of(226, 228), events);
    assertNull(payload.ownerEntity);
    assertEquals(0, payload.values[0]);
    assertNull(payload.strings[0]);
    assertSame(NONE, payload.eventEnum);
  }

  @Test
  void explicitPayloadAndCatalogRequiredWithoutDefaultsInstalledOnMissingBindings() {
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.create(null));
    assertThrows(IllegalArgumentException.class, () -> RogueEventPayload46.clear(null, catalog()));
  }
}
