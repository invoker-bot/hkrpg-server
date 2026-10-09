package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueTeamBoostWithdrawal46.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueTeamBoostWithdrawal46Test {
  static State ring(int... ids) {
    var s = new State();
    s.additional = new AdditionalList();
    s.additional.size = ids.length;
    var nodes = new Node[ids.length];
    for (int i = 0; i < ids.length; i++) {
      nodes[i] = new Node();
      nodes[i].hasRegistration = 1;
      nodes[i].registrationId = ids[i];
      nodes[i].owner = s.additional;
    }
    for (int i = 0; i < ids.length; i++) {
      nodes[i].next = nodes[(i + 1) % ids.length];
      nodes[i].previous = nodes[(i + ids.length - 1) % ids.length];
    }
    s.additional.head = ids.length == 0 ? null : nodes[0];
    return s;
  }

  static ChildEntities children(Object... entries) {
    var c = new ChildEntities();
    c.entries = entries;
    c.size = entries.length;
    return c;
  }

  static BoostEntry entry(Object modifier, ChildEntities children) {
    return new BoostEntry(modifier, 73, children, 7, 9);
  }

  static BoostEntries boost(BoostEntry... entries) {
    var b = new BoostEntries();
    b.entries = entries;
    b.size = entries.length;
    return b;
  }

  static State state(BoostEntry... entries) {
    var s = ring();
    s.boost = boost(entries);
    return s;
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void registrationIdentifiersPreserveSignedBitsAndRemoveOnlyFirstMatch(int id) {
    var s = ring(id, id);
    var first = s.additional.head;
    var second = first.next;
    removeAdditional(s, id);
    assertSame(second, s.additional.head);
    assertSame(second, second.next);
    assertSame(second, second.previous);
    assertNull(first.owner);
    assertNull(first.next);
    assertNull(first.previous);
    assertEquals(1, s.additional.size);
    assertEquals(1, s.additional.version);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2, 4, 128})
  void onlyLowPresenceBitEnablesRegistrationComparison(int flags) {
    var s = ring(1, 2);
    s.additional.head.hasRegistration = (byte) flags;
    removeAdditional(s, 1);
    assertEquals(2, s.additional.size);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3, 129, 255})
  void otherFlagBitsDoNotSuppressSetLowPresenceBit(int flags) {
    var s = ring(1);
    s.additional.head.hasRegistration = (byte) flags;
    removeAdditional(s, 1);
    assertNull(s.additional.head);
  }

  @Test
  void missingIdentifierLeavesWholeRingUnchanged() {
    var s = ring(1, 2, 3);
    var h = s.additional.head;
    removeAdditional(s, 9);
    assertSame(h, s.additional.head);
    assertEquals(3, s.additional.size);
    assertEquals(0, s.additional.version);
  }

  @Test
  void removingMiddleNodeRepairsBothNeighbours() {
    var s = ring(1, 2, 3);
    var h = s.additional.head;
    var m = h.next;
    var t = m.next;
    removeAdditional(s, 2);
    assertSame(t, h.next);
    assertSame(h, t.previous);
    assertSame(h, s.additional.head);
    assertNull(m.owner);
  }

  @Test
  void removingLastNodeKeepsHeadAndCircularLinks() {
    var s = ring(1, 2, 3);
    var h = s.additional.head;
    removeAdditional(s, 3);
    assertSame(h, h.next.next);
    assertSame(h.next, h.previous);
    assertEquals(2, s.additional.size);
  }

  @Test
  void singleSelfLinkedNodeDoesNotRequirePreviousLink() {
    var s = ring(1);
    s.additional.head.previous = null;
    removeAdditional(s, 1);
    assertNull(s.additional.head);
    assertEquals(0, s.additional.size);
  }

  @Test
  void emptyHeadDoesNotRequireAnyNodeStorage() {
    var s = ring();
    removeAdditional(s, 1);
    assertEquals(0, s.additional.version);
  }

  @Test
  void missingAdditionalListIsClosed() {
    assertThrows(IllegalArgumentException.class, () -> removeAdditional(new State(), 1));
  }

  @Test
  void detachedNextReturnsBeforeReadingMissingOwner() {
    var s = ring(1);
    s.additional.head.hasRegistration = 0;
    s.additional.head.next = null;
    s.additional.head.owner = null;
    removeAdditional(s, 1);
    assertEquals(1, s.additional.size);
  }

  @Test
  void nonnullNextRequiresCurrentNodeOwner() {
    var s = ring(1, 2);
    s.additional.head.owner = null;
    assertThrows(IllegalArgumentException.class, () -> removeAdditional(s, 9));
    assertEquals(2, s.additional.size);
  }

  @Test
  void foreignMatchingNodeIsRejectedBeforeAnyMutation() {
    var s = ring(1);
    var n = s.additional.head;
    n.owner = new AdditionalList();
    assertThrows(IllegalArgumentException.class, () -> removeAdditional(s, 1));
    assertSame(n, s.additional.head);
    assertSame(n, n.next);
    assertEquals(0, s.additional.version);
  }

  @Test
  void missingNextFailsBeforeChangingPreviousOrCounters() {
    var s = ring(1, 2);
    var n = s.additional.head;
    var p = n.previous;
    n.next = null;
    assertThrows(IllegalArgumentException.class, () -> removeNode(s.additional, n));
    assertSame(p, n.previous);
    assertEquals(2, s.additional.size);
    assertEquals(0, s.additional.version);
  }

  @Test
  void missingPreviousKeepsAlreadyWrittenNextPreviousPrefix() {
    var s = ring(1, 2);
    var n = s.additional.head;
    var next = n.next;
    n.previous = null;
    assertThrows(IllegalArgumentException.class, () -> removeNode(s.additional, n));
    assertNull(next.previous);
    assertSame(n, s.additional.head);
    assertSame(s.additional, n.owner);
    assertEquals(2, s.additional.size);
  }

  @Test
  void countersWrapOnlyAfterNodeInvalidationSucceeds() {
    var s = ring(1);
    s.additional.size = Integer.MIN_VALUE;
    s.additional.version = Integer.MAX_VALUE;
    var n = s.additional.head;
    removeAdditional(s, 1);
    assertEquals(Integer.MAX_VALUE, s.additional.size);
    assertEquals(Integer.MIN_VALUE, s.additional.version);
    assertNull(n.owner);
  }

  @Test
  void repeatedRemovalOfInvalidatedNodeFailsWithoutSecondCounterChange() {
    var s = ring(1);
    var n = s.additional.head;
    removeNode(s.additional, n);
    assertThrows(IllegalArgumentException.class, () -> removeNode(s.additional, n));
    assertEquals(0, s.additional.size);
    assertEquals(1, s.additional.version);
  }

  @Test
  void malformedCycleBeyondOwnerHeadIsClosedByFiniteTraversal() {
    var s = ring(1);
    var n = new Node();
    n.owner = s.additional;
    n.next = n;
    s.additional.head.next = n;
    assertThrows(IllegalArgumentException.class, () -> removeAdditional(s, 9));
    assertEquals(1, s.additional.size);
  }

  @Test
  void nullAndEmptyBoostListsReturnWithoutRequiringEntries() {
    var s = ring();
    removeBoost(s, null);
    s.boost = new BoostEntries();
    removeBoost(s, null);
    s.boost.size = -1;
    removeBoost(s, null);
    assertEquals(-1, s.boost.size);
  }

  @Test
  void reverseRemovalFindsAllIdentityMatchesAndPreservesOtherEntries() {
    Object target = new Object(), other = new Object();
    var a = children(1);
    var b = children(2);
    var retained = entry(other, children(3));
    var s = state(entry(target, a), retained, entry(target, b));
    removeBoost(s, target);
    assertEquals(1, s.boost.size);
    assertSame(retained, s.boost.entries[0]);
    assertNull(s.boost.entries[1]);
    assertNull(s.boost.entries[2]);
    assertEquals(2, s.boost.version);
    assertEquals(0, a.size);
    assertEquals(0, b.size);
    assertNull(a.entries[0]);
    assertNull(b.entries[0]);
  }

  @Test
  void boostIdentityComparisonDoesNotUseValueEquality() {
    Object a = new String("same"), b = new String("same");
    var retained = entry(b, null);
    var s = state(entry(a, null), retained);
    removeBoost(s, a);
    assertEquals(1, s.boost.size);
    assertSame(retained, s.boost.entries[0]);
  }

  @Test
  void defaultZeroStructSlotMatchesNullModifier() {
    var s = state(null, entry(new Object(), null));
    var retained = s.boost.entries[1];
    removeBoost(s, null);
    assertEquals(1, s.boost.size);
    assertSame(retained, s.boost.entries[0]);
  }

  @Test
  void unmatchedEntryDoesNotClearItsChildren() {
    var c = children(1);
    var s = state(entry(new Object(), c));
    removeBoost(s, new Object());
    assertEquals(1, c.size);
    assertEquals(0, c.version);
    assertEquals(0, s.boost.version);
  }

  @Test
  void nullChildrenAreAllowed() {
    Object target = new Object();
    var s = state(entry(target, null));
    removeBoost(s, target);
    assertEquals(0, s.boost.size);
  }

  @Test
  void zeroChildCountStillIncrementsVersionWithoutRequiringStorage() {
    var c = new ChildEntities();
    clearChildren(entry(null, c));
    assertEquals(1, c.version);
    assertEquals(0, c.size);
  }

  @Test
  void negativeChildCountIsResetAndDoesNotRequireStorage() {
    var c = new ChildEntities();
    c.size = -1;
    clearChildren(entry(null, c));
    assertEquals(0, c.size);
    assertEquals(1, c.version);
  }

  @Test
  void childClearFailureKeepsHeaderWritesAndLeavesOuterEntryPresent() {
    Object target = new Object();
    var c = new ChildEntities();
    c.size = 1;
    var e = entry(target, c);
    var s = state(e);
    assertThrows(IllegalArgumentException.class, () -> removeBoost(s, target));
    assertEquals(0, c.size);
    assertEquals(1, c.version);
    assertEquals(1, s.boost.size);
    assertSame(e, s.boost.entries[0]);
  }

  @Test
  void retryAfterMissingChildStorageSkipsZeroCountAndCompletesRemoval() {
    Object target = new Object();
    var c = new ChildEntities();
    c.size = 1;
    var s = state(entry(target, c));
    assertThrows(IllegalArgumentException.class, () -> removeBoost(s, target));
    removeBoost(s, target);
    assertEquals(0, s.boost.size);
    assertEquals(2, c.version);
  }

  @Test
  void shortChildStorageKeepsEntriesAndHeaderClear() {
    var c = children(1);
    c.size = 2;
    assertThrows(IndexOutOfBoundsException.class, () -> clearChildren(entry(null, c)));
    assertEquals(0, c.size);
    assertEquals(1, c.version);
    assertEquals(1, c.entries[0]);
  }

  @Test
  void successfulReversePrefixSurvivesFailureAtEarlierMatchingChildList() {
    Object target = new Object();
    var bad = new ChildEntities();
    bad.size = 1;
    var good = children(1);
    var earlier = entry(target, bad);
    var s = state(earlier, entry(target, good));
    assertThrows(IllegalArgumentException.class, () -> removeBoost(s, target));
    assertEquals(1, s.boost.size);
    assertSame(earlier, s.boost.entries[0]);
    assertEquals(1, s.boost.version);
    assertEquals(0, good.size);
    assertNull(good.entries[0]);
  }

  @Test
  void childClearOnlyZerosLivePrefixAndLeavesCapacityTail() {
    var c = children(1, 2, 3);
    c.size = 2;
    clearChildren(entry(null, c));
    assertArrayEquals(new Object[] {null, null, 3}, c.entries);
  }

  @Test
  void childVersionWrapIsPreserved() {
    var c = children(1);
    c.version = Integer.MAX_VALUE;
    clearChildren(entry(null, c));
    assertEquals(Integer.MIN_VALUE, c.version);
  }

  @Test
  void outerVersionWrapIsPreserved() {
    var s = state(entry(null, null));
    s.boost.version = Integer.MAX_VALUE;
    removeBoost(s, null);
    assertEquals(Integer.MIN_VALUE, s.boost.version);
  }

  @Test
  void invalidUnsignedRemovalIndexDoesNotDecreaseCount() {
    var list = boost(entry(null, null));
    assertThrows(IndexOutOfBoundsException.class, () -> removeAt(list, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> removeAt(list, 1));
    assertEquals(1, list.size);
    assertEquals(0, list.version);
  }

  @Test
  void missingOuterStorageFailsAfterCountDecrementInRemoveAt() {
    var list = new BoostEntries();
    list.size = 1;
    assertThrows(IllegalArgumentException.class, () -> removeAt(list, 0));
    assertEquals(0, list.size);
    assertEquals(0, list.version);
  }

  @Test
  void shortShiftStorageKeepsDecreasedCountAndDoesNotIncrementVersion() {
    var list = boost(entry(null, null));
    list.size = 3;
    assertThrows(IndexOutOfBoundsException.class, () -> removeAt(list, 0));
    assertEquals(2, list.size);
    assertEquals(0, list.version);
  }

  @Test
  void outerCapacityBeyondLiveCountIsPreserved() {
    var a = entry(null, null);
    var tail = entry(new Object(), null);
    var list = boost(a, tail);
    list.size = 1;
    removeAt(list, 0);
    assertNull(list.entries[0]);
    assertSame(tail, list.entries[1]);
  }

  @Test
  void missingBoostStorageFailsBeforeChildClearOrOuterCounterWrite() {
    var s = ring();
    s.boost = new BoostEntries();
    s.boost.size = 1;
    assertThrows(IllegalArgumentException.class, () -> removeBoost(s, null));
    assertEquals(1, s.boost.size);
    assertEquals(0, s.boost.version);
  }

  @Test
  void excessiveInitialBoostCountIsClosedBeforeAnyArrayRead() {
    var s = ring();
    s.boost = new BoostEntries();
    s.boost.size = 256;
    assertThrows(IllegalArgumentException.class, () -> removeBoost(s, null));
    assertEquals(256, s.boost.size);
  }

  @Test
  void excessiveChildClearKeepsNativeHeaderPrefixButDoesNotTouchStorage() {
    var c = children(1);
    c.size = 256;
    assertThrows(IllegalArgumentException.class, () -> clearChildren(entry(null, c)));
    assertEquals(0, c.size);
    assertEquals(1, c.version);
    assertEquals(1, c.entries[0]);
  }

  @Test
  void nullInputBindingsAreClosed() {
    assertThrows(IllegalArgumentException.class, () -> removeAdditional(null, 1));
    assertThrows(IllegalArgumentException.class, () -> removeNode(null, new Node()));
    assertThrows(IllegalArgumentException.class, () -> removeNode(new AdditionalList(), null));
    assertThrows(IllegalArgumentException.class, () -> removeBoost(null, null));
    assertThrows(IllegalArgumentException.class, () -> clearChildren(null));
    assertThrows(IllegalArgumentException.class, () -> removeAt(null, 0));
  }

  @Test
  void actualOuterStackedEffectsWithdrawAdditionalRegistrationsAndBoostIdentity() {
    var withdrawalModule = ring(71, 72);
    var effects = RogueModifierStackedEffects46Test.state();
    var target = effects.stack.replacement;
    var child = children(new Object());
    withdrawalModule.boost = boost(entry(target, child), entry(new Object(), null));
    effects.stack.stackedAdditionalTeamBp =
        new ArrayList<>(
            List.of(
                new RogueModifierStackedEffects46.AdditionalBp(
                    RogueModifierStackedEffects46Test.TEAM, 71)));
    effects.boostContexts = new RogueModifierStackedEffects46.BoostContexts();
    effects.boostContexts.add(RogueModifierStackedEffects46Test.TEAM);
    var w =
        new RogueModifierStackedEffects46Test.World() {
          public Object teamBoostPointModule(Object team) {
            return withdrawalModule;
          }

          public void removeAdditionalTeamBp(Object m, int id) {
            removeAdditional((State) m, id);
          }

          public void removeBoostCallback(Object m, RogueModifierReplace46.State modifier) {
            removeBoost((State) m, modifier);
          }
        };
    RogueModifierStackedEffects46.popAdditionalTeamBp(effects, w);
    RogueModifierStackedEffects46.popBoostCallbacks(effects, w);
    assertEquals(72, withdrawalModule.additional.head.registrationId);
    assertEquals(1, withdrawalModule.boost.size);
    assertEquals(0, child.size);
    assertTrue(effects.stack.stackedAdditionalTeamBp.isEmpty());
    assertTrue(effects.boostContexts.isEmpty());
  }
}
