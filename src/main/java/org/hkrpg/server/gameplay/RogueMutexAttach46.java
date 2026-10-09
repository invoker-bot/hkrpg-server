package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueOwnedParameterLists46.References;

/** Ordinary retry of deferred mutex effects after other modifiers destroy or finish fading. */
public final class RogueMutexAttach46 {
  public static final int LIMIT = 255;

  private RogueMutexAttach46() {}

  public static final class Parameter {
    public String effectPath;
    public int mutexType, maxMutexCount;
    public float minMutexTime;
  }

  public static final class Effect {
    public String effectPath;
    public boolean autoHideInSkill;
    public int instanceId, instancePadding, reuseRefCount;
    public MonoEffect instance;
    public TriggerParameters parameters;
  }

  public static final class MonoEffect {
    public long cachedPointer;
    public boolean isActive;
    public int instanceId;
    public Object behavior;
    public TriggerParameters triggerParams;
  }

  public static final class TriggerParameters {
    public CompletionCallback completeCallback;
    public int aliveState;
  }

  public static final class Closure {
    public Effect effectData;
    public RogueModifierDestroy46.State owner;
  }

  public record Binding(Object methodInfo, long methodPointer, long trampoline) {}

  public static final class CompletionCallback {
    public Object methodInfo;
    public long methodPointer, invokePointer, trampoline;
    public Closure target, methodCodeTarget;
  }

  public interface World extends RogueAttachEffectFade46.Lists {
    Closure allocateClosure();

    boolean equalPath(String first, String second);

    Object ownerEntity(Object abilityComponent);

    Object entityWorld(Object entity);

    Object worldEffectMutexModule(Object gameWorld);

    int globalMutex(Object module, String path, float time, int maximum);

    int entityMutex(
        Object module, String path, float time, int maximum, Object entity, int unknownZero);

    void revive(Object behavior);

    void replay(Object behavior, boolean value);

    void setupEffectHide(RogueModifierDestroy46.State self, Effect effect);

    void tryHideEffectInSkillUse(RogueModifierDestroy46.State self, Effect effect, boolean value);

    CompletionCallback allocateCompletionCallback();

    Binding completionCallbackBinding();

    void asyncLoadEffectFinished(
        RogueModifierDestroy46.State self, Effect effect, Object parameters);

    void copy(
        Object[] source, int sourceIndex, Object[] destination, int destinationIndex, int count);
  }

  public static void invokeCompletionCallback(Closure closure, Object parameters, World world) {
    require(closure, "mutex completion closure");
    RogueModifierDestroy46.State owner = require(closure.owner, "mutex completion closure owner");
    Effect effect = closure.effectData;
    require(world, "mutex asynchronous completion binding")
        .asyncLoadEffectFinished(owner, effect, parameters);
  }

  public static void invokeCompletionCallback(
      Closure closure, Object instance, Object nativeCallbackParameters, World world) {
    invokeCompletionCallback(closure, instance, world);
  }

