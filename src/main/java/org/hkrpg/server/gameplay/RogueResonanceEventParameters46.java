package org.hkrpg.server.gameplay;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Standard parameter transfer from an explicit source context; does not create a battle event. */
public final class RogueResonanceEventParameters46 {
  private RogueResonanceEventParameters46() {}

  public record Binding(String name, RogueResonanceDynamicCell46.Scalar value) {
    public Binding {
      RogueResonanceVariables46.nameKey(name);
      Objects.requireNonNull(value, "Missing event parameter scalar");
    }

    public int key() {
      return RogueResonanceVariables46.nameKey(name);
    }
  }

  public static Map<Integer, Long> evaluate(
      List<Binding> bindings, RogueResonanceLookup46 sourceContext) {
    if (bindings == null || bindings.isEmpty()) return Map.of();
    if (bindings.size() > 255 || sourceContext == null)
      throw new IllegalArgumentException("Invalid finite event parameter transfer");
    List<Binding> selected;
    try {
      selected = List.copyOf(bindings);
    } catch (NullPointerException malformed) {
      throw new IllegalArgumentException("Missing event parameter binding", malformed);
    }
    if (selected.size() > 255)
      throw new IllegalArgumentException("Invalid finite event parameter transfer");
    // The matched original dictionaries have distinct output hashes. Other collision
    // behavior and mutable native dictionary enumeration are outside this projection.
    var keys = new HashSet<Integer>();
    for (var binding : selected)
      if (!keys.add(binding.key()))
        throw new IllegalArgumentException("Unmatched repeated event parameter hash");
    var result = new LinkedHashMap<Integer, Long>();
    for (var binding : selected) {
      long raw = binding.value().read(sourceContext::read);
      if ((raw & 1) != 0)
        throw new IllegalArgumentException("Extended event parameter value is unmatched");
      result.put(binding.key(), raw);
    }
    return Collections.unmodifiableMap(result);
  }
}
