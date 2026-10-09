package org.hkrpg.server.gameplay;

/** Ordinary owned-effect withdrawals; component, pool and notification bodies stay bound. */
public final class RogueModifierOwnedEffects46 {
  public static final int CHARACTER_CHANGED = 990;

  private RogueModifierOwnedEffects46() {}

  public static final class State {
    public final RogueModifierCleanup46.State cleanup;
    public Object characterChange, departed, unselectable;

    public State(RogueModifierCleanup46.State cleanup) {
      if (cleanup == null) throw new IllegalArgumentException("Missing shared cleanup state");
      this.cleanup = cleanup;
    }
  }

  public interface World {
    void detachCharacterChange(Object ownerAbility, Object parameter, boolean immediate);

    void refreshDeparted(Object ownerAbility, Object parameter, Object replacement);

    Object ownerEntity(Object ownerAbility);

    Object entityWorld(Object entity);

    Object worldBattleInstance(Object gameWorld);

    Object battleGameMode(Object battle);

    void refreshUnselectable(Object gameMode, Object parameter, Object replacement);

    void recycle(Object parameter);

    void notifyGlobal(int event, Object payload, boolean immediate);
  }

  public static void detachCharacterChange(State state, boolean immediate, World world) {
    require(state, "owned-effect state");
    Object parameter = state.characterChange;
    if (parameter == null) return;
    state.characterChange = null;
    Object owner = require(state.cleanup.ownerAbilityComponent, "owner ability component");
    World bound = require(world, "owned-effect world");
    bound.detachCharacterChange(owner, parameter, immediate);
    bound.recycle(parameter);
    bound.notifyGlobal(CHARACTER_CHANGED, null, true);
  }

  public static void detachDeparted(State state, World world) {
    require(state, "owned-effect state");
    Object parameter = state.departed;
    if (parameter == null) return;
    state.departed = null;
    Object owner = require(state.cleanup.ownerAbilityComponent, "owner ability component");
    World bound = require(world, "owned-effect world");
    bound.refreshDeparted(owner, parameter, null);
    bound.recycle(parameter);
  }

  public static void detachUnselectable(State state, World world) {
    require(state, "owned-effect state");
    Object parameter = state.unselectable;
    if (parameter == null) return;
    state.unselectable = null;
    Object owner = require(state.cleanup.ownerAbilityComponent, "owner ability component");
    World bound = require(world, "owned-effect world");
    Object entity = require(bound.ownerEntity(owner), "owner entity");
    Object gameWorld = require(bound.entityWorld(entity), "entity world");
    Object battle = require(bound.worldBattleInstance(gameWorld), "battle instance");
    Object gameMode = require(bound.battleGameMode(battle), "turn-based game mode");
    bound.refreshUnselectable(gameMode, parameter, null);
    bound.recycle(parameter);
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
