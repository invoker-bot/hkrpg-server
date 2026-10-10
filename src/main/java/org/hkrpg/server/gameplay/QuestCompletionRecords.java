package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;

/** Completion and reward closure use the same source-validated owning domain. */
public final class QuestCompletionRecords {
  public static boolean completed(Player player, int id) {
    synchronized (player) {
      return completedState(player, id) != null;
    }
  }

  public static boolean closed(Player player, int id) {
    synchronized (player) {
      var state = completedState(player, id);
      return state != null && state.getPhase() == QuestState.Phase.CLOSED;
    }
  }

  private static QuestState completedState(Player player, int id) {
    var definition = GameData.getQuestExcelMap().get(id);
    if (id <= 0
        || definition == null
        || definition.getId() != id
        || definition.getQuestType() != 1
            && definition.getQuestType() != 4
            && !(definition.getQuestType() == 3
                && (BoxingClubActivityQuests.belongs(definition)
                    || TimelineActivityQuests.belongs(definition)))) return null;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    var state = ownedState(player, definition);
    return rule != null
            && rule.getId() == definition.getFinishWayID()
            && MissionStateRecords.completed(state, id, rule.getProgress())
        ? state
        : null;
  }

  private static QuestState ownedState(Player player, QuestExcel definition) {
    int id = definition.getId();
    if (LocalLegendQuests.belongs(definition)) return LocalLegendQuests.state(player, id);
    if (DiceRollQuests.belongs(definition)) return DiceRollQuests.state(player, id);
    if (BoxingClubActivityQuests.belongs(definition))
      return BoxingClubActivityQuests.completionState(player, id);
    if (TimelineActivityQuests.belongs(definition))
      return TimelineActivityQuests.completionState(player, id);
    if (BoxingClubResidentQuests.belongs(definition))
      return BoxingClubResidentQuests.state(player, id);
    return player.getQuestStates().get(id);
  }

  private QuestCompletionRecords() {}
}
