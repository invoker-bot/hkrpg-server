package org.hkrpg.server.gameplay;

/** Finite compiled reference lists used by departed and unselectable registration transitions. */
public final class RogueOwnedParameterLists46 {
  public static final int LIMIT = 255;

  private RogueOwnedParameterLists46() {}

  public static final class References {
    public Object[] entries;
    public int size, version;

    public References(Object[] entries, int size, int version) {
      this.entries = entries;
      this.size = size;
      this.version = version;
    }
  }

  public static final class Departed {
    public boolean firstFlag, hide;
  }

  public static final class Unselectable {
    public Object entity;

    public Unselectable(Object entity) {
      this.entity = entity;
    }
  }

  public static final class AbilityState {
    public References departed;
    public Object ownerEntity;
  }

  public static final class ModeState {
    public References unselectable;
  }

  public interface World {
    Object visibilityComponent(Object entity);

    void setVisibility(Object component, boolean enabled, boolean immediate, int reason);

    void departedTransition(AbilityState state, boolean previouslyDeparted);

    void crossEntityReplacementError(Object previousEntity, Object replacementEntity);

    Object abilityComponent(Object entity);

    void refreshSelfUnselectable(Object component);
  }

  public static boolean removeFirst(References list, Object value) {
    require(list, "parameter references");
    Object[] entries = require(list.entries, "parameter storage");
    int count = list.size;
    if (count < 0 || count > entries.length)
      throw new IllegalArgumentException("Invalid reference count");
    bound(count);
    int index = -1;
    for (int i = 0; i < count; i++)
      if (entries[i] == value) {
        index = i;
        break;
      }
    if (index < 0) return false;
    if (Integer.compareUnsigned(index, list.size) >= 0)
      throw new IllegalArgumentException("Reference index outside list");
    int next = --list.size;
    if (next > index) {
      bound(next);
      Object[] current = require(list.entries, "parameter storage");
      System.arraycopy(current, index + 1, current, index, next - index);
    }
    Object[] current = require(list.entries, "parameter storage");
    if (Integer.compareUnsigned(next, current.length) >= 0)
      throw new IllegalArgumentException("Missing trailing reference slot");
    current[next] = null;
    list.version++;
    return true;
  }

  public static void add(References list, Object value) {
    require(list, "parameter references");
    list.version++;
    Object[] entries = require(list.entries, "parameter storage");
    int index = list.size;
    if (Integer.compareUnsigned(index, entries.length) < 0) {
      bound(index + 1);
      list.size = index + 1;
      entries[index] = value;
      return;
    }
    int next = index + 1;
    if (index >= entries.length) {
      int capacity = entries.length == 0 ? 4 : entries.length * 2;
      if (capacity < next) capacity = next;
      bound(capacity);
      if (index < 0 || index > entries.length)
        throw new IllegalArgumentException("Invalid growth count");
      Object[] grown = new Object[capacity];
      System.arraycopy(entries, 0, grown, 0, index);
      list.entries = grown;
      entries = grown;
    }
    list.size = next;
    if (Integer.compareUnsigned(index, entries.length) >= 0)
      throw new IllegalArgumentException("Missing added reference slot");
    entries[index] = value;
  }

  public static void refreshDeparted(
      AbilityState state, Departed previous, Departed replacement, World world) {
    require(state, "ability state");
    if (previous == replacement) return;
    boolean wasDeparted = require(state.departed, "departed references").size > 0;
    if (previous != null) removeFirst(require(state.departed, "departed references"), previous);
    if (replacement != null) add(require(state.departed, "departed references"), replacement);
    refreshDepartedVisibility(state, world);
    if (wasDeparted != (require(state.departed, "departed references").size > 0))
      require(world, "parameter world").departedTransition(state, wasDeparted);
  }

  public static void refreshDepartedVisibility(AbilityState state, World world) {
    require(state, "ability state");
    References captured = require(state.departed, "departed references");
    int count = captured.size;
    boolean hide = false;
    if (count > 0) {
      bound(count);
      Object[] entries = require(captured.entries, "departed storage");
      for (int i = 0; i < count; i++) {
        if (i >= entries.length)
          throw new IllegalArgumentException("Departed storage prefix missing");
        Object value = require(entries[i], "departed parameter");
        if (!(value instanceof Departed parameter))
          throw new IllegalArgumentException("Unexpected departed parameter type");
        if (parameter.hide) {
          hide = true;
          break;
        }
      }
    }
    Object entity = require(state.ownerEntity, "owner entity");
    World bound = require(world, "parameter world");
    Object component = bound.visibilityComponent(entity);
    if (component != null) bound.setVisibility(component, !hide, true, 30);
  }

  public static void refreshUnselectable(
      ModeState state, Unselectable previous, Unselectable replacement, World world) {
    require(state, "mode state");
    if (previous == replacement) return;
    if (previous != null && replacement != null && previous.entity != replacement.entity) {
      Object oldEntity = require(previous.entity, "previous entity");
      Object newEntity = require(replacement.entity, "replacement entity");
      require(world, "parameter world").crossEntityReplacementError(oldEntity, newEntity);
      return;
    }
    Object entity = previous == null ? null : previous.entity;
    if (entity == null && replacement != null) entity = replacement.entity;
    if (previous != null)
      removeFirst(require(state.unselectable, "unselectable references"), previous);
    if (replacement != null)
      add(require(state.unselectable, "unselectable references"), replacement);
    entity = require(entity, "parameter entity");
    World bound = require(world, "parameter world");
    Object component = bound.abilityComponent(entity);
    if (component != null) bound.refreshSelfUnselectable(component);
  }

  private static void bound(int count) {
    if (Integer.compareUnsigned(count, LIMIT) > 0)
      throw new IllegalArgumentException("Compiled parameter list exceeds limit");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
