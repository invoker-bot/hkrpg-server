package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class RogueModifierNumbers46Test {
  static final long ONE = 1L << 33;
  static final Object SOURCE = new Object(), OVERRIDE = new Object();
  static final Supplier<Object> SOURCE_CONTEXT = () -> SOURCE, OVERRIDE_CONTEXT = () -> OVERRIDE;

  static RogueModifierNumbers46.Dynamic raw(long value) {
    return context -> value;
  }

  @Test
  void directAbsoluteDropsFractionRatherThanUsingOrdinaryRound() {
    assertEquals(
        OptionalInt.of(1),
        RogueModifierNumbers46.integer(
            raw(9 * ONE),
            SOURCE_CONTEXT,
            new RogueModifierNumbers46.Triplet(raw(ONE + ONE / 2), null, null),
            OVERRIDE_CONTEXT));
  }

  @Test
  void affineAddsOneToPercentBeforeMultiplicationThenAddsOffset() {
    assertEquals(
        OptionalInt.of(4),
        RogueModifierNumbers46.integer(
            raw(2 * ONE),
            SOURCE_CONTEXT,
            new RogueModifierNumbers46.Triplet(null, raw(ONE / 2), raw(ONE)),
            OVERRIDE_CONTEXT));
  }

  @Test
  void absentSourceCannotBeInventedFromAnAffineOverride() {
    assertEquals(
        OptionalInt.empty(),
        RogueModifierNumbers46.integer(
            null,
            SOURCE_CONTEXT,
            new RogueModifierNumbers46.Triplet(null, raw(ONE), raw(4 * ONE)),
            OVERRIDE_CONTEXT));
  }

  static final RogueModifierNumbers46.Triplet EMPTY =
      new RogueModifierNumbers46.Triplet(null, null, null);

  static RogueModifierNumbers46.Dynamic fail() {
    return context -> {
      throw new AssertionError("Unexpected numeric read");
    };
  }

  static RogueModifierNumbers46.Dynamic logged(
      String label, long value, Object expected, List<String> events) {
    return context -> {
      assertSame(expected, context);
      events.add(label);
      return value;
    };
  }

  @Test
  void ordinaryMidpointsChooseEvenOnBothSidesOfZero() {
    for (long halves : new long[] {-5, -3, -1, 1, 3, 5}) {
      int expected =
          switch ((int) halves) {
            case -5, -3 -> -2;
            case -1, 1 -> 0;
            default -> 2;
          };
      assertEquals(
          OptionalInt.of(expected),
          RogueModifierNumbers46.integer(raw(halves * ONE / 2), SOURCE_CONTEXT, null, null));
    }
  }

  @Test
  void ordinarySentinelZeroAndAbsenceKeepDistinctPresence() {
    assertEquals(
        OptionalInt.of(-1), RogueModifierNumbers46.integer(raw(-ONE), SOURCE_CONTEXT, null, null));
    assertEquals(
        OptionalInt.of(0), RogueModifierNumbers46.integer(raw(0), SOURCE_CONTEXT, null, null));
    assertEquals(OptionalInt.empty(), RogueModifierNumbers46.integer(null, null, null, null));
  }

  @Test
  void emptyOverrideTripletFallsBackToOrdinaryRounding() {
    assertEquals(
        OptionalInt.of(2),
        RogueModifierNumbers46.integer(raw(3 * ONE / 2), SOURCE_CONTEXT, EMPTY, OVERRIDE_CONTEXT));
  }

  @Test
  void missingOverrideContextIgnoresEveryOverrideCell() {
    assertEquals(
        OptionalInt.of(2),
        RogueModifierNumbers46.integer(
            raw(3 * ONE / 2),
            SOURCE_CONTEXT,
            new RogueModifierNumbers46.Triplet(fail(), fail(), fail()),
            () -> null));
  }

  @Test
  void absentSourceNeedsNoSourceContext() {
    assertEquals(
        OptionalInt.empty(), RogueModifierNumbers46.integer(null, null, EMPTY, OVERRIDE_CONTEXT));
  }

  @Test
  void absentSourceDoesNotReadAffineExpressionsOrTheirContextsAgain() {
    AtomicInteger reads = new AtomicInteger();
    assertEquals(
        OptionalInt.empty(),
        RogueModifierNumbers46.integer(
            null,
            null,
            new RogueModifierNumbers46.Triplet(null, fail(), fail()),
            () -> {
              reads.incrementAndGet();
              return OVERRIDE;
            }));
    assertEquals(1, reads.get());
  }

  @Test
  void absoluteCanProvideValueForAnAbsentSource() {
    assertEquals(
        OptionalInt.of(6),
        RogueModifierNumbers46.integer(
            null,
            null,
            new RogueModifierNumbers46.Triplet(raw(13 * ONE / 2), null, null),
            OVERRIDE_CONTEXT));
  }

  @Test
  void absoluteIgnoresSourceAndAffineCells() {
    assertEquals(
        OptionalInt.of(3),
        RogueModifierNumbers46.integer(
            fail(),
            null,
            new RogueModifierNumbers46.Triplet(raw(3 * ONE), fail(), fail()),
            OVERRIDE_CONTEXT));
  }

  @Test
  void negativeAbsoluteUsesSignedShiftIncludingFraction() {
    assertEquals(
        OptionalInt.of(-3),
        RogueModifierNumbers46.integer(
            null,
            null,
            new RogueModifierNumbers46.Triplet(raw(-5 * ONE / 2), null, null),
            OVERRIDE_CONTEXT));
  }

  @Test
  void absoluteRereadsTheCurrentContextAfterItsPresenceCheck() {
    Object later = new Object();
    AtomicInteger reads = new AtomicInteger();
    assertEquals(
        OptionalInt.of(1),
        RogueModifierNumbers46.integer(
            null,
            null,
            new RogueModifierNumbers46.Triplet(
                context -> {
                  assertSame(later, context);
                  return ONE;
                },
                null,
                null),
            () -> reads.getAndIncrement() == 0 ? OVERRIDE : later));
    assertEquals(2, reads.get());
  }

  @Test
  void disappearingAbsoluteContextCannotFallBackToSource() {
    AtomicInteger reads = new AtomicInteger();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierNumbers46.integer(
                fail(),
                null,
                new RogueModifierNumbers46.Triplet(fail(), null, null),
                () -> reads.getAndIncrement() == 0 ? OVERRIDE : null));
  }

  @Test
  void percentageOnlyPreservesBasePlusThePercentageIncrease() {
    assertEquals(
        OptionalInt.of(3),
        RogueModifierNumbers46.integer(
            raw(2 * ONE),
            SOURCE_CONTEXT,
            new RogueModifierNumbers46.Triplet(null, raw(ONE / 2), null),
            OVERRIDE_CONTEXT));
  }

  @Test
  void additiveOnlyPreservesOriginalBase() {
    assertEquals(
        OptionalInt.of(5),
        RogueModifierNumbers46.integer(
            raw(2 * ONE),
            SOURCE_CONTEXT,
            new RogueModifierNumbers46.Triplet(null, null, raw(3 * ONE)),
            OVERRIDE_CONTEXT));
  }

  @Test
  void negativePercentAndNegativeAddAreNotClamped() {
    assertEquals(
        OptionalInt.of(-3),
        RogueModifierNumbers46.integer(
            raw(4 * ONE),
            SOURCE_CONTEXT,
            new RogueModifierNumbers46.Triplet(null, raw(-3 * ONE / 2), raw(-ONE)),
            OVERRIDE_CONTEXT));
  }

  @Test
  void affineRoundsOnlyTheFinalResult() {
    assertEquals(
        OptionalInt.of(4),
        RogueModifierNumbers46.integer(
            raw(5 * ONE / 2),
            SOURCE_CONTEXT,
            new RogueModifierNumbers46.Triplet(null, raw(ONE / 2), null),
            OVERRIDE_CONTEXT));
  }

  @Test
  void evaluationsKeepSourcePercentThenAddOrderAndSeparateContexts() {
    List<String> events = new ArrayList<>();
    var triplet =
        new RogueModifierNumbers46.Triplet(
            null,
            logged("percent", ONE / 2, OVERRIDE, events),
            logged("add", ONE, OVERRIDE, events));
    assertEquals(
        OptionalInt.of(4),
        RogueModifierNumbers46.integer(
            logged("source", 2 * ONE, SOURCE, events),
            () -> {
              events.add("source-context");
              return SOURCE;
            },
            triplet,
            () -> {
              events.add("override-context");
              return OVERRIDE;
            }));
    assertEquals(
        List.of(
            "override-context",
            "source-context",
            "source",
            "override-context",
            "percent",
            "override-context",
            "add"),
        events);
  }

  @Test
  void sourceEvaluationMayChangeTheOverrideContextBeforePercentage() {
    Object later = new Object();
    AtomicReference<Object> context = new AtomicReference<>(OVERRIDE);
    assertEquals(
        OptionalInt.of(4),
        RogueModifierNumbers46.integer(
            c -> {
              context.set(later);
              return 2 * ONE;
            },
            SOURCE_CONTEXT,
            new RogueModifierNumbers46.Triplet(
                null,
                c -> {
                  assertSame(later, c);
                  return ONE;
                },
                null),
            context::get));
  }

  @Test
  void percentageEvaluationMayChangeTheContextBeforeAdditive() {
    Object later = new Object();
    AtomicReference<Object> context = new AtomicReference<>(OVERRIDE);
    assertEquals(
        OptionalInt.of(5),
        RogueModifierNumbers46.integer(
            raw(2 * ONE),
            SOURCE_CONTEXT,
            new RogueModifierNumbers46.Triplet(
                null,
                c -> {
                  context.set(later);
                  return ONE;
                },
                c -> {
                  assertSame(later, c);
                  return ONE;
                }),
            context::get));
  }

  @Test
  void sourceFailureStopsBeforeAnyOverrideExpression() {
    RuntimeException failure = new RuntimeException("source");
    assertSame(
        failure,
        assertThrows(
            RuntimeException.class,
            () ->
                RogueModifierNumbers46.integer(
                    c -> {
                      throw failure;
                    },
                    SOURCE_CONTEXT,
                    new RogueModifierNumbers46.Triplet(null, fail(), fail()),
                    OVERRIDE_CONTEXT)));
  }

  @Test
  void percentFailureStopsBeforeAdditive() {
    RuntimeException failure = new RuntimeException("percent");
    assertSame(
        failure,
        assertThrows(
            RuntimeException.class,
            () ->
                RogueModifierNumbers46.integer(
                    raw(ONE),
                    SOURCE_CONTEXT,
                    new RogueModifierNumbers46.Triplet(
                        null,
                        c -> {
                          throw failure;
                        },
                        fail()),
                    OVERRIDE_CONTEXT)));
  }

  @Test
  void percentageFactorOverflowStopsBeforeAdditiveEvaluation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierNumbers46.integer(
                raw(ONE),
                SOURCE_CONTEXT,
                new RogueModifierNumbers46.Triplet(null, raw(Long.MAX_VALUE - 1), fail()),
                OVERRIDE_CONTEXT));
  }

  @Test
  void extendedSourceAndOverrideValuesRemainClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierNumbers46.integer(raw(1), SOURCE_CONTEXT, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierNumbers46.integer(
                null,
                null,
                new RogueModifierNumbers46.Triplet(raw(1), null, null),
                OVERRIDE_CONTEXT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierNumbers46.integer(
                raw(ONE),
                SOURCE_CONTEXT,
                new RogueModifierNumbers46.Triplet(null, raw(1), null),
                OVERRIDE_CONTEXT));
  }

  @Test
  void presentSourceRequiresCurrentContext() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierNumbers46.integer(raw(ONE), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierNumbers46.integer(raw(ONE), () -> null, null, null));
  }

  @Test
  void presentOverrideContextRequiresItsTriplet() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierNumbers46.integer(raw(ONE), SOURCE_CONTEXT, null, OVERRIDE_CONTEXT));
  }

  @Test
  void ordinaryResultOutsideTheVerifiedFiniteRangeRemainsClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierNumbers46.integer(raw(Long.MAX_VALUE - 1), SOURCE_CONTEXT, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierNumbers46.integer(raw(Long.MIN_VALUE), SOURCE_CONTEXT, null, null));
  }

  @Test
  void directAbsoluteShiftDoesNotInventTheOrdinaryRoundingGuard() {
    assertEquals(
        OptionalInt.of(-0x40000000),
        RogueModifierNumbers46.integer(
            null,
            null,
            new RogueModifierNumbers46.Triplet(raw(Long.MIN_VALUE), null, null),
            OVERRIDE_CONTEXT));
  }

  @Test
  void countPrecedesMaxLayerLifeTimeThenLayerAndIsNotOverridden() {
    List<String> events = new ArrayList<>();
    var config =
        new RogueModifierNumbers46.Config(
            logged("count", 5 * ONE, SOURCE, events),
            logged("max", 6 * ONE, SOURCE, events),
            logged("life", 7 * ONE, SOURCE, events),
            logged("layer", 8 * ONE, SOURCE, events));
    var result =
        RogueModifierNumbers46.resolve(
            config,
            SOURCE_CONTEXT,
            new RogueModifierNumbers46.Overrides(EMPTY, EMPTY, EMPTY, OVERRIDE_CONTEXT));
    assertEquals(List.of("count", "max", "life", "layer"), events);
    assertEquals(
        new RogueModifierNumbers46.Resolved(
            OptionalInt.of(5), OptionalInt.of(6), OptionalInt.of(7), OptionalInt.of(8)),
        result);
  }

  @Test
  void laterFieldFailureKeepsPriorEvaluationEffectsAndStopsFollowingCells() {
    List<String> events = new ArrayList<>();
    RuntimeException failure = new RuntimeException("life");
    var config =
        new RogueModifierNumbers46.Config(
            logged("count", ONE, SOURCE, events),
            logged("max", ONE, SOURCE, events),
            c -> {
              events.add("life");
              throw failure;
            },
            fail());
    assertSame(
        failure,
        assertThrows(
            RuntimeException.class,
            () -> RogueModifierNumbers46.resolve(config, SOURCE_CONTEXT, null)));
    assertEquals(List.of("count", "max", "life"), events);
  }

  @Test
  void invalidSourceAndOverrideProjectionsCannotCreateResolvedValues() {
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierNumbers46.resolve(null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierNumbers46.Overrides(null, EMPTY, EMPTY, OVERRIDE_CONTEXT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RogueModifierNumbers46.Overrides(EMPTY, EMPTY, EMPTY, null));
    assertEquals(
        new RogueModifierNumbers46.Resolved(
            OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty()),
        RogueModifierNumbers46.resolve(
            new RogueModifierNumbers46.Config(null, null, null, null), null, null));
  }
}
