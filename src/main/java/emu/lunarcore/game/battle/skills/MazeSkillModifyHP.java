package emu.lunarcore.game.battle.skills;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;

public class MazeSkillModifyHP extends MazeSkillAction {
  private final java.util.function.Function<GameAvatar, java.util.OptionalDouble> ratio;

  public MazeSkillModifyHP(int hp) {
    this(caster -> java.util.OptionalDouble.of(hp / 100.0));
  }

  public MazeSkillModifyHP(
      java.util.function.Function<GameAvatar, java.util.OptionalDouble> ratio) {
    this.ratio = java.util.Objects.requireNonNull(ratio);
  }

  @Override
  public void onCast(GameAvatar caster, MotionInfo castPosition) {
    if (caster == null || caster.getOwner() == null) return;
    var owner = caster.getOwner();
    synchronized (owner) {
      var value = ratio.apply(caster);
      if (value.isEmpty() || !Double.isFinite(value.getAsDouble()) || value.getAsDouble() < 0)
        return;
      var lineup = owner.getCurrentLineup();
      if (lineup == null
          || !lineup.getAvatars().contains(caster.getAvatarId())
          || owner.getAvatarById(caster.getAvatarId()) != caster) return;
      // HP is a normalized 0..10000 bar. Cap before integer conversion and addition.
      int amount = (int) (Math.min(1.0, value.getAsDouble()) * 10000);
      if (amount > 0) lineup.heal(amount, false);
    }
  }
}
