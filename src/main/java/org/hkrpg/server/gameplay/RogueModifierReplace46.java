package org.hkrpg.server.gameplay;

import java.util.OptionalInt;

/** Ordinary replacement order; actual override lookup, injections and virtual events stay bound. */
public final class RogueModifierReplace46 {
  private RogueModifierReplace46() {}

  public static final class State {
    public final RogueModifierValueBinding46.State values;
    public int executingSequenceCount, layerAddWhenStack;
    public Object delayReplaceAbility,
        initialFloatInjections,
        initialStringInjections,
        dynamicStrings;
    public OptionalInt forceLayer = OptionalInt.empty();

    public State(RogueModifierValueBinding46.State values) {
      if (values == null) throw new IllegalArgumentException("Missing explicit replacement values");
      this.values = values;
    }
  }

  public static final class BaseParameters {
    public Object floatInjections, stringInjections;
  }

  public static final class Parameters {
    public OptionalInt maximumLayerOverride = OptionalInt.empty(), forceLayer = OptionalInt.empty();
    public boolean immediately;
    public BaseParameters base;
  }

  public interface World {
    Object acquireNumericOverrides(State state);

    int calculateMaxLayer(State state, Object overrides, Parameters parameters);

    void valueChanged(State state, int type);

    int calculateLayerAdd(State state, Object overrides, Parameters parameters);

    void releaseNumericOverrides(Object overrides);

    boolean containsBehaviorFlag(State state, int flag);

    Object taskContext(State state);

    void replaceContextAbility(Object context, Object ability);

    void afterAbilityVirtual(State state);

    void setupFloatInjections(State state, Object injections);

    void setupStringInjections(State state, Object injections);

    void afterReplaceVirtual(State state);
  }

  public static void replace(State state, Object ability, Parameters parameters, World world) {
    if (state == null || world == null)
      throw new IllegalArgumentException("Missing explicit replacement binding");
    Object overrides = world.acquireNumericOverrides(state);
    if (parameters == null) throw new IllegalArgumentException("Missing replacement parameters");
    if (parameters.maximumLayerOverride == null)
      throw new IllegalArgumentException("Missing maximum-layer presence binding");
    if (parameters.maximumLayerOverride.isPresent()) {
      state.values.baseMaxLayer = world.calculateMaxLayer(state, overrides, parameters);
      world.valueChanged(state, 2);
    }
    state.layerAddWhenStack = world.calculateLayerAdd(state, overrides, parameters);
    if (overrides != null) world.releaseNumericOverrides(overrides);
    if (parameters.forceLayer == null)
      throw new IllegalArgumentException("Missing nullable forced-layer binding");
    state.forceLayer = parameters.forceLayer;
    state.values.modifier.lifetime.immediately =
        parameters.immediately || world.containsBehaviorFlag(state, 7);
    replaceBase(state, ability, parameters.base, world);
  }

  private static void replaceBase(
      State state, Object ability, BaseParameters parameters, World world) {
    if (state.executingSequenceCount != 0) {
      state.delayReplaceAbility = ability;
    } else {
      Object context = world.taskContext(state);
      if (context == null) throw new IllegalArgumentException("Missing replacement task context");
      world.replaceContextAbility(context, ability);
      world.afterAbilityVirtual(state);
    }
    if (parameters == null)
      throw new IllegalArgumentException("Missing base replacement parameters");
    Object floats = parameters.floatInjections;
    if (floats != null) {
      state.initialFloatInjections = floats;
      world.setupFloatInjections(state, floats);
    }
    Object strings = parameters.stringInjections;
    if (strings != null) {
      state.initialStringInjections = strings;
      world.setupStringInjections(state, strings);
    }
    world.afterReplaceVirtual(state);
  }
}
