package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** A pending delivery is retained for reconciliation, never automatically paid a second time. */
@Getter
@Entity(useDiscriminator = false)
public class BookPickupState {
  @Getter
  @Entity(useDiscriminator = false)
  public static class Receipt {
    private String source;
    private int reward;
    private int book;
    private boolean granted;

    private Receipt() {}

    Receipt(String source, int reward, int book, boolean granted) {
      this.source = source;
      this.reward = reward;
      this.book = book;
      this.granted = granted;
    }

    boolean valid() {
      return source != null && source.matches("[a-f0-9]{64}") && reward > 0 && book > 0;
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

  BookPickupState with(String key, Receipt receipt) {
    var next = new BookPickupState();
    next.receipts.putAll(receipts);
    next.receipts.put(key, receipt);
    return next;
  }
}
