package org.hkrpg.server.gameplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.data.excel.InteractExcel;
import emu.lunarcore.data.excel.PropExcel;
import emu.lunarcore.game.scene.Scene;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/** Verify the authored JSON, then compare its normalized definitions to the live scene. */
final class PuzzleRewardSources {
  static JsonObject catalog(String name) {
    try (var stream =
            Objects.requireNonNull(
                PuzzleRewardSources.class.getResourceAsStream("/gameplay/" + name));
        var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception invalid) {
      throw new ExceptionInInitializerError(invalid);
    }
  }

  static JsonElement read(JsonObject catalog, String path) {
    try {
      var pins = catalog.getAsJsonObject("files");
      if (pins == null || !pins.has(path)) return null;
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var raw = WorldMechanismSources.read(root, path);
      return raw != null
              && pins.get(path).getAsString().equals(TimelineActivityRules.fingerprint(raw))
          ? raw
          : null;
    } catch (Exception invalid) {
      return null;
    }
  }

  static boolean row(JsonObject catalog, String table, int id) {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
      var data = WorldMechanismSources.read(root, "ExcelOutput/" + table + ".json");
      if (data == null || !data.isJsonArray()) return false;
      String idKey = table.equals("InteractConfig") ? "InteractID" : "ID";
      JsonObject found = null;
      for (var element : data.getAsJsonArray()) {
        var candidate = element.getAsJsonObject();
        if (candidate.get(idKey).getAsInt() != id) continue;
        if (found != null) return false;
        found = candidate;
      }
      var pins = catalog.getAsJsonObject("rows");
      String key = table + ":" + id;
      if (found == null
          || pins == null
          || !pins.has(key)
          || !pins.get(key).getAsString().equals(TimelineActivityRules.fingerprint(found)))
        return false;
      var gson = ResourceLoader.getGson();
      Object expected;
      Object actual;
      switch (table) {
        case "InteractConfig" -> {
          var row = gson.fromJson(found, InteractExcel.class);
          row.onLoad();
          expected = row;
          actual = GameData.getInteractExcelMap().get(id);
        }
        case "MazeProp" -> {
          found = found.deepCopy();
          if (found.get("PropName").isJsonObject())
            found.addProperty(
                "PropName", found.getAsJsonObject("PropName").get("Hash").getAsLong());
          var row = gson.fromJson(found, PropExcel.class);
          row.onLoad();
          expected = row;
          actual = GameData.getPropExcelMap().get(id);
        }
        default -> {
          return false;
        }
      }
      return actual != null && gson.toJsonTree(expected).equals(gson.toJsonTree(actual));
    } catch (Exception invalid) {
      return false;
    }
  }

  static GroupInfo group(JsonObject catalog, String path, int id, Scene scene) {
    var raw = read(catalog, path);
    if (raw == null || scene == null) return null;
    try {
      var expected = ResourceLoader.getGson().fromJson(raw, GroupInfo.class);
      expected.setId(id);
      var floor = new FloorInfo(scene.getFloorId());
      floor.getGroups().put(id, expected);
      floor.onLoad();
      var actual = scene.getResourceGroups().get(id);
      return actual != null
              && ResourceLoader.getGson()
                  .toJsonTree(expected)
                  .equals(ResourceLoader.getGson().toJsonTree(actual))
          ? actual
          : null;
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  static boolean floor(JsonObject catalog, String path, Scene scene) {
    var raw = read(catalog, path);
    if (raw == null || scene == null || scene.getFloorInfo() == null) return false;
    try {
      var expected = ResourceLoader.getGson().fromJson(raw, FloorInfo.class);
      var gson = ResourceLoader.getGson();
      return gson.toJsonTree(expected.getExtraDatas(scene.getDimensionId()))
          .equals(gson.toJsonTree(scene.getFloorInfo().getExtraDatas(scene.getDimensionId())));
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private PuzzleRewardSources() {}
}
