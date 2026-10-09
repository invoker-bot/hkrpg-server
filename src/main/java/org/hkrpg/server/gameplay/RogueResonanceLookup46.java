package org.hkrpg.server.gameplay;

/** Explicitly supplied fixed-cell lookup projections; this is not a live TaskContext binding. */
public interface RogueResonanceLookup46 {
  RogueResonanceVariables46 select(int scope, int key);

  default RogueResonanceTaskCell46.AliasEvaluator aliasEvaluator() {
    return null;
  }

  default long readInScope(int scope, int key) {
    return select(scope, key).readBound(key, true, aliasEvaluator());
  }

  default long read(int key) {
    return select(0, key).readBound(key, false, aliasEvaluator());
  }
}
