package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** Only committed player-selected display items; no construction or reward progress. */
@Entity(useDiscriminator = false)
@Getter
public final class PlayerRoomDisplayState {
  private int version = 1;
  private Map<Integer, Integer> items = new TreeMap<>();
  private Map<Integer, Picture> pictures = new TreeMap<>();

  public PlayerRoomDisplayState() {}
  PlayerRoomDisplayState(Map<Integer, Integer> items, Map<Integer, Picture> pictures) {
    this.items = new TreeMap<>(items);
    this.pictures = new TreeMap<>(pictures);
  }

  public Map<Integer, Integer> getItems() { return Collections.unmodifiableMap(items); }
  public Map<Integer, Picture> getPictures() { return Collections.unmodifiableMap(pictures); }

  boolean valid() {
    if (version != 1 || items == null || pictures == null || items.size() > 6
        || pictures.size() > 6) return false;
    var slots = new HashSet<Integer>();
    var identities = new HashSet<Integer>();
    for (var entry : items.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null || entry.getKey() <= 0
          || entry.getValue() <= 0 || !slots.add(entry.getKey())
          || !identities.add(entry.getValue())) return false;
    }
    var photos = new HashSet<String>();
    for (var entry : pictures.entrySet()) {
      var p = entry.getValue();
      if (entry.getKey() == null || entry.getKey() <= 0 || p == null || p.id <= 0
          || (p.type != 1 && p.type != 3 && p.type != 4) || !slots.add(entry.getKey())
          || !photos.add(p.type + ":" + p.id)) return false;
    }
    return true;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Picture {
    private int id;
    private int type;
    public Picture() {}
    public Picture(int id, int type) { this.id = id; this.type = type; }
    @Override public boolean equals(Object other) {
      return other instanceof Picture p && id == p.id && type == p.type;
    }
    @Override public int hashCode() { return Objects.hash(id, type); }
  }
}
