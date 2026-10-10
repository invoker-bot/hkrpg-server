package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.Scene;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Original module, template and scene identities; unrelated operator rows stay independent. */
final class ActivityKillRules {
  private static ActivityKillRules current;
  final Path root;
  final Map<String, String> files;
  final Map<String, Map<Integer, JsonObject>> tables;
  final Map<String, Object> expected = new HashMap<>();

  private ActivityKillRules(
      Path root, Map<String, String> files, Map<String, Map<Integer, JsonObject>> tables) {
    this.root = root;
    this.files = Map.copyOf(files);
    this.tables = Map.copyOf(tables);
    for (String key : ActivityKillSources.ROWS.keySet()) {
      var parts = key.split(":");
      var row = tables.get(parts[0]).get(Integer.parseInt(parts[1]));
      Object value =
          switch (parts[0]) {
            case "QuestData" -> ResourceLoader.getGson().fromJson(row, QuestExcel.class);
            case "FinishWay" -> ResourceLoader.getGson().fromJson(row, FinishWayExcel.class);
            case "RewardData" -> {
              var r = ResourceLoader.getGson().fromJson(row, RewardExcel.class);
              r.onLoad();
              yield r;
            }
            case "ItemConfig" -> ResourceLoader.getGson().fromJson(row, ItemExcel.class);
            case "ActivityPanel" ->
                ResourceLoader.getGson().fromJson(row, ActivityPanelExcel.class);
            case "MainMission" -> ResourceLoader.getGson().fromJson(row, MainMissionExcel.class);
            case "NPCMonsterData" -> ResourceLoader.getGson().fromJson(row, NpcMonsterExcel.class);
            default -> null;
          };
      if (value != null) expected.put(key, value);
    }
  }

