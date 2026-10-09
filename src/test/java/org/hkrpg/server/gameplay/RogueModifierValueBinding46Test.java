package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RogueModifierValueBinding46Test {
  record Write(Object values, int key, long raw) {}

  static class BoundWorld implements RogueModifierValueBinding46.World {
    final List<Write> writes = new ArrayList<>();

    public void setVariable(Object values, int key, long raw) {
      writes.add(new Write(values, key, raw));
    }
  }

  static RogueModifierValueBinding46.State state() {
    var lifetime = new RogueModifierLifetime46.State(new Object(), 7, 3);
    var modifier = new RogueModifierStackMode46.State(lifetime, 5, 2);
    var state = new RogueModifierValueBinding46.State(modifier);
    state.keys = new int[] {11, 22, 33, 44};
    state.mask = 15;
    state.dynamicValues = new Object();
    state.baseMaxLayer = 7;
    state.extraMaxLayer = 2;
    return state;
  }

  @Test
  void integerAtStandardBoundaryUsesExtendedTaggedEncoding() {
    assertEquals((1L << 56) | 1L, RogueModifierValueBinding46.integerRaw(0x40000000));
  }

  @Test
  void maximumLayerAdditionWrapsBeforeTheMinimumOfOne() {
    var state = state();
    var world = new BoundWorld();
    state.baseMaxLayer = Integer.MAX_VALUE;
    state.extraMaxLayer = 1;
    RogueModifierValueBinding46.notifyValue(state, 2, world);
    assertEquals(List.of(new Write(state.dynamicValues, 33, 1L << 33)), world.writes);
  }

  @Test
  void absentVariableKeySkipsUnmatchedRawValueBeforeWriting() {
    var variables =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(), change -> fail("No absent-key notification"));
    assertDoesNotThrow(
        () -> RogueModifierValueBinding46.standardVariables().setVariable(variables, 111, 1L));
    assertTrue(variables.snapshot().isEmpty());
  }

  @Test
  void allFourSlotsUseCurrentFieldAndTheirOwnKeys() {
    var state = state();
    var world = new BoundWorld();
    for (int type = 0; type < 4; type++)
      RogueModifierValueBinding46.notifyValue(state, type, world);
    assertEquals(
        List.of(
            new Write(state.dynamicValues, 11, 2L << 33),
            new Write(state.dynamicValues, 22, 5L << 33),
            new Write(state.dynamicValues, 33, 9L << 33),
            new Write(state.dynamicValues, 44, 3L << 33)),
        world.writes);
  }

  @Test
  void absentArraySkipsMissingContainerAndWriter() {
    var state = state();
    state.keys = null;
    state.dynamicValues = null;
    assertDoesNotThrow(() -> RogueModifierValueBinding46.notifyValue(state, 0, null));
  }

  @Test
  void disabledMaskSkipsShortArrayMissingContainerAndWriter() {
    var state = state();
    state.mask = 0;
    state.keys = new int[0];
    state.dynamicValues = null;
    assertDoesNotThrow(() -> RogueModifierValueBinding46.notifyValue(state, 3, null));
  }

  @Test
  void eachMaskBitEnablesOnlyItsOwnValueSlot() {
    for (int enabled = 0; enabled < 4; enabled++) {
      var state = state();
      state.mask = 1 << enabled;
      var world = new BoundWorld();
      for (int type = 0; type < 4; type++)
        RogueModifierValueBinding46.notifyValue(state, type, world);
      assertEquals(1, world.writes.size());
      assertEquals(state.keys[enabled], world.writes.getFirst().key());
    }
  }

  @Test
  void unknownTypesSkipEvenWithMatchingWrappedMaskBit() {
    var state = state();
    state.mask = -1;
    state.keys = new int[0];
    state.dynamicValues = null;
    for (int type : List.of(-1, Integer.MIN_VALUE, 4, 31, 32, Integer.MAX_VALUE))
      assertDoesNotThrow(() -> RogueModifierValueBinding46.notifyValue(state, type, null));
  }

  @Test
  void enabledShortKeyArrayFailsBeforeAnyWrite() {
    for (int type = 0; type < 4; type++) {
      var state = state();
      var world = new BoundWorld();
      state.keys = new int[type];
      int requested = type;
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueModifierValueBinding46.notifyValue(state, requested, world));
      assertTrue(world.writes.isEmpty());
    }
  }

  @Test
  void enabledMissingDynamicContainerFailsBeforeWriter() {
    var state = state();
    state.dynamicValues = null;
    var world = new BoundWorld();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierValueBinding46.notifyValue(state, 0, world));
    assertTrue(world.writes.isEmpty());
  }

  @Test
  void missingModifierAndBindingStateFailClosed() {
    assertThrows(IllegalArgumentException.class, () -> new RogueModifierValueBinding46.State(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierValueBinding46.notifyValue(null, 0, null));
  }

  @Test
  void enabledMissingWriterFailsWithoutChangingModifierValues() {
    var state = state();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierValueBinding46.notifyValue(state, 0, null));
    assertEquals(2, state.modifier.layer);
    assertEquals(5, state.modifier.count);
    assertEquals(3, state.modifier.lifetime.currentLife);
  }

  @Test
  void zeroCurrentValueIsStillWrittenWhenEnabled() {
    var state = state();
    state.modifier.layer = 0;
    var world = new BoundWorld();
    RogueModifierValueBinding46.notifyValue(state, 0, world);
    assertEquals(0L, world.writes.getFirst().raw());
  }

  @Test
  void negativeLayerIsEncodedWithoutClamping() {
    var state = state();
    state.modifier.layer = -5;
    var world = new BoundWorld();
    RogueModifierValueBinding46.notifyValue(state, 0, world);
    assertEquals(-5L << 33, world.writes.getFirst().raw());
  }

  @Test
  void negativeCountIsEncodedWithoutClamping() {
    var state = state();
    state.modifier.count = -5;
    var world = new BoundWorld();
    RogueModifierValueBinding46.notifyValue(state, 1, world);
    assertEquals(-5L << 33, world.writes.getFirst().raw());
  }

  @Test
  void minimumLifeIntegerKeepsExtendedSignedEncoding() {
    var state = state();
    state.modifier.lifetime.currentLife = Integer.MIN_VALUE;
    var world = new BoundWorld();
    RogueModifierValueBinding46.notifyValue(state, 3, world);
    assertEquals((-1L << 57) | 1L, world.writes.getFirst().raw());
  }

  @Test
  void negativeMaxLayerSumBecomesOne() {
    var state = state();
    state.baseMaxLayer = -5;
    state.extraMaxLayer = 2;
    var world = new BoundWorld();
    RogueModifierValueBinding46.notifyValue(state, 2, world);
    assertEquals(1L << 33, world.writes.getFirst().raw());
  }

  @Test
  void exactMaxLayerSumOneRemainsOne() {
    var state = state();
    state.baseMaxLayer = -1;
    state.extraMaxLayer = 2;
    var world = new BoundWorld();
    RogueModifierValueBinding46.notifyValue(state, 2, world);
    assertEquals(1L << 33, world.writes.getFirst().raw());
  }

  @Test
  void exactMaxLayerSumTwoRemainsTwo() {
    var state = state();
    state.baseMaxLayer = -1;
    state.extraMaxLayer = 3;
    var world = new BoundWorld();
    RogueModifierValueBinding46.notifyValue(state, 2, world);
    assertEquals(2L << 33, world.writes.getFirst().raw());
  }

  @Test
  void negativeOverflowMaxLayerSumCanWrapToPositiveExtendedValue() {
    var state = state();
    state.baseMaxLayer = Integer.MIN_VALUE;
    state.extraMaxLayer = -1;
    var world = new BoundWorld();
    RogueModifierValueBinding46.notifyValue(state, 2, world);
    assertEquals(((long) Integer.MAX_VALUE << 26) | 1L, world.writes.getFirst().raw());
  }

  @Test
  void notificationsReadLatestFieldsRatherThanCreationValues() {
    var state = state();
    var world = new BoundWorld();
    state.modifier.layer = 19;
    state.keys[0] = 91;
    RogueModifierValueBinding46.notifyValue(state, 0, world);
    assertEquals(new Write(state.dynamicValues, 91, 19L << 33), world.writes.getFirst());
  }

  @Test
  void callbackMutationIsSeenByTheNextNotification() {
    var state = state();
    var world =
        new BoundWorld() {
          public void setVariable(Object values, int key, long raw) {
            super.setVariable(values, key, raw);
            state.modifier.layer = 19;
          }
        };
    RogueModifierValueBinding46.notifyValue(state, 0, world);
    RogueModifierValueBinding46.notifyValue(state, 0, world);
    assertEquals(List.of(2L << 33, 19L << 33), world.writes.stream().map(Write::raw).toList());
  }

  @Test
  void writerFailurePropagatesWithoutMutatingModifierState() {
    var state = state();
    var failure = new IllegalStateException("write");
    var world =
        new BoundWorld() {
          public void setVariable(Object values, int key, long raw) {
            super.setVariable(values, key, raw);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierValueBinding46.notifyValue(state, 0, world)));
    assertEquals(1, world.writes.size());
    assertEquals(2, state.modifier.layer);
    assertEquals(3, state.modifier.lifetime.currentLife);
  }

  @Test
  void lifeNotificationDoesNotChangeInitialLifeOrRenewTurn() {
    var state = state();
    state.modifier.lifetime.renewTurn = 19;
    RogueModifierValueBinding46.notifyValue(state, 3, new BoundWorld());
    assertEquals(7, state.modifier.lifetime.initialLife);
    assertEquals(19, state.modifier.lifetime.renewTurn);
  }

  @Test
  void highMaskBitDoesNotEnableKnownLowerSlots() {
    var state = state();
    state.mask = Integer.MIN_VALUE;
    var world = new BoundWorld();
    for (int type = 0; type < 4; type++)
      RogueModifierValueBinding46.notifyValue(state, type, world);
    assertTrue(world.writes.isEmpty());
  }

  @Test
  void largerKeyArrayDoesNotReadUnusedTail() {
    var state = state();
    state.keys = new int[1024];
    state.keys[3] = 99;
    var world = new BoundWorld();
    RogueModifierValueBinding46.notifyValue(state, 3, world);
    assertEquals(99, world.writes.getFirst().key());
  }

  @Test
  void zeroHashKeyIsPassedToExplicitWriter() {
    var state = state();
    state.keys[0] = 0;
    var world = new BoundWorld();
    RogueModifierValueBinding46.notifyValue(state, 0, world);
    assertEquals(0, world.writes.getFirst().key());
  }

  @Test
  void signedHashKeyIsPassedWithoutNormalization() {
    var state = state();
    state.keys[0] = Integer.MIN_VALUE;
    var world = new BoundWorld();
    RogueModifierValueBinding46.notifyValue(state, 0, world);
    assertEquals(Integer.MIN_VALUE, world.writes.getFirst().key());
  }

  @Test
  void integerZeroHasStandardZeroEncoding() {
    assertEquals(0L, RogueModifierValueBinding46.integerRaw(0));
  }

  @Test
  void smallNegativeIntegerHasStandardEncoding() {
    assertEquals(-7L << 33, RogueModifierValueBinding46.integerRaw(-7));
  }

  @Test
  void positiveIntegerBeforeBoundaryHasStandardEncoding() {
    assertEquals((0x3fffffffL << 33), RogueModifierValueBinding46.integerRaw(0x3fffffff));
  }

  @Test
  void negativeIntegerBeforeBoundaryHasStandardEncoding() {
    assertEquals((-0x3fffffffL << 33), RogueModifierValueBinding46.integerRaw(-0x3fffffff));
  }

  @Test
  void negativeIntegerAtBoundaryHasExtendedEncoding() {
    assertEquals((-1L << 56) | 1L, RogueModifierValueBinding46.integerRaw(-0x40000000));
  }

  @Test
  void maximumIntegerHasExtendedEncoding() {
    assertEquals(
        ((long) Integer.MAX_VALUE << 26) | 1L,
        RogueModifierValueBinding46.integerRaw(Integer.MAX_VALUE));
  }

  @Test
  void minimumIntegerHasExtendedEncoding() {
    assertEquals((-1L << 57) | 1L, RogueModifierValueBinding46.integerRaw(Integer.MIN_VALUE));
  }

  @Test
  void encodedIntegerRoundTripsAcrossBoundariesAndDeterministicSamples() {
    var values =
        new ArrayList<Integer>(
            List.of(
                Integer.MIN_VALUE,
                -0x40000001,
                -0x40000000,
                -0x3fffffff,
                -1,
                0,
                1,
                0x3fffffff,
                0x40000000,
                0x40000001,
                Integer.MAX_VALUE));
    var random = new java.util.Random(460510253);
    for (int i = 0; i < 256; i++) values.add(random.nextInt());
    for (int value : values) {
      long raw = RogueModifierValueBinding46.integerRaw(value);
      boolean extended = (raw & 1L) != 0;
      assertEquals(Math.abs((long) value) >= 0x40000000L, extended);
      assertEquals(value, (int) (raw >> (extended ? 26 : 33)));
    }
  }

  @Test
  void standardAdapterWritesKnownVariableWithClampAndObserver() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var variables =
        new RogueResonanceVariables46(
            Map.of(11, new RogueResonanceVariables46.Value(0, 0, 1L << 33)), changes::add);
    var state = state();
    state.dynamicValues = variables;
    RogueModifierValueBinding46.notifyValue(
        state, 0, RogueModifierValueBinding46.standardVariables());
    assertEquals(1L << 33, variables.read(11));
    assertEquals(List.of(new RogueResonanceVariables46.Change(11, 0, 1L << 33)), changes);
  }

  @Test
  void standardAdapterRejectsUnmatchedContainerType() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierValueBinding46.standardVariables().setVariable(new Object(), 11, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierValueBinding46.standardVariables().setVariable(null, 11, 0));
  }

  @Test
  void standardAdapterRejectsExtendedExistingCellBeforeNotification() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var variables = RogueResonanceVariables46.fromSourceDefaults(Map.of(11, 0L), changes::add);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierValueBinding46.standardVariables()
                .setVariable(variables, 11, (1L << 56) | 1));
    assertEquals(0L, variables.read(11));
    assertTrue(changes.isEmpty());
  }

  @Test
  void standardAdapterStillNotifiesWhenClampingKeepsCurrentValue() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var variables =
        new RogueResonanceVariables46(
            Map.of(11, new RogueResonanceVariables46.Value(1L << 33, 0, 1L << 33)), changes::add);
    var state = state();
    state.dynamicValues = variables;
    RogueModifierValueBinding46.notifyValue(
        state, 0, RogueModifierValueBinding46.standardVariables());
    assertEquals(List.of(new RogueResonanceVariables46.Change(11, 1L << 33, 1L << 33)), changes);
  }

  @Test
  void absentKeySkipsEncodedLargeIntegerWithoutOpeningMixedCells() {
    var state = state();
    state.modifier.layer = Integer.MIN_VALUE;
    var variables =
        RogueResonanceVariables46.fromSourceDefaults(Map.of(99, 0L), change -> fail("Absent key"));
    state.dynamicValues = variables;
    assertDoesNotThrow(
        () ->
            RogueModifierValueBinding46.notifyValue(
                state, 0, RogueModifierValueBinding46.standardVariables()));
    assertEquals(Map.of(99, 0L), variables.snapshot());
  }

  @Test
  void stackReplacementWritesLifeThenCountIntoKnownVariables() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var variables =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(22, 5L << 33, 44, 3L << 33), changes::add);
    var state = state();
    state.dynamicValues = variables;
    var order = new ArrayList<Integer>();
    var world =
        new RogueModifierStackMode46.World() {
          public void valueChanged(RogueModifierStackMode46.State s, int type) {
            assertSame(state.modifier, s);
            order.add(type);
            RogueModifierValueBinding46.notifyValue(
                state, type, RogueModifierValueBinding46.standardVariables());
          }

          public void onReplace(RogueModifierStackMode46.State s) {
            order.add(9);
          }
        };
    RogueModifierStackMode46.applyMode(state.modifier, 5, 9, 11, world);
    assertEquals(List.of(3, 1, 9), order);
    assertEquals(9L << 33, variables.read(44));
    assertEquals(11L << 33, variables.read(22));
    assertEquals(
        List.of(44, 22), changes.stream().map(RogueResonanceVariables46.Change::key).toList());
  }

  @Test
  void lifetimeAdvanceWritesTypeThreeIntoTheSameKnownVariableState() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var variables =
        RogueResonanceVariables46.fromSourceDefaults(Map.of(44, 3L << 33), changes::add);
    var state = state();
    state.dynamicValues = variables;
    var world =
        new RogueModifierLifetime46Test.BoundWorld() {
          public void valueChanged(RogueModifierLifetime46.State life, int type) {
            assertSame(state.modifier.lifetime, life);
            super.valueChanged(life, type);
            RogueModifierValueBinding46.notifyValue(
                state, type, RogueModifierValueBinding46.standardVariables());
          }
        };
    RogueModifierLifetime46.process(state.modifier.lifetime, 7, world);
    assertEquals(2L << 33, variables.read(44));
    assertEquals(7, state.modifier.lifetime.initialLife);
    assertEquals(List.of(new RogueResonanceVariables46.Change(44, 3L << 33, 2L << 33)), changes);
  }
}
