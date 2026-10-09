package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RogueModifierResistance46Test {
  static final long ONE = 1L << 33;

  @Test
  void behaviorResistanceUsesTheMaximumRatherThanSum() {
    assertEquals(
        ONE / 2,
        RogueModifierResistance46.behaviorMaximum(
            List.of(12, 13), flag -> OptionalLong.of(ONE / 2), null));
  }

  @Test
  void antiFlagsOnlySkipValuesBelowOneAndRetainCompleteImmunity() {
    assertEquals(
        ONE,
        RogueModifierResistance46.behaviorMaximum(
            List.of(12), flag -> OptionalLong.of(ONE), List.of(12)));
  }

  @Test
  void statusAndBehaviorComplementsAreMultipliedSeparately() {
    assertEquals(
        ONE / 4,
        RogueModifierResistance46.statusFactor(
            () -> ONE / 2, List.of(12), flag -> OptionalLong.of(ONE / 2), null));
  }

  @Test
  void missingAndEmptyFlagsSkipEvenAbsentMappings() {
    assertEquals(0, RogueModifierResistance46.behaviorMaximum(null, null, null));
    assertEquals(0, RogueModifierResistance46.probabilityMaximum(List.of(), null));
  }

  @Test
  void nonemptyFlagsRequireTheCurrentMapping() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierResistance46.behaviorMaximum(List.of(12), null, null));
  }

  @Test
  void absentKeysAreSkippedWithoutInventingAValue() {
    assertEquals(
        0, RogueModifierResistance46.behaviorMaximum(List.of(12), f -> OptionalLong.empty(), null));
  }

  @Test
  void missingLookupResultsAreNotAbsentKeys() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierResistance46.behaviorMaximum(List.of(12), f -> null, null));
  }

  @Test
  void matchedInvalidValuePropagatesInsteadOfSkipping() {
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueModifierResistance46.behaviorMaximum(
                List.of(12),
                f -> {
                  throw new IllegalStateException();
                },
                null));
  }

  @Test
  void negativeBehaviorValuesCannotLowerInitialZero() {
    assertEquals(
        0,
        RogueModifierResistance46.behaviorMaximum(List.of(12), f -> OptionalLong.of(-ONE), null));
  }

  @Test
  void duplicatesDoNotAccumulateBehaviorResistance() {
    AtomicInteger reads = new AtomicInteger();
    assertEquals(
        ONE / 2,
        RogueModifierResistance46.behaviorMaximum(
            List.of(12, 12),
            f -> {
              reads.incrementAndGet();
              return OptionalLong.of(ONE / 2);
            },
            null));
    assertEquals(2, reads.get());
  }

  @Test
  void probabilityAlsoUsesMaximumWithZeroInitialValue() {
    assertEquals(
        ONE / 2,
        RogueModifierResistance46.probabilityMaximum(
            List.of(12, 13), f -> OptionalLong.of(f == 12 ? ONE / 4 : ONE / 2)));
    assertEquals(
        0, RogueModifierResistance46.probabilityMaximum(List.of(12), f -> OptionalLong.of(-ONE)));
  }

  @Test
  void antiFlagsSkipOnlyMatchingValuesBelowOne() {
    assertEquals(
        ONE / 4,
        RogueModifierResistance46.behaviorMaximum(
            List.of(12, 13), f -> OptionalLong.of(f == 12 ? ONE / 2 : ONE / 4), List.of(12)));
  }

  @Test
  void antiFlagAtOneRetainsValueAndAboveOneIsNotClamped() {
    assertEquals(
        2 * ONE,
        RogueModifierResistance46.behaviorMaximum(
            List.of(12), f -> OptionalLong.of(2 * ONE), List.of(12)));
    assertEquals(
        ONE - 2,
        RogueModifierResistance46.behaviorMaximum(
            List.of(12), f -> OptionalLong.of(ONE - 2), List.of(13)));
  }

  @Test
  void antiFlagThresholdHasExactStandardBoundary() {
    assertEquals(
        0,
        RogueModifierResistance46.behaviorMaximum(
            List.of(12), f -> OptionalLong.of(ONE - 2), List.of(12)));
    assertEquals(
        ONE + 2,
        RogueModifierResistance46.behaviorMaximum(
            List.of(12), f -> OptionalLong.of(ONE + 2), List.of(12)));
  }

  @Test
  void listCountAndValuesAreReadAgainAfterMappingCallbacks() {
    List<Integer> flags = new ArrayList<>();
    flags.add(12);
    assertEquals(
        ONE,
        RogueModifierResistance46.probabilityMaximum(
            flags,
            f -> {
              if (f == 12) {
                flags.add(13);
                return OptionalLong.of(ONE / 2);
              }
              return OptionalLong.of(ONE);
            }));
  }

  @Test
  void mappingFailureStopsLaterFlagReads() {
    List<Integer> reads = new ArrayList<>();
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueModifierResistance46.behaviorMaximum(
                List.of(12, 13),
                f -> {
                  reads.add(f);
                  throw new IllegalStateException();
                },
                null));
    assertEquals(List.of(12), reads);
  }

  @Test
  void statusPropertyPrecedesAnyBehaviorLookup() {
    List<String> order = new ArrayList<>();
    assertEquals(
        ONE / 4,
        RogueModifierResistance46.statusFactor(
            () -> {
              order.add("property");
              return ONE / 2;
            },
            List.of(12),
            f -> {
              order.add("mapping");
              return OptionalLong.of(ONE / 2);
            },
            null));
    assertEquals(List.of("property", "mapping"), order);
  }

  @Test
  void failingStatusPropertySkipsMappingReads() {
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueModifierResistance46.statusFactor(
                () -> {
                  throw new IllegalStateException();
                },
                List.of(12),
                f -> {
                  fail();
                  return OptionalLong.empty();
                },
                null));
  }

  @Test
  void failingPropertyComplementStopsBeforeBehaviorLookup() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierResistance46.statusFactor(
                () -> Long.MIN_VALUE,
                List.of(12),
                f -> {
                  fail();
                  return OptionalLong.empty();
                },
                null));
  }

  @Test
  void statusPropertyIsReadEvenWithNoFlags() {
    AtomicInteger reads = new AtomicInteger();
    assertEquals(
        ONE / 2,
        RogueModifierResistance46.statusFactor(
            () -> {
              reads.incrementAndGet();
              return ONE / 2;
            },
            null,
            null,
            null));
    assertEquals(1, reads.get());
  }

  @Test
  void behaviorFailurePreservesPriorPropertyRead() {
    AtomicInteger reads = new AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueModifierResistance46.statusFactor(
                () -> {
                  reads.incrementAndGet();
                  return 0;
                },
                List.of(12),
                f -> {
                  throw new IllegalStateException();
                },
                null));
    assertEquals(1, reads.get());
  }

  @Test
  void absentResistanceProducesOneAndCompleteResistanceProducesZero() {
    assertEquals(ONE, RogueModifierResistance46.statusFactor(() -> 0, null, null, null));
    assertEquals(0, RogueModifierResistance46.statusFactor(() -> ONE, null, null, null));
    assertEquals(
        0,
        RogueModifierResistance46.statusFactor(
            () -> 0, List.of(12), f -> OptionalLong.of(ONE), null));
  }

  @Test
  void complementProductsAreNotClampedOrRounded() {
    assertEquals(-ONE, RogueModifierResistance46.statusFactor(() -> 2 * ONE, null, null, null));
    assertEquals(
        ONE,
        RogueModifierResistance46.statusFactor(
            () -> 2 * ONE, List.of(12), f -> OptionalLong.of(2 * ONE), null));
    assertEquals(
        3 * ONE / 8,
        RogueModifierResistance46.statusFactor(
            () -> ONE / 2, List.of(12), f -> OptionalLong.of(ONE / 4), null));
  }

  @Test
  void negativeStatusPropertyCanIncreaseComplement() {
    assertEquals(2 * ONE, RogueModifierResistance46.statusFactor(() -> -ONE, null, null, null));
  }

  @Test
  void extraHitAddsPropertyToMaximumRatherThanOnePlusProperty() {
    assertEquals(
        3 * ONE / 4,
        RogueModifierResistance46.extraHit(
            () -> ONE / 4, List.of(12, 13), f -> OptionalLong.of(ONE / 2)));
    assertEquals(-ONE, RogueModifierResistance46.extraHit(() -> -ONE, null, null));
  }

  @Test
  void extraHitPropertyPrecedesMappingAndMayChangeItsValues() {
    AtomicInteger state = new AtomicInteger();
    assertEquals(
        ONE,
        RogueModifierResistance46.extraHit(
            () -> {
              state.set(1);
              return 0;
            },
            List.of(12),
            f -> {
              assertEquals(1, state.get());
              return OptionalLong.of(ONE);
            }));
  }

  @Test
  void missingPropertiesAndExtendedInputsStayClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierResistance46.statusFactor(null, null, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierResistance46.extraHit(null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierResistance46.statusFactor(() -> 1, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierResistance46.behaviorMaximum(List.of(12), f -> OptionalLong.of(1), null));
  }

  @Test
  void invalidFlagCellsAndUnboundedProjectionStayClosed() {
    List<Integer> flags = new ArrayList<>();
    flags.add(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierResistance46.probabilityMaximum(flags, f -> OptionalLong.of(0)));
    flags.clear();
    for (int i = 0; i < 256; i++) flags.add(12);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierResistance46.probabilityMaximum(flags, f -> OptionalLong.of(0)));
  }
}
