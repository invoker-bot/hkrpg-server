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
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;

/** Explicit standard-word event tables and wave-stage preparation; no live entity factory. */
public final class RogueBattleEventTable46 {
  private RogueBattleEventTable46() {}

  private static final Set<Integer> PROPERTY_TYPES =
      Set.of(
          0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
          25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46,
          47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68,
          69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
          91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110,
          111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128,
          129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146,
          147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164,
          165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182,
          183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200,
          201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218,
          219, 220, 221, 222, 223, 224, 225, 226);

  public record Property(int type, long raw, long mask, int offset, int endOffset) {
    public Property {
      if (!PROPERTY_TYPES.contains(type) || (mask >>> 2) != 0 || offset < 0 || endOffset <= offset)
        throw new IllegalArgumentException("Unmatched event property identity or mask");
      standard(raw);
    }
  }

  public record Text(int cacheIndex, long hash) {}

  public record Config(
      int id,
      int team,
      int subType,
      int actionBarType,
      int buttonType,
      String name,
      String icon,
      List<String> abilities,
      List<Property> properties,
      long speed,
      boolean hardLevel,
      boolean eliteGroup,
      Text actionBarDescription,
      String description,
      List<Long> parameters,
      String assetPack,
      long mask,
      int offset,
      int endOffset) {
    public Config {
      identity(id, mask, 16, offset, endOffset);
      if (team < 0
          || team > 5
          || subType < 0
          || subType > 22
          || actionBarType < 0
          || buttonType < 0)
        throw new IllegalArgumentException("Unmatched finite event enum or integer");
      abilities = copied(abilities);
      properties = copied(properties);
      parameters = copied(parameters);
      standard(speed);
      if (parameters != null) for (long value : parameters) standard(value);
    }
  }

  public record Data(
      int id,
      String config,
      String prefab,
      String levelAreaPrefab,
      String actionBarPrefab,
      String basePoint,
      List<Integer> skills,
      boolean spReserved,
      long mask,
      int offset,
      int endOffset) {
    public Data {
      identity(id, mask, 8, offset, endOffset);
      skills = copied(skills);
      if (skills != null && skills.stream().anyMatch(v -> v < 0))
        throw new IllegalArgumentException("Unmatched finite skill identity");
    }
  }

  public record WaveStage(int id, Object sourceRow) {
    public WaveStage {
      if (id <= 0 || sourceRow == null)
        throw new IllegalArgumentException("Missing explicit original wave stage");
    }
  }

  public record Prepared(Config event, WaveStage waveStage) {}

  public static Map<Integer, Config> readConfig(byte[] raw) {
    return new Reader(raw).configTable();
  }

  public static Map<Integer, Data> readData(byte[] raw) {
    return new Reader(raw).dataTable();
  }

  public static Optional<Prepared> prepare(
      int eventId,
      Map<Integer, Config> events,
      int currentWaveStageId,
      IntFunction<WaveStage> stages) {
    if (events == null || eventId < 0 || currentWaveStageId < 0)
      throw new IllegalArgumentException("Invalid finite event preparation");
    var event = events.get(eventId);
    if (event == null) return Optional.empty();
    if (event.id() != eventId)
      throw new IllegalArgumentException("Mismatched explicit event identity");
    if (!event.hardLevel() && !event.eliteGroup()) return Optional.of(new Prepared(event, null));
    if (stages == null)
      throw new IllegalArgumentException("Missing explicit current-wave stage lookup");
    var stage = stages.apply(currentWaveStageId);
    if (stage == null) return Optional.empty();
    if (stage.id() != currentWaveStageId)
      throw new IllegalArgumentException("Mismatched explicit current-wave stage");
    return Optional.of(new Prepared(event, stage));
  }

  private static void identity(int id, long mask, int bits, int offset, int end) {
    if (id <= 0 || (mask >>> bits) != 0 || offset < 0 || end <= offset)
      throw new IllegalArgumentException("Invalid original event identity, mask or range");
  }

  private static void standard(long value) {
    if ((value & 1) != 0) throw new IllegalArgumentException("Extended event number is unmatched");
  }

  private static <T> List<T> copied(List<T> values) {
    if (values == null) return null;
    if (values.size() > 512) throw new IllegalArgumentException("Excessive finite event array");
    return List.copyOf(values);
  }

  private static final class Reader {
    private final byte[] data;
    private int position;

    private Reader(byte[] raw) {
      if (raw == null || raw.length == 0 || raw.length > 8 * 1024 * 1024)
        throw new IllegalArgumentException("Invalid finite original event table");
      data = raw.clone();
    }

    private Map<Integer, Config> configTable() {
      int count = count();
      var values = new LinkedHashMap<Integer, Config>();
      for (int row = 0; row < count; row++) {
        int start = position;
        long mask = mask(16);
        int id = 0, team = 0, subType = 0, actionBar = 0, button = 0;
        String name = null, icon = null, description = null, asset = null;
        List<String> abilities = null;
        List<Property> properties = null;
        List<Long> parameters = null;
        long speed = 0;
        boolean hard = false, elite = false;
        Text text = null;
        for (int bit = 0; bit < 16; bit++) {
          if ((mask & (1L << bit)) == 0) continue;
          switch (bit) {
            case 0 -> id = uint();
            case 1 -> team = enumeration(5);
            case 2 -> subType = enumeration(22);
            case 3 -> actionBar = uint();
            case 4 -> button = uint();
            case 5 -> name = string();
            case 6 -> icon = string();
            case 7 -> abilities = strings();
            case 8 -> properties = properties();
            case 9 -> speed = fixed();
            case 10 -> hard = bool();
            case 11 -> elite = bool();
            case 12 -> text = new Text(sint(), unsigned());
            case 13 -> description = string();
            case 14 -> parameters = fixeds();
            case 15 -> asset = string();
            default -> throw new IllegalArgumentException("Unmatched event field");
          }
        }
        var value =
            new Config(
                id,
                team,
                subType,
                actionBar,
                button,
                name,
                icon,
                abilities,
                properties,
                speed,
                hard,
                elite,
                text,
                description,
                parameters,
                asset,
                mask,
                start,
                position);
        if (values.putIfAbsent(id, value) != null)
          throw new IllegalArgumentException("Repeated original event identity");
      }
      finish();
      return Collections.unmodifiableMap(values);
    }

