package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import lombok.Getter;

/** An unpaid pure commission keeps its exact old view when the next day retires it. */
@Entity(useDiscriminator = false)
@Getter
public final class DailyMissionExpiredState {
  private long day, expiredAt;
  private int mainId;
  private QuestState main;
  private Map<Integer, QuestState> subs = new HashMap<>();
  private Map<Integer, Integer> goals = new HashMap<>();
  private Map<Integer, GameplayState.ActionCursor> actions = new HashMap<>();

  protected DailyMissionExpiredState() {}

  DailyMissionExpiredState(
      long day,
      long expiredAt,
      int mainId,
      QuestState main,
      Map<Integer, QuestState> subs,
      Map<Integer, Integer> goals,
      Map<Integer, GameplayState.ActionCursor> actions) {
    this.day = day;
    this.expiredAt = expiredAt;
    this.mainId = mainId;
    this.main = main;
    this.subs.putAll(subs);
    this.goals.putAll(goals);
    this.actions.putAll(actions);
  }
}
