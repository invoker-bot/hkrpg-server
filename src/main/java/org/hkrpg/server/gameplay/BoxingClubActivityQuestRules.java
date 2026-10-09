package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Pins nonperiodic activity ownership, objectives, rewards and inventory definitions. */
final class BoxingClubActivityQuestRules {
  private static final Map<String, String> KEYS =
      Map.ofEntries(
          Map.entry("QuestData", "QuestID"),
          Map.entry("FinishWay", "ID"),
          Map.entry("CycleQuest", "CycleID"),
          Map.entry("RewardData", "RewardID"),
          Map.entry("ItemConfig", "ID"),
          Map.entry("ActivityConfig", "ActivityID"),
          Map.entry("ActivityPanel", "PanelID"),
          Map.entry("BoxingClubActivityQuest", "ID"),
          Map.entry("BoxingClubChallengeSeason", "SeasonID"),
          Map.entry("BoxingClubConstValueClient", "ConstValueName"),
          Map.entry("ConstValueClient", "ConstValueName"));
  private static BoxingClubActivityQuestRules current;

  record Cycle(
      int CycleID,
      List<Integer> QuestList,
      int MinLevel,
      int MaxLevel,
      int Cycledays,
      List<Integer> WeekDayList,
      int ScheduleDataID,
      int ActivityModuleID,
      boolean IsNonPeriodic) {}

  final Map<Integer, Cycle> cycles = new TreeMap<>();
  final Map<Integer, ActivityPanelExcel> panels = new TreeMap<>();
  final Map<String, JsonObject> rows;
  final Path root;
  final Map<String, String> files;
  final Map<Integer, QuestExcel> quests = new TreeMap<>();
  final Map<Integer, FinishWayExcel> finishes = new TreeMap<>();
  final Map<Integer, RewardExcel> rewards = new TreeMap<>();
  final Map<Integer, ItemExcel> items = new TreeMap<>();

  private BoxingClubActivityQuestRules(
      Path root, Map<String, String> files, Map<String, JsonObject> rows) {
    this.rows = Map.copyOf(rows);
    this.root = root;
    this.files = Map.copyOf(files);
    var gson = ResourceLoader.getGson();
    for (var entry : rows.entrySet()) {
      String table = entry.getKey().split(":", 2)[0];
      var row = entry.getValue();
      switch (table) {
        case "CycleQuest" -> {
          var value = gson.fromJson(row, Cycle.class);
          cycles.put(value.CycleID(), value);
        }
        case "ActivityPanel" -> {
          var value = gson.fromJson(row, ActivityPanelExcel.class);
          panels.put(value.getId(), value);
        }
        case "QuestData" -> {
          var value = gson.fromJson(row, QuestExcel.class);
          quests.put(value.getId(), value);
        }
        case "FinishWay" -> {
          var value = gson.fromJson(row, FinishWayExcel.class);
          finishes.put(value.getId(), value);
        }
        case "RewardData" -> {
          var value = gson.fromJson(row, RewardExcel.class);
          value.onLoad();
          rewards.put(value.getId(), value);
        }
        case "ItemConfig" -> {
          var value = gson.fromJson(row, ItemExcel.class);
          items.put(value.getId(), value);
        }
      }
    }
  }

  static synchronized BoxingClubActivityQuestRules load() {
    try {
      // The domain requires all 50 source definitions. Skip resource reads in unrelated catalogs.
      if (GameData.getQuestExcelMap().get(6000060) == null
          || GameData.getQuestExcelMap().get(6000397) == null) return null;
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var files = new TreeMap<String, String>();
      var texts = new TreeMap<String, String>();
      for (String table : KEYS.keySet()) {
        var path = root.resolve("ExcelOutput/" + table + ".json");
        if (!Files.isRegularFile(path) || Files.size(path) > 8 * 1024 * 1024) return null;
        String text = Files.readString(path).replace("\r\n", "\n");
        texts.put(table, text);
        files.put(table, hash(text));
      }
      if (current != null && current.root.equals(root) && current.files.equals(files))
        return current.loaded() ? current : null;
      var rows = new HashMap<String, JsonObject>();
      for (var entry : texts.entrySet()) {
        var json = JsonParser.parseString(entry.getValue());
        if (!json.isJsonArray() || json.getAsJsonArray().size() > 32768) return null;
        for (var value : json.getAsJsonArray()) {
          if (!value.isJsonObject()) return null;
          var row = value.getAsJsonObject();
          String field = KEYS.get(entry.getKey());
          if (!row.has(field)) return null;
          String key = entry.getKey() + ":" + row.get(field).getAsString();
          String expected = BoxingClubActivityQuestSources.ROWS.get(key);
          if (expected == null) continue;
          if (rows.put(key, row) != null || !expected.equals(hash(canonical(row).toString())))
            return null;
        }
      }
      if (rows.size() != BoxingClubActivityQuestSources.ROWS.size()) return null;
      var next = new BoxingClubActivityQuestRules(root, files, rows);
      if (!next.loaded()) return null;
      current = next;
      return next;
    } catch (Exception invalid) {
      return null;
    }
  }

