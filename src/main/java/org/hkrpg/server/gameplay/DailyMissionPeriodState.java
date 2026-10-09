package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import lombok.Getter;

/** Completed repeatable commission views are archived before installing a new period. */
@Entity(useDiscriminator = false)
@Getter
public final class DailyMissionPeriodState {
  private Map<Integer, Long> runDays = new HashMap<>();
  private Map<String, Snapshot> archives = new HashMap<>();
  private Map<String, DailyMissionExpiredState> expired = new HashMap<>();

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Snapshot {
    private long day;
    private int mainId;
    private QuestState main;
    private Map<Integer, QuestState> subs = new HashMap<>();
    private Map<Integer, Integer> goals = new HashMap<>();
    private Map<Integer, GameplayState.ActionCursor> actions = new HashMap<>();
    private List<String> rewardKeys = new ArrayList<>();

    protected Snapshot() {}

    Snapshot(
        long day,
        int id,
        QuestState main,
        Map<Integer, QuestState> subs,
        Map<Integer, Integer> goals,
        Map<Integer, GameplayState.ActionCursor> actions,
        List<String> rewardKeys) {
      this.day = day;
      this.mainId = id;
      this.main = main;
      this.subs.putAll(subs);
      this.goals.putAll(goals);
      this.actions.putAll(actions);
      this.rewardKeys.addAll(rewardKeys);
    }
  }
}
