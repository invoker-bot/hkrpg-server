package org.hkrpg.server.gameplay;

import java.util.function.Supplier;
import org.hkrpg.server.gameplay.RogueBaseOtherModifierEvents46.Destroyed;
import org.hkrpg.server.gameplay.RogueBaseOtherModifierEvents46.FadeFinished;
import org.hkrpg.server.gameplay.RogueOwnedParameterLists46.References;

/** Ordinary construction, range initialization and queries of the two matched modifier payloads. */
public final class RogueOtherEventPayload46 {
  public static final int DESTROYED = 207, FADE_FINISHED = 208, LIMIT = 4096;

  private RogueOtherEventPayload46() {}

  public static void constructEffects(References list, Supplier<Object[]> sharedEmptyArray) {
    References target = require(list, "effect list");
    Object[] empty = require(sharedEmptyArray, "shared empty effect array").get();
    target.entries = empty;
  }

  public interface World {
    References allocateEffects();

    void initializeEffects(References list);

    void clear(Object[] storage, int start, int count);

    void insertRange(References list, int index, Object sourceEffects);

    int runtimeId(Object sourceEntity);
  }

  public static void construct(Destroyed event, World world) {
    World bound = require(world, "destroyed effect list factory");
    References list = bound.allocateEffects();
    bound.initializeEffects(list);
    require(event, "destroyed payload").effects = list;
  }

  public static void construct(FadeFinished event, World world) {
    World bound = require(world, "fade effect list factory");
    References list = bound.allocateEffects();
    bound.initializeEffects(list);
    require(event, "fade payload").effects = list;
  }

  public static Destroyed initialize(
      Destroyed event, Object sourceEntity, Object sourceEffects, Object modifier, World world) {
    require(event, "destroyed payload");
    event.sourceEntity = sourceEntity;
    References list = effects(event.effects);
    list.version++;
    int previous = list.size;
    list.size = 0;
    if (previous > 0) {
      finite(previous);
      require(world, "destroyed clear binding").clear(list.entries, 0, previous);
      list = effects(event.effects);
    }
    int index = list.size;
    require(world, "destroyed insert range binding").insertRange(list, index, sourceEffects);
    event.modifier = modifier;
    return event;
  }

  public static FadeFinished initialize(
      FadeFinished event, Object sourceEntity, Object sourceEffects, Object modifier, World world) {
    require(event, "fade payload");
    event.sourceEntity = sourceEntity;
    References list = effects(event.effects);
    list.version++;
    int previous = list.size;
    list.size = 0;
    if (previous > 0) {
      finite(previous);
      require(world, "fade range clear binding").clear(list.entries, 0, previous);
      list = effects(event.effects);
    }
    int index = list.size;
    require(world, "fade insert range binding").insertRange(list, index, sourceEffects);
    event.modifier = modifier;
    return event;
  }

  public static int sourceRuntimeId(Destroyed event, World world) {
    Object source =
        require(require(event, "destroyed payload").sourceEntity, "destroyed source entity");
    return require(world, "destroyed source runtime identifier").runtimeId(source);
  }

  public static int sourceRuntimeId(FadeFinished event, World world) {
    Object source = require(require(event, "fade payload").sourceEntity, "fade source entity");
    return require(world, "fade source runtime identifier").runtimeId(source);
  }

  public static int eventType(Destroyed event) {
    return DESTROYED;
  }

  public static int eventType(FadeFinished event) {
    return FADE_FINISHED;
  }

  private static References effects(Object value) {
    if (!(value instanceof References list))
      throw new IllegalArgumentException("Unmatched effect list projection");
    return list;
  }

  private static void finite(int count) {
    if (count > LIMIT)
      throw new IllegalArgumentException("Excessive finite modifier event effects");
  }

  private static <T> T require(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
