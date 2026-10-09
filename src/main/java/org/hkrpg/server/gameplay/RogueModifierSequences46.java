package org.hkrpg.server.gameplay;

/**
 * Ordinary modifier sequence counters and deferred replacement; native virtual slots stay bound.
 */
public final class RogueModifierSequences46 {
  private RogueModifierSequences46() {}

  public static final class State {
    public final RogueModifierReplace46.State replacement;
    public int tickingSequenceCount;

    public State(RogueModifierReplace46.State replacement) {
      if (replacement == null) throw new IllegalArgumentException("Missing replacement state");
      this.replacement = replacement;
    }
  }

  public interface Sequence {
    int state();

    void state(int state);

    int step(float delta);

    void reset();
  }

  public interface World {
    void sequenceEnded(State state);

    Object taskContext(State state);

    void replaceContextAbility(Object context, Object ability);

    void afterAbilityVirtual(State state);
  }

  public static void start(State state, Sequence sequence, World world) {
    requireState(state);
    if (sequence == null) return;
    state.replacement.executingSequenceCount++;
    state.tickingSequenceCount++;
    int result = sequence.step(0f);
    sequence.state(terminal(result) ? result : RogueTaskSequence46.EXECUTING);
    tick(state, sequence, 0f, world);
  }

  public static void tick(State state, Sequence sequence, float delta, World world) {
    requireState(state);
    if (sequence == null) return;
    int result = sequence.state();
    if (result == RogueTaskSequence46.EXECUTING) {
      sequence.state(sequence.step(delta));
      result = sequence.state();
    }
    if (!terminal(result)) return;
    sequence.reset();
    sequence.state(RogueTaskSequence46.READY);
    state.tickingSequenceCount--;
    if (world == null) throw new IllegalArgumentException("Missing sequence-end virtual binding");
    world.sequenceEnded(state);
  }

  public static void endAndFlush(State state, World world) {
    requireState(state);
    int previous = state.replacement.executingSequenceCount;
    state.replacement.executingSequenceCount--;
    if (previous > 1) return;
    Object ability = state.replacement.delayReplaceAbility;
    if (ability == null) return;
    if (world == null) throw new IllegalArgumentException("Missing deferred replacement world");
    Object context = world.taskContext(state);
    if (context == null) throw new IllegalArgumentException("Missing deferred task context");
    world.replaceContextAbility(context, ability);
    world.afterAbilityVirtual(state);
    state.replacement.delayReplaceAbility = null;
  }

  private static boolean terminal(int state) {
    return state == RogueTaskSequence46.SUCCESS || state == RogueTaskSequence46.FAIL;
  }

  private static void requireState(State state) {
    if (state == null) throw new IllegalArgumentException("Missing modifier sequence state");
  }
}
