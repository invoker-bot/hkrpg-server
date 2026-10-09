package org.hkrpg.server.gameplay;

/** Ordinary UnStack orchestration over the existing shared modifier states. */
public final class RogueModifierUnstack46 {
  private RogueModifierUnstack46() {}

  /** Embedded value projection; its team-null branch preserves the other two members. */
  public record BpDirty(Object team, Object modifier, int value) {
    public static final BpDirty ZERO = new BpDirty(null, null, 0);
  }

  public static final class State {
    public final RogueModifierStackedEffects46.State stacked;
    public final RogueModifierRegistrations46.State registrations;
    public final RogueModifierOwnedEffects46.State owned;
    public BpDirty bpDirty = BpDirty.ZERO;

    public State(
        RogueModifierStackedEffects46.State stacked,
        RogueModifierRegistrations46.State registrations,
        RogueModifierOwnedEffects46.State owned) {
      this.stacked = require(stacked, "stacked effects");
      this.registrations = require(registrations, "registrations");
      this.owned = require(owned, "owned effects");
      var cleanup = require(registrations.cleanup, "registration cleanup");
      if (cleanup != owned.cleanup || cleanup.sequence.replacement != stacked.stack.replacement)
        throw new IllegalArgumentException("UnStack states must share one modifier");
    }
  }

  public interface World
      extends RogueModifierStackedEffects46.World,
          RogueModifierRegistrations46.World,
          RogueModifierOwnedEffects46.World {
    void popStackedProperties(RogueModifierStackEvent46.State state);

    void removeSpecialBp(Object module, RogueModifierReplace46.State modifier);
  }

  public static void unstack(State state, World world) {
    require(state, "unstack state");
    World bound = require(world, "unstack world");
    bound.popStackedProperties(state.stacked.stack);
    RogueModifierStackedEffects46.popWeakness(state.stacked, bound);
    RogueModifierStackedEffects46.popAdditionalTeamBp(state.stacked, bound);
    RogueModifierStackedEffects46.popBoostCallbacks(state.stacked, bound);
    popSpecialBp(state, bound);
    RogueModifierRegistrations46.popNames(state.registrations, bound);
    RogueModifierRegistrations46.popBindings(state.registrations, bound);
    RogueModifierRegistrations46.popAi(state.registrations, bound);
    RogueModifierRegistrations46.popUpperGroups(state.registrations, bound);
    RogueModifierOwnedEffects46.detachCharacterChange(state.owned, true, bound);
    RogueModifierOwnedEffects46.detachDeparted(state.owned, bound);
    RogueModifierOwnedEffects46.detachUnselectable(state.owned, bound);
  }

  public static void popSpecialBp(State state, World world) {
    BpDirty value = require(require(state, "unstack state").bpDirty, "BP dirty value");
    if (value.team() == null) return;
    World bound = require(world, "special BP world");
    Object module = bound.teamBoostPointModule(value.team());
    if (module != null) bound.removeSpecialBp(module, state.stacked.stack.replacement);
    state.bpDirty = BpDirty.ZERO;
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