  private boolean loaded() {
    return quests.size() == 50
        && finishes.size() == 50
        && rewards.size() == 39
        && items.size() == 17
        && panels.size() == 2
        && panels.entrySet().stream()
            .allMatch(e -> equal(e.getValue(), GameData.getActivityPanelExcelMap().get(e.getKey())))
        && cycles.size() == 50
        && cycles.values().stream()
            .allMatch(
                c -> {
                  var live = DailyCycleCatalog.cycles.get(c.CycleID());
                  return c.IsNonPeriodic()
                      && c.Cycledays() == 0
                      && c.ScheduleDataID() == 0
                      && c.MinLevel() == 1
                      && c.MaxLevel() == 999
                      && List.of(c.CycleID()).equals(c.QuestList())
                      && List.of(1, 2, 3, 4, 5, 6, 7).equals(c.WeekDayList())
                      && live != null
                      && live.CycleID() == c.CycleID()
                      && live.QuestList().equals(c.QuestList())
                      && live.MinLevel() == c.MinLevel()
                      && live.MaxLevel() == c.MaxLevel()
                      && live.Cycledays() == 0
                      && live.ScheduleDataID() == 0
                      && live.WeekDayList().equals(c.WeekDayList());
                })
        && quests.entrySet().stream()
            .allMatch(e -> equal(e.getValue(), GameData.getQuestExcelMap().get(e.getKey())))
        && finishes.entrySet().stream()
            .allMatch(e -> equal(e.getValue(), GameData.getFinishWayExcelMap().get(e.getKey())))
        && rewards.entrySet().stream()
            .allMatch(e -> equal(e.getValue(), GameData.getRewardExcelMap().get(e.getKey())))
        && items.entrySet().stream()
            .allMatch(e -> equal(e.getValue(), GameData.getItemExcelMap().get(e.getKey())));
  }

  static boolean sourceID(int id) {
    return id >= 6000060 && id <= 6000080 || id >= 6000369 && id <= 6000397;
  }

  static int season(int id) {
    return id < 6000300 ? 1 : 2;
  }

  boolean unlocked(emu.lunarcore.game.player.Player p, QuestExcel quest) {
    if (quest == null || quest.getUnlockParamList() == null) return false;
    if ("AutoUnlock".equals(quest.getUnlockType())) return quest.getUnlockParamList().isEmpty();
    return "FinishMission".equals(quest.getUnlockType())
        && List.of(2010720).equals(quest.getUnlockParamList())
        && p.getMissionManager().finished(2010720);
  }

  GameplayDefinitions.Window window(
      emu.lunarcore.game.player.Player p, int season, java.time.Instant now) {
    int panel = season == 1 ? 50000 : 40003;
    int module = season == 1 ? 5000001 : 4000308;
    if (now == null || !ActivityAccess.unlocked(p, panel)) return null;
    var modules = rows.get("ActivityConfig:" + panel).getAsJsonArray("ActivityModuleIDList");
    if (!modules.contains(new JsonPrimitive(module))) return null;
    var matches =
        GameplayDefinitions.windows.stream()
            .filter(
                w ->
                    w.panelId() == panel
                        && w.moduleId() == module
                        && w.beginTime() > 0
                        && w.endTime() > w.beginTime()
                        && w.beginTime() <= now.getEpochSecond()
                        && now.getEpochSecond() < w.endTime())
            .toList();
    return matches.size() == 1 ? matches.getFirst() : null;
  }

  private static boolean equal(Object expected, Object actual) {
    return actual != null
        && ResourceLoader.getGson()
            .toJsonTree(expected)
            .equals(ResourceLoader.getGson().toJsonTree(actual));
  }

  private static JsonElement canonical(JsonElement value) {
    if (value.isJsonObject()) {
      var next = new JsonObject();
      new TreeSet<>(value.getAsJsonObject().keySet())
          .forEach(k -> next.add(k, canonical(value.getAsJsonObject().get(k))));
      return next;
    }
    if (value.isJsonArray()) {
      var next = new JsonArray();
      value.getAsJsonArray().forEach(e -> next.add(canonical(e)));
      return next;
    }
    return value;
  }

  private static String hash(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }
}
