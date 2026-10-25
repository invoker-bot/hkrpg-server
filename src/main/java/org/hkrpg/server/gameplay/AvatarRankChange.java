package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.Map;
import java.util.TreeMap;
import lombok.Getter;

/** Persist intent before consuming a formal character's rank material; uncertain writes cannot replay. */
@Getter
@Entity(useDiscriminator = false)
public final class AvatarRankChange {
  public enum Phase { RESERVED, DEBITED, COMPLETE }
  private int avatarId;
  private int sourceRankId;
  private int beforeRank;
  private int afterRank;
  private Map<Integer,Integer> costs = new TreeMap<>();
  private Map<Integer,Integer> inventoryBefore = new TreeMap<>();
  private Phase phase;

  @Deprecated public AvatarRankChange() {}

  AvatarRankChange(int avatarId, int sourceRankId, int beforeRank,
      Map<Integer,Integer> costs, Map<Integer,Integer> inventoryBefore) {
    this.avatarId = avatarId;
    this.sourceRankId = sourceRankId;
    this.beforeRank = beforeRank;
    this.afterRank = beforeRank + 1;
    this.costs = new TreeMap<>(costs);
    this.inventoryBefore = new TreeMap<>(inventoryBefore);
    this.phase = Phase.RESERVED;
  }

  boolean pending() { return phase != Phase.COMPLETE; }
  void debited() { phase = Phase.DEBITED; }
  void complete() { phase = Phase.COMPLETE; }
}
