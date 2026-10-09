package org.hkrpg.server.gameplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Finite original definitions and callback selection; effect instances remain external. */
public final class RogueModifierDefinitions46 {
  private RogueModifierDefinitions46() {}

  public record Defaults(
      int count,
      int stacking,
      int lifeTime,
      int maxLayer,
      long layerAddWhenStackRaw,
      int workingTurn,
      int lifeStepMoment,
      boolean applyBehaviorFlagBindEffects) {}

  public static final Defaults DEFAULTS = new Defaults(-1, 1, -1, 99, 0L, 1, 2, true);

  public sealed interface Action permits Add, Trigger, Remove, Branch {
    int taskId();
  }

  public record Add(String alias, String literalName) implements Action {
    public Add {
      text(alias);
      text(literalName);
    }

    public int taskId() {
      return 50;
    }
  }

  public record Trigger(
      String alias, String effectPath, boolean forceSimulateImmediately, Float minMutexTime)
      implements Action {
    public Trigger {
      text(alias);
      text(effectPath);
      if (minMutexTime != null && !Float.isFinite(minMutexTime))
        throw new IllegalArgumentException("Invalid finite effect time");
    }

    public int taskId() {
      return 3606;
    }
  }

  public record Remove(String alias, String effectPath, boolean forceImmediateFadeOut)
      implements Action {
    public Remove {
      text(alias);
      text(effectPath);
    }

    public int taskId() {
      return 2171;
    }
  }

  /** These are source descriptors. The bound action factory owns predicate evaluation. */
  public sealed interface Condition permits ByTeam, CompareTargets {}

  public record ByTeam(String alias, int team) implements Condition {
    public ByTeam {
      text(alias);
      if (team < 0 || team > 5) throw new IllegalArgumentException("Invalid explicit source team");
    }
  }

  public record CompareTargets(String alias, String compareAlias) implements Condition {
    public CompareTargets {
      text(alias);
      text(compareAlias);
    }
  }

  public record Branch(Condition condition, List<Action> success, List<Action> failure)
      implements Action {
    public Branch {
      if (condition == null)
        throw new IllegalArgumentException("Missing explicit predicate descriptor");
      success = actions(success);
      failure = actions(failure);
    }

    public int taskId() {
      return 1993;
    }
  }

  public record Callback(int event, List<Action> program) {
    public Callback {
      if (event < 0) throw new IllegalArgumentException("Invalid explicit callback event");
      program = actions(program);
    }
  }

  public record Definition(Defaults defaults, List<Callback> callbacks) {
    public Definition {
      if (defaults == null)
        throw new IllegalArgumentException("Missing explicit modifier defaults");
      if (callbacks != null) {
        if (callbacks.size() > 255)
          throw new IllegalArgumentException("Oversized finite callbacks");
        callbacks = List.copyOf(callbacks);
      }
    }

    // Native configuration methods return the first matching entry, without merging duplicates.
    public Callback callbackInfo(int event) {
      if (callbacks != null) for (var c : callbacks) if (c.event() == event) return c;
      return null;
    }

    public List<Action> callback(int event) {
      var c = callbackInfo(event);
      return c == null ? null : c.program();
    }

    public boolean hasCallback(int event) {
      return callbackInfo(event) != null;
    }
  }

  private static void text(String s) {
    if (s == null || s.isEmpty())
      throw new IllegalArgumentException("Missing explicit source text");
  }

  private static List<Action> actions(List<Action> value) {
    if (value == null) return null;
    if (value.size() > 255) throw new IllegalArgumentException("Oversized finite callback program");
    return List.copyOf(value);
  }

  public static <T> T resolve(
      String name,
      Map<String, T> abilityModifiers,
      Function<String, T> global,
      Consumer<String> missingDiagnostic) {
    text(name);
    if (global == null || missingDiagnostic == null)
      throw new IllegalArgumentException("Missing explicit modifier lookup bindings");
    T result = abilityModifiers == null ? null : abilityModifiers.get(name);
    if (result != null) return result;
    result = global.apply(name);
    if (result == null) missingDiagnostic.accept(name);
    return result;
  }

  @FunctionalInterface
  public interface Factory {
    RogueTaskSequence46.Task create(Action command);
  }

  /** Selects a source callback, preserving absence; all nonempty actions need a real bound task. */
  public static RogueTaskSequence46.Sequence compileCallback(
      Definition definition, int event, Factory factory) {
    if (definition == null)
      throw new IllegalArgumentException("Missing explicit modifier definition");
    var program = definition.callback(event);
    if (program == null) return null;
    if (!program.isEmpty() && factory == null)
      throw new IllegalArgumentException("Missing explicit callback action factory");
    var tasks = new ArrayList<RogueTaskSequence46.Task>();
    for (var action : program) {
      var task = factory.create(action);
      if (task == null)
        throw new IllegalArgumentException("Callback action factory returned no task");
      tasks.add(task);
    }
    return new RogueTaskSequence46.Sequence(tasks, false);
  }

  public static Map<String, Definition> classicLocal(int event) {
    RogueResonanceClassicEvents46.find(event)
        .orElseThrow(() -> new IllegalArgumentException("Unknown original classic event"));
    String name =
        switch (event) {
          case 41001 -> "StageAbility_612020_Modifier";
          case 41002 -> "StageAbility_612220_Modifier";
          case 41003 -> "StageAbility_612320_Modifier";
          case 41004 -> "StageAbility_612420_Modifier";
          case 41005 -> "StageAbility_612520_Modifier";
          default -> null;
        };
    return name == null ? Map.of() : Map.of(name, new Definition(DEFAULTS, null));
  }

  public static Map<String, Definition> breedingGlobal() {
    String prefix = "MAvatar_RogueBattleevent127_AimTarget";
    String path = "Effects/Eff_Prefab/Eff_BattleEvent/Eff_Rogue_Breeding/";
    String target = path + "Eff_BattleEvent_Rogue_Breeding_02.prefab";
    String main = path + "Eff_BattleEvent_Rogue_Breeding_01_01.prefab";
    var defs = new LinkedHashMap<String, Definition>();
    defs.put(
        prefix,
        new Definition(
            DEFAULTS,
            List.of(
                new Callback(
                    57,
                    List.of(
                        new Branch(
                            new ByTeam("ParamEntity", 1),
                            List.of(new Add("ParamEntity", prefix + "_Sub")),
                            null))))));
    defs.put(
        prefix + "_Main",
        new Definition(
            DEFAULTS,
            List.of(
                new Callback(
                    172, List.of(new Trigger("CurrentAimAtTarget", target, false, null))))));
    defs.put(
        prefix + "_Main2",
        new Definition(
            DEFAULTS,
            List.of(
                new Callback(95, List.of(new Remove("AllLightTeam", main, false))),
                new Callback(172, List.of(new Trigger("CurrentAimAtTarget", main, false, -1f))))));
    defs.put(
        prefix + "_Sub",
        new Definition(
            DEFAULTS,
            List.of(
                new Callback(
                    173,
                    List.of(
                        new Branch(
                            new CompareTargets("ModifierOwnerEntity", "CurrentAimAtTarget"),
                            List.of(),
                            List.of(new Remove("ModifierOwnerEntity", target, false))))))));
    return java.util.Collections.unmodifiableMap(defs);
  }
}
