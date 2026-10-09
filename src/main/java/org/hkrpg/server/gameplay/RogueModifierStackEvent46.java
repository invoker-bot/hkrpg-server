package org.hkrpg.server.gameplay;

import java.util.Deque;
import java.util.List;

/**
 * Native stack-event order with shared layer values; real property, owner and world bodies stay
 * bound.
 */
public final class RogueModifierStackEvent46 {
  private RogueModifierStackEvent46() {}

  public static final class Arguments {
    public long requestedLayerAdd, actualLayerChange;
  }

  public record Config(boolean applyBehaviorFlagBindEffects) {}

  public static final class State {
    public final RogueModifierReplace46.State replacement;
    public Deque<Arguments> parameterFrames;
    public List<?> stackedAdditionalTeamBp;
    public Config config;
    public int shelvedAdditionalTeamBp, maximumReentryVersion, currentReentryVersion;
    public boolean silenceAdd;

    public State(RogueModifierReplace46.State replacement) {
      if (replacement == null)
        throw new IllegalArgumentException("Missing explicit stack replacement state");
      this.replacement = replacement;
    }
  }

  public interface World {
    void valueChanged(State state, int type);

    void popStackedProperties(State state);

    void popStackedAdditionalTeamBp(State state);

    void popBoostPointCallbackDecorate(State state);

    void bindBehaviorFlagEffects(State state);

    void tryBehaviorFlagEvent(State state, boolean first, boolean second);

    void executeEvent(State state, int event);

    void postOwnerStackEvents(State state);

    void postAdditionalTeamBpStack(State state, boolean value);
  }

  /** Exact finite integer encoding used by stack arguments, including widened layer differences. */
  public static long integerRaw(long value) {
    if (value < -4294967295L || value > 4294967295L)
      throw new IllegalArgumentException("Unmatched finite stack integer range");
    return Math.abs(value) < 0x40000000L ? value << 33 : (value << 26) | 1L;
  }

  public static void onStack(State state, World world) {
    if (state == null || world == null)
      throw new IllegalArgumentException("Missing explicit stack event binding");
    var replacement = state.replacement;
    var args = new Arguments();
    if (replacement.forceLayer == null)
      throw new IllegalArgumentException("Missing forced-layer presence binding");
    args.requestedLayerAdd =
        integerRaw(replacement.forceLayer.isPresent() ? 0 : replacement.layerAddWhenStack);
    if (state.parameterFrames == null)
      throw new IllegalArgumentException("Missing stack parameter frames");
    state.parameterFrames.push(args);
    int previous = replacement.values.modifier.layer;
    if (replacement.forceLayer == null)
      throw new IllegalArgumentException("Missing current forced-layer presence binding");
    int target =
        replacement.forceLayer.isPresent()
            ? replacement.forceLayer.getAsInt()
            : previous + replacement.layerAddWhenStack;
    setLayer(state, target, world);
    int layer = replacement.values.modifier.layer;
    int maximum = Math.max(1, replacement.values.baseMaxLayer + replacement.values.extraMaxLayer);
    setLayer(state, Math.min(layer, maximum), world);
    args.actualLayerChange = integerRaw((long) replacement.values.modifier.layer - previous);
    world.popStackedProperties(state);
    List<?> additional = state.stackedAdditionalTeamBp;
    state.shelvedAdditionalTeamBp = additional == null ? 0 : additional.size();
    world.popStackedAdditionalTeamBp(state);
    world.popBoostPointCallbackDecorate(state);
    Config config = state.config;
    if (config == null) throw new IllegalArgumentException("Missing current stack config");
    if (config.applyBehaviorFlagBindEffects()) world.bindBehaviorFlagEffects(state);
    int previousVersion = state.currentReentryVersion;
    state.maximumReentryVersion++;
    state.currentReentryVersion = state.maximumReentryVersion;
    world.tryBehaviorFlagEvent(state, false, false);
    world.executeEvent(state, 53);
    if (state.currentReentryVersion == 1) {
      state.maximumReentryVersion = 0;
      previousVersion = 0;
    }
    state.currentReentryVersion = previousVersion;
    if (!state.silenceAdd) world.postOwnerStackEvents(state);
    if (state.parameterFrames == null)
      throw new IllegalArgumentException("Missing current stack parameter frames");
    state.parameterFrames.pop();
    if (state.shelvedAdditionalTeamBp > 0) world.postAdditionalTeamBpStack(state, false);
  }

  private static void setLayer(State state, int value, World world) {
    var modifier = state.replacement.values.modifier;
    if (modifier.layer != value) {
      modifier.layer = value;
      world.valueChanged(state, 0);
    }
  }
}
