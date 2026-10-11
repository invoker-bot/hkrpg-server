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
    // Only the original three-stage graph freezes these completed prerequisite identities.
    private String initialAcceptance, firstAcceptance;
    private GameplayState.PhoneSection phone;

    protected Run() {}

    Run(long day, int mainId, int goalId, int sectionId, String source, String mainAcceptance, String goalAcceptance,
        GameplayState.PhoneSection phone) {
      this(day, mainId, goalId, sectionId, source, mainAcceptance, goalAcceptance, null, null, phone);
    }

    Run(long day, int mainId, int goalId, int sectionId, String source, String mainAcceptance, String goalAcceptance,
        String initialAcceptance, String firstAcceptance, GameplayState.PhoneSection phone) {
      this.day = day;
      this.mainId = mainId;
      this.goalId = goalId;
      this.sectionId = sectionId;
      this.source = source;
      this.mainAcceptance = mainAcceptance;
      this.goalAcceptance = goalAcceptance;
      this.initialAcceptance = initialAcceptance;
      this.firstAcceptance = firstAcceptance;
      this.phone = phone;
    }

    public long getDay() { return day; }
    public int getMainId() { return mainId; }
    public int getGoalId() { return goalId; }
    public int getSectionId() { return sectionId; }
    public String getSource() { return source; }
    public String getMainAcceptance() { return mainAcceptance; }
    public String getGoalAcceptance() { return goalAcceptance; }
    public String getInitialAcceptance() { return initialAcceptance; }
    public String getFirstAcceptance() { return firstAcceptance; }
    public GameplayState.PhoneSection getPhone() { return phone; }
  }
}
