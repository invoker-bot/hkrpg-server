package org.hkrpg.server.gameplay;

/** Ordinary stacking-mode branch only; admission, live replacement and UI still need bindings. */
public final class RogueModifierStackMode46 {
  private RogueModifierStackMode46() {}

  public static final class State {
    public final RogueModifierLifetime46.State lifetime;
    public int count, layer;

    public State(RogueModifierLifetime46.State lifetime, int count, int layer) {
      if (lifetime == null)
        throw new IllegalArgumentException("Missing explicit modifier lifetime");
      this.lifetime = lifetime;
      this.count = count;
      this.layer = layer;
    }
  }

  public interface World {
    void valueChanged(State state, int valueType);

    void onReplace(State state);
  }

  public static void applyMode(
      State state, int mode, int incomingLife, int incomingCount, World world) {
    require(state, world);
    if (mode < 0 || mode > 13) throw new IllegalArgumentException("Unmatched finite stacking mode");
    switch (mode) {
      case 2 -> {
        setCurrentLife(state, incomingLife, world);
        state.lifetime.initialLife = state.lifetime.currentLife;
      }
      case 3 -> {
        int value =
            incomingLife == -1 || state.lifetime.initialLife == -1
                ? -1
                : state.lifetime.currentLife + incomingLife;
        setCurrentLife(state, value, world);
        state.lifetime.initialLife = state.lifetime.currentLife;
      }
      case 5, 7, 8, 12 -> {
        setCurrentLife(state, incomingLife, world);
        state.lifetime.initialLife = state.lifetime.currentLife;
        setCount(state, incomingCount, world);
        world.onReplace(state);
      }
      case 6 -> {
        int value =
            incomingLife == -1 || state.lifetime.initialLife == -1
                ? -1
                : Math.max(state.lifetime.currentLife, incomingLife);
        setCurrentLife(state, value, world);
        state.lifetime.initialLife = state.lifetime.currentLife;
        world.onReplace(state);
      }
      case 10 -> {
        setCount(state, incomingCount, world);
        world.onReplace(state);
      }
      default -> {}
    }
  }

  public static void setCurrentLife(State state, int value, World world) {
    require(state, world);
    if (state.lifetime.currentLife != value) {
      state.lifetime.currentLife = value;
      world.valueChanged(state, 3);
    }
  }

  public static void setCount(State state, int value, World world) {
    require(state, world);
    if (state.count != value) {
      state.count = value;
      world.valueChanged(state, 1);
    }
  }

  public static void setLayer(State state, int value, World world) {
    require(state, world);
    if (state.layer != value) {
      state.layer = value;
      world.valueChanged(state, 0);
    }
  }

  private static void require(State state, World world) {
    if (state == null || world == null)
      throw new IllegalArgumentException("Missing explicit stacking binding");
  }
}
