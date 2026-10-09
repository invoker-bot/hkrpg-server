package org.hkrpg.server.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Finite write tasks; numeric contexts, target properties and selected lookups are explicit. */
public final class RogueResonanceWrite46 {
  private RogueResonanceWrite46() {}

  public static final class ExpressionTask {
    private final int key;
    private final RogueResonanceExpression46.Program value;
    private volatile int state = 0x7777;

    public ExpressionTask(int variant, String key, RogueResonanceExpression46.Program value) {
      if (variant != 2791 || value == null) {
        throw new IllegalArgumentException("Unmatched expression write task");
      }
      this.key = RogueResonanceVariables46.nameKey(key);
      this.value = value;
    }

    public int state() {
      return state;
    }

    public int execute(
        Map<Integer, Long> values,
        List<RogueResonanceVariables46> selectedTargets,
        boolean casterInCharmAction) {
      // Native 2791 marks its lifecycle complete at entry, before the charm guard or evaluation.
      // This state alone does not establish that the task's effect succeeded.
      state = 0x9999;
      if (casterInCharmAction) return 0;
      long raw = value.evaluate(values);
      return writeTargets(raw, selectedTargets);
    }

    public int executeInContext(
        RogueResonanceContext46 context,
        List<RogueResonanceVariables46> selectedTargets,
        boolean casterInCharmAction) {
      return executeInContext(
          (RogueResonanceLookup46) context, selectedTargets, casterInCharmAction);
    }

    public int executeInContext(
        RogueResonanceLookup46 context,
        List<RogueResonanceVariables46> selectedTargets,
        boolean casterInCharmAction) {
      state = 0x9999;
      if (casterInCharmAction) return 0;
      if (context == null) throw new IllegalArgumentException("Missing resonance task context");
      return writeTargets(value.evaluateWithResolver(context::read), selectedTargets);
    }

    public int executeForEntities(
        RogueResonanceScopedLookup46 context,
        List<RogueResonanceScopedLookup46.Entity> selectedTargets,
        int scope,
        boolean casterInCharmAction) {
      state = 0x9999;
      if (casterInCharmAction) return 0;
      if (context == null) throw new IllegalArgumentException("Missing resonance task context");
      // Native evaluates once before target iteration; a later selection failure
      // preserves earlier writes. Do not eagerly resolve all target containers.
      long raw = value.evaluateWithResolver(context::read);
      if (selectedTargets == null || selectedTargets.size() > 255) {
        throw new IllegalArgumentException("Invalid finite resonance entity selection");
      }
      var targets = new ArrayList<>(selectedTargets);
      if (targets.size() > 255)
        throw new IllegalArgumentException("Invalid finite resonance entity selection");
      int writes = 0;
      for (var target : targets)
        if (context.selectForEntity(target, scope, key).write(key, raw)) writes++;
      return writes;
    }

    private int writeTargets(long raw, List<RogueResonanceVariables46> selectedTargets) {
      if (selectedTargets == null || selectedTargets.size() > 255) {
        throw new IllegalArgumentException("Invalid finite resonance target selection");
      }
      List<RogueResonanceVariables46> targets;
      try {
        targets = List.copyOf(selectedTargets);
      } catch (NullPointerException malformed) {
        throw new IllegalArgumentException("Missing selected resonance target lookup", malformed);
      }
      if (targets.size() > 255) {
        throw new IllegalArgumentException("Invalid finite resonance target selection");
      }
      int writes = 0;
      for (var target : targets) if (target.write(key, raw)) writes++;
      return writes;
    }
  }

  public static final class PropertyTask {
    private final int key;
    private volatile int state = 0x7777;

    public PropertyTask(int variant, String key, int property) {
      if (variant != 2855 || property != 12) {
        throw new IllegalArgumentException("Unmatched property write task");
      }
      this.key = RogueResonanceVariables46.nameKey(key);
    }

    public int state() {
      return state;
    }

    public boolean execute(
        RogueResonanceVariables46 selectedLookup, List<Map<Integer, Long>> targets) {
      // Native 2855 also assigns this state before selecting and reading its first target.
      state = 0x9999;
      if (selectedLookup == null || targets == null || targets.isEmpty() || targets.size() > 255) {
        throw new IllegalArgumentException("Missing finite resonance property context");
      }
      var first = targets.getFirst();
      if (first == null) throw new IllegalArgumentException("Missing resonance property target");
      Map<Integer, Long> properties;
      try {
        properties = Map.copyOf(first);
      } catch (NullPointerException malformed) {
        throw new IllegalArgumentException("Invalid resonance property context", malformed);
      }
      Long raw = properties.get(12);
      if (raw == null || (raw & 1) != 0) {
        throw new IllegalArgumentException("Unmatched standard CurrentSP property");
      }
      return selectedLookup.write(key, raw);
    }

    public boolean executeInContext(
        RogueResonanceContext46 context, int scope, List<Map<Integer, Long>> targets) {
      return executeInContext((RogueResonanceLookup46) context, scope, targets);
    }

    public boolean executeInContext(
        RogueResonanceLookup46 context, int scope, List<Map<Integer, Long>> targets) {
      state = 0x9999;
      if (context == null) throw new IllegalArgumentException("Missing resonance task context");
      return execute(context.select(scope, key), targets);
    }
  }
}
