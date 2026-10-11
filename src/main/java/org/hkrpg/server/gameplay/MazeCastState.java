package org.hkrpg.server.gameplay;

import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.proto.MotionInfoOuterClass.MotionInfo;
import emu.lunarcore.server.game.GameSession;
import java.lang.ref.WeakReference;
import java.util.*;

/** Transient native UseID bindings, never persisted or inferred from a timeout. */
public final class MazeCastState {
  // Server admission budget, not a client lifetime or an eviction of accepted casts.
  private static final int MAX_CASTS = 4096;
  private WeakReference<GameSession> session = new WeakReference<>(null);
  private final Map<Integer, Cast> casts = new HashMap<>();

  public static boolean validUseId(int use) {
    // The uint native counter starts at 1 and skips the all-ones sentinel.
    return use != 0 && use != -1;
  }

  public Cast find(GameSession current, int use) {
    bindSession(current);
    return casts.get(use);
  }

  public Cast begin(GameSession current, int use, GameAvatar caster, MazeSkill skill, int index) {
    bindSession(current);
    if (!validUseId(use) || casts.size() >= MAX_CASTS || casts.containsKey(use)) return null;
    var result = new Cast(caster, skill, index);
    casts.put(use, result);
    return result;
  }

  /** A captured original release grants the finite input path, never its payment to another UseID. */
  public boolean hasReleasedOrigin(GameSession current, GameAvatar caster, MazeSkill origin,
      int index, int expectedCost, String modifier) {
    bindSession(current);
    return casts.values().stream().anyMatch(cast -> cast.matches(caster, origin, index)
        && cast.prepaid && cast.paidAmount == expectedCost && cast.released
        && cast.metadata != null && cast.metadata.hasAdventureModifier(modifier));
  }

  private void bindSession(GameSession current) {
    if (session.get() == current) return;
    casts.clear();
    session = new WeakReference<>(Objects.requireNonNull(current));
  }

  public static final class Cast {
    private final WeakReference<Scene> scene;
    private final GameAvatar caster;
    private final PlayerLineup lineup;
    private final MazeSkill skill;
    private final int entityId, index;
    private boolean prepaid, released;
    private int paidAmount;
    private MazeSkill.CastMetadata metadata;

    private Cast(GameAvatar caster, MazeSkill skill, int index) {
      this.caster = Objects.requireNonNull(caster);
      this.skill = Objects.requireNonNull(skill);
      this.scene = new WeakReference<>(caster.getOwner().getScene());
      this.lineup = caster.getOwner().getCurrentLineup();
      this.entityId = caster.getEntityId();
      this.index = index;
    }

    public boolean matches(GameAvatar actor, MazeSkill definition, int nativeIndex) {
      return actor == caster && definition == skill && nativeIndex == index
          && actor.getEntityId() == entityId && actor.getOwner().getScene() == scene.get()
          && actor.getOwner().getCurrentLineup() == lineup;
    }

    public boolean prepaid() { return prepaid; }
    public boolean released() { return released; }
    public int paidAmount() { return paidAmount; }
    public void pay(int amount) {
      if (prepaid || amount <= 0) throw new IllegalStateException("Invalid native OnUse receipt");
      paidAmount = amount;
      prepaid = true;
    }

    /** Old projectile callbacks restore this Ability's metadata, even after a new cast. */
    public void release(MotionInfo position) {
      if (!released) {
        skill.onCast(caster, position);
        metadata = caster.getMazeCastMetadata();
        released = true;
      } else {
        caster.setMazeCastMetadata(metadata);
      }
    }
  }
}
