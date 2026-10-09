package org.hkrpg.server.gameplay;

import emu.lunarcore.data.excel.StageExcel;
import java.util.*;
import java.util.stream.Collectors;

/** Matches the original stage's declared target order before allocating a battle. */
final class LocalLegendStageBinding {
  static String identity(LocalLegendCatalog.Level level, StageExcel stage) {
    if (stage == null
        || stage.getStageAbilityConfig() == null
        || !stage.getStageAbilityConfig().equals(List.of("StageAbility_SetFullHPandHalfSP"))
        || stage.getStageConfigData() == null
        || stage.getStageConfigData().size() != 4
        || stage.getMonsterWaves() == null
        || stage.getMonsterWaves().isEmpty()
        || stage.getMonsterWaves().size() > 32) return null;
    var settings = new TreeMap<String, String>();
    for (var row : stage.getStageConfigData()) {
      if (row == null || row.size() != 2) return null;
      var key = row.get("BFLIFKBEOPJ");
      var value = row.get("MNDFOPKBHKP");
      if (key == null
          || value == null
          || !key.isJsonPrimitive()
          || !value.isJsonPrimitive()
          || !key.getAsJsonPrimitive().isString()
          || !value.getAsJsonPrimitive().isString()
          || settings.putIfAbsent(key.getAsString(), value.getAsString()) != null) return null;
    }
    String goals =
        level.goals().stream().map(g -> Integer.toString(g.id())).collect(Collectors.joining("|"));
    if (!"1".equals(settings.get("_Wave"))
        || !"0".equals(settings.get("_IsEliteBattle"))
        || !goals.equals(settings.get("_BattleTarget"))
        || !"20037".equals(settings.get("_CreateBattleEvent"))) return null;
    for (var wave : stage.getMonsterWaves())
      if (wave == null
          || wave.isEmpty()
          || wave.size() > 5
          || wave.stream().anyMatch(id -> id == null || id <= 0)) return null;
    return stage.getId()
        + ":"
        + stage.getStageType()
        + ":"
        + stage.getLevel()
        + ":"
        + stage.getStageAbilityConfig()
        + ":"
        + settings
        + ":"
        + stage.isForbidExitBattle()
        + ":"
        + stage.getMonsterWaves();
  }

  private LocalLegendStageBinding() {}
}
