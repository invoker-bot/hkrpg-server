package emu.lunarcore.game.battle.skills;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.util.List;

/** Explicit trigger switches scoped to the actual caster and source summon ID. */
public final class MazeSkillSetSummonTrigger extends MazeSkillAction {
  private final int summonId;
  private final List<String> names;
  private final boolean enabled;

  public MazeSkillSetSummonTrigger(int summonId, List<String> names, boolean enabled) {
    this.summonId = summonId;
    this.names = names == null ? List.of() : List.copyOf(names);
    this.enabled = enabled;
  }

  @Override
  public void onCast(GameAvatar caster, MotionInfo position) {
    if (caster.getScene() != null)
      caster.getScene().setSummonUnitTriggers(caster, summonId, names, enabled);
  }

  @Override
  public void onCastHit(GameAvatar caster, List<? extends GameEntity> targets) {
    onCast(caster, null);
  }

  @Override
  public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    onCast(caster, null);
  }
}
