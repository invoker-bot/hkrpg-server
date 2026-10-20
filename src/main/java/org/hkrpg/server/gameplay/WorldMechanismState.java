package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.scene.Scene;
import java.util.*;
import lombok.Getter;

/** Saved before a defeated guard disappears or an unlocked chest is published. No rewards here. */
@Entity(useDiscriminator = false)
public final class WorldMechanismState {
  private int version = 1;
  private Map<Integer, Progress> records = new TreeMap<>();

  @Getter
  @Entity(useDiscriminator = false)
  public static final class Progress {
    private int chest, entry, dimension, story;
    private long runtime, at, completedAt;
    private String event, source;
    private Set<Integer> defeated = new TreeSet<>();
    private boolean complete;

    private Progress() {}

    private Progress(WorldMechanismSources.Rule rule, Scene scene, long now) {
      chest = rule.chest();
      entry = scene.getEntryId();
      dimension = scene.getDimensionId();
      story = scene.getStoryLineId();
      runtime = scene.getRuntimeId();
      at = now;
      event = UUID.randomUUID().toString();
      source = rule.source();
    }

    private boolean valid() {
      try {
        var rule = WorldMechanismSources.RULES.get(chest);
        var loc = MapChestSources.LOCATIONS.get(chest);
        return rule != null
            && source.equals(rule.source())
            && entry > 0
            && dimension >= 0
            && loc.dimensions().contains(dimension)
            && story >= 0
            && runtime > 0
            && at > 0
            && UUID.fromString(event).toString().equals(event)
            && defeated != null
            && rule.guards().containsAll(defeated)
            && (rule.kind() == WorldMechanismSources.Kind.GUARDS
                ? complete == defeated.containsAll(rule.guards()) && !defeated.isEmpty()
                : complete && defeated.isEmpty())
            && (complete ? completedAt >= at : completedAt == 0);
      } catch (RuntimeException invalid) {
        return false;
      }
    }
  }

  public Map<Integer, Progress> getRecords() {
    return records == null ? null : Collections.unmodifiableMap(records);
  }

  public boolean valid() {
    return version == 1
        && records != null
        && records.size() <= WorldMechanismSources.RULES.size()
        && records.entrySet().stream()
            .allMatch(
                e ->
                    e.getKey() != null
                        && e.getValue() != null
                        && e.getKey() == e.getValue().chest
                        && e.getValue().valid());
  }

  boolean completed(int id) {
    return valid() && records.containsKey(id) && records.get(id).complete;
  }

  boolean defeated(int id, int instance) {
    return valid() && records.containsKey(id) && records.get(id).defeated.contains(instance);
  }

  WorldMechanismState advance(WorldMechanismSources.Rule rule, Scene scene, int guard, long now) {
    if (!valid()) throw new IllegalStateException("Invalid world mechanism journal");
    var next =
        ResourceLoader.getGson()
            .fromJson(ResourceLoader.getGson().toJson(this), WorldMechanismState.class);
    var record = next.records.computeIfAbsent(rule.chest(), k -> new Progress(rule, scene, now));
    if (record.complete) return this;
    if (rule.kind() == WorldMechanismSources.Kind.GUARDS) {
      if (!rule.guards().contains(guard)) throw new IllegalArgumentException("Unrelated guard");
      record.defeated.add(guard);
      record.complete = record.defeated.containsAll(rule.guards());
    } else {
      if (guard != 0) throw new IllegalArgumentException("Unexpected guard");
      record.complete = true;
    }
    if (record.complete) record.completedAt = now;
    if (!next.valid()) throw new IllegalStateException("Invalid mechanism transition");
    return next;
  }
}
