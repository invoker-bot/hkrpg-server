package org.hkrpg.server.gameplay;

import java.util.function.IntToLongFunction;

/** Explicit standard-cell projections; runtime task configuration decoding is separate. */
public sealed interface RogueResonanceCell46
    permits RogueResonanceDynamicCell46, RogueResonanceTaskCell46 {
  long read(IntToLongFunction provider, RogueResonanceTaskCell46.AliasEvaluator evaluator);

  long clip(long raw, IntToLongFunction provider);

  RogueResonanceVariables46.Bounds bounds(IntToLongFunction provider);

  void replaceValue(long raw);

  RogueResonanceCell46 copy();
}
