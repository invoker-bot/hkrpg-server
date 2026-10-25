package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** A reserved payment is never automatically repeated after interruption. */
@Getter
@Entity(useDiscriminator = false)
public class ShopPurchaseState {
  private Map<String, Integer> counts = new HashMap<>();
  private Purchase pending;
  private int personalCard = 253000;
  public void personalCard(int id) { personalCard = id; }

  public void reserve(String key, int count, Map<Integer,Integer> costs, Map<Integer,Integer> rewards) {
    if (pending != null) throw new IllegalStateException("Unsettled shop purchase");
    pending = new Purchase(key,count,costs,rewards);
  }
  public void cancelReservation() { pending = null; }
  public void complete() {
    counts.put(pending.key, Math.addExact(counts.getOrDefault(pending.key,0),pending.count));
    pending = null;
  }

  @Getter
  @Entity(useDiscriminator = false)
  public static class Purchase {
    private String key;
    private int count;
    private Map<Integer,Integer> costs, rewards;
    protected Purchase() {}
    Purchase(String key,int count,Map<Integer,Integer> costs,Map<Integer,Integer> rewards) {
      this.key=key; this.count=count; this.costs=new TreeMap<>(costs); this.rewards=new TreeMap<>(rewards);
    }
  }
}
