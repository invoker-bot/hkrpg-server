package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueMutexAttach46.Effect;
import org.hkrpg.server.gameplay.RogueMutexAttach46.MonoEffect;

/** Ordinary asynchronous load completion over the shared effect and modifier projections. */
public final class RogueAsyncEffectFinish46 {
  private RogueAsyncEffectFinish46() {}

  public interface World {
    void setFinish(Object behavior, boolean immediate, boolean fade, boolean unknown);

    void setupEffectHide(RogueModifierDestroy46.State self, Effect effect);

    void tryHideEffectInSkillUse(RogueModifierDestroy46.State self, Effect effect, boolean value);
  }

  public static void finish(
      RogueModifierDestroy46.State self, Effect effect, MonoEffect instance, World world) {
    if (instance == null
        || instance.cachedPointer == 0
        || instance.triggerParams == null
        || instance.triggerParams.aliveState > 5) return;
    require(self, "async effect modifier");
    if (self.effectUnload || self.cleanup.effects == null) {
      Object behavior = require(instance.behavior, "async setFinish behavior");
      require(world, "async setFinish binding").setFinish(behavior, true, false, false);
    } else {
      require(effect, "async effect data");
      effect.instanceId = instance.instanceId;
      effect.instancePadding = 0;
      effect.instance = instance;
    }
    World bound = require(world, "async hide bindings");
    bound.setupEffectHide(self, effect);
    bound.tryHideEffectInSkillUse(self, effect, false);
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
