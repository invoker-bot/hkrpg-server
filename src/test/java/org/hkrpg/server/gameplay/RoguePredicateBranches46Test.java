package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueTaskSequence46.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.hkrpg.server.gameplay.RogueModifierDefinitions46.*;
import org.junit.jupiter.api.Test;

class RoguePredicateBranches46Test {
  static class Child implements Task {
    int state = READY, result;
    int begins, resets;

    Child(int result) {
      this.result = result;
    }

    public int state() {
      return state;
    }

    public void begin() {
      begins++;
      state = result;
    }

    public void tick(float delta) {}

    public void reset() {
      resets++;
      state = READY;
    }
  }

  @Test
  void comparesSingleEntityIdentityWithDefaultMask15() {
    var masks = new ArrayList<Integer>();
    var entity = new String("entity");
    var targets =
        new RoguePredicateBranches46.Targets() {
          public Object first(String alias, int mask) {
            masks.add(mask);
            return entity;
          }

          public int team(Object ignored) {
            fail("comparison must not read team");
            return 0;
          }
        };
    assertTrue(RoguePredicateBranches46.evaluate(new CompareTargets("owner", "aim"), targets));
    assertEquals(List.of(15, 15), masks);
  }

  @Test
  void failedSelectedBranchFinishesTheParentSuccessfully() {
    var predicate = new Child(SUCCESS);
    var failed = new Child(FAIL);
    var success = new Sequence(List.of(failed), false);
    var branch =
        new RoguePredicateBranches46.BranchTask(true, predicate, success, null, s -> fail());
    branch.begin();
    assertEquals(SUCCESS, branch.state());
    assertEquals(FAIL, success.state());
    assertSame(success, branch.selected());
    assertEquals(1, predicate.resets);
  }

  @Test
  void compilerPreservesEmptySuccessBranchAndAbsentFailure() {
    var targets =
        new RoguePredicateBranches46.Targets() {
          public Object first(String a, int m) {
            return new Object();
          }

          public int team(Object e) {
            return 1;
          }
        };
    var factory =
        RoguePredicateBranches46.factory(
            targets,
            a -> {
              fail("no leaves");
              return null;
            },
            (c, e) -> new Child(SUCCESS),
            s -> fail());
    var branch =
        (RoguePredicateBranches46.BranchTask)
            factory.create(new Branch(new ByTeam("ParamEntity", 1), List.of(), null));
    branch.begin();
    assertNotNull(branch.selected());
    assertEquals(List.of(), branch.selected().children());
    assertEquals(SUCCESS, branch.state());
  }

  static final class Binding implements RoguePredicateBranches46.Targets {
    final Map<String, Object> values = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    int team = 1;

    public Object first(String alias, int mask) {
      calls.add(alias + ":" + mask);
      return values.get(alias);
    }

    public int team(Object e) {
      calls.add("team");
      return team;
    }
  }

  static final class Probe extends Child {
    Runnable onBegin = () -> {}, onReset = () -> {}, onTick = () -> {}, onDispose = () -> {};
    int ticks, disposes;
    float lastDelta;

    Probe(int result) {
      super(result);
    }

    public void begin() {
      super.begin();
      onBegin.run();
    }

    public void reset() {
      onReset.run();
      super.reset();
    }

    public void tick(float delta) {
      ticks++;
      lastDelta = delta;
      onTick.run();
    }

    public void dispose() {
      disposes++;
      onDispose.run();
    }
  }

  static Sequence sequence(Task child) {
    return new Sequence(List.of(child), false);
  }

  static RoguePredicateBranches46.BranchTask branch(Task p, Task s, Task f) {
    return new RoguePredicateBranches46.BranchTask(
        true, p, s == null ? null : sequence(s), f == null ? null : sequence(f), v -> {});
  }

  static Probe evaluator(java.util.function.BooleanSupplier evaluation) {
    var p = new Probe(SUCCESS);
    p.onBegin = () -> p.state = evaluation.getAsBoolean() ? SUCCESS : FAIL;
    return p;
  }

