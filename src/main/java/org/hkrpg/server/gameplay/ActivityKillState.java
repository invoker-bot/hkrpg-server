package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import lombok.Getter;

/** Nonperiodic source-bound deaths; each observation belongs to one accepted original goal. */
@Entity(useDiscriminator = false)
public final class ActivityKillState {
  private int version = 1;
  private Map<Integer, QuestState> quests = new TreeMap<>();
  private Map<Integer, List<Receipt>> receipts = new TreeMap<>();

  @Getter
  @Entity(useDiscriminator = false)
  public static final class Receipt {
    private int quest, stage, monster, entity, copies, plane, floor, entry, dimension, story, mode;
    private long at, begin, end;
    private String battle, acceptance, stageSource, monsterSource;

    private Receipt() {}

    Receipt(
        int quest,
        int stage,
        int monster,
        int entity,
        int copies,
        int plane,
        int floor,
        int entry,
        int dimension,
        int story,
        int mode,
        long at,
        GameplayDefinitions.Window window,
        String battle,
        String acceptance) {
      this.quest = quest;
      this.stage = stage;
      this.monster = monster;
      this.entity = entity;
      this.copies = copies;
      this.plane = plane;
      this.floor = floor;
      this.entry = entry;
      this.dimension = dimension;
      this.story = story;
      this.mode = mode;
      this.at = at;
      this.begin = window.beginTime();
      this.end = window.endTime();
      this.battle = battle;
      this.acceptance = acceptance;
      stageSource = ActivityKillSources.STAGES.get(stage);
      monsterSource = ActivityKillSources.MONSTERS.get(monster);
    }

    boolean valid(ActivityKillRules rules, QuestState state) {
      try {
        var original = rules.stage(stage);
        return state != null
            && original != null
            && ActivityKillSources.IDS.contains(quest)
            && entity > 0
            && copies > 0
            && copies <= 64
            && plane > 0
            && floor > 0
            && entry > 0
            && dimension >= 0
            && story >= 0
            && at > 0
            && begin > 0
            && end > begin
            && at >= begin
            && at < end
            && (mode == 2 || mode == 8)
            && rules.mode(plane, floor, entry) == mode
            && UUID.fromString(battle).toString().equals(battle)
            && UUID.fromString(acceptance).toString().equals(acceptance)
            && Objects.equals(acceptance, state.getObservationKey())
            && rules.template(monster) == rules.target(quest)
            && Objects.equals(stageSource, ActivityKillSources.STAGES.get(stage))
            && Objects.equals(monsterSource, ActivityKillSources.MONSTERS.get(monster))
            && original.getMonsterWaves().stream().anyMatch(w -> w.contains(monster));
      } catch (RuntimeException invalid) {
        return false;
      }
    }
  }

  public Map<Integer, QuestState> getQuests() {
    return quests == null ? null : Collections.unmodifiableMap(quests);
  }

  public Map<Integer, List<Receipt>> getReceipts() {
    return receipts == null ? null : Collections.unmodifiableMap(receipts);
  }

  void accept(int id) {
    var state = new QuestState(id);
    state.captureObservationKey();
    quests.put(id, state);
  }

  boolean valid(ActivityKillRules rules) {
    if (rules == null
        || version != 1
        || quests == null
        || receipts == null
        || quests.size() > 5
        || receipts.size() > 5
        || quests.keySet().stream()
            .anyMatch(id -> id == null || !ActivityKillSources.IDS.contains(id))
        || receipts.keySet().stream().anyMatch(id -> id == null || !quests.containsKey(id)))
      return false;
    var entities = new HashSet<String>();
    var capacities = new HashMap<String, Integer>();
    var counts = new HashMap<String, Integer>();
    var frames = new HashMap<String, String>();
    for (var e : quests.entrySet()) {
      int id = e.getKey(), goal = rules.goal(id);
      var state = e.getValue();
      var history = receipts.getOrDefault(id, List.of());
      if (history == null
          || history.size() > goal
          || state == null
          || state.getObservationKey() == null
          || !(MissionStateRecords.doing(state, id, goal)
              || MissionStateRecords.completed(state, id, goal))
          || state.getProgress() != history.size()) return false;
      try {
        if (!UUID.fromString(state.getObservationKey())
            .toString()
            .equals(state.getObservationKey())) return false;
      } catch (RuntimeException invalid) {
        return false;
      }
      long last = 0;
      for (var r : history) {
        if (r == null
            || r.quest != id
            || !r.valid(rules, state)
            || r.at < last
            || !entities.add(r.battle + ":" + r.entity)) return false;
        String frame =
            r.plane
                + ":"
                + r.floor
                + ":"
                + r.entry
                + ":"
                + r.dimension
                + ":"
                + r.story
                + ":"
                + r.mode
                + ":"
                + r.at
                + ":"
                + r.begin
                + ":"
                + r.end;
        String previous = frames.putIfAbsent(r.battle, frame);
        if (previous != null && !previous.equals(frame)) return false;
        String key = r.battle + ":" + r.stage + ":" + r.monster;
        int capacity =
            (int)
                    rules.stage(r.stage).getMonsterWaves().stream()
                        .flatMap(Collection::stream)
                        .filter(m -> m == r.monster)
                        .count()
                * r.copies;
        Integer old = capacities.putIfAbsent(key, capacity);
        if (old != null && old != capacity) return false;
        if (counts.merge(key, 1, Integer::sum) > capacity) return false;
        last = r.at;
      }
      if (history.size() == goal && state.getFinishTime() != last) return false;
    }
    return true;
  }

  ActivityKillState recorded(List<Receipt> observations, ActivityKillRules rules) {
    var next = new ActivityKillState();
    var gson = ResourceLoader.getGson();
    quests.forEach(
        (id, state) -> next.quests.put(id, gson.fromJson(gson.toJson(state), QuestState.class)));
    receipts.forEach((id, history) -> next.receipts.put(id, new ArrayList<>(history)));
    for (var r : observations) {
      var q = next.quests.get(r.quest);
      int goal = rules.goal(r.quest);
      if (!MissionStateRecords.doing(q, r.quest, goal)) continue;
      if (!q.advance(1, goal, r.at)) throw new IllegalStateException("Unaccepted original death");
      next.receipts.computeIfAbsent(r.quest, id -> new ArrayList<>()).add(r);
    }
    return next;
  }
}
