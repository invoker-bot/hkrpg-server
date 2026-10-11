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

  public boolean isCurrentCast(GameSession current, Cast binding) {
    bindSession(current);
    return binding != null && casts.containsValue(binding);
  }

  /** A captured original release grants the finite input path, never its payment to another UseID. */
  public boolean hasReleasedOrigin(GameSession current, GameAvatar caster, MazeSkill origin,
      int index, int expectedCost, String modifier) {
    bindSession(current);
    return casts.values().stream().anyMatch(cast -> cast.matches(caster, origin, index)
        && cast.prepaid && cast.paidAmount == expectedCost && cast.released
        && cast.metadata != null && cast.metadata.hasAdventureModifier(modifier));
  }

  /** The actual base release and its still-current source buff, without inferring prepayment. */
  public boolean hasReleasedBuffOrigin(GameSession current, GameAvatar caster, MazeSkill origin,
      int index, int buff) {
    bindSession(current);
    long expiry = caster.getBuffs().get(buff);
    if (expiry <= System.currentTimeMillis()) return false;
    return casts.values().stream().anyMatch(cast -> cast.matches(caster, origin, index)
        && cast.released && Objects.equals(cast.releasedBuffs.get(buff), expiry));
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
    private Map<Integer, Long> releasedBuffs = Map.of();

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
    /** One finite direct Attack attempt can restore its caster effects and unreleased native binding. */
    public Attempt attempt() { return new Attempt(); }

    public final class Attempt implements AutoCloseable {
      private final Map<Integer, Long> buffs = new HashMap<>(caster.getBuffs());
      private final MazeSkill.CastMetadata casterMetadata = caster.getMazeCastMetadata();
      private final MazeSkill.CastMetadata castMetadata = metadata;
      private final Map<Integer, Long> priorReleasedBuffs = releasedBuffs;
      private final boolean priorReleased = released;
      private boolean accepted;

      private Attempt() {}
      public void commit() { accepted = true; }

      @Override public void close() {
        if (accepted) return;
        synchronized (caster.getBuffs()) {
          caster.getBuffs().clear();
          caster.getBuffs().putAll(buffs);
        }
        caster.setMazeCastMetadata(casterMetadata);
        metadata = castMetadata;
        releasedBuffs = priorReleasedBuffs;
        released = priorReleased;
      }
    }
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
        releasedBuffs = new HashMap<>(caster.getBuffs());
        released = true;
      } else {
        caster.setMazeCastMetadata(metadata);
      }
    }
  }
}
