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
    // Absent on legacy single-book receipts. The old book field remains their stable identity.
    private List<Integer> books;
    private boolean granted;

    private Receipt() {}

    Receipt(String source, int reward, int book, boolean granted) {
      this.source = source;
      this.reward = reward;
      this.book = book;
      this.granted = granted;
    }

    Receipt(String source, int reward, List<Integer> books, boolean granted) {
      this(source, reward, books.getFirst(), granted);
      if (books.size() > 1) this.books = List.copyOf(books);
    }

    List<Integer> books() {
      return books == null ? List.of(book) : books;
    }

    boolean valid() {
      return source != null
          && source.matches("[a-f0-9]{64}")
          && reward > 0
          && book > 0
          && (books == null
              || books.size() >= 2
                  && books.size() <= 6
                  && books.stream().allMatch(id -> id != null && id > 0)
                  && books.equals(books.stream().distinct().sorted().toList())
                  && books.getFirst() == book);
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