  @Test
  void valueEqualEntitiesAreNotTheSameOriginalTarget() {
    var b = new Binding();
    b.values.put("owner", new String("same"));
    b.values.put("aim", new String("same"));
    assertFalse(RoguePredicateBranches46.evaluate(new CompareTargets("owner", "aim"), b));
    assertEquals(List.of("owner:15", "aim:15"), b.calls);
  }

  @Test
  void missingFirstTargetStopsBeforeSecondSelection() {
    var b = new Binding();
    assertFalse(RoguePredicateBranches46.evaluate(new CompareTargets("owner", "aim"), b));
    assertEquals(List.of("owner:15"), b.calls);
  }

  @Test
  void missingSecondTargetIsFalse() {
    var b = new Binding();
    b.values.put("owner", new Object());
    assertFalse(RoguePredicateBranches46.evaluate(new CompareTargets("owner", "aim"), b));
    assertEquals(List.of("owner:15", "aim:15"), b.calls);
  }

  @Test
  void sameAliasStillPerformsTwoLiveSelections() {
    var b = new Binding();
    b.values.put("owner", new Object());
    assertTrue(RoguePredicateBranches46.evaluate(new CompareTargets("owner", "owner"), b));
    assertEquals(List.of("owner:15", "owner:15"), b.calls);
  }

  @Test
  void explicitCompareMaskReachesBothSelections() {
    var b = new Binding();
    var e = new Object();
    b.values.put("owner", e);
    b.values.put("aim", e);
    assertTrue(RoguePredicateBranches46.compare("owner", "aim", 0, b));
    assertEquals(List.of("owner:0", "aim:0"), b.calls);
  }

  @Test
  void teamUsesAllMaskAndReadsOnlyTheSelectedEntity() {
    var b = new Binding();
    b.values.put("ParamEntity", new Object());
    assertTrue(RoguePredicateBranches46.evaluate(new ByTeam("ParamEntity", 1), b));
    assertEquals(List.of("ParamEntity:65535", "team"), b.calls);
    b.team = 2;
    assertFalse(RoguePredicateBranches46.evaluate(new ByTeam("ParamEntity", 1), b));
  }

  @Test
  void absentTeamTargetDoesNotReadItsTeam() {
    var b = new Binding();
    assertFalse(RoguePredicateBranches46.evaluate(new ByTeam("ParamEntity", 1), b));
    assertEquals(List.of("ParamEntity:65535"), b.calls);
  }

  @Test
  void selectionFailurePropagatesWithoutTryingAnotherTarget() {
    var e = new IllegalStateException("selection");
    var b =
        new RoguePredicateBranches46.Targets() {
          public Object first(String a, int m) {
            throw e;
          }

          public int team(Object o) {
            fail();
            return 0;
          }
        };
    assertSame(
        e,
        assertThrows(
            IllegalStateException.class,
            () -> RoguePredicateBranches46.evaluate(new CompareTargets("a", "b"), b)));
  }

  @Test
  void projectBindingGuardsDoNotInventSuccessfulUnknownTargets() {
    var b = new Binding();
    assertThrows(IllegalArgumentException.class, () -> RoguePredicateBranches46.evaluate(null, b));
    assertThrows(
        IllegalArgumentException.class,
        () -> RoguePredicateBranches46.evaluate(new ByTeam("a", 1), null));
    for (int mask : new int[] {-1, 65536})
      assertThrows(
          IllegalArgumentException.class,
          () -> RoguePredicateBranches46.compare("a", "b", mask, b));
    assertThrows(
        IllegalArgumentException.class, () -> RoguePredicateBranches46.compare("", "b", 15, b));
    assertTrue(b.calls.isEmpty());
  }

