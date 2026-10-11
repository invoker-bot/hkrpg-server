package emu.lunarcore.game.battle.skills;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.entity.GameEntity;
import java.util.List;

/** Keeps a source trigger's attack effects separate from its admitted-battle callback. */
public final class MazeSkillTriggerAttack extends MazeSkillAction {
  private final MazeSkill skill;
  private final boolean triggerBattle;
  private final boolean projectileCallback;
  private final List<MazeSkillAction> onAttack;
  private final List<MazeSkillAction> onBattle;

  public MazeSkillTriggerAttack(MazeSkill skill, boolean triggerBattle, boolean projectileCallback,
      List<MazeSkillAction> onAttack, List<MazeSkillAction> onBattle) {
    this.skill = java.util.Objects.requireNonNull(skill);
    this.triggerBattle = triggerBattle;
    this.projectileCallback = projectileCallback;
    this.onAttack = List.copyOf(onAttack);
    this.onBattle = List.copyOf(onBattle);
  }

  @Override
  public void prepareAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    if (projectileCallback) skill.applyMetadata(caster, null, triggerBattle);
    for (var action : onAttack) action.prepareAttack(caster, targets);
  }

  @Override
  public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    for (var action : onAttack) action.onAttack(caster, targets);
    if (triggerBattle) skill.deferBattle(caster, onBattle, targets);
  }

  @Override
  public void onCastHit(GameAvatar caster, List<? extends GameEntity> targets) {
    for (var action : onAttack) action.onCastHit(caster, targets);
  }
}
