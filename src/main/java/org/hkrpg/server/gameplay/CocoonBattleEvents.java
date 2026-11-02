package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.enums.StageType;

/** A bounded compatibility rule for the dormant counter observed in native 4.6 farming. */
final class CocoonBattleEvents {
  static boolean acceptsDormantElation(Battle battle, RogueResonance.Energy row) {
    // Fixed 4.6 BattleEventConfig identifies 70001 as TeamNeutral / Elation, not an avatar
    // summon or a Rogue resonance. Only the observed idle reading is supported here.
    if (row.eventId() != 70001 || row.current() != 0 || row.maximum() != 100000)
      return false;
    var player = battle.getPlayer();
    var session = player.getSession();
    if (session == null || !session.getProtocol().experimental() || player.getBattle() != battle
        || battle.getStage() == null || battle.getStage().getStageType() != StageType.Cocoon)
      return false;
    var cocoon = GameData.getCocoonExcelMap().get(battle.getCocoonId(), battle.getWorldLevel());
    int count = battle.getCocoonWave();
    if (cocoon == null || cocoon.getCocoonId() != battle.getCocoonId()
        || cocoon.getWorldLevel() != battle.getWorldLevel()
        || cocoon.getMappingInfoID() != battle.getMappingInfoId()
        || count <= 0 || count > cocoon.getMaxChallengeCnt()
        || cocoon.getStaminaCost() <= 0
        || (long) cocoon.getStaminaCost() * count != battle.getStaminaCost()
        || cocoon.getStageIDList() == null
        || !cocoon.getStageIDList().contains(battle.getStage().getId())
        || battle.getWaves().size() != count)
      return false;
    return battle.getWaves().stream().allMatch(wave -> wave.getStage() != null
        && wave.getStage().getStageType() == StageType.Cocoon
        && cocoon.getStageIDList().contains(wave.getStage().getId()));
  }

  private CocoonBattleEvents() {}
}
