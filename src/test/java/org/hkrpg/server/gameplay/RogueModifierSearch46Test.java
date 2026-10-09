package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class RogueModifierSearch46Test {
  static final Object CONTEXT = new Object(), ABILITY = new Object(), COMPONENT = new Object();

  static RogueModifierSearch46.Candidate candidate() {
    return new RogueModifierSearch46.Candidate("buff", 1, 5);
  }

  static RogueModifierSearch46.Search search(int caster, Object ability) {
    return new RogueModifierSearch46.Search("buff", caster, 0, 100, ability);
  }

  static class World implements RogueModifierSearch46.World {
    List<RogueModifierSearch46.Candidate> list = List.of();
    String reserved = "current";
    Object context = CONTEXT, ability = ABILITY;
    Integer owner;
    Supplier<RogueModifierSearch46.Entity> entity = () -> new RogueModifierSearch46.Entity(7, 1);
    Supplier<RogueModifierSearch46.Candidate> current = () -> null;
    final List<String> order = new ArrayList<>();

    public List<RogueModifierSearch46.Candidate> modifiers(Object component) {
      assertSame(COMPONENT, component);
      order.add("list");
      return list;
    }

    public String currentModifierReservedName() {
      order.add("reserved");
      return reserved;
    }

    public RogueModifierSearch46.Candidate currentModifier(Object component) {
      assertSame(COMPONENT, component);
      order.add("current");
      return current.get();
    }

    public Object taskContext(RogueModifierSearch46.Candidate candidate) {
      order.add("context");
      return context;
    }

    public RogueModifierSearch46.Entity casterEntity(Object context) {
      assertSame(this.context, context);
      order.add("entity");
      return entity.get();
    }

    public Integer sourceEntityId(RogueModifierSearch46.Entity entity) {
      order.add("owner");
      return owner;
    }

    public Object contextAbility(Object context) {
      assertSame(this.context, context);
      order.add("ability");
      return ability;
    }
  }

  @Test
  void differentNamesRejectBeforeWorldStateOrSourceReads() {
    assertFalse(
        RogueModifierSearch46.matches(
            candidate(), new RogueModifierSearch46.Search("other", 7, 9, 100, ABILITY), null));
  }

  @Test
  void equalDistinctNamesUseOrdinalEquality() {
    var c = candidate();
    c.name = new String("buff");
    assertTrue(RogueModifierSearch46.matches(c, search(0, ABILITY), null));
  }

  @Test
  void bothNullNamesMatchBeforeWildcardReturn() {
    var c = candidate();
    c.name = null;
    assertTrue(
        RogueModifierSearch46.matches(
            c, new RogueModifierSearch46.Search(null, -1, 0, 100, ABILITY), null));
  }

  @Test
  void oneNullNameRejects() {
    var c = candidate();
    c.name = null;
    assertFalse(RogueModifierSearch46.matches(c, search(0, null), null));
    assertFalse(
        RogueModifierSearch46.matches(
            candidate(), new RogueModifierSearch46.Search(null, 0, 0, 100, null), null));
  }

  @Test
  void stateFilterZeroAcceptsOnlyUnsignedZeroOrOne() {
    for (int value : new int[] {0, 1, 2, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      var c = candidate();
      c.state = value;
      assertEquals(
          value == 0 || value == 1, RogueModifierSearch46.matches(c, search(0, null), null));
    }
  }

  @Test
  void stateFilterOneRequiresAliveOne() {
    for (int value : new int[] {0, 1, 2, -1}) {
      var c = candidate();
      c.state = value;
      assertEquals(
          value == 1,
          RogueModifierSearch46.matches(
              c, new RogueModifierSearch46.Search("buff", 0, 1, 100, null), null));
    }
  }

  @Test
  void otherRawStateFiltersSkipNativeStateRestriction() {
    for (int filter : new int[] {2, -1, Integer.MIN_VALUE}) {
      var c = candidate();
      c.state = -1;
      assertTrue(
          RogueModifierSearch46.matches(
              c, new RogueModifierSearch46.Search("buff", 0, filter, 100, null), null));
    }
  }

  @Test
  void originFlagOneHundredIsAnyAndOtherFlagsMatchExactly() {
    var c = candidate();
    assertTrue(
        RogueModifierSearch46.matches(
            c, new RogueModifierSearch46.Search("buff", 0, 0, 5, null), null));
    assertFalse(
        RogueModifierSearch46.matches(
            c, new RogueModifierSearch46.Search("buff", 0, 0, 0, null), null));
    assertTrue(RogueModifierSearch46.matches(c, search(0, null), null));
  }

  @Test
  void zeroAndMinusOneCasterBypassAbilityAndAllWorldReads() {
    for (int caster : new int[] {0, -1})
      assertTrue(RogueModifierSearch46.matches(candidate(), search(caster, new Object()), null));
  }

  @Test
  void otherNegativeCasterIsAnExactBitPattern() {
    var world = new World();
    world.entity = () -> new RogueModifierSearch46.Entity(-3, 1);
    assertTrue(RogueModifierSearch46.matches(candidate(), search(-3, null), world));
    assertEquals(List.of("context", "entity"), world.order);
  }

  @Test
  void directRuntimeIdMatchSkipsSecondContextAndOwnerLookup() {
    var world = new World();
    assertTrue(RogueModifierSearch46.matches(candidate(), search(7, null), world));
    assertEquals(List.of("context", "entity"), world.order);
  }

  @Test
  void directIdMatchStillReadsRequestedAbility() {
    var world = new World();
    assertTrue(RogueModifierSearch46.matches(candidate(), search(7, ABILITY), world));
    assertEquals(List.of("context", "entity", "context", "ability"), world.order);
  }

  @Test
  void directIdMatchWithDifferentAbilityRejects() {
    var world = new World();
    world.ability = new Object();
    assertFalse(RogueModifierSearch46.matches(candidate(), search(7, ABILITY), world));
  }

  @Test
  void abilityComparisonUsesIdentityInsteadOfValueEquality() {
    var world = new World();
    world.ability = new String("ability");
    assertFalse(
        RogueModifierSearch46.matches(candidate(), search(7, new String("ability")), world));
  }

  @Test
  void missingFirstEntityRejectsWithoutSecondSourceOrOwner() {
    var world = new World();
    world.entity = () -> null;
    assertFalse(RogueModifierSearch46.matches(candidate(), search(8, null), world));
    assertEquals(List.of("context", "entity"), world.order);
  }

  @Test
  void missingFirstEntityStillReadsAbilityBeforeReturningFalse() {
    var world = new World();
    world.entity = () -> null;
    assertFalse(RogueModifierSearch46.matches(candidate(), search(8, ABILITY), world));
    assertEquals(List.of("context", "entity", "context", "ability"), world.order);
  }

  @Test
  void firstIdMismatchRereadsCurrentContextAndCasterThenUsesOwner() {
    var world = new World();
    world.owner = 8;
    assertTrue(RogueModifierSearch46.matches(candidate(), search(8, null), world));
    assertEquals(List.of("context", "entity", "context", "entity", "owner"), world.order);
  }

  @Test
  void changedSecondEntityUsesItsCurrentOwnerAndNotFirstOwner() {
    var world = new World();
    var first = new RogueModifierSearch46.Entity(7, 1);
    var second = new RogueModifierSearch46.Entity(8, 8);
    int[] calls = {0};
    world.entity = () -> ++calls[0] == 1 ? first : second;
    assertTrue(RogueModifierSearch46.matches(candidate(), search(8, null), world));
    assertFalse(world.order.contains("owner"));
  }

  @Test
  void destroyedSecondEntityUsesOwnRuntimeIdWithoutOwnerComponent() {
    var world = new World();
    world.entity = () -> new RogueModifierSearch46.Entity(7, 8);
    world.owner = 8;
    assertFalse(RogueModifierSearch46.matches(candidate(), search(8, null), world));
    assertFalse(world.order.contains("owner"));
  }

  @Test
  void absentOwnerComponentFallsBackToSecondEntityRuntimeId() {
    var world = new World();
    int[] calls = {0};
    world.entity = () -> new RogueModifierSearch46.Entity(++calls[0] == 1 ? 7 : 8, 1);
    assertTrue(RogueModifierSearch46.matches(candidate(), search(8, null), world));
    assertEquals("owner", world.order.getLast());
  }

  @Test
  void mismatchingOwnerStillRunsAbilityRead() {
    var world = new World();
    world.owner = 9;
    assertFalse(RogueModifierSearch46.matches(candidate(), search(8, ABILITY), world));
    assertEquals(
        List.of("context", "entity", "context", "entity", "owner", "context", "ability"),
        world.order);
  }

  @Test
  void secondEntityDisappearanceKeepsFalseAndStillReadsAbility() {
    var world = new World();
    int[] calls = {0};
    world.entity = () -> ++calls[0] == 1 ? new RogueModifierSearch46.Entity(7, 1) : null;
    assertFalse(RogueModifierSearch46.matches(candidate(), search(8, ABILITY), world));
    assertEquals("ability", world.order.getLast());
    assertFalse(world.order.contains("owner"));
  }

  @Test
  void contextDisappearingBetweenReadsFailsInsteadOfUsingOldContext() {
    var world = new World();
    world.entity =
        () -> {
          world.context = null;
          return new RogueModifierSearch46.Entity(7, 1);
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierSearch46.matches(candidate(), search(8, null), world));
    assertEquals(List.of("context", "entity", "context"), world.order);
  }

  @Test
  void sourceCallbackFaultStopsBeforeAbilityAndDoesNotInventFalse() {
    var world = new World();
    world.entity =
        () -> {
          throw new IllegalStateException("source");
        };
    assertThrows(
        IllegalStateException.class,
        () -> RogueModifierSearch46.matches(candidate(), search(8, ABILITY), world));
    assertEquals(List.of("context", "entity"), world.order);
  }

  @Test
  void findReturnsFirstMatchingActualCandidateWithoutFallback() {
    var world = new World();
    var first = candidate();
    world.list = List.of(first, candidate());
    assertSame(first, RogueModifierSearch46.find(COMPONENT, search(0, null), world));
    assertEquals(List.of("list"), world.order);
  }

  @Test
  void findRereadsListAfterEachNonmatch() {
    var world = new World();
    var wrong = candidate();
    wrong.name = "wrong";
    var target = candidate();
    world.list = List.of(wrong, target);
    assertSame(target, RogueModifierSearch46.find(COMPONENT, search(0, null), world));
    assertEquals(List.of("list", "list"), world.order);
  }

  @Test
  void listReplacementDuringSourceReadAffectsNextIndex() {
    var world = new World();
    var first = candidate();
    var next = candidate();
    next.stackingFlag = 4;
    world.list = List.of(first);
    world.entity =
        () -> {
          world.list = List.of(first, next);
          return null;
        };
    assertNull(
        RogueModifierSearch46.find(
            COMPONENT, new RogueModifierSearch46.Search("buff", 8, 0, 5, null), world));
    assertEquals(List.of("list", "context", "entity", "list", "list", "reserved"), world.order);
  }

  @Test
  void emptyOrdinaryNameSearchDoesNotReadCurrentModifier() {
    var world = new World();
    assertNull(RogueModifierSearch46.find(COMPONENT, search(0, null), world));
    assertEquals(List.of("list", "reserved"), world.order);
  }

  @Test
  void reservedFallbackChecksCurrentThenReadsAgainAndReturnsSecondValue() {
    var world = new World();
    var first = candidate();
    var second = candidate();
    int[] calls = {0};
    world.current = () -> ++calls[0] == 1 ? first : second;
    assertSame(
        second,
        RogueModifierSearch46.find(
            COMPONENT, new RogueModifierSearch46.Search("current", 8, 1, 999, ABILITY), world));
    assertEquals(List.of("list", "reserved", "current", "current"), world.order);
  }

  @Test
  void reservedFallbackCanBecomeNullOnSecondRead() {
    var world = new World();
    int[] calls = {0};
    world.current = () -> ++calls[0] == 1 ? candidate() : null;
    assertNull(
        RogueModifierSearch46.find(
            COMPONENT, new RogueModifierSearch46.Search("current", 0, 0, 100, null), world));
    assertEquals(2, calls[0]);
  }

  @Test
  void missingCurrentFallbackStopsAfterFirstRead() {
    var world = new World();
    assertNull(
        RogueModifierSearch46.find(
            COMPONENT, new RogueModifierSearch46.Search("current", 0, 0, 100, null), world));
    assertEquals(List.of("list", "reserved", "current"), world.order);
  }

  @Test
  void missingListOrEntryIsRejectedBeforeFallback() {
    var world = new World();
    world.list = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierSearch46.find(COMPONENT, search(0, null), world));
    assertEquals(List.of("list"), world.order);
    world.order.clear();
    world.list = new ArrayList<>();
    world.list.add(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierSearch46.find(COMPONENT, search(0, null), world));
    assertEquals(List.of("list"), world.order);
  }

  @Test
  void missingSourceContextOrWorldIsRejectedOnlyAfterEarlyGates() {
    var world = new World();
    world.context = null;
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierSearch46.matches(candidate(), search(8, null), world));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierSearch46.matches(candidate(), search(8, null), null));
    assertTrue(RogueModifierSearch46.matches(candidate(), search(0, null), world));
  }

  @Test
  void missingCandidateSearchOrComponentFailsBeforeWorldCalls() {
    var world = new World();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierSearch46.matches(null, search(0, null), world));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierSearch46.matches(candidate(), null, world));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierSearch46.find(null, search(0, null), world));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierSearch46.find(COMPONENT, null, world));
    assertTrue(world.order.isEmpty());
  }
}
