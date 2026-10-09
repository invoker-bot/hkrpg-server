package org.hkrpg.server.gameplay;

/**
 * Ordinary base destruction and two listener withdrawals; delegate/runtime operations stay bound.
 */
public final class RogueBaseModifierDestroy46 {
  public static final int OTHER_DESTROY_METHOD = 508636;
  public static final int OTHER_EFFECT_FADE_METHOD = 508637;
  public static final long CALLBACK_TRAMPOLINE_RVA = 0x3d10L;

  private RogueBaseModifierDestroy46() {}

  public record MethodBinding(int methodIndex, Object methodInfo, long methodPointer) {}

  public static final class Callback {
    public long methodPointer, invokePointer, trampolineRva;
    public Object methodInfo;
    public RogueModifierDestroy46.State target, methodCodeTarget;
    public CallbackData data;
    public Callback[] delegates;
  }

  public static final class CallbackData {
    public String methodName;
    public Object targetType;
    public boolean curriedFirstArgument;
  }

  public interface World {
    Object ownerAbilityComponent(RogueModifierDestroy46.State state);

    Object ownerEntity(Object component);

    Object entityWorld(Object entity);

    Object worldEventManager(Object gameWorld);

    int entityId(Object entity);

    Callback allocateCallback();

    MethodBinding callbackMethodBinding(int methodIndex);

    void unregisterEventListener(Object manager, int event, int entityId, Callback callback);
  }

  public static void destroy(RogueModifierDestroy46.State state, int reason, World world) {
    require(state, "base destruction state");
    state.deathSource = reason;
    World bound = require(world, "base destruction world");
    Object component =
        require(bound.ownerAbilityComponent(state), "virtual owner ability component");
    Object entity = require(bound.ownerEntity(component), "base destruction entity");
    Object gameWorld = require(bound.entityWorld(entity), "base destruction entity world");
    Object manager = bound.worldEventManager(gameWorld);
    int entityId = bound.entityId(entity);
    Callback first = callback(state, OTHER_DESTROY_METHOD, bound);
    bound.unregisterEventListener(require(manager, "base event manager"), 207, entityId, first);
    entityId = bound.entityId(entity);
    Callback second = callback(state, OTHER_EFFECT_FADE_METHOD, bound);
    bound.unregisterEventListener(manager, 208, entityId, second);
  }

  private static Callback callback(RogueModifierDestroy46.State state, int method, World world) {
    Callback allocated = world.allocateCallback();
    MethodBinding binding =
        require(world.callbackMethodBinding(method), "destruction callback method");
    if (binding.methodIndex() != method)
      throw new IllegalArgumentException("Unmatched destruction callback method identity");
    Object methodInfo = require(binding.methodInfo(), "destruction callback method info");
    Callback value = require(allocated, "destruction callback allocation");
    value.methodPointer = binding.methodPointer();
    value.methodInfo = methodInfo;
    value.target = state;
    value.invokePointer = binding.methodPointer();
    value.methodCodeTarget = state;
    value.trampolineRva = CALLBACK_TRAMPOLINE_RVA;
    return value;
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
