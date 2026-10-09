package org.hkrpg.server.gameplay;

import java.util.List;
import java.util.OptionalLong;

/**
 * Finite original decision order; world, immunity tables, events and random state require bindings.
 */
public final class RogueModifierChanceRoll46 {
  private static final long ONE = 1L << 33;

  private RogueModifierChanceRoll46() {}

  @FunctionalInterface
  public interface Definition {
    List<Integer> behaviorFlags();
  }

  public record Input(
      Object target,
      Object source,
      String modifierName,
      long chance,
      long extraHit,
      Definition definition) {}

  public record Result(boolean successful, boolean baseHit, long actualChance) {
    public boolean resisted() {
      return baseHit && !successful;
    }
  }

  public interface World {
    int statusType(String modifierName);

    Object targetComponent(Object target);

    Object actualOwner(Object source);

    boolean hasBehavior(Object component, int flag);

    Object summonerEntity(Object target);

    void notifyShieldConvert(Object component, Object actualSourceOwner);

    boolean behaviorImmune(Object component, Object source, List<Integer> flags);

    boolean statusImmune(Object component, int statusType);

    List<Integer> emptyBehaviorFlags();

    void notifyImmune(Object component, Object source, List<Integer> flags);

    long sample(Object target);

    List<Integer> antiFlags(Object actualSourceOwner);

    long statusResistance(Object component);

    OptionalLong behaviorResistance(Object component, int flag);

    void releaseAntiFlags(List<Integer> flags);

    void executeResistSequence(Object component, List<Integer> flags, Object source);
  }

  public static Result decide(Input input, World world) {
    if (input == null || world == null)
      throw new IllegalArgumentException("Missing explicit chance world binding");
    int statusType = world.statusType(input.modifierName());
    if (input.target() == null) throw new IllegalArgumentException("Missing chance target");
    Object component = world.targetComponent(input.target());
    if (input.definition() == null)
      throw new IllegalArgumentException("Missing explicit modifier definition");
    Definition definition = input.definition();
    if (contains(definition.behaviorFlags(), 69)) return new Result(true, true, -ONE);
    boolean shield = contains(definition.behaviorFlags(), 10);
    Object actualOwner = world.actualOwner(input.source());
    if (shield) {
      requireComponent(component);
      if (world.hasBehavior(component, 70)
          && actualOwner != input.target()
          && world.summonerEntity(input.target()) != actualOwner) return new Result(false, true, 0);
      if (world.hasBehavior(component, 83)) {
        world.notifyShieldConvert(component, actualOwner);
        return new Result(false, true, 0);
      }
    }
    boolean behaviorImmune =
        world.behaviorImmune(component, input.source(), definition.behaviorFlags());
    boolean statusImmune = world.statusImmune(component, statusType);
    if (behaviorImmune || statusImmune) {
      if (statusType == 2) {
        List<Integer> flags = definition.behaviorFlags();
        if (flags == null) {
          flags = world.emptyBehaviorFlags();
          if (flags == null || !flags.isEmpty())
            throw new IllegalArgumentException("Missing explicit empty behavior list");
        }
        world.notifyImmune(component, input.source(), flags);
      }
      return new Result(false, true, 0);
    }
    long chance = standard(input.chance());
    if (chance < 0) return new Result(true, true, -ONE);
    long base =
        RogueResonanceExpression46.multiplyStandard(
            chance, RogueResonanceExpression46.addStandard(ONE, standard(input.extraHit())));
    long sampled = draw(world.sample(input.target()));
    List<Integer> anti = null;
    List<Integer> flags = definition.behaviorFlags();
    if (flags != null && !flags.isEmpty() && actualOwner != null)
      anti = world.antiFlags(actualOwner);
    requireComponent(component);
    long actual =
        RogueResonanceExpression46.multiplyStandard(
            base,
            RogueModifierResistance46.statusFactor(
                () -> world.statusResistance(component),
                definition.behaviorFlags(),
                f -> world.behaviorResistance(component, f),
                anti));
    boolean baseHit = sampled <= base;
    boolean successful = sampled <= actual;
    if (anti != null) world.releaseAntiFlags(anti);
    if (baseHit && !successful)
      world.executeResistSequence(component, definition.behaviorFlags(), input.source());
    return new Result(successful, baseHit, actual);
  }

  private static boolean contains(List<Integer> flags, int sought) {
    if (flags == null) return false;
    for (int i = 0; i < flags.size(); i++) {
      if (i >= 255 || flags.get(i) == null)
        throw new IllegalArgumentException("Invalid finite behavior flags");
      if (flags.get(i) == sought) return true;
    }
    return false;
  }

  private static void requireComponent(Object component) {
    if (component == null) throw new IllegalArgumentException("Missing chance component");
  }

  private static long standard(long value) {
    if ((value & 1L) != 0) throw new IllegalArgumentException("Extended chance value is unmatched");
    return value;
  }

  private static long draw(long value) {
    standard(value);
    if (value < 0 || value >= ONE)
      throw new IllegalArgumentException("Unmatched projected random sample");
    return value;
  }
}
