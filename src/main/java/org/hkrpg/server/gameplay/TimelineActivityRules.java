package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Original nonperiodic activity ownership, prerequisite gates and complete prop definitions. */
final class TimelineActivityRules {
  static final Set<Integer> IDS = Set.of(6029846, 6029847, 6029848, 6029849);
  private static TimelineActivityRules current;
  final Path root;
  final Map<String, String> files;
  final Map<String, JsonObject> rows;
  final Map<Integer, MissionInfo.SubMissionInfo> prerequisites;
  final Map<String, Object> expected = new HashMap<>();

  private TimelineActivityRules(
      Path root,
      Map<String, String> files,
      Map<String, JsonObject> rows,
      Map<Integer, MissionInfo.SubMissionInfo> prerequisites) {
    this.root = root;
    this.files = Map.copyOf(files);
    this.rows = Map.copyOf(rows);
    this.prerequisites = Map.copyOf(prerequisites);
    var gson = ResourceLoader.getGson();
    for (var e : rows.entrySet()) {
      String table = e.getKey().split(":")[0];
      var raw = e.getValue().deepCopy();
      Object value =
          switch (table) {
            case "QuestData" -> gson.fromJson(raw, QuestExcel.class);
            case "FinishWay" -> gson.fromJson(raw, FinishWayExcel.class);
            case "RewardData" -> {
              var r = gson.fromJson(raw, RewardExcel.class);
              r.onLoad();
              yield r;
            }
            case "ItemConfig" -> gson.fromJson(raw, ItemExcel.class);
            case "ActivityPanel" -> gson.fromJson(raw, ActivityPanelExcel.class);
            case "MainMission" -> gson.fromJson(raw, MainMissionExcel.class);
            case "MazePlane" -> gson.fromJson(raw, MazePlaneExcel.class);
            case "MapEntrance" -> gson.fromJson(raw, MapEntranceExcel.class);
            case "MazeProp" -> {
              if (raw.has("PropName") && raw.get("PropName").isJsonObject())
                raw.addProperty(
                    "PropName", raw.getAsJsonObject("PropName").get("Hash").getAsLong());
              var p = gson.fromJson(raw, PropExcel.class);
              p.onLoad();
              yield p;
            }
            default -> null;
          };
      if (value != null) expected.put(e.getKey(), value);
    }
  }

