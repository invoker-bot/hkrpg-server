package org.hkrpg.server.gameplay;

/** Ordinary turn-end and destruction cleanup order; effect/component/disposal bodies stay bound. */
public final class RogueModifierCleanup46 {
  private RogueModifierCleanup46() {}

  public static final class Config {
    public String effectPriority;
    public Object behaviorFlags;
  }

  public static final class State {
    public final RogueModifierSequences46.State sequence;
    public final RogueModifierSearch46.Candidate candidate;
    public boolean pendingDispose;
    public Object effects, mutexEffects, ownerAbilityComponent;
    public int hitEffectOverride = -1, beforeBuffUIDelayCount;
    public Config config;

    public State(
        RogueModifierSequences46.State sequence, RogueModifierSearch46.Candidate candidate) {
      if (sequence == null || candidate == null)
        throw new IllegalArgumentException("Missing shared cleanup state");
      this.sequence = sequence;
      this.candidate = candidate;
    }
  }

  public interface World extends RogueModifierSequences46.World {
    void unstack(State state);

    void detachAllEffects(State state, boolean immediate);

    void removeOnHitEffectOverride(Object component, int override, int beforeBuffUIDelayCount);

    void updateModifiersEffectHideByPriority(Object component);

    void markBehaviorFlagVisualDirty(Object component);

    void disposeBody(State state);
  }

  public static boolean isExecutingIgnoreTicking(State state) {
    requireState(state);
    return state.sequence.replacement.executingSequenceCount > state.sequence.tickingSequenceCount;
  }

  public static void requestDispose(State state, World world) {
    requireState(state);
    if (isExecutingIgnoreTicking(state)) {
      state.pendingDispose = true;
      return;
    }
    world(world).disposeBody(state);
  }

  public static void endTurnSequence(State state, World world) {
    requireState(state);
    RogueModifierSequences46.endAndFlush(state.sequence, world);
    if (isExecutingIgnoreTicking(state)) return;
    if (state.candidate.state == 2) {
      world(world).unstack(state);
      cleanupAfterDestroy(state, world);
    }
    if (state.pendingDispose) {
      state.pendingDispose = false;
      requestDispose(state, world);
    }
  }

  public static void cleanupAfterDestroy(State state, World world) {
    requireState(state);
    world(world).detachAllEffects(state, true);
    state.effects = null;
    state.mutexEffects = null;
    if (state.hitEffectOverride >= 0) {
      world.removeOnHitEffectOverride(
          owner(state), state.hitEffectOverride, state.beforeBuffUIDelayCount);
      state.hitEffectOverride = -1;
    }
    Config priorityConfig = state.config;
    if (priorityConfig != null
        && priorityConfig.effectPriority != null
        && !priorityConfig.effectPriority.isEmpty()) {
      world.updateModifiersEffectHideByPriority(owner(state));
    }
    Config behaviorConfig = state.config;
    if (behaviorConfig == null)
      throw new IllegalArgumentException("Missing cleanup modifier config");
    if (behaviorConfig.behaviorFlags != null) world.markBehaviorFlagVisualDirty(owner(state));
  }

  private static Object owner(State state) {
    if (state.ownerAbilityComponent == null)
      throw new IllegalArgumentException("Missing cleanup owner ability component");
    return state.ownerAbilityComponent;
  }

  private static World world(World world) {
    if (world == null) throw new IllegalArgumentException("Missing cleanup world binding");
    return world;
  }

  private static void requireState(State state) {
    if (state == null) throw new IllegalArgumentException("Missing modifier cleanup state");
  }
}
