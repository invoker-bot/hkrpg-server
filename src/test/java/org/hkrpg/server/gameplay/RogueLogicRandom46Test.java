package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RogueLogicRandom46Test {
  static final long ONE = 1L << 33;

  static class Counters implements RogueLogicRandom46.CounterBinding {
    final List<String> order = new ArrayList<>();
    int oldCount, newCount, global;

    public void notifyChange(int oldValue, int newValue) {
      order.add("notify");
      oldCount = oldValue;
      newCount = newValue;
    }

    public void publishGlobal(int value) {
      order.add("global");
      global = value;
    }
  }

  static JsonObject profile() {
    var stream =
        RogueLogicRandom46Test.class.getResourceAsStream("/fixtures/rogue-logic-random-4.6.json");
    assertNotNull(stream);
    return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
        .getAsJsonObject();
  }

  @Test
  void minimumSeedUsesMaximumMagnitudeSentinel() {
    var min = new RogueLogicRandom46.Seeded(Integer.MIN_VALUE);
    var max = new RogueLogicRandom46.Seeded(Integer.MAX_VALUE);
    for (int i = 0; i < 128; i++)
      assertEquals(max.nextIntSample(), min.nextIntSample(), "sample " + i);
  }

  @Test
  void counterNotificationSeesOldCountBeforeLocalAssignment() {
    var state = new RogueLogicRandom46[1];
    var counters =
        new Counters() {
          public void notifyChange(int oldValue, int newValue) {
            assertEquals(oldValue, state[0].callCount());
            super.notifyChange(oldValue, newValue);
          }
        };
    state[0] = new RogueLogicRandom46(0, counters);
    state[0].next();
    assertEquals(1, state[0].callCount());
  }

  @Test
  void projectionTruncatesInsteadOfRounding() {
    var record =
        profile()
            .getAsJsonObject("hostComparison")
            .getAsJsonArray("records")
            .get(0)
            .getAsJsonObject();
    var random = new RogueLogicRandom46(record.get("seed").getAsInt(), new Counters());
    int i = 0;
    for (var row : record.getAsJsonArray("values"))
      assertEquals(
          row.getAsJsonObject().get("standardRaw").getAsLong(), random.next(), "sample " + i++);
  }

  @Test
  void concreteSeedAndInitialCountAreRetained() {
    var random = new RogueLogicRandom46(-42, new Counters());
    assertEquals(-42, random.seed());
    assertEquals(0, random.callCount());
  }

  @Test
  void positiveAndNegativeMagnitudesShareBaseSequence() {
    for (int seed : List.of(1, 42, 123456789, 161803398, Integer.MAX_VALUE)) {
      var positive = new RogueLogicRandom46.Seeded(seed);
      var negative = new RogueLogicRandom46.Seeded(-seed);
      for (int i = 0; i < 1000; i++)
        assertEquals(positive.nextIntSample(), negative.nextIntSample());
    }
  }

  @Test
  void allHostDoubleBitsMatchSeededBaseSequence() {
    for (var element : profile().getAsJsonObject("hostComparison").getAsJsonArray("records")) {
      var record = element.getAsJsonObject();
      var random = new RogueLogicRandom46.Seeded(record.get("seed").getAsInt());
      int index = 0;
      for (var value : record.getAsJsonArray("values")) {
        assertEquals(
            Long.parseUnsignedLong(value.getAsJsonObject().get("doubleBits").getAsString(), 16),
            Double.doubleToRawLongBits(random.nextDouble()),
            "seed " + record.get("seed") + " sample " + index++);
      }
    }
  }

  @Test
  void allHostStandardSamplesMatchWrappedSequence() {
    for (var element : profile().getAsJsonObject("hostComparison").getAsJsonArray("records")) {
      var record = element.getAsJsonObject();
      var counters = new Counters();
      var random = new RogueLogicRandom46(record.get("seed").getAsInt(), counters);
      int index = 0;
      for (var value : record.getAsJsonArray("values")) {
        assertEquals(
            value.getAsJsonObject().get("standardRaw").getAsLong(),
            random.next(),
            "seed " + record.get("seed") + " sample " + index++);
      }
      assertEquals(128, random.callCount());
      assertEquals(128, counters.global);
    }
  }

  @Test
  void integerAndDoubleOperationsAdvanceSameStateExactlyOnce() {
    var integer = new RogueLogicRandom46.Seeded(42);
    var mixed = new RogueLogicRandom46.Seeded(42);
    for (int index = 0; index < 1000; index++) {
      int value = integer.nextIntSample();
      if ((index & 1) == 0) assertEquals(value, mixed.nextIntSample());
      else
        assertEquals(
            Double.doubleToRawLongBits(value * Double.longBitsToDouble(0x3e00000000200000L)),
            Double.doubleToRawLongBits(mixed.nextDouble()));
    }
  }

  @Test
  void inverseMultiplicationIsDistinctFromSampleDivision() {
    var integer = new RogueLogicRandom46.Seeded(0);
    var floating = new RogueLogicRandom46.Seeded(0);
    boolean differed = false;
    for (int index = 0; index < 128; index++) {
      int sample = integer.nextIntSample();
      double actual = floating.nextDouble();
      assertEquals(
          Double.doubleToRawLongBits(sample * Double.longBitsToDouble(0x3e00000000200000L)),
          Double.doubleToRawLongBits(actual));
      differed |=
          Double.doubleToRawLongBits(actual) != Double.doubleToRawLongBits(sample / 2147483647.0);
    }
    assertTrue(differed);
  }

  @Test
  void independentInstancesDoNotShareSeedArraysOrCounters() {
    var firstCounters = new Counters();
    var secondCounters = new Counters();
    var first = new RogueLogicRandom46(42, firstCounters);
    var second = new RogueLogicRandom46(42, secondCounters);
    long sample = first.next();
    first.next();
    assertEquals(sample, second.next());
    assertEquals(2, first.callCount());
    assertEquals(1, second.callCount());
    assertEquals(2, firstCounters.global);
    assertEquals(1, secondCounters.global);
  }

  @Test
  void changingSeedChangesKnownSequence() {
    var first = new RogueLogicRandom46.Seeded(0);
    var second = new RogueLogicRandom46.Seeded(1);
    assertNotEquals(first.nextIntSample(), second.nextIntSample());
  }

  @Test
  void manySamplesStayWithinOrdinaryStandardRange() {
    for (int seed : List.of(0, 42, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
      var random = new RogueLogicRandom46(seed, new Counters());
      for (int i = 0; i < 10000; i++) {
        long raw = random.next();
        assertTrue(raw >= 0 && raw < ONE);
        assertEquals(0, raw & 1);
      }
      assertEquals(10000, random.callCount());
    }
  }

  @Test
  void manyIntegerSamplesNeverReachMaximumOrBecomeNegative() {
    for (int seed : List.of(0, 42, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
      var random = new RogueLogicRandom46.Seeded(seed);
      for (int i = 0; i < 10000; i++) {
        int value = random.nextIntSample();
        assertTrue(value >= 0 && value < Integer.MAX_VALUE);
      }
    }
  }

  @Test
  void notificationAndGlobalWriteAreInOriginalOrder() {
    var counters = new Counters();
    var random = new RogueLogicRandom46(0, counters);
    random.next();
    assertEquals(List.of("notify", "global"), counters.order);
    assertEquals(0, counters.oldCount);
    assertEquals(1, counters.newCount);
    random.next();
    assertEquals(List.of("notify", "global", "notify", "global"), counters.order);
    assertEquals(1, counters.oldCount);
    assertEquals(2, counters.newCount);
  }

  @Test
  void globalPublicationSeesAlreadyAssignedLocalCount() {
    var state = new RogueLogicRandom46[1];
    var counters =
        new Counters() {
          public void publishGlobal(int value) {
            assertEquals(value, state[0].callCount());
            super.publishGlobal(value);
          }
        };
    state[0] = new RogueLogicRandom46(42, counters);
    state[0].next();
    assertEquals(1, counters.global);
  }

  @Test
  void failedNotificationLeavesLocalAndGlobalCountUnchanged() {
    var failure = new IllegalStateException("event unavailable");
    var counters =
        new Counters() {
          public void notifyChange(int oldValue, int newValue) {
            super.notifyChange(oldValue, newValue);
            throw failure;
          }
        };
    var random = new RogueLogicRandom46(0, counters);
    assertSame(failure, assertThrows(IllegalStateException.class, random::next));
    assertEquals(0, random.callCount());
    assertEquals(0, counters.global);
    assertEquals(List.of("notify"), counters.order);
  }

  @Test
  void notificationFailureStillConsumesTheRandomSample() {
    var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
    var counters =
        new Counters() {
          public void notifyChange(int oldValue, int newValue) {
            super.notifyChange(oldValue, newValue);
            if (fail.getAndSet(false)) throw new IllegalStateException("once");
          }
        };
    var random = new RogueLogicRandom46(0, counters);
    assertThrows(IllegalStateException.class, random::next);
    long second =
        profile()
            .getAsJsonObject("hostComparison")
            .getAsJsonArray("records")
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("values")
            .get(1)
            .getAsJsonObject()
            .get("standardRaw")
            .getAsLong();
    assertEquals(second, random.next());
    assertEquals(0, counters.oldCount);
    assertEquals(1, random.callCount());
  }

  @Test
  void explicitGlobalBindingFailureRetainsAssignedLocalCountAndConsumedDraw() {
    var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
    var counters =
        new Counters() {
          public void publishGlobal(int value) {
            order.add("global");
            if (fail.getAndSet(false)) throw new IllegalStateException("once");
            global = value;
          }
        };
    var random = new RogueLogicRandom46(0, counters);
    assertThrows(IllegalStateException.class, random::next);
    assertEquals(1, random.callCount());
    long second =
        profile()
            .getAsJsonObject("hostComparison")
            .getAsJsonArray("records")
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("values")
            .get(1)
            .getAsJsonObject()
            .get("standardRaw")
            .getAsLong();
    assertEquals(second, random.next());
    assertEquals(2, random.callCount());
    assertEquals(2, counters.global);
  }

  @Test
  void signedCounterIncrementWrapsWithoutCheckedOverflow() throws Exception {
    var counters = new Counters();
    var random = new RogueLogicRandom46(0, counters);
    var field = RogueLogicRandom46.class.getDeclaredField("calls");
    field.setAccessible(true);
    field.setInt(random, Integer.MAX_VALUE);
    random.next();
    assertEquals(Integer.MAX_VALUE, counters.oldCount);
    assertEquals(Integer.MIN_VALUE, counters.newCount);
    assertEquals(Integer.MIN_VALUE, random.callCount());
    assertEquals(Integer.MIN_VALUE, counters.global);
  }

  @Test
  void missingCounterBindingIsClosed() {
    assertThrows(IllegalArgumentException.class, () -> new RogueLogicRandom46(0, null));
  }

  @Test
  void chanceDecisionUsesSeededSampleOnlyOnce() {
    var counters = new Counters();
    var random = new RogueLogicRandom46(0, counters);
    var world =
        new RogueModifierChanceRoll46Test.BoundWorld() {
          public long sample(Object target) {
            order.add("draw");
            return random.next();
          }
        };
    world.statusResistance = ONE / 2;
    var result =
        RogueModifierChanceRoll46.decide(RogueModifierChanceRoll46Test.input(ONE, null), world);
    assertEquals(new RogueModifierChanceRoll46.Result(false, true, ONE / 2), result);
    assertEquals(1, random.callCount());
    assertEquals(List.of("notify", "global"), counters.order);
    assertEquals("resisted", world.order.getLast());
  }

  @Test
  void alwaysSuccessAndNegativeBypassDoNotAdvanceLogicRandom() {
    var counters = new Counters();
    var random = new RogueLogicRandom46(0, counters);
    var world =
        new RogueModifierChanceRoll46Test.BoundWorld() {
          public long sample(Object target) {
            return random.next();
          }
        };
    assertTrue(
        RogueModifierChanceRoll46.decide(
                RogueModifierChanceRoll46Test.input(ONE, List.of(69)), world)
            .successful());
    assertTrue(
        RogueModifierChanceRoll46.decide(RogueModifierChanceRoll46Test.input(-ONE, null), world)
            .successful());
    assertEquals(0, random.callCount());
    assertTrue(counters.order.isEmpty());
  }

  @Test
  void immunityDoesNotAdvanceLogicRandom() {
    var counters = new Counters();
    var random = new RogueLogicRandom46(0, counters);
    var world =
        new RogueModifierChanceRoll46Test.BoundWorld() {
          public long sample(Object target) {
            return random.next();
          }
        };
    world.statusImmune = true;
    assertFalse(
        RogueModifierChanceRoll46.decide(RogueModifierChanceRoll46Test.input(ONE, null), world)
            .successful());
    assertEquals(0, random.callCount());
    assertTrue(counters.order.isEmpty());
  }

  @Test
  void shieldConversionDoesNotAdvanceLogicRandom() {
    var counters = new Counters();
    var random = new RogueLogicRandom46(0, counters);
    var world =
        new RogueModifierChanceRoll46Test.BoundWorld() {
          public long sample(Object target) {
            return random.next();
          }
        };
    world.targetFlags.add(83);
    assertFalse(
        RogueModifierChanceRoll46.decide(
                RogueModifierChanceRoll46Test.input(ONE, List.of(10)), world)
            .successful());
    assertEquals(0, random.callCount());
    assertTrue(counters.order.isEmpty());
  }

  @Test
  void failedCounterNotificationStopsResistanceAndCallbackAfterConsumingDraw() {
    var counters =
        new Counters() {
          public void notifyChange(int oldValue, int newValue) {
            throw new IllegalStateException("event unavailable");
          }
        };
    var random = new RogueLogicRandom46(0, counters);
    var world =
        new RogueModifierChanceRoll46Test.BoundWorld() {
          public long sample(Object target) {
            order.add("draw");
            return random.next();
          }
        };
    assertThrows(
        IllegalStateException.class,
        () ->
            RogueModifierChanceRoll46.decide(
                RogueModifierChanceRoll46Test.input(ONE, List.of(7)), world));
    assertEquals("draw", world.order.getLast());
    assertFalse(world.order.contains("anti"));
    assertFalse(world.order.contains("property"));
    assertEquals(0, random.callCount());
  }
}
