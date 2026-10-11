package emu.lunarcore.game.battle.skills;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.entity.GameEntity;
import java.util.List;

/** Native event masks select their callback lists; an untagged cast executes neither. */
public final class MazeSkillProjectileCallbacks extends MazeSkillAction {
  private final MazeSkill skill;
  private final List<MazeSkillAction> hit;
  private final List<MazeSkillAction> finish;
  private final List<MazeSkillAction> combined;

  public MazeSkillProjectileCallbacks(MazeSkill skill, List<MazeSkillAction> hit, List<MazeSkillAction> finish) {
    this.skill = java.util.Objects.requireNonNull(skill);
    this.hit = List.copyOf(hit);
    this.finish = List.copyOf(finish);
    var actions = new java.util.LinkedHashSet<MazeSkillAction>(hit);
    actions.addAll(finish);
    this.combined = List.copyOf(actions);
  }

  private List<MazeSkillAction> select(GameAvatar caster) {
    return switch (skill.callbackPhase(caster)) {
      case DIRECT -> List.of();
      case COMBINED -> combined;
      case PROJECTILE_HIT -> hit;
      case PROJECTILE_LIFETIME_FINISH -> finish;
    };
  }

  @Override
  public void prepareAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    for (var action : select(caster)) action.prepareAttack(caster, targets);
  }

  @Override
  public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    for (var action : select(caster)) action.onAttack(caster, targets);
  }

  @Override
  public void onCastHit(GameAvatar caster, List<? extends GameEntity> targets) {
    for (var action : select(caster)) action.onCastHit(caster, targets);
  }
}
