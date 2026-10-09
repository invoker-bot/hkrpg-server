package org.hkrpg.server.gameplay;

import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.SceneBattleInfoOuterClass.SceneBattleInfo;

/** Immutable finite source-2 event plan; ordinary player and trial levels are not changed. */
public final class StageEventDifficultyBattle extends Battle {
  private final StageEventDifficultySettings.Plan difficulty;

  public StageEventDifficultyBattle(Player player, StageEventDifficultySettings.Plan plan) {
    super(player, player.getCurrentLineup(), plan.stage());
    difficulty = plan;
    setWorldLevel(plan.world());
  }

  public boolean matches(Player player) {
    return difficulty.matches(player, this);
  }

  public int effectiveWorld() {
    return difficulty.world();
  }

  @Override
  public SceneBattleInfo toProto() {
    return super.toProto().setWorldLevel(effectiveWorld());
  }
}
