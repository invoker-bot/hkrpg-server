package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;

/**
 * Confirmed source-bound prop destructions; ownership and old recovery receipts cannot infer it.
 */
@Entity(useDiscriminator = false)
public final class RogueDestructionState {
  private int count;

  public int total() {
    return count;
  }

  public boolean valid() {
    return count >= 0;
  }

  public boolean canRecord() {
    return valid() && count < Integer.MAX_VALUE;
  }

  public boolean record() {
    if (!canRecord()) return false;
    count++;
    return true;
  }
}
