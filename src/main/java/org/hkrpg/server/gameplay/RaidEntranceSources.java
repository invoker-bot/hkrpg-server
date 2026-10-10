package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Omitted Default entrance fields stay distinct from SkipUI and are frozen from the whole row. */
final class RaidEntranceSources {
  private static final Gson GSON = new Gson();

  private record Source(String typed, String original, int difficulty) {}

  private record Cache(Path file, FileTime modified, long size, Map<Long, Source> rows) {}

  private static Cache cache;

  private static int integer(JsonObject row, String name, boolean optional) {
    if (!row.has(name) && optional) return 0;
    var value = row.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Invalid entrance source integer");
    return value.getAsBigDecimal().intValueExact();
  }

  private static boolean defaultType(JsonObject row) {
    if (!row.has("EnterType")) return true;
    var value = row.get("EnterType");
    return value.isJsonPrimitive()
        && value.getAsJsonPrimitive().isString()
        && "Default".equals(value.getAsString());
  }

  private static synchronized Source source(GameplayDefinitions.Raid row) {
    try {
      if (row == null
          || row.EnterType() != null
              && !"Default".equals(row.EnterType())
              && !RaidEntryRecovery.none(row)) return null;
      var path =
          Path.of(LunarCore.getConfig().getResourceDir(), "ExcelOutput", "RaidConfig.json")
              .toRealPath();
      var before = Files.readAttributes(path, BasicFileAttributes.class);
      if (!before.isRegularFile() || before.size() > 16 * 1024 * 1024) return null;
      if (cache == null
          || !cache.file().equals(path)
          || !cache.modified().equals(before.lastModifiedTime())
          || cache.size() != before.size()) {
        var definitions = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
        if (definitions.size() > 10000) return null;
        var rows = new HashMap<Long, Source>();
        var seen = new HashSet<Long>();
        for (var value : definitions) {
          var original = value.getAsJsonObject();
          int id = integer(original, "RaidID", false),
              world = integer(original, "WorldLevel", true);
          if (id <= 0 || world < 0 || world > 99)
            throw new IllegalArgumentException("Invalid entrance source key");
          long key = (long) id * 100 + world;
          if (!seen.add(key)) {
            rows.remove(key);
            continue;
          }
          boolean skip =
              original.has("EnterType")
                  && original.get("EnterType").isJsonPrimitive()
                  && original.getAsJsonPrimitive("EnterType").isString()
                  && "SkipUI".equals(original.get("EnterType").getAsString());
          if (!defaultType(original) && !skip) continue;
          int difficulty = integer(original, "DifficultyAdjustmentType", true);
          if (difficulty < 0 || difficulty > 1) continue;
          var typed = GSON.fromJson(original, GameplayDefinitions.Raid.class);
          if (skip && !RaidEntryRecovery.none(typed)) continue;
          rows.put(key, new Source(GSON.toJson(typed), original.toString(), difficulty));
        }
        var after = Files.readAttributes(path, BasicFileAttributes.class);
        if (!before.lastModifiedTime().equals(after.lastModifiedTime())
            || before.size() != after.size()) return null;
        cache = new Cache(path, after.lastModifiedTime(), after.size(), Map.copyOf(rows));
      }
      var source = cache.rows().get(row.key());
      return source != null && source.typed().equals(GSON.toJson(row)) ? source : null;
    } catch (IOException
        | IllegalArgumentException
        | IllegalStateException
        | ArithmeticException invalid) {
      cache = null;
      return null;
    }
  }

  static boolean supported(GameplayDefinitions.Raid row) {
    if (row == null) return false;
    if ("SkipUI".equals(row.EnterType())) return true;
    return source(row) != null
        && row.MainMissionIDList() != null
        && ("Player".equals(row.TeamType()) && row.MainMissionIDList().size() > 1
            || "TrialOrPlayer".equals(row.TeamType()) && row.MainMissionIDList().size() == 1);
  }

  static boolean permits(Player player, GameplayDefinitions.Raid row) {
    if (row == null) return false;
    if (RaidEntryRecovery.none(row)) return original(row) && player.getWorldLevel() == 0;
    if ("SkipUI".equals(row.EnterType())) return true;
    var source = source(row);
    return source != null && RaidDifficultySettings.permits(player, row);
  }

  static JsonElement fingerprint(GameplayDefinitions.Raid row) {
    var source = source(row);
    return source == null ? JsonNull.INSTANCE : JsonParser.parseString(source.original());
  }

  static boolean original(GameplayDefinitions.Raid row) {
    return source(row) != null;
  }

  static boolean adjustable(GameplayDefinitions.Raid row) {
    var source = source(row);
    return source != null
        && source.difficulty() == 1
        && (row.EnterType() == null || "Default".equals(row.EnterType()));
  }

  private RaidEntranceSources() {}
}
