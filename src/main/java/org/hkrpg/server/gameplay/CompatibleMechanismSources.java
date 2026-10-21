package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.ResourceBase;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.Scene;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicitly authored compatibility bindings, separate from recovered original server scripts. */
final class CompatibleMechanismSources {
  record Actor(
      int group,
      int instance,
      int prop,
      PropState initial,
      String role,
      Set<Integer> interactions,
      Set<String> requires) {}

  record Report(
      int group,
      String name,
      int value,
      String key,
      Set<String> requires,
      int mission,
      Map<String, Integer> effects,
      Map<String, Integer> groupEffects) {}

  record Mission(String key, Set<String> requires) {}

  record Flow(
      int floor,
      int plane,
      Set<Integer> groups,
      Map<String, Actor> actors,
      List<Report> reports,
      Map<Integer, Set<String>> rewards,
      Map<Integer, Mission> missions,
      Set<String> files,
      Map<String, String> rows,
      String policy,
      String board,
      int basePieces,
      int mission,
      String pieceValue,
      String boardValue,
      List<Set<String>> waves,
      int presentationGroup) {
    String source() {
      var raw = new JsonObject();
      raw.add("flow", ResourceLoader.getGson().toJsonTree(this));
      var hashes = new JsonObject();
      for (String file : files) hashes.add(file, CATALOG.getAsJsonObject("files").get(file));
      raw.add("files", hashes);
      return TimelineActivityRules.fingerprint(raw);
    }

    Set<String> events() {
      var keys = new HashSet<String>();
      reports.forEach(r -> keys.add(r.key()));
      missions.values().forEach(m -> keys.add(m.key()));
      actors.forEach(
          (k, a) -> {
            keys.add("done:" + k);
            if (a.role().equals("board")) keys.add("open:" + k);
          });
      if (waves != null) {
        keys.add("started");
        waves.forEach(keys::addAll);
      }
      if (presentationGroup > 0) for (int i = 1; i <= 3; i++) keys.add("claimed:" + i);
      return keys;
    }
  }

  private static final JsonObject CATALOG;
  static final Map<String, Flow> FLOWS;

