package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RogueResonanceScopedLookup46Test {
  private static final long ONE = 1L << 33;

  private static RogueResonanceVariables46 cells(int key, long raw) {
    return RogueResonanceVariables46.fromSourceDefaults(Map.of(key, raw), null);
  }

  private static RogueResonanceContext46 prefix() {
    return new RogueResonanceContext46(null, List.of(), null, null);
  }

  private static Long readDefault(RogueResonanceScopedLookup46 lookup, int key) {
    try {
      return lookup.read(key);
    } catch (IllegalArgumentException unmatched) {
      return null;
    }
  }

  @Test
  void explicitAbilityAndCasterScopesChooseDifferentMatchedContainers() {
    var ability = cells(42, ONE);
    var caster = cells(42, 2 * ONE);
    var lookup =
        new RogueResonanceScopedLookup46(
            prefix(),
            new RogueResonanceScopedLookup46.Tail(
                new RogueResonanceScopedLookup46.Ability(ability, caster),
                cells(42, 3 * ONE),
                null,
                null,
                null));
    Long actual;
    try {
      actual = lookup.select(3, 42).read(42) + lookup.select(4, 42).read(42);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(3 * ONE), actual);
    assertSame(ability, lookup.select(3, 42));
    assertSame(caster, lookup.select(4, 42));
  }

  @Test
  void defaultScopeWithAbilitySkipsPrimaryOwnerBeforeCaster() {
    var caster = cells(42, 2 * ONE);
    var lookup =
        new RogueResonanceScopedLookup46(
            prefix(),
            new RogueResonanceScopedLookup46.Tail(
                new RogueResonanceScopedLookup46.Ability(cells(99, 0), caster),
                cells(42, ONE),
                null,
                null,
                null));
    assertEquals(Long.valueOf(2 * ONE), readDefault(lookup, 42));
  }

  @Test
  void absentAbilityUsesPrimaryOwnerBeforeSummoner() {
    var primary = cells(42, ONE);
    var lookup =
        new RogueResonanceScopedLookup46(
            prefix(),
            new RogueResonanceScopedLookup46.Tail(
                null,
                primary,
                new RogueResonanceScopedLookup46.Entity(new Object(), cells(42, 2 * ONE)),
                null,
                null));
    assertEquals(Long.valueOf(ONE), readDefault(lookup, 42));
  }

  @Test
  void summonerScopeMayFallBackToDistinctOwnerButNeverGlobalAliases() {
    var owner = cells(42, 3 * ONE);
    var lookup =
        new RogueResonanceScopedLookup46(
            prefix(),
            new RogueResonanceScopedLookup46.Tail(
                null,
                cells(42, ONE),
                new RogueResonanceScopedLookup46.Entity(new Object(), cells(99, 0)),
                new RogueResonanceScopedLookup46.Entity(new Object(), owner),
                cells(43, 4 * ONE)));
    Long actual;
    try {
      actual = lookup.select(8, 42).read(42);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(3 * ONE), actual);
    assertSame(owner, lookup.select(8, 42));
    assertThrows(IllegalArgumentException.class, () -> lookup.select(8, 43));
  }

  @Test
  void identicalOwnerAndSummonerIdentitySkipsOwnerFallbackAndUsesDefaultGlobal() {
    var identity = new Object();
    var lookup =
        new RogueResonanceScopedLookup46(
            prefix(),
            new RogueResonanceScopedLookup46.Tail(
                null,
                null,
                new RogueResonanceScopedLookup46.Entity(identity, cells(99, 0)),
                new RogueResonanceScopedLookup46.Entity(identity, cells(42, 3 * ONE)),
                cells(42, 4 * ONE)));
    assertEquals(Long.valueOf(4 * ONE), readDefault(lookup, 42));
  }

  @Test
  void prefixZeroValueShadowsEntireDefaultRemainder() {
    var callback = cells(42, 0);
    var prefix = new RogueResonanceContext46(callback, List.of(), null, null);
    var lookup =
        new RogueResonanceScopedLookup46(
            prefix,
            new RogueResonanceScopedLookup46.Tail(
                new RogueResonanceScopedLookup46.Ability(cells(42, ONE), cells(42, 2 * ONE)),
                null,
                null,
                null,
                null));
    assertEquals(Long.valueOf(0), readDefault(lookup, 42));
    assertSame(callback, lookup.select(0, 42));
  }

  @Test
  void explicitOwnerScopeUsesPrimaryCustomDataAndBypassesAbility() {
    var primary = cells(42, 3 * ONE);
    var lookup =
        new RogueResonanceScopedLookup46(
            prefix(),
            new RogueResonanceScopedLookup46.Tail(
                new RogueResonanceScopedLookup46.Ability(cells(42, ONE), cells(42, 2 * ONE)),
                primary,
                null,
                null,
                null));
    Long actual;
    try {
      actual = lookup.select(5, 42).read(42);
    } catch (IllegalArgumentException unmatched) {
      actual = null;
    }
    assertEquals(Long.valueOf(3 * ONE), actual);
    assertSame(primary, lookup.select(5, 42));
  }

  @Test
  void abilityPresenceChangesDefaultLookupEvenWhenItsOwnAndCasterKeysAreMissing() {
    var primary = cells(42, ONE);
    var global = cells(42, 4 * ONE);
    var withAbility =
        new RogueResonanceScopedLookup46(
            prefix(),
            new RogueResonanceScopedLookup46.Tail(
                new RogueResonanceScopedLookup46.Ability(cells(99, 0), cells(99, 0)),
                primary,
                null,
                null,
                global));
    var withoutAbility =
        new RogueResonanceScopedLookup46(
            prefix(), new RogueResonanceScopedLookup46.Tail(null, primary, null, null, global));
    assertEquals(4 * ONE, withAbility.read(42));
    assertEquals(ONE, withoutAbility.read(42));
    assertSame(primary, withAbility.select(5, 42));
  }

  @Test
  void absentAbilityNeverOpensExplicitCasterOrAbilityScopes() {
    var lookup =
        new RogueResonanceScopedLookup46(
            prefix(),
            new RogueResonanceScopedLookup46.Tail(
                null, cells(42, ONE), null, null, cells(42, 2 * ONE)));
    for (int scope : new int[] {3, 4})
      assertThrows(IllegalArgumentException.class, () -> lookup.select(scope, 42));
    assertEquals(ONE, lookup.read(42));
  }

  @Test
  void summonerPrecedesOwnerAndOwnerPrecedesGlobalIncludingAbsentSummoner() {
    var summoner = cells(42, ONE);
    var owner = cells(43, 2 * ONE);
    var global = cells(44, 3 * ONE);
    var ownerEntity = new RogueResonanceScopedLookup46.Entity(new Object(), owner);
    var lookup =
        new RogueResonanceScopedLookup46(
            prefix(),
            new RogueResonanceScopedLookup46.Tail(
                null,
                null,
                new RogueResonanceScopedLookup46.Entity(new Object(), summoner),
                ownerEntity,
                global));
    assertEquals(ONE, lookup.read(42));
    assertEquals(2 * ONE, lookup.read(43));
    assertEquals(3 * ONE, lookup.read(44));
    var absentSummoner =
        new RogueResonanceScopedLookup46(
            prefix(), new RogueResonanceScopedLookup46.Tail(null, null, null, ownerEntity, global));
    assertEquals(2 * ONE, absentSummoner.select(8, 43).read(43));
    assertThrows(IllegalArgumentException.class, () -> absentSummoner.select(8, 44));
  }

  @Test
  void entityComparisonUsesReferenceIdentityEvenWhenEqualsReturnsTrue() {
    class Token {
      @Override
      public boolean equals(Object other) {
        return other instanceof Token;
      }

      @Override
      public int hashCode() {
        return 0;
      }
    }
    var summoner = new Token();
    var owner = new Token();
    assertEquals(summoner, owner);
    assertNotSame(summoner, owner);
    var lookup =
        new RogueResonanceScopedLookup46(
            prefix(),
            new RogueResonanceScopedLookup46.Tail(
                null,
                null,
                new RogueResonanceScopedLookup46.Entity(summoner, cells(99, 0)),
                new RogueResonanceScopedLookup46.Entity(owner, cells(42, ONE)),
                cells(42, 2 * ONE)));
    assertEquals(ONE, lookup.read(42));
  }

  @Test
  void explicitScopesNeverBorrowPrefixGlobalOrUnmatchedAitagAndTargetBehavior() {
    var prefix =
        new RogueResonanceContext46(cells(42, ONE), List.of(), cells(43, ONE), cells(44, ONE));
    var lookup =
        new RogueResonanceScopedLookup46(
            prefix,
            new RogueResonanceScopedLookup46.Tail(
                new RogueResonanceScopedLookup46.Ability(cells(99, 0), null),
                null,
                null,
                null,
                cells(45, ONE)));
    for (int scope : new int[] {3, 4, 5, 8})
      assertThrows(IllegalArgumentException.class, () -> lookup.select(scope, 42));
    for (int scope : new int[] {-1, 6, 7, 10, Integer.MAX_VALUE})
      assertThrows(IllegalArgumentException.class, () -> lookup.select(scope, 42));
    assertEquals(ONE, lookup.select(1, 43).read(43));
    assertEquals(ONE, lookup.select(2, 44).read(44));
    assertEquals(ONE, lookup.select(9, 42).read(42));
    assertThrows(IllegalArgumentException.class, () -> lookup.read(46));
  }

  @Test
  void malformedRoleProjectionsAreRejectedAndEmptyExistingLookupsRemainDistinctFromAbsentAbility() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RogueResonanceScopedLookup46(
                null, new RogueResonanceScopedLookup46.Tail(null, null, null, null, null)));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceScopedLookup46(prefix(), null));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceScopedLookup46.Ability(null, null));
    assertThrows(
        IllegalArgumentException.class, () -> new RogueResonanceScopedLookup46.Entity(null, null));
    var empty = RogueResonanceVariables46.fromSourceDefaults(Map.of(), null);
    var lookup =
        new RogueResonanceScopedLookup46(
            prefix(),
            new RogueResonanceScopedLookup46.Tail(
                new RogueResonanceScopedLookup46.Ability(empty, null),
                cells(42, ONE),
                null,
                null,
                cells(42, 2 * ONE)));
    assertEquals(2 * ONE, lookup.read(42));
  }

  @Test
  void allThreeTasksUseExplicitAbilityLocalsAndIsolatedExtraFrameParameter() {
    var locals =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(-978103959, 0L, 1555818884, 0L, -38167523, 0L, -1803181471, 0L), null);
    var prefix = new RogueResonanceContext46(null, List.of(cells(909827949, ONE / 64)), null, null);
    RogueResonanceLookup46 lookup =
        new RogueResonanceScopedLookup46(
            prefix,
            new RogueResonanceScopedLookup46.Tail(
                new RogueResonanceScopedLookup46.Ability(locals, null), null, null, null, null));
    assertTrue(
        new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12)
            .executeInContext(lookup, 0, List.of(Map.of(12, 10 * ONE))));
    var multiplied =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("010000000411"), new long[] {100 * ONE}, new int[] {909827949});
    assertEquals(
        1,
        new RogueResonanceWrite46.ExpressionTask(2791, "Extra_AttackTime", multiplied)
            .executeInContext(lookup, List.of(locals), false));
    var round = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    round.executeInContext(lookup, 3);
    var floor =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("01000101010205100111"),
            new long[0],
            new int[] {1776456860, -978103959, 1555818884});
    assertEquals(5 * ONE, floor.evaluateWithResolver(lookup::read));
    assertEquals(0x9999, round.state());
  }

  @Test
  void projectedWritesPreserveObserversAndLifecycleFailuresWithoutCreatingCells() {
    int key = RogueResonanceVariables46.nameKey("_CurrentSP");
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var locals = RogueResonanceVariables46.fromSourceDefaults(Map.of(key, 0L), changes::add);
    RogueResonanceLookup46 lookup =
        new RogueResonanceScopedLookup46(
            prefix(),
            new RogueResonanceScopedLookup46.Tail(
                new RogueResonanceScopedLookup46.Ability(locals, null), null, null, null, null));
    assertTrue(
        new RogueResonanceWrite46.PropertyTask(2855, "_CurrentSP", 12)
            .executeInContext(lookup, 3, List.of(Map.of(12, ONE))));
    assertEquals(List.of(new RogueResonanceVariables46.Change(key, 0, ONE)), changes);
    var absent = new RogueResonanceWrite46.PropertyTask(2855, "absent", 12);
    assertThrows(
        IllegalArgumentException.class,
        () -> absent.executeInContext(lookup, 3, List.of(Map.of(12, ONE))));
    assertEquals(0x9999, absent.state());
    var round = new RogueResonanceVariables46.RoundToIntTask(1221, "absent");
    assertThrows(IllegalArgumentException.class, () -> round.executeInContext(lookup, 3));
    assertEquals(0x7777, round.state());
    assertEquals(1, locals.snapshot().size());
  }
}
