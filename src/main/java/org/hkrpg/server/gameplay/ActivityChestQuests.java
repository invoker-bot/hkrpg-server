package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.time.Instant;
import java.util.*;

/** Source-bound world and time-flame goals consume successful new chest openings only. */
public final class ActivityChestQuests {
  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return WorldChestState.goal(definition.getId()) > 0
        || definition.getQuestType() == 3
            && rule != null
            && "TreasureChestOpenWithWorld".equals(rule.getFinishType());
  }

  public static boolean refresh(Player p) {
    synchronized (p) {
      var rules = WorldChestRules.load();
      var state = p.getGameplay().getWorldChests();
      return state.valid(rules, p) && rules.window(p, Instant.now()) != null && state.accept(rules);
    }
  }

  public static List<QuestState> states(Player p) {
    var out = new ArrayList<QuestState>();
    for (int id : List.of(6029850, 6029851, 6029852, TimeRelayFlameSources.GOAL)) {
      var q = state(p, id);
      if (q != null) out.add(q);
    }
    return out;
  }

  public static QuestState state(Player p, int id) {
    synchronized (p) {
      if (WorldChestState.goal(id) < 1) return null;
      var rules = WorldChestRules.load();
      var state = p.getGameplay().getWorldChests();
      return state.valid(rules, p) && rules.window(p, Instant.now()) != null
          ? state.getQuests().get(id)
          : null;
    }
  }

  public static QuestState completionState(Player p, int id) {
    synchronized (p) {
      if (WorldChestState.goal(id) < 1) return null;
      var rules = WorldChestRules.load();
      var state = p.getGameplay().getWorldChests();
      return state.valid(rules, p)
              && rules.unlocked(p)
              && MissionStateRecords.completed(
                  state.getQuests().get(id), id, WorldChestState.goal(id))
          ? state.getQuests().get(id)
          : null;
    }
  }

  private ActivityChestQuests() {}
}
