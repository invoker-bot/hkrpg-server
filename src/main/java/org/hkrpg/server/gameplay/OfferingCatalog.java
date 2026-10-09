package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import java.nio.file.*;
import java.util.*;

/**
 * Source-defined manual Dice offering. Other offerings require their own entry/unlock contracts.
 */
public record OfferingCatalog(int id, int item, List<Integer> costs, List<Integer> rewards) {
  public static OfferingCatalog read() {
    try {
      var root = Path.of(LunarCore.getConfig().getResourceDir(), "ExcelOutput");
      var types =
          rows(root, "OfferingTypeConfig").stream().filter(r -> number(r, "ID") == 12).toList();
      if (types.size() != 1) return null;
      var type = types.getFirst();
      int item = number(type, "ItemID"), max = number(type, "MaxLevel");
      if (item != 264999
          || max > 100
          || type.has("UnlockID") && number(type, "UnlockID") != 0
          || type.has("IsAutoOffer") && type.get("IsAutoOffer").getAsBoolean()) return null;
      var levels = new TreeMap<Integer, JsonObject>();
      for (var row : rows(root, "OfferingLevelConfig")) {
        if (number(row, "TypeID") != 12) continue;
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
        costs.add(cost);
        rewards.add(reward);
      }
      return new OfferingCatalog(12, item, List.copyOf(costs), List.copyOf(rewards));
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
