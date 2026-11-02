package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import java.nio.file.*;
import java.util.*;

/**
 * Source-defined manual Dice and music offerings, each with its own entry/unlock contract.
 */
public record OfferingCatalog(int id, int item, List<Integer> costs, List<Integer> rewards) {
  public static OfferingCatalog read() {
    return read(12);
  }

  public static OfferingCatalog read(int id) {
    if (id != 5 && id != 12) return null;
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir(), "ExcelOutput");
      var types =
          rows(root, "OfferingTypeConfig").stream().filter(r -> number(r, "ID") == id).toList();
      if (types.size() != 1) return null;
      var type = types.getFirst();
      int item = number(type, "ItemID"), max = number(type, "MaxLevel");
      if (item != (id == 5 ? 270003 : 264999)
          || max > 100
          || (id == 5 ? max != 16 || !type.has("UnlockID") || number(type, "UnlockID") != 105301
              : type.has("UnlockID") && number(type, "UnlockID") != 0)
          || type.has("IsAutoOffer") && type.get("IsAutoOffer").getAsBoolean()) return null;
      var levels = new TreeMap<Integer, JsonObject>();
      for (var row : rows(root, "OfferingLevelConfig")) {
        if (number(row, "TypeID") != id) continue;
        int level = number(row, "Level");
        if (level < 1 || level > max || levels.putIfAbsent(level, row) != null) return null;
      }
      if (max < 1 || levels.size() != max) return null;
      var costs = new ArrayList<Integer>();
      var rewards = new ArrayList<Integer>();
      long total = 0;
      for (int level = 1; level <= max; level++) {
        int cost = number(levels.get(level), "ItemCost"),
            reward = number(levels.get(level), "RewardID");
        if (cost < 1 || reward < 1 || (total += cost) > Integer.MAX_VALUE) return null;
        if (id == 5 && (cost != 3 || reward != 260000 + level)) return null;
        costs.add(cost);
        rewards.add(reward);
      }
      return new OfferingCatalog(id, item, List.copyOf(costs), List.copyOf(rewards));
    } catch (java.io.IOException | RuntimeException invalid) {
      return null;
    }
  }

  private static List<JsonObject> rows(Path root, String name) throws java.io.IOException {
    var json = JsonParser.parseString(Files.readString(root.resolve(name + ".json")));
    var values =
        json.isJsonArray()
            ? json.getAsJsonArray().asList()
            : json.getAsJsonObject().asMap().values();
    return values.stream().map(JsonElement::getAsJsonObject).toList();
  }

  private static int number(JsonObject row, String key) {
    var value = row.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsString().matches("0|[1-9][0-9]*"))
      throw new IllegalArgumentException("Invalid offering resource integer");
    return Integer.parseInt(value.getAsString());
  }
}
