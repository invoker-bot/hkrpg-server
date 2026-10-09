package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;

/** Ordinary quest completion needs its own current goal and an intact saved record. */
public final class QuestCompletionRecords {
  public static boolean completed(Player player, int id) {
    synchronized (player) {
      var definition = GameData.getQuestExcelMap().get(id);
      if (id <= 0
          || definition == null
          || definition.getId() != id
          || definition.getQuestType() != 1 && definition.getQuestType() != 4) return false;
      var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
      var state = LocalLegendQuests.belongs(definition)
          ? LocalLegendQuests.state(player, id) : DiceRollQuests.belongs(definition)
              ? DiceRollQuests.state(player, id) : BoxingClubResidentQuests.belongs(definition)
                  ? BoxingClubResidentQuests.state(player, id) : player.getQuestStates().get(id);
      return rule != null
          && rule.getId() == definition.getFinishWayID()
          && MissionStateRecords.completed(state, id, rule.getProgress());
    }
  }

  private QuestCompletionRecords() {}
}