  public static void tryAttach(
      RogueModifierDestroy46.State self, References source, boolean fade, World world) {
    require(self, "mutex modifier");
    if (self.cleanup.mutexEffects == null || source == null) return;
    References pending = references(self.cleanup.mutexEffects);
    int remaining = pending.size;
    if (remaining <= 0 || source.size == 0) return;
    finite(remaining);
    while (remaining > 0) {
      int index = remaining - 1;
      pending = references(self.cleanup.mutexEffects);
      requireIndex(pending, index);
      Object parameterObject = storage(pending, index);
      int incoming = source.size;
      if (incoming > 0) {
        finite(incoming);
        Parameter parameter = null;
        if (parameterObject != null) {
          if (!(parameterObject instanceof Parameter value))
            throw new IllegalArgumentException("Unknown mutex parameter projection");
          parameter = value;
        }
        World bound = require(world, "mutex effect bindings");
        for (int cursor = incoming - 1; cursor >= 0; cursor--) {
          Closure closure = require(bound.allocateClosure(), "mutex effect closure");
          closure.owner = self;
          requireIndex(source, cursor);
          Object candidate = storage(source, cursor);
          if (candidate == null) closure.effectData = null;
          if (!(candidate instanceof Effect effect))
            throw new IllegalArgumentException("Missing or unknown candidate effect");
          closure.effectData = effect;
          if (effect.reuseRefCount != 0) continue;
          if (parameter != null) {
            String path = effect.effectPath, expected = parameter.effectPath;
            if (path != expected
                && (path == null
                    || expected == null
                    || path.length() != expected.length()
                    || !bound.equalPath(path, expected))) continue;
            if (fade) {
              Object owner = require(self.cleanup.ownerAbilityComponent, "mutex owner ability");
              Object entity = require(bound.ownerEntity(owner), "mutex owner entity");
              Object gameWorld = require(bound.entityWorld(entity), "mutex owner world");
              Object module = bound.worldEffectMutexModule(gameWorld);
              if (parameter.mutexType == 0) {
                if (bound.globalMutex(
                        module,
                        parameter.effectPath,
                        parameter.minMutexTime,
                        parameter.maxMutexCount)
                    != 0) continue;
              } else if (parameter.mutexType == 1) {
                Object currentOwner =
                    require(self.cleanup.ownerAbilityComponent, "current mutex owner ability");
                Object currentEntity = bound.ownerEntity(currentOwner);
                if (bound.entityMutex(
                        module,
                        parameter.effectPath,
                        parameter.minMutexTime,
                        parameter.maxMutexCount,
                        currentEntity,
                        0)
                    != 0) continue;
              }
            }
          }
          References active = references(self.cleanup.effects);
          active.version++;
          Object[] array = require(active.entries, "active effect storage");
          int position = active.size;
          Effect captured = closure.effectData;
          if (Integer.compareUnsigned(position, array.length) < 0) {
            finite(position + 1);
            active.size = position + 1;
            array[position] = captured;
          } else RogueAttachEffectFade46.addWithResize(active, captured, bound);
          Effect current = require(closure.effectData, "current attached effect");
          current.reuseRefCount++;
          MonoEffect instance = current.instance;
          if (instance != null
              && instance.cachedPointer != 0
              && current.instanceId == instance.instanceId) {
            if (fade) {
              MonoEffect first =
                  require(require(closure.effectData, "revive effect").instance, "revive instance");
              bound.revive(require(first.behavior, "revive behavior"));
              MonoEffect second =
                  require(require(closure.effectData, "replay effect").instance, "replay instance");
              bound.replay(require(second.behavior, "replay behavior"), false);
            }
            bound.setupEffectHide(self, closure.effectData);
            bound.tryHideEffectInSkillUse(self, closure.effectData, false);
          } else if ((instance == null || instance.cachedPointer == 0)
              && current.instanceId == 0
              && current.parameters != null) {
            TriggerParameters parameters = current.parameters;
            CompletionCallback callback = bound.allocateCompletionCallback();
            Binding binding =
                require(bound.completionCallbackBinding(), "mutex completion callback binding");
            require(callback, "mutex completion callback");
            callback.methodPointer = binding.methodPointer();
            callback.invokePointer = binding.methodPointer();
            callback.methodInfo = binding.methodInfo();
            callback.target = closure;
            callback.methodCodeTarget = closure;
            callback.trampoline = binding.trampoline();
            parameters.completeCallback = callback;
          }
          removePending(self, index, bound);
          break;
        }
      }
      remaining--;
    }
  }

  private static void removePending(RogueModifierDestroy46.State self, int index, World world) {
    References current = references(self.cleanup.mutexEffects);
    requireIndex(current, index);
    int next = --current.size;
    int shifted = next - index;
    if (shifted > 0) {
      finite(shifted);
      Object[] array = current.entries;
      world.copy(array, index + 1, array, index, shifted);
    }
    Object[] array = require(current.entries, "current pending effect storage");
    int tail = current.size;
    if (Integer.compareUnsigned(tail, array.length) >= 0)
      throw new IllegalArgumentException("Pending effect tail outside storage");
    array[tail] = null;
    current.version++;
  }

  private static Object storage(References list, int index) {
    Object[] array = require(list.entries, "mutex effect storage");
    if (Integer.compareUnsigned(index, array.length) >= 0)
      throw new IllegalArgumentException("Mutex effect index outside storage");
    return array[index];
  }

  private static void requireIndex(References list, int index) {
    if (Integer.compareUnsigned(list.size, index) <= 0)
      throw new IllegalArgumentException("Mutex effect index outside current count");
  }

  private static References references(Object value) {
    if (!(value instanceof References list))
      throw new IllegalArgumentException("Missing or unknown mutex effect list");
    return list;
  }

  private static void finite(int count) {
    if (count > LIMIT) throw new IllegalArgumentException("Excessive finite mutex effect count");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
