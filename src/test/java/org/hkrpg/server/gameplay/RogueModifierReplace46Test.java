package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RogueModifierReplace46Test {
  static final Object OVERRIDES = new Object(), CONTEXT = new Object(), ABILITY = new Object();

  static class BoundWorld implements RogueModifierReplace46.World {
    final List<String> order = new ArrayList<>();
    Object overrides = OVERRIDES, context = CONTEXT, replacedAbility;
    int maxLayer = 7, layerAdd = 3;
    boolean immediateFlag;

    public Object acquireNumericOverrides(RogueModifierReplace46.State state) {
      order.add("acquire");
      return overrides;
    }

    public int calculateMaxLayer(
        RogueModifierReplace46.State state, Object o, RogueModifierReplace46.Parameters p) {
      assertSame(overrides, o);
      order.add("max");
      return maxLayer;
    }

    public void valueChanged(RogueModifierReplace46.State state, int type) {
      order.add("value" + type);
    }

    public int calculateLayerAdd(
        RogueModifierReplace46.State state, Object o, RogueModifierReplace46.Parameters p) {
      assertSame(overrides, o);
      order.add("layerAdd");
      return layerAdd;
    }

    public void releaseNumericOverrides(Object o) {
      assertSame(overrides, o);
      order.add("release");
    }

    public boolean containsBehaviorFlag(RogueModifierReplace46.State state, int flag) {
      assertEquals(7, flag);
      order.add("flag7");
      return immediateFlag;
    }

    public Object taskContext(RogueModifierReplace46.State state) {
      order.add("context");
      return context;
    }

    public void replaceContextAbility(Object c, Object ability) {
      assertSame(context, c);
      replacedAbility = ability;
      order.add("ability");
    }

    public void afterAbilityVirtual(RogueModifierReplace46.State state) {
      order.add("abilityChanged");
    }

    public void setupFloatInjections(RogueModifierReplace46.State state, Object list) {
      assertSame(state.initialFloatInjections, list);
      order.add("float");
    }

    public void setupStringInjections(RogueModifierReplace46.State state, Object list) {
      assertSame(state.initialStringInjections, list);
      order.add("string");
    }

    public void afterReplaceVirtual(RogueModifierReplace46.State state) {
      order.add("dispatch");
    }
  }

  static RogueModifierReplace46.State state() {
    var lifetime = new RogueModifierLifetime46.State(new Object(), 5, 2);
    lifetime.modifierName = "effect";
    var modifier = new RogueModifierStackMode46.State(lifetime, 7, 2);
    var values = new RogueModifierValueBinding46.State(modifier);
    values.baseMaxLayer = 7;
    return new RogueModifierReplace46.State(values);
  }

  static RogueModifierReplace46.Parameters parameters() {
    var p = new RogueModifierReplace46.Parameters();
    p.base = new RogueModifierReplace46.BaseParameters();
    return p;
  }

  @Test
  void activeSequenceStoresAbilityWithoutReplacingCurrentContext() {
    var state = state();
    state.executingSequenceCount = 3;
    var world = new BoundWorld();
    RogueModifierReplace46.replace(state, ABILITY, parameters(), world);
    assertSame(ABILITY, state.delayReplaceAbility);
    assertNull(world.replacedAbility);
    assertEquals(List.of("acquire", "layerAdd", "release", "flag7", "dispatch"), world.order);
  }

  @Test
  void maxLayerPresenceNotifiesEvenWhenRecalculatedValueIsUnchanged() {
    var state = state();
    var p = parameters();
    p.maximumLayerOverride = OptionalInt.of(7);
    var world = new BoundWorld();
    RogueModifierReplace46.replace(state, ABILITY, p, world);
    assertEquals(
        List.of(
            "acquire",
            "max",
            "value2",
            "layerAdd",
            "release",
            "flag7",
            "context",
            "ability",
            "abilityChanged",
            "dispatch"),
        world.order);
  }

  @Test
  void numericOverridesReleaseBeforeAbilityContextOrReplacementEvents() {
    var state = state();
    var world = new BoundWorld();
    RogueModifierReplace46.replace(state, ABILITY, parameters(), world);
    assertEquals(
        List.of(
            "acquire",
            "layerAdd",
            "release",
            "flag7",
            "context",
            "ability",
            "abilityChanged",
            "dispatch"),
        world.order);
    assertEquals(3, state.layerAddWhenStack);
    assertSame(ABILITY, world.replacedAbility);
  }

  @Test
  void absentMaximumOverrideSkipsMaxCalculationAndNotification() {
    var state = state();
    var world = new BoundWorld();
    RogueModifierReplace46.replace(state, ABILITY, parameters(), world);
    assertEquals(7, state.values.baseMaxLayer);
    assertFalse(world.order.contains("max"));
    assertFalse(world.order.contains("value2"));
    assertEquals(3, state.layerAddWhenStack);
  }

  @Test
  void presentZeroMaximumOverrideStillRecalculatesAndNotifies() {
    var state = state();
    var p = parameters();
    p.maximumLayerOverride = OptionalInt.of(0);
    var world = new BoundWorld();
    world.maxLayer = 19;
    RogueModifierReplace46.replace(state, ABILITY, p, world);
    assertEquals(19, state.values.baseMaxLayer);
    assertTrue(world.order.contains("value2"));
  }

  @Test
  void calculatedNegativeMaximumIsNotClampedDuringReplacement() {
    var state = state();
    var p = parameters();
    p.maximumLayerOverride = OptionalInt.of(-9);
    var world = new BoundWorld();
    world.maxLayer = -9;
    RogueModifierReplace46.replace(state, ABILITY, p, world);
    assertEquals(-9, state.values.baseMaxLayer);
  }

  @Test
  void maxNotificationCanChangeParametersBeforeLayerCalculation() {
    var state = state();
    var p = parameters();
    p.maximumLayerOverride = OptionalInt.of(7);
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierReplace46.State s, int type) {
            super.valueChanged(s, type);
            p.forceLayer = OptionalInt.of(19);
          }

          public int calculateLayerAdd(
              RogueModifierReplace46.State s, Object o, RogueModifierReplace46.Parameters actual) {
            assertEquals(19, actual.forceLayer.getAsInt());
            return super.calculateLayerAdd(s, o, actual);
          }
        };
    RogueModifierReplace46.replace(state, ABILITY, p, world);
    assertEquals(19, state.forceLayer.getAsInt());
  }

  @Test
  void layerCalculationMutationIsReadWhenCopyingForcedLayer() {
    var state = state();
    var p = parameters();
    var world =
        new BoundWorld() {
          public int calculateLayerAdd(
              RogueModifierReplace46.State s, Object o, RogueModifierReplace46.Parameters actual) {
            actual.forceLayer = OptionalInt.of(19);
            return super.calculateLayerAdd(s, o, actual);
          }
        };
    RogueModifierReplace46.replace(state, ABILITY, p, world);
    assertEquals(19, state.forceLayer.getAsInt());
  }

  @Test
  void knownAbsentNumericOverrideSkipsReleaseAndPassesNullToCalculators() {
    var state = state();
    var p = parameters();
    p.maximumLayerOverride = OptionalInt.of(7);
    var world = new BoundWorld();
    world.overrides = null;
    RogueModifierReplace46.replace(state, ABILITY, p, world);
    assertFalse(world.order.contains("release"));
    assertTrue(world.order.contains("max"));
    assertEquals(3, state.layerAddWhenStack);
  }

  @Test
  void missingParametersFailAfterAcquiringWithoutReleasingOverrides() {
    var world = new BoundWorld();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierReplace46.replace(state(), ABILITY, null, world));
    assertEquals(List.of("acquire"), world.order);
  }

  @Test
  void unmatchedMaximumPresenceFailsBeforeAnyNumericCalculation() {
    var p = parameters();
    p.maximumLayerOverride = null;
    var world = new BoundWorld();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierReplace46.replace(state(), ABILITY, p, world));
    assertEquals(List.of("acquire"), world.order);
  }

  @Test
  void maximumCalculationFailureStopsBeforeWriteNotificationOrRelease() {
    var state = state();
    var p = parameters();
    p.maximumLayerOverride = OptionalInt.of(19);
    var failure = new IllegalStateException("max");
    var world =
        new BoundWorld() {
          public int calculateMaxLayer(
              RogueModifierReplace46.State s, Object o, RogueModifierReplace46.Parameters actual) {
            super.calculateMaxLayer(s, o, actual);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierReplace46.replace(state, ABILITY, p, world)));
    assertEquals(7, state.values.baseMaxLayer);
    assertEquals(List.of("acquire", "max"), world.order);
  }

  @Test
  void valueNotificationFailureRetainsMaximumWriteAndStopsBeforeLayerOrRelease() {
    var state = state();
    var p = parameters();
    p.maximumLayerOverride = OptionalInt.of(19);
    var failure = new IllegalStateException("value");
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierReplace46.State s, int type) {
            super.valueChanged(s, type);
            throw failure;
          }
        };
    world.maxLayer = 19;
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierReplace46.replace(state, ABILITY, p, world)));
    assertEquals(19, state.values.baseMaxLayer);
    assertEquals(List.of("acquire", "max", "value2"), world.order);
  }

  @Test
  void layerCalculationFailureRetainsMaximumAndSkipsRelease() {
    var state = state();
    var p = parameters();
    p.maximumLayerOverride = OptionalInt.of(19);
    var failure = new IllegalStateException("layer");
    var world =
        new BoundWorld() {
          public int calculateLayerAdd(
              RogueModifierReplace46.State s, Object o, RogueModifierReplace46.Parameters actual) {
            super.calculateLayerAdd(s, o, actual);
            throw failure;
          }
        };
    world.maxLayer = 19;
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierReplace46.replace(state, ABILITY, p, world)));
    assertEquals(19, state.values.baseMaxLayer);
    assertEquals(0, state.layerAddWhenStack);
    assertEquals(List.of("acquire", "max", "value2", "layerAdd"), world.order);
  }

  @Test
  void releaseFailureRetainsLayerWriteAndStopsBeforeForceOrAbility() {
    var state = state();
    var p = parameters();
    p.forceLayer = OptionalInt.of(19);
    var failure = new IllegalStateException("release");
    var world =
        new BoundWorld() {
          public void releaseNumericOverrides(Object o) {
            super.releaseNumericOverrides(o);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierReplace46.replace(state, ABILITY, p, world)));
    assertEquals(3, state.layerAddWhenStack);
    assertTrue(state.forceLayer.isEmpty());
    assertEquals(List.of("acquire", "layerAdd", "release"), world.order);
  }

  @Test
  void unmatchedForcedLayerFailsAfterReleaseAndBeforeImmediateOrBase() {
    var state = state();
    var p = parameters();
    p.forceLayer = null;
    var world = new BoundWorld();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierReplace46.replace(state, ABILITY, p, world));
    assertEquals(List.of("acquire", "layerAdd", "release"), world.order);
  }

  @Test
  void copiedForcedLayerDoesNotDirectlyChangeActualLayer() {
    var state = state();
    var p = parameters();
    p.forceLayer = OptionalInt.of(19);
    RogueModifierReplace46.replace(state, ABILITY, p, new BoundWorld());
    assertEquals(19, state.forceLayer.getAsInt());
    assertEquals(2, state.values.modifier.layer);
  }

  @Test
  void absentIncomingForcedLayerClearsPreviousOptionalValue() {
    var state = state();
    state.forceLayer = OptionalInt.of(19);
    RogueModifierReplace46.replace(state, ABILITY, parameters(), new BoundWorld());
    assertTrue(state.forceLayer.isEmpty());
  }

  @Test
  void explicitImmediateShortCircuitsBehaviorFlagLookup() {
    var state = state();
    var p = parameters();
    p.immediately = true;
    var world = new BoundWorld();
    RogueModifierReplace46.replace(state, ABILITY, p, world);
    assertTrue(state.values.modifier.lifetime.immediately);
    assertFalse(world.order.contains("flag7"));
  }

  @Test
  void behaviorFlagSevenCanEnableImmediateLifeStep() {
    var state = state();
    var world = new BoundWorld();
    world.immediateFlag = true;
    RogueModifierReplace46.replace(state, ABILITY, parameters(), world);
    assertTrue(state.values.modifier.lifetime.immediately);
    assertTrue(world.order.contains("flag7"));
  }

  @Test
  void falseParameterAndBehaviorFlagClearPreviousImmediateValue() {
    var state = state();
    state.values.modifier.lifetime.immediately = true;
    RogueModifierReplace46.replace(state, ABILITY, parameters(), new BoundWorld());
    assertFalse(state.values.modifier.lifetime.immediately);
  }

  @Test
  void behaviorLookupFailureKeepsCopiedForceAndPreviousImmediateValue() {
    var state = state();
    state.values.modifier.lifetime.immediately = true;
    var p = parameters();
    p.forceLayer = OptionalInt.of(19);
    var failure = new IllegalStateException("flag");
    var world =
        new BoundWorld() {
          public boolean containsBehaviorFlag(RogueModifierReplace46.State s, int flag) {
            super.containsBehaviorFlag(s, flag);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierReplace46.replace(state, ABILITY, p, world)));
    assertEquals(19, state.forceLayer.getAsInt());
    assertTrue(state.values.modifier.lifetime.immediately);
    assertFalse(world.order.contains("context"));
  }

  @Test
  void zeroExecutingSequencesUpdatesAbilityBeforeAbilityChangedAndDispatch() {
    var state = state();
    var world = new BoundWorld();
    RogueModifierReplace46.replace(state, ABILITY, parameters(), world);
    assertEquals(
        List.of("context", "ability", "abilityChanged", "dispatch"), world.order.subList(4, 8));
    assertSame(ABILITY, world.replacedAbility);
  }

  @Test
  void anyNonzeroExecutingSequenceCountDefersAbilityReplacement() {
    for (int count : List.of(-1, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
      var state = state();
      state.executingSequenceCount = count;
      var world = new BoundWorld();
      RogueModifierReplace46.replace(state, ABILITY, parameters(), world);
      assertSame(ABILITY, state.delayReplaceAbility);
      assertFalse(world.order.contains("context"));
    }
  }

  @Test
  void deferredNullAbilityClearsAnOlderDelayedAbility() {
    var state = state();
    state.executingSequenceCount = 1;
    state.delayReplaceAbility = ABILITY;
    RogueModifierReplace46.replace(state, null, parameters(), new BoundWorld());
    assertNull(state.delayReplaceAbility);
  }

  @Test
  void missingImmediateContextFailsAfterNumericAndLifeFlagUpdates() {
    var state = state();
    var p = parameters();
    p.immediately = true;
    var world = new BoundWorld();
    world.context = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierReplace46.replace(state, ABILITY, p, world));
    assertEquals(3, state.layerAddWhenStack);
    assertTrue(state.values.modifier.lifetime.immediately);
    assertEquals(List.of("acquire", "layerAdd", "release", "context"), world.order);
  }

  @Test
  void contextAbilitySetterFailureStopsBeforeAbilityChangedAndInjections() {
    var failure = new IllegalStateException("ability");
    var world =
        new BoundWorld() {
          public void replaceContextAbility(Object c, Object ability) {
            super.replaceContextAbility(c, ability);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierReplace46.replace(state(), ABILITY, parameters(), world)));
    assertFalse(world.order.contains("abilityChanged"));
    assertFalse(world.order.contains("dispatch"));
  }

  @Test
  void abilityChangedFailureStopsBeforeInjectionAndDispatch() {
    var failure = new IllegalStateException("changed");
    var world =
        new BoundWorld() {
          public void afterAbilityVirtual(RogueModifierReplace46.State s) {
            super.afterAbilityVirtual(s);
            throw failure;
          }
        };
    var p = parameters();
    p.base.floatInjections = new Object();
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierReplace46.replace(state(), ABILITY, p, world)));
    assertFalse(world.order.contains("float"));
    assertFalse(world.order.contains("dispatch"));
  }

  @Test
  void missingBaseParametersStillRetainDeferredAbilityWrite() {
    var state = state();
    state.executingSequenceCount = 1;
    var p = parameters();
    p.base = null;
    var world = new BoundWorld();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierReplace46.replace(state, ABILITY, p, world));
    assertSame(ABILITY, state.delayReplaceAbility);
    assertFalse(world.order.contains("context"));
  }

  @Test
  void missingBaseParametersFailAfterImmediateAbilityContextWasUpdated() {
    var p = parameters();
    p.base = null;
    var world = new BoundWorld();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierReplace46.replace(state(), ABILITY, p, world));
    assertSame(ABILITY, world.replacedAbility);
    assertTrue(world.order.contains("abilityChanged"));
    assertFalse(world.order.contains("dispatch"));
  }

  @Test
  void floatThenStringInjectionAssignmentsPrecedeTheirCallbacksAndDispatch() {
    var state = state();
    var p = parameters();
    p.base.floatInjections = new Object();
    p.base.stringInjections = new Object();
    var world = new BoundWorld();
    RogueModifierReplace46.replace(state, ABILITY, p, world);
    assertSame(p.base.floatInjections, state.initialFloatInjections);
    assertSame(p.base.stringInjections, state.initialStringInjections);
    assertEquals(
        List.of("float", "string", "dispatch"),
        world.order.subList(world.order.size() - 3, world.order.size()));
  }

  @Test
  void absentInjectionListsPreserveOldListsWithoutSetupCallbacks() {
    var state = state();
    var floats = new Object();
    var strings = new Object();
    state.initialFloatInjections = floats;
    state.initialStringInjections = strings;
    var world = new BoundWorld();
    RogueModifierReplace46.replace(state, ABILITY, parameters(), world);
    assertSame(floats, state.initialFloatInjections);
    assertSame(strings, state.initialStringInjections);
    assertFalse(world.order.contains("float"));
    assertFalse(world.order.contains("string"));
  }

  @Test
  void knownEmptyNonNullInjectionListsStillEnterSetupCallbacks() {
    var p = parameters();
    p.base.floatInjections = List.of();
    p.base.stringInjections = List.of();
    var world = new BoundWorld();
    RogueModifierReplace46.replace(state(), ABILITY, p, world);
    assertTrue(world.order.contains("float"));
    assertTrue(world.order.contains("string"));
  }

  @Test
  void floatSetupMutationIsReadForTheLaterStringList() {
    var state = state();
    var p = parameters();
    p.base.floatInjections = new Object();
    var strings = new Object();
    var world =
        new BoundWorld() {
          public void setupFloatInjections(RogueModifierReplace46.State s, Object list) {
            super.setupFloatInjections(s, list);
            p.base.stringInjections = strings;
          }
        };
    RogueModifierReplace46.replace(state, ABILITY, p, world);
    assertSame(strings, state.initialStringInjections);
    assertTrue(world.order.contains("string"));
  }

  @Test
  void floatSetupFailureRetainsAssignedFloatListAndSkipsStringsOrDispatch() {
    var state = state();
    var p = parameters();
    p.base.floatInjections = new Object();
    p.base.stringInjections = new Object();
    var failure = new IllegalStateException("float");
    var world =
        new BoundWorld() {
          public void setupFloatInjections(RogueModifierReplace46.State s, Object list) {
            super.setupFloatInjections(s, list);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierReplace46.replace(state, ABILITY, p, world)));
    assertSame(p.base.floatInjections, state.initialFloatInjections);
    assertNull(state.initialStringInjections);
    assertFalse(world.order.contains("dispatch"));
  }

  @Test
  void stringSetupFailureRetainsBothAssignedListsAndSkipsDispatch() {
    var state = state();
    var p = parameters();
    p.base.floatInjections = new Object();
    p.base.stringInjections = new Object();
    var failure = new IllegalStateException("string");
    var world =
        new BoundWorld() {
          public void setupStringInjections(RogueModifierReplace46.State s, Object list) {
            super.setupStringInjections(s, list);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierReplace46.replace(state, ABILITY, p, world)));
    assertSame(p.base.floatInjections, state.initialFloatInjections);
    assertSame(p.base.stringInjections, state.initialStringInjections);
    assertFalse(world.order.contains("dispatch"));
  }

  @Test
  void replacementDispatchFailureRetainsEarlierAbilityAndInjectionChanges() {
    var state = state();
    var p = parameters();
    p.base.floatInjections = new Object();
    var failure = new IllegalStateException("dispatch");
    var world =
        new BoundWorld() {
          public void afterReplaceVirtual(RogueModifierReplace46.State s) {
            super.afterReplaceVirtual(s);
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierReplace46.replace(state, ABILITY, p, world)));
    assertSame(ABILITY, world.replacedAbility);
    assertSame(p.base.floatInjections, state.initialFloatInjections);
    assertEquals("dispatch", world.order.getLast());
  }

  @Test
  void immediateReplacementDoesNotClearOlderDelayedAbilityField() {
    var state = state();
    state.delayReplaceAbility = new Object();
    var delayed = state.delayReplaceAbility;
    RogueModifierReplace46.replace(state, ABILITY, parameters(), new BoundWorld());
    assertSame(delayed, state.delayReplaceAbility);
  }

  @Test
  void numericCallbackCanChangeSequenceCountBeforeTheBaseDecision() {
    var state = state();
    var world =
        new BoundWorld() {
          public int calculateLayerAdd(
              RogueModifierReplace46.State s, Object o, RogueModifierReplace46.Parameters p) {
            s.executingSequenceCount = 1;
            return super.calculateLayerAdd(s, o, p);
          }
        };
    RogueModifierReplace46.replace(state, ABILITY, parameters(), world);
    assertSame(ABILITY, state.delayReplaceAbility);
    assertFalse(world.order.contains("context"));
  }

  @Test
  void numericCallbackCanReplaceBaseParametersBeforeTheBaseCall() {
    var state = state();
    var p = parameters();
    var floats = new Object();
    var world =
        new BoundWorld() {
          public int calculateLayerAdd(
              RogueModifierReplace46.State s, Object o, RogueModifierReplace46.Parameters actual) {
            actual.base = new RogueModifierReplace46.BaseParameters();
            actual.base.floatInjections = floats;
            return super.calculateLayerAdd(s, o, actual);
          }
        };
    RogueModifierReplace46.replace(state, ABILITY, p, world);
    assertSame(floats, state.initialFloatInjections);
  }

  @Test
  void maximumNotificationUpdatesTheSharedJavaVariableBinding() {
    var state = state();
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var variables =
        RogueResonanceVariables46.fromSourceDefaults(java.util.Map.of(33, 7L << 33), changes::add);
    state.values.keys = new int[] {11, 22, 33, 44};
    state.values.mask = 4;
    state.values.dynamicValues = variables;
    var p = parameters();
    p.maximumLayerOverride = OptionalInt.of(19);
    var world =
        new BoundWorld() {
          public void valueChanged(RogueModifierReplace46.State s, int type) {
            super.valueChanged(s, type);
            RogueModifierValueBinding46.notifyValue(
                s.values, type, RogueModifierValueBinding46.standardVariables());
          }
        };
    world.maxLayer = 19;
    RogueModifierReplace46.replace(state, ABILITY, p, world);
    assertEquals(19L << 33, variables.read(33));
    assertEquals(List.of(new RogueResonanceVariables46.Change(33, 7L << 33, 19L << 33)), changes);
  }

  @Test
  void replacementImmediateFlagAllowsSameTurnLifetimeAdvance() {
    var state = state();
    state.values.modifier.lifetime.renewTurn = 7;
    var p = parameters();
    p.immediately = true;
    RogueModifierReplace46.replace(state, ABILITY, p, new BoundWorld());
    var world = new RogueModifierLifetime46Test.BoundWorld();
    RogueModifierLifetime46.process(state.values.modifier.lifetime, 7, world);
    assertEquals(1, state.values.modifier.lifetime.currentLife);
    assertEquals(List.of("moment", "status", "value3", "hud"), world.order);
  }

  @Test
  void missingStateValuesOrWorldFailBeforeAcquiringAnything() {
    var world = new BoundWorld();
    assertThrows(IllegalArgumentException.class, () -> new RogueModifierReplace46.State(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierReplace46.replace(null, ABILITY, parameters(), world));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierReplace46.replace(state(), ABILITY, parameters(), null));
    assertTrue(world.order.isEmpty());
  }
}
