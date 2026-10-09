package org.hkrpg.server.gameplay;

import java.util.List;

/** Ordinary registration-ID consumption sharing the existing stack-event and cleanup state. */
public final class RogueAdditionalBpCost46 {
  public static final int EXHAUSTED_REASON = 4;

  private RogueAdditionalBpCost46() {}

  public static final class State {
    public final RogueModifierCleanup46.State cleanup;
    public final RogueModifierStackEvent46.State stack;

    public State(RogueModifierCleanup46.State cleanup, RogueModifierStackEvent46.State stack) {
      this.cleanup = require(cleanup, "cleanup state");
      this.stack = require(stack, "stack event state");
      if (cleanup.sequence.replacement != stack.replacement)
        throw new IllegalArgumentException("BP consumption must share one modifier");
    }
  }

  public interface World {
    void destroy(RogueModifierCleanup46.State modifier, int reason);
  }

  public static void cost(State state, int registrationId, World world) {
    require(state, "BP consumption state");
    List<?> initial = state.stack.stackedAdditionalTeamBp;
    if (initial == null || initial.size() == 0) return;
    int count = current(state).size();
    if (count > RogueSpecialBpWithdrawal46.LIMIT)
      throw new IllegalArgumentException("Excessive finite BP consumption records");
    for (int i = count > 0 ? count - 1 : -1; i >= 0; i--) {
      Object value = current(state).get(i);
      if (!(value instanceof RogueModifierStackedEffects46.AdditionalBp entry))
        throw new IllegalArgumentException("Unmatched explicit BP consumption entry");
      if (entry.registrationId() == registrationId) current(state).remove(i);
    }
    List<?> remaining = state.stack.stackedAdditionalTeamBp;
    if (remaining == null || remaining.size() == 0)
      require(world, "BP consumption destruction world").destroy(state.cleanup, EXHAUSTED_REASON);
  }

  private static List<?> current(State state) {
    return require(state.stack.stackedAdditionalTeamBp, "current BP consumption records");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
