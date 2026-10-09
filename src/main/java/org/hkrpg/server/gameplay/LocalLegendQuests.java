package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;

/** Observes source target quests only from valid, resource-bound Local Legend history. */
public final class LocalLegendQuests {
  // Original 4.6 FinishWay rows; retain ownership if a current rule is missing or malformed.
  private static final Set<Integer> SOURCE_IDS =
      Set.of(
          6072101, 6072102, 6072103, 6072104, 6072105, 6072106, 6072107, 6072108, 6072109, 6072110,
          6072113, 6072114, 6072117, 6072118, 6072121, 6072122, 6072125, 6072126, 6072129, 6072130,
          6072140, 6072141, 6072142, 6072143, 6072144);

  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return SOURCE_IDS.contains(definition.getId())
        || LocalLegendAchievementRules.belongs(definition)
        || rule != null && "LocalLegendFinishBattleTarget".equals(rule.getFinishType());
  }

  private static FinishWayExcel rule(QuestExcel definition) {
    if (definition == null
        || definition.getId() <= 0
        || GameData.getQuestExcelMap().get(definition.getId()) != definition
        || definition.getQuestType() != 1 && definition.getQuestType() != 3
        || !"AutoUnlock".equals(definition.getUnlockType())
        || definition.getUnlockParamList() == null
        || !definition.getUnlockParamList().isEmpty()) return null;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    if (rule == null
        || rule.getId() != definition.getFinishWayID()
        || !"LocalLegendFinishBattleTarget".equals(rule.getFinishType())
        || !"Equal".equals(rule.getParamType())
        || rule.getProgress() != 1
        || rule.getParamInt1() <= 0
        || rule.getParamInt2() != 0
        || rule.getParamInt3() != 0
        || rule.getParamStr1() == null
        || !rule.getParamStr1().isBlank()
        || rule.getParamIntList() == null
        || !rule.getParamIntList().isEmpty()
        || rule.getParamItemList() == null
        || !rule.getParamItemList().isEmpty()) return null;
    return LocalLegendCatalog.levels().stream()
            .flatMap(level -> level.goals().stream())
            .anyMatch(goal -> goal.id() == rule.getParamInt1())
        ? rule
        : null;
  }

  public static boolean refresh(Player player) {
    synchronized (player) {
      var history = player.getGameplay().getLocalLegendProgress();
      if (!history.valid()) return false;
      boolean changed = false;
      for (var definition : GameData.getQuestExcelMap().values()) {
        if (!belongs(definition)) continue;
        if (LocalLegendAchievementRules.belongs(definition)) {
          var rule = LocalLegendAchievementRules.rule(definition.getId());
          if (rule == null || !history.hasAchievement(rule.quest())) continue;
          var state = player.getQuestStates().get(rule.quest());
          if (MissionStateRecords.doing(state, rule.quest(), 1))
            changed |= state.advance(1, 1, System.currentTimeMillis() / 1000);
          continue;
        }
        var rule = rule(definition);
        if (rule == null || !history.hasTarget(rule.getParamInt1())) continue;
        var state = player.getQuestStates().get(definition.getId());
        if (state == null && definition.getQuestType() == 3) {
          state = new QuestState(definition.getId());
          player.getQuestStates().put(definition.getId(), state);
          changed = true;
        }
        if (MissionStateRecords.doing(state, definition.getId(), 1))
          changed |= state.advance(1, 1, System.currentTimeMillis() / 1000);
      }
      return changed;
    }
  }

  public static QuestState state(Player player, int id) {
    synchronized (player) {
      var definition = GameData.getQuestExcelMap().get(id);
      if (LocalLegendAchievementRules.belongs(definition)) {
        var rule = LocalLegendAchievementRules.rule(id);
        var history = player.getGameplay().getLocalLegendProgress();
        if (rule == null || !history.valid()) return null;
        var state = player.getQuestStates().get(id);
        return (MissionStateRecords.doing(state, id, 1)
                    || MissionStateRecords.completed(state, id, 1))
                && state.getProgress() <= (history.hasAchievement(id) ? 1 : 0)
            ? state
            : null;
      }
      var rule = rule(definition);
      var history = player.getGameplay().getLocalLegendProgress();
      if (rule == null || !history.valid()) return null;
      var state = player.getQuestStates().get(id);
      return (MissionStateRecords.doing(state, id, 1)
                  || MissionStateRecords.completed(state, id, 1))
              && state.getProgress() <= (history.hasTarget(rule.getParamInt1()) ? 1 : 0)
          ? state
          : null;
    }
  }

  public static List<QuestState> activityStates(Player player) {
    synchronized (player) {
      return GameData.getQuestExcelMap().values().stream()
          .filter(d -> d.getQuestType() == 3 && belongs(d))
          .map(d -> state(player, d.getId()))
          .filter(Objects::nonNull)
          .sorted(Comparator.comparingInt(QuestState::getQuestId))
          .toList();
    }
  }

  private LocalLegendQuests() {}
}
