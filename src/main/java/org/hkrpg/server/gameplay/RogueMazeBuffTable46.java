package org.hkrpg.server.gameplay;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit original standard-word table input; no automatic resource install or query task. */
public final class RogueMazeBuffTable46 {
  private RogueMazeBuffTable46() {}

  public record Key(int id, int level) {}

  public record Row(
      int id,
      int level,
      String modifier,
      String bindingKey,
      int bindingType,
      List<Long> parameters,
      int offset,
      int endOffset,
      long fieldMask) {
    public Row {
      if (id <= 0 || level < 0 || offset < 0 || endOffset <= offset || (fieldMask >>> 22) != 0)
        throw new IllegalArgumentException("Invalid original MazeBuff row");
      if (parameters != null) {
        parameters = List.copyOf(parameters);
        if (parameters.size() > 255 || parameters.stream().anyMatch(v -> (v & 1) != 0))
          throw new IllegalArgumentException("Unmatched finite standard MazeBuff parameters");
      }
    }
  }

  public static Map<Key, Row> read(byte[] data) {
    if (data == null || data.length == 0 || data.length > 8 * 1024 * 1024)
      throw new IllegalArgumentException("Invalid finite MazeBuff table input");
    return new Reader(data.clone()).table();
  }

  private static final class Reader {
    private final byte[] data;
    private int pos;

    private Reader(byte[] data) {
      this.data = data;
    }

    private Map<Key, Row> table() {
      long count = signed(32);
      if (count < 0 || count > 200000)
        throw new IllegalArgumentException("Excessive finite MazeBuff table rows");
      var rows = new LinkedHashMap<Key, Row>();
      for (int index = 0; index < count; index++) {
        int offset = pos;
        long mask = unsigned();
        if ((mask >>> 22) != 0) throw new IllegalArgumentException("Unmatched MazeBuff field mask");
        int id = 0, level = 0, bindingType = 0;
        String modifier = null, bindingKey = null;
        List<Long> parameters = null;
        // Serialized order comes from MazeBuffRow.FromBinary, not metadata field order.
        for (int bit = 0; bit < 22; bit++) {
          if ((mask & (1L << bit)) == 0) continue;
          switch (bit) {
            case 0 -> id = identity();
            case 3 -> level = identity();
            case 1, 2, 4, 9, 19 -> uint32();
            case 5 -> modifier = string();
            case 7 -> bindingKey = string();
            case 10, 15 -> string();
            case 6 -> bindingType = enumeration(8);
            case 8 -> parameters = parameters();
            case 11, 12, 13, 14 -> {
              signed(32); // TextID cache index; neither it nor its hash is a parameter.
              unsigned();
            }
            case 16, 17 -> enumeration(6);
            case 18 -> enumeration(3);
            case 20 -> enumeration(2);
            case 21 -> {
              if (byteValue() > 1) throw new IllegalArgumentException("Invalid MazeBuff boolean");
            }
            default -> throw new IllegalArgumentException("Unmatched MazeBuff field");
          }
        }
        var row =
            new Row(id, level, modifier, bindingKey, bindingType, parameters, offset, pos, mask);
        if (rows.putIfAbsent(new Key(id, level), row) != null)
          throw new IllegalArgumentException("Repeated MazeBuff ID and level");
      }
      if (pos != data.length) throw new IllegalArgumentException("Unconsumed MazeBuff table bytes");
      return Collections.unmodifiableMap(rows);
    }

    private List<Long> parameters() {
      long size = signed(32);
      if (size == -1) return null;
      if (size < 0 || size > 255)
        throw new IllegalArgumentException("Excessive finite MazeBuff parameter array");
      var values = new ArrayList<Long>((int) size);
      for (int i = 0; i < size; i++) {
        long value = signed(64);
        if ((value & 1) != 0)
          throw new IllegalArgumentException("Extended MazeBuff parameter is unmatched");
        values.add(value);
      }
      return values;
    }

    private int identity() {
      long value = uint32();
      if (value > Integer.MAX_VALUE)
        throw new IllegalArgumentException("MazeBuff identity exceeds project integer scope");
      return (int) value;
    }

    private int enumeration(int maximum) {
      long value = signed(32);
      if (value < 0 || value > maximum)
        throw new IllegalArgumentException("Unmatched MazeBuff enumeration");
      return (int) value;
    }

    private String string() {
      long size = unsigned();
      if (size < 0 || size > 65536 || size > data.length - pos)
        throw new IllegalArgumentException("Invalid MazeBuff string length");
      try {
        String value =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, pos, (int) size))
                .toString();
        pos += (int) size;
        return value;
      } catch (CharacterCodingException malformed) {
        throw new IllegalArgumentException("Invalid MazeBuff UTF8 string", malformed);
      }
    }

    private long uint32() {
      long value = unsigned();
      if ((value >>> 32) != 0) throw new IllegalArgumentException("MazeBuff UInt32 overflow");
      return value;
    }

    private long signed(int bits) {
      long encoded = unsigned();
      long value = (encoded >>> 1) ^ -(encoded & 1);
      if (bits == 32 && (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE))
        throw new IllegalArgumentException("MazeBuff Int32 overflow");
      return value;
    }

    private long unsigned() {
      long value = 0;
      for (int ordinal = 0; ordinal < 10; ordinal++) {
        int next = byteValue();
        if (ordinal == 9 && next > 1)
          throw new IllegalArgumentException("MazeBuff UInt64 overflow");
        value |= (long) (next & 127) << (ordinal * 7);
        if (next < 128) {
          if (ordinal > 0 && next == 0)
            throw new IllegalArgumentException("Noncanonical MazeBuff integer");
          return value;
        }
      }
      throw new IllegalArgumentException("Excessive MazeBuff integer length");
    }

    private int byteValue() {
      if (pos >= data.length) throw new IllegalArgumentException("Truncated MazeBuff table");
      return data[pos++] & 255;
    }
  }
}
