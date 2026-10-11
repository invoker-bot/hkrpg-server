package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.*;
import emu.lunarcore.game.scene.entity.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Full fixed-source records, loaded objects and actual scene entities must agree. */
final class WolfBroAmmoRules {
  private static final Map<String, WolfBroAmmoRules> CACHE = new HashMap<>();
  private static final String AI = "Config/ConfigAI/Adventure/NPCMonster/WolfBro_NPCMonster/ST_Gameplay_WolfBro_NPCMonster_Master.json";
  final WolfBroCatalog.Level level;
  final Map<Integer, GroupInfo> groups;
  final Map<Integer, NpcMonsterExcel> monsters;
  final Map<Integer, PropExcel> props;
  final MazePlaneExcel plane;
  final FloorInfo floor;
  final String signature;
  final Map<String, String> rawHashes;
  final Map<String, JsonElement> originals;
  final int maximum, small;

  private WolfBroAmmoRules(WolfBroCatalog.Level level, Map<String, String> hashes,
      Map<String, JsonElement> files) {
    this.level = level;
    rawHashes = Map.copyOf(hashes);
    originals = Map.copyOf(files);
    groups = new HashMap<>(); monsters = new HashMap<>(); props = new HashMap<>();
    var gson = ResourceLoader.getGson();
    floor = gson.fromJson(files.get(floorPath(level)), FloorInfo.class);
    for (int id : groupIds(level)) {
      var group = gson.fromJson(files.get(groupPath(level, id)), GroupInfo.class);
      group.setId(id); groups.put(id, group);
      floor.getGroups().put(id, group);
    }
    // Compare the same loaded shape, including authored prop normalization and parent binding.
    floor.onLoad();
    var expected = new TreeMap<String, String>();
    files.forEach((key, value) -> {
      if (!key.startsWith("ExcelOutput/")) expected.put(key, TimelineActivityRules.fingerprint(value));
    });
    var planeRow = selected(files, "MazePlane", "PlaneID", Set.of(level.PlaneID())).get(level.PlaneID());
    var copied = planeRow.deepCopy();
    copied.addProperty("PlaneName", copied.getAsJsonObject("PlaneName").get("Hash").getAsLong());
    plane = gson.fromJson(copied, MazePlaneExcel.class);
    var npcIds = new HashSet<Integer>();
    for (var group : groups.values()) for (var monster : group.getMonsterList()) npcIds.add(monster.getNPCMonsterID());
    for (var e : selected(files, "NPCMonsterData", "ID", npcIds).entrySet())
      monsters.put(e.getKey(), gson.fromJson(e.getValue(), NpcMonsterExcel.class));
    for (var e : selected(files, "MazeProp", "ID", Set.of(103068, 103069)).entrySet()) {
      var prop = gson.fromJson(e.getValue(), PropExcel.class);
      prop.onLoad();
      props.put(e.getKey(), prop);
    }
    selected(files, "MazePuzzleWolfBro", "WolfBroID", Set.of(level.WolfBroID()));
    var constants = new HashMap<String, Integer>();
    for (String key : List.of("Wolf_Bro_Close_State", "Wolf_Bro_Start_State", "Wolf_Bro_Small_State", "Wolf_Bro_Max_Bullet_Cnt")) {
      var found = new ArrayList<JsonObject>();
      for (var row : files.get("ExcelOutput/ConstValueCommon.json").getAsJsonArray()) {
        var object = row.getAsJsonObject();
        if (object.get("ConstValueName").getAsString().equals(key)) found.add(object);
      }
      if (found.size() != 1 || !WolfBroAmmoSources.ROWS.get("ConstValueCommon:" + key)
          .equals(TimelineActivityRules.fingerprint(found.getFirst()))) throw new IllegalArgumentException("WolfBro constant drift");
      expected.put("ConstValueCommon:" + key, TimelineActivityRules.fingerprint(found.getFirst()));
      constants.put(key, found.getFirst().getAsJsonObject("Value").get("IntValue").getAsInt());
    }
    for (String key : WolfBroAmmoSources.ROWS.keySet()) {
      String table = key.split(":", 2)[0];
      if (table.equals("ConstValueCommon")) continue;
      String id = key.split(":", 2)[1];
      if (table.equals("MazePuzzleWolfBro") && Integer.parseInt(id) == level.WolfBroID()
          || table.equals("MazePlane") && Integer.parseInt(id) == level.PlaneID()
          || table.equals("MazeProp") || table.equals("NPCMonsterData") && npcIds.contains(Integer.parseInt(id)))
        expected.put(key, WolfBroAmmoSources.ROWS.get(key));
    }
    maximum = constants.get("Wolf_Bro_Max_Bullet_Cnt"); small = constants.get("Wolf_Bro_Small_State");
    signature = TimelineActivityRules.fingerprint(gson.toJsonTree(expected));
  }

