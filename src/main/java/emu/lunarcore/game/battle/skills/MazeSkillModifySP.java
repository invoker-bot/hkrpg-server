package emu.lunarcore.game.battle.skills;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.server.packet.send.PacketSyncLineupNotify;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Function;

public class MazeSkillModifySP extends MazeSkillAction {
  private final Function<GameAvatar, OptionalDouble> ratio;

  public MazeSkillModifySP(int sp) {
    this(caster -> OptionalDouble.of(sp / 100.0));
  }

  public MazeSkillModifySP(Function<GameAvatar, OptionalDouble> ratio) {
    this.ratio = Objects.requireNonNull(ratio);
  }

  @Override
  public void onCast(GameAvatar caster, MotionInfo castPosition) {
    if (caster == null || caster.getOwner() == null) return;
    var owner = caster.getOwner();
    synchronized (owner) {
      var lineup = owner.getCurrentLineup();
      if (lineup == null
          || !lineup.getAvatars().contains(caster.getAvatarId())
          || owner.getAvatarById(caster.getAvatarId()) != caster) return;
      var value = ratio.apply(caster);
      if (value.isEmpty() || !Double.isFinite(value.getAsDouble()) || value.getAsDouble() < 0)
        return;
      int amount = (int) (Math.min(1.0, value.getAsDouble()) * 10000);
      int before = caster.getCurrentSp(lineup);
      if (amount == 0 || before >= caster.getMaxSp()) return;
      caster.setCurrentSp(lineup, Math.min(caster.getMaxSp(), before + amount));
      caster.save();
      owner.sendPacket(new PacketSyncLineupNotify(lineup));
    }
  }
}
