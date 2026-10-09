package org.hkrpg.server.gameplay;

import java.math.*;
import java.util.OptionalInt;

/**
 * Counts cleared rooms, then freezes resource score and conversion; never extrapolates a missing
 * row.
 */
public final class RogueEconomy {
  record Score(int points, int coins, boolean ready) {}

  /** Only new runs read this resource; a persisted balance is never reinitialized. */
  public static OptionalInt initialCoins() {
    String base = GameplayDefinitions.rogueConstants.get("Start_Rogue_Coin_Base");
    if (base == null || !base.matches("[0-9]+")) return OptionalInt.empty();
    try {
      return OptionalInt.of(Integer.parseInt(base));
    } catch (NumberFormatException e) {
      return OptionalInt.empty();
    }
  }

  static Score capture(GameplayState.RogueRun run) {
    var area = GameplayDefinitions.areas.get(run.getAreaId());
    if (area == null || area.ScoreMap() == null) return new Score(0, 0, false);
    int passed = run.getCleared().size();
    if (passed == 0 || area.ScoreMap().isEmpty()) return new Score(0, 0, true);
    Integer score = area.ScoreMap().get(passed);
    if (score == null || score < 0) return new Score(0, 0, false);
    try {
      var rate =
          new BigDecimal(
              GameplayDefinitions.rogueConstants.get("Rogue_Explore_Score_To_Talent_Coin_Rate"));
      if (rate.signum() < 0) return new Score(score, 0, false);
      int coins =
          BigDecimal.valueOf(score).multiply(rate).setScale(0, RoundingMode.DOWN).intValueExact();
      return new Score(score, coins, true);
    } catch (RuntimeException e) {
      return new Score(score, 0, false);
    }
  }

  private RogueEconomy() {}
}
