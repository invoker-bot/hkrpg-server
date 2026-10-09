package org.hkrpg.server.gameplay;

import java.util.*;

/** Ten source OnLeaveBattle/ByLocalWin target leaves; the Hard dynamic conditions stay separate. */
final class LocalLegendWinnerTargets {
  private record Binding(int id, int group, int difficulty, String ability) {}

  private static final List<Binding> SOURCE =
      List.of(
          new Binding(5001804, 1, 1, "BattleTarget_LocalLegend_S1_4"),
          new Binding(5001805, 1, 3, "BattleTarget_LocalLegend_S1_5"),
          new Binding(5001809, 2, 1, "BattleTarget_LocalLegend_S2_4"),
          new Binding(5001810, 2, 3, "BattleTarget_LocalLegend_S2_5"),
          new Binding(5001814, 3, 1, "BattleTarget_LocalLegend_S3_4"),
          new Binding(5001815, 3, 3, "BattleTarget_LocalLegend_S3_5"),
          new Binding(5001819, 4, 1, "BattleTarget_LocalLegend_S4_4"),
          new Binding(5001820, 4, 3, "BattleTarget_LocalLegend_S4_5"),
          new Binding(5001824, 5, 1, "BattleTarget_LocalLegend_S5_4"),
          new Binding(5001825, 5, 3, "BattleTarget_LocalLegend_S5_5"));

  static boolean valid(LocalLegendCatalog.Level level) {
    int matches = 0;
    for (var goal : level.goals()) {
      for (var binding : SOURCE) {
        if (goal.id() != binding.id() && !goal.ability().equals(binding.ability())) continue;
        if (goal.id() != binding.id()
            || !goal.ability().equals(binding.ability())
            || level.group() != binding.group()
            || level.difficulty() != binding.difficulty()
            || goal.threshold() != 1) return false;
        matches++;
      }
    }
    return matches
        == (SOURCE.stream()
                .anyMatch(b -> b.group() == level.group() && b.difficulty() == level.difficulty())
            ? 1
            : 0);
  }

  static OptionalInt mask(LocalLegendCatalog.Level level) {
    if (LocalLegendCatalog.level(level.group(), level.difficulty()) != level || !valid(level))
      return OptionalInt.empty();
    int mask = 0;
    for (int i = 0; i < level.goals().size(); i++) {
      var goal = level.goals().get(i);
      if (SOURCE.stream().anyMatch(b -> b.id() == goal.id())) mask |= 1 << i;
    }
    return OptionalInt.of(mask);
  }

  private LocalLegendWinnerTargets() {}
}
