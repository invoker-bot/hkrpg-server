package emu.lunarcore.game.battle.skills;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.util.List;

/** An explicit TargetFetchSummonUnit selector scoped to its actual caster. */
public final class MazeSkillDestroySummonUnit extends MazeSkillAction {
  private final int summonUnitId;

  public MazeSkillDestroySummonUnit(int summonUnitId) {
    if (summonUnitId <= 0) throw new IllegalArgumentException("Summon unit ID must be positive");
    this.summonUnitId = summonUnitId;
  }

  private void destroy(GameAvatar caster) {
    if (caster != null && caster.getOwner() != null && caster.getOwner().getScene() != null) {
      caster.getScene().removeSummonUnit(caster, summonUnitId);
    }
  }

  @Override
  public void onCast(GameAvatar caster, MotionInfo castPosition) {
    destroy(caster);
  }

  @Override
  public void onCastHit(GameAvatar caster, List<? extends GameEntity> entities) {
    destroy(caster);
  }

  @Override
  public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    destroy(caster);
  }

  @Override
  public void onBattle(GameAvatar caster, List<? extends GameEntity> targets, Battle battle) {
    destroy(caster);
  }
}
