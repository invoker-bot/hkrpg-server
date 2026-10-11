package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityNpc;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Pinned original ride goal, live operator window and current source NPC admission. */
final class CavalryBeastRules {
  private static CavalryBeastRules current;
  private final Path root;
  private final Map<String, String> hashes;
  private final Map<String, JsonObject> rows;
  private final Map<String, Object> expected = new HashMap<>();
  private final Map<String, GroupInfo> groups = new HashMap<>();
  private final Map<String, FloorInfo> floors = new HashMap<>();
  private final Map<Integer, MissionInfo.SubMissionInfo> prerequisites = new HashMap<>();

  private CavalryBeastRules(Path root, Map<String, String> hashes, Map<String, JsonObject> rows,
      Map<String, JsonElement> files) {
    this.root = root;
    this.hashes = Map.copyOf(hashes);
    this.rows = Map.copyOf(rows);
    var gson = ResourceLoader.getGson();
    for (var entry : rows.entrySet()) {
      var raw = entry.getValue().deepCopy();
      Object value = switch (entry.getKey().split(":")[0]) {
        case "QuestData" -> gson.fromJson(raw, QuestExcel.class);
        case "FinishWay" -> gson.fromJson(raw, FinishWayExcel.class);
        case "RewardData" -> {
          var reward = gson.fromJson(raw, RewardExcel.class);
          reward.onLoad();
          yield reward;
        }
        case "ItemConfig" -> gson.fromJson(raw, ItemExcel.class);
        case "ActivityPanel" -> gson.fromJson(raw, ActivityPanelExcel.class);
        case "MainMission" -> gson.fromJson(raw, MainMissionExcel.class);
        case "NPCData" -> gson.fromJson(raw, NpcExcel.class);
        case "MapEntrance" -> gson.fromJson(raw, MapEntranceExcel.class);
        case "MazePlane" -> {
          if (raw.has("PlaneName") && raw.get("PlaneName").isJsonObject())
            raw.addProperty("PlaneName", raw.getAsJsonObject("PlaneName").get("Hash").getAsLong());
          yield gson.fromJson(raw, MazePlaneExcel.class);
        }
        default -> null;
      };
      if (value != null) expected.put(entry.getKey(), value);
    }
    for (var entry : files.entrySet()) {
      if (entry.getKey().startsWith("Config/LevelOutput/RuntimeFloor/")) {
        var floor = gson.fromJson(entry.getValue(), FloorInfo.class);
        String key = Path.of(entry.getKey()).getFileName().toString().replace(".json", "");
        floors.put(key, floor);
      } else if (entry.getKey().startsWith("Config/Level/Mission/")) {
        if (entry.getKey().endsWith(".partial.json")) continue;
        var merged = entry.getValue().getAsJsonObject().deepCopy();
        var partial = files.get(entry.getKey().replace(".json", ".partial.json")).getAsJsonObject();
        for (var field : partial.entrySet()) {
          if (merged.has(field.getKey()) && !merged.get(field.getKey()).equals(field.getValue()))
            throw new IllegalArgumentException("Conflicting original cavalry prerequisite header");
          merged.add(field.getKey(), field.getValue());
        }
        var header = gson.fromJson(merged, MissionInfo.class);
        for (var sub : header.getSubMissionList()) if (Set.of(104010102, 104010320).contains(sub.getId())) {
          if (header.getMainMissionID() <= 0 || sub.getMainMissionID() != header.getMainMissionID())
            throw new IllegalArgumentException("Changed original cavalry prerequisite owner");
          if (prerequisites.put(sub.getId(), sub) != null) throw new IllegalArgumentException("Duplicate cavalry prerequisite");
        }
      }
    }
    for (var binding : CavalryBeastSources.BINDINGS) {
      if (groups.containsKey(binding.path())) continue;
      var raw = files.get(binding.path()).getAsJsonObject();
      var tagged = raw.getAsJsonArray("NPCList").asList().stream().map(JsonElement::getAsJsonObject)
          .filter(npc -> npc.get("ID").getAsInt() == binding.instance()).toList();
      if (tagged.size() != 1 || !tagged.getFirst().has("TagOverrideConfig"))
        throw new IllegalArgumentException("Missing original cavalry tag");
      var tags = tagged.getFirst().getAsJsonObject("TagOverrideConfig").getAsJsonObject("OverrideTags").getAsJsonArray("List");
      if (tags.asList().stream().map(JsonElement::getAsJsonObject)
          .filter(tag -> "NPC.CavalryBeast".equals(tag.get("Tag").getAsString())
              && tag.get("TagHash").getAsLong() == 2136885204L).count() != 1)
        throw new IllegalArgumentException("Changed original cavalry tag");
      var group = gson.fromJson(raw, GroupInfo.class);
      group.setId(binding.group());
      // Resource loading normalizes prop state/value sources; preserve the same known transform.
      var normalizer = new FloorInfo();
      normalizer.getGroups().put(group.getId(), group);
      normalizer.onLoad();
      groups.put(binding.path(), group);
    }
    if (prerequisites.size() != 2) throw new IllegalArgumentException("Missing cavalry prerequisites");
  }

