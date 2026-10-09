package org.hkrpg.server.gameplay;

import dev.morphia.annotations.Entity;
import java.util.*;
import lombok.Getter;

/** Actual prop outcomes, bound to one acquired lottery and a named project odds/pool policy. */
@Entity(useDiscriminator = false)
@Getter
public final class RogueLotteryState {
  static final String RULE = "hkrpg.rogue.galactic-lottery-five-percent-10004.v1";
  private String rule = "";
  private String acquisition = "";
  private Map<String, Roll> rolls = new LinkedHashMap<>();
  private boolean lastBroken;

  protected RogueLotteryState() {}

  RogueLotteryState(String acquisition) {
    this.rule = RULE;
    this.acquisition = acquisition;
  }

  static boolean uuid(String value) {
    if (value == null) return false;
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  boolean valid(GameplayState.RogueMiracle item) {
    return item != null
        && RULE.equals(rule)
        && uuid(acquisition)
        && acquisition.equals(item.getAcquisitionId())
        && rolls != null
        && rolls.size() <= 4096
        && item.getDestroyedProps() != null
        && item.getTotalCount() == 1
        && item.getUseCount() == (lastBroken ? 1 : 0)
        && (!lastBroken
            || rolls.values().stream().anyMatch(r -> r != null && r.getOutcome() == Outcome.BREAK))
        && item.getDestroyedProps().equals(rolls.keySet())
        && rolls.entrySet().stream()
            .allMatch(
                e ->
                    validKey(e.getKey())
                        && e.getValue() != null
                        && e.getValue().valid()
                        && e.getValue()
                            .matches(RogueGalacticLottery.outcome(acquisition, e.getKey())));
  }

  static boolean validKey(String key) {
    if (key == null || key.length() > 65) return false;
    var parts = key.split(":", -1);
    if (parts.length != 6) return false;
    try {
      for (var part : parts) {
        int value = Integer.parseInt(part);
        if (value <= 0 || !Integer.toString(value).equals(part)) return false;
      }
      return true;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  boolean remember(GameplayState.RogueMiracle item, String key, Roll roll) {
    if (!valid(item)
        || rolls.size() >= 4096
        || !validKey(key)
        || rolls.containsKey(key)
        || roll == null
        || !roll.valid()) return false;
    if (!item.rememberDestruction(key)) return false;
    rolls.put(key, roll);
    if (roll.getOutcome() == Outcome.BREAK) lastBroken = true;
    return true;
  }

  void repaired() {
    lastBroken = false;
  }

  enum Outcome {
    NONE,
    GAIN,
    BREAK,
    EXHAUSTED
  }

  @Entity(useDiscriminator = false)
  @Getter
  public static final class Roll {
    private Outcome outcome;
    private int awardedId;
    private String awardedAcquisition = "";
    private Map<Integer, Integer> beforeHp = new TreeMap<>();
    private Map<Integer, Integer> afterHp = new TreeMap<>();

    protected Roll() {}

    private Roll(Outcome outcome) {
      this.outcome = outcome;
    }

    static Roll none() {
      return new Roll(Outcome.NONE);
    }

    static Roll exhausted() {
      return new Roll(Outcome.EXHAUSTED);
    }

    boolean matches(Outcome draw) {
      return outcome == draw || outcome == Outcome.EXHAUSTED && draw == Outcome.GAIN;
    }

    static Roll gain(int id, String acquisition) {
      var roll = new Roll(Outcome.GAIN);
      roll.awardedId = id;
      roll.awardedAcquisition = acquisition;
      return roll;
    }

    static Roll broken(Map<Integer, Integer> before, Map<Integer, Integer> after) {
      var roll = new Roll(Outcome.BREAK);
      roll.beforeHp.putAll(before);
      roll.afterHp.putAll(after);
      return roll;
    }

    boolean valid() {
      if (outcome == null || beforeHp == null || afterHp == null || awardedAcquisition == null)
        return false;
      return switch (outcome) {
        case NONE, EXHAUSTED ->
            awardedId == 0
                && awardedAcquisition.isEmpty()
                && beforeHp.isEmpty()
                && afterHp.isEmpty();
        case GAIN ->
            awardedId > 0
                && awardedId != 21
                && awardedId != 63
                && uuid(awardedAcquisition)
                && beforeHp.isEmpty()
                && afterHp.isEmpty();
        case BREAK ->
            awardedId == 0
                && awardedAcquisition.isEmpty()
                && !beforeHp.isEmpty()
                && beforeHp.size() <= 4
                && beforeHp.keySet().equals(afterHp.keySet())
                && beforeHp.entrySet().stream()
                    .allMatch(
                        e ->
                            e.getKey() != null
                                && e.getKey() > 0
                                && e.getValue() != null
                                && e.getValue() >= 0
                                && e.getValue() <= 10000
                                && Objects.equals(
                                    afterHp.get(e.getKey()), (e.getValue() + 99) / 100));
      };
    }
  }
}
