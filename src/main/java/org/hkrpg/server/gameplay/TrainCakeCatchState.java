package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** One committed local box batch and its actual captures, saved with the owning Player. */
@Entity(useDiscriminator = false)
@Getter
public final class TrainCakeCatchState {
  private int version = 1;
  private String source = "";
  private long refreshAt;
  private List<Spot> spots = new ArrayList<>();
  private Set<Integer> caught = new TreeSet<>();
  private Map<Integer, Integer> counts = new TreeMap<>();

  public TrainCakeCatchState() {}

  TrainCakeCatchState(String source, long refreshAt, List<Spot> spots,
      Set<Integer> caught, Map<Integer, Integer> counts) {
    this.source = source;
    this.refreshAt = refreshAt;
    this.spots = new ArrayList<>(spots);
    this.caught = new TreeSet<>(caught);
    this.counts = new TreeMap<>(counts);
  }

  public List<Spot> getSpots() { return Collections.unmodifiableList(spots); }
  public Set<Integer> getCaught() { return Collections.unmodifiableSet(caught); }
  public Map<Integer, Integer> getCounts() { return Collections.unmodifiableMap(counts); }

  public boolean valid() {
    if (version != 1 || source == null || spots == null || caught == null || counts == null
        || refreshAt < 0 || counts.size() > 27 || spots.size() > 3 || caught.size() > 3) return false;
    if (refreshAt == 0) return source.isEmpty() && spots.isEmpty() && caught.isEmpty() && counts.isEmpty();
    if (!source.matches("[a-f0-9]{64}") || spots.size() != 3) return false;
    var ids = new HashSet<Integer>();
    var slots = new HashSet<Integer>();
    for (var spot : spots) {
      if (spot == null || spot.cat < 1 || spot.cat > 27 || spot.slot < 0 || spot.slot > 6
          || !ids.add(spot.cat) || !slots.add(spot.slot)) return false;
    }
    if (!ids.containsAll(caught)) return false;
    for (var entry : counts.entrySet()) {
      if (entry.getKey() == null || entry.getKey() < 1 || entry.getKey() > 27
          || entry.getValue() == null || entry.getValue() < 1 || entry.getValue() > 13) return false;
    }
    return counts.keySet().containsAll(caught);
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Spot {
    private int cat, slot;
    public Spot() {}
    public Spot(int cat, int slot) { this.cat = cat; this.slot = slot; }
  }
}
