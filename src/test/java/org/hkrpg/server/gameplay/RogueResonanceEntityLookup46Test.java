package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RogueResonanceEntityLookup46Test {
  private static final long ONE = 1L << 33;
  private static final int KEY = RogueResonanceVariables46.nameKey("Extra_AttackTime");

  private static RogueResonanceVariables46 cells(Map<Integer, Long> values) {
    return RogueResonanceVariables46.fromSourceDefaults(values, null);
  }

  private static RogueResonanceScopedLookup46 lookup(
      RogueResonanceScopedLookup46.Entity summoner,
      RogueResonanceScopedLookup46.Entity owner,
      RogueResonanceVariables46 callback,
      RogueResonanceVariables46 ability) {
    return new RogueResonanceScopedLookup46(
        new RogueResonanceContext46(callback, List.of(), null, null),
        new RogueResonanceScopedLookup46.Tail(
            ability == null ? null : new RogueResonanceScopedLookup46.Ability(ability, null),
            null,
            summoner,
            owner,
            null));
  }

  // Return null only for an API that is absent in the red baseline. Native failure
  // exceptions still reach assertions, so a failed runtime path cannot look successful.
  private static Object call(Object receiver, String method, Class<?>[] types, Object... args) {
    try {
      return receiver.getClass().getMethod(method, types).invoke(receiver, args);
    } catch (NoSuchMethodException absent) {
      return null;
    } catch (InvocationTargetException failed) {
      if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new AssertionError(failed.getCause());
    } catch (ReflectiveOperationException failed) {
      throw new AssertionError(failed);
    }
  }

  private static RogueResonanceScopedLookup46.Entity entity(
      Object identity, RogueResonanceVariables46 custom, RogueResonanceVariables46 aiTag) {
    try {
      return RogueResonanceScopedLookup46.Entity.class
          .getConstructor(
              Object.class, RogueResonanceVariables46.class, RogueResonanceVariables46.class)
          .newInstance(identity, custom, aiTag);
    } catch (NoSuchMethodException absent) {
      return null;
    } catch (ReflectiveOperationException failed) {
      throw new AssertionError(failed);
    }
  }

  private static RogueResonanceVariables46 selected(
      RogueResonanceScopedLookup46 lookup,
      RogueResonanceScopedLookup46.Entity entity,
      int scope,
      int key) {
    return (RogueResonanceVariables46)
        call(
            lookup,
            "selectForEntity",
            new Class<?>[] {RogueResonanceScopedLookup46.Entity.class, int.class, int.class},
            entity,
            scope,
            key);
  }

  @Test
  void nativeMissingReadReturnsZeroWithoutOpeningStrictReadOrCreatingCells() {
    var values = cells(Map.of(99, ONE));
    assertEquals(Long.valueOf(0), call(values, "readNativeFixed", new Class<?>[] {int.class}, KEY));
    assertThrows(IllegalArgumentException.class, () -> values.read(KEY));
    assertEquals(Map.of(99, ONE), values.snapshot());
  }

  @Test
  void ownerAiTagSelectionReturnsContainerEvenWhenKeyIsAbsent() {
    var aiTag = cells(Map.of(99, ONE));
    var owner = entity(new Object(), cells(Map.of(KEY, 2 * ONE)), aiTag);
    var lookup = lookup(null, owner, null, null);
    Long actual;
    try {
      actual = (Long) call(lookup, "readInScope", new Class<?>[] {int.class, int.class}, 7, KEY);
    } catch (IllegalArgumentException absent) {
      actual = null;
    }
    assertEquals(Long.valueOf(0), actual);
    assertSame(aiTag, lookup.select(7, KEY));
    assertEquals(Map.of(99, ONE), aiTag.snapshot());
  }

  @Test
  void missingAiTagRoundCompletesWithoutSetterNotificationOrCellCreation() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var aiTag = RogueResonanceVariables46.fromSourceDefaults(Map.of(), changes::add);
    var lookup = lookup(null, entity(new Object(), null, aiTag), null, null);
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    try {
      task.executeInContext(lookup, 7);
    } catch (IllegalArgumentException unmatched) {
    }
    assertEquals(0x9999, task.state());
    assertTrue(aiTag.snapshot().isEmpty());
    assertTrue(changes.isEmpty());
  }

  @Test
  void arbitraryTargetDefaultScopeUsesItsCustomContainerRatherThanAbility() {
    var targetValues = cells(Map.of(KEY, ONE));
    var target = new RogueResonanceScopedLookup46.Entity(new Object(), targetValues);
    var lookup = lookup(null, null, null, cells(Map.of(KEY, 2 * ONE)));
    assertSame(targetValues, selected(lookup, target, 0, KEY));
    assertEquals(2 * ONE, lookup.read(KEY));
  }

  @Test
  void targetScopeSixBypassesContextForMatchingSummonerAndOwnerIdentity() {
    var identity = new Object();
    var targetValues = cells(Map.of(KEY, ONE));
    var target = new RogueResonanceScopedLookup46.Entity(identity, targetValues);
    var lookup = lookup(target, target, null, cells(Map.of(KEY, 2 * ONE)));
    assertSame(targetValues, selected(lookup, target, 6, KEY));
    assertThrows(IllegalArgumentException.class, () -> lookup.select(6, KEY));
  }

  @Test
  void entityExpressionReadsOnceBeforeSelectingAndWritingTargetsInOrder() {
    var notifications = new ArrayList<Long>();
    var source = cells(Map.of(123, ONE));
    var first =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(KEY, 0L),
            c -> {
              notifications.add(c.current());
              source.write(123, 7 * ONE);
            });
    var second =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(KEY, 0L), c -> notifications.add(c.current()));
    var targets =
        List.of(
            new RogueResonanceScopedLookup46.Entity(new Object(), first),
            new RogueResonanceScopedLookup46.Entity(new Object(), second));
    var lookup = lookup(null, null, source, null);
    var expression =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("010011"), new long[0], new int[] {123});
    var task = new RogueResonanceWrite46.ExpressionTask(2791, "Extra_AttackTime", expression);
    assertEquals(
        Integer.valueOf(2),
        call(
            task,
            "executeForEntities",
            new Class<?>[] {
              RogueResonanceScopedLookup46.class, List.class, int.class, boolean.class
            },
            lookup,
            targets,
            0,
            false));
    assertEquals(List.of(ONE, ONE), notifications);
    assertEquals(7 * ONE, source.read(123));
    assertEquals(0x9999, task.state());
  }

  private static RogueResonanceExpression46.Program constant(long raw) {
    return RogueResonanceExpression46.compile(
        HexFormat.of().parseHex("000011"), new long[] {raw}, new int[0]);
  }

  @Test
  void existingNativeReadPreservesSignedStandardWordAndStrictBounds() {
    var values = cells(Map.of(KEY, -ONE / 2));
    assertEquals(-ONE / 2, values.readNativeFixed(KEY));
    assertEquals(-ONE / 2, values.read(KEY));
    assertThrows(IllegalArgumentException.class, () -> values.bounds(77));
    assertFalse(values.write(77, ONE));
    assertEquals(0, values.readNativeFixed(77));
    assertEquals(1, values.snapshot().size());
  }

  @Test
  void contextAiTagBypassesOtherScopesAndRequiresActualProjectedContainer() {
    var aiTag = cells(Map.of(KEY, 3 * ONE));
    var owner =
        new RogueResonanceScopedLookup46.Entity(new Object(), cells(Map.of(KEY, 2 * ONE)), aiTag);
    var lookup = lookup(null, owner, cells(Map.of(KEY, ONE)), cells(Map.of(KEY, 4 * ONE)));
    assertEquals(3 * ONE, lookup.readInScope(7, KEY));
    assertEquals(ONE, lookup.read(KEY));
    var missing =
        lookup(
            null,
            new RogueResonanceScopedLookup46.Entity(new Object(), null),
            cells(Map.of(KEY, ONE)),
            null);
    assertThrows(IllegalArgumentException.class, () -> missing.readInScope(7, KEY));
  }

  @Test
  void aiTagRoundUsesTiesToEvenAndStillNotifiesUnchangedValues() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var values =
        RogueResonanceVariables46.fromSourceDefaults(Map.of(KEY, 5 * ONE / 2), changes::add);
    var lookup =
        lookup(
            null, new RogueResonanceScopedLookup46.Entity(new Object(), null, values), null, null);
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    task.executeInContext(lookup, 7);
    task.executeInContext(lookup, 7);
    assertEquals(
        List.of(
            new RogueResonanceVariables46.Change(KEY, 5 * ONE / 2, 2 * ONE),
            new RogueResonanceVariables46.Change(KEY, 2 * ONE, 2 * ONE)),
        changes);
    assertEquals(0x9999, task.state());
  }

  @Test
  void nativeRoundKeepsWrittenValueButDoesNotCompleteAfterObserverFailure() {
    var values =
        RogueResonanceVariables46.fromSourceDefaults(
            Map.of(KEY, 3 * ONE / 2),
            c -> {
              throw new IllegalStateException("observer");
            });
    var lookup =
        lookup(
            null, new RogueResonanceScopedLookup46.Entity(new Object(), null, values), null, null);
    var task = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    assertThrows(IllegalStateException.class, () -> task.executeInContext(lookup, 7));
    assertEquals(2 * ONE, values.read(KEY));
    assertEquals(0x7777, task.state());
    var strict = new RogueResonanceVariables46.RoundToIntTask(1221, "Extra_AttackTime");
    assertThrows(IllegalArgumentException.class, () -> strict.execute(cells(Map.of())));
    assertEquals(0x7777, strict.state());
  }

  @Test
  void arbitraryTargetMissingKeyReturnsZeroWithoutContextFallback() {
    var empty = cells(Map.of());
    var target = new RogueResonanceScopedLookup46.Entity(new Object(), empty);
    var lookup = lookup(null, null, cells(Map.of(KEY, ONE)), cells(Map.of(KEY, 2 * ONE)));
    for (int scope : new int[] {0, 1, 2, 3, 4, 5, 6, 8}) {
      assertSame(empty, lookup.selectForEntity(target, scope, KEY));
      assertEquals(0, lookup.readForEntity(target, scope, KEY));
    }
    assertEquals(ONE, lookup.read(KEY));
    assertTrue(empty.snapshot().isEmpty());
  }

  @Test
  void matchingSummonerDelegatesEvenAiTagScopeToContextOwner() {
    var summoner =
        new RogueResonanceScopedLookup46.Entity(
            new Object(), cells(Map.of(KEY, ONE)), cells(Map.of(KEY, 2 * ONE)));
    var owner =
        new RogueResonanceScopedLookup46.Entity(
            new Object(), cells(Map.of(KEY, 3 * ONE)), cells(Map.of(KEY, 4 * ONE)));
    var ability = cells(Map.of(KEY, 5 * ONE));
    var lookup = lookup(summoner, owner, null, ability);
    assertSame(ability, lookup.selectForEntity(summoner, 0, KEY));
    assertSame(owner.aiTag(), lookup.selectForEntity(summoner, 7, KEY));
    assertSame(summoner.customData(), lookup.selectForEntity(summoner, 6, KEY));
  }

  @Test
  void callbackScopeAlwaysUsesContextAndUnknownScopesAreRejected() {
    var callback = cells(Map.of(KEY, ONE));
    var target = new RogueResonanceScopedLookup46.Entity(new Object(), cells(Map.of(KEY, 2 * ONE)));
    var lookup = lookup(target, target, callback, null);
    assertSame(callback, lookup.selectForEntity(target, 9, KEY));
    var foreign = new RogueResonanceScopedLookup46.Entity(new Object(), null);
    assertSame(callback, lookup.selectForEntity(foreign, 9, KEY));
    assertThrows(IllegalArgumentException.class, () -> lookup.selectForEntity(foreign, 9, 77));
    for (int scope : new int[] {-1, 10, Integer.MAX_VALUE})
      assertThrows(
          IllegalArgumentException.class, () -> lookup.selectForEntity(target, scope, KEY));
  }

  @Test
  void scopeSixUsesPassedProjectionEvenIfRoleSharesItsIdentity() {
    var identity = new Object();
    var role = new RogueResonanceScopedLookup46.Entity(identity, cells(Map.of(KEY, ONE)));
    var target = new RogueResonanceScopedLookup46.Entity(identity, cells(Map.of(KEY, 2 * ONE)));
    var lookup = lookup(role, role, null, cells(Map.of(KEY, 3 * ONE)));
    assertSame(target.customData(), lookup.selectForEntity(target, 6, KEY));
    assertEquals(3 * ONE, lookup.readForEntity(target, 0, KEY));
  }

  @Test
  void nullTargetPointerEqualityPreservesNativeContextDelegation() {
    var callback = cells(Map.of(KEY, ONE));
    var absentRoles = lookup(null, null, callback, null);
    assertSame(callback, absentRoles.selectForEntity(null, 0, KEY));
    assertThrows(IllegalArgumentException.class, () -> absentRoles.selectForEntity(null, 6, KEY));
    var role = new RogueResonanceScopedLookup46.Entity(new Object(), null);
    var presentRoles = lookup(role, role, callback, null);
    assertThrows(IllegalArgumentException.class, () -> presentRoles.selectForEntity(null, 0, KEY));
    assertThrows(IllegalArgumentException.class, () -> presentRoles.selectForEntity(null, 7, KEY));
    assertSame(callback, presentRoles.selectForEntity(null, 9, KEY));
  }

  @Test
  void entityIdentityUsesReferenceRatherThanEquals() {
    var identity = new String("same");
    var targetIdentity = new String("same");
    assertEquals(identity, targetIdentity);
    assertNotSame(identity, targetIdentity);
    var role = new RogueResonanceScopedLookup46.Entity(identity, cells(Map.of(KEY, ONE)));
    var target =
        new RogueResonanceScopedLookup46.Entity(targetIdentity, cells(Map.of(KEY, 2 * ONE)));
    var lookup = lookup(role, role, cells(Map.of(KEY, 3 * ONE)), null);
    assertEquals(2 * ONE, lookup.readForEntity(target, 0, KEY));
  }

  @Test
  void targetSelectionFailurePreservesPreviousWriteAndStopsLaterTargets() {
    var first = cells(Map.of(KEY, 0L));
    var later = cells(Map.of(KEY, 0L));
    var targets =
        List.of(
            new RogueResonanceScopedLookup46.Entity(new Object(), first),
            new RogueResonanceScopedLookup46.Entity(new Object(), null),
            new RogueResonanceScopedLookup46.Entity(new Object(), later));
    var task = new RogueResonanceWrite46.ExpressionTask(2791, "Extra_AttackTime", constant(ONE));
    assertThrows(
        IllegalArgumentException.class,
        () -> task.executeForEntities(lookup(null, null, null, null), targets, 0, false));
    assertEquals(ONE, first.read(KEY));
    assertEquals(0, later.read(KEY));
    assertEquals(0x9999, task.state());
  }

  @Test
  void charmGuardPrecedesContextScopeTargetAndExpressionValidation() {
    var unresolved =
        RogueResonanceExpression46.compile(
            HexFormat.of().parseHex("010011"), new long[0], new int[] {123});
    var task = new RogueResonanceWrite46.ExpressionTask(2791, "Extra_AttackTime", unresolved);
    assertEquals(0, task.executeForEntities(null, null, -1, true));
    assertEquals(0x9999, task.state());
  }

  @Test
  void copiedTargetsAndFiniteLimitPreserveIterationAndMissingKeys() {
    var targets = new ArrayList<RogueResonanceScopedLookup46.Entity>();
    var first = RogueResonanceVariables46.fromSourceDefaults(Map.of(KEY, 0L), c -> targets.clear());
    var second = cells(Map.of(KEY, 0L));
    var empty = cells(Map.of());
    targets.add(new RogueResonanceScopedLookup46.Entity(new Object(), first));
    targets.add(new RogueResonanceScopedLookup46.Entity(new Object(), empty));
    targets.add(new RogueResonanceScopedLookup46.Entity(new Object(), second));
    var task = new RogueResonanceWrite46.ExpressionTask(2791, "Extra_AttackTime", constant(ONE));
    var lookup = lookup(null, null, null, null);
    assertEquals(2, task.executeForEntities(lookup, targets, 0, false));
    assertEquals(ONE, second.read(KEY));
    assertTrue(empty.snapshot().isEmpty());
    assertTrue(targets.isEmpty());
    assertEquals(0, task.executeForEntities(lookup, List.of(), 0, false));
    assertThrows(
        IllegalArgumentException.class, () -> task.executeForEntities(lookup, null, 0, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            task.executeForEntities(
                lookup,
                java.util.Collections.nCopies(
                    256, new RogueResonanceScopedLookup46.Entity(new Object(), second)),
                0,
                false));
  }

  @Test
  void propertyTaskMissingAiTagWriteDoesNotCreateCellOrNotify() {
    var changes = new ArrayList<RogueResonanceVariables46.Change>();
    var aiTag = RogueResonanceVariables46.fromSourceDefaults(Map.of(), changes::add);
    var lookup =
        lookup(
            null, new RogueResonanceScopedLookup46.Entity(new Object(), null, aiTag), null, null);
    var task = new RogueResonanceWrite46.PropertyTask(2855, "Extra_AttackTime", 12);
    assertFalse(task.executeInContext(lookup, 7, List.of(Map.of(12, ONE))));
    assertTrue(aiTag.snapshot().isEmpty());
    assertTrue(changes.isEmpty());
    assertEquals(0x9999, task.state());
  }
}
