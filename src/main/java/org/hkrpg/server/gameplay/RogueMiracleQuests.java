package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;

/** Permanent source achievements count actual canonical classic-universe handbook collections. */
public final class RogueMiracleQuests {
  private static final Set<Integer> SOURCE_IDS = Set.of(4092318, 4092319);

  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return SOURCE_IDS.contains(definition.getId())
        || rule != null && rule.isType("RogueMiracleUnlock", 733);
  }

  private static FinishWayExcel rule(Player player, QuestExcel definition) {
    if (definition == null
        || definition.getId() <= 0
        || GameData.getQuestExcelMap().get(definition.getId()) != definition
        || definition.getQuestType() != 1 && definition.getQuestType() != 4) return null;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    if (rule == null
        || rule.getId() != definition.getFinishWayID()
        || !rule.isType("RogueMiracleUnlock", 733)
        || !"NoPara".equals(rule.getParamType())
        || rule.getProgress() <= 0
        || rule.getParamInt1() != 0
        || rule.getParamInt2() != 0
        || rule.getParamInt3() != 0
        || rule.getParamStr1() == null
        || !rule.getParamStr1().isBlank()
        || rule.getParamItemList() == null
        || !rule.getParamItemList().isEmpty()) return null;
    if (rule.getParamIntList() == null
        || !rule.getParamIntList().isEmpty()
        || rule.getProgress() > eligible().size()) return null;
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
    var history = player.getGameplay().getRogueHandbook();
    return history != null && history.valid();
  }

  private static Set<Integer> eligible() {
    var ids = new HashSet<Integer>();
    GameplayDefinitions.miracles.forEach(
        (id, source) -> {
          if (id != null && id > 0 && source != null && source.MiracleID() == id) {
            int handbook = RogueHandbookCatalog.unlock(id);
            if (handbook > 0) ids.add(handbook);
          }
        });
    return ids;
  }

  private static int count(Player player) {
    return (int)
        eligible().stream()
            .filter(player.getGameplay().getRogueHandbook().getMiracles()::contains)
            .count();
  }

  public static boolean hasCollection(Player player) {
    synchronized (player) {
      return historyValid(player)
          && count(player) > 0
          && GameData.getQuestExcelMap().values().stream().anyMatch(d -> rule(player, d) != null);
    }
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
        int actual = count(player);
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
              && state.getProgress() <= count(player)
          ? state
          : null;
    }
  }

  private RogueMiracleQuests() {}
}
