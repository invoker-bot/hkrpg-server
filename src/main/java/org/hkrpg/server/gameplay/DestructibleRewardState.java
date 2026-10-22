package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/**
 * Per-location refresh receipts. Incomplete deliveries require reconciliation, even on a new day.
 */
@Getter
@Entity(useDiscriminator = false)
public class DestructibleRewardState {
  @Getter
  @Entity(useDiscriminator = false)
  public static class Receipt {
    private long day;
    private String source;
    private boolean granted;

    private Receipt() {}

    Receipt(long day, String source, boolean granted) {
      this.day = day;
      this.source = source;
      this.granted = granted;
    }

    boolean valid() {
      return day > 0 && source != null && source.matches("[a-f0-9]{64}");
    }
  }

  private Map<String, Receipt> receipts = new HashMap<>();

  boolean valid() {
    return receipts != null
        && receipts.entrySet().stream()
            .allMatch(
                e ->
                    e.getKey() != null
                        && e.getKey().matches("[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*")
                        && e.getValue() != null
                        && e.getValue().valid());
  }

  DestructibleRewardState with(String key, Receipt receipt) {
    var next = new DestructibleRewardState();
    next.receipts.putAll(receipts);
    next.receipts.put(key, receipt);
    return next;
  }
}
