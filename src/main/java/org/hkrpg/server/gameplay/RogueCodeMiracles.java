package org.hkrpg.server.gameplay;

import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.Player;
import java.util.*;

/** Six pinned classic-universe code pairs; progress belongs to a prepared acquisition. */
final class RogueCodeMiracles {
  private static final Set<Integer> BROKEN = Set.of(45, 47, 49, 51, 53, 55);

  static boolean isBrokenCode(int id) {
    return BROKEN.contains(id);
  }

  static boolean isCode(int id) {
    return BROKEN.contains(id) || BROKEN.contains(id - 1);
  }

  static boolean oppositeOwned(GameplayState.RogueRun run, int id) {
    return isCode(id) && run.getMiracles().containsKey(isBrokenCode(id) ? id + 1 : id - 1);
  }

  static Map<Integer, String> prepare(GameplayState.RogueRun run, Battle battle) {
    var frozen = new HashMap<Integer, String>();
    for (int id : BROKEN)
      if (RogueMiracles.active(run, id) && battle.hasBuff(630968 + id))
        frozen.put(id, run.getMiracles().get(id).getAcquisitionId());
    return Map.copyOf(frozen);
  }

  record Progress(List<Integer> updated, Map<Integer, Integer> replaced) {
    void sync(Player player) {
      updated.forEach(id -> org.hkrpg.server.protocol.Universe46.syncMiracle(player, id, false, 0));
      replaced.forEach(
          (oldId, newId) ->
              org.hkrpg.server.protocol.Universe46.syncMiracleReplacement(player, oldId, newId));
    }
  }

  /**
   * Called under the player lock after the run's distinct, resource-bound win marker is accepted.
   */
  static Progress victory(GameplayState.RogueRun run, Map<Integer, String> frozen) {
    var updated = new ArrayList<Integer>();
    var replaced = new TreeMap<Integer, Integer>();
    for (int id : BROKEN) {
      var item = run.getMiracles().get(id);
      if (!RogueMiracles.active(run, id) || !item.getAcquisitionId().equals(frozen.get(id)))
        continue;
      if (item.getUseCount() == 2) {
        var repaired = new GameplayState.RogueMiracle(0, item.getCodeRepairParameters());
        run.getMiracles().remove(id);
        run.getMiracles().put(id + 1, repaired);
        replaced.put(id, id + 1);
      } else {
        item.consume();
        updated.add(id);
      }
    }
    return new Progress(List.copyOf(updated), Collections.unmodifiableMap(replaced));
  }

  private RogueCodeMiracles() {}
}
