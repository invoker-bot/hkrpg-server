package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.HashMap;
import java.util.Map;

/** Phone histories belong to a particular accepted daily run in the same Player document. */
@Entity(useDiscriminator = false)
public final class DailyMissionMessageState {
  private Map<String, Run> runs = new HashMap<>();

  public Map<String, Run> getRuns() { return runs; }

  @Entity(useDiscriminator = false)
  public static final class Run {
    private long day;
    private int mainId, goalId, sectionId;
    private String source, mainAcceptance, goalAcceptance;
    private GameplayState.PhoneSection phone;

    protected Run() {}

    Run(long day, String source, String mainAcceptance, String goalAcceptance,
        GameplayState.PhoneSection phone) {
      this.day = day;
      this.mainId = DailyMissionMessageRules.MAIN;
      this.goalId = DailyMissionMessageRules.GOAL;
      this.sectionId = DailyMissionMessageRules.SECTION;
      this.source = source;
      this.mainAcceptance = mainAcceptance;
      this.goalAcceptance = goalAcceptance;
      this.phone = phone;
    }

    public long getDay() { return day; }
    public int getMainId() { return mainId; }
    public int getGoalId() { return goalId; }
    public int getSectionId() { return sectionId; }
    public String getSource() { return source; }
    public String getMainAcceptance() { return mainAcceptance; }
    public String getGoalAcceptance() { return goalAcceptance; }
    public GameplayState.PhoneSection getPhone() { return phone; }
  }
}