  @Test
  void absentPredicateConfigurationDoesNotEvaluateOrPickABranch() {
    var p = new Probe(SUCCESS);
    var s = new Probe(SUCCESS);
    var f = new Probe(SUCCESS);
    var task =
        new RoguePredicateBranches46.BranchTask(false, p, sequence(s), sequence(f), v -> fail());
    task.begin();
    assertEquals(SUCCESS, task.state());
    assertNull(task.selected());
    assertEquals(0, p.begins + s.begins + f.begins);
  }

  @Test
  void missingPredicateTaskWithPresentConfigurationPicksFailure() {
    var s = new Probe(SUCCESS);
    var f = new Probe(SUCCESS);
    var task = branch(null, s, f);
    task.begin();
    assertEquals(0, s.begins);
    assertEquals(1, f.begins);
    assertEquals(SUCCESS, task.state());
  }

  @Test
  void onlyExactSuccessStatePicksTheSuccessBranch() {
    for (int result : new int[] {READY, EXECUTING, FAIL, 0}) {
      var p = new Probe(result);
      var s = new Probe(SUCCESS);
      var f = new Probe(SUCCESS);
      var wait = new ArrayList<Integer>();
      var task =
          new RoguePredicateBranches46.BranchTask(true, p, sequence(s), sequence(f), wait::add);
      task.begin();
      assertEquals(0, s.begins);
      assertEquals(1, f.begins);
      assertEquals(1, p.resets);
      assertEquals(result == EXECUTING ? List.of(EXECUTING) : List.of(), wait);
    }
  }

  @Test
  void runOnceReturnsBeforeResetSnapshot() {
    var p = new Probe(FAIL);
    assertEquals(FAIL, RoguePredicateBranches46.runOnce(p, v -> fail()));
    assertEquals(READY, p.state());
    assertEquals(1, p.resets);
    assertEquals(FAIL, RoguePredicateBranches46.runOnce(null, v -> fail()));
  }

  @Test
  void predicateBeginExceptionSkipsResetAndBranchSelection() {
    var p = new Probe(SUCCESS);
    var e = new IllegalStateException("begin");
    p.onBegin =
        () -> {
          throw e;
        };
    var s = new Probe(SUCCESS);
    var task = branch(p, s, null);
    assertSame(e, assertThrows(IllegalStateException.class, task::begin));
    assertEquals(0, p.resets);
    assertNull(task.selected());
    assertEquals(READY, task.state());
    assertEquals(0, s.begins);
  }

  @Test
  void waitingDiagnosticExceptionSkipsResetAndBranchSelection() {
    var p = new Probe(EXECUTING);
    var e = new IllegalStateException("diagnostic");
    var task =
        new RoguePredicateBranches46.BranchTask(
            true,
            p,
            null,
            null,
            v -> {
              throw e;
            });
    assertSame(e, assertThrows(IllegalStateException.class, task::begin));
    assertEquals(0, p.resets);
    assertEquals(EXECUTING, p.state());
    assertNull(task.selected());
    assertEquals(READY, task.state());
  }

  @Test
  void predicateResetExceptionStopsBeforeBranchSelection() {
    var p = new Probe(SUCCESS);
    var e = new IllegalStateException("reset");
    p.onReset =
        () -> {
          throw e;
        };
    var task = branch(p, new Probe(SUCCESS), null);
    assertSame(e, assertThrows(IllegalStateException.class, task::begin));
    assertNull(task.selected());
    assertEquals(READY, task.state());
  }

  @Test
  void selectedBranchResetExceptionKeepsSelectedAndParentState() {
    var s = new Probe(SUCCESS);
    var e = new IllegalStateException("reset");
    s.onReset =
        () -> {
          throw e;
        };
    var task = branch(new Probe(SUCCESS), s, null);
    assertSame(e, assertThrows(IllegalStateException.class, task::begin));
    assertNotNull(task.selected());
    assertEquals(READY, task.state());
    assertEquals(0, s.begins);
  }

