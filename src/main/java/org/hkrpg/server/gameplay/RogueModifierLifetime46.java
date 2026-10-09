package org.hkrpg.server.gameplay;

import java.util.List;
import java.util.OptionalInt;

/**
 * Ordinary and round-change lifetime order; live instances, lock tables and events need bindings.
 */
public final class RogueModifierLifetime46 {
  private RogueModifierLifetime46() {}

  public static final class State {
    public final Object identity;
    public int initialLife, currentLife, renewTurn, lockStepStatusType;
    public boolean immediately;
    public String modifierName, overrideStatusName;

    public State(Object identity, int initialLife, int currentLife) {
      if (identity == null)
        throw new IllegalArgumentException("Missing explicit modifier identity");
      this.identity = identity;
      this.initialLife = initialLife;
      this.currentLife = currentLife;
    }
  }

  public interface World {
    int lifeStepMoment(State state);

    OptionalInt statusTypeByName(String name);

    Object contextEntity(State state);

    int entityType(Object entity);

    boolean isInLockStepStatus(State state, int statusType, int entityType);

    void valueChanged(State state, int valueType);

    boolean hasOwnerHud(State state);

    void notifyHud(State state);

    void destroy(State state, int deathSource);

    Object gameWorld(State state);

    Object battleInstance(Object world);

    Object gameMode(Object battle);

    OptionalInt limboEntityCount(Object mode);

    void makeLimboEntitiesDie(Object mode);
  }

  public interface LockSource {
    int count(int statusType);

    List<? extends List<Integer>> entityTypeLists(int statusType);
  }

  public static boolean statusLocked(int statusType, int entityType, LockSource source) {
    if (statusType != 1 && statusType != 2) return false;
    if (source == null) throw new IllegalArgumentException("Missing explicit lock source");
    if (source.count(statusType) > 0) return true;
    List<? extends List<Integer>> lists = source.entityTypeLists(statusType);
    if (lists == null) return false;
    int visited = 0;
    for (int outer = 0; outer < lists.size(); outer++) {
      if (outer >= 255) throw new IllegalArgumentException("Unmatched finite lock list count");
      List<Integer> values = lists.get(outer);
      if (values == null) throw new IllegalArgumentException("Missing matched lock source list");
      for (int inner = 0; inner < values.size(); inner++) {
        if (++visited > 255)
          throw new IllegalArgumentException("Unmatched finite lock value count");
        Integer value = values.get(inner);
        if (value == null) throw new IllegalArgumentException("Missing finite entity type");
        if (value == entityType) return true;
      }
    }
    return false;
  }

  public static void process(State state, int turn, World world) {
    require(state, world);
    int moment = moment(world.lifeStepMoment(state));
    if (moment == 0 || moment == 6 || !state.immediately && state.renewTurn == turn) return;
    step(state, world);
  }

  public static void roundRemainingChanged(State state, World world) {
    require(state, world);
    if (moment(world.lifeStepMoment(state)) == 6) step(state, world);
  }

  private static void step(State state, World world) {
    if (state.initialLife == -1) return;
    String name = state.overrideStatusName;
    if (name == null || name.isEmpty()) name = state.modifierName;
    OptionalInt row = world.statusTypeByName(name);
    if (row == null) throw new IllegalArgumentException("Missing explicit status lookup result");
    boolean preserve = false;
    if (row.isPresent()) {
      Object owner = world.contextEntity(state);
      int entityType = owner == null ? 0 : world.entityType(owner);
      preserve =
          world.isInLockStepStatus(state, row.getAsInt(), entityType)
              && state.lockStepStatusType == 0;
    }
    boolean changed = !preserve;
    if (changed) {
      state.currentLife--;
      world.valueChanged(state, 3);
    }
    if (world.hasOwnerHud(state)) world.notifyHud(state);
    if (state.currentLife <= 0) {
      world.destroy(state, 2);
      Object contextWorld = world.gameWorld(state);
      if (contextWorld == null) return;
      Object battle = required(world.battleInstance(contextWorld));
      Object mode = required(world.gameMode(battle));
      OptionalInt count = world.limboEntityCount(mode);
      if (count == null || count.isPresent() && count.getAsInt() < 0)
        throw new IllegalArgumentException("Missing finite limbo count binding");
      if (count.isPresent() && count.getAsInt() > 0) world.makeLimboEntitiesDie(mode);
    }
  }

  private static Object required(Object value) {
    if (value == null)
      throw new IllegalArgumentException("Missing required post-expiry world object");
    return value;
  }

  private static int moment(int value) {
    if (value < 0 || value > 6) throw new IllegalArgumentException("Unmatched lifetime moment");
    return value;
  }

  private static void require(State state, World world) {
    if (state == null || world == null)
      throw new IllegalArgumentException("Missing explicit lifetime binding");
  }
}
