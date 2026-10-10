package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Original standalone presentation props; mission first-talk effects keep their existing owner. */
final class WorldFirstTalkRules {
  static final int QUEST = 6029859;
  private static WorldFirstTalkRules current;
  final Path root;
  final Map<String, String> hashes;
  final Map<String, Map<Integer, JsonObject>> tables;
  final Map<String, JsonElement> files;
  private final Map<String, Object> expected = new HashMap<>();
  private final Map<Integer, GroupInfo> groups = new HashMap<>();
  private final MissionInfo.SubMissionInfo prerequisite;

  private WorldFirstTalkRules(
      Path root,
      Map<String, String> hashes,
      Map<String, Map<Integer, JsonObject>> tables,
      Map<String, JsonElement> files) {
    this.root = root;
    this.hashes = Map.copyOf(hashes);
    this.tables = Map.copyOf(tables);
    this.files = Map.copyOf(files);
    var gson = ResourceLoader.getGson();
    for (String key : WorldFirstTalkSources.ROWS.keySet()) {
      var p = key.split(":");
      var row = tables.get(p[0]).get(Integer.parseInt(p[1])).deepCopy();
      Object value =
          switch (p[0]) {
            case "QuestData" -> gson.fromJson(row, QuestExcel.class);
            case "FinishWay" -> gson.fromJson(row, FinishWayExcel.class);
            case "RewardData" -> {
              var v = gson.fromJson(row, RewardExcel.class);
              v.onLoad();
              yield v;
            }
            case "ItemConfig" -> gson.fromJson(row, ItemExcel.class);
            case "ActivityPanel" -> gson.fromJson(row, ActivityPanelExcel.class);
            case "MainMission" -> gson.fromJson(row, MainMissionExcel.class);
            case "MazePlane" -> gson.fromJson(row, MazePlaneExcel.class);
            case "MapEntrance" -> gson.fromJson(row, MapEntranceExcel.class);
            case "MazeProp" -> {
              if (row.get("PropName").isJsonObject())
                row.addProperty(
                    "PropName", row.getAsJsonObject("PropName").get("Hash").getAsLong());
              var v = gson.fromJson(row, PropExcel.class);
              v.onLoad();
              yield v;
            }
            default -> null;
          };
      if (value != null) expected.put(key, value);
    }
    for (var l : WorldFirstTalkSources.LOCATIONS.values()) {
      var g = gson.fromJson(files.get(l.path()), GroupInfo.class);
      g.setId(l.group());
      groups.put(l.group(), g);
    }
    var rows =
        files
            .get("Config/Level/Mission/1040101/MissionInfo_1040101.partial.json")
            .getAsJsonObject()
            .getAsJsonArray("SubMissionList");
    prerequisite =
        rows.asList().stream()
            .map(v -> gson.fromJson(v, MissionInfo.SubMissionInfo.class))
            .filter(v -> v.getId() == 104010102)
            .findFirst()
            .orElseThrow();
  }