  static synchronized TimelineActivityRules load() {
    if (IDS.stream().anyMatch(id -> GameData.getQuestExcelMap().get(id) == null)) return null;
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var files = new TreeMap<String, String>();
      var texts = new TreeMap<String, String>();
      for (String table : TimelineActivitySources.KEYS.keySet()) {
        String path = "ExcelOutput/" + table + ".json";
        String text = read(root, path, 8 * 1024 * 1024);
        texts.put(path, text);
        files.put(path, hash(text));
      }
      for (String path : TimelineActivitySources.PREREQUISITE_FILES.keySet()) {
        String text = read(root, path, 2 * 1024 * 1024);
        texts.put(path, text);
        files.put(path, hash(text));
      }
      if (current != null && current.root.equals(root) && current.files.equals(files))
        return current.loaded() ? current : null;
      var rows = new HashMap<String, JsonObject>();
      var prerequisites = new HashMap<Integer, MissionInfo.SubMissionInfo>();
      for (var e : texts.entrySet()) {
        var json = JsonParser.parseString(e.getValue());
        if (e.getKey().startsWith("ExcelOutput/")) {
          String table = e.getKey().substring(12, e.getKey().length() - 5);
          if (!json.isJsonArray() || json.getAsJsonArray().size() > 32768) return null;
          for (var value : json.getAsJsonArray()) {
            if (!value.isJsonObject()) return null;
            var row = value.getAsJsonObject();
            String field = TimelineActivitySources.KEYS.get(table);
            if (!row.has(field)) return null;
            String key = table + ":" + row.get(field).getAsString();
            String pin = TimelineActivitySources.ROWS.get(key);
            if (pin == null) continue;
            if (rows.put(key, row) != null || !pin.equals(fingerprint(row))) return null;
          }
        } else {
          if (!TimelineActivitySources.PREREQUISITE_FILES.get(e.getKey()).equals(fingerprint(json)))
            return null;
          for (var raw : json.getAsJsonObject().getAsJsonArray("SubMissionList")) {
            var row = raw.getAsJsonObject();
            int id = row.get("ID").getAsInt();
            if (Set.of(104010102, 104010602, 104011107, 104010966).contains(id))
              if (prerequisites.put(
                      id, ResourceLoader.getGson().fromJson(row, MissionInfo.SubMissionInfo.class))
                  != null) return null;
          }
        }
      }
      if (rows.size() != TimelineActivitySources.ROWS.size() || prerequisites.size() != 4)
        return null;
      var next = new TimelineActivityRules(root, files, rows, prerequisites);
      if (!next.loaded()) return null;
      current = next;
      return next;
    } catch (Exception invalid) {
      return null;
    }
  }

  private boolean loaded() {
    var gson = ResourceLoader.getGson();
    for (var e : expected.entrySet()) {
      var parts = e.getKey().split(":");
      int id = Integer.parseInt(parts[1]);
      Object actual =
          switch (parts[0]) {
            case "QuestData" -> GameData.getQuestExcelMap().get(id);
            case "FinishWay" -> GameData.getFinishWayExcelMap().get(id);
            case "RewardData" -> GameData.getRewardExcelMap().get(id);
            case "ItemConfig" -> GameData.getItemExcelMap().get(id);
            case "ActivityPanel" -> GameData.getActivityPanelExcelMap().get(id);
            case "MainMission" -> GameData.getMainMissionExcelMap().get(id);
            case "MazePlane" -> GameData.getMazePlaneExcelMap().get(id);
            case "MazeProp" -> GameData.getPropExcelMap().get(id);
            case "MapEntrance" -> GameData.getMapEntranceExcelMap().get(id);
            default -> null;
          };
      if (actual == null || !gson.toJsonTree(e.getValue()).equals(gson.toJsonTree(actual)))
        return false;
      if (e.getValue() instanceof RewardExcel reward
          && (!(actual instanceof RewardExcel live)
              || !reward.getRewards().equals(live.getRewards()))) return false;
    }
    for (var e : prerequisites.entrySet()) {
      var actual = GameplayDefinitions.subs.get(e.getKey());
      if (actual == null) return false;
      var first = gson.toJsonTree(e.getValue()).getAsJsonObject();
      var second = gson.toJsonTree(actual).getAsJsonObject();
      first.remove("task");
      second.remove("task");
      if (!first.equals(second)) return false;
    }
    return true;
  }

  int plane(int id) {
    return rows.get("FinishWay:" + id).get("MazePlaneID").getAsInt();
  }

  int floor(int id) {
    return rows.get("FinishWay:" + id).get("MazeFloorID").getAsInt();
  }

  boolean unlocked(Player p, int id) {
    if (!IDS.contains(id) || !ActivityAccess.unlocked(p, 30013)) return false;
    int unlock = rows.get("QuestTimeLimitConfig:" + id).get("UnlockData").getAsInt();
    var conditions = rows.get("FuncUnlockData:" + unlock).getAsJsonArray("Conditions");
    if (conditions.size() != 1) return false;
    var condition = conditions.get(0).getAsJsonObject();
    return "FinishSubMission".equals(condition.get("Type").getAsString())
        && MissionStateRecords.subCompleted(
            p, Integer.parseInt(condition.get("Param").getAsString()));
  }

  GameplayDefinitions.Window window(Player p, int id, Instant now) {
    if (now == null || !unlocked(p, id)) return null;
    var windows =
        GameplayDefinitions.windows.stream()
            .filter(
                w ->
                    w.panelId() == 30013
                        && w.moduleId() == 3001301
                        && w.beginTime() > 0
                        && w.endTime() > w.beginTime()
                        && w.beginTime() <= now.getEpochSecond()
                        && now.getEpochSecond() < w.endTime())
            .toList();
    return windows.size() == 1 ? windows.getFirst() : null;
  }

  PropInfo prop(Scene scene, int group, int instance) {
    return prop(scene.getPlaneId(), scene.getFloorId(), scene.getDimensionId(), group, instance);
  }

  PropInfo prop(int plane, int floorId, int dimension, int group, int instance) {
    var definition = definition(plane, floorId, dimension, group);
    if (definition == null) return null;
    try {
      var json = JsonParser.parseString(read(root, definition.path(), 2 * 1024 * 1024));
      if (!definition.source().equals(fingerprint(json))) return null;
      var original = ResourceLoader.getGson().fromJson(json, GroupInfo.class);
      original.setId(group);
      var floor = new FloorInfo(floorId);
      floor.getGroups().put(group, original);
      floor.onLoad();
      var found = original.getPropList().stream().filter(p -> p.getID() == instance).toList();
      return found.size() == 1 && PropTimelines.tagged(found.getFirst()) ? found.getFirst() : null;
    } catch (Exception invalid) {
      return null;
    }
  }

  private TimelineActivitySources.Group definition(int plane, int floor, int dimension, int group) {
    var variants = TimelineActivitySources.GROUPS.get(plane + ":" + floor + ":" + group);
    if (variants == null) return null;
    var selected = variants.stream().filter(v -> v.dimensions().contains(dimension)).toList();
    return selected.size() == 1 ? selected.getFirst() : null;
  }

  String groupSource(int plane, int floor, int dimension, int group) {
    var value = definition(plane, floor, dimension, group);
    return value == null ? null : value.source();
  }

  private static String read(Path root, String relative, int limit) throws Exception {
    var file = root.resolve(relative);
    if (!Files.isRegularFile(file)
        || Files.size(file) > limit
        || !file.toRealPath().startsWith(root.toRealPath()))
      throw new IllegalArgumentException("Unavailable original timeline source");
    return Files.readString(file).replace("\r\n", "\n");
  }

  static String fingerprint(JsonElement value) {
    return hash(canonical(value).toString());
  }

  private static JsonElement canonical(JsonElement value) {
    if (value.isJsonObject()) {
      var result = new JsonObject();
      new TreeSet<>(value.getAsJsonObject().keySet())
          .forEach(k -> result.add(k, canonical(value.getAsJsonObject().get(k))));
      return result;
    }
    if (value.isJsonArray()) {
      var result = new JsonArray();
      value.getAsJsonArray().forEach(v -> result.add(canonical(v)));
      return result;
    }
    return value;
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }
}
