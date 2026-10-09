package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** One hourly anchor and frozen active rates; interrupted grants remain explicitly pending. */
@Entity(useDiscriminator = false)
@Getter
public class ExpeditionState {
  private long rewardTime, sequence;
  private List<Assignment> assignments = new ArrayList<>();
  private Attempt lastAttempt;

  @Entity(useDiscriminator = false)
  @Getter
  public static class Assignment {
    private int id;
    private Map<Integer, Integer> rates = new TreeMap<>();

    protected Assignment() {}

    public Assignment(int id, Map<Integer, Integer> rates) {
      this.id = id;
      this.rates = new TreeMap<>(rates);
    }
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static class Attempt {
    private long sequence, beforeTime, afterTime;
    private int hours;
    private List<Integer> previousIDs = new ArrayList<>(), nextIDs = new ArrayList<>();
    private Map<Integer, Integer> rewards = new TreeMap<>();
    private boolean completed;

    protected Attempt() {}

    Attempt(
        long sequence,
        long before,
        long after,
        int hours,
        List<Integer> previous,
        List<Assignment> next,
        Map<Integer, Integer> rewards) {
      this.sequence = sequence;
      beforeTime = before;
      afterTime = after;
      this.hours = hours;
      previousIDs = new ArrayList<>(previous);
      nextIDs = next.stream().map(Assignment::getId).toList();
      this.rewards = new TreeMap<>(rewards);
    }

    public String key() {
      return "expedition-harvest:" + sequence;
    }
  }

  public boolean pending() {
    return lastAttempt != null && !lastAttempt.completed;
  }

  public Attempt reserve(
      long nextTime, int hours, List<Assignment> next, Map<Integer, Integer> rewards) {
    if (pending()) throw new IllegalStateException("Expedition grant pending");
    long serial = Math.addExact(sequence, 1);
    lastAttempt = new Attempt(serial, rewardTime, nextTime, hours, ids(), next, rewards);
    sequence = serial;
    return lastAttempt;
  }

  public List<Integer> ids() {
    return assignments.stream().map(Assignment::getId).toList();
  }

  public void commit(List<Assignment> next, long time) {
    if (time < rewardTime || time <= 0 || time % 3600 != 0)
      throw new IllegalArgumentException("Invalid hourly anchor");
    assignments = new ArrayList<>(next);
    rewardTime = time;
    if (lastAttempt != null && !lastAttempt.completed) lastAttempt.completed = true;
  }
}