  @Test
  void selectedBranchBeginExceptionDoesNotRollBackItsPreviousWork() {
    var s = new Probe(SUCCESS);
    var e = new IllegalStateException("begin");
    s.onBegin =
        () -> {
          throw e;
        };
    var task = branch(new Probe(SUCCESS), s, null);
    assertSame(e, assertThrows(IllegalStateException.class, task::begin));
    assertEquals(1, s.resets);
    assertEquals(1, s.begins);
    assertEquals(SUCCESS, s.state());
    assertNotNull(task.selected());
    assertEquals(READY, task.state());
  }

  @Test
  void executingBranchWaitsAndLaterCompletesWithoutReevaluatingPredicate() {
    var p = new Probe(SUCCESS);
    var s = new Probe(EXECUTING);
    var task = branch(p, s, null);
    task.begin();
    assertEquals(EXECUTING, task.state());
    assertEquals(1, s.ticks);
    s.onTick = () -> s.state = SUCCESS;
    task.tick(.25f);
    assertEquals(.25f, s.lastDelta);
    assertEquals(SUCCESS, task.state());
    assertEquals(1, p.begins);
    assertEquals(2, s.ticks);
    task.tick(.5f);
    assertEquals(2, s.ticks);
  }

  @Test
  void alreadyFailedSelectedSequenceEndsParentWithoutTickingItAgain() {
    var p = new Probe(SUCCESS);
    var s = new Probe(EXECUTING);
    var task = branch(p, s, null);
    task.begin();
    task.selected().stopForChild(FAIL);
    task.tick(.1f);
    assertEquals(SUCCESS, task.state());
    assertEquals(1, s.ticks);
  }

  @Test
  void tickExceptionPreservesWaitingParentAndPreviousEffects() {
    var s = new Probe(EXECUTING);
    var task = branch(new Probe(SUCCESS), s, null);
    task.begin();
    var e = new IllegalStateException("tick");
    s.onTick =
        () -> {
          throw e;
        };
    assertSame(e, assertThrows(IllegalStateException.class, () -> task.tick(.1f)));
    assertEquals(EXECUTING, task.state());
    assertEquals(2, s.ticks);
  }

  @Test
  void resetTouchesOnlySelectedBranchAndClearsItAfterReturn() {
    var p = new Probe(SUCCESS);
    var s = new Probe(SUCCESS);
    var f = new Probe(SUCCESS);
    var task = branch(p, s, f);
    task.begin();
    task.reset();
    assertEquals(2, s.resets);
    assertEquals(0, f.resets);
    assertEquals(1, p.resets);
    assertNull(task.selected());
    assertEquals(READY, task.state());
  }

  @Test
  void resetExceptionKeepsSelectedPointerAndCompletedParentState() {
    var s = new Probe(SUCCESS);
    var task = branch(new Probe(SUCCESS), s, null);
    task.begin();
    var selected = task.selected();
    var e = new IllegalStateException("reset");
    s.onReset =
        () -> {
          throw e;
        };
    assertSame(e, assertThrows(IllegalStateException.class, task::reset));
    assertSame(selected, task.selected());
    assertEquals(SUCCESS, task.state());
  }

  @Test
  void repeatedBeginReevaluatesAndChangesSelectedBranch() {
    var p = new Probe(SUCCESS);
    var s = new Probe(SUCCESS);
    var f = new Probe(SUCCESS);
    var task = branch(p, s, f);
    task.begin();
    p.result = FAIL;
    task.begin();
    assertEquals(2, p.begins);
    assertEquals(2, p.resets);
    assertEquals(1, s.begins);
    assertEquals(1, f.begins);
    assertEquals(SUCCESS, task.state());
  }

  @Test
  void exceptionOnRepeatedBeginPreservesOldSelectedPointerAndState() {
    var p = new Probe(SUCCESS);
    var task = branch(p, new Probe(SUCCESS), null);
    task.begin();
    var selected = task.selected();
    p.onBegin =
        () -> {
          throw new IllegalStateException("repeat");
        };
    assertThrows(IllegalStateException.class, task::begin);
    assertSame(selected, task.selected());
    assertEquals(SUCCESS, task.state());
  }

