package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;

/** Accepted classic main-page reports only; old saves start with no manufactured roll history. */
@Entity(useDiscriminator = false)
public final class DiceRollState {
  static final String SOURCE_IDENTITY =
      "4.6-mainpage:9262:1500:cea8b6ffe42464dbec2162be883350db072baa5fbc8663f69a32e0c562ffaa95";
  private long count, lastMillis;
  private String resourceIdentity = "";

  public DiceRollState() {}

  DiceRollState(long count, long lastMillis) {
    this.count = count;
    this.lastMillis = lastMillis;
    resourceIdentity = SOURCE_IDENTITY;
  }

  public long getCount() {
    return count;
  }

  public long getLastMillis() {
    return lastMillis;
  }

  public boolean valid() {
    return count == 0 && lastMillis == 0 && "".equals(resourceIdentity)
        || count > 0
            && count <= 0xffffffffL
            && lastMillis > 0
            && lastMillis <= 253402300799000L
            && SOURCE_IDENTITY.equals(resourceIdentity);
  }
}
