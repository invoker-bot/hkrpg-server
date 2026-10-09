package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.game.inventory.GameItem;
import java.util.*;
import lombok.Getter;

/**
 * One frozen decomposition attempt; an interrupted debit or grant is never automatically replayed.
 */
@Entity(useDiscriminator = false)
@Getter
public final class ItemSaleState {
  private Attempt lastAttempt;

  public boolean available() {
    return lastAttempt == null
        || "COMPLETE".equals(lastAttempt.phase)
            && lastAttempt.sequence > 0
            && lastAttempt.sequence < Long.MAX_VALUE;
  }

  public Attempt reserve(
      boolean materials,
      List<GameItem> items,
      Map<Integer, Integer> returns,
      Map<Integer, Integer> before) {
    if (!available()) throw new IllegalStateException("Unresolved decomposition attempt");
    long sequence = lastAttempt == null ? 1 : Math.addExact(lastAttempt.sequence, 1);
    lastAttempt = new Attempt(sequence, materials, items, returns, before);
    return lastAttempt;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class Input {
    private int itemId, uniqueId, ownerUid;
    private String persistentId;

    protected Input() {}

    Input(GameItem item) {
      itemId = item.getItemId();
      uniqueId = item.getInternalUid();
      ownerUid = item.getOwnerUid();
      persistentId = item.getId() == null ? null : item.getId().toHexString();
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class Attempt {
    private long sequence;
    private boolean materials;
    private String phase = "RESERVED";
    private List<Input> inputs = new ArrayList<>();
    private Map<Integer, Integer> returns = new TreeMap<>();
    private Map<Integer, Integer> before = new TreeMap<>();
    private Map<Integer, Integer> received = new TreeMap<>();

    protected Attempt() {}

    public List<Input> getInputs() {
      return inputs == null ? List.of() : Collections.unmodifiableList(inputs);
    }

    public Map<Integer, Integer> getReturns() {
      return returns == null ? Map.of() : Collections.unmodifiableMap(returns);
    }

    public Map<Integer, Integer> getBefore() {
      return before == null ? Map.of() : Collections.unmodifiableMap(before);
    }

    public Map<Integer, Integer> getReceived() {
      return received == null ? Map.of() : Collections.unmodifiableMap(received);
    }

    Attempt(
        long sequence,
        boolean materials,
        List<GameItem> items,
        Map<Integer, Integer> returns,
        Map<Integer, Integer> before) {
      this.sequence = sequence;
      this.materials = materials;
      items.forEach(i -> inputs.add(new Input(i)));
      this.returns.putAll(returns);
      this.before.putAll(before);
    }

    void debited() {
      if (!"RESERVED".equals(phase)) throw new IllegalStateException("Invalid decomposition phase");
      phase = "DEBITED";
    }

    void settle(Map<Integer, Integer> actual, boolean complete) {
      if (!"DEBITED".equals(phase)) throw new IllegalStateException("Invalid decomposition grant");
      received.putAll(actual);
      phase = complete ? "COMPLETE" : "PARTIAL";
    }
  }
}
