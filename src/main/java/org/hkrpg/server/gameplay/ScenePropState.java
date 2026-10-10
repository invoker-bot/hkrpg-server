package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** Authored prop transitions, bound to world context or the owning raid's main acceptance. */
@Entity(useDiscriminator = false)
public final class ScenePropState {
  private int version;
  private Map<String, Snapshot> records = new HashMap<>();

  protected ScenePropState() {}

  static ScenePropState empty() {
    var result = new ScenePropState();
    result.version = 1;
    return result;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Snapshot {
    private int plane, floor, dimension, story, group, instance, prop, state;
    private String source, acceptance;

    protected Snapshot() {}

    Snapshot(
        int plane,
        int floor,
        int dimension,
        int story,
        int group,
        int instance,
        int prop,
        int state,
        String source,
        String acceptance) {
      this.plane = plane;
      this.floor = floor;
      this.dimension = dimension;
      this.story = story;
      this.group = group;
      this.instance = instance;
      this.prop = prop;
      this.state = state;
      this.source = source;
      this.acceptance = acceptance;
    }

    String key() {
      return plane + ":" + floor + ":" + dimension + ":" + story + ":" + group + ":" + instance;
    }

    boolean valid() {
      if (plane <= 0
          || floor <= 0
          || dimension < 0
          || story < 0
          || group <= 0
          || instance <= 0
          || prop <= 0
          || state < 0
          || source == null
          || !source.matches("[0-9a-f]{64}")
          || acceptance == null) return false;
      try {
        return acceptance.isEmpty() || UUID.fromString(acceptance).toString().equals(acceptance);
      } catch (IllegalArgumentException invalid) {
        return false;
      }
    }
  }

  public boolean valid() {
    return version == 1
        && records != null
        && records.entrySet().stream()
            .allMatch(
                e ->
                    e.getKey() != null
                        && e.getValue() != null
                        && e.getValue().valid()
                        && e.getKey().equals(e.getValue().key()));
  }

  Snapshot get(String key) {
    return valid() ? records.get(key) : null;
  }

  ScenePropState with(Collection<Snapshot> snapshots) {
    if (!valid() || snapshots == null || snapshots.isEmpty())
      throw new IllegalStateException("Invalid prop transition");
    var next = empty();
    next.records.putAll(records);
    for (var s : snapshots) {
      if (s == null || !s.valid()) throw new IllegalStateException("Invalid prop state");
      next.records.put(s.key(), s);
    }
    return next;
  }

  public Map<String, Snapshot> getRecords() {
    return records == null ? null : Collections.unmodifiableMap(records);
  }
}
