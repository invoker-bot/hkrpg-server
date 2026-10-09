package org.hkrpg.server.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.hkrpg.server.gameplay.RogueModifierDefinitions46.*;
import org.hkrpg.server.gameplay.RogueTaskSequence46.*;

/**
 * Two original predicate evaluations and ordinary branch execution with explicit world bindings.
 */
public final class RoguePredicateBranches46 {
  private RoguePredicateBranches46() {}

  public static final int COMPARE_ALIVE_MASK = 15;
  public static final int TEAM_ALIVE_MASK = 65535;

  public interface Targets {
    Object first(String alias, int aliveMask);

    int team(Object entity);
  }

  public static boolean evaluate(Condition condition, Targets targets) {
    if (condition == null || targets == null)
      throw new IllegalArgumentException("Missing explicit predicate bindings");
    if (condition instanceof ByTeam c) {
      var entity = targets.first(c.alias(), TEAM_ALIVE_MASK);
      return entity != null && targets.team(entity) == c.team();
    }
    var c = (CompareTargets) condition;
    return compare(c.alias(), c.compareAlias(), COMPARE_ALIVE_MASK, targets);
  }

  public static boolean compare(String alias, String compareAlias, int mask, Targets targets) {
    if (alias == null
        || alias.isEmpty()
        || compareAlias == null
        || compareAlias.isEmpty()
        || mask < 0
        || mask > 65535
        || targets == null)
      throw new IllegalArgumentException("Invalid explicit predicate target binding");
    var first = targets.first(alias, mask);
    return first != null && first == targets.first(compareAlias, mask);
  }

  public static final class BranchTask implements Task {
    private final boolean predicateConfigPresent;
    private Task predicate;
    private Sequence success;
    private Sequence failure;
    private Sequence selected;
    private final Consumer<Integer> waitingDiagnostic;
    private int state = RogueTaskSequence46.READY;

    public BranchTask(
        boolean predicateConfigPresent,
        Task predicate,
        Sequence success,
        Sequence failure,
        Consumer<Integer> waitingDiagnostic) {
      if (waitingDiagnostic == null)
        throw new IllegalArgumentException("Missing explicit predicate waiting diagnostic");
      this.predicateConfigPresent = predicateConfigPresent;
      this.predicate = predicate;
      this.success = success;
      this.failure = failure;
      this.waitingDiagnostic = waitingDiagnostic;
    }

    public int state() {
      return state;
    }

    public Sequence selected() {
      return selected;
    }

    public void begin() {
      if (predicateConfigPresent)
        selected =
            runOnce(predicate, waitingDiagnostic) == RogueTaskSequence46.SUCCESS
                ? success
                : failure;
      if (selected == null) {
        state = RogueTaskSequence46.SUCCESS;
        return;
      }
      selected.reset();
      selected.begin();
      sync();
    }

    public void tick(float delta) {
      if (state != RogueTaskSequence46.EXECUTING) return;
      if (!Float.isFinite(delta) || delta < 0)
        throw new IllegalArgumentException("Invalid finite branch delta");
      if (selected == null) throw new IllegalStateException("Missing executing branch sequence");
      if (selected.state() == RogueTaskSequence46.EXECUTING) selected.tick(delta);
      sync();
    }

    private void sync() {
      state =
          selected != null && selected.state() == RogueTaskSequence46.EXECUTING
              ? RogueTaskSequence46.EXECUTING
              : RogueTaskSequence46.SUCCESS;
    }

    public void reset() {
      if (selected != null) selected.reset();
      selected = null;
      state = RogueTaskSequence46.READY;
    }

    public void dispose() {
      if (success != null) {
        success.dispose();
        success = null;
      }
      if (failure != null) {
        failure.dispose();
        failure = null;
      }
      if (predicate != null) {
        predicate.dispose();
        predicate = null;
      }
    }
  }

  /** No finally: native reset is only reached after begin and any waiting diagnostic return. */
  public static int runOnce(Task predicate, Consumer<Integer> diagnostic) {
    if (diagnostic == null)
      throw new IllegalArgumentException("Missing explicit predicate waiting diagnostic");
    if (predicate == null) return RogueTaskSequence46.FAIL;
    predicate.begin();
    int result = predicate.state();
    if (result == RogueTaskSequence46.EXECUTING) diagnostic.accept(result);
    predicate.reset();
    return result;
  }

  @FunctionalInterface
  public interface PredicateFactory {
    // Generic predicate task state, inversion, plugins and disposal belong to the bound factory.
    Task create(Condition condition, BooleanSupplier originalEvaluation);
  }

  /**
   * Both matched source callbacks omit Inverse; their shared predicate lifecycle is now concrete.
   */
  public static RogueModifierDefinitions46.Factory factory(
      Targets targets, RogueModifierDefinitions46.Factory leaves, Consumer<Integer> diagnostic) {
    return factory(
        targets,
        leaves,
        (condition, evaluation) -> new RoguePredicateTask46(evaluation, () -> false),
        diagnostic);
  }

  /**
   * Recursively compiles source branches; all actual leaf actions and predicate tasks are explicit.
   */
  public static RogueModifierDefinitions46.Factory factory(
      Targets targets,
      RogueModifierDefinitions46.Factory leaves,
      PredicateFactory predicates,
      Consumer<Integer> diagnostic) {
    return action -> {
      if (action == null) throw new IllegalArgumentException("Missing explicit source action");
      if (!(action instanceof Branch branch)) {
        if (leaves == null)
          throw new IllegalArgumentException("Missing explicit leaf action factory");
        return require(leaves.create(action));
      }
      if (targets == null || predicates == null || diagnostic == null)
        throw new IllegalArgumentException("Missing explicit source branch bindings");
      var nested = factory(targets, leaves, predicates, diagnostic);
      var success = compile(branch.success(), nested);
      var failure = compile(branch.failure(), nested);
      var predicate =
          require(
              predicates.create(branch.condition(), () -> evaluate(branch.condition(), targets)));
      return new BranchTask(true, predicate, success, failure, diagnostic);
    };
  }

  private static Task require(Task task) {
    if (task == null)
      throw new IllegalArgumentException("Explicit action or predicate factory returned no task");
    return task;
  }

  private static Sequence compile(
      List<Action> actions, RogueModifierDefinitions46.Factory factory) {
    if (actions == null) return null;
    var tasks = new ArrayList<Task>();
    for (var action : actions) tasks.add(require(factory.create(action)));
    return new Sequence(tasks, false);
  }
}
