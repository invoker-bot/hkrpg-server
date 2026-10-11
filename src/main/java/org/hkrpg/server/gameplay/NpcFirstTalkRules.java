package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Exact original G61 graph binding. Presentation is executed by the client, never as a reward. */
final class NpcFirstTalkRules {
  private static NpcFirstTalkRules current;
  final Path root;
  final Map<String, String> hashes;
  final Map<String, Map<Integer, JsonObject>> tables;
  private final Map<String, Object> expected = new HashMap<>();
  private final GroupInfo group;
  private final FloorInfo floor;
  private final JsonObject npcSource;

  private NpcFirstTalkRules(Path root, Map<String, String> hashes,
      Map<String, Map<Integer, JsonObject>> tables, Map<String, JsonElement> files) {
    this.root = root;
    this.hashes = Map.copyOf(hashes);
    this.tables = Map.copyOf(tables);
    var gson = ResourceLoader.getGson();
    for (String key : NpcFirstTalkSources.ROWS.keySet()) {
      var parts = key.split(":");
      var row = tables.get(parts[0]).get(Integer.parseInt(parts[1]));
      Object value = switch (parts[0]) {
        case "NPCData" -> gson.fromJson(row, NpcExcel.class);
        case "MazePlane" -> gson.fromJson(row, MazePlaneExcel.class);
        case "MapEntrance" -> gson.fromJson(row, MapEntranceExcel.class);
        case "MainMission" -> gson.fromJson(row, MainMissionExcel.class);
        default -> null;
      };
      if (value != null) expected.put(key, value);
    }
    var originalGroup = files.get(NpcFirstTalkSources.GROUP_PATH).getAsJsonObject();
    group = gson.fromJson(originalGroup, GroupInfo.class);
    group.setId(NpcFirstTalkSources.GROUP);
    floor = gson.fromJson(files.get(NpcFirstTalkSources.FLOOR_PATH), FloorInfo.class);
    npcSource = originalGroup.getAsJsonArray("NPCList").get(0).getAsJsonObject();
  }

