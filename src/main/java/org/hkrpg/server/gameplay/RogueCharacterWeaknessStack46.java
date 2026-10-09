package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueCharacterWeakness46.*;

/** Ordinary finite slot allocation and growth; initial native array factories stay external. */
public final class RogueCharacterWeaknessStack46 {
  private RogueCharacterWeaknessStack46() {}

  public static int tryAvailable(State s, int operation) {
    validate(s, operation);
    boolean[] captured = required(occupy(s, operation), "captured weakness occupancy");
    for (int index = 0; index < captured.length; index++) {
      bounded(index);
      if (!captured[index]) return index;
    }
    return -1;
  }

  public static int available(State s, int operation) {
    int index = tryAvailable(s, operation);
    if (index != -1) return index;
    reserve(s, operation);
    return tryAvailable(s, operation);
  }

  public static void reserve(State s, int operation) {
    validate(s, operation);
    int length = required(stack(s, operation), "weakness stack before growth").length;
    if (length > 127) throw new IllegalArgumentException("Excessive finite weakness stack growth");
    int size = length * 2;
    int[] nextStack = new int[size];
    boolean[] nextOccupy = new boolean[size];
    int[] current = required(stack(s, operation), "current weakness stack copy");
    System.arraycopy(current, 0, nextStack, 0, current.length);
    boolean[] currentOccupy = required(occupy(s, operation), "current weakness occupancy copy");
    System.arraycopy(currentOccupy, 0, nextOccupy, 0, currentOccupy.length);
    stack(s, operation, nextStack);
    occupy(s, operation, nextOccupy);
  }

  public static int add(State s, int operation, int damageMask, World world) {
    int index = available(s, operation);
    boolean[] currentOccupy = required(occupy(s, operation), "current weakness slot occupancy");
    index(index, currentOccupy.length);
    currentOccupy[index] = true;
    int[] current = required(stack(s, operation), "current weakness slot stack");
    index(index, current.length);
    current[index] = damageMask;
    refresh(s, operation);
    refreshActive(s, world);
    return index;
  }

  private static void validate(State s, int op) {
    required(s, "character weakness state");
    if (Integer.compareUnsigned(op, 3) > 0)
      throw new IllegalArgumentException("Unmatched weakness operation");
  }

  private static void index(int index, int length) {
    if (Integer.compareUnsigned(index, length) >= 0)
      throw new IndexOutOfBoundsException("Invalid unsigned weakness slot index");
    bounded(index);
  }

  private static void bounded(int index) {
    if (index >= 255) throw new IllegalArgumentException("Excessive finite weakness slot search");
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

  private static void stack(State s, int op, int[] value) {
    switch (op) {
      case OVERRIDE -> s.overrideStack = value;
      case PROTECTED -> s.protectStack = value;
      case ATTACH -> s.attachStack = value;
      default -> s.secretStack = value;
    }
  }

  private static void occupy(State s, int op, boolean[] value) {
    switch (op) {
      case OVERRIDE -> s.overrideOccupy = value;
      case PROTECTED -> s.protectOccupy = value;
      case ATTACH -> s.attachOccupy = value;
      default -> s.secretOccupy = value;
    }
  }

  private static <T> T required(T value, String name) {
    if (value == null) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
