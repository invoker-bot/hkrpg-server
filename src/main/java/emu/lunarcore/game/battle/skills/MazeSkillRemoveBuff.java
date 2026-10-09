package emu.lunarcore.game.battle.skills;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.util.List;

/** Removes a configured maze buff from the explicitly resolved caster. */
public final class MazeSkillRemoveBuff extends MazeSkillAction {
  private final int buffId;

  public MazeSkillRemoveBuff(int buffId) {
    if (buffId <= 0) throw new IllegalArgumentException("Maze buff ID must be positive");
    this.buffId = buffId;
  }

  @Override
  public void onCast(GameAvatar caster, MotionInfo castPosition) {
    caster.removeBuff(buffId);
  }

  @Override
  public void onCastHit(GameAvatar caster, List<? extends GameEntity> entities) {
    caster.removeBuff(buffId);
  }

  @Override
  public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    caster.removeBuff(buffId);
  }
}
