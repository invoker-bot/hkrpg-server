package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueModifierDestroy46.State;
import org.hkrpg.server.gameplay.RogueMutexAttach46.Effect;
import org.hkrpg.server.gameplay.RogueMutexAttach46.MonoEffect;

/**
 * Ordinary effect hiding over shared modifier/effect state and explicit component/timer bindings.
 */
public final class RogueEffectHide46 {
  private RogueEffectHide46() {}

  public static final class Behavior {
    public int hideReason;
  }

  public static final class SkillClosure {
    public State owner;
    public int instanceId, instancePadding;
    public MonoEffect instance;
  }

  public static final class TimerComponent {
    public long cachedPointer;
    public Object timer;
  }

  public record Binding(Object methodInfo, long methodPointer, long trampoline) {}

  public static final class Callback {
    public Object methodInfo;
    public long methodPointer, invokePointer, trampoline;
    public SkillClosure target, methodCodeTarget;
  }

  public interface World {
    void setActive(Behavior behavior, boolean active);

    SkillClosure allocateClosure();

    Object componentType();

    TimerComponent findComponent(MonoEffect instance, Object type);

    Callback allocateCallback();

    Binding callbackBinding();

    void schedule(Object timer, Callback callback, float value, int zero, boolean flag);
  }

  public static void setup(State self, Effect effect, World world) {
    if (effect == null) return;
    MonoEffect instance = effect.instance;
    if (instance == null
        || instance.cachedPointer == 0
        || effect.instanceId != instance.instanceId
        || instance.triggerParams == null
        || instance.triggerParams.aliveState > 5) return;
    Object captured = instance.behavior;
    require(self, "effect hide modifier");
    boolean hidden = self.effectHide || self.effectPriorityHide;
    Behavior behavior = behavior(captured);
    int previous = behavior.hideReason;
    if (hidden) behavior.hideReason = previous | 0x80;
    else {
      behavior.hideReason = previous & ~0x80;
      if (previous != 0x80) return;
    }
    require(world, "effect active binding").setActive(behavior, !hidden);
  }

  public static void tryHide(State self, Effect effect, boolean wait, World world) {
    World bound = require(world, "skill effect bindings");
    SkillClosure closure = require(bound.allocateClosure(), "skill effect closure");
    closure.owner = self;
    require(self, "skill effect modifier");
    if (!self.effectAutoHideInSkill) return;
    require(effect, "skill effect data");
    if (!effect.autoHideInSkill) return;
    closure.instanceId = effect.instanceId;
    closure.instancePadding = effect.instancePadding;
    closure.instance = effect.instance;
    MonoEffect captured = closure.instance;
    if (captured == null
        || captured.cachedPointer == 0
        || closure.instanceId != captured.instanceId
        || captured.triggerParams == null
        || captured.triggerParams.aliveState > 4) return;
    Object type = bound.componentType();
    TimerComponent component = bound.findComponent(captured, type);
    MonoEffect current = require(closure.instance, "current skill effect instance");
    if (wait
        && current.triggerParams != null
        && current.triggerParams.aliveState == 3
        && component != null
        && component.cachedPointer != 0) {
      Object timer = component.timer;
      Callback callback = bound.allocateCallback();
      Binding binding = require(bound.callbackBinding(), "skill callback method binding");
      require(callback, "skill callback allocation");
      callback.methodPointer = binding.methodPointer();
      callback.methodInfo = binding.methodInfo();
      callback.target = closure;
      callback.invokePointer = binding.methodPointer();
      callback.methodCodeTarget = closure;
      callback.trampoline = binding.trampoline();
      require(timer, "skill timer");
      bound.schedule(timer, callback, Float.intBitsToFloat(0xbf800000), 0, false);
      return;
    }
    hideSkill(current, bound);
  }

  public static void invokeDelayed(SkillClosure closure, World world) {
    require(closure, "delayed skill closure");
    State owner = require(closure.owner, "delayed skill owner");
    if (owner.cleanup.effects == null) return;
    hideSkill(require(closure.instance, "delayed skill instance"), world);
  }

  private static void hideSkill(MonoEffect instance, World world) {
    Behavior behavior = behavior(instance.behavior);
    behavior.hideReason |= 0x200;
    require(world, "skill active binding").setActive(behavior, false);
  }

  private static Behavior behavior(Object value) {
    if (!(value instanceof Behavior result))
      throw new IllegalArgumentException("Missing or unknown effect behavior projection");
    return result;
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
