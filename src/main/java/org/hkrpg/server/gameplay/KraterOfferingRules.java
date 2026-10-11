package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Original source costs, complete world-shop NPC and both source prerequisite gates. */
final class KraterOfferingRules {
  static final int ID = 6, ITEM = 120004, MAX = 50, QUEST = 6029857;
  static final String GROUP =
      "Config/LevelOutput/SharedRuntimeGroup/Groups_P10402_F10402001/LevelGroup_P10402_F10402001_G24.json";

  private record Cached(String bytes, JsonElement json, String canonical) {}

  private static final Map<Path, Cached> cache = new LinkedHashMap<>(64, .75f, true);
  private static final Map<Integer, KraterOfferingRules> currentById = new HashMap<>();
  private final int id;
  private final Path root;
  private final Map<String, String> hashes;
  private final Map<String, JsonObject> rows;
  private final Map<String, Object> expected = new HashMap<>();
  private final GroupInfo group;
  private final NpcInfo npc;
  private final MissionInfo.SubMissionInfo prerequisite;
  private final String source;

  private KraterOfferingRules(
      int id,
      Path root,
      Map<String, String> hashes,
      Map<String, JsonObject> rows,
      Map<String, JsonElement> files) {
    this.id = id;
    this.root = root;
    this.hashes = Map.copyOf(hashes);
    this.rows = Map.copyOf(rows);
    var gson = ResourceLoader.getGson();
    for (var entry : rows.entrySet()) {
      String table = entry.getKey().split(":")[0];
      var row = entry.getValue();
      Object value;
      if (table.startsWith("ItemConfig") || table.equals("ItemPlayerCard"))
        value = gson.fromJson(row, ItemExcel.class);
      else
        value =
            switch (table) {
              case "QuestData" -> gson.fromJson(row, QuestExcel.class);
              case "FinishWay" -> gson.fromJson(row, FinishWayExcel.class);
              case "RewardData" -> {
                var reward = gson.fromJson(row, RewardExcel.class);
                reward.onLoad();
                yield reward;
              }
              case "ActivityPanel" -> gson.fromJson(row, ActivityPanelExcel.class);
              case "MainMission" -> gson.fromJson(row, MainMissionExcel.class);
              case "MazePlane" -> gson.fromJson(row, MazePlaneExcel.class);
              case "MapEntrance" -> gson.fromJson(row, MapEntranceExcel.class);
              case "NPCData" -> gson.fromJson(row, NpcExcel.class);
              default -> null;
            };
      if (value != null) expected.put(entry.getKey(), value);
    }
    group = gson.fromJson(files.get(GROUP), GroupInfo.class);
    group.setId(24);
    npc = group.getNPCList().stream().filter(n -> n.getID() == 400001).findFirst().orElseThrow();
    if (id == 6) {
      var subs =
          files
              .get("Config/Level/Mission/1040104/MissionInfo_1040104.partial.json")
              .getAsJsonObject()
              .getAsJsonArray("SubMissionList");
      prerequisite =
          subs.asList().stream()
              .map(v -> gson.fromJson(v, MissionInfo.SubMissionInfo.class))
              .filter(v -> v.getId() == 104010403)
              .findFirst()
              .orElseThrow();

    } else prerequisite = null;
    source =
        TimelineActivityRules.fingerprint(
            gson.toJsonTree(Map.of("rows", rowPins(id), "files", filePins(id))));
  }

  private static Map<String, String> rowPins(int id) {
    return id == 6 ? KraterOfferingSources.ROWS : WorldShopOfferingSources.ROWS;
  }

  private static Map<String, String> filePins(int id) {
    return id == 6 ? KraterOfferingSources.FILES : WorldShopOfferingSources.FILES;
  }

  private static Map<String, String> keys(int id) {
    return id == 6 ? KraterOfferingSources.KEYS : WorldShopOfferingSources.KEYS;
  }

  int id() {
    return id;
  }

  int item() {
    return id == 6 ? 120004 : 120005;
  }

  static KraterOfferingRules load() {
    return load(6);
  }

