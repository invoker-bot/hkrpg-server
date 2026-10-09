package org.hkrpg.server.gameplay;

/** Ordered ordinary departed-event tail; payload factory, dispatch and pool release stay bound. */
public final class RogueDepartedTransition46 {
  private RogueDepartedTransition46() {}

  /**
   * Projection of the source event's owner field at offset 64, without an implicit event factory.
   */
  public static class Event {
    public Object ownerEntity;
  }

  public interface World {
    Event acquireEvent();

    Object entityWorld(Object entity);

    Object worldBattleInstance(Object gameWorld);

    Object battleGameMode(Object battle);

    Object eventProcessor(Object gameMode);

    void dispatchEntity(
        Object processor,
        Object entity,
        int eventType,
        Event payload,
        Object sourceEntity,
        int filterMask);

    void dispatchGlobal(
        Object processor,
        int eventType,
        Event payload,
        Object sourceEntity,
        int filterMask,
        Object extraFilter);

    void releaseEvent(Event payload);
  }

  public static void dispatch(
      RogueOwnedParameterLists46.AbilityState state, boolean previouslyDeparted, World world) {
    require(state, "ability state");
    World bound = require(world, "departed event world");
    Event payload = require(bound.acquireEvent(), "departed event payload");
    Object entity = state.ownerEntity;
    payload.ownerEntity = entity;
    entity = require(entity, "owner entity");
    Object gameWorld = require(bound.entityWorld(entity), "entity world");
    Object battle = bound.worldBattleInstance(gameWorld);
    if (battle != null) {
      Object mode = bound.battleGameMode(battle);
      if (mode != null) {
        Object processor = bound.eventProcessor(mode);
        if (processor != null) {
          bound.dispatchEntity(processor, entity, previouslyDeparted ? 227 : 226, payload, null, 0);
          bound.dispatchGlobal(
              processor, previouslyDeparted ? 229 : 228, payload, state.ownerEntity, 0, null);
        }
      }
    }
    bound.releaseEvent(payload);
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
