package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RogueModifierChanceRoll46Test {
  static final long ONE = 1L << 33;
  static final Object TARGET = new Object(),
      SOURCE = new Object(),
      OWNER = new Object(),
      COMPONENT = new Object();

  static class BoundWorld implements RogueModifierChanceRoll46.World {
    final List<String> order = new ArrayList<>();
    final Set<Integer> targetFlags = new HashSet<>();
    final Map<Integer, Long> resistances = new HashMap<>();
    final AtomicInteger draws = new AtomicInteger();
    Object component = COMPONENT, actual = OWNER, summoner = OWNER;
    int status = 2;
    boolean behaviorImmune, statusImmune;
    long sample = ONE / 2, statusResistance;
    List<Integer> anti, released, immuneFlags, resistFlags, empty = List.of();
    Object immuneSource, convertOwner, resistedSource;

    public int statusType(String name) {
      order.add("status");
      return status;
    }

    public Object targetComponent(Object target) {
      assertSame(TARGET, target);
      order.add("component");
      return component;
    }

    public Object actualOwner(Object source) {
      assertSame(SOURCE, source);
      order.add("owner");
      return actual;
    }

    public boolean hasBehavior(Object comp, int flag) {
      assertSame(component, comp);
      order.add("has" + flag);
      return targetFlags.contains(flag);
    }

    public Object summonerEntity(Object target) {
      assertSame(TARGET, target);
      order.add("summoner");
      return summoner;
    }

    public void notifyShieldConvert(Object comp, Object owner) {
      assertSame(component, comp);
      order.add("convert");
      convertOwner = owner;
    }

    public boolean behaviorImmune(Object comp, Object source, List<Integer> flags) {
      assertSame(component, comp);
      assertSame(SOURCE, source);
      order.add("behaviorImmune");
      return behaviorImmune;
    }

    public boolean statusImmune(Object comp, int type) {
      assertSame(component, comp);
      assertEquals(status, type);
      order.add("statusImmune");
      return statusImmune;
    }

    public List<Integer> emptyBehaviorFlags() {
      order.add("empty");
      return empty;
    }

    public void notifyImmune(Object comp, Object source, List<Integer> flags) {
      assertSame(component, comp);
      order.add("immune");
      immuneSource = source;
      immuneFlags = flags;
    }

    public long sample(Object target) {
      assertSame(TARGET, target);
      order.add("draw");
      draws.incrementAndGet();
      return sample;
    }

    public List<Integer> antiFlags(Object owner) {
      assertSame(actual, owner);
      order.add("anti");
      return anti;
    }

    public long statusResistance(Object comp) {
      assertSame(component, comp);
      order.add("property");
      return statusResistance;
    }

    public OptionalLong behaviorResistance(Object comp, int flag) {
      assertSame(component, comp);
      order.add("resistance" + flag);
      Long value = resistances.get(flag);
      return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    public void releaseAntiFlags(List<Integer> flags) {
      order.add("release");
      released = flags;
    }

    public void executeResistSequence(Object comp, List<Integer> flags, Object source) {
      assertSame(component, comp);
      order.add("resisted");
      resistFlags = flags;
      resistedSource = source;
    }
  }

  static RogueModifierChanceRoll46.Input input(long chance, List<Integer> flags) {
    return new RogueModifierChanceRoll46.Input(TARGET, SOURCE, "effect", chance, 0, () -> flags);
  }

  @Test
  void negativeChanceStillChecksImmunityBeforeBypass() {
    var world = new BoundWorld();
    world.statusImmune = true;
    assertEquals(
        new RogueModifierChanceRoll46.Result(false, true, 0),
        RogueModifierChanceRoll46.decide(input(-ONE, null), world));
    assertEquals(
        List.of(
            "status", "component", "owner", "behaviorImmune", "statusImmune", "empty", "immune"),
        world.order);
  }

  @Test
  void originalAndResistedChanceShareExactlyOneDraw() {
    var world = new BoundWorld();
    assertTrue(RogueModifierChanceRoll46.decide(input(ONE, null), world).successful());
    assertEquals(1, world.draws.get());
  }

  @Test
  void exactThresholdIsInclusiveForBothResults() {
    var world = new BoundWorld();
    assertEquals(
        new RogueModifierChanceRoll46.Result(true, true, ONE / 2),
        RogueModifierChanceRoll46.decide(input(ONE / 2, null), world));
  }

  @Test
  void alwaysSuccessSkipsOwnerImmunityAndInvalidNumericInputs() {
    var world = new BoundWorld();
    world.component = null;
    world.statusImmune = true;
    var request =
        new RogueModifierChanceRoll46.Input(TARGET, SOURCE, "effect", 1, 1, () -> List.of(69, 10));
    assertEquals(
        new RogueModifierChanceRoll46.Result(true, true, -ONE),
        RogueModifierChanceRoll46.decide(request, world));
    assertEquals(List.of("status", "component"), world.order);
  }

  @Test
  void statusLookupFailurePreventsComponentAndDefinitionReads() {
    var failure = new IllegalStateException("table unavailable");
    var world =
        new BoundWorld() {
          public int statusType(String name) {
            order.add("status");
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierChanceRoll46.decide(input(ONE, null), world)));
    assertEquals(List.of("status"), world.order);
  }

  @Test
  void missingTargetFailsAfterStatusLookup() {
    var world = new BoundWorld();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierChanceRoll46.decide(
                new RogueModifierChanceRoll46.Input(null, SOURCE, "effect", ONE, 0, () -> null),
                world));
    assertEquals(List.of("status"), world.order);
  }

  @Test
  void missingDefinitionFailsAfterTargetComponentLookup() {
    var world = new BoundWorld();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierChanceRoll46.decide(
                new RogueModifierChanceRoll46.Input(TARGET, SOURCE, "effect", ONE, 0, null),
                world));
    assertEquals(List.of("status", "component"), world.order);
  }

  @Test
  void missingWorldOrRequestIsClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierChanceRoll46.decide(input(ONE, null), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierChanceRoll46.decide(null, new BoundWorld()));
  }

  @Test
  void bothImmunityQueriesRunEvenWhenBehaviorImmune() {
    var world = new BoundWorld();
    world.behaviorImmune = true;
    var flags = List.of(7);
    assertEquals(
        new RogueModifierChanceRoll46.Result(false, true, 0),
        RogueModifierChanceRoll46.decide(input(ONE, flags), world));
    assertEquals(
        List.of("status", "component", "owner", "behaviorImmune", "statusImmune", "immune"),
        world.order);
    assertSame(flags, world.immuneFlags);
    assertSame(SOURCE, world.immuneSource);
  }

  @Test
  void otherStatusTypesDoNotEmitDebuffImmuneNotification() {
    for (int type : List.of(0, 1, 3)) {
      var world = new BoundWorld();
      world.status = type;
      world.statusImmune = true;
      assertEquals(
          new RogueModifierChanceRoll46.Result(false, true, 0),
          RogueModifierChanceRoll46.decide(input(-ONE, null), world));
      assertEquals(
          List.of("status", "component", "owner", "behaviorImmune", "statusImmune"), world.order);
    }
  }

  @Test
  void nullDebuffFlagsUseExplicitEmptyBinding() {
    var world = new BoundWorld();
    world.statusImmune = true;
    world.empty = new ArrayList<>();
    RogueModifierChanceRoll46.decide(input(ONE, null), world);
    assertSame(world.empty, world.immuneFlags);
    assertSame(SOURCE, world.immuneSource);
  }

  @Test
  void unknownOrNonemptyEmptyListBindingFailsBeforeNotification() {
    for (List<Integer> empty : java.util.Arrays.asList(null, List.of(7))) {
      var world = new BoundWorld();
      world.statusImmune = true;
      world.empty = empty;
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueModifierChanceRoll46.decide(input(ONE, null), world));
      assertEquals("empty", world.order.getLast());
      assertNull(world.immuneSource);
    }
  }

  @Test
  void immuneResultDoesNotValidateUnusedNumericInputs() {
    var world = new BoundWorld();
    world.statusImmune = true;
    var request =
        new RogueModifierChanceRoll46.Input(TARGET, SOURCE, "effect", 1, 1, () -> List.of());
    assertEquals(
        new RogueModifierChanceRoll46.Result(false, true, 0),
        RogueModifierChanceRoll46.decide(request, world));
    assertEquals(0, world.draws.get());
  }

  @Test
  void negativeChanceCanonicalizesSentinelAfterImmunityAndSkipsRemainingInputs() {
    var world = new BoundWorld();
    world.component = null;
    var request =
        new RogueModifierChanceRoll46.Input(TARGET, SOURCE, "effect", -2 * ONE, 1, () -> null);
    assertEquals(
        new RogueModifierChanceRoll46.Result(true, true, -ONE),
        RogueModifierChanceRoll46.decide(request, world));
    assertEquals(
        List.of("status", "component", "owner", "behaviorImmune", "statusImmune"), world.order);
  }

  @Test
  void shieldRejectsUnrelatedSourceBeforeConversionAndImmunity() {
    var world = new BoundWorld();
    world.targetFlags.addAll(List.of(70, 83));
    world.summoner = new Object();
    assertEquals(
        new RogueModifierChanceRoll46.Result(false, true, 0),
        RogueModifierChanceRoll46.decide(input(-ONE, List.of(10)), world));
    assertEquals(List.of("status", "component", "owner", "has70", "summoner"), world.order);
  }

  @Test
  void targetAsActualOwnerSkipsSummonerLookup() {
    var world = new BoundWorld();
    world.actual = TARGET;
    world.summoner = new Object();
    world.targetFlags.add(70);
    assertTrue(RogueModifierChanceRoll46.decide(input(-ONE, List.of(10)), world).successful());
    assertEquals(
        List.of("status", "component", "owner", "has70", "has83", "behaviorImmune", "statusImmune"),
        world.order);
  }

  @Test
  void summonerAsActualOwnerPassesIdentityRestriction() {
    var world = new BoundWorld();
    world.targetFlags.add(70);
    assertTrue(RogueModifierChanceRoll46.decide(input(-ONE, List.of(10)), world).successful());
    assertTrue(world.order.indexOf("summoner") < world.order.indexOf("has83"));
  }

  @Test
  void absentSummonerAndAbsentActualOwnerCompareEqual() {
    var world = new BoundWorld();
    world.actual = null;
    world.summoner = null;
    world.targetFlags.add(70);
    assertTrue(RogueModifierChanceRoll46.decide(input(-ONE, List.of(10)), world).successful());
    assertTrue(world.order.contains("statusImmune"));
  }

  @Test
  void shieldRestrictionUsesIdentityRatherThanEquality() {
    var world = new BoundWorld();
    world.actual = new String("owner");
    world.summoner = new String("owner");
    world.targetFlags.add(70);
    assertNotSame(world.actual, world.summoner);
    assertEquals(world.actual, world.summoner);
    assertFalse(RogueModifierChanceRoll46.decide(input(ONE, List.of(10)), world).successful());
    assertFalse(world.order.contains("has83"));
  }

  @Test
  void conversionReceivesActualOwnerAndSkipsImmunityAndSample() {
    var world = new BoundWorld();
    world.targetFlags.add(83);
    assertEquals(
        new RogueModifierChanceRoll46.Result(false, true, 0),
        RogueModifierChanceRoll46.decide(input(ONE, List.of(10)), world));
    assertSame(OWNER, world.convertOwner);
    assertEquals(List.of("status", "component", "owner", "has70", "has83", "convert"), world.order);
  }

  @Test
  void shieldNeedsComponentBeforeAnyBehaviorQuery() {
    var world = new BoundWorld();
    world.component = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierChanceRoll46.decide(input(-ONE, List.of(10)), world));
    assertEquals(List.of("status", "component", "owner"), world.order);
  }

  @Test
  void nonShieldSkipsTargetShieldQueries() {
    var world = new BoundWorld();
    world.targetFlags.addAll(List.of(70, 83));
    assertTrue(RogueModifierChanceRoll46.decide(input(ONE, List.of(7)), world).successful());
    assertFalse(world.order.contains("has70"));
    assertNull(world.convertOwner);
  }

  @Test
  void extraHitScalesBaseBeforeIndependentPropertyAndBehaviorComplements() {
    var world = new BoundWorld();
    world.statusResistance = ONE / 2;
    world.resistances.put(7, ONE / 2);
    world.sample = ONE / 8;
    var request =
        new RogueModifierChanceRoll46.Input(
            TARGET, SOURCE, "effect", ONE / 2, ONE, () -> List.of(7));
    assertEquals(
        new RogueModifierChanceRoll46.Result(true, true, ONE / 4),
        RogueModifierChanceRoll46.decide(request, world));
    assertEquals(
        List.of(
            "status",
            "component",
            "owner",
            "behaviorImmune",
            "statusImmune",
            "draw",
            "anti",
            "property",
            "resistance7"),
        world.order);
  }

  @Test
  void failedActualHitAfterBaseHitExecutesResistWithOriginalSource() {
    var world = new BoundWorld();
    world.statusResistance = ONE / 2;
    world.anti = new ArrayList<>();
    var flags = List.of(7);
    var result = RogueModifierChanceRoll46.decide(input(ONE / 2, flags), world);
    assertEquals(new RogueModifierChanceRoll46.Result(false, true, ONE / 4), result);
    assertTrue(result.resisted());
    assertSame(world.anti, world.released);
    assertSame(flags, world.resistFlags);
    assertSame(SOURCE, world.resistedSource);
    assertEquals(
        List.of("release", "resisted"),
        world.order.subList(world.order.size() - 2, world.order.size()));
  }

  @Test
  void baseMissDoesNotRunResistSequence() {
    var world = new BoundWorld();
    world.statusResistance = ONE / 2;
    var result = RogueModifierChanceRoll46.decide(input(ONE / 4, List.of(7)), world);
    assertEquals(new RogueModifierChanceRoll46.Result(false, false, ONE / 8), result);
    assertFalse(result.resisted());
    assertNull(world.resistedSource);
  }

  @Test
  void zeroSampleHitsExactlyZeroChance() {
    var world = new BoundWorld();
    world.sample = 0;
    assertEquals(
        new RogueModifierChanceRoll46.Result(true, true, 0),
        RogueModifierChanceRoll46.decide(input(0, null), world));
    assertEquals(1, world.draws.get());
  }

  @Test
  void outOfUnitProbabilityRemainsUnclamped() {
    var world = new BoundWorld();
    world.sample = ONE - 2;
    assertEquals(
        new RogueModifierChanceRoll46.Result(true, true, 2 * ONE),
        RogueModifierChanceRoll46.decide(input(2 * ONE, null), world));
    var negative =
        new RogueModifierChanceRoll46.Input(TARGET, SOURCE, "effect", ONE, -2 * ONE, () -> null);
    assertEquals(
        new RogueModifierChanceRoll46.Result(false, false, -ONE),
        RogueModifierChanceRoll46.decide(negative, new BoundWorld()));
  }

  @Test
  void negativePropertyCanMakeActualHitDespiteBaseMiss() {
    var world = new BoundWorld();
    world.sample = ONE / 2;
    world.statusResistance = -ONE;
    assertEquals(
        new RogueModifierChanceRoll46.Result(true, false, ONE / 2),
        RogueModifierChanceRoll46.decide(input(ONE / 4, null), world));
    assertNull(world.resistedSource);
  }

  @Test
  void antiFlagsIgnorePartialResistanceButRetainCompleteImmunity() {
    var world = new BoundWorld();
    world.anti = List.of(7);
    world.resistances.put(7, ONE / 2);
    assertTrue(RogueModifierChanceRoll46.decide(input(ONE, List.of(7)), world).successful());
    world.resistances.put(7, ONE);
    world.order.clear();
    assertEquals(
        new RogueModifierChanceRoll46.Result(false, true, 0),
        RogueModifierChanceRoll46.decide(input(ONE, List.of(7)), world));
    assertSame(world.anti, world.released);
    assertEquals("resisted", world.order.getLast());
  }

  @Test
  void nullOrEmptyFlagsDoNotAcquireAntiList() {
    for (List<Integer> flags : java.util.Arrays.asList(null, List.<Integer>of())) {
      var world = new BoundWorld();
      world.anti = List.of(7);
      RogueModifierChanceRoll46.decide(input(ONE, flags), world);
      assertFalse(world.order.contains("anti"));
      assertNull(world.released);
    }
  }

  @Test
  void absentActualOwnerDoesNotAcquireAntiList() {
    var world = new BoundWorld();
    world.actual = null;
    world.anti = List.of(7);
    RogueModifierChanceRoll46.decide(input(ONE, List.of(7)), world);
    assertFalse(world.order.contains("anti"));
    assertNull(world.released);
  }

  @Test
  void acquiredEmptyListIsReleasedAndNullResultIsNot() {
    var empty = new BoundWorld();
    empty.anti = new ArrayList<>();
    RogueModifierChanceRoll46.decide(input(ONE, List.of(7)), empty);
    assertSame(empty.anti, empty.released);
    var absent = new BoundWorld();
    RogueModifierChanceRoll46.decide(input(ONE, List.of(7)), absent);
    assertFalse(absent.order.contains("release"));
  }

  @Test
  void drawFailureStopsAntiAcquisitionAndPropertyReads() {
    var failure = new IllegalStateException("random unavailable");
    var world =
        new BoundWorld() {
          public long sample(Object target) {
            order.add("draw");
            throw failure;
          }
        };
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierChanceRoll46.decide(input(ONE, List.of(7)), world)));
    assertEquals("draw", world.order.getLast());
    assertFalse(world.order.contains("anti"));
  }

  @Test
  void numericFailureAfterAcquisitionDoesNotReleaseList() {
    var world = new BoundWorld();
    world.anti = List.of(7);
    world.statusResistance = 1;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierChanceRoll46.decide(input(ONE, List.of(7)), world));
    assertEquals("property", world.order.getLast());
    assertNull(world.released);
    assertNull(world.resistedSource);
  }

  @Test
  void normalReleaseFailurePreventsResistSequence() {
    var failure = new IllegalStateException("pool unavailable");
    var world =
        new BoundWorld() {
          public void releaseAntiFlags(List<Integer> flags) {
            order.add("release");
            throw failure;
          }
        };
    world.anti = List.of(7);
    world.statusResistance = ONE / 2;
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierChanceRoll46.decide(input(ONE / 2, List.of(7)), world)));
    assertEquals("release", world.order.getLast());
    assertNull(world.resistedSource);
  }

  @Test
  void callbackReadsCurrentFlagsAfterNormalRelease() {
    var original = List.of(7);
    var current = List.of(8);
    var state = new java.util.concurrent.atomic.AtomicReference<>(original);
    var world =
        new BoundWorld() {
          public void releaseAntiFlags(List<Integer> flags) {
            super.releaseAntiFlags(flags);
            state.set(current);
          }
        };
    world.anti = List.of(7);
    world.statusResistance = ONE / 2;
    var request =
        new RogueModifierChanceRoll46.Input(TARGET, SOURCE, "effect", ONE / 2, 0, state::get);
    RogueModifierChanceRoll46.decide(request, world);
    assertSame(current, world.resistFlags);
    assertSame(SOURCE, world.resistedSource);
  }

  @Test
  void acquisitionMutationChangesFlagsReadByResistance() {
    var state = new java.util.concurrent.atomic.AtomicReference<>(List.of(7));
    var world =
        new BoundWorld() {
          public List<Integer> antiFlags(Object owner) {
            state.set(List.of(8));
            return super.antiFlags(owner);
          }
        };
    world.resistances.put(8, ONE);
    var request = new RogueModifierChanceRoll46.Input(TARGET, SOURCE, "effect", ONE, 0, state::get);
    assertEquals(0, RogueModifierChanceRoll46.decide(request, world).actualChance());
    assertTrue(world.order.contains("resistance8"));
    assertFalse(world.order.contains("resistance7"));
  }

  @Test
  void unmatchedSamplesFailBeforeAnyResistanceRead() {
    for (long sample : List.of(-2L, ONE, 1L)) {
      var world = new BoundWorld();
      world.sample = sample;
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueModifierChanceRoll46.decide(input(ONE, List.of(7)), world));
      assertEquals("draw", world.order.getLast());
      assertFalse(world.order.contains("anti"));
    }
  }

  @Test
  void unmatchedChanceAndExtraHitFailAfterImmunityBeforeDraw() {
    var chance = new BoundWorld();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierChanceRoll46.decide(input(1, null), chance));
    assertEquals("statusImmune", chance.order.getLast());
    var extra = new BoundWorld();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierChanceRoll46.decide(
                new RogueModifierChanceRoll46.Input(TARGET, SOURCE, "effect", ONE, 1, () -> null),
                extra));
    assertEquals("statusImmune", extra.order.getLast());
  }

  @Test
  void unsupportedBaseProductStopsBeforeSampling() {
    var world = new BoundWorld();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierChanceRoll46.decide(
                new RogueModifierChanceRoll46.Input(
                    TARGET, SOURCE, "effect", Long.MAX_VALUE - 1, ONE, () -> null),
                world));
    assertEquals("statusImmune", world.order.getLast());
  }

  @Test
  void missingNormalComponentFailsAfterSampleAndAcquisition() {
    var world = new BoundWorld();
    world.component = null;
    world.anti = List.of(7);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierChanceRoll46.decide(input(ONE, List.of(7)), world));
    assertEquals("anti", world.order.getLast());
    assertNull(world.released);
  }

  @Test
  void nullFlagAndOversizedUnmatchedListAreClosedBeforeOwner() {
    for (List<Integer> flags :
        List.of(java.util.Arrays.asList((Integer) null), java.util.Collections.nCopies(256, 7))) {
      var world = new BoundWorld();
      assertThrows(
          IllegalArgumentException.class,
          () -> RogueModifierChanceRoll46.decide(input(ONE, flags), world));
      assertEquals(List.of("status", "component"), world.order);
    }
  }
}
