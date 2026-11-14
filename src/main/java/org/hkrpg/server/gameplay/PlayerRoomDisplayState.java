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
  private Map<Integer, Badge> badges = new TreeMap<>();
  // The native preference uses true for closed; old saves therefore start closed.
  private boolean badgeAutoFillClosed = true;
  private Map<Integer, Long> autoSeenBadges = new TreeMap<>();

  public PlayerRoomDisplayState() {}
  PlayerRoomDisplayState(Map<Integer, Integer> items, Map<Integer, Picture> pictures) {
    this.items = new TreeMap<>(items);
    this.pictures = new TreeMap<>(pictures);
  }

  PlayerRoomDisplayState(Map<Integer, Integer> items, Map<Integer, Picture> pictures,
      Map<Integer, Badge> badges, boolean closed, Map<Integer, Long> seen) {
    this(items, pictures);
    this.badges = new TreeMap<>(badges);
    this.badgeAutoFillClosed = closed;
    this.autoSeenBadges = new TreeMap<>(seen);
  }

  public Map<Integer, Integer> getItems() { return Collections.unmodifiableMap(items); }
  public Map<Integer, Picture> getPictures() { return Collections.unmodifiableMap(pictures); }
  public Map<Integer, Badge> getBadges() { return Collections.unmodifiableMap(badges); }
  public boolean isBadgeAutoFillClosed() { return badgeAutoFillClosed; }
  public Map<Integer, Long> getAutoSeenBadges() { return Collections.unmodifiableMap(autoSeenBadges); }

  boolean valid() {
    if (version != 1 || items == null || pictures == null || badges == null || autoSeenBadges == null
        || items.size() > 6 || pictures.size() > 6 || badges.size() > 9 || autoSeenBadges.size() > 256)
      return false;
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
    var badgeIds = new HashSet<Integer>();
    for (var entry : badges.entrySet()) {
      var badge = entry.getValue();
      if (entry.getKey() == null || entry.getKey() <= 0 || badge == null || badge.id <= 0
          || badge.expireTime <= 0 || badge.expireTime > BadgeCatalog.PERMANENT
          || !slots.add(entry.getKey()) || !badgeIds.add(badge.id)) return false;
    }
    for (var entry : autoSeenBadges.entrySet())
      if (entry.getKey() == null || entry.getKey() <= 0 || entry.getValue() == null
          || entry.getValue() <= 0 || entry.getValue() > BadgeCatalog.PERMANENT) return false;
    return true;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Badge {
    private int id;
    private long expireTime;
    public Badge() {}
    public Badge(int id, long expireTime) { this.id = id; this.expireTime = expireTime; }
    @Override public boolean equals(Object other) {
      return other instanceof Badge b && id == b.id && expireTime == b.expireTime;
    }
    @Override public int hashCode() { return Objects.hash(id, expireTime); }
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
