package org.hkrpg.server.gameplay;

/** Explicit task callback on a selected context projection; no client task graph is decoded. */
public final class RogueResonanceAliasContext46 implements RogueResonanceLookup46 {
  @FunctionalInterface
  public interface Evaluation {
    long evaluate(RogueResonanceLookup46 context, Object taskConfiguration, int outputKey);
  }

  private final RogueResonanceLookup46 selectedContext;
  private final RogueResonanceTaskCell46.AliasEvaluator evaluator;

  public RogueResonanceAliasContext46(RogueResonanceLookup46 context, Evaluation evaluation) {
    if (context == null || evaluation == null)
      throw new IllegalArgumentException("Missing explicit resonance alias context");
    selectedContext = context;
    evaluator = (configuration, key) -> evaluation.evaluate(this, configuration, key);
  }

  @Override
  public RogueResonanceVariables46 select(int scope, int key) {
    return selectedContext.select(scope, key);
  }

  @Override
  public RogueResonanceTaskCell46.AliasEvaluator aliasEvaluator() {
    return evaluator;
  }
}