  @Test
  void disposeReleasesSuccessFailureThenPredicateAndDoesNotRewriteState() {
    var calls = new ArrayList<String>();
    var p = new Probe(SUCCESS);
    var s = new Probe(SUCCESS);
    var f = new Probe(SUCCESS);
    p.onDispose = () -> calls.add("predicate");
    s.onDispose = () -> calls.add("success");
    f.onDispose = () -> calls.add("failure");
    var task = branch(p, s, f);
    task.begin();
    var selected = task.selected();
    task.dispose();
    task.dispose();
    assertEquals(List.of("success", "failure", "predicate"), calls);
    assertSame(selected, task.selected());
    assertEquals(SUCCESS, task.state());
  }

  @Test
  void failedDisposeKeepsPriorReleaseAndStopsBeforePredicate() {
    var p = new Probe(SUCCESS);
    var s = new Probe(SUCCESS);
    var f = new Probe(SUCCESS);
    var e = new IllegalStateException("dispose");
    f.onDispose =
        () -> {
          throw e;
        };
    var task = branch(p, s, f);
    assertSame(e, assertThrows(IllegalStateException.class, task::dispose));
    assertEquals(1, s.disposes);
    assertEquals(1, f.disposes);
    assertEquals(0, p.disposes);
    f.onDispose = () -> {};
    task.dispose();
    assertEquals(1, s.disposes);
    assertEquals(2, f.disposes);
    assertEquals(1, p.disposes);
  }

  @Test
  void originalCreationCallbackAddsSubModifierOnlyForTeamOne() {
    var b = new Binding();
    b.values.put("ParamEntity", new Object());
    var actions = new ArrayList<Action>();
    var factory =
        RoguePredicateBranches46.factory(
            b,
            a -> {
              var p = new Probe(SUCCESS);
              p.onBegin = () -> actions.add(a);
              return p;
            },
            (c, e) -> evaluator(e),
            v -> fail());
    var d =
        RogueModifierDefinitions46.breedingGlobal().get("MAvatar_RogueBattleevent127_AimTarget");
    var callback = RogueModifierDefinitions46.compileCallback(d, 57, factory);
    assertTrue(b.calls.isEmpty());
    callback.begin();
    assertEquals(
        List.of(new Add("ParamEntity", "MAvatar_RogueBattleevent127_AimTarget_Sub")), actions);
    callback.reset();
    b.team = 2;
    callback.begin();
    assertEquals(1, actions.size());
    assertEquals(SUCCESS, callback.state());
  }

  @Test
  void originalRefreshCallbackKeepsSameTargetAndRemovesEffectAfterTargetChanges() {
    var b = new Binding();
    var owner = new Object();
    b.values.put("ModifierOwnerEntity", owner);
    b.values.put("CurrentAimAtTarget", owner);
    var actions = new ArrayList<Action>();
    var factory =
        RoguePredicateBranches46.factory(
            b,
            a -> {
              var p = new Probe(SUCCESS);
              p.onBegin = () -> actions.add(a);
              return p;
            },
            (c, e) -> evaluator(e),
            v -> fail());
    var d =
        RogueModifierDefinitions46.breedingGlobal()
            .get("MAvatar_RogueBattleevent127_AimTarget_Sub");
    var callback = RogueModifierDefinitions46.compileCallback(d, 173, factory);
    callback.begin();
    assertTrue(actions.isEmpty());
    callback.reset();
    b.values.put("CurrentAimAtTarget", new Object());
    callback.begin();
    assertEquals(1, actions.size());
    var remove = (Remove) actions.getFirst();
    assertEquals("ModifierOwnerEntity", remove.alias());
    assertTrue(remove.effectPath().endsWith("Eff_BattleEvent_Rogue_Breeding_02.prefab"));
    assertFalse(remove.forceImmediateFadeOut());
  }

