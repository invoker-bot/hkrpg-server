package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueTaskSequence46.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RoguePredicateTask46Test {
  @Test
  void inverseTurnsFalseEvaluationIntoSuccess() {
    var task = new RoguePredicateTask46(() -> false, () -> true);
    task.begin();
    assertEquals(SUCCESS, task.state());
  }

  @Test
  void inverseIsReadAfterEvaluationAndCanObserveItsConfigurationChange() {
    var order = new ArrayList<String>();
    var inverse = new boolean[1];
    var task =
        new RoguePredicateTask46(
            () -> {
              order.add("evaluate");
              inverse[0] = true;
              return true;
            },
            () -> {
              order.add("inverse");
              return inverse[0];
            });
    task.begin();
    assertEquals(List.of("evaluate", "inverse"), order);
    assertEquals(FAIL, task.state());
  }

  @Test
  void tickThrowsInsteadOfInventingAnEmptySuccessfulAction() {
    var task = new RoguePredicateTask46(() -> true, () -> false);
    assertThrows(UnsupportedOperationException.class, () -> task.tick(0));
    assertEquals(READY, task.state());
  }

  static class Binding implements RoguePredicateBranches46.Targets {
    final Map<String, Object> entities = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    int team = 1;

    public Object first(String alias, int mask) {
      calls.add(alias + ":" + mask);
      return entities.get(alias);
    }

    public int team(Object entity) {
      calls.add("team");
      return team;
    }
  }

  static class Leaf implements RogueTaskSequence46.Task {
    int state = READY, begins, ticks, resets;
    int result = SUCCESS;
    Runnable effect = () -> {};

    public int state() {
      return state;
    }

    public void begin() {
      begins++;
      effect.run();
      state = result;
    }

    public void tick(float delta) {
      ticks++;
    }

    public void reset() {
      resets++;
      state = READY;
    }
  }

  static org.hkrpg.server.gameplay.RogueModifierDefinitions46.Definition definition(String suffix) {
    return RogueModifierDefinitions46.breedingGlobal()
        .get("MAvatar_RogueBattleevent127_AimTarget" + suffix);
  }

  static org.hkrpg.server.gameplay.RogueModifierDefinitions46.Factory factory(
      Binding binding, List<RogueModifierDefinitions46.Action> effects) {
    return RoguePredicateBranches46.factory(
        binding,
        a -> {
          var task = new Leaf();
          task.effect = () -> effects.add(a);
          return task;
        },
        v -> fail("immediate predicate cannot wait"));
  }

  @Test
  void constructorStartsReadyWithoutReadingTheWorldOrConfiguration() {
    var task =
        new RoguePredicateTask46(
            () -> {
              fail();
              return false;
            },
            () -> {
              fail();
              return false;
            });
    assertEquals(READY, task.state());
  }

  @Test
  void allFourBooleanCombinationsUseTheOriginalExclusiveOr() {
    for (boolean result : new boolean[] {false, true})
      for (boolean inverse : new boolean[] {false, true}) {
        var order = new ArrayList<String>();
        var task =
            new RoguePredicateTask46(
                () -> {
                  order.add("eval");
                  return result;
                },
                () -> {
                  order.add("inverse");
                  return inverse;
                });
        task.begin();
        assertEquals(result != inverse ? SUCCESS : FAIL, task.state());
        assertEquals(List.of("eval", "inverse"), order);
      }
  }

  @Test
  void successfulRunOnceReturnsTheTerminalSnapshotAndResets() {
    var task = new RoguePredicateTask46(() -> true, () -> false);
    assertEquals(SUCCESS, RoguePredicateBranches46.runOnce(task, v -> fail()));
    assertEquals(READY, task.state());
  }

  @Test
  void failedRunOnceReturnsFailAndResetsWithoutWaitingDiagnostic() {
    var task = new RoguePredicateTask46(() -> false, () -> false);
    assertEquals(FAIL, RoguePredicateBranches46.runOnce(task, v -> fail()));
    assertEquals(READY, task.state());
  }

  @Test
  void repeatedBeginReadsCurrentEvaluationAndInverseEvenAfterCompletion() {
    var values = new boolean[] {true, false};
    var task = new RoguePredicateTask46(() -> values[0], () -> values[1]);
    task.begin();
    assertEquals(SUCCESS, task.state());
    values[1] = true;
    task.begin();
    assertEquals(FAIL, task.state());
    values[0] = false;
    task.begin();
    assertEquals(SUCCESS, task.state());
  }

  @Test
  void evaluationExceptionLeavesReadyAndDoesNotReadInverse() {
    var e = new IllegalStateException("eval");
    var task =
        new RoguePredicateTask46(
            () -> {
              throw e;
            },
            () -> {
              fail();
              return false;
            });
    assertSame(e, assertThrows(IllegalStateException.class, task::begin));
    assertEquals(READY, task.state());
  }

  @Test
  void inverseExceptionKeepsReadyAfterTheEvaluationEffects() {
    var calls = new ArrayList<String>();
    var e = new IllegalStateException("inverse");
    var task =
        new RoguePredicateTask46(
            () -> {
              calls.add("evaluated");
              return true;
            },
            () -> {
              throw e;
            });
    assertSame(e, assertThrows(IllegalStateException.class, task::begin));
    assertEquals(List.of("evaluated"), calls);
    assertEquals(READY, task.state());
  }

  @Test
  void repeatedEvaluationExceptionPreservesThePreviousSuccess() {
    var failure = new boolean[1];
    var task =
        new RoguePredicateTask46(
            () -> {
              if (failure[0]) throw new IllegalStateException("eval");
              return true;
            },
            () -> false);
    task.begin();
    failure[0] = true;
    assertThrows(IllegalStateException.class, task::begin);
    assertEquals(SUCCESS, task.state());
  }

  @Test
  void repeatedInverseExceptionPreservesThePreviousFailure() {
    var failure = new boolean[1];
    var task =
        new RoguePredicateTask46(
            () -> false,
            () -> {
              if (failure[0]) throw new IllegalStateException("inverse");
              return false;
            });
    task.begin();
    failure[0] = true;
    assertThrows(IllegalStateException.class, task::begin);
    assertEquals(FAIL, task.state());
  }

  @Test
  void resetChangesOnlyStateAndDoesNotReadEitherBinding() {
    var count = new int[1];
    var task =
        new RoguePredicateTask46(
            () -> {
              count[0]++;
              return false;
            },
            () -> {
              count[0]++;
              return false;
            });
    task.reset();
    assertEquals(READY, task.state());
    assertEquals(0, count[0]);
    task.begin();
    assertEquals(FAIL, task.state());
    task.reset();
    assertEquals(READY, task.state());
    assertEquals(2, count[0]);
  }

  @Test
  void disposeIsTheOriginalNoOpAndRetainsBindingsAndState() {
    var value = new boolean[] {true};
    var task = new RoguePredicateTask46(() -> value[0], () -> false);
    task.begin();
    task.dispose();
    task.dispose();
    assertEquals(SUCCESS, task.state());
    value[0] = false;
    task.begin();
    assertEquals(FAIL, task.state());
  }

  @Test
  void tickThrowsForEveryStateAndEveryDeltaWithoutReadingBindings() {
    var reads = new int[1];
    var task =
        new RoguePredicateTask46(
            () -> {
              reads[0]++;
              return true;
            },
            () -> {
              reads[0]++;
              return false;
            });
    for (int stage = 0; stage < 4; stage++) {
      if (stage == 1) task.begin();
      if (stage == 2) task.reset();
      if (stage == 3) task.dispose();
      int state = task.state();
      int before = reads[0];
      for (float delta : new float[] {0, .1f, -1, Float.NaN, Float.POSITIVE_INFINITY})
        assertThrows(UnsupportedOperationException.class, () -> task.tick(delta));
      assertEquals(state, task.state());
      assertEquals(before, reads[0]);
    }
  }

  @Test
  void missingBindingsAreRejectedBeforeAnyEvaluation() {
    assertThrows(IllegalArgumentException.class, () -> new RoguePredicateTask46(null, () -> false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoguePredicateTask46(
                () -> {
                  fail();
                  return false;
                },
                null));
  }

  @Test
  void defaultCreationCallbackExecutesItsOriginalAddForTeamOne() {
    var b = new Binding();
    b.entities.put("ParamEntity", new Object());
    var effects = new ArrayList<RogueModifierDefinitions46.Action>();
    var callback =
        RogueModifierDefinitions46.compileCallback(definition(""), 57, factory(b, effects));
    callback.begin();
    assertEquals(
        List.of(
            new RogueModifierDefinitions46.Add(
                "ParamEntity", "MAvatar_RogueBattleevent127_AimTarget_Sub")),
        effects);
    assertEquals(List.of("ParamEntity:65535", "team"), b.calls);
    assertEquals(SUCCESS, callback.state());
  }

  @Test
  void defaultCreationCallbackUsesAbsentFailureForOtherTeams() {
    var b = new Binding();
    b.team = 2;
    b.entities.put("ParamEntity", new Object());
    var effects = new ArrayList<RogueModifierDefinitions46.Action>();
    var callback =
        RogueModifierDefinitions46.compileCallback(definition(""), 57, factory(b, effects));
    callback.begin();
    assertTrue(effects.isEmpty());
    assertEquals(SUCCESS, callback.state());
    var branch = (RoguePredicateBranches46.BranchTask) callback.children().getFirst();
    assertNull(branch.selected());
  }

  @Test
  void defaultRefreshCallbackPreservesAnExplicitEmptySuccessfulBranch() {
    var b = new Binding();
    var entity = new Object();
    b.entities.put("ModifierOwnerEntity", entity);
    b.entities.put("CurrentAimAtTarget", entity);
    var effects = new ArrayList<RogueModifierDefinitions46.Action>();
    var callback =
        RogueModifierDefinitions46.compileCallback(definition("_Sub"), 173, factory(b, effects));
    callback.begin();
    assertTrue(effects.isEmpty());
    assertEquals(SUCCESS, callback.state());
    var branch = (RoguePredicateBranches46.BranchTask) callback.children().getFirst();
    assertNotNull(branch.selected());
    assertTrue(branch.selected().children().isEmpty());
  }

  @Test
  void defaultRefreshCallbackRemovesTheOriginalEffectAfterIdentityChanges() {
    var b = new Binding();
    b.entities.put("ModifierOwnerEntity", new String("same"));
    b.entities.put("CurrentAimAtTarget", new String("same"));
    var effects = new ArrayList<RogueModifierDefinitions46.Action>();
    var callback =
        RogueModifierDefinitions46.compileCallback(definition("_Sub"), 173, factory(b, effects));
    callback.begin();
    assertEquals(1, effects.size());
    var remove = (RogueModifierDefinitions46.Remove) effects.getFirst();
    assertEquals("ModifierOwnerEntity", remove.alias());
    assertEquals(List.of("ModifierOwnerEntity:15", "CurrentAimAtTarget:15"), b.calls);
    assertEquals(SUCCESS, callback.state());
  }

  @Test
  void defaultRefreshCallbackShortCircuitsMissingFirstTargetThenRunsFailureAction() {
    var b = new Binding();
    var effects = new ArrayList<RogueModifierDefinitions46.Action>();
    var callback =
        RogueModifierDefinitions46.compileCallback(definition("_Sub"), 173, factory(b, effects));
    callback.begin();
    assertEquals(List.of("ModifierOwnerEntity:15"), b.calls);
    assertEquals(1, effects.size());
  }

  @Test
  void defaultCompilerDoesNotReadTargetsDuringTaskAllocation() {
    var b = new Binding();
    var effects = new ArrayList<RogueModifierDefinitions46.Action>();
    RogueModifierDefinitions46.compileCallback(definition(""), 57, factory(b, effects));
    assertTrue(b.calls.isEmpty());
    assertTrue(effects.isEmpty());
  }

  @Test
  void defaultCompilerStillRequiresARealWorldBindingForSourceBranches() {
    var source = definition("_Sub").callback(173).getFirst();
    assertThrows(
        IllegalArgumentException.class,
        () -> RoguePredicateBranches46.factory(null, a -> new Leaf(), v -> {}).create(source));
  }

  @Test
  void compilerRequiresBothBranchProgramsBeforeItCanEvaluateThePredicate() {
    var b = new Binding();
    b.team = 2;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RogueModifierDefinitions46.compileCallback(
                definition(""), 57, RoguePredicateBranches46.factory(b, null, v -> {})));
    assertTrue(b.calls.isEmpty());
  }

  @Test
  void ordinaryLeafDoesNotRequireAnUnusedPredicateWorldBinding() {
    var leaf = new Leaf();
    var factory = RoguePredicateBranches46.factory(null, a -> leaf, null);
    assertSame(leaf, factory.create(new RogueModifierDefinitions46.Add("a", "name")));
    assertEquals(0, leaf.begins);
  }

  @Test
  void compilerRejectsMissingDiagnosticsBeforeAllocatingBranchActions() {
    var b = new Binding();
    var allocations = new int[1];
    var factory =
        RoguePredicateBranches46.factory(
            b,
            a -> {
              allocations[0]++;
              return new Leaf();
            },
            null);
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.create(definition("").callback(57).getFirst()));
    assertEquals(0, allocations[0]);
  }

  @Test
  void defaultEvaluationExceptionKeepsParentReadyAndLeavesActionsUnexecuted() {
    var e = new IllegalStateException("world");
    var target =
        new RoguePredicateBranches46.Targets() {
          public Object first(String a, int m) {
            throw e;
          }

          public int team(Object o) {
            fail();
            return 0;
          }
        };
    var leaf = new Leaf();
    var f = RoguePredicateBranches46.factory(target, a -> leaf, v -> fail());
    var branch = f.create(definition("").callback(57).getFirst());
    assertSame(e, assertThrows(IllegalStateException.class, branch::begin));
    assertEquals(READY, branch.state());
    assertEquals(0, leaf.begins);
  }

  @Test
  void defaultCompilerRecursivelyUsesConcretePredicates() {
    var b = new Binding();
    b.entities.put("a", new Object());
    var effects = new ArrayList<RogueModifierDefinitions46.Action>();
    var inner =
        new RogueModifierDefinitions46.Branch(
            new RogueModifierDefinitions46.ByTeam("a", 1),
            List.of(new RogueModifierDefinitions46.Add("a", "nested")),
            null);
    var outer =
        new RogueModifierDefinitions46.Branch(
            new RogueModifierDefinitions46.ByTeam("a", 1), List.of(inner), null);
    var task = factory(b, effects).create(outer);
    task.begin();
    assertEquals(List.of(new RogueModifierDefinitions46.Add("a", "nested")), effects);
    assertEquals(SUCCESS, task.state());
  }

  @Test
  void runOnceCallbackCanReactToTheNextLiveTargetRefresh() {
    var b = new Binding();
    var entity = new Object();
    b.entities.put("ModifierOwnerEntity", entity);
    b.entities.put("CurrentAimAtTarget", entity);
    var effects = new ArrayList<RogueModifierDefinitions46.Action>();
    var sequence =
        RogueModifierDefinitions46.compileCallback(definition("_Sub"), 173, factory(b, effects));
    assertEquals(SUCCESS, sequence.runOnce(v -> fail()));
    assertEquals(READY, sequence.state());
    assertTrue(effects.isEmpty());
    b.entities.put("CurrentAimAtTarget", new Object());
    assertEquals(SUCCESS, sequence.runOnce(v -> fail()));
    assertEquals(1, effects.size());
    assertEquals(READY, sequence.state());
  }

  @Test
  void waitingLeafTicksWithoutCallingTheUnsupportedPredicateTick() {
    var b = new Binding();
    b.entities.put("ParamEntity", new Object());
    var leaf = new Leaf();
    leaf.result = EXECUTING;
    var sequence =
        RogueModifierDefinitions46.compileCallback(
            definition(""), 57, RoguePredicateBranches46.factory(b, a -> leaf, v -> fail()));
    sequence.begin();
    assertEquals(EXECUTING, sequence.state());
    int reads = b.calls.size();
    sequence.tick(.1f);
    assertEquals(reads, b.calls.size());
    assertTrue(leaf.ticks > 0);
    assertEquals(EXECUTING, sequence.state());
  }

  @Test
  void directFailedPredicateStopsAnOrdinarySequenceBeforeLaterActions() {
    var task = new RoguePredicateTask46(() -> false, () -> false);
    var leaf = new Leaf();
    var sequence = new RogueTaskSequence46.Sequence(List.of(task, leaf), false);
    sequence.begin();
    assertEquals(FAIL, sequence.state());
    assertEquals(FAIL, task.state());
    assertEquals(0, leaf.begins);
  }

  @Test
  void directSuccessfulPredicateCompletesBeforeLaterActions() {
    var task = new RoguePredicateTask46(() -> true, () -> false);
    var leaf = new Leaf();
    var sequence = new RogueTaskSequence46.Sequence(List.of(task, leaf), false);
    sequence.begin();
    assertEquals(SUCCESS, sequence.state());
    assertEquals(1, leaf.begins);
    assertEquals(SUCCESS, task.state());
  }
}
