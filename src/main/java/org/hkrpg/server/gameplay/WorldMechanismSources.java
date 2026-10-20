package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.ResourceBase;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.Scene;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Compiled exact targets from the fixed source; no group-wide "unlock everything" fallback. */
final class WorldMechanismSources {
  enum Kind {
    GUARDS,
    CONTROLLER,
    JIGSAW,
    SPEAR,
    GROUP_PROPERTY,
    MISSION,
    CLIENT_REPORT
  }

  record Rule(
      int chest,
      Kind kind,
      PropState initial,
      Set<Integer> guards,
      int controller,
      int controllerProp,
      int finish,
      Set<Integer> interactions,
      Set<Integer> reports,
      Set<Integer> pieces,
      Set<Integer> controllers,
      int shield,
      int shieldProp,
      int controllerGroup,
      String controllerPath,
      String property,
      int propertyValue,
      String floorValue,
      String lateFloorValue,
      int mission,
      int controllerMission,
      Set<String> files,
      Set<String> rows,
      String source) {}

  private static final JsonObject CATALOG = catalog();
  static final Map<Integer, Rule> RULES = rules();

  private record Cached(String digest, JsonElement raw) {}

  private static final Map<Path, Cached> CACHE = new LinkedHashMap<>(32, 0.75f, true);

