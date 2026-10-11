package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.util.*;
import lombok.Getter;

/** Original chest identity is permanent; activity credit belongs to its captured acceptance. */
@Entity(useDiscriminator = false)
public final class WorldChestState {
  private int version = 1;
  private Map<Integer, Opening> records = new TreeMap<>();
  private Map<Integer, QuestState> quests = new TreeMap<>();
  private Map<Integer, Unlock> unlocks = new TreeMap<>();
  private Map<Integer, GoldenSpearRecord> spears = new TreeMap<>();

  public enum Phase {
    PREPARED,
    OPEN
  }

  /** A source-bound client finish report; it grants no loot or activity credit. */
  @Getter
  @Entity(useDiscriminator = false)
  public static final class Unlock {
    private int chest, plane, floor, entry, dimension, story, group, instance, prop, entity;
    private long at, runtime;
    private String event, propSource, groupSource, chestSource;
    private Phase phase;
    private int triggerEntity;
    private String triggerSource;
    private long completedAt;

    private Unlock() {}

    Unlock(Player p, int id, EntityProp actor, WorldChestRules rules, long now) {
      var scene = p.getScene();
      chest = id;
      plane = scene.getPlaneId();
      floor = scene.getFloorId();
      entry = scene.getEntryId();
      dimension = scene.getDimensionId();
      story = scene.getStoryLineId();
      runtime = scene.getRuntimeId();
      group = actor.getGroupId();
      instance = actor.getInstId();
      prop = actor.getPropId();
      entity = actor.getEntityId();
      at = now;
      event = UUID.randomUUID().toString();
      propSource = PropTimelines.source(actor.getPropInfo());
      groupSource = rules.filePins.get(rules.locations.get(id).path());
      chestSource = rules.rowPins.get("MazeChest:" + id);
    }

    Unlock(
        Player p,
        int id,
        EntityProp chestActor,
        EntityProp shield,
        WorldChestRules rules,
        long now) {
      this(p, id, chestActor, rules, now);
      phase = Phase.PREPARED;
      triggerEntity = shield.getEntityId();
      triggerSource = PropTimelines.source(shield.getPropInfo());
    }

    public boolean completed() {
      return phase == null || phase == Phase.OPEN;
    }

    boolean valid(WorldChestRules rules) {
      try {
        var l = rules.locations.get(chest);
        var original = rules.original(chest, dimension);
        boolean sourceEvent =
            rules.flame(chest)
                ? phase == null && triggerEntity == 0 && triggerSource == null && completedAt == 0
                : rules.golden(chest)
                    && phase != null
                    && triggerEntity > 0
                    && triggerEntity != entity
                    && Objects.equals(
                        triggerSource,
                        PropTimelines.source(
                            rules.groupProp(chest, dimension, 300001, GoldenSpearSources.SHIELD)))
                    && (phase == Phase.PREPARED ? completedAt == 0 : completedAt >= at);
        return sourceEvent
            && l != null
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
            && Objects.equals(groupSource, rules.filePins.get(l.path()))
            && Objects.equals(chestSource, rules.rowPins.get("MazeChest:" + chest));
      } catch (RuntimeException invalid) {
        return false;
      }
    }
  }

  @Getter
  @Entity(useDiscriminator = false)
  public static final class Opening {
    private int chest, plane, floor, entry, dimension, story, group, instance, prop, entity, policy;
    private long at, runtime, begin, end;
    private String event, propSource, groupSource, chestSource;
    private Phase phase;
    private Map<Integer, Integer> loot;
    private Map<Integer, String> acceptances;

    private Opening() {}

