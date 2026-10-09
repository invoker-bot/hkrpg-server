package emu.lunarcore.game.battle.skills;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.scene.SceneBuff;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.server.packet.send.PacketSyncEntityBuffChangeListScNotify;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
public class MazeSkillAddBuff extends MazeSkillAction {
  private int buffId;
  private int duration;
  private double lifetimeSeconds;

  @Getter(lombok.AccessLevel.NONE)
  private final java.util.function.Function<GameAvatar, java.util.OptionalDouble> lifetime;

  @Setter private boolean casterTarget;

  @Getter(lombok.AccessLevel.NONE)
  @Setter
  private java.util.function.Predicate<GameAvatar> condition;

  @Setter private boolean sendBuffPacket;

  public MazeSkillAddBuff(int buffId, int duration) {
    this(buffId, (double) duration);
  }

  public MazeSkillAddBuff(int buffId, double duration) {
    org.hkrpg.server.gameplay.MazeBuffLifetime.milliseconds(duration);
    this.buffId = buffId;
    // Retain the original integer getter for integrations; runtime uses the precise value.
    this.duration = (int) duration;
    this.lifetimeSeconds = duration;
    this.lifetime = caster -> java.util.OptionalDouble.of(duration);
  }

  public MazeSkillAddBuff(
      int buffId, java.util.function.Function<GameAvatar, java.util.OptionalDouble> lifetime) {
    this.buffId = buffId;
    this.lifetimeSeconds = Double.NaN; // A dynamic duration has no caster-independent value.
    this.lifetime = java.util.Objects.requireNonNull(lifetime);
  }

  public java.util.OptionalDouble getLifetime(GameAvatar caster) {
    if (caster == null) return java.util.OptionalDouble.empty();
    return lifetime.apply(caster);
  }

  @Override
  public void onCast(GameAvatar caster, MotionInfo castPosition) {
    if (!canApply(caster)) return;
    var duration = getLifetime(caster);
    if (duration.isPresent()) caster.addBuff(buffId, duration.getAsDouble());
  }

  @Override
  public void onCastHit(GameAvatar caster, List<? extends GameEntity> entities) {
    if (this.casterTarget) {
      this.onCast(caster, null);
      return;
    }
    for (GameEntity entity : entities) {
      if (entity instanceof EntityMonster monster) {
        this.applyToMonster(caster, monster);
      }
    }
  }

  public SceneBuff applyToMonster(GameAvatar caster, EntityMonster monster) {
    if (this.casterTarget || !canApply(caster)) return null;
    var duration = getLifetime(caster);
    if (duration.isEmpty()) return null;
    var buff = monster.addBuff(caster.getAvatarId(), buffId, duration.getAsDouble());
    if (buff != null && this.sendBuffPacket) {
      caster
          .getOwner()
          .sendPacket(new PacketSyncEntityBuffChangeListScNotify(monster.getEntityId(), buff));
    }
    return buff;
  }

  @Override
  public void onAttack(GameAvatar caster, List<? extends GameEntity> targets) {
    if (this.casterTarget) {
      this.onCast(caster, null);
      return;
    }
    if (!canApply(caster) || getLifetime(caster).isEmpty()) return;
    // Add debuff to monsters
    for (GameEntity target : targets) {
      if (target instanceof EntityMonster monster) {
        // Set as temp buff
        monster.addTempBuff(new SceneBuff(caster.getAvatarId(), buffId));
      }
    }
  }

  private boolean canApply(GameAvatar caster) {
    return this.condition == null || this.condition.test(caster);
  }
}
