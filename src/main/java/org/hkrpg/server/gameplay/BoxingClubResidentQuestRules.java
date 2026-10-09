package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Pins resident quests, their actual shared finish identities, rewards and inventory definitions.
 */
final class BoxingClubResidentQuestRules {
  private static final Map<String, String> KEYS =
      Map.of(
          "QuestData",
          "QuestID",
          "FinishWay",
          "ID",
          "RewardData",
          "RewardID",
          "ItemConfig",
          "ID",
          "BoxingClubConstValueClient",
          "ConstValueName");
  private static BoxingClubResidentQuestRules current;
  final Path root;
  final Map<String, String> files;
  final Map<Integer, QuestExcel> quests = new TreeMap<>();
  final Map<Integer, FinishWayExcel> finishes = new TreeMap<>();
  final Map<Integer, RewardExcel> rewards = new TreeMap<>();
  final Map<Integer, ItemExcel> items = new TreeMap<>();

  private BoxingClubResidentQuestRules(
      Path root, Map<String, String> files, Map<String, JsonObject> rows) {
    this.root = root;
    this.files = Map.copyOf(files);
    var gson = ResourceLoader.getGson();
    for (var entry : rows.entrySet()) {
      String table = entry.getKey().split(":", 2)[0];
      var row = entry.getValue();
      switch (table) {
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

  static synchronized BoxingClubResidentQuestRules load() {
    try {
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
          String expected = BoxingClubResidentQuestSources.ROWS.get(key);
          if (expected == null) continue;
          if (rows.put(key, row) != null || !expected.equals(hash(canonical(row).toString())))
            return null;
        }
      }
      if (rows.size() != BoxingClubResidentQuestSources.ROWS.size()) return null;
      var next = new BoxingClubResidentQuestRules(root, files, rows);
      if (!next.loaded()) return null;
      current = next;
      return next;
    } catch (Exception invalid) {
      return null;
    }
  }

  private boolean loaded() {
    return quests.size() == 32
        && finishes.size() == 32
        && rewards.size() == 5
        && items.size() == 3
        && quests.entrySet().stream()
            .allMatch(e -> equal(e.getValue(), GameData.getQuestExcelMap().get(e.getKey())))
        && finishes.entrySet().stream()
            .allMatch(e -> equal(e.getValue(), GameData.getFinishWayExcelMap().get(e.getKey())))
        && rewards.entrySet().stream()
            .allMatch(e -> equal(e.getValue(), GameData.getRewardExcelMap().get(e.getKey())))
        && items.entrySet().stream()
            .allMatch(e -> equal(e.getValue(), GameData.getItemExcelMap().get(e.getKey())));
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
