package emu.lunarcore.game.battle.skills;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/** Supported source condition at each callback; unavailable context executes neither list. */
public final class MazeSkillBuffCondition extends MazeSkillAction {
  private final Function<GameAvatar, Optional<Boolean>> predicate;
  private final List<MazeSkillAction> success;
  private final List<MazeSkillAction> failure;

  public MazeSkillBuffCondition(
      int buffId, boolean inverse, List<MazeSkillAction> success, List<MazeSkillAction> failure) {
    this(buffPredicate(buffId, inverse), success, failure);
  }

  public MazeSkillBuffCondition(
      Predicate<GameAvatar> predicate,
      List<MazeSkillAction> success,
      List<MazeSkillAction> failure) {
    this(success, failure, caster -> Optional.of(predicate.test(caster)));
  }

  private MazeSkillBuffCondition(
      List<MazeSkillAction> success,
      List<MazeSkillAction> failure,
      Function<GameAvatar, Optional<Boolean>> predicate) {
    this.predicate = Objects.requireNonNull(predicate);
    this.success = List.copyOf(success);
    this.failure = List.copyOf(failure);
  }

  public static MazeSkillBuffCondition fromCondition(
      Function<GameAvatar, Optional<Boolean>> predicate,
      List<MazeSkillAction> success,
      List<MazeSkillAction> failure) {
    return new MazeSkillBuffCondition(success, failure, predicate);
  }

  private static Predicate<GameAvatar> buffPredicate(int buffId, boolean inverse) {
    if (buffId <= 0) throw new IllegalArgumentException("Maze buff ID must be positive");
    return caster -> {
      synchronized (caster.getBuffs()) {
        boolean active =
            caster.getBuffs().containsKey(buffId)
                && caster.getBuffs().get(buffId) >= System.currentTimeMillis();
        return active != inverse;
      }
    };
  }

  private List<MazeSkillAction> select(GameAvatar caster) {
    return predicate.apply(caster).map(value -> value ? success : failure).orElse(List.of());
  }

  @Override
  public void onCast(GameAvatar caster, MotionInfo castPosition) {
    for (var action : select(caster)) action.onCast(caster, castPosition);
  }

  @Override
  public void onCastHit(GameAvatar caster, List<? extends GameEntity> entities) {
    for (var action : select(caster)) action.onCastHit(caster, entities);
  }

  @Override
  public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    for (var action : select(caster)) action.onAttack(caster, targets);
  }
}
