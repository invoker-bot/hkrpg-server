package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import lombok.Getter;

/** Three source-bound paired observations per nonperiodic quest, persisted with its quest phase. */
@Entity(useDiscriminator = false)
public final class TimelineActivityState {
  private int version = 1;
  private Map<Integer, QuestState> quests = new HashMap<>();
  private Map<Integer, List<Receipt>> receipts = new HashMap<>();

  @dev.morphia.annotations.Transient
  private transient Map<Integer, TimelineActivityQuests.Pending> pending = new HashMap<>();

  @Entity(useDiscriminator = false)
  @Getter
  static final class Receipt {
    private int quest, plane, floor, dimension, story, group, instance, prop;
    private long at, begin, end;
    private String acceptance, groupSource, propSource, previous, state;

    protected Receipt() {}

    Receipt(
        int quest,
        PropTimelineState.Snapshot snapshot,
        String previous,
        String acceptance,
        long at,
        GameplayDefinitions.Window window,
        String groupSource) {
      this.quest = quest;
      plane = snapshot.getPlane();
      floor = snapshot.getFloor();
      dimension = snapshot.getDimension();
      story = snapshot.getStory();
      group = snapshot.getGroup();
      instance = snapshot.getInstance();
      prop = snapshot.getProp();
      propSource = snapshot.getSource();
      state = snapshot.getState();
      this.previous = previous;
      this.acceptance = acceptance;
      this.at = at;
      begin = window.beginTime();
      end = window.endTime();
      this.groupSource = groupSource;
    }

    boolean valid(TimelineActivityRules rules, String expectedAcceptance) {
      try {
        if (!TimelineActivityRules.IDS.contains(quest)
            || rules.plane(quest) != plane
            || rules.floor(quest) != floor
            || dimension < 0
            || story < 0
            || group <= 0
            || instance <= 0
            || prop <= 0
            || at <= 0
            || begin <= 0
            || end <= begin
            || at < begin
            || at >= end
            || !Objects.equals(acceptance, expectedAcceptance)
            || !UUID.fromString(acceptance).toString().equals(acceptance)
            || !PropTimelineState.stateName(state)
            || previous != null && !PropTimelineState.stateName(previous)
            || Objects.equals(previous, state)
            || !Objects.equals(groupSource, rules.groupSource(plane, floor, dimension, group)))
          return false;
        var definition = rules.prop(plane, floor, dimension, group, instance);
        return definition != null
            && definition.getPropID() == prop
            && Objects.equals(propSource, PropTimelines.source(definition));
      } catch (RuntimeException invalid) {
        return false;
      }
    }
  }

  Map<Integer, TimelineActivityQuests.Pending> pending() {
    if (pending == null) pending = new HashMap<>();
    return pending;
  }

  public Map<Integer, QuestState> getQuests() {
    return quests == null ? null : Collections.unmodifiableMap(quests);
  }

  public Map<Integer, List<Receipt>> getReceipts() {
    return receipts == null ? null : Collections.unmodifiableMap(receipts);
  }

  void accept(int id) {
    quests.put(id, new QuestState(id));
  }

  int count(int id) {
    return receipts.getOrDefault(id, List.of()).size();
  }

  boolean valid(TimelineActivityRules rules) {
    if (rules == null
        || version != 1
        || quests == null
        || receipts == null
        || quests.size() > 4
        || receipts.size() > 4
        || quests.keySet().stream()
            .anyMatch(id -> id == null || !TimelineActivityRules.IDS.contains(id))
        || receipts.keySet().stream().anyMatch(id -> id == null || !quests.containsKey(id)))
      return false;
    for (var e : quests.entrySet()) {
      int id = e.getKey();
      var quest = e.getValue();
      var history = receipts.getOrDefault(id, List.of());
      if (history == null
          || history.size() > 3
          || !(MissionStateRecords.doing(quest, id, 3)
              || MissionStateRecords.completed(quest, id, 3))
          || quest.getProgress() != history.size()) return false;
      long last = 0;
      for (var receipt : history) {
        if (receipt == null
            || receipt.quest != id
            || !receipt.valid(rules, quest.getObservationKey())
            || receipt.at < last) return false;
        last = receipt.at;
      }
      if (history.size() == 3 && quest.getFinishTime() != last) return false;
    }
    return true;
  }

  TimelineActivityState recorded(int id, Receipt receipt) {
    var next = new TimelineActivityState();
    var gson = ResourceLoader.getGson();
    quests.forEach(
        (key, value) -> next.quests.put(key, gson.fromJson(gson.toJson(value), QuestState.class)));
    receipts.forEach((key, value) -> next.receipts.put(key, new ArrayList<>(value)));
    var state = next.quests.get(id);
    var history = next.receipts.computeIfAbsent(id, key -> new ArrayList<>());
    if (history.size() >= 3 || state == null || !state.advance(1, 3, receipt.at))
      throw new IllegalStateException("Unaccepted timeline count");
    history.add(receipt);
    return next;
  }
}