  static synchronized ActivityKillRules load() {
    if (ActivityKillSources.IDS.stream()
        .anyMatch(id -> GameData.getQuestExcelMap().get(id) == null)) return null;
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var keys = new TreeMap<>(ActivityKillSources.KEYS);
      keys.put("MonsterConfig", "MonsterID");
      keys.put("StageConfig", "StageID");
      keys.put("MazePlane", "PlaneID");
      keys.put("MapEntrance", "ID");
      var files = new TreeMap<String, String>();
      var texts = new TreeMap<String, String>();
      for (var e : keys.entrySet()) {
        var path = root.resolve("ExcelOutput/" + e.getKey() + ".json");
        if (!Files.isRegularFile(path)
            || Files.size(path) > 64 * 1024 * 1024
            || !path.toRealPath().startsWith(root.toRealPath())) return null;
        String text = Files.readString(path).replace("\r\n", "\n");
        files.put(e.getKey(), digest(text));
        texts.put(e.getKey(), text);
      }
      if (current != null && current.root.equals(root) && current.files.equals(files))
        return current.loaded() ? current : null;
      var tables = new HashMap<String, Map<Integer, JsonObject>>();
      for (var e : texts.entrySet()) {
        var json = JsonParser.parseString(e.getValue());
        if (!json.isJsonArray() || json.getAsJsonArray().size() > 131072) return null;
        var rows = new HashMap<Integer, JsonObject>();
        for (var value : json.getAsJsonArray()) {
          if (!value.isJsonObject()) return null;
          var row = value.getAsJsonObject();
          if (!row.has(keys.get(e.getKey()))) return null;
          int id = row.get(keys.get(e.getKey())).getAsInt();
          if (id <= 0 || rows.put(id, row) != null) return null;
        }
        tables.put(e.getKey(), Map.copyOf(rows));
      }
      for (var e : ActivityKillSources.ROWS.entrySet()) {
        var p = e.getKey().split(":");
        var row = tables.get(p[0]).get(Integer.parseInt(p[1]));
        if (row == null || !e.getValue().equals(TimelineActivityRules.fingerprint(row)))
          return null;
      }
      var next = new ActivityKillRules(root, files, tables);
      if (!next.loaded()) return null;
      current = next;
      return next;
    } catch (Exception invalid) {
      return null;
    }
  }

  private static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }

  private boolean loaded() {
    var gson = ResourceLoader.getGson();
    for (var e : expected.entrySet()) {
      var p = e.getKey().split(":");
      int id = Integer.parseInt(p[1]);
      Object actual =
          switch (p[0]) {
            case "QuestData" -> GameData.getQuestExcelMap().get(id);
            case "FinishWay" -> GameData.getFinishWayExcelMap().get(id);
            case "RewardData" -> GameData.getRewardExcelMap().get(id);
            case "ItemConfig" -> GameData.getItemExcelMap().get(id);
            case "ActivityPanel" -> GameData.getActivityPanelExcelMap().get(id);
            case "MainMission" -> GameData.getMainMissionExcelMap().get(id);
            case "NPCMonsterData" -> GameData.getNpcMonsterExcelMap().get(id);
            default -> null;
          };
      if (actual == null || !gson.toJsonTree(actual).equals(gson.toJsonTree(e.getValue())))
        return false;
      if (e.getValue() instanceof RewardExcel r
          && (!(actual instanceof RewardExcel a) || !r.getRewards().equals(a.getRewards())))
        return false;
    }
    return true;
  }

  int goal(int id) {
    return tables.get("FinishWay").get(id).get("Progress").getAsInt();
  }

  int target(int id) {
    return tables.get("FinishWay").get(id).get("ParamInt1").getAsInt();
  }

  boolean unlocked(Player p, int id) {
    if (!ActivityKillSources.IDS.contains(id) || !ActivityAccess.unlocked(p, 30013)) return false;
    int unlock = tables.get("QuestTimeLimitConfig").get(id).get("UnlockData").getAsInt();
    var list = tables.get("FuncUnlockData").get(unlock).getAsJsonArray("Conditions");
    if (list.size() != 1) return false;
    var row = list.get(0).getAsJsonObject();
    return "FinishMainMission".equals(row.get("Type").getAsString())
        && MissionStateRecords.mainCompleted(p, Integer.parseInt(row.get("Param").getAsString()));
  }

  GameplayDefinitions.Window window(Player p, int id, Instant now) {
    if (now == null || !unlocked(p, id)) return null;
    var matches =
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
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  private JsonObject pinned(String table, int id, Map<Integer, String> pins) {
    String pin = pins.get(id);
    var row = tables.get(table).get(id);
    return pin != null && row != null && pin.equals(TimelineActivityRules.fingerprint(row))
        ? row
        : null;
  }

  int template(int id) {
    var row = pinned("MonsterConfig", id, ActivityKillSources.MONSTERS);
    var actual = GameData.getMonsterExcelMap().get(id);
    if (row == null || actual == null) return -1;
    var expected = ResourceLoader.getGson().fromJson(row, MonsterExcel.class);
    return ResourceLoader.getGson()
            .toJsonTree(actual)
            .equals(ResourceLoader.getGson().toJsonTree(expected))
        ? ActivityKillSources.TEMPLATES.getOrDefault(id, 0)
        : -1;
  }

  StageExcel stage(int id) {
    var row = pinned("StageConfig", id, ActivityKillSources.STAGES);
    var actual = GameData.getStageExcelMap().get(id);
    if (row == null || actual == null) return null;
    var expected = ResourceLoader.getGson().fromJson(row, StageExcel.class);
    expected.onLoad();
    return ResourceLoader.getGson()
                .toJsonTree(actual)
                .equals(ResourceLoader.getGson().toJsonTree(expected))
            && actual.getMonsterWaves().equals(expected.getMonsterWaves())
        ? actual
        : null;
  }

  int mode(int plane, int floor, int entry) {
    var raw = pinned("MazePlane", plane, ActivityKillSources.PLANES);
    var point = pinned("MapEntrance", entry, ActivityKillSources.ENTRIES);
    if (raw == null
        || point == null
        || point.get("PlaneID").getAsInt() != plane
        || point.get("FloorID").getAsInt() != floor) return 0;
    var actual = GameData.getMazePlaneExcelMap().get(plane);
    var actualEntry = GameData.getMapEntranceExcelMap().get(entry);
    var gson = ResourceLoader.getGson();
    if (actual == null
        || actualEntry == null
        || !gson.toJsonTree(actual)
            .equals(gson.toJsonTree(gson.fromJson(raw, MazePlaneExcel.class)))
        || !gson.toJsonTree(actualEntry)
            .equals(gson.toJsonTree(gson.fromJson(point, MapEntranceExcel.class)))) return 0;
    return actual.getPlaneType() == PlaneType.Maze
        ? 2
        : actual.getPlaneType() == PlaneType.Raid ? 8 : 0;
  }

  int mode(Player p, Scene scene) {
    if (scene == null
        || scene.getPlayer() != p
        || scene.getPlaneId() != p.getPlaneId()
        || scene.getFloorId() != p.getFloorId()
        || scene.getEntryId() != p.getEntryId()
        || scene.getStoryLineId() != p.getGameplay().getStoryLines().getCurrentId()) return 0;
    return mode(scene.getPlaneId(), scene.getFloorId(), scene.getEntryId());
  }
}
