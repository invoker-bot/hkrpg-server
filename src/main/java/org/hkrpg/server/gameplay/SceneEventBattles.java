package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.enums.StageType;

/** Exact event/world lookup. Internal scripted direct-stage entry remains in BattleService. */
public final class SceneEventBattles {
  public static StageExcel resolve(int eventId, int world) {
    if (eventId <= 0 || world < 0) return null;
    var event = GameData.getPlaneEventExcelMap().get(eventId, world);
    if (event == null
        || event.getEventId() != eventId
        || event.getWorldLevel() != world
        || event.getStageId() <= 0) return null;
    var stage = GameData.getStageExcelMap().get(event.getStageId());
    if (stage == null
        || stage.getId() != event.getStageId()
        || stage.getMonsterWaves() == null
        || stage.getMonsterWaves().isEmpty()
        || stage.getMonsterWaves().stream()
            .anyMatch(
                wave -> wave == null || wave.isEmpty() || wave.intStream().anyMatch(id -> id <= 0)))
      return null;
    return stage;
  }

  /** Local Legend uses its dedicated entry with activity, difficulty and party context. */
  public static StageExcel resolveClientEvent(int eventId, int world) {
    var stage = resolve(eventId, world);
    return stage != null && stage.getStageType() != StageType.LocalLegend ? stage : null;
  }

  private SceneEventBattles() {}
}
