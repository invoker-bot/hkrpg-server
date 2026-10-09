package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.gameplay.RogueOwnedParameterLists46.References;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RogueFadeListCapacity46Test {
  static class World implements RogueFadeListCapacity46.World {
    List<String> calls = new ArrayList<>();
    Object[] allocated, empty = new Object[0], copiedSource;
    int copyCount;
    boolean nullAllocation;
    Runnable allocateAction = () -> {}, copyAction = () -> {}, emptyAction = () -> {};

    public Object[] allocate(int count) {
      calls.add("allocate");
      allocateAction.run();
      return nullAllocation ? null : (allocated = new Object[count]);
    }

    public Object[] emptyArray() {
      calls.add("empty");
      emptyAction.run();
      return empty;
    }

    public void copy(Object[] source, int from, Object[] destination, int to, int count) {
      calls.add("copy");
      copiedSource = source;
      copyCount = count;
      System.arraycopy(source, from, destination, to, count);
      copyAction.run();
    }
  }

  @Test
  void nullListFailsBeforeBindings() {
    var world = new World();
    assertThrows(
        IllegalArgumentException.class, () -> RogueFadeListCapacity46.setCapacity(null, 2, world));
    assertTrue(world.calls.isEmpty());
  }

  @Test
  void capacityBelowCountFailsBeforeMissingStorage() {
    var list = new References(null, 3, 9);
    assertThrows(
        IllegalArgumentException.class, () -> RogueFadeListCapacity46.setCapacity(list, 2, null));
    assertEquals(3, list.size);
    assertEquals(9, list.version);
  }

  @Test
  void matchingCapacityReturnsWithoutBindingsOrVersionChange() {
    var list = new References(new Object[2], 1, 9);
    Object[] old = list.entries;
    RogueFadeListCapacity46.setCapacity(list, 2, null);
    assertSame(old, list.entries);
    assertEquals(9, list.version);
  }

  @Test
  void missingStorageFailsEvenForZeroCapacity() {
    var world = new World();
    var list = new References(null, 0, 9);
    assertThrows(
        IllegalArgumentException.class, () -> RogueFadeListCapacity46.setCapacity(list, 0, world));
    assertTrue(world.calls.isEmpty());
  }

  @Test
  void positiveCapacityCopiesCurrentCountAndPreservesVersion() {
    var world = new World();
    Object first = new Object(), second = new Object();
    var list = new References(new Object[] {first, second, new Object()}, 2, 9);
    RogueFadeListCapacity46.setCapacity(list, 5, world);
    assertSame(first, list.entries[0]);
    assertSame(second, list.entries[1]);
    assertNull(list.entries[2]);
    assertEquals(2, list.size);
    assertEquals(9, list.version);
    assertEquals(2, world.copyCount);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void nonpositiveCurrentCountSkipsCopy(int count) {
    var world = new World();
    var list = new References(new Object[1], count, 9);
    RogueFadeListCapacity46.setCapacity(list, 3, world);
    assertEquals(List.of("allocate"), world.calls);
    assertEquals(count, list.size);
  }

  @Test
  void allocationChangesCountAndStorageBeforeCopy() {
    var world = new World();
    var list = new References(new Object[1], 0, 9);
    Object value = new Object();
    Object[] replacement = {value, null};
    world.allocateAction =
        () -> {
          list.entries = replacement;
          list.size = 1;
        };
    RogueFadeListCapacity46.setCapacity(list, 4, world);
    assertSame(replacement, world.copiedSource);
    assertSame(value, list.entries[0]);
    assertEquals(1, world.copyCount);
  }

  @Test
  void allocationChangingCountToNegativeSkipsCopyAndRetainsCount() {
    var world = new World();
    var list = new References(new Object[1], 1, 9);
    world.allocateAction = () -> list.size = -3;
    RogueFadeListCapacity46.setCapacity(list, 3, world);
    assertEquals(-3, list.size);
    assertEquals(List.of("allocate"), world.calls);
  }

  @Test
  void copyCanMutateCountAndVersionWithoutBeingReset() {
    var world = new World();
    var list = new References(new Object[1], 1, 9);
    world.copyAction =
        () -> {
          list.size = 2;
          list.version = 15;
        };
    RogueFadeListCapacity46.setCapacity(list, 3, world);
    assertEquals(2, list.size);
    assertEquals(15, list.version);
    assertSame(world.allocated, list.entries);
  }

  @Test
  void copyFailureKeepsBoundMutationAndDoesNotPublishAllocated() {
    var world = new World();
    var list = new References(new Object[1], 1, 9);
    Object[] changed = new Object[2];
    world.copyAction =
        () -> {
          list.entries = changed;
          throw new IllegalStateException("copy");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueFadeListCapacity46.setCapacity(list, 3, world));
    assertSame(changed, list.entries);
  }

  @Test
  void nullAllocationWithNoCopyPublishesNull() {
    var world = new World();
    world.nullAllocation = true;
    var list = new References(new Object[1], 0, 9);
    RogueFadeListCapacity46.setCapacity(list, 3, world);
    assertNull(list.entries);
    assertEquals(9, list.version);
  }

  @Test
  void nullAllocationWithPositiveCountFailsDuringCopyBeforePublication() {
    var world = new World();
    world.nullAllocation = true;
    var list = new References(new Object[1], 1, 9);
    Object[] old = list.entries;
    assertThrows(
        NullPointerException.class, () -> RogueFadeListCapacity46.setCapacity(list, 3, world));
    assertSame(old, list.entries);
    assertEquals(List.of("allocate", "copy"), world.calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void nonpositiveRequestedCapacityUsesSameSharedEmptyArray(int capacity) {
    var world = new World();
    var list = new References(new Object[1], capacity, 9);
    RogueFadeListCapacity46.setCapacity(list, capacity, world);
    assertSame(world.empty, list.entries);
    assertEquals(capacity, list.size);
    assertEquals(9, list.version);
    assertEquals(List.of("empty"), world.calls);
  }

  @Test
  void nullSharedEmptyIsPublishedWithoutInventingArray() {
    var world = new World();
    world.empty = null;
    var list = new References(new Object[1], 0, 9);
    RogueFadeListCapacity46.setCapacity(list, 0, world);
    assertNull(list.entries);
  }

  @Test
  void emptyArrayBindingFailurePreservesItsPrefix() {
    var world = new World();
    var list = new References(new Object[1], 0, 9);
    world.emptyAction =
        () -> {
          list.size = -1;
          throw new IllegalStateException("empty");
        };
    assertThrows(
        IllegalStateException.class, () -> RogueFadeListCapacity46.setCapacity(list, 0, world));
    assertEquals(-1, list.size);
    assertEquals(1, list.entries.length);
  }

  @Test
  void finiteAllocationStopsBeforeFactory() {
    var world = new World();
    var list = new References(new Object[1], 0, 9);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueFadeListCapacity46.setCapacity(list, 4097, world));
    assertTrue(world.calls.isEmpty());
  }

  @Test
  void exactLargeExistingCapacitySkipsFiniteAllocationPolicy() {
    var list = new References(new Object[4097], 0, 9);
    RogueFadeListCapacity46.setCapacity(list, 4097, null);
    assertEquals(4097, list.entries.length);
  }

  @Test
  void actualFadeInitializerUsesActualCapacitySetterAndKeepsTwoVersions() {
    var arrays = new World();
    var event = RogueAttachEffectFade46Test.event(0, 0, 9);
    Object effect = new Object();
    var world =
        new RogueAttachEffectFade46Test.World() {
          public void setCapacity(References list, int capacity) {
            RogueFadeListCapacity46.setCapacity(list, capacity, arrays);
          }
        };
    RogueAttachEffectFade46.initialize(event, null, effect, null, world);
    assertEquals(4, RogueAttachEffectFade46Test.effects(event).entries.length);
    assertEquals(1, RogueAttachEffectFade46Test.effects(event).size);
    assertEquals(11, RogueAttachEffectFade46Test.effects(event).version);
    assertSame(effect, RogueAttachEffectFade46Test.effects(event).entries[0]);
  }
}
