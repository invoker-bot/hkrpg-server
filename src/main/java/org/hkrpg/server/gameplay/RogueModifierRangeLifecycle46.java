package org.hkrpg.server.gameplay;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Root range listener retirement and active exits; full modifier destruction stays separate. */
public final class RogueModifierRangeLifecycle46 {
  private RogueModifierRangeLifecycle46() {}

  private static final Object OWNER_CHANGE_METHOD = new Object(),
      MODIFIER_CHANGE_METHOD = new Object();

  public static final class State {
    public final RogueModifierDynamicRange46.State range;
    public final RogueModifierCasterChange46.State caster;
    private final RogueResonanceObserver46.Listener ownerListener, modifierListener;

    public State(RogueModifierDynamicRange46.State range, RogueModifierDynamicRange46.World world) {
      if (range == null || world == null)
        throw new IllegalArgumentException("Missing explicit range lifecycle binding");
      this.range = range;
      caster =
          new RogueModifierCasterChange46.State(
              range.replacement,
              (state, key, change) ->
                  RogueModifierDynamicRange46.changed(range, key, change, world));
      ownerListener =
          new RogueResonanceObserver46.Listener(
              range.replacement,
              OWNER_CHANGE_METHOD,
              c -> RogueModifierDynamicRange46.changed(range, scopedKey(c.key(), 5), c, world));
      modifierListener =
          new RogueResonanceObserver46.Listener(
              range.replacement,
              MODIFIER_CHANGE_METHOD,
              c -> RogueModifierDynamicRange46.changed(range, scopedKey(c.key(), 2), c, world));
    }

    public RogueResonanceObserver46.Listener ownerListener() {
      return ownerListener;
    }

    public RogueResonanceObserver46.Listener modifierListener() {
      return modifierListener;
    }
  }

  /**
   * Explicit mutable versioned groups. Native dictionary factories and other map variants stay
   * closed.
   */
  public static final class Groups
      extends AbstractMap<Long, List<RogueModifierDynamicRange46.Entry>> {
    private final LinkedHashMap<Long, List<RogueModifierDynamicRange46.Entry>> groups =
        new LinkedHashMap<>();
    private int version;

    public int version() {
      return version;
    }

    @Override
    public List<RogueModifierDynamicRange46.Entry> put(
        Long key, List<RogueModifierDynamicRange46.Entry> entries) {
      if (key == null || (!groups.containsKey(key) && groups.size() >= 255))
        throw new IllegalArgumentException("Invalid finite range group key/count");
      version++;
      return groups.put(key, entries);
    }

    @Override
    public List<RogueModifierDynamicRange46.Entry> remove(Object key) {
      if (!groups.containsKey(key)) return null;
      version++;
      return groups.remove(key);
    }

    @Override
    public void clear() {
      version++;
      groups.clear();
    }

    @Override
    public List<RogueModifierDynamicRange46.Entry> get(Object key) {
      return groups.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
      return groups.containsKey(key);
    }

    @Override
    public int size() {
      return groups.size();
    }

    @Override
    public Set<Map.Entry<Long, List<RogueModifierDynamicRange46.Entry>>> entrySet() {
      return Collections.unmodifiableMap(groups).entrySet();
    }
  }

  public interface World extends RogueModifierCasterChange46.World {
    Object ownerAbilityComponent(State state);

    Object ownerEntity(Object component);

    void executeSequence(Object sequence);
  }

  public static long scopedKey(int hash, int scope) {
    if (scope != 2 && scope != 4 && scope != 5)
      throw new IllegalArgumentException("Unmatched finite range listener scope");
    return ((long) hash << 32) | scope;
  }

  public static void removeRootListeners(State state, World world) {
    if (state == null || world == null)
      throw new IllegalArgumentException("Missing explicit range retirement binding");
    if (state.caster.listenScopeMask == 0) return;
    if ((state.caster.listenScopeMask & 32) != 0) {
      Object owner = world.ownerAbilityComponent(state);
      if (owner == null)
        throw new IllegalArgumentException("Missing current range owner ability component");
      Object entity = world.ownerEntity(owner);
      if (entity == null) throw new IllegalArgumentException("Missing range owner entity");
      Object component = world.casterComponent(entity);
      if (component == null)
        throw new IllegalArgumentException("Missing range owner numeric component");
      RogueResonanceVariables46 values = world.casterVariables(component);
      if (values == null) throw new IllegalArgumentException("Missing range owner root values");
      values.removeObserver(state.ownerListener);
    }
    if ((state.caster.listenScopeMask & 16) != 0) {
      Object caster = state.caster.caster;
      if (caster == null) {
        Object context = world.taskContext(state.caster);
        if (context == null)
          throw new IllegalArgumentException("Missing current range retirement context");
        caster = world.casterEntity(context);
        state.caster.caster = caster;
      }
      if (caster != null) {
        Object component = world.casterComponent(caster);
        if (component == null)
          throw new IllegalArgumentException("Missing range caster numeric component");
        RogueResonanceVariables46 values = world.casterVariables(component);
        if (values != null) values.removeObserver(state.caster.listener());
      }
    }
    if ((state.caster.listenScopeMask & 4) != 0) {
      Object current = state.range.replacement.values.dynamicValues;
      if (!(current instanceof RogueResonanceVariables46 values))
        throw new IllegalArgumentException("Missing explicit modifier root values");
      values.removeObserver(state.modifierListener);
    }
    state.caster.listenScopeMask = 0;
  }

  public static void exitActiveRanges(State state, World world) {
    if (state == null || world == null)
      throw new IllegalArgumentException("Missing explicit range exit binding");
    Map<Long, List<RogueModifierDynamicRange46.Entry>> selected = state.range.listenerGroups;
    if (selected == null) return;
    if (!(selected instanceof Groups groups))
      throw new IllegalArgumentException("Unmatched range dictionary version binding");
    int version = groups.version();
    var cursor = groups.groups.values().iterator();
    while (true) {
      if (groups.version() != version)
        throw new IllegalArgumentException("Range dictionary changed during retirement");
      if (!cursor.hasNext()) break;
      List<RogueModifierDynamicRange46.Entry> entries = cursor.next();
      if (entries == null)
        throw new IllegalArgumentException("Missing captured retirement range group");
      for (int index = 0; index < entries.size(); index++) {
        if (index >= 255)
          throw new IllegalArgumentException("Excessive finite retirement range group");
        RogueModifierDynamicRange46.Entry entry = entries.get(index);
        if (entry == null)
          throw new IllegalArgumentException("Missing current retirement range entry");
        if (entry.inRange) {
          Object sequence = entry.exitSequence;
          if (sequence != null) world.executeSequence(sequence);
        }
      }
    }
    state.range.listenerGroups = null;
  }

  public static void retire(State state, World world) {
    removeRootListeners(state, world);
    exitActiveRanges(state, world);
  }
}
