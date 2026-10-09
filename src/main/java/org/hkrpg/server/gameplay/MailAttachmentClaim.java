package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;

/** A reservation and its observed delivery stay in the owning mail document. */
@Entity(useDiscriminator = false)
public final class MailAttachmentClaim {
  public enum Phase {
    READY,
    PENDING,
    CLAIMED
  }

  private Phase phase;
  private Map<Integer, Integer> expected = new TreeMap<>();
  private Map<Integer, Integer> before = new TreeMap<>();
  private Map<Integer, Integer> received = new TreeMap<>();
  private List<Integer> receivedUniqueIds = new ArrayList<>();

  /** Morphia constructor leaves absent state unknown rather than granting it a fresh claim. */
  @Deprecated
  public MailAttachmentClaim() {}

  public static MailAttachmentClaim ready() {
    var result = new MailAttachmentClaim();
    result.phase = Phase.READY;
    return result;
  }

  public Phase getPhase() {
    return phase;
  }

  public Map<Integer, Integer> getExpected() {
    return Map.copyOf(expected);
  }

  public Map<Integer, Integer> getBefore() {
    return Map.copyOf(before);
  }

  public Map<Integer, Integer> getReceived() {
    return Map.copyOf(received);
  }

  public List<Integer> getReceivedUniqueIds() {
    return List.copyOf(receivedUniqueIds);
  }

  public void reserve(Map<Integer, Integer> amounts, Map<Integer, Integer> balances) {
    if (phase != Phase.READY) throw new IllegalStateException("Mail already reserved");
    expected = new TreeMap<>(amounts);
    before = new TreeMap<>(balances);
    phase = Phase.PENDING;
  }

  public void settle(Map<Integer, Integer> amounts, List<Integer> uniqueIds, boolean complete) {
    if (phase != Phase.PENDING) throw new IllegalStateException("Mail was not reserved");
    received = new TreeMap<>(amounts);
    receivedUniqueIds = new ArrayList<>(uniqueIds);
    if (complete) phase = Phase.CLAIMED;
  }
}
