package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** Last accepted ASCII state, separated between the ordinary world and each mission raid run. */
@Entity(useDiscriminator = false)
public final class PropTimelineState {
  private int version;
  private Map<String, Snapshot> records = new HashMap<>();

  protected PropTimelineState() {}

  static PropTimelineState empty() {
    var state = new PropTimelineState();
    state.version = 1;
    return state;
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Snapshot {
    private int plane, floor, dimension, story, group, instance, prop;
    private String source, state, acceptance;

    protected Snapshot() {}

    Snapshot(
        int plane,
        int floor,
        int dimension,
        int story,
        int group,
        int instance,
        int prop,
        String source,
        String state,
        String acceptance) {
      this.plane = plane;
      this.floor = floor;
      this.dimension = dimension;
      this.story = story;
      this.group = group;
      this.instance = instance;
      this.prop = prop;
      this.source = source;
      this.state = state;
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
          || source == null
          || !source.matches("[0-9a-f]{64}")
          || !stateName(state)
          || acceptance == null) return false;
      try {
        return acceptance.isEmpty() || UUID.fromString(acceptance).toString().equals(acceptance);
      } catch (IllegalArgumentException invalid) {
        return false;
      }
    }
  }

  public static boolean stateName(String state) {
    return state != null && state.matches("[A-Za-z0-9_]{1,128}");
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

  PropTimelineState with(Snapshot snapshot) {
    if (!valid() || snapshot == null || !snapshot.valid())
      throw new IllegalStateException("Invalid timeline record");
    var result = empty();
    result.records.putAll(records);
    result.records.put(snapshot.key(), snapshot);
    return result;
  }

  public Map<String, Snapshot> getRecords() {
    return records == null ? null : Collections.unmodifiableMap(records);
  }
}
