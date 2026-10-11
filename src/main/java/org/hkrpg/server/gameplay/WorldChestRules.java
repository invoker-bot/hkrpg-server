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
final class WorldChestRules {
  static final int FIRST = 6029850;
  static final int LAST = 6029852;
  private static WorldChestRules current;
  final Path root;
  final Map<String, String> hashes;
  final Map<String, Map<Integer, JsonObject>> tables;
  final Map<String, JsonElement> files;
  final Map<String, String> rowPins, filePins;
  final Map<Integer, WorldChestSources.Location> locations;
  private final Map<String, Object> expected = new HashMap<>();
  private final Map<Integer, GroupInfo> groups = new HashMap<>();

  private WorldChestRules(
      Path root,
      Map<String, String> hashes,
      Map<String, Map<Integer, JsonObject>> tables,
      Map<String, JsonElement> files,
      boolean flames,
      boolean spears) {
    this.root = root;
    this.hashes = Map.copyOf(hashes);
    this.tables = Map.copyOf(tables);
    this.files = Map.copyOf(files);
    rowPins = rows(flames, spears);
    filePins = files(flames, spears);
    locations =
        GoldenSpearSources.merge(
            flames ? TimeRelayFlameSources.LOCATIONS : WorldChestSources.LOCATIONS,
            spears ? GoldenSpearSources.PUZZLES : Map.of());
    var gson = ResourceLoader.getGson();
    for (String key : rowPins.keySet()) {
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
            case "InteractConfig" -> gson.fromJson(row, InteractExcel.class);
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
    for (var l : locations.values()) {
      var g = gson.fromJson(files.get(l.path()), GroupInfo.class);
      g.setId(l.group());
      groups.put(l.group(), g);
    }
  }

  private static Map<String, String> rows(boolean flames, boolean spears) {
    return GoldenSpearSources.merge(
        flames ? TimeRelayFlameSources.ROWS : WorldChestSources.ROWS,
        spears ? GoldenSpearSources.ROWS : Map.of());
  }

  private static Map<String, String> files(boolean flames, boolean spears) {
    return GoldenSpearSources.merge(
        flames ? TimeRelayFlameSources.FILES : WorldChestSources.FILES,
        spears ? GoldenSpearSources.FILES : Map.of());
  }

  static synchronized WorldChestRules load() {
    if (GameData.getQuestExcelMap().get(FIRST) == null
        || GameData.getPropExcelMap().get(60401) == null) return null;
    try {
      boolean flames = GameData.getPropExcelMap().get(104046) != null;
      if (flames != (GameData.getQuestExcelMap().get(TimeRelayFlameSources.GOAL) != null))
        return null;
      boolean spears = GameData.getPropExcelMap().get(GoldenSpearSources.SPEAR) != null;
      if (spears != (GameData.getPropExcelMap().get(GoldenSpearSources.SHIELD) != null)
          || spears != (GameData.getQuestExcelMap().get(GoldenSpearSources.GOAL) != null))
        return null;
      var filePins = files(flames, spears);
      var rowPins = rows(flames, spears);
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var hashes = new TreeMap<String, String>();
      var json = new TreeMap<String, JsonElement>();
      var paths = new TreeSet<>(filePins.keySet());
      for (String table : WorldChestSources.KEYS.keySet())
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
      for (var e : filePins.entrySet())
        if (!e.getValue().equals(hashes.get(e.getKey()))) return null;
      var tables = new HashMap<String, Map<Integer, JsonObject>>();
      for (var e : WorldChestSources.KEYS.entrySet()) {
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
      for (var e : rowPins.entrySet()) {
        var p = e.getKey().split(":");
        var row = tables.get(p[0]).get(Integer.parseInt(p[1]));
        if (row == null || !e.getValue().equals(TimelineActivityRules.fingerprint(row)))
          return null;
      }
      var next = new WorldChestRules(root, hashes, tables, json, flames, spears);
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
            case "InteractConfig" -> GameData.getInteractExcelMap().get(id);
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
    return true;
  }

  boolean unlocked(Player p) {
    return ActivityAccess.unlocked(p, 30013);
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

  JsonObject chest(int id) {
    return tables.get("MazeChest").get(id);
  }

  List<Integer> goals() {
    var out = new ArrayList<>(List.of(FIRST, FIRST + 1, LAST));
    if (locations.keySet().stream().anyMatch(this::flame)) out.add(TimeRelayFlameSources.GOAL);
    if (locations.keySet().stream().anyMatch(this::golden)) out.add(GoldenSpearSources.GOAL);
    return List.copyOf(out);
  }

  boolean flame(int id) {
    return locations.containsKey(id) && TimeRelayFlameSources.PUZZLES.containsKey(id);
  }

  boolean golden(int id) {
    return locations.containsKey(id) && GoldenSpearSources.PUZZLES.containsKey(id);
  }

  boolean puzzle(int id) {
    return flame(id) || golden(id);
  }

  boolean countsGoal(int goal, int id) {
    return goals().contains(goal)
        && (goal == TimeRelayFlameSources.GOAL
            ? flame(id)
                && chest(id).getAsJsonArray("ChestType").asList().stream()
                    .anyMatch(t -> "CHEST_PUZZLE_TIME_RELAY_FLAME".equals(t.getAsString()))
            : goal != GoldenSpearSources.GOAL
                || golden(id)
                    && chest(id).getAsJsonArray("ChestType").asList().stream()
                        .anyMatch(t -> "CHEST_PUZZLE_GOLDEN_SPEAR".equals(t.getAsString())));
  }

  Map<Integer, Integer> loot(int id) {
    var row = chest(id);
    if (row == null || row.get("WorldID").getAsInt() != 501) return null;
    return WorldChestRewards.recipe(row.getAsJsonArray("ChestType"));
  }

  PropInfo original(int id, int dimension) {
    var l = locations.get(id);
    if (l == null) return null;
    var prop = groupProp(id, dimension, l.instance(), l.prop());
    return prop != null
            && prop.getState()
                == (flame(id)
                    ? emu.lunarcore.game.enums.PropState.ChestLocked
                    : golden(id)
                        ? emu.lunarcore.game.enums.PropState.Hidden
                        : emu.lunarcore.game.enums.PropState.ChestClosed)
        ? prop
        : null;
  }

  PropInfo groupProp(int id, int dimension, int instance, int propId) {
    var l = locations.get(id);
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
        actual.getPropList().stream().filter(p -> p != null && p.getID() == instance).toList();
    if (matches.size() != 1) return null;
    var prop = matches.getFirst();
    return prop.getPropID() == propId
            && !prop.isIsDelete()
            && !prop.isIsClientOnly()
            && prop.isLoadOnInitial()
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
    var l = locations.get(id);
    return l == null ? null : actor(p, id, l.instance(), l.prop());
  }

  EntityProp actor(Player p, int id, int instance, int propId) {
    var l = locations.get(id);
    var scene = p.getScene();
    if (l == null
        || scene == null
        || scene.getPlayer() != p
        || p.getBattle() != null
        || p.getInstance() != null
        || p.getGameplay().getStoryLines().isTransitionPending()
        || scene.getPlaneId() != l.plane()
        || scene.getFloorId() != l.floor()
        || scene.getWorldId() != 501
        || p.getWorldId() != 501
        || p.getPlaneId() != scene.getPlaneId()
        || p.getFloorId() != scene.getFloorId()
        || p.getEntryId() != scene.getEntryId()
        || scene.getStoryLineId() != p.getGameplay().getStoryLines().getCurrentId()
        || scene.getPlaneType() != PlaneType.Town && scene.getPlaneType() != PlaneType.Maze
        || !entry(scene.getPlaneId(), scene.getFloorId(), scene.getEntryId())
        || !scene.getLoadedGroups().contains(l.group())) return null;
    var original =
        instance == l.instance() && propId == l.prop()
            ? original(id, scene.getDimensionId())
            : groupProp(id, scene.getDimensionId(), instance, propId);
    if (original == null) return null;
    var matches =
        scene.getEntitiesByGroup(EntityProp.class, l.group()).stream()
            .filter(e -> e.getInstId() == instance)
            .toList();
    if (matches.size() != 1) return null;
    var actor = matches.getFirst();
    return actor.getScene() == scene
            && actor.getEntityId() > 0
            && scene.getEntityById(actor.getEntityId()) == actor
            && actor.getPropId() == propId
            && actor.getPropInfo() != null
            && PropTimelines.source(actor.getPropInfo()).equals(PropTimelines.source(original))
        ? actor
        : null;
  }
}