  /** Only source validation is used during the FirstTalkDefinitions bootstrap. */
  static synchronized NpcFirstTalkRules read(Path resourceRoot) {
    try {
      var root = resourceRoot.toAbsolutePath().normalize();
      var actualRoot = root.toRealPath();
      var paths = new TreeSet<>(NpcFirstTalkSources.FILES.keySet());
      for (String table : NpcFirstTalkSources.KEYS.keySet()) paths.add("ExcelOutput/" + table + ".json");
      var hashes = new TreeMap<String, String>();
      var raw = new TreeMap<String, byte[]>();
      for (String path : paths) {
        var file = root.resolve(path).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)
            || Files.size(file) > 64 * 1024 * 1024 || !file.toRealPath().startsWith(actualRoot)) return null;
        byte[] bytes = Files.readAllBytes(file);
        hashes.put(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        raw.put(path, bytes);
      }
      if (current != null && current.root.equals(root) && current.hashes.equals(hashes)) return current;
      var files = new TreeMap<String, JsonElement>();
      for (var entry : raw.entrySet()) files.put(entry.getKey(), JsonParser.parseString(
          new String(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8)));
      for (var entry : NpcFirstTalkSources.FILES.entrySet())
        if (!entry.getValue().equals(TimelineActivityRules.fingerprint(files.get(entry.getKey())))) return null;
      var tables = new HashMap<String, Map<Integer, JsonObject>>();
      for (var table : NpcFirstTalkSources.KEYS.entrySet()) {
        var values = files.get("ExcelOutput/" + table.getKey() + ".json");
        if (!values.isJsonArray() || values.getAsJsonArray().size() > 131072) return null;
        var selected = new HashMap<Integer, JsonObject>();
        for (var value : values.getAsJsonArray()) {
          if (!value.isJsonObject() || !value.getAsJsonObject().has(table.getValue())) return null;
          var row = value.getAsJsonObject();
          int id = row.get(table.getValue()).getAsInt();
          String key = table.getKey() + ":" + id;
          String pin = NpcFirstTalkSources.ROWS.get(key);
          if (pin != null && (selected.put(id, row) != null
              || !pin.equals(TimelineActivityRules.fingerprint(row)))) return null;
        }
        tables.put(table.getKey(), Map.copyOf(selected));
      }
      for (String key : NpcFirstTalkSources.ROWS.keySet()) {
        var parts = key.split(":");
        if (!tables.get(parts[0]).containsKey(Integer.parseInt(parts[1]))) return null;
      }
      current = new NpcFirstTalkRules(root, hashes, tables, files);
      return current;
    } catch (Exception invalid) {
      return null;
    }
  }

  static NpcFirstTalkRules load() {
    var rules = read(Path.of(LunarCore.getConfig().getResourceDir()));
    return rules != null && rules.loaded() ? rules : null;
  }

  boolean intro(JsonObject row) {
    return row != null && row.equals(tables.get("PerformanceE").get(NpcFirstTalkSources.PERFORMANCE));
  }

  private boolean loaded() {
    var gson = ResourceLoader.getGson();
    for (var entry : expected.entrySet()) {
      var parts = entry.getKey().split(":");
      int id = Integer.parseInt(parts[1]);
      Object actual = switch (parts[0]) {
        case "NPCData" -> GameData.getNpcExcelMap().get(id);
        case "MazePlane" -> GameData.getMazePlaneExcelMap().get(id);
        case "MapEntrance" -> GameData.getMapEntranceExcelMap().get(id);
        case "MainMission" -> GameData.getMainMissionExcelMap().get(id);
        default -> null;
      };
      if (actual == null || !gson.toJsonTree(actual).equals(gson.toJsonTree(entry.getValue()))) return false;
    }
    var definition = FirstTalkDefinitions.performances.get(NpcFirstTalkSources.PERFORMANCE);
    var actual = GameData.getFloorInfo(NpcFirstTalkSources.PLANE, NpcFirstTalkSources.FLOOR);
    return definition != null && definition.reportable() && NpcFirstTalkSources.INTRO.equals(definition.path())
        && actual != null && gson.toJsonTree(actual).equals(gson.toJsonTree(floor)) && original() != null;
  }

  NpcInfo original() {
    var actual = GameData.getFloorInfo(NpcFirstTalkSources.PLANE, NpcFirstTalkSources.FLOOR);
    if (actual == null || !actual.isGroupInDimension(NpcFirstTalkSources.GROUP, 0)) return null;
    var candidate = actual.getGroups(0).get(NpcFirstTalkSources.GROUP);
    if (candidate == null || candidate.getId() != NpcFirstTalkSources.GROUP
        || !ResourceLoader.getGson().toJsonTree(candidate).equals(ResourceLoader.getGson().toJsonTree(group))
        || candidate.getNPCList() == null || candidate.getNPCList().size() != 1) return null;
    var npc = candidate.getNPCList().getFirst();
    return npc != null && npc.getID() == NpcFirstTalkSources.INSTANCE && npc.getNPCID() == NpcFirstTalkSources.NPC
        && !npc.isIsDelete() && !npc.isIsClientOnly() && npc.isLoadOnInitial() ? npc : null;
  }

  String npcSource() { return TimelineActivityRules.fingerprint(npcSource); }

  boolean entry(int entry) {
    var row = tables.get("MapEntrance").get(entry);
    return row != null && row.get("PlaneID").getAsInt() == NpcFirstTalkSources.PLANE
        && row.get("FloorID").getAsInt() == NpcFirstTalkSources.FLOOR;
  }

  private boolean entryUnlocked(Player player, int entry) {
    if (!entry(entry)) return false;
    var row = tables.get("MapEntrance").get(entry);
    for (var id : row.getAsJsonArray("FinishMainMissionList"))
      if (!MissionStateRecords.mainCompleted(player, id.getAsInt())) return false;
    for (var id : row.getAsJsonArray("FinishSubMissionList"))
      if (!MissionStateRecords.subCompleted(player, id.getAsInt())) return false;
    for (var id : row.getAsJsonArray("BeginMainMissionList"))
      if (!MissionStateRecords.mainAccepted(player, id.getAsInt())) return false;
    return true;
  }

  private static boolean position(Position actual, Position expected) {
    return actual != null && expected != null && actual.getX() == expected.getX()
        && actual.getY() == expected.getY() && actual.getZ() == expected.getZ();
  }

  EntityNpc actor(Player player) {
    var scene = player.getScene();
    if (scene == null || scene.getPlayer() != player || player.getBattle() != null || player.getInstance() != null
        || player.getGameplay().getCurrentMissionRaid() != 0 || player.getGameplay().getRogue().isInside()
        || player.getGameplay().getStoryLines().isTransitionPending()
        || scene.getStoryLineId() != 0 || player.getGameplay().getStoryLines().getCurrentId() != 0
        || scene.getDimensionId() != 0 || scene.getPlaneType() != PlaneType.Maze
        || scene.getPlaneId() != NpcFirstTalkSources.PLANE || scene.getFloorId() != NpcFirstTalkSources.FLOOR
        || player.getPlaneId() != scene.getPlaneId() || player.getFloorId() != scene.getFloorId()
        || player.getEntryId() != scene.getEntryId() || !entryUnlocked(player, scene.getEntryId())
        || !MissionStateRecords.mainCompleted(player, NpcFirstTalkSources.PREREQUISITE)
        || !scene.getLoadedGroups().contains(NpcFirstTalkSources.GROUP)) return null;
    var original = original();
    if (original == null) return null;
    var actors = scene.getEntitiesByGroup(EntityNpc.class, NpcFirstTalkSources.GROUP).stream()
        .filter(actor -> actor.getInstId() == NpcFirstTalkSources.INSTANCE).toList();
    if (actors.size() != 1) return null;
    var actor = actors.getFirst();
    return actor.getScene() == scene && actor.getEntityId() > 0 && scene.getEntityById(actor.getEntityId()) == actor
        && actor.getNpcId() == NpcFirstTalkSources.NPC && position(actor.getPos(), original.getPos())
        && position(actor.getRot(), original.getRot()) ? actor : null;
  }
}
