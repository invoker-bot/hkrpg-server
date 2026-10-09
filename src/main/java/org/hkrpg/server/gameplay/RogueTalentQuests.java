package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;

/** Permanent source achievements count distinct actually owned talents from their explicit list. */
public final class RogueTalentQuests {
  private static final Set<Integer> SOURCE_IDS = Set.of(4092316, 4092317);

  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return SOURCE_IDS.contains(definition.getId())
        || rule != null && rule.isType("RogueTalentEnable", 726);
  }

  private static FinishWayExcel rule(Player player, QuestExcel definition) {
    if (definition == null
        || definition.getId() <= 0
        || GameData.getQuestExcelMap().get(definition.getId()) != definition
        || definition.getQuestType() != 1 && definition.getQuestType() != 4) return null;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    if (rule == null
        || rule.getId() != definition.getFinishWayID()
        || !rule.isType("RogueTalentEnable", 726)
        || !"ListContain".equals(rule.getParamType())
        || rule.getProgress() <= 0
        || rule.getParamInt1() != 0
        || rule.getParamInt2() != 0
        || rule.getParamInt3() != 0
        || rule.getParamStr1() == null
        || !rule.getParamStr1().isBlank()
        || rule.getParamItemList() == null
        || !rule.getParamItemList().isEmpty()) return null;
    var ids = rule.getParamIntList();
    if (ids == null
        || ids.isEmpty()
        || ids.size() > 256
        || rule.getProgress() > ids.size()
        || new HashSet<>(ids).size() != ids.size()) return null;
    for (Integer id : ids) {
      var talent = id == null ? null : GameplayDefinitions.rogueTalents.get(id);
      if (id == null || id <= 0 || talent == null || talent.TalentID() != id) return null;
    }
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

  private static boolean historyValid(Player player) {
    var history = player.getGameplay().getRogueTalents();
    return history != null
        && history.size() <= 4096
        && history.stream().allMatch(id -> id != null && id > 0);
  }

  private static int count(Player player, FinishWayExcel rule) {
    return (int)
        rule.getParamIntList().stream()
            .filter(player.getGameplay().getRogueTalents()::contains)
            .count();
  }

  public static boolean refresh(Player player) {
    synchronized (player) {
      if (!historyValid(player)) return false;
      boolean changed = false;
      for (var definition : GameData.getQuestExcelMap().values()) {
        var rule = rule(player, definition);
        if (rule == null) continue;
        var state = player.getQuestStates().get(definition.getId());
        if (!MissionStateRecords.doing(state, definition.getId(), rule.getProgress())) continue;
        int actual = count(player, rule);
        if (state.getProgress() > actual) continue;
        changed |=
            state.advance(
                actual - state.getProgress(),
                rule.getProgress(),
                System.currentTimeMillis() / 1000);
      }
      return changed;
    }
  }

  public static QuestState state(Player player, int id) {
    synchronized (player) {
      var definition = GameData.getQuestExcelMap().get(id);
      var rule = rule(player, definition);
      if (rule == null || !historyValid(player)) return null;
      var state = player.getQuestStates().get(id);
      return (MissionStateRecords.doing(state, id, rule.getProgress())
                  || MissionStateRecords.completed(state, id, rule.getProgress()))
              && state.getProgress() <= count(player, rule)
          ? state
          : null;
    }
  }

  private RogueTalentQuests() {}
}
