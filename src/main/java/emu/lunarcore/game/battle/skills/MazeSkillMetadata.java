package emu.lunarcore.game.battle.skills;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.util.List;

/** Legacy per-cast markers follow the executed task order; this is not a modifier lifecycle. */
public final class MazeSkillMetadata extends MazeSkillAction {
  private final MazeSkill skill;
  private final String modifier;
  private final Boolean battle;
  private final boolean remove;

  public MazeSkillMetadata(MazeSkill skill, String modifier) {
    this(skill, modifier, false);
  }

  private MazeSkillMetadata(MazeSkill skill, String modifier, boolean remove) {
    this.skill = java.util.Objects.requireNonNull(skill);
    this.modifier = java.util.Objects.requireNonNull(modifier);
    this.battle = null;
    this.remove = remove;
  }

  public static MazeSkillMetadata removeModifier(MazeSkill skill, String modifier) {
    return new MazeSkillMetadata(skill, modifier, true);
  }

  public MazeSkillMetadata(MazeSkill skill, boolean battle) {
    this.skill = java.util.Objects.requireNonNull(skill);
    this.modifier = null;
    this.battle = battle;
    this.remove = false;
  }

  private void apply(GameAvatar caster) {
    if (remove) skill.removeMetadata(caster, modifier);
    else skill.applyMetadata(caster, modifier, battle);
  }

  @Override
  public void onCast(GameAvatar caster, MotionInfo position) {
    apply(caster);
  }

  @Override
  public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    apply(caster);
  }

  @Override
  public void onCastHit(GameAvatar caster, List<? extends GameEntity> targets) {
    apply(caster);
  }
}