  static synchronized CavalryBeastRules load() {
    if (GameData.getQuestExcelMap().get(CavalryBeastSources.QUEST) == null) return null;
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var hashes = new TreeMap<String, String>();
      var texts = new TreeMap<String, String>();
      for (String table : CavalryBeastSources.KEYS.keySet()) {
        String path = "ExcelOutput/" + table + ".json";
        String text = read(root, path, 16 * 1024 * 1024);
        texts.put(path, text);
        hashes.put(path, hash(text));
      }
      for (String path : CavalryBeastSources.FILES.keySet()) {
        String text = read(root, path, 2 * 1024 * 1024);
        texts.put(path, text);
        hashes.put(path, hash(text));
      }
      if (current != null && current.root.equals(root) && current.hashes.equals(hashes))
        return current.loaded() ? current : null;
      var rows = new HashMap<String, JsonObject>();
      var files = new HashMap<String, JsonElement>();
      for (var entry : texts.entrySet()) {
        var value = JsonParser.parseString(entry.getValue());
        if (entry.getKey().startsWith("ExcelOutput/")) {
          String table = Path.of(entry.getKey()).getFileName().toString().replace(".json", "");
          if (!value.isJsonArray() || value.getAsJsonArray().size() > 32768) return null;
          for (var item : value.getAsJsonArray()) {
            if (!item.isJsonObject()) return null;
            var row = item.getAsJsonObject();
            String field = CavalryBeastSources.KEYS.get(table);
            if (!row.has(field)) return null;
            String key = table + ":" + row.get(field).getAsString();
            var pin = CavalryBeastSources.ROWS.get(key);
            if (pin == null) continue;
            if (rows.put(key, row) != null || !pin.equals(TimelineActivityRules.fingerprint(row))) return null;
          }
        } else {
          if (!CavalryBeastSources.FILES.get(entry.getKey()).equals(TimelineActivityRules.fingerprint(value))) return null;
          files.put(entry.getKey(), value);
        }
      }
      if (rows.size() != CavalryBeastSources.ROWS.size()) return null;
      var next = new CavalryBeastRules(root, hashes, rows, files);
      if (!next.loaded()) return null;
      current = next;
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
      Object live = switch (key[0]) {
        case "QuestData" -> GameData.getQuestExcelMap().get(id);
        case "FinishWay" -> GameData.getFinishWayExcelMap().get(id);
        case "RewardData" -> GameData.getRewardExcelMap().get(id);
        case "ItemConfig" -> GameData.getItemExcelMap().get(id);
        case "ActivityPanel" -> GameData.getActivityPanelExcelMap().get(id);
        case "MainMission" -> GameData.getMainMissionExcelMap().get(id);
        case "NPCData" -> GameData.getNpcExcelMap().get(id);
        case "MazePlane" -> GameData.getMazePlaneExcelMap().get(id);
        case "MapEntrance" -> GameData.getMapEntranceExcelMap().get(id);
        default -> null;
      };
      if (live == null || !gson.toJsonTree(entry.getValue()).equals(gson.toJsonTree(live))) return false;
      if (entry.getValue() instanceof RewardExcel reward
          && (!(live instanceof RewardExcel actual) || !reward.getRewards().equals(actual.getRewards()))) return false;
    }
    for (var entry : prerequisites.entrySet()) {
      var live = GameplayDefinitions.subs.get(entry.getKey());
      if (live == null) return false;
      var first = gson.toJsonTree(entry.getValue()).getAsJsonObject();
      var second = gson.toJsonTree(live).getAsJsonObject();
      first.remove("task");
      second.remove("task");
      if (!first.equals(second)) return false;
    }
    return true;
  }

  boolean unlocked(Player player) {
    if (!ActivityAccess.unlocked(player, CavalryBeastSources.PANEL)) return false;
    var unlock = rows.get("FuncUnlockData:106001").getAsJsonArray("Conditions");
    if (unlock.size() != 1 || !"FinishSubMission".equals(unlock.get(0).getAsJsonObject().get("Type").getAsString())
        || !MissionStateRecords.subCompleted(player, 104010102)) return false;
    var cycle = rows.get("CycleQuest:6029860");
    return cycle.get("IsNonPeriodic").getAsBoolean()
        && player.getLevel() >= cycle.get("MinLevel").getAsInt()
        && player.getLevel() <= cycle.get("MaxLevel").getAsInt();
  }

  GameplayDefinitions.Window window(Player player, Instant now) {
    if (now == null || !unlocked(player)) return null;
    var active = GameplayDefinitions.windows.stream().filter(window -> window.panelId() == CavalryBeastSources.PANEL
        && window.moduleId() == CavalryBeastSources.MODULE && window.beginTime() > 0
        && window.endTime() > window.beginTime() && window.beginTime() <= now.getEpochSecond()
        && now.getEpochSecond() < window.endTime()).toList();
    return active.size() == 1 ? active.getFirst() : null;
  }

  boolean entry(int plane, int floor, int id) {
    var row = rows.get("MapEntrance:" + id);
    return row != null && row.get("PlaneID").getAsInt() == plane && row.get("FloorID").getAsInt() == floor;
  }

  CavalryBeastSources.Binding binding(int plane, int floor, int dimension, int group, int instance) {
    var matches = CavalryBeastSources.BINDINGS.stream().filter(value -> value.plane() == 10401
        && value.floor() == 10401001 && value.plane() == plane && value.floor() == floor
        && value.group() == group && value.instance() == instance && value.dimensions().contains(dimension)).toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  EntityNpc actor(Player player, int groupId, int instance) {
    var scene = player.getScene();
    if (scene == null || scene.getPlayer() != player || player.isInBattle() || player.getBattle() != null
        || player.getInstance() != null || player.getGameplay().getStoryLines().isTransitionPending()
        || scene.getPlaneType() != emu.lunarcore.game.enums.PlaneType.Town
        || scene.getWorldId() != CavalryBeastSources.WORLD || player.getWorldId() != scene.getWorldId()
        || scene.getPlaneId() != player.getPlaneId() || scene.getFloorId() != player.getFloorId()
        || scene.getEntryId() != player.getEntryId() || scene.getStoryLineId() < 0
        || scene.getStoryLineId() != player.getGameplay().getStoryLines().getCurrentId()
        || !entry(scene.getPlaneId(), scene.getFloorId(), scene.getEntryId())
        || !scene.getLoadedGroups().contains(groupId)) return null;
    var origin = binding(scene.getPlaneId(), scene.getFloorId(), scene.getDimensionId(), groupId, instance);
    if (origin == null) return null;
    try {
      var floor = GameData.getFloorInfo(scene.getPlaneId(), scene.getFloorId());
      var expectedFloor = floors.get("P" + scene.getPlaneId() + "_F" + scene.getFloorId());
      var gson = ResourceLoader.getGson();
      if (floor == null || expectedFloor == null || floor != scene.getFloorInfo()
          || !gson.toJsonTree(floor).equals(gson.toJsonTree(expectedFloor))
          || !floor.isGroupInDimension(groupId, scene.getDimensionId())) return null;
      var liveGroup = floor.getGroups(scene.getDimensionId()).get(groupId);
      var originalGroup = groups.get(origin.path());
      if (liveGroup == null || originalGroup == null || liveGroup.getLoadSide() != GroupInfo.GroupLoadSide.Server
          || !gson.toJsonTree(liveGroup).equals(gson.toJsonTree(originalGroup))
          || !player.getMissionManager().shouldLoadGroup(liveGroup, scene)) return null;
      var definitions = originalGroup.getNPCList().stream().filter(npc -> npc.getID() == instance).toList();
      if (definitions.size() != 1) return null;
      var definition = definitions.getFirst();
      if (definition.getNPCID() != origin.npc() || definition.isIsDelete()
          || definition.isIsClientOnly() || !definition.isLoadOnInitial()) return null;
      var actors = scene.getEntitiesByGroup(EntityNpc.class, groupId).stream()
          .filter(npc -> npc.getInstId() == instance).toList();
      if (actors.size() != 1) return null;
      var actor = actors.getFirst();
      return actor.getScene() == scene && actor.getEntityId() > 0
          && scene.getEntities().get(actor.getEntityId()) == actor && actor.getNpcId() == origin.npc()
          && actor.getPos().getFastDist(definition.getPos()) == 0
          && actor.getRot().getFastDist(definition.getRot()) == 0 ? actor : null;
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  private static String read(Path root, String relative, int limit) throws Exception {
    var file = root.resolve(relative);
    if (!Files.isRegularFile(file) || Files.size(file) > limit || !file.toRealPath().startsWith(root.toRealPath()))
      throw new IllegalArgumentException("Unavailable original cavalry source");
    return Files.readString(file).replace("\r\n", "\n");
  }

  private static String hash(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
