package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.QuestExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.time.Instant;
import java.util.*;

/** The original activity goal consumes a new source observation, never a legacy seen bit. */
public final class ActivityFirstTalkQuests {
  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return definition.getId() == WorldFirstTalkRules.QUEST
        || definition.getQuestType() == 3
            && rule != null
            && "FinishFirstTalkPerformance".equals(rule.getFinishType());
  }

  public static boolean refresh(Player p) {
    synchronized (p) {
      var rules = WorldFirstTalkRules.load();
      var domain = p.getGameplay().getWorldFirstTalks();
      return domain.valid(rules) && rules.window(p, Instant.now()) != null && domain.accept();
    }
  }

  public static List<QuestState> states(Player p) {
    var q = state(p, WorldFirstTalkRules.QUEST);
    return q == null ? List.of() : List.of(q);
  }

  public static QuestState state(Player p, int id) {
    synchronized (p) {
      if (id != WorldFirstTalkRules.QUEST) return null;
      var rules = WorldFirstTalkRules.load();
      var domain = p.getGameplay().getWorldFirstTalks();
      return domain.valid(rules) && rules.window(p, Instant.now()) != null
          ? domain.getActivity()
          : null;
    }
  }

  public static QuestState completionState(Player p, int id) {
    synchronized (p) {
      if (id != WorldFirstTalkRules.QUEST) return null;
      var rules = WorldFirstTalkRules.load();
      var domain = p.getGameplay().getWorldFirstTalks();
      return domain.valid(rules)
              && rules.unlocked(p)
              && MissionStateRecords.completed(domain.getActivity(), id, 1)
          ? domain.getActivity()
          : null;
    }
  }

  private ActivityFirstTalkQuests() {}
}
