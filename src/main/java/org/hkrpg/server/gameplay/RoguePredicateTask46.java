package org.hkrpg.server.gameplay;

import java.util.function.BooleanSupplier;
import org.hkrpg.server.gameplay.RogueTaskSequence46.Task;

/**
 * Matched shared predicate lifecycle; evaluation and the live configuration getter are explicit.
 */
public final class RoguePredicateTask46 implements Task {
  private final BooleanSupplier evaluation;
  private final BooleanSupplier inverse;
  private int state = RogueTaskSequence46.READY;

  public RoguePredicateTask46(BooleanSupplier evaluation, BooleanSupplier inverse) {
    if (evaluation == null || inverse == null)
      throw new IllegalArgumentException(
          "Missing explicit predicate evaluation/configuration binding");
    this.evaluation = evaluation;
    this.inverse = inverse;
  }

  public int state() {
    return state;
  }

  public void begin() {
    boolean result = evaluation.getAsBoolean();
    boolean inverted = inverse.getAsBoolean();
    state = result != inverted ? RogueTaskSequence46.SUCCESS : RogueTaskSequence46.FAIL;
  }

  public void reset() {
    state = RogueTaskSequence46.READY;
  }

  public void tick(float delta) {
    // Original shared Tick throws NotImplementedException, including before begin.
    throw new UnsupportedOperationException("Original predicate task Tick is not implemented");
  }

  public void dispose() {}
}
