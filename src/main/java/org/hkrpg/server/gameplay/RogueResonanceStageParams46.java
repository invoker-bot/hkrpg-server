package org.hkrpg.server.gameplay;

import java.util.List;
import java.util.Objects;

/** Finite standard-word projection of the native StageParamEntry builders. */
public final class RogueResonanceStageParams46 {
  private RogueResonanceStageParams46() {}

  public record Entry(String name, long raw) {
    public Entry {
      RogueResonanceVariables46.nameKey(name);
      if ((raw & 1) != 0)
        throw new IllegalArgumentException("Extended stage parameter is unmatched");
    }

    public int key() {
      return RogueResonanceVariables46.nameKey(name);
    }
  }

  public static Entry[] create(long[] source) {
    if (source == null || source.length == 0) return null;
    validateLength(source);
    var entries = new Entry[source.length];
    assign(source, entries, 0);
    return entries;
  }

  public static void assign(long[] source, Entry[] destination, int startIndex) {
    if (!active(source, startIndex)) return;
    Objects.requireNonNull(destination, "Missing stage parameter array");
    for (int i = startIndex; i < source.length; i++)
      destination[i] = new Entry("#ADF_" + (i + 1), source[i]);
  }

  public static void append(long[] source, List<Entry> destination, int startIndex) {
    if (!active(source, startIndex)) return;
    Objects.requireNonNull(destination, "Missing stage parameter list");
    for (int i = startIndex; i < source.length; i++)
      destination.add(new Entry("#ADF_" + (i + 1), source[i]));
  }

  private static boolean active(long[] source, int startIndex) {
    if (source == null || source.length == 0 || startIndex >= source.length) return false;
    if (startIndex < 0) throw new IndexOutOfBoundsException("Negative stage parameter index");
    validateLength(source);
    return true;
  }

  private static void validateLength(long[] source) {
    if (source.length > 255)
      throw new IllegalArgumentException("Excessive finite stage parameter source");
  }
}
