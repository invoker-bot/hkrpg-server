package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** Persisted local orders and actual daily logins; absent on older saves. */
@Entity(useDiscriminator = false)
@Getter
public final class RecommendGiftState {
  private Map<String, Order> orders = new HashMap<>();
  private long dailyStart;
  private long lastLoginDay;
  private int unlocked;
  private int claimed;
  private int pendingClaimTo;
  private String pendingClaimKey = "";
  public Map<String,Order> getOrders() { if (orders == null) orders = new HashMap<>(); return orders; }
  void startDaily(long timestamp, long day) { dailyStart=timestamp; lastLoginDay=day; }
  void unlock(long day) { lastLoginDay=day; unlocked++; }
  void reserveDaily(int to, String key) { pendingClaimTo=to; pendingClaimKey=key; }
  void finishDaily() { claimed=pendingClaimTo; pendingClaimTo=0; pendingClaimKey=""; }
  @Entity(useDiscriminator = false)
  @Getter
  public static final class Order {
    private String product;
    private long time;
    private boolean completed;
    protected Order() {}
    Order(String product, long time) { this.product=product; this.time=time; }
    void complete() { completed=true; }
  }
}
