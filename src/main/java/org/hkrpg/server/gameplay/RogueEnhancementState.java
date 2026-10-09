package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;

/** Confirmed paid and curio upgrades, saved with the actual current-run changes. */
@Entity(useDiscriminator = false)
public final class RogueEnhancementState {
  private int count;

  public int total() {
    return count;
  }

  public boolean valid() {
    return count >= 0;
  }

  public boolean canEnhance() {
    return canRecordEnhancements(1);
  }

  public boolean recordEnhancement() {
    return recordEnhancements(1);
  }

  public boolean canRecordEnhancements(int operations) {
    return valid() && operations >= 0 && (long) count + operations <= Integer.MAX_VALUE;
  }

  public boolean recordEnhancements(int operations) {
    if (!canRecordEnhancements(operations)) return false;
    count += operations;
    return true;
  }
}
