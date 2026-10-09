package org.hkrpg.server.gameplay;

import java.util.List;

/** Caster cache and real root-listener migration; native component/context factories stay bound. */
public final class RogueModifierCasterChange46 {
  private RogueModifierCasterChange46() {}

  private static final Object CASTER_CHANGE_METHOD = new Object();

  @FunctionalInterface
  public interface ChangeHandler {
    void changed(State state, long scopedKey, RogueResonanceVariables46.Change change);
  }

  public static final class State {
    public final RogueModifierReplace46.State replacement;
    public Object caster;
    public int listenScopeMask;
    private final RogueResonanceObserver46.Listener listener;

    public State(RogueModifierReplace46.State replacement, ChangeHandler handler) {
      if (replacement == null || handler == null)
        throw new IllegalArgumentException("Missing caster-change state binding");
      this.replacement = replacement;
      listener =
          new RogueResonanceObserver46.Listener(
              replacement, CASTER_CHANGE_METHOD, c -> handler.changed(this, scopedKey(c.key()), c));
    }

    public RogueResonanceObserver46.Listener listener() {
      return listener;
    }
  }

  public static final class OwnerRegistry {
    public List<Object> modifiers;

    public OwnerRegistry(List<Object> modifiers) {
      this.modifiers = modifiers;
    }
  }

  public interface World {
    Object taskContext(State state);

    Object casterEntity(Object context);

    Object casterComponent(Object entity);

    RogueResonanceVariables46 casterVariables(Object component);

    Object contextAbility(Object context);

    OwnerRegistry ownerRegistry(Object ability);
  }

  public static long scopedKey(int hash) {
    return ((long) hash << 32) | 4L;
  }

  public static void changed(State state, World world) {
    if (state == null || world == null)
      throw new IllegalArgumentException("Missing explicit caster-change world binding");
    Object previous = state.caster;
    state.caster = null;
    state.caster = readCaster(state, world);
    if (previous != state.caster && (state.listenScopeMask & 16) != 0) {
      if (previous != null) {
        Object component = world.casterComponent(previous);
        if (component == null)
          throw new IllegalArgumentException("Missing previous caster component");
        RogueResonanceVariables46 values = world.casterVariables(component);
        if (values != null) values.removeObserver(state.listener);
      }
      Object caster = state.caster;
      if (caster == null) {
        state.caster = readCaster(state, world);
        caster = state.caster;
      }
      if (caster != null) {
        Object component = world.casterComponent(caster);
        if (component == null)
          throw new IllegalArgumentException("Missing current caster component");
        RogueResonanceVariables46 values = world.casterVariables(component);
        if (values != null) values.combineObserver(state.listener);
      }
    }
    Object ability = world.contextAbility(requireContext(state, world));
    if (ability == null) return;
    OwnerRegistry registry = world.ownerRegistry(ability);
    if (registry != null) registerIdentity(registry, state.replacement);
  }

  public static void registerIdentity(OwnerRegistry registry, Object modifier) {
    if (registry == null || registry.modifiers == null || modifier == null)
      throw new IllegalArgumentException("Missing finite owner modifier registry");
    List<Object> modifiers = registry.modifiers;
    int count = modifiers.size();
    if (count > 255) throw new IllegalArgumentException("Excessive finite owner modifier registry");
    for (int index = count - 1; index >= 0; index--) if (modifiers.get(index) == modifier) return;
    if (count >= 255)
      throw new IllegalArgumentException("Excessive finite owner modifier registry");
    modifiers.add(modifier);
  }

  private static Object requireContext(State state, World world) {
    Object context = world.taskContext(state);
    if (context == null)
      throw new IllegalArgumentException("Missing current caster-change task context");
    return context;
  }

  private static Object readCaster(State state, World world) {
    return world.casterEntity(requireContext(state, world));
  }
}
