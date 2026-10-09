package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;

/** Original 4.6 battle death goals; victories and wave sizes cannot reconstruct reported kills. */
public final class RogueBattleKillQuests {
  private static final Map<Integer, Integer> SOURCE_GOALS = Map.of(4090301, 15, 4090303, 1000);

  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return SOURCE_GOALS.containsKey(definition.getId())
        || rule != null && rule.isType("RogueKillBattleMonster", 752);
  }

  private static FinishWayExcel rule(Player player, QuestExcel definition) {
    if (definition == null
        || GameData.getQuestExcelMap().get(definition.getId()) != definition
        || !SOURCE_GOALS.containsKey(definition.getId())
        || definition.getQuestType() != 1 && definition.getQuestType() != 4) return null;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    if (rule == null
        || rule.getId() != definition.getFinishWayID()
        || !rule.isType("RogueKillBattleMonster", 752)
        || !"NoPara".equals(rule.getParamType())
        || rule.getProgress() != SOURCE_GOALS.get(definition.getId())
        || rule.getParamInt1() != 0
        || rule.getParamInt2() != 0
        || rule.getParamInt3() != 0
        || rule.getParamStr1() == null
        || !rule.getParamStr1().isBlank()
        || rule.getParamItemList() == null
        || !rule.getParamItemList().isEmpty()
        || rule.getParamIntList() == null
        || !rule.getParamIntList().isEmpty()) return null;
    var prerequisites = definition.getUnlockParamList();
    if (prerequisites == null
        || prerequisites.size() > 256
        || new HashSet<>(prerequisites).size() != prerequisites.size()
        || prerequisites.stream().anyMatch(id -> id == null || id <= 0)) return null;
    boolean unlocked =
        "AutoUnlock".equals(definition.getUnlockType()) && prerequisites.isEmpty()
            || "FinishQuest".equals(definition.getUnlockType())
                && !prerequisites.isEmpty()
                && prerequisites.stream()
                    .allMatch(id -> QuestCompletionRecords.completed(player, id));
    return unlocked ? rule : null;
  }

  private static RogueBattleKillState history(Player player) {
    var state = player.getGameplay().getRogueBattleKills();
    return state != null && state.valid() ? state : null;
  }

  public static boolean hasKills(Player player) {
    synchronized (player) {
      var history = history(player);
      return history != null
          && history.total() > 0
          && GameData.getQuestExcelMap().values().stream().anyMatch(d -> rule(player, d) != null);
    }
  }

  public static boolean refresh(Player player) {
    synchronized (player) {
      var history = history(player);
      if (history == null) return false;
      boolean changed = false;
      for (var definition : GameData.getQuestExcelMap().values()) {
        var rule = rule(player, definition);
        if (rule == null) continue;
        var state = player.getQuestStates().get(definition.getId());
        if (!MissionStateRecords.doing(state, definition.getId(), rule.getProgress())
            || state.getProgress() > history.total()) continue;
        changed |=
            state.advance(
                history.total() - state.getProgress(),
                rule.getProgress(),
                System.currentTimeMillis() / 1000);
      }
      return changed;
    }
  }

  public static QuestState state(Player player, int id) {
    synchronized (player) {
      var history = history(player);
      var rule = rule(player, GameData.getQuestExcelMap().get(id));
      if (history == null || rule == null) return null;
      var state = player.getQuestStates().get(id);
      return (MissionStateRecords.doing(state, id, rule.getProgress())
                  || MissionStateRecords.completed(state, id, rule.getProgress()))
              && state.getProgress() <= history.total()
          ? state
          : null;
    }
  }

  private RogueBattleKillQuests() {}
}