  @Test
  void compilerAllocatesSuccessFailureThenPredicateAndDoesNotEvaluateUntilBegin() {
    var calls = new ArrayList<String>();
    var b = new Binding();
    b.values.put("a", new Object());
    var factory =
        RoguePredicateBranches46.factory(
            b,
            a -> {
              calls.add(((Add) a).literalName());
              return new Probe(SUCCESS);
            },
            (c, e) -> {
              calls.add("predicate");
              return evaluator(e);
            },
            v -> fail());
    var task =
        factory.create(
            new Branch(
                new ByTeam("a", 1),
                List.of(new Add("a", "success")),
                List.of(new Add("a", "failure"))));
    assertEquals(List.of("success", "failure", "predicate"), calls);
    assertTrue(b.calls.isEmpty());
    task.begin();
    assertFalse(b.calls.isEmpty());
  }

  @Test
  void nestedBranchesCompileThroughTheSameExplicitFactories() {
    var b = new Binding();
    b.values.put("a", new Object());
    var effects = new ArrayList<String>();
    var factory =
        RoguePredicateBranches46.factory(
            b,
            a -> {
              var p = new Probe(SUCCESS);
              p.onBegin = () -> effects.add(((Add) a).literalName());
              return p;
            },
            (c, e) -> evaluator(e),
            v -> fail());
    var inner = new Branch(new ByTeam("a", 1), List.of(new Add("a", "nested")), null);
    var task = factory.create(new Branch(new ByTeam("a", 1), List.of(inner), null));
    task.begin();
    assertEquals(List.of("nested"), effects);
    assertEquals(SUCCESS, task.state());
  }

  @Test
  void missingOrNullFactoriesNeverBecomeEmptySuccessfulGameplay() {
    var b = new Binding();
    var source = new Branch(new ByTeam("a", 1), List.of(), null);
    assertThrows(
        IllegalArgumentException.class,
        () -> RoguePredicateBranches46.factory(b, null, null, v -> {}).create(source));
    assertThrows(
        IllegalArgumentException.class,
        () -> RoguePredicateBranches46.factory(b, null, (c, e) -> null, v -> {}).create(source));
    assertThrows(
        IllegalArgumentException.class,
        () -> RoguePredicateBranches46.factory(null, null, null, null).create(new Add("a", "m")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RoguePredicateBranches46.factory(null, a -> null, null, null)
                .create(new Add("a", "m")));
  }

  @Test
  void explicitLeavesRemainUsableWithoutUnneededPredicateBindings() {
    var leaf = new Probe(SUCCESS);
    var factory = RoguePredicateBranches46.factory(null, a -> leaf, null, null);
    assertSame(leaf, factory.create(new Add("a", "m")));
    assertEquals(0, leaf.begins);
  }

  @Test
  void allocationFailureDoesNotExecuteOrDisposeAlreadyCreatedActions() {
    var b = new Binding();
    var first = new Probe(SUCCESS);
    var e = new IllegalStateException("allocation");
    var n = new int[1];
    var factory =
        RoguePredicateBranches46.factory(
            b,
            a -> {
              if (n[0]++ == 0) return first;
              throw e;
            },
            (c, v) -> evaluator(v),
            v -> fail());
    var source =
        new Branch(new ByTeam("a", 1), List.of(new Add("a", "one")), List.of(new Add("a", "two")));
    assertSame(e, assertThrows(IllegalStateException.class, () -> factory.create(source)));
    assertEquals(0, first.begins + first.disposes);
    assertTrue(b.calls.isEmpty());
  }

  @Test
  void finiteDeltaGuardAppliesOnlyWhileParentIsExecuting() {
    var task = branch(new Probe(SUCCESS), new Probe(EXECUTING), null);
    task.tick(Float.NaN);
    task.begin();
    for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -1})
      assertThrows(IllegalArgumentException.class, () -> task.tick(value));
    assertEquals(EXECUTING, task.state());
  }
}