  static synchronized WorldFirstTalkRules load() {
    if (GameData.getQuestExcelMap().get(QUEST) == null
        || GameData.getPropExcelMap().get(104050) == null) return null;
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var hashes = new TreeMap<String, String>();
      var json = new TreeMap<String, JsonElement>();
      var paths = new TreeSet<>(WorldFirstTalkSources.FILES.keySet());
      for (String table : WorldFirstTalkSources.KEYS.keySet())
        paths.add("ExcelOutput/" + table + ".json");
      for (String path : paths) {
        var file = root.resolve(path);
        if (!Files.isRegularFile(file)
            || Files.size(file) > 64 * 1024 * 1024
            || !file.toRealPath().startsWith(root.toRealPath())) return null;
        var value = JsonParser.parseString(Files.readString(file));
        hashes.put(path, TimelineActivityRules.fingerprint(value));
        json.put(path, value);
      }
      if (current != null && current.root.equals(root) && current.hashes.equals(hashes))
        return current.loaded() ? current : null;
      for (var e : WorldFirstTalkSources.FILES.entrySet())
        if (!e.getValue().equals(hashes.get(e.getKey()))) return null;
      var tables = new HashMap<String, Map<Integer, JsonObject>>();
      for (var e : WorldFirstTalkSources.KEYS.entrySet()) {
        var value = json.get("ExcelOutput/" + e.getKey() + ".json");
        if (!value.isJsonArray() || value.getAsJsonArray().size() > 131072) return null;
        var rows = new HashMap<Integer, JsonObject>();
        for (var v : value.getAsJsonArray()) {
          if (!v.isJsonObject() || !v.getAsJsonObject().has(e.getValue())) return null;
          var row = v.getAsJsonObject();
          int id = row.get(e.getValue()).getAsInt();
          if (id <= 0 || rows.put(id, row) != null) return null;
        }
        tables.put(e.getKey(), Map.copyOf(rows));
      }
      for (var e : WorldFirstTalkSources.ROWS.entrySet()) {
        var p = e.getKey().split(":");
        var row = tables.get(p[0]).get(Integer.parseInt(p[1]));
        if (row == null || !e.getValue().equals(TimelineActivityRules.fingerprint(row)))
          return null;
      }
      var next = new WorldFirstTalkRules(root, hashes, tables, json);
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
            case "MazePlane" -> GameData.getMazePlaneExcelMap().get(id);
            case "MapEntrance" -> GameData.getMapEntranceExcelMap().get(id);
            case "MazeProp" -> GameData.getPropExcelMap().get(id);
            default -> null;
          };
      if (actual == null || !gson.toJsonTree(actual).equals(gson.toJsonTree(e.getValue())))
        return false;
      if (e.getValue() instanceof RewardExcel r
          && !r.getRewards().equals(((RewardExcel) actual).getRewards())) return false;
    }
    if (!gson.toJsonTree(prerequisite)
        .equals(gson.toJsonTree(GameplayDefinitions.subs.get(104010102)))) return false;
    for (int id : WorldFirstTalkSources.LOCATIONS.keySet()) {
      var row = tables.get("PerformanceE").get(id);
      var definition = FirstTalkDefinitions.performances.get(id);
      if (definition == null
          || !definition.reportable()
          || !definition.path().equals(row.get("PerformancePath").getAsString())) return false;
    }
    return true;
  }

  boolean unlocked(Player p) {
    return ActivityAccess.unlocked(p, 30013) && MissionStateRecords.subCompleted(p, 104010102);
  }

  GameplayDefinitions.Window window(Player p, Instant now) {
    if (now == null || !unlocked(p)) return null;
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

  String script(int id) {
    return tables.get("PerformanceE").get(id).get("PerformancePath").getAsString();
  }

  PropInfo original(int id, int dimension) {
    var l = WorldFirstTalkSources.LOCATIONS.get(id);
    if (l == null || !l.dimensions().contains(dimension)) return null;
    var floor = GameData.getFloorInfo(l.plane(), l.floor());
    if (floor == null || !floor.isGroupInDimension(l.group(), dimension)) return null;
    var actual = floor.getGroups(dimension).get(l.group());
    var expected = groups.get(l.group());
    if (actual == null
        || !ResourceLoader.getGson()
            .toJsonTree(actual)
            .equals(ResourceLoader.getGson().toJsonTree(expected))) return null;
    var matches =
        actual.getPropList().stream().filter(p -> p != null && p.getID() == l.instance()).toList();
    if (matches.size() != 1) return null;
    var prop = matches.getFirst();
    return prop.getPropID() == l.prop()
            && !prop.isIsDelete()
            && !prop.isIsClientOnly()
            && prop.isLoadOnInitial()
            && String.valueOf(id).equals(prop.getSharedValueByKey("PerformenceID"))
        ? prop
        : null;
  }

  boolean entry(int plane, int floor, int entry) {
    var source = tables.get("MapEntrance").get(entry);
    return source != null
        && source.get("PlaneID").getAsInt() == plane
        && source.get("FloorID").getAsInt() == floor;
  }

  EntityProp actor(Player p, int id) {
    var l = WorldFirstTalkSources.LOCATIONS.get(id);
    var scene = p.getScene();
    if (l == null
        || scene == null
        || scene.getPlayer() != p
        || p.getBattle() != null
        || p.getInstance() != null
        || p.getGameplay().getStoryLines().isTransitionPending()
        || scene.getPlaneId() != l.plane()
        || scene.getFloorId() != l.floor()
        || p.getPlaneId() != scene.getPlaneId()
        || p.getFloorId() != scene.getFloorId()
        || p.getEntryId() != scene.getEntryId()
        || scene.getStoryLineId() != p.getGameplay().getStoryLines().getCurrentId()
        || scene.getPlaneType() != PlaneType.Town && scene.getPlaneType() != PlaneType.Maze
        || !entry(scene.getPlaneId(), scene.getFloorId(), scene.getEntryId())
        || !scene.getLoadedGroups().contains(l.group())) return null;
    var original = original(id, scene.getDimensionId());
    if (original == null) return null;
    var matches =
        scene.getEntitiesByGroup(EntityProp.class, l.group()).stream()
            .filter(e -> e.getInstId() == l.instance())
            .toList();
    if (matches.size() != 1) return null;
    var actor = matches.getFirst();
    return actor.getScene() == scene
            && actor.getEntityId() > 0
            && scene.getEntityById(actor.getEntityId()) == actor
            && actor.getPropId() == l.prop()
            && actor.getPropInfo() != null
            && PropTimelines.source(actor.getPropInfo()).equals(PropTimelines.source(original))
        ? actor
        : null;
  }
}
