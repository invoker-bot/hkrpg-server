package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.CocoonExcel;
import emu.lunarcore.data.excel.FinishWayExcel;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.Player;
import java.util.List;

/** Original golden/crimson daily categories, with literal values from 4.6 FarmTypeConfig. */
public final class CocoonFarmTypes {
  static int category(CocoonExcel row) {
    if (row == null || !"TYPE_NORMAL".equals(row.getCocoonType())) return 0;
    return switch (row.getFarmType() == null ? "" : row.getFarmType()) {
      case "COCOON3" -> 5;
      case "COCOON_AVATAR_EXP" -> 6;
      case "COCOON_EQUIPMENT_EXP" -> 7;
      case "COCOON_COIN" -> 8;
      default -> 0;
    };
  }

  public static boolean supported(FinishWayExcel rule) {
    return rule != null
        && "FarmTypeCocoonFinish".equals(rule.getFinishType())
        && rule.getProgress() == 1
        && "ListContain".equals(rule.getParamType())
        && rule.getParamInt1() == 0
        && rule.getParamInt2() == 0
        && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && (List.of(5).equals(rule.getParamIntList())
            || List.of(6, 7, 8).equals(rule.getParamIntList()))
        && rule.getParamItemList() != null
        && rule.getParamItemList().isEmpty()
        && !rule.isIsBackTrack();
  }

  /** Called after actual victory settlement, stamina debit and the existing drop delivery. */
  public static int victory(Player player, Battle battle) {
    if (CocoonCategories.victory(player, battle).isEmpty()) return 0;
    return category(GameData.getCocoonExcelMap().get(battle.getCocoonId(), battle.getWorldLevel()));
  }

  public static boolean matches(FinishWayExcel rule, int category) {
    return category >= 5
        && category <= 8
        && supported(rule)
        && rule.getParamIntList().contains(category);
  }

  private CocoonFarmTypes() {}
}
