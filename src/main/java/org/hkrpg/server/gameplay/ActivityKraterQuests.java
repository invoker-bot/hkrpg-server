package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.time.Instant;
import java.util.*;

/** The original offering-level predicate uses a paid source receipt, including verified history. */
public final class ActivityKraterQuests {
  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return definition.getId() == KraterOfferingRules.QUEST
        || definition.getQuestType() == 3
            && rule != null
            && "OfferingLevel".equals(rule.getFinishType());
  }

  public static boolean refresh(Player p) {
    synchronized (p) {
      if (!ActivityAccess.unlocked(p, 30013)) return false;
      var rules = KraterOfferingRules.load();
      var state = p.getGameplay().getKraterOffering();
      return state.valid(rules, p) && state.refresh(rules, p, Instant.now());
    }
  }

  public static List<QuestState> states(Player p) {
    var state = state(p, KraterOfferingRules.QUEST);
    return state == null ? List.of() : List.of(state);
  }

  public static QuestState state(Player p, int id) {
    synchronized (p) {
      if (id != KraterOfferingRules.QUEST || !ActivityAccess.unlocked(p, 30013)) return null;
      var rules = KraterOfferingRules.load();
      var state = p.getGameplay().getKraterOffering();
      return state.valid(rules, p) && rules.window(p, Instant.now()) != null
          ? state.getQuest()
          : null;
    }
  }

  public static QuestState completionState(Player p, int id) {
    synchronized (p) {
      if (id != KraterOfferingRules.QUEST || !ActivityAccess.unlocked(p, 30013)) return null;
      var rules = KraterOfferingRules.load();
      var state = p.getGameplay().getKraterOffering();
      return state.valid(rules, p)
              && rules.unlocked(p)
              && ActivityAccess.unlocked(p, 30013)
              && MissionStateRecords.completed(state.getQuest(), id, 1)
          ? state.getQuest()
          : null;
    }
  }

  private ActivityKraterQuests() {}
}
