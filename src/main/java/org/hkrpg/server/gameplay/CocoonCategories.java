package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.CocoonExcel;
import emu.lunarcore.data.excel.FinishWayExcel;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.util.*;

/** Finish-condition categories cross-checked against pinned mission and achievement text. */
public final class CocoonCategories {
  public record Victory(int category, int count) {}

  public static int category(CocoonExcel row) {
    if (row == null) return 0;
    return switch (Objects.toString(row.getCocoonType(), "")) {
      case "TYPE_NORMAL" -> 1;
      case "TYPE_WEEK" -> 2;
      case "TYPE_RELIC" -> 3;
      default -> 0;
    };
  }

  public static Optional<Victory> victory(Player player, Battle battle) {
    if (battle == null
        || battle.getPlayer() != player
        || player.getBattle() != battle
        || !battle.isSettling()
        || battle.getStats() == null
        || battle.getResult() != BattleEndStatus.BATTLE_END_WIN
        || battle.getStage() == null
        || battle.getStage().getStageType() != StageType.Cocoon
        || battle.getCocoonId() <= 0
        || battle.getCocoonWave() <= 0
        || battle.getWorldLevel() < 0) return Optional.empty();
    var row = GameData.getCocoonExcelMap().get(battle.getCocoonId(), battle.getWorldLevel());
    int category = category(row);
    if (category == 0
        || row.getCocoonId() != battle.getCocoonId()
        || row.getWorldLevel() != battle.getWorldLevel()
        || row.getStaminaCost() < 0
        || row.getMaxChallengeCnt() <= 0
        || battle.getCocoonWave() > row.getMaxChallengeCnt()
        || row.getStageIDList() == null
        || !row.getStageIDList().contains(battle.getStage().getId())
        || challenges(battle, row) != battle.getCocoonWave()
        || (long) row.getStaminaCost() * battle.getCocoonWave() != battle.getStaminaCost())
      return Optional.empty();
    return Optional.of(new Victory(category, battle.getCocoonWave()));
  }

  /** Each challenge can contain several monster waves, as actual relic stage 1043010 does. */
  private static int challenges(Battle battle, CocoonExcel row) {
    int count = 0, cursor = 0;
    var waves = battle.getWaves();
    while (cursor < waves.size()) {
      var stage = waves.get(cursor).getStage();
      if (stage == null
          || stage.getStageType() != StageType.Cocoon
          || !row.getStageIDList().contains(stage.getId())
          || stage.getMonsterWaves() == null
          || stage.getMonsterWaves().isEmpty()) return 0;
      var group = stage.getMonsterWaves();
      if (group.size() > waves.size() - cursor) return 0;
      for (int i = 0; i < group.size(); i++) {
        var wave = waves.get(cursor + i);
        if (wave.getStage() != stage
            || group.get(i) == null
            || !group.get(i).equals(wave.getMonsters())) return 0;
      }
      cursor += group.size();
      count++;
    }
    return count;
  }

  public static boolean categories(String comparison, List<Integer> wanted, int category) {
    return "ListContain".equals(comparison)
        && wanted != null
        && !wanted.isEmpty()
        && wanted.size() <= 3
        && wanted.stream().allMatch(v -> v != null && v >= 1 && v <= 3)
        && new HashSet<>(wanted).size() == wanted.size()
        && wanted.contains(category);
  }

  public static boolean supported(FinishWayExcel rule) {
    return rule != null
        && rule.isType("AnyCocoonFinish", 26)
        && rule.getProgress() > 0
        && rule.getParamInt1() == 0
        && rule.getParamInt2() == 0
        && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && rule.getParamItemList() != null
        && rule.getParamItemList().isEmpty()
        && rule.getParamIntList() != null
        && categories(
            rule.getParamType(),
            rule.getParamIntList(),
            rule.getParamIntList().isEmpty()
                ? 0
                : rule.getParamIntList().getFirst() == null
                    ? 0
                    : rule.getParamIntList().getFirst());
  }

  public static boolean matches(FinishWayExcel rule, int category) {
    return supported(rule) && categories(rule.getParamType(), rule.getParamIntList(), category);
  }

  private CocoonCategories() {}
}
