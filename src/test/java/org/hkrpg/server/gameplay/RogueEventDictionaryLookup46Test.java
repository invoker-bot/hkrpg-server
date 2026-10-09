package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEventDictionaryLookup46Test {
  static final RogueEventListenerWithdrawal46.Key
      QUERY = new RogueEventListenerWithdrawal46.Key(7, 207),
      COLLISION = new RogueEventListenerWithdrawal46.Key(8, 184);
  static final Object COMPARER = new Object();

  static RogueEventListenerWithdrawal46.Entry entry(
      RogueEventListenerWithdrawal46.Key key, int hash, int next) {
    var e = new RogueEventListenerWithdrawal46.Entry();
    e.key = key;
    e.hashCode = hash;
    e.next = next;
    return e;
  }

  static RogueEventListenerWithdrawal46.Dictionary dictionary(
      RogueEventListenerWithdrawal46.Entry... entries) {
    var d = new RogueEventListenerWithdrawal46.Dictionary();
    d.buckets = new int[] {0, 1, 0};
    d.entries = entries;
    return d;
  }

  static class World implements RogueEventDictionaryLookup46.World {
    int hash = 9361;
    final List<String> order = new ArrayList<>();
    Consumer<RogueEventListenerWithdrawal46.Key> onHash = key -> {};
    Consumer<RogueEventListenerWithdrawal46.Key> onEqual = key -> {};
    Boolean forcedEquality;

    public int hashCode(Object comparer, RogueEventListenerWithdrawal46.Key key) {
      assertSame(COMPARER, comparer);
      order.add("hash");
      onHash.accept(key);
      return hash;
    }

    public boolean equals(
        Object comparer,
        RogueEventListenerWithdrawal46.Key stored,
        RogueEventListenerWithdrawal46.Key requested) {
      assertSame(COMPARER, comparer);
      assertEquals(QUERY, requested);
      order.add("equal");
      onEqual.accept(stored);
      return forcedEquality != null ? forcedEquality : stored.raw() == requested.raw();
    }
  }

  @Test
  void missingBucketsReturnsWithoutEntriesKeyOrComparerBinding() {
    var d = new RogueEventListenerWithdrawal46.Dictionary();
    d.comparer = COMPARER;
    assertEquals(-1, RogueEventDictionaryLookup46.find(d, null, null));
  }

  @Test
  void defaultHashMatchesFrozenSourceValuesAndSignedOverflow() {
    assertEquals(
        8993,
        RogueEventDictionaryLookup46.defaultHash(new RogueEventListenerWithdrawal46.Key(0, 0)));
    assertEquals(9361, RogueEventDictionaryLookup46.defaultHash(QUERY));
    assertEquals(9361, RogueEventDictionaryLookup46.defaultHash(COLLISION));
    assertEquals(
        8969,
        RogueEventDictionaryLookup46.defaultHash(new RogueEventListenerWithdrawal46.Key(-1, -1)));
    assertEquals(
        8993,
        RogueEventDictionaryLookup46.defaultHash(
            new RogueEventListenerWithdrawal46.Key(Integer.MIN_VALUE, 0)));
  }

  @Test
  void exactBucketHeadMatchesWithoutComparerWorld() {
    var d = dictionary(entry(QUERY, 9361, -1));
    assertEquals(0, RogueEventDictionaryLookup46.find(d, QUERY, null));
  }

  @Test
  void equalHashWithDifferentPackedKeyContinuesToNextEntry() {
    var d = dictionary(entry(COLLISION, 9361, 1), entry(QUERY, 9361, -1));
    assertEquals(1, RogueEventDictionaryLookup46.find(d, QUERY, null));
  }

  @Test
  void mismatchedHashSkipsMissingKeyAndContinuesToMatchingEntry() {
    var d = dictionary(entry(null, 0, 1), entry(QUERY, 9361, -1));
    assertEquals(1, RogueEventDictionaryLookup46.find(d, QUERY, null));
  }

  @Test
  void missingMatchingHashKeyIsRejectedAsUnmatchedValueProjection() {
    var d = dictionary(entry(null, 9361, -1));
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, null));
  }

  @Test
  void zeroBucketReturnsMinusOneAfterRequiringCapturedEntries() {
    var d = dictionary(entry(QUERY, 9361, -1));
    d.buckets[1] = 0;
    assertEquals(-1, RogueEventDictionaryLookup46.find(d, QUERY, null));
    d.entries = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, null));
  }

  @ParameterizedTest
  @ValueSource(ints = {-2, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void negativeOrOversizedBucketIndexIsReturnedWithoutNormalizingIt(int rawIndex) {
    var d = dictionary(entry(QUERY, 9361, -1));
    d.buckets[1] = rawIndex + 1;
    assertEquals(rawIndex, RogueEventDictionaryLookup46.find(d, QUERY, null));
  }

  @Test
  void zeroLengthEntriesAndHeadOneReturnRawInvalidIndexZero() {
    var d = dictionary();
    assertEquals(0, RogueEventDictionaryLookup46.find(d, QUERY, null));
  }

  @Test
  void zeroLengthBucketsRaisesDivisionFailureAfterCustomHashCall() {
    var d = dictionary(entry(QUERY, 9361, -1));
    d.buckets = new int[0];
    var w = new World();
    assertThrows(
        ArithmeticException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, null));
    d.comparer = COMPARER;
    assertThrows(ArithmeticException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, w));
    assertEquals(List.of("hash"), w.order);
  }

  @Test
  void explicitNullEntryIsRejectedWithoutDefaultHashOrNext() {
    var d = dictionary((RogueEventListenerWithdrawal46.Entry) null);
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, null));
  }

  @Test
  void defaultMismatchChainPreservesRawNegativeNext() {
    var d = dictionary(entry(COLLISION, 9361, -2));
    assertEquals(-2, RogueEventDictionaryLookup46.find(d, QUERY, null));
  }

  @Test
  void oversizedNextIndexIsReturnedToCallerForItsCurrentArrayValidation() {
    var d = dictionary(entry(COLLISION, 9361, 7));
    assertEquals(7, RogueEventDictionaryLookup46.find(d, QUERY, null));
  }

  @Test
  void cyclicDefaultChainRaisesConcurrentChainFailureWithoutHeaderMutation() {
    var d = dictionary(entry(COLLISION, 9361, 0));
    var buckets = d.buckets;
    var entries = d.entries;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, null));
    assertSame(buckets, d.buckets);
    assertSame(entries, d.entries);
    assertEquals(0, entries[0].next);
  }

  @Test
  void customHashIsMaskedAndCustomEqualityDeterminesMatch() {
    var d = dictionary(entry(COLLISION, 9361, -1));
    d.comparer = COMPARER;
    var w = new World();
    w.hash = 9361 | Integer.MIN_VALUE;
    w.forcedEquality = true;
    assertEquals(0, RogueEventDictionaryLookup46.find(d, QUERY, w));
    assertEquals(List.of("hash", "equal"), w.order);
  }

  @Test
  void customHashMismatchSkipsEqualityAndReadsNext() {
    var d = dictionary(entry(null, 0, 1), entry(QUERY, 9361, -1));
    d.comparer = COMPARER;
    var w = new World();
    assertEquals(1, RogueEventDictionaryLookup46.find(d, QUERY, w));
    assertEquals(List.of("hash", "equal"), w.order);
  }

  @Test
  void customEqualityFalseContinuesAndDoesNotFallBackToRawKeyMatch() {
    var d = dictionary(entry(QUERY, 9361, -1));
    d.comparer = COMPARER;
    var w = new World();
    w.forcedEquality = false;
    assertEquals(-1, RogueEventDictionaryLookup46.find(d, QUERY, w));
    assertEquals(List.of("hash", "equal"), w.order);
  }

  @Test
  void customComparerAndEntriesRemainCapturedAfterHashCallbackReplacesHeaders() {
    var d = dictionary(entry(QUERY, 9361, -1));
    var original = d.entries;
    d.comparer = COMPARER;
    var w = new World();
    w.onHash =
        key -> {
          d.entries = null;
          d.comparer = null;
          d.buckets = null;
        };
    assertEquals(0, RogueEventDictionaryLookup46.find(d, QUERY, w));
    assertNotNull(original[0]);
    assertEquals(List.of("hash", "equal"), w.order);
  }

  @Test
  void capturedBucketArrayHeadChangesDuringHashAreObserved() {
    var d = dictionary(entry(COLLISION, 9361, -1), entry(QUERY, 9361, -1));
    d.comparer = COMPARER;
    var w = new World();
    w.onHash = key -> d.buckets[1] = 2;
    assertEquals(1, RogueEventDictionaryLookup46.find(d, QUERY, w));
    assertEquals(List.of("hash", "equal"), w.order);
  }

  @Test
  void failedEqualityRereadsCapturedEntryNextAfterSlotReplacement() {
    var d = dictionary(entry(COLLISION, 9361, -1), entry(QUERY, 9361, -1));
    d.comparer = COMPARER;
    var w = new World();
    w.onEqual =
        key -> {
          if (key == COLLISION) d.entries[0] = entry(QUERY, 9361, 1);
        };
    assertEquals(1, RogueEventDictionaryLookup46.find(d, QUERY, w));
    assertEquals(List.of("hash", "equal", "equal"), w.order);
  }

  @Test
  void successfulEqualityReturnsCapturedIndexEvenWhenEntryHeadersChange() {
    var d = dictionary(entry(QUERY, 9361, -1));
    d.comparer = COMPARER;
    var w = new World();
    w.onEqual = key -> d.entries = null;
    assertEquals(0, RogueEventDictionaryLookup46.find(d, QUERY, w));
    assertNull(d.entries);
  }

  @Test
  void failedEqualityWithNullReplacedEntryFailsBeforeFollowingLink() {
    var d = dictionary(entry(COLLISION, 9361, -1));
    d.comparer = COMPARER;
    var w = new World();
    w.onEqual = key -> d.entries[0] = null;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, w));
    assertEquals(List.of("hash", "equal"), w.order);
  }

  @Test
  void cyclicCustomChainFailsAfterItsTwoFailedEqualityCallsForOneEntry() {
    var d = dictionary(entry(COLLISION, 9361, 0));
    d.comparer = COMPARER;
    var w = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, w));
    assertEquals(List.of("hash", "equal", "equal"), w.order);
  }

  @Test
  void customHashFailurePreservesCallbackHeaderChangesAndStopsEquality() {
    var d = dictionary(entry(QUERY, 9361, -1));
    d.comparer = COMPARER;
    var w = new World();
    w.onHash =
        key -> {
          d.buckets = null;
          throw new IllegalStateException("hash");
        };
    assertThrows(IllegalStateException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, w));
    assertNull(d.buckets);
    assertEquals(List.of("hash"), w.order);
  }

  @Test
  void customEqualityFailurePreservesNextMutationWithoutAdvancing() {
    var d = dictionary(entry(COLLISION, 9361, -1));
    d.comparer = COMPARER;
    var w = new World();
    w.onEqual =
        key -> {
          d.entries[0].next = 8;
          throw new IllegalStateException("equal");
        };
    assertThrows(IllegalStateException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, w));
    assertEquals(8, d.entries[0].next);
    assertEquals(List.of("hash", "equal"), w.order);
  }

  @Test
  void exactFiniteCapacityFindsLastEntryAndExcessBucketsOrEntriesAreRejected() {
    var d = dictionary();
    d.entries = new RogueEventListenerWithdrawal46.Entry[4096];
    d.entries[4095] = entry(QUERY, 9361, -1);
    d.buckets[1] = 4096;
    assertEquals(4095, RogueEventDictionaryLookup46.find(d, QUERY, null));
    d.entries = new RogueEventListenerWithdrawal46.Entry[4097];
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, null));
    d.buckets = new int[4097];
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, null));
  }

  @Test
  void actualManagerWithdrawalUsesRealBucketLookupAndActualDelegateEqualityToRemove() {
    var stored = RogueEventDelegateEquality46Test.callback(null);
    var requested = RogueEventDelegateEquality46Test.callback(null);
    var s = RogueEventListenerWithdrawal46Test.state(stored);
    s.entityEvents.entries[0].hashCode = 9361;
    s.entityEvents.entries[0].next = -1;
    s.entityEvents.buckets = new int[] {0, 1, 0};
    var w =
        new RogueEventListenerWithdrawal46Test.World() {
          @Override
          public int findEntryIndex(
              RogueEventListenerWithdrawal46.Dictionary d, RogueEventListenerWithdrawal46.Key key) {
            return RogueEventDictionaryLookup46.find(d, key, null);
          }
        };
    RogueEventListenerWithdrawal46.unregister(s, 207, 7, requested, w);
    assertTrue(RogueEventListenerWithdrawal46Test.values(s, 0).isEmpty());
    assertEquals(List.of("remove"), w.order);
  }

  @Test
  void callerRereadsCurrentEntriesAfterSuccessfulCustomLookupThenFailsWithoutRemove() {
    var stored = RogueEventDelegateEquality46Test.callback(null);
    var s = RogueEventListenerWithdrawal46Test.state(stored);
    s.entityEvents.entries[0].hashCode = 9361;
    s.entityEvents.entries[0].next = -1;
    s.entityEvents.buckets = new int[] {0, 1, 0};
    s.entityEvents.comparer = COMPARER;
    var comparerWorld = new World();
    comparerWorld.onEqual = key -> s.entityEvents.entries = null;
    var w =
        new RogueEventListenerWithdrawal46Test.World() {
          @Override
          public int findEntryIndex(
              RogueEventListenerWithdrawal46.Dictionary d, RogueEventListenerWithdrawal46.Key key) {
            return RogueEventDictionaryLookup46.find(d, key, comparerWorld);
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventListenerWithdrawal46.unregister(s, 207, 7, stored, w));
    assertTrue(w.order.isEmpty());
    assertNull(s.entityEvents.entries);
  }

  @Test
  void keyHashCodePreservesSignWhileDictionaryHashMasksTheSameRawResult() {
    var key = new RogueEventListenerWithdrawal46.Key(Integer.MIN_VALUE, 0);
    assertEquals(Integer.MIN_VALUE + 8993, RogueEventDictionaryLookup46.keyHashCode(key));
    assertEquals(8993, RogueEventDictionaryLookup46.defaultHash(key));
  }

  @Test
  void keyEqualityComparesBothRawFieldsAndSeparatesSameHashCollision() {
    assertTrue(
        RogueEventDictionaryLookup46.keysEqual(
            QUERY, new RogueEventListenerWithdrawal46.Key(7, 207)));
    assertFalse(RogueEventDictionaryLookup46.keysEqual(QUERY, COLLISION));
    assertFalse(
        RogueEventDictionaryLookup46.keysEqual(
            QUERY, new RogueEventListenerWithdrawal46.Key(7, 208)));
    var raw = new RogueEventListenerWithdrawal46.Key(-1, Integer.MIN_VALUE);
    assertTrue(
        RogueEventDictionaryLookup46.keysEqual(
            raw, new RogueEventListenerWithdrawal46.Key(-1, Integer.MIN_VALUE)));
  }

  @Test
  void keyHashAndEqualityRejectMissingExplicitValueStructures() {
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.keyHashCode(null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.keysEqual(null, QUERY));
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.keysEqual(QUERY, null));
  }

  @Test
  void missingComparerWorldAndExplicitKeyAreRejectedOnlyForNonemptyBucketProjection() {
    var d = dictionary(entry(QUERY, 9361, -1));
    d.comparer = COMPARER;
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.find(d, QUERY, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventDictionaryLookup46.find(d, null, new World()));
    assertThrows(
        IllegalArgumentException.class, () -> RogueEventDictionaryLookup46.find(null, QUERY, null));
  }
}
