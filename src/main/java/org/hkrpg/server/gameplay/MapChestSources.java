package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Generated from authored floor indices, ChestID rows and groups, never guessed from prop IDs. */
final class MapChestSources {
  private static final Set<Integer> RESERVED =
      GoldenSpearSources.merge(MascotSources.LOCATIONS, BaseZoneChestSources.LOCATIONS).keySet();
  private static final JsonObject CATALOG = readCatalog();
  static final Map<Integer, WorldChestSources.Location> LOCATIONS = locations();
  static final Map<String, String> ROWS = strings("rows");
  static final Map<String, String> FILES = strings("files");

  private record Cached(String bytes, int floor, GroupInfo group) {}

  private static final Map<Path, Cached> GROUPS = new LinkedHashMap<>(32, 0.75f, true);

  private static JsonObject readCatalog() {
    try (var stream =
            Objects.requireNonNull(
                MapChestSources.class.getResourceAsStream("/gameplay/map-chests-4.6.json"));
        var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception invalid) {
      throw new ExceptionInInitializerError(invalid);
    }
  }

  private static Map<String, String> strings(String name) {
    var result = new HashMap<String, String>();
    CATALOG
        .getAsJsonObject(name)
        .entrySet()
        .forEach(e -> result.put(e.getKey(), e.getValue().getAsString()));
    return Map.copyOf(result);
  }

  private static Map<Integer, WorldChestSources.Location> locations() {
    var result = new HashMap<Integer, WorldChestSources.Location>();
    CATALOG
        .getAsJsonObject("locations")
        .entrySet()
        .forEach(
            e -> {
              int id = Integer.parseInt(e.getKey());
              // Existing puzzle owners and historical receipt fingerprints retain their exact
              // identities.
              if (RESERVED.contains(id)) return;
              var row = e.getValue().getAsJsonObject();
              var dimensions = new HashSet<Integer>();
              row.getAsJsonArray("dimensions").forEach(d -> dimensions.add(d.getAsInt()));
              result.put(
                  id,
                  new WorldChestSources.Location(
                      row.get("plane").getAsInt(),
                      row.get("floor").getAsInt(),
                      row.get("group").getAsInt(),
                      row.get("instance").getAsInt(),
                      row.get("prop").getAsInt(),
                      row.get("path").getAsString(),
                      Set.copyOf(dimensions)));
            });
    return Map.copyOf(result);
  }

  /** Read bytes on every validation; equal mtime or size must not conceal a changed source. */
  static synchronized GroupInfo group(Path root, String relative, int floorId, int id) {
    try {
      String pin = FILES.get(relative);
      var file = root.resolve(relative).normalize();
      if (pin == null
          || !file.startsWith(root)
          || !Files.isRegularFile(file)
          || Files.size(file) > 2 * 1024 * 1024
          || !file.toRealPath().startsWith(root.toRealPath())) return null;
      var bytes = Files.readAllBytes(file);
      String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      var cached = GROUPS.get(file);
      if (cached != null && cached.bytes().equals(digest) && cached.floor() == floorId && cached.group().getId() == id)
        return cached.group();
      var raw = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
      if (!pin.equals(TimelineActivityRules.fingerprint(raw))) return null;
      var group = ResourceLoader.getGson().fromJson(raw, GroupInfo.class);
      group.setId(id);
      var floor = new FloorInfo(floorId);
      floor.getGroups().put(id, group);
      floor.onLoad();
      if (GROUPS.size() >= 256) GROUPS.remove(GROUPS.keySet().iterator().next());
      GROUPS.put(file, new Cached(digest, floorId, group));
      return group;
    } catch (Exception invalid) {
      return null;
    }
  }

  private MapChestSources() {}
}
