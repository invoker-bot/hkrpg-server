package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** Prepare a whole source-bound curio upgrade batch before mutating ownership or the counter. */
final class RogueAutomaticEnhancement {
  static List<Integer> select(Player player, int limit) {
    var history = player.getGameplay().getRogueEnhancements();
    var run = player.getGameplay().getRogue();
    if (history == null
        || !history.valid()
        || limit <= 0
        || limit > 100
        || run.getBlessings() == null) return null;
    var candidates = new ArrayList<Integer>();
    for (var owned : run.getBlessings().entrySet()) {
      if (owned.getKey() == null || owned.getKey() <= 0 || !Objects.equals(owned.getValue(), 1))
        continue;
      int id = owned.getKey();
      boolean supported = true;
      for (int level : List.of(1, 2)) {
        var maze = GameData.getMazeBuffExcelMap().get(id, level);
        supported &=
            maze != null
                && maze.getID() == id
                && maze.getLv() == level
                && GameplayDefinitions.blessings.stream()
                        .filter(
                            b ->
                                b != null
                                    && b.MazeBuffID() == id
                                    && b.MazeBuffLevel() == level
                                    && b.RogueVersion() == 1)
                        .count()
                    == 1;
      }
      if (supported) candidates.add(id);
    }
    Collections.sort(candidates);
    Collections.shuffle(
        candidates, new Random(Objects.hash(run.getRunId(), run.getSelectionId(), 20)));
    var selected = List.copyOf(candidates.subList(0, Math.min(limit, candidates.size())));
    return history.canRecordEnhancements(selected.size()) ? selected : null;
  }

  private RogueAutomaticEnhancement() {}
}
