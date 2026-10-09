package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueEffectHide46.Behavior;
import org.hkrpg.server.gameplay.RogueEffectHide46.TimerComponent;
import org.hkrpg.server.gameplay.RogueMutexAttach46.MonoEffect;

/**
 * Ordinary fade scheduling control; interpolation, audio, delegates and final fading stay bound.
 */
public final class RogueEffectTimer46 {
  private RogueEffectTimer46() {}

  public static final class State {
    public Object ownerMonoBehavior, fadeDriver, callback;
    public byte flag72, flag73;
  }

  public static final class FollowComponent {
    public long cachedPointer;
    public boolean stopFollow;
  }

  public interface World {
    void invoke(Object callback);

    boolean fadeCheck(State self);

    void fallback(State self);

    Object followType();

    FollowComponent findFollow(TimerComponent owner, Object type);

    void lerpOne(State self, float value);

    void lerpZero(State self, float value, boolean flag);

    Object gameObject(TimerComponent owner);

    void postAudio(Behavior behavior, String name, Object gameObject);

    void onFadeOut(Behavior behavior);
  }

  public static void schedule(
      State self, Object callback, float value, int rawFlag, boolean flag, World world) {
    require(self, "fade scheduling state");
    self.flag73 = (byte) rawFlag;
    self.flag72 = 0;
    TimerComponent first = owner(self.ownerMonoBehavior);
    if (!first.initialized || self.fadeDriver == null) {
      require(callback, "immediate fade callback");
      require(world, "immediate fade callback binding").invoke(callback);
      return;
    }
    Object previous = self.callback;
    if (previous != null) require(world, "previous fade callback binding").invoke(previous);
    self.callback = callback;
    World bound = require(world, "fade scheduling bindings");
    if (!bound.fadeCheck(self)) {
      bound.fallback(self);
      return;
    }
    TimerComponent current = owner(self.ownerMonoBehavior);
    if (current.stopFollowOnFadeOut) {
      Object type = bound.followType();
      FollowComponent component = bound.findFollow(current, type);
      if (component != null && component.cachedPointer != 0) component.stopFollow = true;
    }
    current = owner(self.ownerMonoBehavior);
    if (current.fadeOutLerp == 1) bound.lerpOne(self, value);
    else if (current.fadeOutLerp == 0) bound.lerpZero(self, value, flag);
    current = owner(self.ownerMonoBehavior);
    String audio = current.fadeOutAudioEffectName;
    if (audio != null
        && !audio.isEmpty()
        && current.ownerEffect != null
        && current.ownerEffect.behavior != null) {
      Behavior captured = behavior(current.ownerEffect.behavior);
      Object gameObject = bound.gameObject(current);
      bound.postAudio(captured, audio, gameObject);
      current = owner(self.ownerMonoBehavior);
    }
    MonoEffect effect = require(current.ownerEffect, "final fade owner effect");
    bound.onFadeOut(behavior(effect.behavior));
  }

  private static TimerComponent owner(Object value) {
    if (!(value instanceof TimerComponent result))
      throw new IllegalArgumentException("Missing or unknown fade plugin owner projection");
    return result;
  }

  private static Behavior behavior(Object value) {
    if (!(value instanceof Behavior result))
      throw new IllegalArgumentException("Missing or unknown fade effect behavior projection");
    return result;
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
