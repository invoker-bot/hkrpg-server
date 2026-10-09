package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RogueModifierStackEvent46Test {
  static final long ONE = 1L << 33;

  static RogueModifierStackEvent46.State state() {
    var s = new RogueModifierStackEvent46.State(RogueModifierReplace46Test.state());
    s.replacement.values.modifier.layer = 1;
    s.replacement.values.baseMaxLayer = 10;
    s.replacement.layerAddWhenStack = 2;
    s.parameterFrames = new ArrayDeque<>();
    s.config = new RogueModifierStackEvent46.Config(false);
    return s;
  }

  static class BoundWorld implements RogueModifierStackEvent46.World {
    final List<String> order = new ArrayList<>();
    final List<Integer> layers = new ArrayList<>();
    final List<Integer> versions = new ArrayList<>();
    RogueModifierStackEvent46.Arguments arguments;

    public void valueChanged(RogueModifierStackEvent46.State s, int type) {
      assertEquals(0, type);
      order.add("value");
      layers.add(s.replacement.values.modifier.layer);
    }

    public void popStackedProperties(RogueModifierStackEvent46.State s) {
      order.add("properties");
      arguments = s.parameterFrames.peek();
    }

    public void popStackedAdditionalTeamBp(RogueModifierStackEvent46.State s) {
      order.add("bp");
    }

    public void popBoostPointCallbackDecorate(RogueModifierStackEvent46.State s) {
      order.add("boost");
    }

    public void bindBehaviorFlagEffects(RogueModifierStackEvent46.State s) {
      order.add("bind");
    }

    public void tryBehaviorFlagEvent(
        RogueModifierStackEvent46.State s, boolean first, boolean second) {
      assertFalse(first);
      assertFalse(second);
      order.add("behavior");
    }

    public void executeEvent(RogueModifierStackEvent46.State s, int event) {
      assertEquals(53, event);
      order.add("stack");
      versions.add(s.currentReentryVersion);
    }

    public void postOwnerStackEvents(RogueModifierStackEvent46.State s) {
      order.add("owner");
    }

    public void postAdditionalTeamBpStack(RogueModifierStackEvent46.State s, boolean value) {
      assertFalse(value);
      order.add("additional");
    }
  }

  static Deque<RogueModifierStackEvent46.Arguments> onPush(Runnable action) {
    return new ArrayDeque<>() {
      public void push(RogueModifierStackEvent46.Arguments args) {
        super.push(args);
        action.run();
      }
    };
  }

  @Test
  void ordinaryStackUpdatesSharedLayerAndOrderedArguments() {
    var s = state();
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(3, s.replacement.values.modifier.layer);
    assertEquals(2 * ONE, w.arguments.requestedLayerAdd);
    assertEquals(2 * ONE, w.arguments.actualLayerChange);
    assertTrue(s.parameterFrames.isEmpty());
    assertEquals(
        List.of("value", "properties", "bp", "boost", "behavior", "stack", "owner"), w.order);
  }

  @Test
  void forcedLayerOverridesAdditionAndReportsZeroRequestedAddition() {
    var s = state();
    s.replacement.forceLayer = OptionalInt.of(5);
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(5, s.replacement.values.modifier.layer);
    assertEquals(0, w.arguments.requestedLayerAdd);
    assertEquals(4 * ONE, w.arguments.actualLayerChange);
  }

  @Test
  void additionUsesUncheckedInt32Wrap() {
    var s = state();
    s.replacement.values.modifier.layer = Integer.MAX_VALUE;
    s.replacement.layerAddWhenStack = 1;
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(Integer.MIN_VALUE, s.replacement.values.modifier.layer);
    assertEquals((-4294967295L << 26) | 1L, w.arguments.actualLayerChange);
  }

  @Test
  void maximumIsAtLeastOneAfterUncheckedSum() {
    var s = state();
    s.replacement.values.baseMaxLayer = 0;
    s.replacement.values.extraMaxLayer = -5;
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(1, s.replacement.values.modifier.layer);
    assertEquals(List.of(3, 1), w.layers);
    assertEquals(0, w.arguments.actualLayerChange);
  }

  @Test
  void overflowingMaximumSumUsesWrappedValueAndOneFloor() {
    var s = state();
    s.replacement.values.baseMaxLayer = Integer.MAX_VALUE;
    s.replacement.values.extraMaxLayer = 1;
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(1, s.replacement.values.modifier.layer);
    assertEquals(List.of(3, 1), w.layers);
  }

  @Test
  void negativeLayerIsNotInventedAsZeroOrOne() {
    var s = state();
    s.replacement.forceLayer = OptionalInt.of(-5);
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(-5, s.replacement.values.modifier.layer);
    assertEquals(-6 * ONE, w.arguments.actualLayerChange);
    assertEquals(List.of(-5), w.layers);
  }

  @Test
  void unchangedFirstLayerCanStillBeClampedAndNotifyOnce() {
    var s = state();
    s.replacement.values.modifier.layer = 5;
    s.replacement.layerAddWhenStack = 0;
    s.replacement.values.baseMaxLayer = 2;
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(List.of(2), w.layers);
    assertEquals(-3 * ONE, w.arguments.actualLayerChange);
  }

  @Test
  void twoLayerChangesGenerateTwoNotificationsInOrder() {
    var s = state();
    s.replacement.values.baseMaxLayer = 2;
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(List.of(3, 2), w.layers);
    assertEquals(ONE, w.arguments.actualLayerChange);
  }

  @Test
  void equalLayerWithoutClampDoesNotNotify() {
    var s = state();
    s.replacement.layerAddWhenStack = 0;
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertTrue(w.layers.isEmpty());
    assertEquals(0, w.arguments.actualLayerChange);
  }

  @Test
  void setterCallbackLayerChangesAreRereadBeforeClampAndDelta() {
    var s = state();
    var w =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackEvent46.State s, int type) {
            super.valueChanged(s, type);
            s.replacement.values.modifier.layer = 7;
          }
        };
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(7, s.replacement.values.modifier.layer);
    assertEquals(6 * ONE, w.arguments.actualLayerChange);
    assertEquals(List.of(3), w.layers);
  }

  @Test
  void setterCallbackMaximumChangesAffectCurrentClamp() {
    var s = state();
    var w =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackEvent46.State s, int type) {
            super.valueChanged(s, type);
            s.replacement.values.baseMaxLayer = 2;
          }
        };
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(2, s.replacement.values.modifier.layer);
    assertEquals(List.of(3, 2), w.layers);
  }

  @Test
  void forcePresenceIsRereadAfterParameterPush() {
    var s = state();
    s.parameterFrames = onPush(() -> s.replacement.forceLayer = OptionalInt.of(5));
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(2 * ONE, w.arguments.requestedLayerAdd);
    assertEquals(5, s.replacement.values.modifier.layer);
  }

  @Test
  void previousLayerIsCapturedAfterParameterPush() {
    var s = state();
    s.parameterFrames = onPush(() -> s.replacement.values.modifier.layer = 4);
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(6, s.replacement.values.modifier.layer);
    assertEquals(2 * ONE, w.arguments.actualLayerChange);
  }

  @Test
  void missingForcePresenceAfterPushKeepsPushedFrame() {
    var s = state();
    s.parameterFrames = onPush(() -> s.replacement.forceLayer = null);
    var w = new BoundWorld();
    assertThrows(IllegalArgumentException.class, () -> RogueModifierStackEvent46.onStack(s, w));
    assertEquals(1, s.parameterFrames.size());
    assertEquals(1, s.replacement.values.modifier.layer);
    assertTrue(w.order.isEmpty());
  }

  @Test
  void positiveLayerDifferenceUsesInt64BeforeEncoding() {
    var s = state();
    s.replacement.values.modifier.layer = Integer.MIN_VALUE;
    s.replacement.forceLayer = OptionalInt.of(Integer.MAX_VALUE);
    s.replacement.values.baseMaxLayer = Integer.MAX_VALUE;
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals((4294967295L << 26) | 1L, w.arguments.actualLayerChange);
  }

  @Test
  void largeRequestedAdditionUsesExtendedMode() {
    var s = state();
    s.replacement.layerAddWhenStack = Integer.MIN_VALUE;
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(((long) Integer.MIN_VALUE << 26) | 1L, w.arguments.requestedLayerAdd);
  }

  @Test
  void integerArgumentEncodingMatchesIndependentExactScale() {
    for (long value :
        new long[] {
          -4294967295L, -0x40000000L, -0x3fffffffL, -1, 0, 1, 0x3fffffffL, 0x40000000L, 4294967295L
        }) {
      long expected =
          BigInteger.valueOf(value).shiftLeft(Math.abs(value) < 0x40000000L ? 33 : 26).longValue();
      if (Math.abs(value) >= 0x40000000L) expected |= 1;
      assertEquals(expected, RogueModifierStackEvent46.integerRaw(value));
    }
  }

  @Test
  void unmatchedIntegerArgumentRangeIsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierStackEvent46.integerRaw(4294967296L));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierStackEvent46.integerRaw(Long.MIN_VALUE));
  }

  @Test
  void missingAdditionalListStoresZeroBeforeHelpers() {
    var s = state();
    s.shelvedAdditionalTeamBp = 99;
    var w =
        new BoundWorld() {
          public void popStackedAdditionalTeamBp(RogueModifierStackEvent46.State s) {
            assertEquals(0, s.shelvedAdditionalTeamBp);
            super.popStackedAdditionalTeamBp(s);
          }
        };
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(0, s.shelvedAdditionalTeamBp);
    assertFalse(w.order.contains("additional"));
  }

  @Test
  void additionalCountIsReadAfterPropertiesAndBeforeAdditionalPop() {
    var s = state();
    s.stackedAdditionalTeamBp = List.of(1);
    var w =
        new BoundWorld() {
          public void popStackedProperties(RogueModifierStackEvent46.State s) {
            super.popStackedProperties(s);
            s.stackedAdditionalTeamBp = List.of(1, 2, 3);
          }

          public void popStackedAdditionalTeamBp(RogueModifierStackEvent46.State s) {
            assertEquals(3, s.shelvedAdditionalTeamBp);
            s.stackedAdditionalTeamBp = List.of();
            super.popStackedAdditionalTeamBp(s);
          }
        };
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(3, s.shelvedAdditionalTeamBp);
    assertEquals("additional", w.order.getLast());
  }

  @Test
  void helperChangesToShelvedCountControlFinalPost() {
    var s = state();
    s.stackedAdditionalTeamBp = List.of(1);
    var w =
        new BoundWorld() {
          public void popStackedAdditionalTeamBp(RogueModifierStackEvent46.State s) {
            super.popStackedAdditionalTeamBp(s);
            s.shelvedAdditionalTeamBp = -1;
          }
        };
    RogueModifierStackEvent46.onStack(s, w);
    assertFalse(w.order.contains("additional"));
  }

  @Test
  void configIsRereadAfterHelperUpdates() {
    var s = state();
    var w =
        new BoundWorld() {
          public void popBoostPointCallbackDecorate(RogueModifierStackEvent46.State s) {
            super.popBoostPointCallbackDecorate(s);
            s.config = new RogueModifierStackEvent46.Config(true);
          }
        };
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(
        List.of("value", "properties", "bp", "boost", "bind", "behavior", "stack", "owner"),
        w.order);
  }

  @Test
  void missingConfigFailsAfterHelpersWithoutVersionEventsOrPop() {
    var s = state();
    s.config = null;
    var w = new BoundWorld();
    assertThrows(IllegalArgumentException.class, () -> RogueModifierStackEvent46.onStack(s, w));
    assertEquals(List.of("value", "properties", "bp", "boost"), w.order);
    assertEquals(1, s.parameterFrames.size());
    assertEquals(0, s.maximumReentryVersion);
  }

  @Test
  void disabledBindingSkipsEffectHandler() {
    var s = state();
    var w =
        new BoundWorld() {
          public void bindBehaviorFlagEffects(RogueModifierStackEvent46.State s) {
            fail("disabled bind");
          }
        };
    RogueModifierStackEvent46.onStack(s, w);
    assertFalse(w.order.contains("bind"));
  }

  @Test
  void silenceSkipsOwnerEventsButStillPopsAndPostsAdditional() {
    var s = state();
    s.silenceAdd = true;
    s.stackedAdditionalTeamBp = List.of(1);
    var w =
        new BoundWorld() {
          public void postOwnerStackEvents(RogueModifierStackEvent46.State s) {
            fail("silence");
          }
        };
    RogueModifierStackEvent46.onStack(s, w);
    assertTrue(s.parameterFrames.isEmpty());
    assertEquals("additional", w.order.getLast());
  }

  @Test
  void silenceIsRereadAfterEventCallback() {
    var s = state();
    var w =
        new BoundWorld() {
          public void executeEvent(RogueModifierStackEvent46.State s, int event) {
            super.executeEvent(s, event);
            s.silenceAdd = true;
          }
        };
    RogueModifierStackEvent46.onStack(s, w);
    assertFalse(w.order.contains("owner"));
  }

  @Test
  void firstVersionIsVisibleDuringEventsAndResetsAfterward() {
    var s = state();
    var w =
        new BoundWorld() {
          public void tryBehaviorFlagEvent(
              RogueModifierStackEvent46.State s, boolean a, boolean b) {
            assertEquals(1, s.maximumReentryVersion);
            assertEquals(1, s.currentReentryVersion);
            super.tryBehaviorFlagEvent(s, a, b);
          }
        };
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(List.of(1), w.versions);
    assertEquals(0, s.maximumReentryVersion);
    assertEquals(0, s.currentReentryVersion);
  }

  @Test
  void nestedActualStackEventsRestoreOuterVersionAndFrame() {
    var s = state();
    s.silenceAdd = true;
    var w =
        new BoundWorld() {
          int depth;

          public void executeEvent(RogueModifierStackEvent46.State s, int event) {
            super.executeEvent(s, event);
            if (depth++ == 0) {
              assertEquals(1, s.parameterFrames.size());
              var outer = s.parameterFrames.peek();
              RogueModifierStackEvent46.onStack(s, this);
              assertSame(outer, s.parameterFrames.peek());
              assertEquals(1, s.currentReentryVersion);
              assertEquals(2, s.maximumReentryVersion);
            }
          }
        };
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(List.of(1, 2), w.versions);
    assertEquals(5, s.replacement.values.modifier.layer);
    assertTrue(s.parameterFrames.isEmpty());
    assertEquals(0, s.maximumReentryVersion);
    assertEquals(0, s.currentReentryVersion);
  }

  @Test
  void changedCurrentVersionSkipsFirstVersionResetAndRestoresSavedVersion() {
    var s = state();
    s.currentReentryVersion = 7;
    var w =
        new BoundWorld() {
          public void executeEvent(RogueModifierStackEvent46.State s, int event) {
            super.executeEvent(s, event);
            s.currentReentryVersion = 2;
          }
        };
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(1, s.maximumReentryVersion);
    assertEquals(7, s.currentReentryVersion);
  }

  @Test
  void observedCurrentVersionOneResetsEvenWhenSavedVersionWasNonzero() {
    var s = state();
    s.currentReentryVersion = 7;
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(0, s.currentReentryVersion);
    assertEquals(0, s.maximumReentryVersion);
  }

  @Test
  void maximumVersionIncrementUsesInt32Wrap() {
    var s = state();
    s.maximumReentryVersion = Integer.MAX_VALUE;
    s.currentReentryVersion = 7;
    var w = new BoundWorld();
    RogueModifierStackEvent46.onStack(s, w);
    assertEquals(List.of(Integer.MIN_VALUE), w.versions);
    assertEquals(Integer.MIN_VALUE, s.maximumReentryVersion);
    assertEquals(7, s.currentReentryVersion);
  }

  @Test
  void behaviorFaultRetainsCurrentVersionAndFrameWithoutFinally() {
    var s = state();
    var w =
        new BoundWorld() {
          public void tryBehaviorFlagEvent(
              RogueModifierStackEvent46.State s, boolean a, boolean b) {
            super.tryBehaviorFlagEvent(s, a, b);
            throw new IllegalStateException("behavior");
          }
        };
    assertThrows(IllegalStateException.class, () -> RogueModifierStackEvent46.onStack(s, w));
    assertEquals(1, s.currentReentryVersion);
    assertEquals(1, s.maximumReentryVersion);
    assertEquals(1, s.parameterFrames.size());
    assertFalse(w.order.contains("stack"));
  }

  @Test
  void stackEventFaultRetainsVersionAndSkipsOwnerAndPop() {
    var s = state();
    var w =
        new BoundWorld() {
          public void executeEvent(RogueModifierStackEvent46.State s, int event) {
            super.executeEvent(s, event);
            throw new IllegalStateException("event");
          }
        };
    assertThrows(IllegalStateException.class, () -> RogueModifierStackEvent46.onStack(s, w));
    assertEquals(1, s.currentReentryVersion);
    assertEquals(1, s.parameterFrames.size());
    assertFalse(w.order.contains("owner"));
  }

  @Test
  void ownerFaultHappensAfterVersionResetButBeforeParameterPop() {
    var s = state();
    var w =
        new BoundWorld() {
          public void postOwnerStackEvents(RogueModifierStackEvent46.State s) {
            super.postOwnerStackEvents(s);
            throw new IllegalStateException("owner");
          }
        };
    assertThrows(IllegalStateException.class, () -> RogueModifierStackEvent46.onStack(s, w));
    assertEquals(0, s.currentReentryVersion);
    assertEquals(0, s.maximumReentryVersion);
    assertEquals(1, s.parameterFrames.size());
  }

  @Test
  void valueObserverFaultLeavesFirstLayerWriteCommittedAndFramePushed() {
    var s = state();
    var w =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackEvent46.State s, int type) {
            super.valueChanged(s, type);
            throw new IllegalStateException("value");
          }
        };
    assertThrows(IllegalStateException.class, () -> RogueModifierStackEvent46.onStack(s, w));
    assertEquals(3, s.replacement.values.modifier.layer);
    assertEquals(1, s.parameterFrames.size());
    assertEquals(List.of("value"), w.order);
  }

  @Test
  void missingFramesFailBeforeChangingLayer() {
    var s = state();
    s.parameterFrames = null;
    var w = new BoundWorld();
    assertThrows(IllegalArgumentException.class, () -> RogueModifierStackEvent46.onStack(s, w));
    assertEquals(1, s.replacement.values.modifier.layer);
    assertTrue(w.order.isEmpty());
  }

  @Test
  void popRereadsCurrentFramesAndLeavesOriginalFrameIfReplaced() {
    var s = state();
    var original = s.parameterFrames;
    var w =
        new BoundWorld() {
          public void postOwnerStackEvents(RogueModifierStackEvent46.State s) {
            super.postOwnerStackEvents(s);
            s.parameterFrames = new ArrayDeque<>();
          }
        };
    assertThrows(NoSuchElementException.class, () -> RogueModifierStackEvent46.onStack(s, w));
    assertEquals(1, original.size());
    assertTrue(s.parameterFrames.isEmpty());
    assertEquals(0, s.currentReentryVersion);
  }

  @Test
  void missingCurrentFramesFailAfterOwnerCallback() {
    var s = state();
    var w =
        new BoundWorld() {
          public void postOwnerStackEvents(RogueModifierStackEvent46.State s) {
            super.postOwnerStackEvents(s);
            s.parameterFrames = null;
          }
        };
    assertThrows(IllegalArgumentException.class, () -> RogueModifierStackEvent46.onStack(s, w));
    assertEquals("owner", w.order.getLast());
  }

  @Test
  void parameterPopFaultStopsFinalAdditionalEvent() {
    var s = state();
    s.stackedAdditionalTeamBp = List.of(1);
    s.parameterFrames =
        new ArrayDeque<>() {
          public RogueModifierStackEvent46.Arguments pop() {
            throw new IllegalStateException("pop");
          }
        };
    var w = new BoundWorld();
    assertThrows(IllegalStateException.class, () -> RogueModifierStackEvent46.onStack(s, w));
    assertEquals(1, s.parameterFrames.size());
    assertFalse(w.order.contains("additional"));
  }

  @Test
  void actualReplacementAndValueBindingUpdateSameInjectedVariable() {
    var s = state();
    var vars = RogueResonanceVariables46.fromSourceDefaults(Map.of(42, ONE), null);
    s.replacement.values.dynamicValues = vars;
    s.replacement.values.keys = new int[] {42};
    s.replacement.values.mask = 1;
    var stackWorld =
        new BoundWorld() {
          public void valueChanged(RogueModifierStackEvent46.State s, int type) {
            super.valueChanged(s, type);
            RogueModifierValueBinding46.notifyValue(
                s.replacement.values, type, RogueModifierValueBinding46.standardVariables());
          }
        };
    var p = RogueModifierReplace46Test.parameters();
    var replaceWorld =
        new RogueModifierReplace46Test.BoundWorld() {
          public void afterReplaceVirtual(RogueModifierReplace46.State state) {
            super.afterReplaceVirtual(state);
            RogueModifierStackEvent46.onStack(s, stackWorld);
          }
        };
    RogueModifierReplace46.replace(
        s.replacement, RogueModifierReplace46Test.ABILITY, p, replaceWorld);
    assertEquals(4, s.replacement.values.modifier.layer);
    assertEquals(4 * ONE, vars.read(42));
    assertEquals(3 * ONE, stackWorld.arguments.actualLayerChange);
    assertTrue(s.parameterFrames.isEmpty());
  }

  @Test
  void missingBindingOrForcedPresenceDoesNotFabricateCompletion() {
    var s = state();
    var w = new BoundWorld();
    assertThrows(IllegalArgumentException.class, () -> new RogueModifierStackEvent46.State(null));
    assertThrows(IllegalArgumentException.class, () -> RogueModifierStackEvent46.onStack(null, w));
    assertThrows(IllegalArgumentException.class, () -> RogueModifierStackEvent46.onStack(s, null));
    s.replacement.forceLayer = null;
    assertThrows(IllegalArgumentException.class, () -> RogueModifierStackEvent46.onStack(s, w));
    assertTrue(s.parameterFrames.isEmpty());
  }
}