    private Map<Integer, Data> dataTable() {
      int count = count();
      var values = new LinkedHashMap<Integer, Data>();
      for (int row = 0; row < count; row++) {
        int start = position;
        long mask = mask(8);
        int id = 0;
        String config = null, prefab = null, levelArea = null, actionBar = null, basePoint = null;
        List<Integer> skills = null;
        boolean reserved = false;
        for (int bit = 0; bit < 8; bit++) {
          if ((mask & (1L << bit)) == 0) continue;
          switch (bit) {
            case 0 -> id = uint();
            case 1 -> config = string();
            case 2 -> prefab = string();
            case 3 -> levelArea = string();
            case 4 -> actionBar = string();
            case 5 -> basePoint = string();
            case 6 -> skills = uints();
            case 7 -> reserved = bool();
            default -> throw new IllegalArgumentException("Unmatched event data field");
          }
        }
        var value =
            new Data(
                id, config, prefab, levelArea, actionBar, basePoint, skills, reserved, mask, start,
                position);
        if (values.putIfAbsent(id, value) != null)
          throw new IllegalArgumentException("Repeated original event data identity");
      }
      finish();
      return Collections.unmodifiableMap(values);
    }

    private List<Property> properties() {
      int n = arrayCount();
      if (n == -1) return null;
      var values = new ArrayList<Property>(n);
      for (int i = 0; i < n; i++) {
        int start = position;
        long mask = mask(2);
        int type = (mask & 1) != 0 ? sint() : 0;
        long raw = (mask & 2) != 0 ? fixed() : 0;
        values.add(new Property(type, raw, mask, start, position));
      }
      return values;
    }

    private List<String> strings() {
      int n = arrayCount();
      if (n == -1) return null;
      var values = new ArrayList<String>(n);
      for (int i = 0; i < n; i++) values.add(string());
      return values;
    }

    private List<Integer> uints() {
      int n = arrayCount();
      if (n == -1) return null;
      var values = new ArrayList<Integer>(n);
      for (int i = 0; i < n; i++) values.add(uint());
      return values;
    }

    private List<Long> fixeds() {
      int n = arrayCount();
      if (n == -1) return null;
      var values = new ArrayList<Long>(n);
      for (int i = 0; i < n; i++) values.add(fixed());
      return values;
    }

    private long mask(int bits) {
      long value = unsigned();
      if ((value >>> bits) != 0) throw new IllegalArgumentException("Unmatched event field mask");
      return value;
    }

    private int count() {
      int value = sint();
      if (value < 0 || value > 200000)
        throw new IllegalArgumentException("Excessive finite event rows");
      return value;
    }

    private int arrayCount() {
      int value = sint();
      if (value < -1 || value > 512)
        throw new IllegalArgumentException("Excessive finite event array");
      return value;
    }

    private int enumeration(int maximum) {
      int value = sint();
      if (value < 0 || value > maximum) throw new IllegalArgumentException("Unmatched event enum");
      return value;
    }

    private int uint() {
      long value = unsigned();
      if (value < 0 || value > Integer.MAX_VALUE)
        throw new IllegalArgumentException("Unmatched finite event UInt32");
      return (int) value;
    }

    private int sint() {
      long value = signed();
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
        throw new IllegalArgumentException("Event Int32 overflow");
      return (int) value;
    }

    private long fixed() {
      long value = signed();
      standard(value);
      return value;
    }

    private boolean bool() {
      int value = byteValue();
      if (value > 1) throw new IllegalArgumentException("Invalid event boolean");
      return value != 0;
    }

    private String string() {
      int size = uint();
      if (size > 65536 || size > data.length - position)
        throw new IllegalArgumentException("Invalid finite event string");
      try {
        String value =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, position, size))
                .toString();
        position += size;
        return value;
      } catch (CharacterCodingException invalid) {
        throw new IllegalArgumentException("Invalid event UTF-8", invalid);
      }
    }

    private long signed() {
      long value = unsigned();
      return (value >>> 1) ^ -(value & 1);
    }

    private long unsigned() {
      long value = 0;
      for (int i = 0; i < 10; i++) {
        int next = byteValue();
        if (i == 9 && next > 1) throw new IllegalArgumentException("Event varint overflow");
        value |= (long) (next & 127) << (i * 7);
        if (next < 128) {
          if (i > 0 && next == 0) throw new IllegalArgumentException("Noncanonical event varint");
          return value;
        }
      }
      throw new IllegalArgumentException("Unterminated event varint");
    }

    private int byteValue() {
      if (position >= data.length)
        throw new IllegalArgumentException("Truncated original event table");
      return data[position++] & 255;
    }

    private void finish() {
      if (position != data.length)
        throw new IllegalArgumentException("Unconsumed original event table bytes");
    }
  }
}