  private static Map<Integer, JsonObject> selected(Map<String, JsonElement> files, String table,
      String key, Set<Integer> ids) {
    var result = new HashMap<Integer, JsonObject>();
    for (var row : files.get("ExcelOutput/" + table + ".json").getAsJsonArray()) {
      var object = row.getAsJsonObject();
      int id = object.get(key).getAsInt();
      if (!ids.contains(id)) continue;
      var pin = WolfBroAmmoSources.ROWS.get(table + ":" + id);
      if (result.put(id, object) != null || pin == null
          || !pin.equals(TimelineActivityRules.fingerprint(object))) throw new IllegalArgumentException("WolfBro row drift");
    }
    if (!result.keySet().equals(ids)) throw new IllegalArgumentException("Missing WolfBro source row");
    return result;
  }

  static String floorPath(WolfBroCatalog.Level level) {
    return "Config/LevelOutput/RuntimeFloor/P" + level.PlaneID() + "_F" + level.FloorID() + ".json";
  }
  static String groupPath(WolfBroCatalog.Level level, int id) {
    String name = "P" + level.PlaneID() + "_F" + level.FloorID();
    return "Config/LevelOutput/SharedRuntimeGroup/Groups_" + name + "/LevelGroup_" + name + "_G" + id + ".json";
  }
  private static Set<Integer> groupIds(WolfBroCatalog.Level level) {
    var ids = new TreeSet<>(level.GroupIDList());
    ids.addAll(level.MonsterGroupIDList()); ids.add(level.ControlGroupID()); return ids;
  }

