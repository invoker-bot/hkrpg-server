package org.hkrpg.server.gameplay;

import java.util.ArrayList;
import java.util.List;

/** Finite current-list withdrawal with shared stack-event state; native pools stay external. */
public final class RogueModifierStackedEffects46 {
  private RogueModifierStackedEffects46() {}

  public record Weakness(Object abilityComponent, int operation, int stackIndex) {}

  public record AdditionalBp(Object teamComponent, int registrationId) {}

  /** Explicit project container; this does not install the native pooled-list factory. */
  public static class BoostContexts extends ArrayList<Object> {}

  public static final class State {
    public final RogueModifierStackEvent46.State stack;
    public List<Weakness> weaknesses;
    public BoostContexts boostContexts;

    public State(RogueModifierStackEvent46.State stack) {
      if (stack == null) throw new IllegalArgumentException("Missing shared stack event state");
      this.stack = stack;
    }
  }

  public interface World {
    Object weaknessComponent(Object abilityComponent);

    Object abilityEntity(Object abilityComponent);

    void unstackWeakness(Object weakness, Object entity, int operation, int stackIndex);

    Object teamBoostPointModule(Object teamComponent);

    void removeAdditionalTeamBp(Object manager, int registrationId);

    void removeBoostCallback(Object manager, RogueModifierReplace46.State modifier);
  }

  public static void popWeakness(State state, World world) {
    state(state);
    List<Weakness> list = required(state.weaknesses, "weakness list");
    int index = 0;
    while (true) {
      int size = list.size();
      list = required(state.weaknesses, "current weakness list");
      if (index >= size) {
        list.clear();
        return;
      }
      bounded(index);
      var entry = required(list.get(index), "explicit weakness entry");
      Object component = required(entry.abilityComponent(), "weakness ability component");
      var bound = required(world, "weakness removal binding");
      Object weakness = required(bound.weaknessComponent(component), "character weakness");
      Object entity = bound.abilityEntity(component);
      bound.unstackWeakness(weakness, entity, entry.operation(), entry.stackIndex());
      index++;
      list = required(state.weaknesses, "current weakness list");
    }
  }

  public static void popAdditionalTeamBp(State state, World world) {
    state(state);
    List<?> list = state.stack.stackedAdditionalTeamBp;
    if (list == null) return;
    int index = 0;
    while (true) {
      int size = list.size();
      list = required(state.stack.stackedAdditionalTeamBp, "current additional team BP list");
      if (index >= size) {
        list.clear();
        return;
      }
      bounded(index);
      Object raw = list.get(index);
      if (!(raw instanceof AdditionalBp entry))
        throw new IllegalArgumentException("Unmatched explicit additional team BP entry");
      Object component = entry.teamComponent();
      if (component != null) {
        var bound = required(world, "additional team BP removal binding");
        Object manager = required(bound.teamBoostPointModule(component), "team boost point module");
        bound.removeAdditionalTeamBp(manager, entry.registrationId());
      }
      index++;
      list = required(state.stack.stackedAdditionalTeamBp, "current additional team BP list");
    }
  }

  public static void popBoostCallbacks(State state, World world) {
    state(state);
    BoostContexts list = state.boostContexts;
    if (list == null) return;
    int index = 0;
    while (true) {
      int size = list.size();
      list = required(state.boostContexts, "current boost callback list");
      if (index >= size) {
        list.clear();
        required(state.boostContexts, "current boost callback trim list").trimToSize();
        return;
      }
      bounded(index);
      Object component = list.get(index);
      if (component != null) {
        var bound = required(world, "boost callback removal binding");
        Object manager = required(bound.teamBoostPointModule(component), "team boost point module");
        bound.removeBoostCallback(manager, state.stack.replacement);
      }
      index++;
      list = required(state.boostContexts, "current boost callback list");
    }
  }

  private static void bounded(int index) {
    if (index >= 255) throw new IllegalArgumentException("Excessive finite stacked effects");
  }

  private static void state(State state) {
    required(state, "stacked effect state");
  }

  private static <T> T required(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
