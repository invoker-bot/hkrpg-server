package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueModifierDynamicRange46.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RogueModifierDynamicRange46Test {
  static final long ONE = 1L << 33, KEY = RogueModifierCasterChange46.scopedKey(7);
  static final Object ENTER = new Object(),
      EXIT = new Object(),
      CHANGE = new Object(),
      CONTEXT = new Object();

  static Config config() {
    return new Config(
        RogueResonanceDynamicCell46.Scalar.fixed(0),
        RogueResonanceDynamicCell46.Scalar.fixed(10 * ONE));
  }

  static Entry entry() {
    var e = new Entry(config());
    e.enterSequence = ENTER;
    e.exitSequence = EXIT;
    e.changeSequence = CHANGE;
    return e;
  }

  static State state(Entry... entries) {
    var s = new State(RogueModifierReplace46Test.state());
    s.gameMode = new GameMode(new ArrayList<>());
    s.listenerGroups = new HashMap<>();
    s.listenerGroups.put(KEY, new ArrayList<>(Arrays.asList(entries)));
    return s;
  }

  static class World implements RogueModifierDynamicRange46.World {
    Object context = CONTEXT;
    ParameterPool pool = new ParameterPool();
    Limits limits = new Limits(3);
    int contextReads, poolReads, lookups, limitReads;
    final List<Object> sequences = new ArrayList<>();
    Consumer<Object> callback = q -> {};
    RogueResonanceLookup46 lookup =
        (scope, key) -> RogueResonanceVariables46.fromSourceDefaults(Map.of(88, 2 * ONE), null);

    public Limits limits(State s) {
      limitReads++;
      return limits;
    }

    public Object taskContext(State s) {
      contextReads++;
      return context;
    }

    public RogueResonanceLookup46 lookup(Object c) {
      assertSame(context, c);
      lookups++;
      return lookup;
    }

    public ParameterPool parameterPool(Object c) {
      assertSame(context, c);
      poolReads++;
      return pool;
    }

    public void executeSequence(Object q) {
      sequences.add(q);
      callback.accept(q);
    }
  }

  static RogueResonanceVariables46.Change change(long previous, long current) {
    return new RogueResonanceVariables46.Change(7, previous, current);
  }

  static void changed(State s, World w, long previous, long current) {
    RogueModifierDynamicRange46.changed(s, KEY, change(previous, current), w);
  }

  @Test
  void absentDictionaryReturnsBeforeWorldReads() {
    var s = state();
    s.listenerGroups = null;
    var w = new World();
    changed(s, w, 0, ONE);
    assertEquals(0, w.limitReads + w.contextReads);
  }

  @Test
  void unknownKeyDoesNotValidateRawArithmetic() {
    var s = state();
    var w = new World();
    RogueModifierDynamicRange46.changed(s, KEY + 1, change(1, 3), w);
    assertEquals(0, w.limitReads);
    assertTrue(s.gameMode.callbackFrames.isEmpty());
  }

  @Test
  void foundNullGroupLeavesFrameAfterLimitsRead() {
    var s = state();
    s.listenerGroups.put(KEY, null);
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> changed(s, w, 0, ONE));
    assertEquals(1, w.limitReads);
    assertEquals(List.of(new Frame(s.replacement, 7, 3)), s.gameMode.callbackFrames);
  }

  @Test
  void missingModeFailsBeforeLimits() {
    var s = state();
    s.gameMode = null;
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> changed(s, w, 0, ONE));
    assertEquals(0, w.limitReads);
  }

  @Test
  void emptyGroupPushesAndPopsWithoutContext() {
    var s = state();
    var w = new World();
    changed(s, w, 0, ONE);
    assertEquals(1, w.limitReads);
    assertEquals(0, w.contextReads);
    assertTrue(s.gameMode.callbackFrames.isEmpty());
  }

  @Test
  void arithmeticFailureRetainsPushedFrame() {
    var s = state(entry());
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> changed(s, w, 1, ONE));
    assertEquals(1, s.gameMode.callbackFrames.size());
    assertEquals(0, w.limitReads);
  }

  @Test
  void absentPreviousIgnoresInvalidPreviousRawAndEnters() {
    var e = entry();
    var s = state(e);
    var w = new World();
    RogueModifierDynamicRange46.changed(
        s, KEY, new RogueResonanceVariables46.Change(7, 1, ONE, false), w);
    assertEquals(List.of(ENTER), w.sequences);
    assertTrue(e.inRange);
  }

  @Test
  void minimumIsInclusive() {
    var e = entry();
    var s = state(e);
    var w = new World();
    changed(s, w, -ONE, 0);
    assertTrue(e.inRange);
    assertEquals(List.of(ENTER), w.sequences);
  }

  @Test
  void maximumDefaultsToExclusive() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    changed(s, w, ONE, 10 * ONE);
    assertFalse(e.inRange);
    assertEquals(List.of(EXIT), w.sequences);
  }

  @Test
  void maximumCanBeInclusive() {
    var e = entry();
    e.config.maximumInclusive = true;
    var s = state(e);
    var w = new World();
    changed(s, w, ONE, 10 * ONE);
    assertTrue(e.inRange);
    assertEquals(List.of(ENTER), w.sequences);
  }

  @Test
  void invertedBoundsExitWithoutSwapping() {
    var e = entry();
    e.inRange = true;
    e.config.minimum = RogueResonanceDynamicCell46.Scalar.fixed(5 * ONE);
    e.config.maximum = RogueResonanceDynamicCell46.Scalar.fixed(ONE);
    var s = state(e);
    var w = new World();
    changed(s, w, ONE, 2 * ONE);
    assertFalse(e.inRange);
    assertEquals(List.of(EXIT), w.sequences);
  }

  @Test
  void unchangedOutsideRangeDoesNotExecute() {
    var e = entry();
    var s = state(e);
    var w = new World();
    changed(s, w, -2 * ONE, -ONE);
    assertTrue(w.sequences.isEmpty());
    assertEquals(0, w.poolReads);
  }

  @Test
  void rangeStateCommitsBeforeEnter() {
    var e = entry();
    var s = state(e);
    var w = new World();
    w.callback =
        q -> {
          assertTrue(e.inRange);
          assertEquals(1, e.enterDepth);
          assertEquals(new Frame(s.replacement, 7, 3), s.gameMode.callbackFrames.getLast());
        };
    changed(s, w, 0, ONE);
    assertEquals(0, e.enterDepth);
  }

  @Test
  void enterFailurePreservesStateDepthAndFrame() {
    var e = entry();
    var s = state(e);
    var w = new World();
    w.callback =
        q -> {
          throw new IllegalStateException();
        };
    assertThrows(IllegalStateException.class, () -> changed(s, w, 0, ONE));
    assertTrue(e.inRange);
    assertEquals(1, e.enterDepth);
    assertEquals(1, s.gameMode.callbackFrames.size());
  }

  @Test
  void exitFailurePreservesStateDepthAndFrame() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    w.callback =
        q -> {
          throw new IllegalStateException();
        };
    assertThrows(IllegalStateException.class, () -> changed(s, w, ONE, -ONE));
    assertFalse(e.inRange);
    assertEquals(1, e.exitDepth);
    assertEquals(1, s.gameMode.callbackFrames.size());
  }

  @Test
  void missingSequenceStillUpdatesRangeState() {
    var e = entry();
    e.enterSequence = null;
    var s = state(e);
    var w = new World();
    changed(s, w, 0, ONE);
    assertTrue(e.inRange);
    assertTrue(w.sequences.isEmpty());
  }

  @Test
  void depthEqualToLimitStillExecutes() {
    var e = entry();
    e.enterDepth = 3;
    var s = state(e);
    var w = new World();
    w.callback = q -> assertEquals(4, e.enterDepth);
    changed(s, w, 0, ONE);
    assertEquals(List.of(ENTER), w.sequences);
    assertEquals(3, e.enterDepth);
  }

  @Test
  void depthAboveLimitSkipsButCommitsRange() {
    var e = entry();
    e.enterDepth = 4;
    var s = state(e);
    var w = new World();
    changed(s, w, 0, ONE);
    assertTrue(e.inRange);
    assertTrue(w.sequences.isEmpty());
    assertEquals(4, e.enterDepth);
  }

  @Test
  void negativeLimitByteBypassesDepthComparison() {
    var e = entry();
    e.enterDepth = 127;
    var s = state(e);
    var w = new World();
    w.limits = new Limits(255);
    w.callback = q -> assertEquals(-128, e.enterDepth);
    changed(s, w, 0, ONE);
    assertEquals(127, e.enterDepth);
    assertEquals(List.of(ENTER), w.sequences);
  }

  @Test
  void limitUsesLowSignedByte() {
    var e = entry();
    e.enterDepth = 1;
    var s = state(e);
    var w = new World();
    w.limits = new Limits(256);
    changed(s, w, 0, ONE);
    assertTrue(w.sequences.isEmpty());
    assertTrue(e.inRange);
  }

  @Test
  void signedNegativeDepthIsBelowPositiveLimit() {
    var e = entry();
    e.enterDepth = -128;
    var s = state(e);
    var w = new World();
    changed(s, w, 0, ONE);
    assertEquals(List.of(ENTER), w.sequences);
    assertEquals(-128, e.enterDepth);
  }

  @Test
  void callbackDepthMutationIsDecrementedFromCurrentValue() {
    var e = entry();
    var s = state(e);
    var w = new World();
    w.callback = q -> e.enterDepth = 20;
    changed(s, w, 0, ONE);
    assertEquals(19, e.enterDepth);
  }

  @Test
  void changedInsideRangeUsesActualAndSuppliedDelta() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    var extra = new Extra(CONTEXT, 7);
    w.callback =
        q -> {
          var p = w.pool.active.getLast();
          assertArrayEquals(new long[] {ONE, 4 * ONE}, p.values);
          assertSame(CONTEXT, p.additionalContext);
          assertEquals(7, p.extraHitCount);
          assertEquals(1, e.changeDepth);
        };
    groupChanged(s, List.of(e), change(ONE, 2 * ONE), 4 * ONE, extra, w);
    assertEquals(List.of(CHANGE), w.sequences);
    assertTrue(w.pool.active.isEmpty());
    assertEquals(1, w.pool.free.size());
    assertEquals(0, e.changeDepth);
  }

  @Test
  void unchangedInsideRangeWithoutClampedFlagSkips() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    groupChanged(s, List.of(e), change(ONE, ONE), ONE, new Extra(null, 0), w);
    assertTrue(w.sequences.isEmpty());
    assertEquals(0, w.poolReads);
  }

  @Test
  void clampedChangeWithZeroDeltaSkips() {
    var e = entry();
    e.inRange = true;
    e.config.triggerClampedChange = true;
    var s = state(e);
    var w = new World();
    groupChanged(s, List.of(e), change(ONE, ONE), 0, new Extra(null, 0), w);
    assertTrue(w.sequences.isEmpty());
  }

  @Test
  void clampedNonzeroDeltaExecutesWithZeroActualDifference() {
    var e = entry();
    e.inRange = true;
    e.config.triggerClampedChange = true;
    var s = state(e);
    var w = new World();
    w.callback = q -> assertArrayEquals(new long[] {0, -ONE}, w.pool.active.getLast().values);
    groupChanged(s, List.of(e), change(ONE, ONE), -ONE, new Extra(null, 0), w);
    assertEquals(List.of(CHANGE), w.sequences);
  }

  @Test
  void absentPreviousInsideRangeSkipsChangeEvenWithDelta() {
    var e = entry();
    e.inRange = true;
    e.config.triggerClampedChange = true;
    var s = state(e);
    var w = new World();
    groupChanged(
        s,
        List.of(e),
        new RogueResonanceVariables46.Change(7, 1, ONE, false),
        ONE,
        new Extra(null, 0),
        w);
    assertTrue(w.sequences.isEmpty());
    assertEquals(0, w.poolReads);
  }

  @Test
  void numericExtendedPreviousComparisonCanSkipBeforeArithmetic() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    groupChanged(s, List.of(e), change((1L << 26) | 1, ONE), 0, new Extra(null, 0), w);
    assertTrue(w.sequences.isEmpty());
    assertEquals(0, w.poolReads);
  }

  @Test
  void blockedChangeStillRegistersAndRecyclesParameters() {
    var e = entry();
    e.inRange = true;
    e.changeDepth = 4;
    var s = state(e);
    var w = new World();
    groupChanged(s, List.of(e), change(ONE, 2 * ONE), ONE, new Extra(null, 0), w);
    assertTrue(w.sequences.isEmpty());
    assertEquals(2, w.poolReads);
    assertTrue(w.pool.active.isEmpty());
    assertEquals(1, w.pool.free.size());
  }

  @Test
  void changeFailureRetainsParametersDepthAndFrame() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    w.callback =
        q -> {
          throw new IllegalStateException();
        };
    assertThrows(IllegalStateException.class, () -> changed(s, w, ONE, 2 * ONE));
    assertEquals(1, e.changeDepth);
    assertEquals(1, w.pool.active.size());
    assertTrue(w.pool.free.isEmpty());
    assertEquals(1, s.gameMode.callbackFrames.size());
  }

  @Test
  void sharedParametersAreCreatedOnceAndKeepCallbackMutation() {
    var a = entry();
    var b = entry();
    a.inRange = b.inRange = true;
    var s = state(a, b);
    var w = new World();
    var identities = new ArrayList<CallbackParameters>();
    w.callback =
        q -> {
          var p = w.pool.active.getLast();
          identities.add(p);
          if (identities.size() == 1) p.values[0] = 9 * ONE;
          else assertEquals(9 * ONE, p.values[0]);
        };
    changed(s, w, ONE, 2 * ONE);
    assertSame(identities.get(0), identities.get(1));
    assertEquals(2, w.poolReads);
  }

  @Test
  void allEntriesUseOneCapturedLimitEvenWhenWorldChanges() {
    var a = entry();
    var b = entry();
    b.enterDepth = 3;
    var s = state(a, b);
    var w = new World();
    w.callback = q -> w.limits = new Limits(0);
    changed(s, w, 0, ONE);
    assertEquals(2, w.sequences.size());
    assertEquals(1, w.limitReads);
  }

  @Test
  void appendedEntriesUseCurrentCount() {
    var a = entry();
    var b = entry();
    var s = state(a);
    var w = new World();
    w.callback =
        q -> {
          if (w.sequences.size() == 1) s.listenerGroups.get(KEY).add(b);
        };
    changed(s, w, 0, ONE);
    assertTrue(b.inRange);
    assertEquals(2, w.sequences.size());
  }

  @Test
  void clearedEntriesStopWithoutFailFastEnumerator() {
    var a = entry();
    var b = entry();
    var s = state(a, b);
    var w = new World();
    w.callback = q -> s.listenerGroups.get(KEY).clear();
    changed(s, w, 0, ONE);
    assertFalse(b.inRange);
    assertEquals(1, w.sequences.size());
  }

  @Test
  void dictionaryReplacementDoesNotReplaceCapturedGroup() {
    var a = entry();
    var b = entry();
    var c = entry();
    var s = state(a, b);
    var w = new World();
    w.callback = q -> s.listenerGroups = new HashMap<>(Map.of(KEY, List.of(c)));
    changed(s, w, 0, ONE);
    assertTrue(b.inRange);
    assertFalse(c.inRange);
  }

  @Test
  void currentFrameListIsPoppedAfterCallbackReplacement() {
    var e = entry();
    var s = state(e);
    var old = s.gameMode;
    var newer = new GameMode(new ArrayList<>(List.of(new Frame(CONTEXT, 88, 9))));
    var w = new World();
    w.callback = q -> s.gameMode = newer;
    changed(s, w, 0, ONE);
    assertEquals(1, old.callbackFrames.size());
    assertTrue(newer.callbackFrames.isEmpty());
  }

  @Test
  void clearedCurrentFramesFailAfterSequenceAndDepthRestoration() {
    var e = entry();
    var s = state(e);
    var w = new World();
    w.callback = q -> s.gameMode.callbackFrames.clear();
    assertThrows(IllegalArgumentException.class, () -> changed(s, w, 0, ONE));
    assertTrue(e.inRange);
    assertEquals(0, e.enterDepth);
  }

  @Test
  void missingLimitsFailsBeforeReadingEmptyGroup() {
    var s = state();
    var w = new World();
    w.limits = null;
    assertThrows(IllegalArgumentException.class, () -> changed(s, w, 0, ONE));
    assertEquals(1, s.gameMode.callbackFrames.size());
  }

  @Test
  void nullEntryFailsAfterEarlierEntryCommitted() {
    var e = entry();
    var s = state(e, (Entry) null);
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> changed(s, w, 0, ONE));
    assertTrue(e.inRange);
    assertEquals(List.of(ENTER), w.sequences);
    assertEquals(1, s.gameMode.callbackFrames.size());
  }

  @Test
  void maximumIsEvaluatedEvenWhenBelowMinimum() {
    var e = entry();
    e.config.maximum = null;
    var s = state(e);
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> changed(s, w, -2 * ONE, -ONE));
    assertEquals(2, w.contextReads);
    assertFalse(e.inRange);
  }

  @Test
  void fixedScalarStillRequiresCurrentContextButNotLookup() {
    var e = entry();
    var s = state(e);
    var w = new World();
    w.context = null;
    assertThrows(IllegalArgumentException.class, () -> changed(s, w, 0, ONE));
    assertEquals(0, w.lookups);
  }

  @Test
  void postfixScalarUsesActualRootLookup() {
    var e = entry();
    e.config.minimum =
        RogueResonanceDynamicCell46.Scalar.fromPostfix(
            0, new byte[] {1, 0, 17}, new long[0], new int[] {88});
    var s = state(e);
    var w = new World();
    changed(s, w, 0, ONE);
    assertFalse(e.inRange);
    assertEquals(1, w.lookups);
  }

  @Test
  void configIsRereadAfterMinimumEvaluation() {
    var e = entry();
    e.config.minimum =
        RogueResonanceDynamicCell46.Scalar.fromPostfix(
            0, new byte[] {1, 0, 17}, new long[0], new int[] {88});
    var s = state(e);
    var w = new World();
    w.lookup =
        new RogueResonanceLookup46() {
          public RogueResonanceVariables46 select(int scope, int key) {
            e.config =
                new Config(
                    RogueResonanceDynamicCell46.Scalar.fixed(8 * ONE),
                    RogueResonanceDynamicCell46.Scalar.fixed(ONE));
            return RogueResonanceVariables46.fromSourceDefaults(Map.of(88, 0L), null);
          }
        };
    changed(s, w, 0, 2 * ONE);
    assertFalse(e.inRange);
    assertEquals(2, w.contextReads);
  }

  @Test
  void reusedShortParameterArrayPreservesFirstWriteBeforeFailure() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    var p = new CallbackParameters();
    p.values = new long[1];
    w.pool.free.add(p);
    assertThrows(IllegalArgumentException.class, () -> changed(s, w, ONE, 2 * ONE));
    assertEquals(ONE, p.values[0]);
    assertSame(p, w.pool.active.getLast());
    assertEquals(0, e.changeDepth);
  }

  @Test
  void arithmeticFailureAfterPoolRegistrationPreservesParameters() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> groupChanged(s, List.of(e), change(1, ONE), ONE, new Extra(null, 0), w));
    assertEquals(1, w.pool.active.size());
    assertTrue(w.sequences.isEmpty());
  }

  @Test
  void missingExtraFailsAfterNumericParameterWrites() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> groupChanged(s, List.of(e), change(ONE, 2 * ONE), 3 * ONE, null, w));
    assertArrayEquals(new long[] {ONE, 3 * ONE}, w.pool.active.getLast().values);
    assertTrue(w.sequences.isEmpty());
  }

  @Test
  void currentPoolIsRereadAndItsLastParametersAreRecycled() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    var original = w.pool;
    var next = new ParameterPool();
    var other = new CallbackParameters();
    next.register(other);
    w.callback = q -> w.pool = next;
    changed(s, w, ONE, 2 * ONE);
    assertEquals(1, original.active.size());
    assertSame(other, next.free.getLast());
    assertTrue(next.active.isEmpty());
    assertEquals(2, w.poolReads);
  }

  @Test
  void missingCurrentPoolOnReturnKeepsOriginalParametersAndFrame() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    var original = w.pool;
    w.callback = q -> w.pool = null;
    assertThrows(IllegalArgumentException.class, () -> changed(s, w, ONE, 2 * ONE));
    assertEquals(1, original.active.size());
    assertEquals(0, e.changeDepth);
    assertEquals(1, s.gameMode.callbackFrames.size());
  }

  @Test
  void changeSequenceIsRereadAfterPoolLookup() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w =
        new World() {
          public ParameterPool parameterPool(Object context) {
            e.changeSequence = ENTER;
            return super.parameterPool(context);
          }
        };
    changed(s, w, ONE, 2 * ONE);
    assertEquals(List.of(ENTER), w.sequences);
  }

  @Test
  void nestedEnterExitUpdatesSharedStateAndRetainsOuterFrames() {
    var e = entry();
    var s = state(e);
    var w = new World();
    w.callback =
        q -> {
          if (q == ENTER) {
            assertEquals(1, e.enterDepth);
            changed(s, w, ONE, -ONE);
            assertEquals(1, s.gameMode.callbackFrames.size());
          } else {
            assertEquals(1, e.exitDepth);
            assertEquals(2, s.gameMode.callbackFrames.size());
          }
        };
    changed(s, w, 0, ONE);
    assertEquals(List.of(ENTER, EXIT), w.sequences);
    assertFalse(e.inRange);
    assertEquals(0, e.enterDepth + e.exitDepth);
    assertTrue(s.gameMode.callbackFrames.isEmpty());
  }

  @Test
  void nestedChangeParametersRestoreOuterPoolTop() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    w.callback =
        q -> {
          if (e.changeDepth == 1) {
            var outer = w.pool.active.getLast();
            changed(s, w, 2 * ONE, 3 * ONE);
            assertSame(outer, w.pool.active.getLast());
            assertArrayEquals(new long[] {ONE, ONE}, outer.values);
          } else assertEquals(2, w.pool.active.size());
        };
    changed(s, w, ONE, 2 * ONE);
    assertTrue(w.pool.active.isEmpty());
    assertEquals(2, w.pool.free.size());
    assertEquals(0, e.changeDepth);
  }

  @Test
  void negativeHashBitsArePreservedInFrames() {
    var e = entry();
    var s = state();
    long key = RogueModifierCasterChange46.scopedKey(Integer.MIN_VALUE);
    s.listenerGroups.put(key, List.of(e));
    var w = new World();
    w.callback = q -> assertEquals(Integer.MIN_VALUE, s.gameMode.callbackFrames.getLast().key());
    RogueModifierDynamicRange46.changed(
        s, key, new RogueResonanceVariables46.Change(Integer.MIN_VALUE, 0, ONE), w);
    assertTrue(e.inRange);
  }

  @Test
  void excessiveGroupsRetainSuccessfulPrefix() {
    var entries = new ArrayList<Entry>();
    for (int i = 0; i < 256; i++) entries.add(entry());
    var s = state();
    s.listenerGroups.put(KEY, entries);
    var w = new World();
    assertThrows(IllegalArgumentException.class, () -> changed(s, w, 0, ONE));
    assertEquals(255, w.sequences.size());
    assertTrue(entries.get(254).inRange);
    assertFalse(entries.get(255).inRange);
    assertEquals(1, s.gameMode.callbackFrames.size());
  }

  @Test
  void actualCasterRootWritesTriggerRangeEnterChangeAndExit() {
    var e = entry();
    var s = state(e);
    var w = new World();
    var caster =
        new RogueModifierCasterChange46.State(
            s.replacement, (a, key, c) -> RogueModifierDynamicRange46.changed(s, key, c, w));
    caster.listenScopeMask = 16;
    var cw = new RogueModifierCasterChange46Test.World();
    RogueModifierCasterChange46.changed(caster, cw);
    var values = cw.values.get(RogueModifierCasterChange46Test.B);
    values.write(7, 2 * ONE);
    values.write(7, 3 * ONE);
    values.write(7, -ONE);
    assertEquals(List.of(ENTER, CHANGE, EXIT), w.sequences);
    assertFalse(e.inRange);
    assertTrue(w.pool.active.isEmpty());
    assertTrue(s.gameMode.callbackFrames.isEmpty());
  }

  @Test
  void sourceInjectionWithAbsentPreviousDoesNotCreateFalseChange() {
    var e = entry();
    e.inRange = true;
    var s = state(e);
    var w = new World();
    var values = RogueResonanceVariables46.fromSourceDefaults(Map.of(7, ONE), null);
    values.combineObserver(
        new RogueResonanceObserver46.Listener(
            s,
            new Object(),
            c ->
                RogueModifierDynamicRange46.changed(
                    s, RogueModifierCasterChange46.scopedKey(c.key()), c, w)));
    values.injectSourceStandard(7, 2 * ONE);
    assertTrue(w.sequences.isEmpty());
    assertTrue(e.inRange);
    assertEquals(0, w.poolReads);
  }
}