    Opening(
        Player p,
        int id,
        EntityProp actor,
        WorldChestRules rules,
        Map<Integer, QuestState> states,
        GameplayDefinitions.Window window,
        long now) {
      var scene = p.getScene();
      chest = id;
      plane = scene.getPlaneId();
      floor = scene.getFloorId();
      entry = scene.getEntryId();
      dimension = scene.getDimensionId();
      story = scene.getStoryLineId();
      runtime = scene.getRuntimeId();
      group = actor.getGroupId();
      instance = actor.getInstId();
      prop = actor.getPropId();
      entity = actor.getEntityId();
      at = now;
      event = UUID.randomUUID().toString();
      phase = Phase.PREPARED;
      policy = WorldChestRewards.VERSION;
      loot = new TreeMap<>(rules.loot(id));
      acceptances = new TreeMap<>();
      propSource = PropTimelines.source(actor.getPropInfo());
      groupSource = rules.filePins.get(rules.locations.get(id).path());
      chestSource = rules.rowPins.get("MazeChest:" + id);
      if (window != null) {
        states.forEach(
            (goal, state) -> {
              int target = goal(goal);
              if (rules.countsGoal(goal, id) && MissionStateRecords.doing(state, goal, target))
                acceptances.put(goal, state.getObservationKey());
            });
        if (!acceptances.isEmpty()) {
          begin = window.beginTime();
          end = window.endTime();
        }
      }
    }

    public String rewardKey() {
      return "world-chest:" + chest + ":" + event;
    }

    boolean valid(WorldChestRules rules, Player p) {
      try {
        var l = rules.locations.get(chest);
        var original = rules.original(chest, dimension);
        if (l == null
            || original == null
            || phase == null
            || plane != l.plane()
            || floor != l.floor()
            || group != l.group()
            || instance != l.instance()
            || prop != l.prop()
            || entity <= 0
            || runtime <= 0
            || story < 0
            || at <= 0
            || policy != WorldChestRewards.VERSION
            || !rules.entry(plane, floor, entry)
            || !UUID.fromString(event).toString().equals(event)
            || !Objects.equals(propSource, PropTimelines.source(original))
            || !Objects.equals(groupSource, rules.filePins.get(l.path()))
            || !Objects.equals(chestSource, rules.rowPins.get("MazeChest:" + chest))
            || !Objects.equals(loot, rules.loot(chest))
            || acceptances == null
            || acceptances.size() > rules.goals().size()) return false;
        var receipt = p.getGameplay().getRewards().get(rewardKey());
        if (receipt == null
            || receipt.getRewardId() != 0
            || receipt.isGranted() != (phase == Phase.OPEN)) return false;
        if (acceptances.isEmpty()) return begin == 0 && end == 0;
        return begin > 0
            && end > begin
            && at >= begin
            && at < end
            && acceptances.entrySet().stream()
                .allMatch(
                    e ->
                        e.getKey() != null
                            && rules.countsGoal(e.getKey(), chest)
                            && e.getValue() != null
                            && UUID.fromString(e.getValue()).toString().equals(e.getValue()));
      } catch (RuntimeException invalid) {
        return false;
      }
    }
  }

  static int goal(int id) {
    return switch (id) {
      case 6029850 -> 5;
      case 6029851 -> 10;
      case 6029852 -> 15;
      case TimeRelayFlameSources.GOAL, GoldenSpearSources.GOAL -> 1;
      default -> -1;
    };
  }

  public Map<Integer, Opening> getRecords() {
    return records == null ? null : Collections.unmodifiableMap(records);
  }

  public Map<Integer, QuestState> getQuests() {
    return quests == null ? null : Collections.unmodifiableMap(quests);
  }

  public Map<Integer, Unlock> getUnlocks() {
    return unlocks == null ? null : Collections.unmodifiableMap(unlocks);
  }

  public Map<Integer, GoldenSpearRecord> getSpears() {
    return spears == null ? null : Collections.unmodifiableMap(spears);
  }

