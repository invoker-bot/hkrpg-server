package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;

/** Accepted reported deaths, independent of scene actors or completed explorations. */
@Entity(useDiscriminator = false)
public final class RogueBattleKillState {
  private int count;

  public int total() {
    return count;
  }

  public boolean valid() {
    return count >= 0;
  }

  public boolean canRecord(int delta) {
    return valid() && delta > 0 && (long) count + delta <= Integer.MAX_VALUE;
  }

  public boolean record(int delta) {
    if (!canRecord(delta)) return false;
    count += delta;
    return true;
  }
}