  static synchronized KraterOfferingRules load(int id) {
    if (id != 6 && id != 10) return null;
    var current = currentById.get(id);
    if (GameData.getNpcExcelMap().get(100) == null) return null;
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var real = root.toRealPath();
      var paths = new TreeSet<>(filePins(id).keySet());
      keys(id).keySet().forEach(t -> paths.add("ExcelOutput/" + t + ".json"));
      var hashes = new TreeMap<String, String>();
      var files = new TreeMap<String, JsonElement>();
      for (String path : paths) {
        var file = root.resolve(path);
        if (!Files.isRegularFile(file)
            || Files.size(file) > 64L * 1024 * 1024
            || !file.toRealPath().startsWith(real)) return null;
        var bytes = Files.readAllBytes(file);
        String raw = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var saved = cache.get(file);
        if (saved == null || !saved.bytes().equals(raw)) {
          var json =
              JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
          saved = new Cached(raw, json, TimelineActivityRules.fingerprint(json));
          cache.put(file, saved);
          while (cache.size() > 64) cache.remove(cache.keySet().iterator().next());
        }
        hashes.put(path, saved.canonical());
        files.put(path, saved.json());
      }
      if (current != null && current.root.equals(root) && current.hashes.equals(hashes))
        return current.loaded() ? current : null;
      for (var entry : filePins(id).entrySet())
        if (!entry.getValue().equals(hashes.get(entry.getKey()))) return null;
      var selected = new HashMap<String, JsonObject>();
      for (var table : keys(id).entrySet()) {
        var json = files.get("ExcelOutput/" + table.getKey() + ".json");
        if (!json.isJsonArray() || json.getAsJsonArray().size() > 131072) return null;
        var all = new HashMap<String, JsonObject>();
        for (var value : json.getAsJsonArray()) {
          if (!value.isJsonObject()) return null;
          var row = value.getAsJsonObject();
          var parts = new ArrayList<String>();
          for (String key : table.getValue().split(",")) {
            if (!row.has(key) || !row.get(key).getAsString().matches("[1-9][0-9]*")) return null;
            parts.add(Integer.toString(row.get(key).getAsInt()));
          }
          String key = table.getKey() + ":" + String.join(":", parts);
          if (all.put(key, row) != null) return null;
        }
        for (var pin : rowPins(id).entrySet()) {
          if (!pin.getKey().startsWith(table.getKey() + ":")) continue;
          var row = all.get(pin.getKey());
          if (row == null || !pin.getValue().equals(TimelineActivityRules.fingerprint(row)))
            return null;
          selected.put(pin.getKey(), row);
        }
      }
      var next = new KraterOfferingRules(id, root, hashes, selected, files);
      if (!next.loaded()) return null;
      currentById.put(id, next);
      return next;
    } catch (Exception invalid) {
      return null;
    }
  }

  private boolean loaded() {
    var gson = ResourceLoader.getGson();
    for (var entry : expected.entrySet()) {
      var key = entry.getKey().split(":");
      int id = Integer.parseInt(key[1]);
      String table = key[0];
      Object actual;
      if (table.startsWith("ItemConfig") || table.equals("ItemPlayerCard"))
        actual = GameData.getItemExcelMap().get(id);
      else
        actual =
            switch (table) {
              case "QuestData" -> GameData.getQuestExcelMap().get(id);
              case "FinishWay" -> GameData.getFinishWayExcelMap().get(id);
              case "RewardData" -> GameData.getRewardExcelMap().get(id);
              case "ActivityPanel" -> GameData.getActivityPanelExcelMap().get(id);
              case "MainMission" -> GameData.getMainMissionExcelMap().get(id);
              case "MazePlane" -> GameData.getMazePlaneExcelMap().get(id);
              case "MapEntrance" -> GameData.getMapEntranceExcelMap().get(id);
              case "NPCData" -> GameData.getNpcExcelMap().get(id);
              default -> null;
            };
      if (actual == null || !gson.toJsonTree(actual).equals(gson.toJsonTree(entry.getValue())))
        return false;
      if (entry.getValue() instanceof RewardExcel reward
          && !reward.getRewards().equals(((RewardExcel) actual).getRewards())) return false;
    }
    if (id == 10) return true;
    var actual = GameplayDefinitions.subs.get(104010403);
    if (actual == null) return false;
    var first = gson.toJsonTree(prerequisite).getAsJsonObject();
    var second = gson.toJsonTree(actual).getAsJsonObject();
    first.remove("task");
    second.remove("task");
    return first.equals(second);
  }

  String source() {
    return source;
  }

  boolean chestFinder(ItemExcel item) {
    return item != null
        && ResourceLoader.getGson()
            .toJsonTree(item)
            .equals(ResourceLoader.getGson().toJsonTree(expected.get("ItemConfig:408905")));
  }

  int cost(int level) {
    return rows.get("OfferingLevelConfig:" + id + ":" + level).get("ItemCost").getAsInt();
  }

  int reward(int level) {
    return rows.get("OfferingLevelConfig:" + id + ":" + level).get("RewardID").getAsInt();
  }

  int normalCost() {
    int sum = 0;
    for (int level = 1; level <= MAX; level++) sum = Math.addExact(sum, cost(level));
    return sum;
  }

  int level(int paid) {
    int level = 0;
    while (level < MAX && paid >= cost(level + 1)) paid -= cost(++level);
    return level;
  }

  int spent(int level) {
    int sum = 0;
    for (int l = 1; l <= level; l++) sum = Math.addExact(sum, cost(l));
    return sum;
  }

  Map<Integer, Integer> recipe(Collection<Integer> levels, int tail, boolean converted) {
    try {
      var out = new TreeMap<Integer, Integer>();
      for (int level : levels) {
        int multiplier = level == 51 ? tail : 1;
        if (multiplier < 1 || level < 1 || level > 51) return null;
        var row = rows.get("RewardData:" + reward(level));
        if (row.has("Hcoin"))
          out.merge(1, Math.multiplyExact(row.get("Hcoin").getAsInt(), multiplier), Math::addExact);
        for (var entry : row.entrySet())
          if (entry.getKey().startsWith("ItemID_")) {
            int id = entry.getValue().getAsInt(),
                count = row.get("Count_" + entry.getKey().substring(7)).getAsInt();
            if (id < 1 || count < 1) return null;
            out.merge(id, Math.multiplyExact(count, multiplier), Math::addExact);
          }
      }
      if (converted && out.containsKey(22))
        out.merge(2, Math.multiplyExact(out.remove(22), 10), Math::addExact);
      return out.isEmpty() ? null : Collections.unmodifiableMap(out);
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  boolean unlocked(Player p) {
    return id == 6
        ? MissionStateRecords.subCompleted(p, 104010403)
        : MissionStateRecords.mainCompleted(p, 1040505);
  }

  GameplayDefinitions.Window window(Player p, Instant now) {
    if (id != 6 || now == null || !unlocked(p) || !ActivityAccess.unlocked(p, 30013)) return null;
    var found =
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
    return found.size() == 1 ? found.getFirst() : null;
  }

  GroupInfo original(int dimension) {
    if (!Set.of(0, 5, 6, 7, 8, 1004, 9).contains(dimension)) return null;
    var floor = GameData.getFloorInfo(10402, 10402001);
    if (floor == null || !floor.isGroupInDimension(24, dimension)) return null;
    var loaded = floor.getGroups(dimension).get(24);
    return loaded != null
            && ResourceLoader.getGson()
                .toJsonTree(loaded)
                .equals(ResourceLoader.getGson().toJsonTree(group))
        ? loaded
        : null;
  }

  EntityNpc actor(Player p, int entity) {
    var scene = p.getScene();
    if (entity <= 0
        || scene == null
        || scene.getPlayer() != p
        || p.getBattle() != null
        || p.getInstance() != null
        || p.getGameplay().getStoryLines().isTransitionPending()
        || scene.getStoryLineId() != p.getGameplay().getStoryLines().getCurrentId()
        || scene.getWorldId() != 501
        || scene.getPlaneType() != PlaneType.Town
        || p.getPlaneId() != 10402
        || p.getFloorId() != 10402001
        || p.getEntryId() != 1040201
        || p.getWorldId() != 501
        || scene.getPlaneId() != 10402
        || scene.getFloorId() != 10402001
        || scene.getEntryId() != 1040201
        || !scene.getLoadedGroups().contains(24)
        || original(scene.getDimensionId()) == null) return null;
    var actor = scene.getEntityById(entity);
    if (!(actor instanceof EntityNpc loaded)
        || loaded.getScene() != scene
        || loaded.getGroupId() != 24
        || loaded.getInstId() != 400001
        || loaded.getNpcId() != 100) return null;
    long count =
        scene.getEntities().values().stream()
            .filter(e -> e instanceof EntityNpc && e.getGroupId() == 24 && e.getInstId() == 400001)
            .count();
    return count == 1
            && ResourceLoader.getGson()
                .toJsonTree(loaded.getPos())
                .equals(ResourceLoader.getGson().toJsonTree(npc.getPos()))
            && ResourceLoader.getGson()
                .toJsonTree(loaded.getRot())
                .equals(ResourceLoader.getGson().toJsonTree(npc.getRot()))
        ? loaded
        : null;
  }
}
