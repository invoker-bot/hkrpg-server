package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RogueModifierSelection46Test {
  static class Bound implements RogueModifierSelection46.Bindings {
    final Object source = new Object(), target = new Object(), definition = new Object();
    final List<String> calls = new ArrayList<>();
    final List<RogueModifierSelection46.Qualified> applied = new ArrayList<>();
    int state = 1, type = 12, sourceTeam = 2;
    Boolean mute = true, charm = false;
    boolean flag, extra = true;
    Object resolved = definition;
    String reference;
    RogueModifierSelection46.Override override;
    List<Object> evaluated = List.of(target);

    public RogueAddModifierEntry46.Source source() {
      calls.add("source");
      return new RogueAddModifierEntry46.Source(source, charm);
    }

    public List<Object> targets(String alias, int mask) {
      assertEquals(1, mask);
      calls.add("targets:" + alias);
      return evaluated;
    }

    public RogueModifierSelection46.Override overrideFor(Object target, String name) {
      calls.add("override:" + name);
      return override;
    }

    public String referenceModifierName(RogueAddModifierEntry46.Request request) {
      calls.add("reference");
      return reference;
    }

    public Object resolveDefinition(String name) {
      calls.add("lookup:" + name);
      return resolved;
    }

    public int aliveState(Object target) {
      calls.add("alive");
      return state;
    }

    public int entityType(Object target) {
      calls.add("type");
      return type;
    }

    public Boolean muteDarkTeamModifierAdd(Object target) {
      calls.add("mute");
      return mute;
    }

    public int team(Object entity) {
      assertSame(source, entity);
      calls.add("team");
      return sourceTeam;
    }

    public boolean containsBehaviorFlag(Object definition, int flag) {
      assertSame(this.definition, definition);
      assertEquals(84, flag);
      calls.add("flag:84");
      return this.flag;
    }

    public void addModifierAlias(String instance, String lookup) {
      calls.add("alias:" + instance + ":" + lookup);
    }

    public boolean additionalRestriction(RogueModifierSelection46.Qualified q) {
      calls.add("extra");
      return extra;
    }

    public void applyQualified(RogueModifierSelection46.Qualified q) {
      calls.add("apply");
      applied.add(q);
    }

    public void finished(List<Object> targets) {
      assertEquals(evaluated, targets);
      calls.add("finished");
    }

    RogueTaskSequence46.Task task() {
      return RogueModifierSelection46.factory(this)
          .create(new RogueResonanceOnAdd46.Modifier(null, "Caster", "Original"));
    }
  }

  @Test
  void mutedDarkTeamWithoutRequiredFlagIsRejected() {
    var b = new Bound();
    assertFalse(RogueModifierSelection46.allowed(b.target, b.source, b.definition, b));
    assertEquals(List.of("alive", "type", "mute", "team", "flag:84"), b.calls);
  }

  @Test
  void overrideKeepsOriginalReferenceWhileLookupUsesExplicitDefinitionName() {
    var names =
        RogueModifierSelection46.names(
            "Original", "Definition", new RogueModifierSelection46.Override("Replacement", true));
    assertEquals(
        new RogueModifierSelection46.Names("Definition", "Replacement", "Original"), names);
    assertTrue(names.referenceChanged());
  }

  @Test
  void aliasIsRegisteredBeforeAdditionalRestrictionAndRejectedTargetNeverApplies() {
    var b = new Bound();
    b.flag = true;
    b.reference = "Definition";
    b.extra = false;
    b.task().begin();
    assertEquals(
        List.of(
            "source",
            "targets:Caster",
            "override:Original",
            "reference",
            "lookup:Definition",
            "alive",
            "type",
            "mute",
            "team",
            "flag:84",
            "alias:Original:Definition",
            "extra",
            "finished"),
        b.calls);
    assertTrue(b.applied.isEmpty());
  }

  @Test
  void absentTargetReturnsFalseWithoutReadingWorld() {
    var b = new Bound();
    assertFalse(RogueModifierSelection46.allowed(null, null, null, b));
    assertTrue(b.calls.isEmpty());
  }

  @Test
  void onlyFourAliveOrLimboStatesPassAndRejectedStatesNeverReadType() {
    for (int state : new int[] {Integer.MIN_VALUE, -1, 0, 1, 2, 3, 4, 5, 8, Integer.MAX_VALUE}) {
      var b = new Bound();
      b.state = state;
      b.type = 0;
      boolean accepted = state >= 1 && state <= 4;
      assertEquals(accepted, RogueModifierSelection46.allowed(b.target, null, null, b));
      assertEquals(accepted ? List.of("alive", "type") : List.of("alive"), b.calls);
    }
  }

  @Test
  void nonBattleEventDoesNotReadComponentOrSourceOrConfiguration() {
    var b = new Bound();
    b.type = 11;
    assertTrue(RogueModifierSelection46.allowed(b.target, null, null, b));
    assertEquals(List.of("alive", "type"), b.calls);
  }

  @Test
  void absentOrUnmutedBattleEventComponentDoesNotReadSource() {
    for (Boolean mute : new Boolean[] {null, false}) {
      var b = new Bound();
      b.mute = mute;
      assertTrue(RogueModifierSelection46.allowed(b.target, null, null, b));
      assertEquals(List.of("alive", "type", "mute"), b.calls);
    }
  }

  @Test
  void sourceTeamsOtherThanTwoDoNotReadConfiguration() {
    for (int team : new int[] {Integer.MIN_VALUE, -1, 0, 1, 3, 4, 5, Integer.MAX_VALUE}) {
      var b = new Bound();
      b.sourceTeam = team;
      assertTrue(RogueModifierSelection46.allowed(b.target, b.source, null, b));
      assertEquals(List.of("alive", "type", "mute", "team"), b.calls);
    }
  }

  @Test
  void requiredBehaviorFlagAllowsMutedDarkTeam() {
    var b = new Bound();
    b.flag = true;
    assertTrue(RogueModifierSelection46.allowed(b.target, b.source, b.definition, b));
    assertEquals(List.of("alive", "type", "mute", "team", "flag:84"), b.calls);
  }

  @Test
  void absentConfigurationRejectsMutedDarkTeamWithoutReadingFlag() {
    var b = new Bound();
    assertFalse(RogueModifierSelection46.allowed(b.target, b.source, null, b));
    assertEquals(List.of("alive", "type", "mute", "team"), b.calls);
  }

  @Test
  void missingSourceThrowsOnlyWhenMutedBattleEventNeedsItsTeam() {
    var b = new Bound();
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierSelection46.allowed(b.target, null, b.definition, b));
    assertEquals(List.of("alive", "type", "mute"), b.calls);
  }

  @Test
  void projectionFailureStopsFollowingReads() {
    var problem = new IllegalStateException("world");
    var b =
        new Bound() {
          public int entityType(Object target) {
            calls.add("type");
            throw problem;
          }
        };
    assertSame(
        problem,
        assertThrows(
            IllegalStateException.class,
            () -> RogueModifierSelection46.allowed(b.target, b.source, b.definition, b)));
    assertEquals(List.of("alive", "type"), b.calls);
  }

  @Test
  void originalNameIsUsedForAllRolesWithoutOverrideOrExplicitReference() {
    assertEquals(
        new RogueModifierSelection46.Names("Original", "Original", "Original"),
        RogueModifierSelection46.names("Original", null, null));
  }

  @Test
  void replacementBecomesBothInstanceAndReferenceWhenOriginalIsNotKept() {
    var n =
        RogueModifierSelection46.names(
            "Original", null, new RogueModifierSelection46.Override("Replacement", false));
    assertEquals(
        new RogueModifierSelection46.Names("Replacement", "Replacement", "Replacement"), n);
    assertFalse(n.referenceChanged());
  }

  @Test
  void nullReplacementFallsBackToOriginalEvenWhenOriginalIsKept() {
    for (boolean keep : new boolean[] {true, false}) {
      var n =
          RogueModifierSelection46.names(
              "Original", "Config", new RogueModifierSelection46.Override(null, keep));
      assertEquals(new RogueModifierSelection46.Names("Config", "Original", "Original"), n);
      assertFalse(n.referenceChanged());
    }
  }

  @Test
  void emptyOverrideAndReferenceRemainExplicitValuesRatherThanNullDefaults() {
    assertEquals(
        new RogueModifierSelection46.Names("", "", "Original"),
        RogueModifierSelection46.names(
            "Original", "", new RogueModifierSelection46.Override("", true)));
  }

  @Test
  void referenceChangeUsesOrdinalContentRatherThanObjectIdentity() {
    String original = new String("原始\uD83C\uDF0C");
    String replacement = new String("原始\uD83C\uDF0C");
    assertNotSame(original, replacement);
    assertFalse(
        RogueModifierSelection46.names(
                original, null, new RogueModifierSelection46.Override(replacement, true))
            .referenceChanged());
    assertTrue(
        RogueModifierSelection46.names("a", null, new RogueModifierSelection46.Override("A", true))
            .referenceChanged());
  }

  @Test
  void acceptedEntryPassesResolvedDefinitionAndAllNameRolesToApplication() {
    var b = new Bound();
    b.flag = true;
    b.override = new RogueModifierSelection46.Override("Replacement", true);
    var task = b.task();
    task.begin();
    assertEquals(RogueTaskSequence46.SUCCESS, task.state());
    assertEquals(1, b.applied.size());
    var q = b.applied.getFirst();
    assertSame(b.definition, q.definition());
    assertSame(b.source, q.request().sourceEntity());
    assertSame(b.target, q.request().targetEntity());
    assertEquals("Original", q.request().literalName());
    assertEquals(
        new RogueModifierSelection46.Names("Replacement", "Replacement", "Original"), q.names());
    assertTrue(q.names().referenceChanged());
    assertEquals(
        List.of(
            "source",
            "targets:Caster",
            "override:Original",
            "reference",
            "lookup:Replacement",
            "alive",
            "type",
            "mute",
            "team",
            "flag:84",
            "extra",
            "apply",
            "finished"),
        b.calls);
  }

  @Test
  void equivalentDefinitionNameDoesNotRegisterAlias() {
    var b = new Bound();
    b.type = 0;
    b.reference = new String("Original");
    b.task().begin();
    assertFalse(b.calls.stream().anyMatch(s -> s.startsWith("alias:")));
    assertEquals(1, b.applied.size());
  }

  @Test
  void rejectedTargetDoesNotRegisterAliasOrCheckAdditionalRestriction() {
    var b = new Bound();
    b.reference = "Definition";
    var task = b.task();
    task.begin();
    assertTrue(b.applied.isEmpty());
    assertEquals(RogueTaskSequence46.SUCCESS, task.state());
    assertEquals(
        List.of(
            "source",
            "targets:Caster",
            "override:Original",
            "reference",
            "lookup:Definition",
            "alive",
            "type",
            "mute",
            "team",
            "flag:84",
            "finished"),
        b.calls);
  }

  @Test
  void definitionLookupOccursBeforeDeadTargetIsRejected() {
    var b = new Bound();
    b.state = 5;
    b.task().begin();
    assertEquals(
        List.of(
            "source",
            "targets:Caster",
            "override:Original",
            "reference",
            "lookup:Original",
            "alive",
            "finished"),
        b.calls);
    assertTrue(b.applied.isEmpty());
  }

  @Test
  void missingDefinitionOnMutedDarkTeamSkipsWithoutDereferencingConfiguration() {
    var b = new Bound();
    b.resolved = null;
    var task = b.task();
    task.begin();
    assertEquals(RogueTaskSequence46.SUCCESS, task.state());
    assertFalse(b.calls.contains("flag:84"));
    assertFalse(b.calls.contains("extra"));
  }

  @Test
  void otherwiseMissingDefinitionThrowsAfterAliasAndBeforeAdditionalRestriction() {
    var b = new Bound();
    b.resolved = null;
    b.type = 0;
    b.reference = "Missing";
    var task = b.task();
    assertThrows(IllegalArgumentException.class, task::begin);
    assertEquals(RogueTaskSequence46.READY, task.state());
    assertEquals(
        List.of(
            "source",
            "targets:Caster",
            "override:Original",
            "reference",
            "lookup:Missing",
            "alive",
            "type",
            "alias:Original:Missing"),
        b.calls);
  }

  @Test
  void charmSkipsAllQualificationAndEmptyTargetsSkipFinalNotification() {
    var b = new Bound();
    b.charm = true;
    b.task().begin();
    assertEquals(List.of("source"), b.calls);
    b.calls.clear();
    b.charm = false;
    b.evaluated = List.of();
    b.task().begin();
    assertEquals(List.of("source", "targets:Caster"), b.calls);
  }

  @Test
  void aliasFailureStopsApplicationAndKeepsTaskState() {
    var problem = new IllegalStateException("alias");
    var b =
        new Bound() {
          public void addModifierAlias(String instance, String lookup) {
            super.addModifierAlias(instance, lookup);
            throw problem;
          }
        };
    b.type = 0;
    b.reference = "Config";
    var task = b.task();
    assertSame(problem, assertThrows(IllegalStateException.class, task::begin));
    assertEquals(RogueTaskSequence46.READY, task.state());
    assertFalse(b.calls.contains("extra"));
    assertFalse(b.calls.contains("finished"));
  }

  @Test
  void subsequentTargetFailurePreservesEarlierApplicationWithoutFinalNotification() {
    Object second = new Object();
    var problem = new IllegalStateException("second");
    var b =
        new Bound() {
          public boolean additionalRestriction(RogueModifierSelection46.Qualified q) {
            if (q.request().targetEntity() == second) throw problem;
            return super.additionalRestriction(q);
          }
        };
    b.type = 0;
    b.evaluated = List.of(b.target, second);
    var task = b.task();
    assertSame(problem, assertThrows(IllegalStateException.class, task::begin));
    assertEquals(1, b.applied.size());
    assertSame(b.target, b.applied.getFirst().request().targetEntity());
    assertEquals(RogueTaskSequence46.READY, task.state());
    assertFalse(b.calls.contains("finished"));
  }

  @Test
  void repeatedEntryRereadsOverrideAndQualificationWithoutInitialStateRewrite() {
    var b = new Bound();
    b.type = 0;
    var task = b.task();
    task.begin();
    b.override = new RogueModifierSelection46.Override("Replacement", false);
    task.begin();
    assertEquals(
        List.of("Original", "Replacement"),
        b.applied.stream().map(q -> q.names().instanceName()).toList());
    b.resolved = null;
    assertThrows(IllegalArgumentException.class, task::begin);
    assertEquals(RogueTaskSequence46.SUCCESS, task.state());
    task.reset();
    assertEquals(RogueTaskSequence46.READY, task.state());
  }

  @Test
  void applicationFailureRetainsRegisteredAliasAndStopsCompletion() {
    var problem = new IllegalStateException("instance");
    var b =
        new Bound() {
          public void applyQualified(RogueModifierSelection46.Qualified q) {
            super.applyQualified(q);
            throw problem;
          }
        };
    b.type = 0;
    b.reference = "Config";
    var task = b.task();
    assertSame(problem, assertThrows(IllegalStateException.class, task::begin));
    assertTrue(b.calls.contains("alias:Original:Config"));
    assertEquals(1, b.applied.size());
    assertFalse(b.calls.contains("finished"));
    assertEquals(RogueTaskSequence46.READY, task.state());
  }

  @Test
  void projectionBindingsAndOriginalLiteralAreRequiredByTheFiniteAdapter() {
    assertThrows(IllegalArgumentException.class, () -> RogueModifierSelection46.context(null));
    assertThrows(IllegalArgumentException.class, () -> RogueModifierSelection46.factory(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierSelection46.allowed(null, null, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierSelection46.names(null, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> RogueModifierSelection46.names("", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> RogueModifierSelection46.context(new Bound()).apply(null));
  }
}
