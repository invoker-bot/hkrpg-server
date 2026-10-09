package org.hkrpg.server.gameplay;

/** Ordinary binding payload defaults and Clear with an explicit shared String.Empty binding. */
public final class RogueBindingPayload46 {
  private RogueBindingPayload46() {}

  public static final class Catalog {
    public String emptyString;

    public Catalog(String emptyString) {
      this.emptyString = emptyString;
    }
  }

  public record Member(Object first, Object second, Object third) {
    public static final Member ZERO = new Member(null, null, null);
  }

  public static final class State {
    public String key;
    public Member first = Member.ZERO,
        second = Member.ZERO,
        third = Member.ZERO,
        fourth = Member.ZERO;
    public Object context, behaviorFlags;
  }

  public static State create() {
    return new State();
  }

  /** The matched registered constructor entry is a ret without field writes. */
  public static void initialize(State state) {}

  public static void clear(State state, Catalog catalog) {
    if (state == null) throw new IllegalArgumentException("Missing binding payload");
    if (catalog == null) throw new IllegalArgumentException("Missing shared empty string catalog");
    state.key = catalog.emptyString;
    state.first = Member.ZERO;
    state.second = Member.ZERO;
    state.third = Member.ZERO;
    state.fourth = Member.ZERO;
    state.context = null;
    state.behaviorFlags = null;
  }
}
