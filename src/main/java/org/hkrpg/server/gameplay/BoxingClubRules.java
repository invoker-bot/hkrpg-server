package org.hkrpg.server.gameplay;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Full original row identity plus the live loaded definitions; no historical automatic opening. */
final class BoxingClubRules {
  private static BoxingClubRules cached;
  final BoxingClubCatalog catalog;
  private final Path root;
  private final Map<String, String> files = new TreeMap<>();
  private final Map<String, JsonObject> rows = new HashMap<>();
  private final Map<Integer, StageExcel> stages = new HashMap<>();
  private final Map<Integer, MazeBuffExcel> buffs = new HashMap<>();
  private final Map<Integer, RewardExcel> rewards = new HashMap<>();
  private final ActivityPanelExcel panel;

  private BoxingClubRules(Path root, BoxingClubCatalog catalog) throws Exception {
    this.root = root;
    this.catalog = catalog;
    for (String table :
        List.of(
            "PlaneEvent",
            "StageConfig",
            "MazeBuff",
            "RewardData",
            "ActivityPanel",
            "ActivityConfig")) {
      Path path = root.resolve("ExcelOutput/" + table + ".json");
      if (!Files.isRegularFile(path) || Files.size(path) > 64 * 1024 * 1024)
        throw new IllegalArgumentException();
      files.put(table, fileHash(path));
      try (var reader = new JsonReader(Files.newBufferedReader(path))) {
        reader.beginArray();
        while (reader.hasNext()) {
          var row = JsonParser.parseReader(reader).getAsJsonObject();
          String key = key(table, row);
          String expected = BoxingClubSourceHashes.ROWS.get(key);
          if (expected != null
              && (!expected.equals(hash(canonical(row).toString()))
                  || rows.putIfAbsent(key, row) != null))
            throw new IllegalArgumentException("Source row mismatch: " + key);
        }
        reader.endArray();
      }
    }
    if (!rows.keySet().equals(BoxingClubSourceHashes.ROWS.keySet()))
      throw new IllegalArgumentException();
    var gson = ResourceLoader.getGson();
    for (var entry : rows.entrySet()) {
      var row = entry.getValue();
      if (entry.getKey().startsWith("StageConfig:")) {
        var stage = gson.fromJson(row, StageExcel.class);
        stage.onLoad();
        stages.put(stage.getId(), stage);
      } else if (entry.getKey().startsWith("MazeBuff:")) {
        var buff = gson.fromJson(row, MazeBuffExcel.class);
        buffs.put(buff.getBuffId(), buff);
      } else if (entry.getKey().startsWith("RewardData:")) {
        var reward = gson.fromJson(row, RewardExcel.class);
        reward.onLoad();
        rewards.put(reward.getId(), reward);
      }
    }
    panel = gson.fromJson(rows.get("ActivityPanel:50000"), ActivityPanelExcel.class);
  }

  static synchronized BoxingClubRules load() {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var catalog = BoxingClubCatalog.load(root);
      if (catalog == null) return null;
      if (cached != null && cached.root.equals(root) && cached.sourcesValid()) return cached;
      var value = new BoxingClubRules(root, catalog);
      if (!value.sourcesValid()) return null;
      cached = value;
      return value;
    } catch (Exception invalid) {
      return null;
    }
  }

  boolean sourcesValid() {
    try {
      if (BoxingClubCatalog.load(root) == null) return false;
      for (var e : files.entrySet())
        if (!e.getValue().equals(fileHash(root.resolve("ExcelOutput/" + e.getKey() + ".json"))))
          return false;
      return true;
    } catch (Exception invalid) {
      return false;
    }
  }

  boolean available(
      emu.lunarcore.game.player.Player player,
      BoxingClubCatalog.Challenge challenge,
      Instant time) {
    if (challenge == null
        || time == null
        || !equal(panel, GameData.getActivityPanelExcelMap().get(50000))
        || !ActivityAccess.unlocked(player, 50000)) return false;
    var modules = rows.get("ActivityConfig:50000").getAsJsonArray("ResidentModuleList");
    if (!modules.contains(new JsonPrimitive(challenge.module()))) return false;
    long now = time.getEpochSecond();
    return GameplayDefinitions.windows.stream()
            .filter(
                w ->
                    w.panelId() == 50000
                        && w.moduleId() == challenge.module()
                        && w.beginTime() > 0
                        && w.endTime() > w.beginTime()
                        && w.beginTime() <= now
                        && now < w.endTime())
            .count()
        == 1;
  }

  StageExcel stage(int event, int world) {
    var row = rows.get("PlaneEvent:" + event + "/" + world);
    var liveEvent = GameData.getPlaneEventExcelMap().get(event, world);
    if (row == null
        || !equal(ResourceLoader.getGson().fromJson(row, PlaneEventExcel.class), liveEvent))
      return null;
    int id = row.get("StageID").getAsInt();
    var expected = stages.get(id);
    var live = SceneEventBattles.resolve(event, world);
    if (!equal(expected, live) || !expected.getMonsterWaves().equals(live.getMonsterWaves()))
      return null;
    return live;
  }

  boolean buffsValid(List<Integer> ids) {
    return ids.stream()
        .allMatch(id -> equal(buffs.get(id), GameData.getMazeBuffExcelMap().get(id, 1)));
  }

  RewardExcel reward(int id) {
    var expected = rewards.get(id);
    var live = GameData.getRewardExcelMap().get(id);
    if (!equal(expected, live)
        || expected.getRewards() == null
        || live.getRewards() == null
        || !items(expected).equals(items(live))) return null;
    return live;
  }

  static Map<Integer, Integer> items(RewardExcel reward) {
    var result = new TreeMap<Integer, Integer>();
    for (var item : reward.getRewards()) result.put(item.getIntKey(), item.getIntValue());
    return Map.copyOf(result);
  }

  static boolean equal(Object expected, Object actual) {
    return expected != null
        && actual != null
        && ResourceLoader.getGson()
            .toJsonTree(expected)
            .equals(ResourceLoader.getGson().toJsonTree(actual));
  }

  private static String key(String table, JsonObject row) {
    String field =
        switch (table) {
          case "PlaneEvent" -> "EventID";
          case "StageConfig" -> "StageID";
          case "MazeBuff" -> "ID";
          case "RewardData" -> "RewardID";
          case "ActivityPanel" -> "PanelID";
          case "ActivityConfig" -> "ActivityID";
          default -> throw new IllegalArgumentException();
        };
    String key = table + ":" + row.get(field).getAsString();
    if (table.equals("PlaneEvent"))
      key += "/" + (row.has("WorldLevel") ? row.get("WorldLevel").getAsString() : "0");
    if (table.equals("MazeBuff")) key += "/" + (row.has("Lv") ? row.get("Lv").getAsString() : "1");
    return key;
  }

  private static JsonElement canonical(JsonElement value) {
    if (value.isJsonObject()) {
      var result = new JsonObject();
      new TreeSet<>(value.getAsJsonObject().keySet())
          .forEach(k -> result.add(k, canonical(value.getAsJsonObject().get(k))));
      return result;
    }
    if (value.isJsonArray()) {
      var result = new JsonArray();
      value.getAsJsonArray().forEach(x -> result.add(canonical(x)));
      return result;
    }
    return value;
  }

  private static String fileHash(Path path) throws Exception {
    return hash(Files.readString(path).replace("\r\n", "\n"));
  }

  private static String hash(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
}