  private static JsonObject catalog() {
    try (var stream =
            Objects.requireNonNull(
                WorldMechanismSources.class.getResourceAsStream(
                    "/gameplay/world-mechanisms-4.6.json"));
        var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Set<Integer> ints(JsonObject raw, String key) {
    var result = new HashSet<Integer>();
    if (raw.has(key)) raw.getAsJsonArray(key).forEach(v -> result.add(v.getAsInt()));
    return Set.copyOf(result);
  }

  private static Set<String> strings(JsonObject raw, String key) {
    var result = new HashSet<String>();
    raw.getAsJsonArray(key).forEach(v -> result.add(v.getAsString()));
    return Set.copyOf(result);
  }

  private static Map<Integer, Rule> rules() {
    var result = new TreeMap<Integer, Rule>();
    for (var e : CATALOG.getAsJsonObject("rules").entrySet()) {
      int id = Integer.parseInt(e.getKey());
      if (!MapChestSources.LOCATIONS.containsKey(id)) continue; // Preserve existing puzzle owners.
      var r = e.getValue().getAsJsonObject();
      var controllers = new HashSet<Integer>();
      controllers.add(r.get("controller").getAsInt());
      if (CATALOG.has("controllerAliases"))
        controllers.addAll(ints(CATALOG.getAsJsonObject("controllerAliases"), e.getKey()));
      result.put(
          id,
          new Rule(
              id,
              Kind.valueOf(r.get("kind").getAsString()),
              PropState.valueOf(r.get("initial").getAsString()),
              ints(r, "guards"),
              r.get("controller").getAsInt(),
              r.get("controllerProp").getAsInt(),
              r.get("finish").getAsInt(),
              ints(r, "interactions"),
              ints(r, "reports"),
              ints(r, "pieces"),
              Set.copyOf(controllers),
              r.has("shield") ? r.get("shield").getAsInt() : 0,
              r.has("shieldProp") ? r.get("shieldProp").getAsInt() : 0,
              r.has("controllerGroup")
                  ? r.get("controllerGroup").getAsInt()
                  : MapChestSources.LOCATIONS.get(id).group(),
              r.has("controllerPath")
                  ? r.get("controllerPath").getAsString()
                  : MapChestSources.LOCATIONS.get(id).path(),
              r.has("property") ? r.get("property").getAsString() : "",
              r.has("propertyValue") ? r.get("propertyValue").getAsInt() : 0,
              r.has("floorValue") ? r.get("floorValue").getAsString() : "",
              r.has("lateFloorValue") ? r.get("lateFloorValue").getAsString() : "",
              r.has("mission") ? r.get("mission").getAsInt() : 0,
              r.has("controllerMission") ? r.get("controllerMission").getAsInt() : 0,
              strings(r, "files"),
              strings(r, "rows"),
              TimelineActivityRules.fingerprint(r)));
    }
    return Map.copyOf(result);
  }

  private static synchronized JsonElement read(Path root, String relative) throws Exception {
    var file = root.resolve(relative).normalize();
    if (!file.startsWith(root)
        || !Files.isRegularFile(file)
        || Files.size(file) > 64 * 1024 * 1024
        || !file.toRealPath().startsWith(root.toRealPath())) return null;
    var bytes = Files.readAllBytes(file);
    var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var cached = CACHE.get(file);
    if (cached != null && cached.digest().equals(digest)) return cached.raw();
    var raw = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
    if (CACHE.size() >= 256) CACHE.remove(CACHE.keySet().iterator().next());
    CACHE.put(file, new Cached(digest, raw));
    return raw;
  }

  static GroupInfo group(Rule rule, Scene scene) {
    try {
      var loc = MapChestSources.LOCATIONS.get(rule.chest());
      if (scene == null
          || scene.getPlaneId() != loc.plane()
          || scene.getFloorId() != loc.floor()
          || !loc.dimensions().contains(scene.getDimensionId())) return null;
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      for (var path : rule.files()) {
        var raw = read(root, path);
        if (raw == null
            || !CATALOG
                .getAsJsonObject("files")
                .get(path)
                .getAsString()
                .equals(TimelineActivityRules.fingerprint(raw))) return null;
      }
      for (String key : rule.rows()) {
        String[] parts = key.split(":");
        int id = Integer.parseInt(parts[1]);
        String idKey = parts[0].equals("InteractConfig") ? "InteractID" : "ID";
        var table = read(root, "ExcelOutput/" + parts[0] + ".json");
        if (table == null || !table.isJsonArray()) return null;
        JsonObject found = null;
        for (var row : table.getAsJsonArray()) {
          if (row.getAsJsonObject().get(idKey).getAsInt() != id) continue;
          if (found != null) return null;
          found = row.getAsJsonObject();
        }
        if (found == null
            || !CATALOG
                .getAsJsonObject("rows")
                .get(key)
                .getAsString()
                .equals(TimelineActivityRules.fingerprint(found))) return null;
        var gson = ResourceLoader.getGson();
        ResourceBase expected;
        Object actual;
        switch (parts[0]) {
          case "MazeProp" -> {
            found = found.deepCopy();
            if (found.get("PropName").isJsonObject())
              found.addProperty(
                  "PropName", found.getAsJsonObject("PropName").get("Hash").getAsLong());
            expected = gson.fromJson(found, PropExcel.class);
            actual = GameData.getPropExcelMap().get(id);
          }
          case "InteractConfig" -> {
            expected = gson.fromJson(found, InteractExcel.class);
            actual = GameData.getInteractExcelMap().get(id);
          }
          case "NPCMonsterData" -> {
            expected = gson.fromJson(found, NpcMonsterExcel.class);
            actual = GameData.getNpcMonsterExcelMap().get(id);
          }
          case "ItemConfig" -> {
            expected = gson.fromJson(found, ItemExcel.class);
            actual = GameData.getItemExcelMap().get(id);
          }
          default -> {
            return null;
          }
        }
        expected.onLoad();
        if (actual == null || !gson.toJsonTree(expected).equals(gson.toJsonTree(actual)))
          return null;
      }
      var expected = MapChestSources.group(root, loc.path(), loc.group());
      var actual = scene.getResourceGroups().get(loc.group());
      return expected != null
              && actual != null
              && ResourceLoader.getGson()
                  .toJsonTree(expected)
                  .equals(ResourceLoader.getGson().toJsonTree(actual))
          ? actual
          : null;
    } catch (Exception invalid) {
      return null;
    }
  }

  static GroupInfo controlGroup(Rule rule, Scene scene) {
    try {
      if (group(rule, scene) == null) return null;
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var expected =
          ResourceLoader.getGson().fromJson(read(root, rule.controllerPath()), GroupInfo.class);
      expected.setId(rule.controllerGroup());
      var floor = new emu.lunarcore.data.config.FloorInfo();
      floor.getGroups().put(expected.getId(), expected);
      floor.onLoad();
      var actual = scene.getResourceGroups().get(rule.controllerGroup());
      return actual != null
              && ResourceLoader.getGson()
                  .toJsonTree(expected)
                  .equals(ResourceLoader.getGson().toJsonTree(actual))
          ? actual
          : null;
    } catch (Exception invalid) {
      return null;
    }
  }

  static boolean floorValuesMatch(Rule rule, Scene scene) {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      String path =
          "Config/LevelOutput/RuntimeFloor/P"
              + scene.getPlaneId()
              + "_F"
              + scene.getFloorId()
              + ".json";
      if (!rule.files().contains(path) || group(rule, scene) == null) return false;
      var gson = ResourceLoader.getGson();
      var expected = gson.fromJson(read(root, path), emu.lunarcore.data.config.FloorInfo.class);
      return gson.toJsonTree(expected.getExtraDatas(scene.getDimensionId()))
          .equals(gson.toJsonTree(scene.getFloorInfo().getExtraDatas(scene.getDimensionId())));
    } catch (Exception invalid) {
      return false;
    }
  }

  static MissionInfo.SubMissionInfo mission(Rule rule, int id) {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      for (String path : rule.files()) {
        if (!path.contains("/MissionInfo_")) continue;
        var raw = read(root, path);
        if (raw == null
            || !CATALOG
                .getAsJsonObject("files")
                .get(path)
                .getAsString()
                .equals(TimelineActivityRules.fingerprint(raw))) return null;
        for (var row : raw.getAsJsonObject().getAsJsonArray("SubMissionList")) {
          if (row.getAsJsonObject().get("ID").getAsInt() != id) continue;
          var gson = ResourceLoader.getGson();
          var expected = gson.fromJson(row, MissionInfo.SubMissionInfo.class);
          var actual = GameplayDefinitions.subs.get(id);
          if (actual == null) return null;
          var metadata = gson.toJsonTree(actual).getAsJsonObject();
          // loadMissions adds the parsed script cache; it is not part of the source row.
          metadata.remove("task");
          return metadata.equals(gson.toJsonTree(expected)) ? actual : null;
        }
      }
    } catch (Exception invalid) {
      return null;
    }
    return null;
  }

  private WorldMechanismSources() {}
}
