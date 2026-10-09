package org.hkrpg.server.gameplay;

import java.util.List;

/**
 * Ordinary explicit weakness stacks and masks; catalog, event factory and live world stay bound.
 */
public final class RogueCharacterWeakness46 {
  private RogueCharacterWeakness46() {}

  public static final int OVERRIDE = 0, PROTECTED = 1, ATTACH = 2, SECRET = 3;

  public record View(int damageType, int kind) {}

  public static final class State {
    public int[] overrideStack, protectStack, attachStack, secretStack;
    public boolean[] overrideOccupy, protectOccupy, attachOccupy, secretOccupy;
    public int overrideMask, protectMask, attachMask, secretMask, defaultMask, activeMask, allMask;
    public boolean activeDirty, notifyChangeEnable;
    public Object owner;
    public List<View> allWithState;
    public List<Integer> specialWeaknesses;
  }

  public interface World {
    int[] damageTypes();

    void notifyWeaknessChanged(Object owner, int eventId, boolean immediate);
  }

  public static void unstack(State s, int operation, int index, World world) {
    state(s);
    operation(operation);
    boolean[] occupy = required(occupy(s, operation), "weakness occupancy");
    index(index, occupy.length);
    occupy[index] = false;
    int[] stack = required(stack(s, operation), "weakness stack");
    index(index, stack.length);
    stack[index] = 0;
    refresh(s, operation);
    refreshActive(s, world);
  }

  public static void refresh(State s, int operation) {
    state(s);
    operation(operation);
    int previous = mask(s, operation);
    mask(s, operation, 0);
    int[] stack = required(stack(s, operation), "current weakness stack");
    boolean occupied = false;
    for (int index = 0; index < stack.length; index++) {
      bounded(index);
      boolean[] occupy = required(occupy(s, operation), "current weakness occupancy");
      index(index, occupy.length);
      if (occupy[index]) {
        mask(s, operation, mask(s, operation) | stack[index]);
        occupied = true;
      }
      stack = required(stack(s, operation), "current weakness stack");
    }
    if (operation == OVERRIDE && !occupied) mask(s, operation, s.defaultMask);
    if (operation == SECRET)
      mask(s, operation, mask(s, operation) & (s.attachMask | s.overrideMask));
    if (previous != mask(s, operation)) s.activeDirty = true;
  }

  public static void refreshActive(State s, World world) {
    state(s);
    if (!s.activeDirty) return;
    int base = s.attachMask | s.overrideMask;
    s.activeMask = base & ~s.protectMask;
    required(s.allWithState, "weakness view list").clear();
    s.allMask = 0;
    int index = 0;
    while (true) {
      int[] types =
          required(
              required(world, "weakness catalog binding").damageTypes(),
              "current weakness catalog");
      if (index >= types.length) break;
      bounded(index);
      int type = types[index++];
      if ((type & base) <= 0) continue;
      int kind =
          (type & s.secretMask) > 0
              ? 3
              : (type & s.protectMask) > 0 ? 2 : (type & s.activeMask) > 0 ? 1 : 0;
      if (kind == 0) continue;
      List<View> current = required(s.allWithState, "current weakness view list");
      if (current.size() >= 255)
        throw new IllegalArgumentException("Excessive finite weakness views");
      current.add(new View(type, kind));
      s.allMask |= type;
    }
    if (s.notifyChangeEnable) world.notifyWeaknessChanged(s.owner, 0x453, true);
    s.activeDirty = false;
  }

  private static void operation(int op) {
    if (Integer.compareUnsigned(op, 3) > 0)
      throw new IllegalArgumentException("Unmatched weakness operation");
  }

  private static void index(int index, int length) {
    if (Integer.compareUnsigned(index, length) >= 0)
      throw new IndexOutOfBoundsException("Invalid unsigned weakness stack index");
    bounded(index);
  }

  private static void bounded(int index) {
    if (index >= 255)
      throw new IllegalArgumentException("Excessive finite weakness stacks or catalog");
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

  private static int mask(State s, int op) {
    return switch (op) {
      case OVERRIDE -> s.overrideMask;
      case PROTECTED -> s.protectMask;
      case ATTACH -> s.attachMask;
      default -> s.secretMask;
    };
  }

  private static void mask(State s, int op, int value) {
    switch (op) {
      case OVERRIDE -> s.overrideMask = value;
      case PROTECTED -> s.protectMask = value;
      case ATTACH -> s.attachMask = value;
      default -> s.secretMask = value;
    }
  }

  private static void state(State s) {
    required(s, "character weakness state");
  }

  private static <T> T required(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