  static {
    try (var in =
            Objects.requireNonNull(
                CompatibleMechanismSources.class.getResourceAsStream(
                    "/gameplay/compatible-mechanisms-4.6.json"));
        var reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
      CATALOG = JsonParser.parseReader(reader).getAsJsonObject();
      FLOWS =
          Map.copyOf(
              ResourceLoader.getGson()
                  .fromJson(CATALOG.get("flows"), new TypeToken<Map<String, Flow>>() {}.getType()));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private record Cached(String bytes, JsonElement raw) {}

  private static final Map<Path, Cached> CACHE = new LinkedHashMap<>(32, .75f, true);
  private static final Map<Scene, Map<Flow, Boolean>> RESTORE = new WeakHashMap<>();

  // Scene construction checks a source once. Every mutating request still calls valid() afresh.
  static synchronized boolean restoration(Flow f, Scene s) {
    return RESTORE.computeIfAbsent(s, k -> new HashMap<>()).computeIfAbsent(f, k -> valid(f, s));
  }

  private static synchronized JsonElement read(String path) throws Exception {
    var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
    var f = root.resolve(path).normalize();
    if (!f.startsWith(root)
        || !Files.isRegularFile(f)
        || Files.size(f) > 64 * 1024 * 1024
        || !f.toRealPath().startsWith(root.toRealPath())) return null;
    byte[] bytes = Files.readAllBytes(f);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var old = CACHE.get(f);
    if (old != null && old.bytes.equals(hash)) return old.raw;
    var raw = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
    if (CACHE.size() >= 256) CACHE.remove(CACHE.keySet().iterator().next());
    CACHE.put(f, new Cached(hash, raw));
    return raw;
  }

  static String groupPath(Flow f, int g) {
    return "Config/LevelOutput/SharedRuntimeGroup/Groups_P"
        + f.plane
        + "_F"
        + f.floor
        + "/LevelGroup_P"
        + f.plane
        + "_F"
        + f.floor
        + "_G"
        + g
        + ".json";
  }

  static boolean matches(Flow f, Scene s) {
    return s != null && s.getPlaneId() == f.plane && s.getFloorId() == f.floor;
  }

  static boolean valid(Flow f, Scene s) {
    if (!matches(f, s)) return false;
    try {
      for (String path : f.files) {
        var raw = read(path);
        if (raw == null
            || !CATALOG
                .getAsJsonObject("files")
                .get(path)
                .getAsString()
                .equals(TimelineActivityRules.fingerprint(raw))) return false;
      }
      var floor =
          ResourceLoader.getGson()
              .fromJson(
                  read("Config/LevelOutput/RuntimeFloor/P" + f.plane + "_F" + f.floor + ".json"),
                  FloorInfo.class);
      if (!ResourceLoader.getGson()
          .toJsonTree(floor.getExtraDatas(s.getDimensionId()))
          .equals(
              ResourceLoader.getGson()
                  .toJsonTree(s.getFloorInfo().getExtraDatas(s.getDimensionId())))) return false;
      var tables = new HashMap<String, JsonElement>();
      for (var row : f.rows.entrySet()) {
        String[] k = row.getKey().split(":");
        int id = Integer.parseInt(k[1]);
        String pk = k[0].equals("InteractConfig") ? "InteractID" : "ID";
        JsonObject found = null;
        if (!tables.containsKey(k[0])) tables.put(k[0], read("ExcelOutput/" + k[0] + ".json"));
        for (var v : tables.get(k[0]).getAsJsonArray())
          if (v.getAsJsonObject().get(pk).getAsInt() == id) {
            if (found != null) return false;
            found = v.getAsJsonObject();
          }
        if (found == null || !row.getValue().equals(TimelineActivityRules.fingerprint(found)))
          return false;
        ResourceBase expected;
        Object actual;
        var gson = ResourceLoader.getGson();
        switch (k[0]) {
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
          default -> {
            return false;
          }
        }
        expected.onLoad();
        if (actual == null || !gson.toJsonTree(expected).equals(gson.toJsonTree(actual)))
          return false;
      }
      return true;
    } catch (Exception invalid) {
      return false;
    }
  }

  static GroupInfo group(Flow f, Scene scene, int id) {
    if (!matches(f, scene) || !f.groups.contains(id)) return null;
    try {
      var expected = ResourceLoader.getGson().fromJson(read(groupPath(f, id)), GroupInfo.class);
      expected.setId(id);
      var floor = new FloorInfo();
      floor.getGroups().put(id, expected);
      floor.onLoad();
      var actual = scene.getResourceGroups().get(id);
      return actual != null
              && ResourceLoader.getGson()
                  .toJsonTree(actual)
                  .equals(ResourceLoader.getGson().toJsonTree(expected))
          ? actual
          : null;
    } catch (Exception invalid) {
      return null;
    }
  }

  static MissionInfo.SubMissionInfo mission(Flow f, int id) {
    try {
      for (String path : f.files)
        if (path.endsWith(".partial.json"))
          for (var row : read(path).getAsJsonObject().getAsJsonArray("SubMissionList")) {
            if (row.getAsJsonObject().get("ID").getAsInt() != id) continue;
            var gson = ResourceLoader.getGson();
            var expected = gson.fromJson(row, MissionInfo.SubMissionInfo.class);
            var actual = GameplayDefinitions.subs.get(id);
            if (actual == null) return null;
            var metadata = gson.toJsonTree(actual).getAsJsonObject();
            metadata.remove("task");
            return metadata.equals(gson.toJsonTree(expected)) ? actual : null;
          }
    } catch (Exception invalid) {
      return null;
    }
    return null;
  }

  private CompatibleMechanismSources() {}
}
