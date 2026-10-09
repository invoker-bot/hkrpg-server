package org.hkrpg.server.gameplay;

/** Integer modifier bindings; the real value arrays, contexts and mixed cells remain separate. */
public final class RogueModifierValueBinding46 {
  private RogueModifierValueBinding46() {}

  public static final class State {
    public final RogueModifierStackMode46.State modifier;
    public int[] keys;
    public int mask, baseMaxLayer, extraMaxLayer;
    public Object dynamicValues;

    public State(RogueModifierStackMode46.State modifier) {
      if (modifier == null) throw new IllegalArgumentException("Missing explicit modifier values");
      this.modifier = modifier;
    }
  }

  @FunctionalInterface
  public interface World {
    void setVariable(Object values, int key, long raw);
  }

  public static void notifyValue(State state, int valueType, World world) {
    if (state == null) throw new IllegalArgumentException("Missing explicit value binding state");
    int[] keys = state.keys;
    if (keys == null
        || (state.mask & (1 << valueType)) == 0
        || Integer.compareUnsigned(valueType, 3) > 0) return;
    if (keys.length <= valueType)
      throw new IllegalArgumentException("Missing matched modifier key slot");
    Object values = state.dynamicValues;
    if (values == null) throw new IllegalArgumentException("Missing modifier dynamic values");
    int key = keys[valueType];
    int value =
        switch (valueType) {
          case 0 -> state.modifier.layer;
          case 1 -> state.modifier.count;
          case 2 -> Math.max(1, state.baseMaxLayer + state.extraMaxLayer);
          case 3 -> state.modifier.lifetime.currentLife;
          default -> throw new IllegalArgumentException("Unmatched modifier value type");
        };
    if (world == null) throw new IllegalArgumentException("Missing explicit modifier value writer");
    world.setVariable(values, key, integerRaw(value));
  }

  public static long integerRaw(int value) {
    return Math.abs((long) value) < 0x40000000L
        ? ((long) value << 33)
        : (((long) value << 26) | 1L);
  }

  public static World standardVariables() {
    return (values, key, raw) -> {
      if (!(values instanceof RogueResonanceVariables46 variables))
        throw new IllegalArgumentException("Unmatched finite dynamic variable container");
      if (!variables.contains(key)) return;
      variables.write(key, raw);
    };
  }
}
