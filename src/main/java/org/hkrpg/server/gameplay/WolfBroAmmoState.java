package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** Persisted per-run ammo. Quantities and drops are the declared policy of this server. */
@Entity(useDiscriminator = false)
@Getter
public final class WolfBroAmmoState {
  private int ownerUid;
  private String uuid, sourceSignature;
  private boolean activated;
  private boolean granted;
  private int count;
  private Map<String, WolfBroState.Point> bullets = new LinkedHashMap<>();
  private Set<String> defeated = new HashSet<>();

  private WolfBroAmmoState() {}

  WolfBroAmmoState(int uid, String uuid, String source) {
    this.ownerUid = uid;
    this.uuid = uuid;
    this.sourceSignature = source;
  }

  WolfBroAmmoState copy() {
    var result = new WolfBroAmmoState(ownerUid, uuid, sourceSignature);
    result.activated = activated;
    result.granted = granted;
    result.count = count;
    result.bullets = new LinkedHashMap<>(bullets);
    result.defeated = new HashSet<>(defeated);
    return result;
  }

  boolean valid(String run) {
    return ownerUid > 0 && Objects.equals(run, uuid)
        && sourceSignature != null && sourceSignature.length() == 64
        && count >= 0 && count <= 5 && (activated || count == 0) && (!activated || granted)
        && bullets != null && defeated != null && defeated.size() <= 512
        && bullets.size() <= defeated.size() && defeated.stream().allMatch(WolfBroAmmoState::key)
        && bullets.entrySet().stream().allMatch(e -> defeated.contains(e.getKey())
            && e.getValue() != null && e.getValue().bounded(10_000_000));
  }

  private static boolean key(String value) {
    return value != null && value.matches("[1-9][0-9]{0,8}:[1-9][0-9]{0,8}");
  }

  boolean matches(int uid, String run, String source) {
    return valid(run) && ownerUid == uid && sourceSignature.equals(source);
  }

  void activate(int maximum) { activated = true; if (!granted) count = maximum; granted = true; }
  void spend() { count--; }
  void drop(String key, WolfBroState.Point point) { defeated.add(key); bullets.put(key, point); }
  void pickup(String key, int maximum) { bullets.remove(key); count = Math.min(maximum, count + 1); }

  WolfBroAmmoState rewind(WolfBroAmmoState archive) {
    var result = archive == null ? new WolfBroAmmoState(ownerUid, uuid, sourceSignature) : archive.copy();
    result.defeated.addAll(defeated);
    result.granted |= granted;
    return result;
  }

  public Map<String, WolfBroState.Point> getBullets() {
    return bullets == null ? null : Collections.unmodifiableMap(bullets);
  }

  public Set<String> getDefeated() {
    return defeated == null ? null : Collections.unmodifiableSet(defeated);
  }

  static boolean same(WolfBroAmmoState left, WolfBroAmmoState right) {
    return emu.lunarcore.data.ResourceLoader.getGson().toJsonTree(left)
        .equals(emu.lunarcore.data.ResourceLoader.getGson().toJsonTree(right));
  }
}
