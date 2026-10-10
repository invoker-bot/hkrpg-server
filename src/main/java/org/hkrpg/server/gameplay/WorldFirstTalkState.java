package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.util.*;
import lombok.Getter;

/** Standalone source observations and one nonperiodic activity goal share one player save. */
@Entity(useDiscriminator = false)
public final class WorldFirstTalkState {
  private int version = 1;
  private Map<Integer, Observation> records = new TreeMap<>();
  @Getter private QuestState activity;
  @Getter private ActivityReceipt activityReceipt;

  @Getter
  @Entity(useDiscriminator = false)
  public static final class Observation {
    private int performance, plane, floor, entry, dimension, story, group, instance, prop, entity;
    private long at, runtime;
    private String event, propSource, groupSource, scriptSource;

    private Observation() {}

    Observation(Player p, int id, EntityProp actor, WorldFirstTalkRules rules, long at) {
      var scene = p.getScene();
      performance = id;
      plane = scene.getPlaneId();
      floor = scene.getFloorId();
      entry = scene.getEntryId();
      dimension = scene.getDimensionId();
      story = scene.getStoryLineId();
      group = actor.getGroupId();
      instance = actor.getInstId();
      prop = actor.getPropId();
      entity = actor.getEntityId();
      runtime = scene.getRuntimeId();
      this.at = at;
      event = UUID.randomUUID().toString();
      propSource = PropTimelines.source(actor.getPropInfo());
      groupSource = WorldFirstTalkSources.FILES.get(WorldFirstTalkSources.LOCATIONS.get(id).path());
      scriptSource = WorldFirstTalkSources.FILES.get(rules.script(id));
    }

    boolean valid(WorldFirstTalkRules rules) {
      try {
        var l = WorldFirstTalkSources.LOCATIONS.get(performance);
        var original = rules.original(performance, dimension);
        return l != null
            && original != null
            && plane == l.plane()
            && floor == l.floor()
            && group == l.group()
            && instance == l.instance()
            && prop == l.prop()
            && entity > 0
            && runtime > 0
            && story >= 0
            && at > 0
            && rules.entry(plane, floor, entry)
            && UUID.fromString(event).toString().equals(event)
            && Objects.equals(propSource, PropTimelines.source(original))
            && Objects.equals(groupSource, WorldFirstTalkSources.FILES.get(l.path()))
            && Objects.equals(
                scriptSource, WorldFirstTalkSources.FILES.get(rules.script(performance)));
      } catch (RuntimeException invalid) {
        return false;
      }
    }
  }

  @Getter
  @Entity(useDiscriminator = false)
  public static final class ActivityReceipt {
    private int performance;
    private long at, begin, end;
    private String event, acceptance;

    private ActivityReceipt() {}

    ActivityReceipt(Observation observation, QuestState state, GameplayDefinitions.Window window) {
      performance = observation.performance;
      at = observation.at;
      event = observation.event;
      acceptance = state.getObservationKey();
      begin = window.beginTime();
      end = window.endTime();
    }
  }

  public Map<Integer, Observation> getRecords() {
    return records == null ? null : Collections.unmodifiableMap(records);
  }

  boolean valid(WorldFirstTalkRules rules) {
    if (rules == null || version != 1 || records == null || records.size() > 25) return false;
    var events = new HashSet<String>();
    for (var e : records.entrySet())
      if (e.getKey() == null
          || e.getValue() == null
          || e.getKey() != e.getValue().performance
          || !e.getValue().valid(rules)
          || !events.add(e.getValue().event)) return false;
    if (activity == null) return activityReceipt == null;
    try {
      if (activity.getObservationKey() == null
          || !UUID.fromString(activity.getObservationKey())
              .toString()
              .equals(activity.getObservationKey())) return false;
      if (activityReceipt == null)
        return MissionStateRecords.doing(activity, WorldFirstTalkRules.QUEST, 1)
            && activity.getProgress() == 0;
      var r = activityReceipt;
      var world = records.get(r.performance);
      return world != null
          && MissionStateRecords.completed(activity, WorldFirstTalkRules.QUEST, 1)
          && Objects.equals(r.acceptance, activity.getObservationKey())
          && Objects.equals(r.event, world.event)
          && r.at == world.at
          && r.at == activity.getFinishTime()
          && r.begin > 0
          && r.end > r.begin
          && r.at >= r.begin
          && r.at < r.end;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  boolean accept() {
    if (activity != null) return false;
    activity = new QuestState(WorldFirstTalkRules.QUEST);
    activity.captureObservationKey();
    return true;
  }

  WorldFirstTalkState recorded(Observation observation, GameplayDefinitions.Window window) {
    var next =
        ResourceLoader.getGson()
            .fromJson(ResourceLoader.getGson().toJson(this), WorldFirstTalkState.class);
    if (next.records.putIfAbsent(observation.performance, observation) != null)
      throw new IllegalStateException("Already observed standalone performance");
    if (window != null
        && next.activity != null
        && MissionStateRecords.doing(next.activity, WorldFirstTalkRules.QUEST, 1)) {
      next.activityReceipt = new ActivityReceipt(observation, next.activity, window);
      if (!next.activity.advance(1, 1, observation.at))
        throw new IllegalStateException("Activity acceptance changed");
    }
    return next;
  }
}
