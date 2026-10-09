package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueCharacterWeakness46.*;

import java.util.Arrays;
import java.util.List;

/**
 * Ordinary reset, copy and special weakness paths; original allocation and hotfixes stay external.
 */
public final class RogueCharacterWeaknessRestore46 {
  private RogueCharacterWeaknessRestore46() {}

  public static final class SpecialEvent {
    public boolean enabled;
    public int type;
    public Object entity;

    public SpecialEvent(boolean enabled, int type) {
      this.enabled = enabled;
      this.type = type;
    }
  }

  public static void initializeDefault(State s, int[] damageTypes, World world) {
    required(s, "weakness state");
    int result = 0;
    if (damageTypes != null) {
      bounded(damageTypes.length);
      for (int type : damageTypes) result |= type;
    }
    s.defaultMask = result;
    refresh(s, OVERRIDE);
    refreshActive(s, world);
  }

  public static void reset(State s, int op, int damageMask, World world) {
    required(s, "weakness state");
    operation(op);
    int[] current = required(stack(s, op), "current reset stack");
    for (int index = 0; index < current.length; index++) {
      bounded(index + 1);
      boolean[] currentOccupy = required(occupy(s, op), "current reset occupancy");
      if (index >= currentOccupy.length)
        throw new IndexOutOfBoundsException("Short reset occupancy");
      if (currentOccupy[index]) current[index] &= ~damageMask;
      current = required(stack(s, op), "current reset stack");
    }
    refresh(s, op);
    refreshActive(s, world);
  }

  public static void copyFrom(State target, State source, int packedOptions, World world) {
    required(target, "target weakness state");
    required(source, "source weakness state");
    target.overrideMask = source.overrideMask;
    target.defaultMask = source.defaultMask;
    target.overrideStack = copy(source.overrideStack, target.overrideStack);
    target.overrideOccupy = copy(source.overrideOccupy, target.overrideOccupy);
    refresh(target, OVERRIDE);
    target.attachMask = source.attachMask;
    target.attachStack = copy(source.attachStack, target.attachStack);
    target.attachOccupy = copy(source.attachOccupy, target.attachOccupy);
    refresh(target, ATTACH);
    if ((packedOptions & 1) != 0) {
      target.protectMask = source.protectMask;
      target.protectStack = copy(source.protectStack, target.protectStack);
      target.protectOccupy = copy(source.protectOccupy, target.protectOccupy);
      refresh(target, PROTECTED);
    }
    if ((packedOptions & 0x100) != 0) {
      target.secretMask = source.secretMask;
      target.secretStack = copy(source.secretStack, target.secretStack);
      target.secretOccupy = copy(source.secretOccupy, target.secretOccupy);
      refresh(target, SECRET);
    }
    target.activeDirty = true;
    target.activeMask = 0;
    required(target.allWithState, "copy target view list").clear();
    target.allMask = 0;
    refreshActive(target, world);
  }

  public static void updateSpecial(State s, SpecialEvent event) {
    required(s, "weakness state");
    required(event, "special weakness event");
    List<Integer> captured = required(s.specialWeaknesses, "special weakness list");
    bounded(captured.size());
    if (event.enabled) {
      if (captured.contains(event.type)) return;
      List<Integer> current = required(s.specialWeaknesses, "current special weakness list");
      if (current.size() >= 255) throw new IllegalArgumentException("Excessive special weaknesses");
      current.add(event.type);
    } else if (captured.contains(event.type)) {
      required(s.specialWeaknesses, "current special weakness list")
          .remove(Integer.valueOf(event.type));
    }
  }

  private static int[] copy(int[] source, int[] target) {
    required(source, "source weakness stack");
    required(target, "target weakness stack");
    bounded(source.length);
    bounded(target.length);
    if (source.length > target.length) return source.clone();
    System.arraycopy(source, 0, target, 0, source.length);
    Arrays.fill(target, source.length, target.length, 0);
    return target;
  }

  private static boolean[] copy(boolean[] source, boolean[] target) {
    required(source, "source weakness occupancy");
    required(target, "target weakness occupancy");
    bounded(source.length);
    bounded(target.length);
    if (source.length > target.length) return source.clone();
    System.arraycopy(source, 0, target, 0, source.length);
    Arrays.fill(target, source.length, target.length, false);
    return target;
  }

  private static int[] stack(State s, int op) {
    return switch (op) {
      case OVERRIDE -> s.overrideStack;
      case PROTECTED -> s.protectStack;
      case ATTACH -> s.attachStack;
      default -> s.secretStack;
    };
  }

  private static boolean[] occupy(State s, int op) {
    return switch (op) {
      case OVERRIDE -> s.overrideOccupy;
      case PROTECTED -> s.protectOccupy;
      case ATTACH -> s.attachOccupy;
      default -> s.secretOccupy;
    };
  }

  private static void operation(int op) {
    if (Integer.compareUnsigned(op, 3) > 0)
      throw new IllegalArgumentException("Unknown weakness operation");
  }

  private static void bounded(int size) {
    if (size > 255) throw new IllegalArgumentException("Excessive finite weakness collection");
  }

  private static <T> T required(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
