package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;

/** The visitor's lifetime totals and current-week claims, saved in the visitor document. */
@Entity(useDiscriminator = false)
public final class TrainCakeVisitState {
  private int version = 1;
  private String source = "";
  private long refreshAt;
  private Map<Integer, Set<Integer>> rooms = new TreeMap<>();
  private Map<Integer, Integer> counts = new TreeMap<>();

  public TrainCakeVisitState() {}

  TrainCakeVisitState(String source, long refreshAt, Map<Integer, Set<Integer>> rooms,
      Map<Integer, Integer> counts) {
    this.source = source;
    this.refreshAt = refreshAt;
    rooms.forEach((owner, cats) -> this.rooms.put(owner, new TreeSet<>(cats)));
    this.counts = new TreeMap<>(counts);
  }

  boolean valid(int uid, String expectedSource) {
    if (version != 1 || source == null || !source.equals(expectedSource) || refreshAt <= 0
        || rooms == null || counts == null || rooms.size() > 9 || counts.size() > 27) return false;
    var currentCounts = new HashMap<Integer, Integer>();
    int total = 0;
    for (var row : rooms.entrySet()) {
      if (row.getKey() == null || row.getKey() <= 0 || row.getKey() == uid
          || row.getValue() == null || row.getValue().isEmpty() || row.getValue().size() > 3)
        return false;
      for (var cat : row.getValue()) {
        if (cat == null || cat < 1 || cat > 27) return false;
        currentCounts.merge(cat, 1, Integer::sum);
        total++;
      }
    }
    if (total > 9) return false;
    for (var row : counts.entrySet())
      if (row.getKey() == null || row.getKey() < 1 || row.getKey() > 27
          || row.getValue() == null || row.getValue() < 1 || row.getValue() > 13) return false;
    return currentCounts.entrySet().stream()
        .allMatch(row -> counts.getOrDefault(row.getKey(), 0) >= row.getValue());
  }

  long refreshAt() { return refreshAt; }
  Map<Integer, Integer> counts() { return Collections.unmodifiableMap(counts); }
  Map<Integer, Set<Integer>> rooms() {
    var copy = new TreeMap<Integer, Set<Integer>>();
    rooms.forEach((owner, cats) -> copy.put(owner, Collections.unmodifiableSet(new TreeSet<>(cats))));
    return Collections.unmodifiableMap(copy);
  }
}
