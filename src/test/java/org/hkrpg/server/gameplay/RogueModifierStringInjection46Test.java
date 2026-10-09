package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RogueModifierStringInjection46Test {
  static RogueModifierStringInjection46.StringInjection item(int key, String value) {
    return new RogueModifierStringInjection46.StringInjection(key, value);
  }

  static RogueModifierStringInjection46.Injections list(
      RogueModifierStringInjection46.StringInjection... entries) {
    return new RogueModifierStringInjection46.Injections(Arrays.asList(entries));
  }

  static RogueModifierReplace46.State state(Map<Integer, String> map) {
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings = RogueModifierStringInjection46.javaMap(map);
    return s;
  }

  @Test
  void nullListSkipsMissingDictionary() {
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings = null;
    RogueModifierStringInjection46.applyStringInjections(s, null);
    assertNull(s.dynamicStrings);
  }

  @Test
  void emptyVersionedListSkipsMissingDictionary() {
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings = null;
    RogueModifierStringInjection46.applyStringInjections(s, list());
    assertNull(s.dynamicStrings);
  }

  @Test
  void ordinaryListCannotPretendToHaveMatchedNativeVersion() {
    var s = RogueModifierReplace46Test.state();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierStringInjection46.applyStringInjections(s, List.of(item(1, "a"))));
  }

  @Test
  void foreignObjectIsRejectedBeforeDictionary() {
    var s = RogueModifierReplace46Test.state();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierStringInjection46.applyStringInjections(s, new Object()));
  }

  @Test
  void missingStateDoesNotFabricateSuccessEvenForNullList() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierStringInjection46.applyStringInjections(null, null));
  }

  @Test
  void missingDictionaryRejectsNonemptyList() {
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierStringInjection46.applyStringInjections(s, list(item(1, "a"))));
  }

  @Test
  void unmatchedDictionaryObjectIsRejected() {
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings = new Object();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierStringInjection46.applyStringInjections(s, list(item(1, "a"))));
  }

  @Test
  void explicitJavaMapWritesAllValues() {
    var map = new HashMap<Integer, String>();
    RogueModifierStringInjection46.applyStringInjections(
        state(map), list(item(1, "a"), item(2, "b")));
    assertEquals(Map.of(1, "a", 2, "b"), map);
  }

  @Test
  void duplicateKeysUseLastValueWithoutIncreasingMapSize() {
    var map = new HashMap<Integer, String>();
    RogueModifierStringInjection46.applyStringInjections(
        state(map), list(item(1, "a"), item(1, "b")));
    assertEquals(Map.of(1, "b"), map);
  }

  @Test
  void existingKeyIsOverwritten() {
    var map = new HashMap<Integer, String>();
    map.put(1, "old");
    RogueModifierStringInjection46.applyStringInjections(state(map), list(item(1, "new")));
    assertEquals(Map.of(1, "new"), map);
  }

  @Test
  void nullStringValueStillDeclaresKey() {
    var map = new HashMap<Integer, String>();
    RogueModifierStringInjection46.applyStringInjections(state(map), list(item(1, null)));
    assertTrue(map.containsKey(1));
    assertNull(map.get(1));
  }

  @Test
  void nullStringOverwritesExistingValue() {
    var map = new HashMap<Integer, String>();
    map.put(1, "old");
    RogueModifierStringInjection46.applyStringInjections(state(map), list(item(1, null)));
    assertTrue(map.containsKey(1));
    assertNull(map.get(1));
  }

  @Test
  void emptyWhitespaceUnicodeAndEmbeddedZeroArePreserved() {
    var map = new HashMap<Integer, String>();
    RogueModifierStringInjection46.applyStringInjections(
        state(map), list(item(0, ""), item(1, " "), item(2, "祝福😀"), item(3, "a\0b")));
    assertEquals(Map.of(0, "", 1, " ", 2, "祝福😀", 3, "a\0b"), map);
  }

  @Test
  void allSignedInt32HashBitsArePreserved() {
    var map = new HashMap<Integer, String>();
    RogueModifierStringInjection46.applyStringInjections(
        state(map),
        list(
            item(Integer.MIN_VALUE, "min"),
            item(Integer.MAX_VALUE, "max"),
            item(-1, "negative"),
            item(0, "zero")));
    assertEquals(
        Map.of(Integer.MIN_VALUE, "min", Integer.MAX_VALUE, "max", -1, "negative", 0, "zero"), map);
  }

  @Test
  void dictionaryOperationsRunInInputOrderIncludingDuplicateKey() {
    var order = new ArrayList<Integer>();
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings = (RogueModifierStringInjection46.StringDictionary) (k, v) -> order.add(k);
    RogueModifierStringInjection46.applyStringInjections(
        s, list(item(2, "b"), item(1, "a"), item(2, "c")));
    assertEquals(List.of(2, 1, 2), order);
  }

  @Test
  void dictionaryReplacementIsRereadForNextElement() {
    var a = new HashMap<Integer, String>();
    var b = new HashMap<Integer, String>();
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings =
        (RogueModifierStringInjection46.StringDictionary)
            (k, v) -> {
              a.put(k, v);
              s.dynamicStrings = RogueModifierStringInjection46.javaMap(b);
            };
    RogueModifierStringInjection46.applyStringInjections(s, list(item(1, "a"), item(2, "b")));
    assertEquals(Map.of(1, "a"), a);
    assertEquals(Map.of(2, "b"), b);
  }

  @Test
  void missingLaterDictionaryKeepsCommittedPrefix() {
    var map = new HashMap<Integer, String>();
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings =
        (RogueModifierStringInjection46.StringDictionary)
            (k, v) -> {
              map.put(k, v);
              s.dynamicStrings = null;
            };
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierStringInjection46.applyStringInjections(
                s, list(item(1, "a"), item(2, "b"))));
    assertEquals(Map.of(1, "a"), map);
  }

  @Test
  void dictionaryFaultKeepsEarlierWritesAndStopsRemainingEntries() {
    var map = new HashMap<Integer, String>();
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings =
        (RogueModifierStringInjection46.StringDictionary)
            (k, v) -> {
              if (k == 2) throw new IllegalStateException("dictionary");
              map.put(k, v);
            };
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueModifierStringInjection46.applyStringInjections(
                s, list(item(1, "a"), item(2, "b"), item(3, "c"))));
    assertEquals(Map.of(1, "a"), map);
  }

  @Test
  void faultAfterActualWriteRetainsFaultingValue() {
    var map = new HashMap<Integer, String>();
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings =
        (RogueModifierStringInjection46.StringDictionary)
            (k, v) -> {
              map.put(k, v);
              throw new IllegalStateException("afterWrite");
            };
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueModifierStringInjection46.applyStringInjections(
                s, list(item(1, "a"), item(2, "b"))));
    assertEquals(Map.of(1, "a"), map);
  }

  @Test
  void appendDuringWriteFailsBeforeVisitingNewItem() {
    var input = list(item(1, "a"));
    var map = new HashMap<Integer, String>();
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings =
        (RogueModifierStringInjection46.StringDictionary)
            (k, v) -> {
              map.put(k, v);
              input.add(item(2, "b"));
            };
    assertThrows(
        ConcurrentModificationException.class,
        () -> RogueModifierStringInjection46.applyStringInjections(s, input));
    assertEquals(Map.of(1, "a"), map);
  }

  @Test
  void clearingListAfterLastWriteStillFailsVersionBeforeCount() {
    var input = list(item(1, "a"));
    var map = new HashMap<Integer, String>();
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings =
        (RogueModifierStringInjection46.StringDictionary)
            (k, v) -> {
              map.put(k, v);
              input.clear();
            };
    assertThrows(
        ConcurrentModificationException.class,
        () -> RogueModifierStringInjection46.applyStringInjections(s, input));
    assertEquals(Map.of(1, "a"), map);
    assertTrue(input.isEmpty());
  }

  @Test
  void removingFinalPendingItemFailsRatherThanEndingNormally() {
    var input = list(item(1, "a"), item(2, "b"));
    var map = new HashMap<Integer, String>();
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings =
        (RogueModifierStringInjection46.StringDictionary)
            (k, v) -> {
              map.put(k, v);
              input.removeLast();
            };
    assertThrows(
        ConcurrentModificationException.class,
        () -> RogueModifierStringInjection46.applyStringInjections(s, input));
    assertEquals(Map.of(1, "a"), map);
  }

  @Test
  void replacingEntryWithSameValueStillInvalidatesVersion() {
    var input = list(item(1, "a"));
    var map = new HashMap<Integer, String>();
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings =
        (RogueModifierStringInjection46.StringDictionary)
            (k, v) -> {
              map.put(k, v);
              input.set(0, input.getFirst());
            };
    assertThrows(
        ConcurrentModificationException.class,
        () -> RogueModifierStringInjection46.applyStringInjections(s, input));
    assertEquals(Map.of(1, "a"), map);
  }

  @Test
  void addThenRemoveRestoringOriginalCountStillInvalidatesVersion() {
    var input = list(item(1, "a"), item(2, "b"));
    var map = new HashMap<Integer, String>();
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings =
        (RogueModifierStringInjection46.StringDictionary)
            (k, v) -> {
              map.put(k, v);
              input.add(item(3, "c"));
              input.removeLast();
            };
    assertThrows(
        ConcurrentModificationException.class,
        () -> RogueModifierStringInjection46.applyStringInjections(s, input));
    assertEquals(Map.of(1, "a"), map);
    assertEquals(2, input.size());
  }

  @Test
  void changingInitialListFieldDoesNotReplaceCapturedList() {
    var input = list(item(1, "a"), item(2, "b"));
    var map = new HashMap<Integer, String>();
    var s = RogueModifierReplace46Test.state();
    s.dynamicStrings =
        (RogueModifierStringInjection46.StringDictionary)
            (k, v) -> {
              map.put(k, v);
              s.initialStringInjections = list(item(99, "new"));
            };
    RogueModifierStringInjection46.applyStringInjections(s, input);
    assertEquals(Map.of(1, "a", 2, "b"), map);
    assertFalse(map.containsKey(99));
  }

  @Test
  void listConstructorCopiesExternalCollection() {
    var original = new ArrayList<RogueModifierStringInjection46.StringInjection>();
    original.add(item(1, "a"));
    var input = new RogueModifierStringInjection46.Injections(original);
    original.add(item(2, "b"));
    var map = new HashMap<Integer, String>();
    RogueModifierStringInjection46.applyStringInjections(state(map), input);
    assertEquals(Map.of(1, "a"), map);
  }

  @Test
  void missingCollectionOrMapDoesNotCreateEmptyBinding() {
    assertThrows(
        IllegalArgumentException.class, () -> new RogueModifierStringInjection46.Injections(null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierStringInjection46.javaMap(null));
  }

  @Test
  void nullLaterEntryKeepsEarlierWriteWithoutInventingStruct() {
    var map = new HashMap<Integer, String>();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierStringInjection46.applyStringInjections(
                state(map), list(item(1, "a"), null)));
    assertEquals(Map.of(1, "a"), map);
  }

  @Test
  void finiteLimitStopsAfter255CommittedItems() {
    var entries = new ArrayList<RogueModifierStringInjection46.StringInjection>();
    for (int i = 0; i < 256; i++) entries.add(item(i, "v"));
    var map = new HashMap<Integer, String>();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierStringInjection46.applyStringInjections(
                state(map), new RogueModifierStringInjection46.Injections(entries)));
    assertEquals(255, map.size());
    assertFalse(map.containsKey(255));
  }

  @Test
  void actualReplacementUsesSameStringStateAndVersionedParameters() {
    var map = new HashMap<Integer, String>();
    var s = state(map);
    var p = RogueModifierReplace46Test.parameters();
    p.base.stringInjections = list(item(1, "a"));
    var world =
        new RogueModifierReplace46Test.BoundWorld() {
          public void setupStringInjections(RogueModifierReplace46.State state, Object values) {
            super.setupStringInjections(state, values);
            RogueModifierStringInjection46.applyStringInjections(state, values);
          }
        };
    RogueModifierReplace46.replace(s, RogueModifierReplace46Test.ABILITY, p, world);
    assertEquals(Map.of(1, "a"), map);
    assertSame(p.base.stringInjections, s.initialStringInjections);
    assertEquals(
        List.of(
            "acquire",
            "layerAdd",
            "release",
            "flag7",
            "context",
            "ability",
            "abilityChanged",
            "string",
            "dispatch"),
        world.order);
  }

  @Test
  void actualNumericAndStringReplacementBindingsKeepFloatBeforeString() {
    var map = new HashMap<Integer, String>();
    var vars = RogueResonanceVariables46.fromSourceDefaults(Map.of(), null);
    var s = state(map);
    s.values.dynamicValues = vars;
    var p = RogueModifierReplace46Test.parameters();
    p.base.floatInjections = List.of(new RogueModifierInjection46.FloatInjection(1, 1L << 33));
    p.base.stringInjections = list(item(1, "a"));
    var world =
        new RogueModifierReplace46Test.BoundWorld() {
          public void setupFloatInjections(RogueModifierReplace46.State state, Object values) {
            super.setupFloatInjections(state, values);
            RogueModifierInjection46.applyFloatInjections(state, values);
          }

          public void setupStringInjections(RogueModifierReplace46.State state, Object values) {
            super.setupStringInjections(state, values);
            assertEquals(1L << 33, vars.read(1));
            RogueModifierStringInjection46.applyStringInjections(state, values);
          }
        };
    RogueModifierReplace46.replace(s, RogueModifierReplace46Test.ABILITY, p, world);
    assertEquals(Map.of(1, "a"), map);
    assertEquals(
        List.of(
            "acquire",
            "layerAdd",
            "release",
            "flag7",
            "context",
            "ability",
            "abilityChanged",
            "float",
            "string",
            "dispatch"),
        world.order);
  }

  @Test
  void unchangedListCanBeReusedAndDictionaryCanBeUpdatedBetweenCalls() {
    var input = list(item(1, "a"));
    var a = new HashMap<Integer, String>();
    var b = new HashMap<Integer, String>();
    var s = state(a);
    RogueModifierStringInjection46.applyStringInjections(s, input);
    s.dynamicStrings = RogueModifierStringInjection46.javaMap(b);
    RogueModifierStringInjection46.applyStringInjections(s, input);
    assertEquals(Map.of(1, "a"), a);
    assertEquals(Map.of(1, "a"), b);
  }
}
