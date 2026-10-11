package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.player.Player;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Original offering entry definitions; query support does not grant unfinished gameplay. */
public final class OfferingQueryCatalog {
  private record Cached(String bytes, JsonElement json, String canonical) {}

  private static final Map<Path, Cached> cache = new LinkedHashMap<>(16, .75f, true);
  private final Map<Integer, JsonObject> types = new HashMap<>();
  private final Map<Integer, JsonObject> unlocks = new HashMap<>();
  private final Map<Integer, MainMissionExcel> mains = new HashMap<>();
  private final Map<Integer, MissionInfo.SubMissionInfo> subs = new HashMap<>();

  private OfferingQueryCatalog(Map<String, JsonObject> rows, Map<String, JsonElement> files) {
    var gson = ResourceLoader.getGson();
    for (var entry : rows.entrySet()) {
      var row = entry.getValue();
      if (entry.getKey().startsWith("OfferingTypeConfig:"))
        types.put(row.get("ID").getAsInt(), row);
      if (entry.getKey().startsWith("FuncUnlockData:"))
        unlocks.put(row.get("UnlockID").getAsInt(), row);
      if (entry.getKey().startsWith("MainMission:")) {
        var main = gson.fromJson(row, MainMissionExcel.class);
        mains.put(main.getId(), main);
      }
    }
    for (var entry : files.entrySet()) {
      if (!entry.getKey().startsWith("Config/Level/Mission/")) continue;
      for (var raw : entry.getValue().getAsJsonObject().getAsJsonArray("SubMissionList")) {
        var sub = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
        if (rows.containsKey("SubMission:" + sub.getId())) subs.put(sub.getId(), sub);
      }
    }
    if (types.size() != 15 || unlocks.size() != 6 || mains.size() != 6 || subs.size() != 3)
      throw new IllegalArgumentException("Missing original offering query prerequisite");
  }

  public static synchronized OfferingQueryCatalog read() {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var real = root.toRealPath();
      var paths = new TreeSet<>(OfferingQuerySources.FILES.keySet());
      OfferingQuerySources.KEYS.keySet().forEach(t -> paths.add("ExcelOutput/" + t + ".json"));
      var files = new TreeMap<String, JsonElement>();
      for (String path : paths) {
        var file = root.resolve(path);
        if (!Files.isRegularFile(file)
            || Files.size(file) > 64L * 1024 * 1024
            || !file.toRealPath().startsWith(real)) return null;
        var bytes = Files.readAllBytes(file);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var saved = cache.get(file);
        if (saved == null || !saved.bytes().equals(hash)) {
          var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
          saved = new Cached(hash, json, TimelineActivityRules.fingerprint(json));
          cache.put(file, saved);
          while (cache.size() > 32) cache.remove(cache.keySet().iterator().next());
        }
        String pinned = OfferingQuerySources.FILES.get(path);
        if (pinned != null && !pinned.equals(saved.canonical())) return null;
        files.put(path, saved.json());
      }
      var selected = new HashMap<String, JsonObject>();
      for (var table : OfferingQuerySources.KEYS.entrySet()) {
        var json = files.get("ExcelOutput/" + table.getKey() + ".json");
        if (!json.isJsonArray() || json.getAsJsonArray().size() > 131072) return null;
        var all = new HashMap<String, JsonObject>();
        for (var value : json.getAsJsonArray()) {
          if (!value.isJsonObject()) return null;
          var row = value.getAsJsonObject();
          String key = table.getKey() + ":" + row.get(table.getValue()).getAsString();
          if (!row.get(table.getValue()).getAsString().matches("[1-9][0-9]*")
              || all.put(key, row) != null) return null;
        }
        for (var pin : OfferingQuerySources.ROWS.entrySet()) {
          if (!pin.getKey().startsWith(table.getKey() + ":")) continue;
          var row = all.get(pin.getKey());
          if (row == null || !pin.getValue().equals(TimelineActivityRules.fingerprint(row)))
            return null;
          selected.put(pin.getKey(), row);
        }
      }
      return new OfferingQueryCatalog(selected, files);
    } catch (Exception invalid) {
      return null;
    }
  }

  public boolean unlocked(Player player, int id, Instant now) {
    synchronized (player) {
      var type = types.get(id);
      if (type == null || now == null) return false;
      if (type.has("ActivityModuleID")) {
        int module = type.get("ActivityModuleID").getAsInt();
        var active =
            GameplayDefinitions.windows.stream()
                .filter(
                    w ->
                        w.moduleId() == module
                            && w.beginTime() > 0
                            && w.endTime() > w.beginTime()
                            && w.beginTime() <= now.getEpochSecond()
                            && now.getEpochSecond() < w.endTime())
                .toList();
        if (active.size() != 1 || !ActivityAccess.unlocked(player, active.getFirst().panelId()))
          return false;
      }
      if (!type.has("UnlockID")) return true;
      var conditions = unlocks.get(type.get("UnlockID").getAsInt()).getAsJsonArray("Conditions");
      for (var value : conditions) {
        var condition = value.getAsJsonObject();
        int target = Integer.parseInt(condition.get("Param").getAsString());
        String kind = condition.get("Type").getAsString();
        if (kind.equals("FinishMainMission")) {
          if (!mainMatches(target) || !MissionStateRecords.mainCompleted(player, target))
            return false;
        } else if (kind.equals("FinishSubMission")) {
          var expected = subs.get(target);
          var actual = GameplayDefinitions.subs.get(target);
          if (expected == null || actual == null || !mainMatches(expected.getMainMissionID()))
            return false;
          var first = ResourceLoader.getGson().toJsonTree(expected).getAsJsonObject();
          var second = ResourceLoader.getGson().toJsonTree(actual).getAsJsonObject();
          first.remove("task");
          second.remove("task");
          if (!first.equals(second) || !MissionStateRecords.subCompleted(player, target))
            return false;
        } else return false;
      }
      return true;
    }
  }

  private boolean mainMatches(int id) {
    var expected = mains.get(id);
    var actual = GameData.getMainMissionExcelMap().get(id);
    return expected != null
        && actual != null
        && ResourceLoader.getGson()
            .toJsonTree(expected)
            .equals(ResourceLoader.getGson().toJsonTree(actual));
  }
}
