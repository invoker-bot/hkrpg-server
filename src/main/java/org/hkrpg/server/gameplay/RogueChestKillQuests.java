package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;

/** Original 1/66 trotter goals observe accepted deaths of the exact authored monster list. */
public final class RogueChestKillQuests {
  private static final Map<Integer, Integer> GOALS = Map.of(4092320, 1, 4092321, 66);
  private static final Set<Integer> MONSTERS =
      Set.of(8002051, 8002052, 8002053, 8002060, 8002070, 8002080, 8002061, 8002071, 8002082);

  public static boolean belongs(QuestExcel definition) {
    if (definition == null) return false;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    return GOALS.containsKey(definition.getId())
        || rule != null && rule.isType("RogueKillChestMonster", 750);
  }

  private static FinishWayExcel rule(Player player, QuestExcel definition) {
    if (definition == null
        || GameData.getQuestExcelMap().get(definition.getId()) != definition
        || !GOALS.containsKey(definition.getId())
        || definition.getQuestType() != 1) return null;
    var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
    if (rule == null
        || rule.getId() != definition.getFinishWayID()
        || !rule.isType("RogueKillChestMonster", 750)
        || !"ListContain".equals(rule.getParamType())
        || rule.getProgress() != GOALS.get(definition.getId())
        || rule.getParamInt1() != 0
        || rule.getParamInt2() != 0
        || rule.getParamInt3() != 0
        || rule.getParamStr1() == null
        || !rule.getParamStr1().isBlank()
        || rule.getParamItemList() == null
        || !rule.getParamItemList().isEmpty()
        || rule.getParamIntList() == null
        || rule.getParamIntList().size() != MONSTERS.size()
        || !new HashSet<>(rule.getParamIntList()).equals(MONSTERS)) return null;
    return "FinishQuest".equals(definition.getUnlockType())
            && List.of(4000001).equals(definition.getUnlockParamList())
            && QuestCompletionRecords.completed(player, 4000001)
        ? rule
        : null;
  }

  private static OptionalInt count(Player player) {
    var aggregate = player.getGameplay().getRogueBattleKills();
    var details = player.getGameplay().getRogueBattleDeaths();
    return aggregate == null
            || !aggregate.valid()
            || details == null
            || !details.valid()
            || details.total() > aggregate.total()
        ? OptionalInt.empty()
        : details.count(MONSTERS);
  }

  public static boolean hasKills(Player player) {
    synchronized (player) {
      return count(player).orElse(0) > 0
          && GameData.getQuestExcelMap().values().stream().anyMatch(d -> rule(player, d) != null);
    }
  }

  public static boolean refresh(Player player) {
    synchronized (player) {
      var count = count(player);
      if (count.isEmpty()) return false;
      boolean changed = false;
      for (var definition : GameData.getQuestExcelMap().values()) {
        var rule = rule(player, definition);
        if (rule == null) continue;
        var state = player.getQuestStates().get(definition.getId());
        if (!MissionStateRecords.doing(state, definition.getId(), rule.getProgress())
            || state.getProgress() > count.getAsInt()) continue;
        changed |=
            state.advance(
                count.getAsInt() - state.getProgress(),
                rule.getProgress(),
                System.currentTimeMillis() / 1000);
      }
      return changed;
    }
  }

  public static QuestState state(Player player, int id) {
    synchronized (player) {
      var count = count(player);
      var rule = rule(player, GameData.getQuestExcelMap().get(id));
      if (count.isEmpty() || rule == null) return null;
      var state = player.getQuestStates().get(id);
      return (MissionStateRecords.doing(state, id, rule.getProgress())
                  || MissionStateRecords.completed(state, id, rule.getProgress()))
              && state.getProgress() <= count.getAsInt()
          ? state
          : null;
    }
  }

  private RogueChestKillQuests() {}
}
