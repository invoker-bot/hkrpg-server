package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.player.Player;
import java.nio.file.Path;
import java.util.*;

/** Modern hourly harvest rows; legacy duration/avatar rewards are a separate system. */
public final class ExpeditionCatalog {
  public record Gate(String Type, String Param) {}

  public record Harvest(int ExpeditionID, int RewardID, List<Gate> UnlockCondition) {}

  public record Team(int TeamID, int UnlockMission) {}

  public static final Map<Integer, Harvest> harvests = new HashMap<>();
  public static final Map<Integer, Team> teams = new HashMap<>();
  public static final Map<String, GameplayDefinitions.ConstantValue> constants = new HashMap<>();

  public static void load(Path root) {
    harvests.clear();
    teams.clear();
    constants.clear();
    GameplayDefinitions.rows(
        root,
        "ExpeditionHarvestData",
        Harvest.class,
        r -> {
          if (harvests.putIfAbsent(r.ExpeditionID(), r) != null)
            throw new IllegalArgumentException("Duplicate harvest");
        });
    GameplayDefinitions.rows(
        root,
        "ExpeditionTeam",
        Team.class,
        r -> {
          if (teams.putIfAbsent(r.TeamID(), r) != null)
            throw new IllegalArgumentException("Duplicate expedition team");
        });
    GameplayDefinitions.rows(
        root,
        "ConstValueCommon",
        GameplayDefinitions.CommonConstant.class,
        r -> {
          if (r.ConstValueName().startsWith("Expedition_Harvest_")) {
            if (r.Value() == null || !r.Value().has("IntValue")) return;
            constants.put(
                r.ConstValueName(),
                new GameplayDefinitions.ConstantValue(
                    r.Value().get("IntValue").getAsBigDecimal().intValueExact()));
          }
        });
  }

  public static int maxHours() {
    var row = constants.get("Expedition_Harvest_Store_Max_Hours");
    return row != null && row.IntValue() == 30 ? row.IntValue() : 0;
  }

  public static int teamCount(Player player) {
    int count = 0;
    for (int id = 1; id <= 4; id++) {
      var row = teams.get(id);
      if (row == null || row.TeamID() != id || row.UnlockMission() < 0) return 0;
      if (row.UnlockMission() == 0 || player.getMissionManager().finished(row.UnlockMission()))
        count++;
    }
    return count;
  }

  public static boolean unlocked(Player player, Harvest row) {
    if (row == null
        || row.ExpeditionID() <= 0
        || row.RewardID() <= 0
        || row.UnlockCondition() == null
        || row.UnlockCondition().isEmpty()) return false;
    for (var gate : row.UnlockCondition()) {
      if (gate == null || !"FinishMainMission".equals(gate.Type())) return false;
      try {
        int id = Integer.parseInt(gate.Param());
        if (id <= 0
            || !Integer.toString(id).equals(gate.Param())
            || !player.getMissionManager().finished(id)) return false;
      } catch (RuntimeException invalid) {
        return false;
      }
    }
    return true;
  }

  public static Map<Integer, Integer> rates(Harvest row) {
    var reward = row == null ? null : GameData.getRewardExcelMap().get(row.RewardID());
    if (reward == null || reward.getRewards() == null || reward.getRewards().isEmpty()) return null;
    var result = new TreeMap<Integer, Integer>();
    for (var item : reward.getRewards()) {
      if (!validItem(item.getIntKey()) || item.getIntValue() <= 0) return null;
      result.put(item.getIntKey(), item.getIntValue());
    }
    return Collections.unmodifiableMap(result);
  }

  static boolean validItem(int id) {
    var row = GameData.getItemExcelMap().get(id);
    return row != null
        && row.getId() == id
        && row.getPileLimit() > 0
        && (row.getItemMainType() == ItemMainType.Material && Inventory.isStackType(row)
            || id == 2 && row.getItemMainType() == ItemMainType.Virtual);
  }

  private ExpeditionCatalog() {}
}
