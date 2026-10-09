package org.hkrpg.server.gameplay;

import static org.hkrpg.server.gameplay.RogueCharacterWeakness46.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordinary component construction, fixed catalog and queries; notification dispatch stays bound.
 */
public final class RogueCharacterWeaknessLifecycle46 {
  private RogueCharacterWeaknessLifecycle46() {}

  public static final int INITIAL_CAPACITY = 4;

  public static final class Catalog {
    public int[] types = {1, 2, 4, 8, 16, 32, 64};
  }

  @FunctionalInterface
  public interface Notifications {
    void send(Object owner, int eventId, boolean immediate);
  }

  public static State create(Object owner) {
    State s = new State();
    s.specialWeaknesses = new ArrayList<>();
    s.notifyChangeEnable = true;
    s.owner = owner;
    s.defaultMask = 0;
    s.overrideStack = new int[INITIAL_CAPACITY];
    s.overrideOccupy = new boolean[INITIAL_CAPACITY];
    s.overrideMask = 0;
    s.attachStack = new int[INITIAL_CAPACITY];
    s.attachOccupy = new boolean[INITIAL_CAPACITY];
    s.attachMask = 0;
    s.protectStack = new int[INITIAL_CAPACITY];
    s.protectOccupy = new boolean[INITIAL_CAPACITY];
    s.protectMask = 0;
    s.secretStack = new int[INITIAL_CAPACITY];
    s.secretOccupy = new boolean[INITIAL_CAPACITY];
    s.secretMask = 0;
    s.allMask = 0;
    s.activeMask = 0;
    s.allWithState = new ArrayList<>();
    return s;
  }

  public static World world(Catalog catalog, Notifications notifications) {
    required(catalog);
    return new World() {
      @Override
      public int[] damageTypes() {
        return catalog.types;
      }

      @Override
      public void notifyWeaknessChanged(Object owner, int eventId, boolean immediate) {
        required(notifications).send(owner, eventId, immediate);
      }
    };
  }

  public static void dispose(State s) {
    required(s).owner = null;
    List<View> captured = s.allWithState;
    if (captured != null) captured.clear();
    s.secretStack = null;
    s.attachStack = null;
    s.protectStack = null;
    s.overrideOccupy = null;
    s.secretOccupy = null;
    s.overrideStack = null;
    s.attachOccupy = null;
    s.protectOccupy = null;
  }

  public static int defaultMask(State s) {
    return required(s).defaultMask;
  }

  public static int overrideMask(State s) {
    return required(s).overrideMask;
  }

  public static int attachedMask(State s) {
    return required(s).attachMask;
  }

  public static int secretMask(State s) {
    return required(s).secretMask;
  }

  public static int activeMask(State s) {
    return required(s).activeMask;
  }

  public static int allMask(State s) {
    return required(s).allMask;
  }

  public static List<View> allViews(State s) {
    return required(s).allWithState;
  }

  public static List<Integer> special(State s) {
    return required(s).specialWeaknesses;
  }

  public static boolean isProtected(State s, int damageMask) {
    return (required(s).protectMask & damageMask) > 0;
  }

  public static boolean isSecret(State s, int damageMask) {
    return (required(s).secretMask & damageMask) > 0;
  }

  public static boolean hasSecret(State s) {
    return required(s).secretMask != 0;
  }

  private static <T> T required(T value) {
    if (value == null) throw new IllegalArgumentException("Missing weakness lifecycle binding");
    return value;
  }
}
