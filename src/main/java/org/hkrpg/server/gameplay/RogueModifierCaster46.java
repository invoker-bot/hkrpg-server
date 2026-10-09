package org.hkrpg.server.gameplay;

import java.util.Map;

/** Ordinary caster inheritance and transit lookup; world objects and creation remain bound. */
public final class RogueModifierCaster46 {
  private RogueModifierCaster46() {}

  public static final int INHERIT = 0, CASTER_SELF = 1, TARGET_SELF = 2;
  public static final String TRANSIT_NAME = "INTERNAL_ModifierCasterTransit";
  public static final Map<String, String> TRANSIT_CONFIG = Map.of("Name", TRANSIT_NAME);

  public interface World {
    Object ownerAbilityInstance();

    Object ownerEntity();

    Object abilityOwnerEntity(Object instance);

    boolean isTurnBasedAbility(Object instance);

    Object turnBasedAbilityComponent(Object entity);

    Object findAbilityInstance(Object component, String name);

    Object addAbilityWithInheritTarget(
        Object component, Map<String, String> config, Object inheritedTargets, Object option);
  }

  /** The six original entry tasks omit InheritCaster and therefore use mode zero. */
  public interface Bindings extends RogueModifierSelection46.Bindings, World {
    void applyInherited(RogueModifierSelection46.Qualified selection, Object abilityInstance);

    default void applyQualified(RogueModifierSelection46.Qualified selection) {
      Object ability = select(INHERIT, selection.request().targetEntity(), this);
      if (ability == null) throw new IllegalArgumentException("Missing selected caster ability");
      applyInherited(selection, ability);
    }
  }

  public static Object select(int mode, Object target, World world) {
    if (world == null) throw new IllegalArgumentException("Missing explicit caster world");
    Object entity;
    switch (mode) {
      case INHERIT -> {
        var ability = world.ownerAbilityInstance();
        if (ability != null) return cast(ability, world);
        entity = contextEntity(world);
      }
      case CASTER_SELF -> entity = world.ownerEntity();
      case TARGET_SELF -> {
        if (target == null) return null;
        entity = target;
      }
      default -> {
        return null;
      }
    }
    if (entity == null) throw new IllegalArgumentException("Missing caster entity");
    return transit(world.turnBasedAbilityComponent(entity), world);
  }

  // The native helper rereads the virtual ability getter, rather than reusing either prior result.
  private static Object contextEntity(World world) {
    if (world.ownerAbilityInstance() == null) return world.ownerEntity();
    var ability = world.ownerAbilityInstance();
    if (ability == null) throw new IllegalArgumentException("Owner ability changed to null");
    return world.abilityOwnerEntity(ability);
  }

  private static Object cast(Object ability, World world) {
    if (!world.isTurnBasedAbility(ability))
      throw new ClassCastException("Caster ability is not the original turn based type");
    return ability;
  }

  public static Object transit(Object component, World world) {
    if (world == null || component == null)
      throw new IllegalArgumentException("Missing explicit transit lookup binding");
    var existing = world.findAbilityInstance(component, TRANSIT_NAME);
    if (existing != null) return cast(existing, world);
    return world.addAbilityWithInheritTarget(component, TRANSIT_CONFIG, null, null);
  }

  public static RogueResonanceOnAdd46.ModifierFactory factory(Bindings bindings) {
    return RogueModifierSelection46.factory(bindings);
  }
}
