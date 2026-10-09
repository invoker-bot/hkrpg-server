package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class RogueModifierChance46Test {
  static final long ONE = 1L << 33;
  static final Object SOURCE = new Object(), OVERRIDE = new Object();

  static RogueModifierNumbers46.Dynamic raw(long value) {
    return context -> value;
  }

  @Test
  void directChanceOverrideStillReadsTheSourceFirst() {
    AtomicInteger reads = new AtomicInteger();
    assertEquals(
        ONE,
        RogueModifierChance46.calculate(
            context -> {
              assertSame(SOURCE, context);
              reads.incrementAndGet();
              return 0L;
            },
            () -> SOURCE,
            new RogueModifierNumbers46.Triplet(raw(ONE), null, null),
            () -> OVERRIDE,
            null));
    assertEquals(1, reads.get());
  }

  @Test
  void negativeBaseSkipsAffineOverridesAndExternalAdjustments() {
    var override = new RogueModifierNumbers46.Triplet(null, raw(ONE), raw(3 * ONE));
    assertEquals(
        -ONE,
        RogueModifierChance46.calculate(raw(-ONE), () -> SOURCE, override, () -> OVERRIDE, null));
  }

  @Test
  void externalPercentagesAreSummedBeforeOneMultiplication() {
    var triple = new RogueModifierNumbers46.Triplet(null, raw(ONE / 2), null);
    var adjustment = new RogueModifierChance46.Adjustment("entry", triple, () -> OVERRIDE, null);
    assertEquals(2 * ONE, RogueModifierChance46.aggregate(ONE, List.of(adjustment, adjustment)));
  }

  static final RogueModifierNumbers46.Triplet EMPTY = triple(null, null, null);

  static RogueModifierNumbers46.Triplet triple(Long absolute, Long percent, Long additive) {
    return new RogueModifierNumbers46.Triplet(
        absolute == null ? null : raw(absolute),
        percent == null ? null : raw(percent),
        additive == null ? null : raw(additive));
  }

  static RogueModifierChance46.Adjustment adjustment(String name, List<Integer> flags) {
    return new RogueModifierChance46.Adjustment(name, EMPTY, null, flags);
  }

  static long chance(long source, RogueModifierNumbers46.Triplet triple, Supplier<Object> context) {
    return RogueModifierChance46.calculate(raw(source), () -> SOURCE, triple, context, null);
  }

  static Supplier<Object> forbidden() {
    return () -> {
      fail("Unexpected context read");
      return null;
    };
  }

  @Test
  void sourceFailurePrecedesOverrideContextRead() {
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueModifierChance46.calculate(
                c -> {
                  throw new IllegalStateException();
                },
                () -> SOURCE,
                triple(ONE, null, null),
                forbidden(),
                null));
  }

  @Test
  void sourceCallbackMayInstallOverrideContext() {
    AtomicReference<Object> context = new AtomicReference<>();
    assertEquals(
        ONE,
        RogueModifierChance46.calculate(
            c -> {
              context.set(OVERRIDE);
              return 0L;
            },
            () -> SOURCE,
            triple(ONE, null, null),
            context::get,
            null));
  }

  @Test
  void absoluteUsesPresenceContextWithoutASecondRead() {
    AtomicInteger reads = new AtomicInteger();
    assertEquals(
        ONE / 2,
        chance(
            0,
            triple(ONE / 2, null, null),
            () -> {
              assertEquals(1, reads.incrementAndGet());
              return OVERRIDE;
            }));
  }

  @Test
  void absoluteCanReplaceNegativeSentinelAndIgnoresOtherCells() {
    var override =
        new RogueModifierNumbers46.Triplet(
            raw(2 * ONE),
            c -> {
              fail();
              return 0;
            },
            c -> {
              fail();
              return 0;
            });
    assertEquals(2 * ONE, chance(-ONE, override, () -> OVERRIDE));
  }

  @Test
  void negativeSentinelDoesNotReadAffineContexts() {
    AtomicInteger reads = new AtomicInteger();
    assertEquals(
        -2L,
        chance(
            -2L,
            triple(null, ONE, ONE),
            () -> {
              assertEquals(1, reads.incrementAndGet());
              return OVERRIDE;
            }));
  }

  @Test
  void absentOverrideContextFallsBackWithoutReadingAnyOverrideCells() {
    assertEquals(ONE / 2, chance(ONE / 2, null, () -> null));
    assertEquals(ONE / 2, chance(ONE / 2, triple(ONE, ONE, ONE), null));
  }

  @Test
  void presentContextRequiresExplicitTriplet() {
    assertThrows(IllegalArgumentException.class, () -> chance(ONE, null, () -> OVERRIDE));
  }

  @Test
  void affineUsesFreshContextForEachDynamicCell() {
    List<String> order = new ArrayList<>();
    Object second = new Object(), third = new Object();
    AtomicInteger reads = new AtomicInteger();
    var override =
        new RogueModifierNumbers46.Triplet(
            null,
            c -> {
              assertSame(second, c);
              order.add("percent");
              return ONE / 2;
            },
            c -> {
              assertSame(third, c);
              order.add("add");
              return ONE / 4;
            });
    assertEquals(
        7 * ONE / 4,
        chance(
            ONE,
            override,
            () -> {
              int n = reads.incrementAndGet();
              order.add("context" + n);
              return n == 1 ? OVERRIDE : n == 2 ? second : third;
            }));
    assertEquals(List.of("context1", "context2", "percent", "context3", "add"), order);
  }

  @Test
  void affineDisappearingContextFailsBeforeDynamicRead() {
    AtomicInteger reads = new AtomicInteger();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            chance(
                ONE,
                triple(null, ONE, null),
                () -> reads.getAndIncrement() == 0 ? OVERRIDE : null));
  }

  @Test
  void sourceAndOverrideUseTheirOwnContextIdentities() {
    assertEquals(
        ONE,
        RogueModifierChance46.calculate(
            c -> {
              assertSame(SOURCE, c);
              return 0;
            },
            () -> SOURCE,
            new RogueModifierNumbers46.Triplet(
                null,
                null,
                c -> {
                  assertSame(OVERRIDE, c);
                  return ONE;
                }),
            () -> OVERRIDE,
            null));
  }

  @Test
  void chanceRemainsFractionalAndIsNeverClampedOrRounded() {
    assertEquals(ONE / 4, chance(ONE / 2, triple(null, -ONE / 2, null), () -> OVERRIDE));
    assertEquals(3 * ONE, chance(ONE, triple(null, ONE, ONE), () -> OVERRIDE));
    assertEquals(-ONE, chance(ONE, triple(null, -2 * ONE, null), () -> OVERRIDE));
  }

  @Test
  void zeroBaseAllowsAdditiveChance() {
    assertEquals(ONE / 2, chance(0, triple(null, null, ONE / 2), () -> OVERRIDE));
  }

  @Test
  void emptyTripletLeavesRawChanceUntouched() {
    assertEquals(ONE / 8, chance(ONE / 8, EMPTY, () -> OVERRIDE));
  }

  @Test
  void missingSourceAndExtendedSourceRemainClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierChance46.calculate(
                null, () -> SOURCE, triple(ONE, null, null), () -> OVERRIDE, null));
    assertThrows(
        IllegalArgumentException.class, () -> chance(1L, triple(ONE, null, null), () -> OVERRIDE));
  }

  @Test
  void extendedOverrideAndAdjustmentRemainClosed() {
    assertThrows(
        IllegalArgumentException.class, () -> chance(0, triple(1L, null, null), () -> OVERRIDE));
    var a =
        new RogueModifierChance46.Adjustment(null, triple(null, 1L, null), () -> OVERRIDE, null);
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierChance46.aggregate(ONE, List.of(a)));
  }

  @Test
  void negativeAggregateSkipsEvenInvalidRecords() {
    List<RogueModifierChance46.Adjustment> list = new ArrayList<>();
    list.add(null);
    assertEquals(-2L, RogueModifierChance46.aggregate(-2L, list));
  }

  @Test
  void externalAbsoluteIsIgnoredWithoutAContextRead() {
    var a =
        new RogueModifierChance46.Adjustment(null, triple(3 * ONE, null, null), forbidden(), null);
    assertEquals(ONE, RogueModifierChance46.aggregate(ONE, List.of(a)));
  }

  @Test
  void externalAdditivesAreSummedAfterAllPercentages() {
    var a =
        new RogueModifierChance46.Adjustment(
            null, triple(null, ONE / 2, ONE), () -> OVERRIDE, null);
    var b =
        new RogueModifierChance46.Adjustment(
            null, triple(null, ONE / 2, 2 * ONE), () -> OVERRIDE, null);
    assertEquals(5 * ONE, RogueModifierChance46.aggregate(ONE, List.of(a, b)));
  }

  @Test
  void externalPercentCallbackMayChangeAdditiveContext() {
    AtomicReference<Object> context = new AtomicReference<>(SOURCE);
    var a =
        new RogueModifierChance46.Adjustment(
            null,
            new RogueModifierNumbers46.Triplet(
                null,
                c -> {
                  assertSame(SOURCE, c);
                  context.set(OVERRIDE);
                  return 0;
                },
                c -> {
                  assertSame(OVERRIDE, c);
                  return ONE;
                }),
            context::get,
            null);
    assertEquals(2 * ONE, RogueModifierChance46.aggregate(ONE, List.of(a)));
  }

  @Test
  void externalListCountIsReadAgainAfterEachCallback() {
    List<RogueModifierChance46.Adjustment> list = new ArrayList<>();
    var next =
        new RogueModifierChance46.Adjustment(null, triple(null, null, ONE), () -> OVERRIDE, null);
    list.add(
        new RogueModifierChance46.Adjustment(
            null,
            new RogueModifierNumbers46.Triplet(
                null,
                c -> {
                  list.add(next);
                  return ONE;
                },
                null),
            () -> OVERRIDE,
            null));
    assertEquals(3 * ONE, RogueModifierChance46.aggregate(ONE, list));
  }

  @Test
  void earlierExternalFailurePreventsLaterReads() {
    var a =
        new RogueModifierChance46.Adjustment(
            null,
            new RogueModifierNumbers46.Triplet(
                null,
                c -> {
                  throw new IllegalStateException();
                },
                c -> {
                  fail();
                  return 0;
                }),
            () -> OVERRIDE,
            null);
    var b = new RogueModifierChance46.Adjustment(null, triple(null, ONE, ONE), forbidden(), null);
    assertThrows(
        IllegalStateException.class, () -> RogueModifierChance46.aggregate(ONE, List.of(a, b)));
  }

  @Test
  void namedEntryUsesOrdinalContentAndSkipsBehaviorFlags() {
    var a = adjustment("\ud83d\ude00A", List.of(84));
    assertTrue(
        RogueModifierChance46.matches(
            new String("\ud83d\ude00A"),
            f -> {
              fail();
              return false;
            },
            a,
            null));
    assertFalse(
        RogueModifierChance46.matches(
            "\ud83d\ude00a",
            null,
            a,
            f -> {
              fail();
              return true;
            }));
    assertFalse(RogueModifierChance46.matches(null, null, a, null));
  }

  @Test
  void missingUnnamedFlagsNeverMatch() {
    assertFalse(RogueModifierChance46.matches(null, null, null, null));
    assertFalse(RogueModifierChance46.matches(null, null, adjustment(null, null), null));
    assertFalse(RogueModifierChance46.matches(null, null, adjustment("", List.of()), null));
  }

  @Test
  void flagsAreMembershipTestsAndAnyUnmutedMatchWins() {
    List<Integer> checks = new ArrayList<>();
    assertTrue(
        RogueModifierChance46.matches(
            null,
            f -> f == 84,
            adjustment("", List.of(84, 12, 7)),
            f -> {
              checks.add(f);
              return true;
            }));
    assertEquals(List.of(84, 12), checks);
  }

  @Test
  void flagMismatchSkipsMutedComponentRead() {
    assertFalse(
        RogueModifierChance46.matches(
            null,
            f -> {
              fail();
              return false;
            },
            adjustment(null, List.of(84)),
            f -> false));
  }

  @Test
  void missingMuteSetAndMissingComponentDoNotMuteMatchingFlags() {
    var a = adjustment(null, List.of(84));
    assertTrue(RogueModifierChance46.matches(null, null, a, f -> true));
    assertTrue(RogueModifierChance46.matches(null, f -> null, a, f -> true));
    assertFalse(RogueModifierChance46.matches(null, f -> true, a, f -> true));
  }

  @Test
  void nonemptyUnnamedFlagsRequireDefinitionEvenWithoutComponent() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierChance46.matches(null, null, adjustment(null, List.of(84)), null));
  }

  @Test
  void collectorPreservesOrderDuplicatesAndObjectIdentity() {
    var a = adjustment("target", null);
    var b = adjustment("other", null);
    var result = RogueModifierChance46.collect("target", f -> false, List.of(a, b, a), null);
    assertEquals(2, result.size());
    assertSame(a, result.get(0));
    assertSame(a, result.get(1));
    assertNull(RogueModifierChance46.collect("none", f -> false, List.of(a), null));
  }

  @Test
  void absentComponentOrMappingSkipsEnumeration() {
    Iterable<RogueModifierChance46.Adjustment> forbidden =
        () -> {
          fail();
          return null;
        };
    assertNull(RogueModifierChance46.collect(null, null, forbidden, null));
    assertNull(RogueModifierChance46.collect(null, f -> false, null, null));
  }

  @Test
  void collectorPropagatesEnumerationFailure() {
    Iterable<RogueModifierChance46.Adjustment> invalid =
        () ->
            new Iterator<>() {
              public boolean hasNext() {
                throw new IllegalStateException();
              }

              public RogueModifierChance46.Adjustment next() {
                fail();
                return null;
              }
            };
    assertThrows(
        IllegalStateException.class,
        () -> RogueModifierChance46.collect(null, f -> false, invalid, null));
  }

  @Test
  void finiteGuardsRejectUnboundContextsAndOversizedLists() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierChance46.calculate(raw(ONE), null, null, null, null));
    List<RogueModifierChance46.Adjustment> list = new ArrayList<>();
    for (int i = 0; i < 256; i++) list.add(adjustment("same", null));
    assertThrows(IllegalArgumentException.class, () -> RogueModifierChance46.aggregate(ONE, list));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierChance46.collect("same", f -> false, list, null));
  }

  @Test
  void normalPoolReleaseUsesSameListAfterSourceEvaluation() {
    List<String> order = new ArrayList<>();
    var selected = new ArrayList<RogueModifierChance46.Adjustment>();
    var pool =
        new RogueModifierChance46.AdjustmentPool() {
          public List<RogueModifierChance46.Adjustment> collect() {
            order.add("collect");
            return selected;
          }

          public void release(List<RogueModifierChance46.Adjustment> list) {
            assertSame(selected, list);
            order.add("release");
          }
        };
    assertEquals(
        ONE,
        RogueModifierChance46.evaluate(
            c -> {
              order.add("source");
              return ONE;
            },
            () -> SOURCE,
            null,
            null,
            pool));
    assertEquals(List.of("collect", "source", "release"), order);
  }

  @Test
  void poolCollectionFailurePrecedesSourceRead() {
    var pool =
        new RogueModifierChance46.AdjustmentPool() {
          public List<RogueModifierChance46.Adjustment> collect() {
            throw new IllegalStateException();
          }

          public void release(List<RogueModifierChance46.Adjustment> list) {
            fail();
          }
        };
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueModifierChance46.evaluate(
                c -> {
                  fail();
                  return 0;
                },
                () -> SOURCE,
                null,
                null,
                pool));
  }

  @Test
  void chanceFailureDoesNotInventFinallyPoolRelease() {
    var pool =
        new RogueModifierChance46.AdjustmentPool() {
          public List<RogueModifierChance46.Adjustment> collect() {
            return new ArrayList<>();
          }

          public void release(List<RogueModifierChance46.Adjustment> list) {
            fail();
          }
        };
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueModifierChance46.evaluate(
                c -> {
                  throw new IllegalStateException();
                },
                () -> SOURCE,
                null,
                null,
                pool));
  }

  @Test
  void nullSelectedListHasNoReleaseAndReleaseFailurePropagates() {
    var pool =
        new RogueModifierChance46.AdjustmentPool() {
          public List<RogueModifierChance46.Adjustment> collect() {
            return null;
          }

          public void release(List<RogueModifierChance46.Adjustment> list) {
            fail();
          }
        };
    assertEquals(ONE, RogueModifierChance46.evaluate(raw(ONE), () -> SOURCE, null, null, pool));
    var bad =
        new RogueModifierChance46.AdjustmentPool() {
          public List<RogueModifierChance46.Adjustment> collect() {
            return new ArrayList<>();
          }

          public void release(List<RogueModifierChance46.Adjustment> list) {
            throw new IllegalStateException();
          }
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueModifierChance46.evaluate(raw(ONE), () -> SOURCE, null, null, bad));
  }
}