  static synchronized WolfBroAmmoRules read(WolfBroCatalog.Level level) {
    if (level == null) return null;
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var real = root.toRealPath();
      var paths = new TreeSet<String>(); paths.add(floorPath(level));
      for (int id : groupIds(level)) paths.add(groupPath(level, id));
      for (String table : List.of("MazePuzzleWolfBro", "MazePlane", "MazeProp", "NPCMonsterData", "ConstValueCommon"))
        paths.add("ExcelOutput/" + table + ".json");
      var hashes = new TreeMap<String, String>(); var raw = new HashMap<String, byte[]>();
      for (String relative : paths) {
        var path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path) || Files.size(path) > 64 * 1024 * 1024
            || !path.toRealPath().startsWith(real)) return null;
        byte[] bytes = Files.readAllBytes(path);
        hashes.put(relative, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        raw.put(relative, bytes);
      }
      String key = root + ":" + level.WolfBroID();
      var cached = CACHE.get(key);
      if (cached != null && cached.rawHashes.equals(hashes)) return cached;
      var files = new HashMap<String, JsonElement>();
      for (var e : raw.entrySet()) {
        var value = JsonParser.parseString(new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        if (e.getKey().startsWith("ExcelOutput/")) {
          if (!value.isJsonArray() || value.getAsJsonArray().size() > 131072) return null;
        } else if (!Objects.equals(WolfBroAmmoSources.FILES.get(e.getKey()), TimelineActivityRules.fingerprint(value))) return null;
        files.put(e.getKey(), value);
      }
      var next = new WolfBroAmmoRules(level, hashes, files);
      if (CACHE.size() >= 128) CACHE.clear();
      CACHE.put(key, next); return next;
    } catch (Exception invalid) { return null; }
  }

  private static boolean equal(Object a, Object b) {
    return a != null && b != null && ResourceLoader.getGson().toJsonTree(a).equals(ResourceLoader.getGson().toJsonTree(b));
  }

  boolean scene(Player player) {
    var scene = player.getScene();
    if (scene == null || scene.getPlayer() != player || player.isInBattle() || player.getInstance() != null
        || scene.getPlaneId() != level.PlaneID() || scene.getFloorId() != level.FloorID()
        || !equal(scene.getExcel(), plane) || !equal(scene.getFloorInfo(), floor)
        || !scene.getLoadedGroups().contains(level.ControlGroupID())) return false;
    for (var e : groups.entrySet()) {
      if (!scene.getFloorInfo().isGroupInDimension(e.getKey(), scene.getDimensionId())
          || !equal(scene.getFloorInfo().getGroups().get(e.getKey()), e.getValue())) return false;
    }
    for (var e : props.entrySet()) if (!equal(GameData.getPropExcelMap().get(e.getKey()), e.getValue())) return false;
    for (var e : monsters.entrySet()) if (!equal(GameData.getNpcMonsterExcelMap().get(e.getKey()), e.getValue())) return false;
    return true;
  }

  EntityProp gun(Player player, int groupId, int instance) {
    var scene = player.getScene(); var group = groups.get(groupId);
    if (group == null || !level.GroupIDList().contains(groupId)
        || !scene.getLoadedGroups().contains(groupId)) return null;
    var definitions = group.getPropList().stream().filter(p -> p.getID() == instance && p.getPropID() == 103068).toList();
    if (definitions.size() != 1) return null;
    var source = definitions.getFirst();
    if (source.isIsDelete() || !source.isLoadOnInitial()) return null;
    var matches = scene.getEntities().values().stream().filter(EntityProp.class::isInstance)
        .map(EntityProp.class::cast).filter(p -> p.getGroupId() == groupId && p.getInstId() == instance).toList();
    if (matches.size() != 1) return null;
    var actual = matches.getFirst();
    return actual.getScene() == scene && actual.getEntityId() > 0 && actual.getPropId() == 103068
        && equal(actual.getPropInfo(), source) && same(actual.getPos(), source.getPos())
        && actual.getExcel() == GameData.getPropExcelMap().get(103068) ? actual : null;
  }

  MonsterInfo sourceMonster(int groupId, int instance) {
    var group = groups.get(groupId);
    if (group == null || !level.MonsterGroupIDList().contains(groupId)) return null;
    var matches = group.getMonsterList().stream().filter(m -> m.getID() == instance).toList();
    if (matches.size() != 1) return null;
    var original = originals.get(groupPath(level, groupId)).getAsJsonObject().getAsJsonArray("MonsterList");
    JsonObject row = null;
    for (var e : original) if (e.getAsJsonObject().get("ID").getAsInt() == instance) row = e.getAsJsonObject();
    var source = matches.getFirst();
    return row != null && row.has("AIConfig") && row.getAsJsonObject("AIConfig").has("AIFile")
        && AI.equals(row.getAsJsonObject("AIConfig").get("AIFile").getAsString())
        && !source.isIsDelete() && !source.isIsClientOnly() && source.isLoadOnInitial() ? source : null;
  }

  EntityMonster monster(Player player, int entityId) {
    var scene = player.getScene();
    if (!(scene.getEntityById(entityId) instanceof EntityMonster actual)
        || !actual.isAlive() || actual.getEntityId() != entityId || actual.getScene() != scene
        || !scene.getLoadedGroups().contains(actual.getGroupId())) return null;
    var source = sourceMonster(actual.getGroupId(), actual.getInstId());
    return source != null && equal(source, actual.getMonsterInfo())
        && actual.getExcel() == GameData.getNpcMonsterExcelMap().get(source.getNPCMonsterID())
        && actual.getEventId() == source.getEventID()
        && same(actual.getPos(), source.getPos()) ? actual : null;
  }

  static String key(int group, int instance) { return group + ":" + instance; }
  static boolean same(emu.lunarcore.util.Position a, emu.lunarcore.util.Position b) {
    return a != null && b != null && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
  }
}
