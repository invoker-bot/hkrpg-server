package emu.lunarcore.game.battle.skills;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.entity.GameEntity;
import java.util.List;

/** The native request identifies either a hit or expiry; casting alone executes neither. */
public final class MazeSkillProjectileCallbacks extends MazeSkillAction {
  private final MazeSkill skill;
  private final List<MazeSkillAction> hit;
  private final List<MazeSkillAction> finish;

  public MazeSkillProjectileCallbacks(MazeSkill skill, List<MazeSkillAction> hit, List<MazeSkillAction> finish) {
    this.skill = java.util.Objects.requireNonNull(skill);
    this.hit = List.copyOf(hit);
    this.finish = List.copyOf(finish);
  }

  private List<MazeSkillAction> select(GameAvatar caster) {
    return switch (skill.callbackPhase(caster)) {
      case DIRECT -> List.of();
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
