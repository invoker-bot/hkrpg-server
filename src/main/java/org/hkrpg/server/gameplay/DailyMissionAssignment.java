package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.time.Instant;
import java.util.*;
import java.util.function.IntPredicate;

/** Stable player/day group selection; the accepted result is saved with the actual mission view. */
final class DailyMissionAssignment {
  static boolean assign(Player player, Instant now, IntPredicate eligible) {
    if (!DailyMissionCatalog.configured() || !DailyMissionRepeats.historyValid(player, now))
      return false;
    boolean changed = DailyMissionExpiry.expire(player, now);
    var choices = new TreeMap<Integer, List<Integer>>();
    for (var row : DailyMissionCatalog.missions()) {
      // Ambiguous or unsupported unfinished views keep blocking another automatic assignment.
      if (MissionStateRecords.mainDoing(player, row.ID())) return changed;
      var quest = GameData.getQuestExcelMap().get(row.QuestID());
      if (quest == null
          || quest.getUnlockType() == null
          || quest.getUnlockParamList() == null
          || !player.getDailyManager().unlocked(quest)
          || !DailyMissionRepeats.supportedGraph(row.ID())
          || !eligible.test(row.ID())) continue;
      choices.computeIfAbsent(row.GroupID(), ignored -> new ArrayList<>()).add(row.ID());
    }
    var random =
        new SplittableRandom(
            DailyManager.day(now) ^ ((long) player.getUid() * 0x9e3779b97f4a7c15L));
    var groups = new ArrayList<>(choices.keySet());
    while (!groups.isEmpty()) {
      int group = groups.remove(random.nextInt(groups.size()));
      var ids = choices.get(group);
      ids.sort(Integer::compareTo);
      while (!ids.isEmpty()) {
        int id = ids.remove(random.nextInt(ids.size()));
        var state = player.getGameplay().getMainMissions().get(id);
        if (state != null) {
          if (DailyMissionRepeats.reopen(player, id, now)) return true;
          continue;
        }
        if (!DailyCommissionAcceptance.record(player, id, now)) continue;
        player.getGameplay().getMainMissions().put(id, new QuestState(id));
        return true;
      }
    }
    return changed;
  }

  private DailyMissionAssignment() {}
}
