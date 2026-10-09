package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueEventListenerWithdrawal46Test {
  static RogueEventListenerWithdrawal46.State state(RogueBaseModifierDestroy46.Callback... values) {
    var s = new RogueEventListenerWithdrawal46.State();
    s.entityEvents = new RogueEventListenerWithdrawal46.Dictionary();
    var e = new RogueEventListenerWithdrawal46.Entry();
    e.key = new RogueEventListenerWithdrawal46.Key(7, 207);
    e.callbacks = new RogueEventListenerWithdrawal46.PooledCallbacks();
    e.callbacks.collection = new ArrayList<>(java.util.Arrays.asList(values));
    s.entityEvents.entries = new RogueEventListenerWithdrawal46.Entry[] {e};
    return s;
  }

  @SuppressWarnings("unchecked")
  static List<Object> values(RogueEventListenerWithdrawal46.State s, int index) {
    return (List<Object>) s.entityEvents.entries[index].callbacks.collection;
  }

  static class World extends RogueEventDelegateEquality46Test.World
      implements RogueEventListenerWithdrawal46.World {
    final List<String> order = new ArrayList<>();
    RogueEventListenerWithdrawal46.Dictionary queried;
    RogueEventListenerWithdrawal46.Key key;
    Runnable onFind = () -> {}, onRemove = () -> {};
    Integer forcedIndex;

    public int findEntryIndex(
        RogueEventListenerWithdrawal46.Dictionary d, RogueEventListenerWithdrawal46.Key value) {
      order.add("find");
      queried = d;
      key = value;
      onFind.run();
      if (forcedIndex != null) return forcedIndex;
      if (d.entries == null) return -1;
      for (int i = 0; i < d.entries.length; i++)
        if (d.entries[i] != null && value.equals(d.entries[i].key)) return i;
      return -1;
    }

    @SuppressWarnings("unchecked")
    public boolean removeCollectionMember(
        Object collection, RogueBaseModifierDestroy46.Callback value) {
      order.add("remove");
      onRemove.run();
      List<Object> list = (List<Object>) collection;
      for (int i = 0; i < list.size(); i++) {
        Object candidate = list.get(i);
        boolean equal =
            candidate == null
                ? value == null
                : candidate instanceof RogueBaseModifierDestroy46.Callback c
                    && RogueEventDelegateEquality46.multicastEquals(c, value, this);
        if (equal) {
          list.remove(i);
          return true;
        }
      }
      return false;
    }
  }

  @Test
  void nullMapReturnsWithoutWorldLookupOrCallbackRequirements() {
    RogueEventListenerWithdrawal46.unregister(
        new RogueEventListenerWithdrawal46.State(), 207, 7, null, null);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, -2, Integer.MIN_VALUE})
  void missingNegativeIndexSkipsStorageAndPooledCollection(int index) {
    var s = state();
    var w = new World();
    w.forcedIndex = index;
    s.entityEvents.entries = null;
    RogueEventListenerWithdrawal46.unregister(s, 207, 7, null, w);
    assertEquals(List.of("find"), w.order);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void rawKeyPacksEventHighAndUnsignedEntityLowWithoutLosingBits(int id) {
    for (int event : new int[] {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 207, 208}) {
      var key = new RogueEventListenerWithdrawal46.Key(id, event);
      assertEquals(Integer.toUnsignedLong(id), key.raw() & 0xffffffffL);
      assertEquals(Integer.toUnsignedLong(event), key.raw() >>> 32);
    }
    var s = state();
    var w = new World();
    w.forcedIndex = -1;
    RogueEventListenerWithdrawal46.unregister(s, 208, id, null, w);
    assertEquals(new RogueEventListenerWithdrawal46.Key(id, 208), w.key);
  }

  @Test
  void absentEventOrEntityKeyDoesNotEnterCollectionRemove() {
    var s = state();
    var w = new World();
    RogueEventListenerWithdrawal46.unregister(s, 208, 7, null, w);
    RogueEventListenerWithdrawal46.unregister(s, 207, 8, null, w);
    assertEquals(List.of("find", "find"), w.order);
  }

  @Test
  void dictionaryReplacementDuringLookupKeepsCapturedDictionaryEntries() {
    var target = RogueEventDelegateEquality46Test.callback(null);
    var s = state(target);
    var w = new World();
    var captured = s.entityEvents;
    var replacement = state().entityEvents;
    w.onFind = () -> s.entityEvents = replacement;
    w.forcedIndex = 0;
    RogueEventListenerWithdrawal46.unregister(s, 207, 7, target, w);
    assertSame(captured, w.queried);
    assertTrue(((List<?>) captured.entries[0].callbacks.collection).isEmpty());
    assertSame(replacement, s.entityEvents);
  }

  @Test
  void capturedDictionaryEntriesAreRereadAfterLookupCallback() {
    var target = RogueEventDelegateEquality46Test.callback(null);
    var s = state();
    var replacement = state(target);
    var w = new World();
    w.forcedIndex = 0;
    w.onFind = () -> s.entityEvents.entries = replacement.entityEvents.entries;
    RogueEventListenerWithdrawal46.unregister(s, 207, 7, target, w);
    assertTrue(values(s, 0).isEmpty());
  }

  @Test
  void nullEntriesAfterFoundIndexFailsAfterLookup() {
    var s = state();
    var w = new World();
    w.forcedIndex = 0;
    s.entityEvents.entries = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventListenerWithdrawal46.unregister(s, 207, 7, null, w));
    assertEquals(List.of("find"), w.order);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, Integer.MAX_VALUE})
  void foundIndexOutsideCurrentEntryArrayFailsWithoutRemove(int index) {
    var s = state();
    var w = new World();
    w.forcedIndex = index;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventListenerWithdrawal46.unregister(s, 207, 7, null, w));
    assertEquals(List.of("find"), w.order);
  }

  @Test
  void nullExplicitEntryIsRejectedWithoutInventingDefaultPooledValue() {
    var s = state();
    var w = new World();
    w.forcedIndex = 0;
    s.entityEvents.entries[0] = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventListenerWithdrawal46.unregister(s, 207, 7, null, w));
    assertEquals(List.of("find"), w.order);
  }

  @Test
  void nullPooledValueFailsAfterFoundEntry() {
    var s = state();
    var w = new World();
    s.entityEvents.entries[0].callbacks = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventListenerWithdrawal46.unregister(s, 207, 7, null, w));
    assertEquals(List.of("find"), w.order);
  }

  @Test
  void missingInnerCollectionFailsBeforeInterfaceRemove() {
    var s = state();
    var w = new World();
    s.entityEvents.entries[0].callbacks.collection = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventListenerWithdrawal46.unregister(s, 207, 7, null, w));
    assertEquals(List.of("find"), w.order);
  }

  @Test
  void lookupFailureKeepsOperatorCallbackListUntouched() {
    var target = RogueEventDelegateEquality46Test.callback(null);
    var s = state(target);
    var w = new World();
    w.onFind =
        () -> {
          throw new IllegalStateException("find");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueEventListenerWithdrawal46.unregister(s, 207, 7, target, w));
    assertEquals(List.of(target), values(s, 0));
    assertEquals(List.of("find"), w.order);
  }

  @Test
  void interfaceRemoveFailureKeepsCallbackSideEffectsWithoutRollback() {
    var first = RogueEventDelegateEquality46Test.callback(null);
    var second = RogueEventDelegateEquality46Test.callback(null);
    var s = state(first, second);
    var w = new World();
    w.onRemove =
        () -> {
          values(s, 0).remove(0);
          throw new IllegalStateException("remove");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueEventListenerWithdrawal46.unregister(s, 207, 7, first, w));
    assertEquals(List.of(second), values(s, 0));
  }

  @Test
  void distinctNewCallbackMatchesStoredTargetAndMethodUsingActualDelegateEquality() {
    var target = RogueModifierDestroy46Test.state();
    var stored = RogueEventDelegateEquality46Test.callback(target);
    var requested = RogueEventDelegateEquality46Test.callback(target);
    var s = state(stored);
    var w = new World();
    RogueEventListenerWithdrawal46.unregister(s, 207, 7, requested, w);
    assertNotSame(stored, requested);
    assertTrue(values(s, 0).isEmpty());
    assertEquals(List.of(requested, stored), w.methods);
    assertEquals(List.of("find", "remove"), w.order);
  }

  @Test
  void onlyFirstEquivalentCallbackRemovedAndOtherReferencesKeepOrder() {
    var target = RogueModifierDestroy46Test.state();
    var first = RogueEventDelegateEquality46Test.callback(target);
    var second = RogueEventDelegateEquality46Test.callback(target);
    var other = RogueEventDelegateEquality46Test.callback(RogueModifierDestroy46Test.state());
    var requested = RogueEventDelegateEquality46Test.callback(target);
    var s = state(other, first, second);
    var w = new World();
    RogueEventListenerWithdrawal46.unregister(s, 207, 7, requested, w);
    assertEquals(List.of(other, second), values(s, 0));
  }

  @Test
  void differentTargetOrMethodKeepsBothListeners() {
    var target = RogueModifierDestroy46Test.state();
    var stored = RogueEventDelegateEquality46Test.callback(target);
    var other = RogueEventDelegateEquality46Test.callback(null);
    var requested = RogueEventDelegateEquality46Test.callback(target);
    requested.methodInfo = new Object();
    var s = state(stored, other);
    var w = new World();
    RogueEventListenerWithdrawal46.unregister(s, 207, 7, requested, w);
    assertEquals(List.of(stored, other), values(s, 0));
  }

  @Test
  void nullRequestedCallbackIsPassedThroughAndRemovesFirstNullItem() {
    var stored = RogueEventDelegateEquality46Test.callback(null);
    var s = state(null, stored, null);
    var w = new World();
    RogueEventListenerWithdrawal46.unregister(s, 207, 7, null, w);
    assertEquals(2, values(s, 0).size());
    assertSame(stored, values(s, 0).get(0));
    assertNull(values(s, 0).get(1));
    assertTrue(w.methods.isEmpty());
  }

  @Test
  void directPooledRemoveReturnsActualInterfaceBoolean() {
    var stored = RogueEventDelegateEquality46Test.callback(null);
    var s = state(stored);
    var w = new World();
    var wrapper = s.entityEvents.entries[0].callbacks;
    assertTrue(RogueEventListenerWithdrawal46.remove(wrapper, stored, w));
    assertFalse(RogueEventListenerWithdrawal46.remove(wrapper, stored, w));
  }

  @Test
  void exactFiniteDictionaryCapacityAllowsLastSlotAndExcessIsRejected() {
    for (int count : new int[] {4096, 4097}) {
      var s = state();
      var w = new World();
      var entry = s.entityEvents.entries[0];
      s.entityEvents.entries = new RogueEventListenerWithdrawal46.Entry[count];
      s.entityEvents.entries[count - 1] = entry;
      w.forcedIndex = count - 1;
      if (count == 4096) {
        RogueEventListenerWithdrawal46.unregister(s, 207, 7, null, w);
        assertEquals(List.of("find", "remove"), w.order);
      } else {
        assertThrows(
            IllegalArgumentException.class,
            () -> RogueEventListenerWithdrawal46.unregister(s, 207, 7, null, w));
        assertEquals(List.of("find"), w.order);
      }
    }
  }

  @Test
  void callbackEqualityFailureStopsRemovalAndKeepsCurrentListPrefix() {
    var stored = RogueEventDelegateEquality46Test.callback(null);
    var requested = RogueEventDelegateEquality46Test.callback(null);
    var s = state(stored);
    var w = new World();
    w.onMethod =
        c -> {
          throw new IllegalStateException("method");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueEventListenerWithdrawal46.unregister(s, 207, 7, requested, w));
    assertEquals(List.of(stored), values(s, 0));
  }

  @Test
  void actualBaseWithdrawalRemovesRegisteredDestroyAndFadeListenersFromSharedManager() {
    var modifier = RogueModifierDestroy46Test.state();
    var seed = new RogueBaseModifierDestroy46Test.World();
    RogueBaseModifierDestroy46.destroy(modifier, 4, seed);
    var listenerManager = state(seed.withdrawn.get(0).callback());
    var second = state(seed.withdrawn.get(1).callback()).entityEvents.entries[0];
    second.key = new RogueEventListenerWithdrawal46.Key(7, 208);
    listenerManager.entityEvents.entries =
        new RogueEventListenerWithdrawal46.Entry[] {
          listenerManager.entityEvents.entries[0], second
        };
    var eventWorld = new World();
    var base =
        new RogueBaseModifierDestroy46Test.World() {
          @Override
          public void unregisterEventListener(
              Object owner, int event, int id, RogueBaseModifierDestroy46.Callback callback) {
            super.unregisterEventListener(owner, event, id, callback);
            RogueEventListenerWithdrawal46.unregister(
                listenerManager, event, id, callback, eventWorld);
          }
        };
    RogueBaseModifierDestroy46.destroy(modifier, 4, base);
    assertTrue(values(listenerManager, 0).isEmpty());
    assertTrue(values(listenerManager, 1).isEmpty());
    assertEquals(List.of("find", "remove", "find", "remove"), eventWorld.order);
  }

  @Test
  void actualBaseSecondListenerFailureLeavesFirstRemovedAndSecondRegistered() {
    var modifier = RogueModifierDestroy46Test.state();
    var seed = new RogueBaseModifierDestroy46Test.World();
    RogueBaseModifierDestroy46.destroy(modifier, 4, seed);
    var listenerManager = state(seed.withdrawn.get(0).callback());
    var second = state(seed.withdrawn.get(1).callback()).entityEvents.entries[0];
    second.key = new RogueEventListenerWithdrawal46.Key(7, 208);
    listenerManager.entityEvents.entries =
        new RogueEventListenerWithdrawal46.Entry[] {
          listenerManager.entityEvents.entries[0], second
        };
    var eventWorld = new World();
    var base =
        new RogueBaseModifierDestroy46Test.World() {
          @Override
          public void unregisterEventListener(
              Object owner, int event, int id, RogueBaseModifierDestroy46.Callback callback) {
            super.unregisterEventListener(owner, event, id, callback);
            if (event == 208)
              eventWorld.onRemove =
                  () -> {
                    throw new IllegalStateException("second");
                  };
            RogueEventListenerWithdrawal46.unregister(
                listenerManager, event, id, callback, eventWorld);
          }
        };
    assertThrows(
        IllegalStateException.class, () -> RogueBaseModifierDestroy46.destroy(modifier, 4, base));
    assertTrue(values(listenerManager, 0).isEmpty());
    assertEquals(1, values(listenerManager, 1).size());
    assertEquals(4, modifier.deathSource);
  }

  @Test
  void pooledRemovalKeepsCapturedCollectionWhenCallbackReplacesWrapperMember() {
    var stored = RogueEventDelegateEquality46Test.callback(null);
    var s = state(stored);
    var w = new World();
    var wrapper = s.entityEvents.entries[0].callbacks;
    var original = values(s, 0);
    var replacement = new ArrayList<>(List.of(stored));
    w.onRemove = () -> wrapper.collection = replacement;
    assertTrue(RogueEventListenerWithdrawal46.remove(wrapper, stored, w));
    assertTrue(original.isEmpty());
    assertEquals(List.of(stored), replacement);
    assertSame(replacement, wrapper.collection);
  }

  @Test
  void foundEntryDoesNotRecheckKeyAfterBoundLookupReturnsIndex() {
    var stored = RogueEventDelegateEquality46Test.callback(null);
    var s = state(stored);
    var w = new World();
    w.forcedIndex = 0;
    s.entityEvents.entries[0].key = new RogueEventListenerWithdrawal46.Key(99, 208);
    RogueEventListenerWithdrawal46.unregister(s, 207, 7, stored, w);
    assertTrue(values(s, 0).isEmpty());
    assertEquals(new RogueEventListenerWithdrawal46.Key(7, 207), w.key);
  }

  @Test
  void emptiedCallbackCollectionKeepsDictionaryEntryAndEmptyRepeatDoesNotDeleteIt() {
    var stored = RogueEventDelegateEquality46Test.callback(null);
    var s = state(stored);
    var w = new World();
    var entry = s.entityEvents.entries[0];
    RogueEventListenerWithdrawal46.unregister(s, 207, 7, stored, w);
    RogueEventListenerWithdrawal46.unregister(s, 207, 7, stored, w);
    assertSame(entry, s.entityEvents.entries[0]);
    assertTrue(values(s, 0).isEmpty());
    assertEquals(1, s.entityEvents.entries.length);
  }

  @Test
  void actualOrdinaryDestroyBaseAndManagerBodiesWithdrawBothListenersAfterEffectCleanup() {
    var modifier = RogueModifierDestroy46Test.state();
    var seed = new RogueBaseModifierDestroy46Test.World();
    RogueBaseModifierDestroy46.destroy(modifier, 4, seed);
    var listenerState = state(seed.withdrawn.get(0).callback());
    var fade = state(seed.withdrawn.get(1).callback()).entityEvents.entries[0];
    fade.key = new RogueEventListenerWithdrawal46.Key(7, 208);
    listenerState.entityEvents.entries =
        new RogueEventListenerWithdrawal46.Entry[] {listenerState.entityEvents.entries[0], fade};
    var eventWorld = new World();
    var base =
        new RogueBaseModifierDestroy46Test.World() {
          @Override
          public void unregisterEventListener(
              Object owner, int event, int id, RogueBaseModifierDestroy46.Callback callback) {
            super.unregisterEventListener(owner, event, id, callback);
            RogueEventListenerWithdrawal46.unregister(
                listenerState, event, id, callback, eventWorld);
          }
        };
    var ordinary = new RogueModifierDestroy46Test.World();
    ordinary.hooks.put(
        "baseDestroy",
        c ->
            RogueBaseModifierDestroy46.destroy(
                (RogueModifierDestroy46.State) c.args()[0], (int) c.args()[1], base));
    RogueModifierDestroy46.destroy(modifier, 4, ordinary.bound);
    assertEquals(2, modifier.cleanup.candidate.state);
    assertNull(modifier.cleanup.effects);
    assertTrue(values(listenerState, 0).isEmpty());
    assertTrue(values(listenerState, 1).isEmpty());
    assertTrue(
        ordinary.names().indexOf("detachAllEffects") < ordinary.names().indexOf("baseDestroy"));
  }

  @Test
  void nullStateAndMissingNonemptyWorldAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventListenerWithdrawal46.unregister(null, 207, 7, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueEventListenerWithdrawal46.unregister(state(), 207, 7, null, null));
  }
}
