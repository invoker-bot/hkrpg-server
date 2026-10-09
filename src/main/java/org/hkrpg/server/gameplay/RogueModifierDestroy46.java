package org.hkrpg.server.gameplay;

/**
 * Ordered ordinary destruction over the shared lifecycle; event and world operations stay bound.
 */
public final class RogueModifierDestroy46 {
  public static final int LIMIT = 255;

  private RogueModifierDestroy46() {}

  public static final class Children {
    public RogueOwnedParameterLists46.References entries;
  }

  public static final class State {
    public final RogueModifierCleanup46.State cleanup;
    public int deathSource;
    public boolean effectUnload;
    public boolean effectHide, effectPriorityHide, effectAutoHideInSkill;
    public Object preshowEntity;
    public Children children;

    public State(RogueModifierCleanup46.State cleanup) {
      this.cleanup = require(cleanup, "destruction cleanup");
    }
  }

  public interface World extends RogueModifierCleanup46.World {
    Object ownerEntity(Object abilityComponent);

    byte ownerContainsBehavior(
        Object abilityComponent, int flag, Object first, Object second, boolean includeRemoved);

    byte muteModifierGlobalListen(Object abilityComponent);

    void muteModifierGlobalListen(Object abilityComponent, byte value);

    byte containsBehavior(State state, int flag, Object parameters);

    void onModifierDestroy(State state);

    Object entityWorld(Object entity);

    Object worldBattleInstance(Object gameWorld);

    Object battleGameMode(Object battle);

    void manuallyAddOpCountAndTriggerApply(Object mode, int operation);

    void traceDestroyed(String name, Object entity, int reason);

    Object destroyNotificationComponent(Object entity);

    void notifyGlobal(int event, RogueModifierCleanup46.State modifier, boolean immediate);

    int entityType(Object entity);

    void makeGroupEntityDie(Object gameWorld, Object entity);

    void makeLevelEntityDie(Object gameWorld, Object entity, Object parameters);

    void baseDestroy(State state, int reason);
  }

  public static void destroy(State state, int reason, World world) {
    destroy(state, reason, world, 0);
  }

  private static void destroy(State state, int reason, World world, int depth) {
    require(state, "destruction state");
    int previous = state.cleanup.candidate.state;
    if (previous > 1) return;
    if (depth >= LIMIT)
      throw new IllegalArgumentException("Excessive finite child destruction depth");
    Object initialOwner = owner(state);
    World bound = require(world, "destruction world");
    Object capturedEntity = bound.ownerEntity(initialOwner);
    byte before = bound.ownerContainsBehavior(initialOwner, 2, null, null, false);
    state.deathSource = reason;
    state.cleanup.candidate.state = 2;
    Object currentOwner = owner(state);
    if (bound.muteModifierGlobalListen(currentOwner) != 0
        && bound.containsBehavior(state, 86, null) != 0) {
      currentOwner = owner(state);
      byte mute = bound.ownerContainsBehavior(currentOwner, 86, null, null, false);
      bound.muteModifierGlobalListen(currentOwner, mute);
    }
    if (previous == 1) {
      state.cleanup.sequence.replacement.executingSequenceCount++;
      bound.onModifierDestroy(state);
      state.cleanup.sequence.replacement.executingSequenceCount--;
    }
    RogueModifierCleanup46.cleanupAfterDestroy(state.cleanup, bound);
    byte after = bound.ownerContainsBehavior(owner(state), 2, null, null, false);
    if (((before ^ after) & 255) == 1 && capturedEntity != null) {
      Object gameWorld = bound.entityWorld(capturedEntity);
      if (gameWorld != null) {
        Object battle = bound.worldBattleInstance(gameWorld);
        if (battle != null) {
          Object mode = bound.battleGameMode(battle);
          if (mode != null) bound.manuallyAddOpCountAndTriggerApply(mode, 798);
        }
      }
    }
    bound.traceDestroyed(state.cleanup.candidate.name, capturedEntity, reason);
    capturedEntity = require(capturedEntity, "captured destruction entity");
    if (bound.destroyNotificationComponent(capturedEntity) != null)
      bound.notifyGlobal(878, state.cleanup, true);
    Object preshow = state.preshowEntity;
    if (preshow != null) {
      Object gameWorld = require(bound.entityWorld(capturedEntity), "preshow owner world");
      if (bound.entityType(preshow) == 27) bound.makeGroupEntityDie(gameWorld, preshow);
      else bound.makeLevelEntityDie(gameWorld, preshow, null);
    }
    Children holder = require(state.children, "child modifier holder");
    RogueOwnedParameterLists46.References initial = holder.entries;
    if (initial != null && initial.size > 0) {
      int index = 0;
      while (true) {
        RogueOwnedParameterLists46.References list = require(holder.entries, "current child list");
        int count = list.size;
        if (count > LIMIT)
          throw new IllegalArgumentException("Excessive finite child modifier count");
        if (index >= count) {
          list.version++;
          list.size = 0;
          if (count > 0) {
            Object[] entries = require(list.entries, "child list storage");
            if (count > entries.length)
              throw new IllegalArgumentException("Short child list storage");
            for (int i = 0; i < count; i++) entries[i] = null;
          }
          break;
        }
        Object[] entries = require(list.entries, "child list storage");
        if (index >= entries.length) throw new IllegalArgumentException("Short child list storage");
        if (!(entries[index] instanceof State child))
          throw new IllegalArgumentException("Unmatched explicit child modifier state");
        destroy(child, state.deathSource, bound, depth + 1);
        index++;
        holder = require(state.children, "current child modifier holder");
      }
    }
    bound.baseDestroy(state, reason);
  }

  private static Object owner(State state) {
    return require(state.cleanup.ownerAbilityComponent, "current destruction ability component");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
