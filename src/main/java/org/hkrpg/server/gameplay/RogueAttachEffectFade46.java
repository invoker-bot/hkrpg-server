package org.hkrpg.server.gameplay;

import org.hkrpg.server.gameplay.RogueBaseOtherModifierEvents46.FadeFinished;
import org.hkrpg.server.gameplay.RogueOwnedParameterLists46.References;

/**
 * Ordinary fade callback, single-effect payload initialization and immediate post/recycle order.
 */
public final class RogueAttachEffectFade46 {
  public static final int LIMIT = 4096;

  private RogueAttachEffectFade46() {}

  public interface Lists {
    void clear(Object[] storage, int start, int count);

    void setCapacity(References list, int capacity);
  }

  public interface Posting {
    /** Explicit native EKFMIDBJDIA projection; a failed cast is passed to dispatch as null. */
    Object runtimeEvent(FadeFinished event);

    void dispatch(Object manager, Object event);

    Object recyclePool(FadeFinished event);

    void recycle(Object pool, FadeFinished event);
  }

  public interface World extends Lists, Posting {
    Object ownerAbilityComponent(RogueModifierDestroy46.State state);

    Object ownerEntity(Object component);

    Object entityWorld(Object entity);

    Object worldEventManager(Object gameWorld);

    FadeFinished acquireEvent();
  }

  public static FadeFinished initialize(
      FadeFinished event, Object sourceEntity, Object effect, Object modifier, Lists world) {
    require(event, "fade payload");
    event.sourceEntity = sourceEntity;
    References list = effects(event);
    list.version++;
    int previous = list.size;
    list.size = 0;
    if (previous > 0) {
      finite(previous);
      require(world, "fade list clear binding").clear(list.entries, 0, previous);
      list = effects(event);
    }
    list.version++;
    Object[] storage = require(list.entries, "fade effect list storage");
    int index = list.size;
    if (Integer.compareUnsigned(index, storage.length) < 0) {
      finite(index + 1);
      list.size = index + 1;
      storage[index] = effect;
    } else {
      addWithResize(list, effect, world);
    }
    event.modifier = modifier;
    return event;
  }

  public static FadeFinished post(FadeFinished event, boolean immediate, Posting world) {
    require(event, "posted fade payload");
    Object manager = event.eventManager;
    if (manager != null) {
      Posting bound = require(world, "fade dispatch binding");
      Object payload = bound.runtimeEvent(event);
      bound.dispatch(manager, payload);
    }
    if (!immediate) return event;
    Posting bound = require(world, "fade recycle binding");
    Object pool = require(bound.recyclePool(event), "fade payload recycle pool");
    bound.recycle(pool, event);
    return null;
  }

  public static void onAttachEffectFadeFinished(
      RogueModifierDestroy46.State state, Object effect, World world) {
    require(state, "fade callback receiver");
    World bound = require(world, "fade callback world");
    Object owner = bound.ownerAbilityComponent(state);
    if (owner == null) return;
    Object entity = bound.ownerEntity(owner);
    if (entity == null) return;
    Object gameWorld = bound.entityWorld(entity);
    if (gameWorld == null) return;
    Object manager = bound.worldEventManager(gameWorld);
    if (manager == null) return;
    FadeFinished event = require(bound.acquireEvent(), "fade payload acquisition");
    event.eventManager = manager;
    owner = require(bound.ownerAbilityComponent(state), "current fade owner ability component");
    Object currentEntity = bound.ownerEntity(owner);
    event = initialize(event, currentEntity, effect, state, bound);
    post(event, true, bound);
  }

  /** Complete ordinary add-with-resize control over an explicitly projected effect list. */
  public static void addWithResize(References list, Object effect, Lists world) {
    require(list, "fade resized effect list");
    int index = list.size;
    Object[] storage = require(list.entries, "fade resize storage");
    int next = index + 1;
    if (index >= storage.length) {
      int doubled = storage.length * 2;
      int capacity =
          storage.length == 0
              ? 4
              : Integer.compareUnsigned(doubled, 0x7fefffff) < 0 ? doubled : 0x7fefffff;
      if (capacity <= next) capacity = next;
      finite(capacity);
      require(world, "fade capacity binding").setCapacity(list, capacity);
      storage = list.entries;
      list.size = next;
      storage = require(storage, "current fade resized storage");
    } else {
      list.size = next;
    }
    if (Integer.compareUnsigned(index, storage.length) >= 0)
      throw new IllegalArgumentException("Missing fade effect append slot");
    storage[index] = effect;
  }

  private static References effects(FadeFinished event) {
    if (!(event.effects instanceof References list))
      throw new IllegalArgumentException("Unmatched explicit fade effect list projection");
    return list;
  }

  private static void finite(int count) {
    if (count < 0 || count > LIMIT)
      throw new IllegalArgumentException("Excessive finite fade effect list");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