  boolean valid(WorldChestRules rules, Player p) {
    if (rules == null
        || version != 1
        || records == null
        || quests == null
        || unlocks == null
        || spears == null
        || records.size() > rules.locations.size()
        || quests.size() > rules.goals().size()
        || unlocks.size() > rules.locations.keySet().stream().filter(rules::puzzle).count()
        || spears.size() > GoldenSpearSources.PUZZLES.size()) return false;
    for (var e : spears.entrySet())
      if (e.getKey() == null
          || e.getValue() == null
          || e.getKey() != e.getValue().getChest()
          || !e.getValue().valid(rules)) return false;
    for (var e : unlocks.entrySet())
      if (e.getKey() == null
          || e.getValue() == null
          || e.getKey() != e.getValue().chest
          || !e.getValue().valid(rules)) return false;
    var keys = new HashSet<String>();
    for (var e : records.entrySet()) {
      if (e.getKey() == null
          || e.getValue() == null
          || e.getKey() != e.getValue().chest
          || !e.getValue().valid(rules, p)
          || !keys.add(e.getValue().rewardKey())) return false;
      if (rules.puzzle(e.getKey())) {
        var unlock = unlocks.get(e.getKey());
        if (unlock == null
            || !unlock.completed()
            || unlock.at > e.getValue().at
            || unlock.completedAt > e.getValue().at) return false;
      }
    }
    var journal =
        p.getGameplay().getRewards().keySet().stream()
            .filter(k -> k.startsWith("world-chest:"))
            .collect(java.util.stream.Collectors.toSet());
    if (!keys.equals(journal)) return false;
    try {
      for (var e : quests.entrySet()) {
        var state = e.getValue();
        int target = e.getKey() == null ? -1 : goal(e.getKey());
        if (target < 1
            || !rules.goals().contains(e.getKey())
            || state == null
            || state.getObservationKey() == null
            || !UUID.fromString(state.getObservationKey())
                .toString()
                .equals(state.getObservationKey())) return false;
        var credited =
            records.values().stream()
                .filter(
                    r ->
                        r.phase == Phase.OPEN
                            && Objects.equals(
                                r.acceptances.get(e.getKey()), state.getObservationKey()))
                .toList();
        if (credited.size() > target || state.getProgress() != credited.size()) return false;
        if (credited.size() < target) {
          if (!MissionStateRecords.doing(state, e.getKey(), target)) return false;
        } else if (!MissionStateRecords.completed(state, e.getKey(), target)
            || state.getFinishTime() != credited.stream().mapToLong(r -> r.at).max().orElseThrow())
          return false;
      }
      for (var record : records.values())
        for (var e : record.acceptances.entrySet()) {
          var state = quests.get(e.getKey());
          if (state == null || !Objects.equals(e.getValue(), state.getObservationKey()))
            return false;
        }
      return true;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  boolean accept(WorldChestRules rules) {
    boolean changed = false;
    for (int id : rules.goals())
      if (!quests.containsKey(id)) {
        var state = new QuestState(id);
        state.captureObservationKey();
        quests.put(id, state);
        changed = true;
      }
    return changed;
  }

  private WorldChestState copy() {
    return ResourceLoader.getGson()
        .fromJson(ResourceLoader.getGson().toJson(this), WorldChestState.class);
  }

  WorldChestState unlocked(Unlock observation) {
    var next = copy();
    if (next.unlocks.putIfAbsent(observation.chest, observation) != null)
      throw new IllegalStateException("Already unlocked original puzzle chest");
    return next;
  }

  WorldChestState revealed(int chest, long now) {
    var next = copy();
    var record = next.unlocks.get(chest);
    if (record == null || record.phase != Phase.PREPARED)
      throw new IllegalStateException("Reveal reservation changed");
    record.phase = Phase.OPEN;
    record.completedAt = now;
    return next;
  }

  WorldChestState spearStep(
      Player p, int chest, EntityProp actor, WorldChestRules rules, int index, long now) {
    var next = copy();
    var record = next.spears.computeIfAbsent(chest, GoldenSpearRecord::new);
    record.append(p, actor, rules, index, now);
    return next;
  }

  WorldChestState prepared(Opening observation) {
    var next = copy();
    if (next.records.putIfAbsent(observation.chest, observation) != null)
      throw new IllegalStateException("Already reserved original chest");
    return next;
  }

  WorldChestState opened(int chest) {
    var next = copy();
    var record = next.records.get(chest);
    if (record == null || record.phase != Phase.PREPARED)
      throw new IllegalStateException("Chest reservation changed");
    record.phase = Phase.OPEN;
    for (var e : record.acceptances.entrySet()) {
      var state = next.quests.get(e.getKey());
      if (state == null
          || !Objects.equals(state.getObservationKey(), e.getValue())
          || !state.advance(1, goal(e.getKey()), record.at))
        throw new IllegalStateException("Chest acceptance changed");
    }
    return next;
  }
}
