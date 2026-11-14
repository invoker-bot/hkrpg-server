package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Reads the original personal-room gate, slot tags, display models and story-photo gates. */
final class PlayerRoomCatalog {
  static final List<Integer> STATIC_IDS = List.of(16001, 16002, 16003);
  final Map<Integer, Set<Integer>> slots = new HashMap<>();
  final Map<Integer, Set<Integer>> items = new HashMap<>();
  final Map<Integer, Integer> storyMissions = new HashMap<>();

  private static JsonArray table(Path root, String name) throws Exception {
    return JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/" + name + ".json"),
        StandardCharsets.UTF_8)).getAsJsonArray();
  }

  private static JsonObject row(JsonArray rows, String key, int id) {
    JsonObject found = null;
    for (var raw : rows) {
      var candidate = raw.getAsJsonObject();
      if (!candidate.has(key) || candidate.get(key).getAsInt() != id) continue;
      if (found != null) throw new IllegalArgumentException("duplicate row");
      found = candidate;
    }
    if (found == null) throw new IllegalArgumentException("missing row");
    return found;
  }

  private static Set<Integer> ints(JsonArray values) {
    var result = new HashSet<Integer>();
    for (var value : values) if (value.getAsInt() <= 0 || !result.add(value.getAsInt()))
      throw new IllegalArgumentException("invalid list");
    return Set.copyOf(result);
  }

  static PlayerRoomCatalog load() throws Exception {
    var root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
    if (Files.isDirectory(root.resolve("4.6"))) root = root.resolve("4.6");
    var constants = table(root, "PlayerRoomConstValueCommon");
    boolean displayArea = false;
    for (var raw : constants) {
      var c = raw.getAsJsonObject();
      if (c.get("ConstValueName").getAsString().equals("Player_Room_Card_Display_Area_ID"))
        displayArea = c.getAsJsonObject("Value").get("IntValue").getAsInt() == 16;
    }
    var unlock = row(table(root, "FuncUnlockData"), "UnlockID", 9966).getAsJsonArray("Conditions");
    if (!displayArea || unlock.size() != 1
        || !unlock.get(0).getAsJsonObject().get("Type").getAsString().equals("PlayerLevel")
        || !unlock.get(0).getAsJsonObject().get("Param").getAsString().equals("21"))
      throw new IllegalArgumentException("personal-room gate changed");
    var area = row(table(root, "TrainPartyAreaConfig"), "ID", 16);
    if (area.get("RequireAreaID").getAsInt() != 12
        || area.has("IsShowInActivity") && area.get("IsShowInActivity").getAsBoolean())
      throw new IllegalArgumentException("personal-room area changed");

    var expected = new HashSet<Integer>();
    var statics = table(root, "TrainPartyStaticConfig");
    for (int id : STATIC_IDS) {
      var staticRow = row(statics, "ID", id);
      if (staticRow.get("AreaID").getAsInt() != 16) throw new IllegalArgumentException("area");
      for (int slot : ints(staticRow.getAsJsonArray("SlotList")))
        if (!expected.add(slot)) throw new IllegalArgumentException("duplicate slot");
    }
    var original = new HashSet<Integer>();
    for (int id = 1600101; id <= 1600114; id++) original.add(id);
    for (int id = 1600301; id <= 1600306; id++) original.add(id);
    original.add(1600201);
    if (!expected.equals(original)) throw new IllegalArgumentException("display slots changed");

    var result = new PlayerRoomCatalog();
    var slots = table(root, "PlayerRoomSlotConfig");
    for (int id : expected) {
      var r = row(slots, "ID", id);
      var tags = ints(r.getAsJsonArray("TagList"));
      if (tags.isEmpty() || !r.has("SubArea")) throw new IllegalArgumentException("slot tags");
      result.slots.put(id, tags);
    }
    for (var raw : table(root, "PlayerRoomDynamicConfig")) {
      var r = raw.getAsJsonObject();
      int id = r.get("ID").getAsInt();
      var tags = ints(r.getAsJsonArray("Taglist"));
      if (id <= 0 || tags.isEmpty() || result.items.putIfAbsent(id, tags) != null)
        throw new IllegalArgumentException("display item");
    }
    for (var raw : table(root, "PerformanceRecallData")) {
      var r = raw.getAsJsonObject();
      if (!r.has("ShowInPlayerRoom") || !r.get("ShowInPlayerRoom").getAsBoolean()) continue;
      var conditions = r.getAsJsonArray("UnlockCondition");
      if (conditions == null || conditions.size() != 1) continue;
      var condition = conditions.get(0).getAsJsonObject();
      if (!condition.get("Type").getAsString().equals("FinishMainMission")) continue;
      int id = r.get("ID").getAsInt(), mission = Integer.parseInt(condition.get("Param").getAsString());
      if (id <= 0 || mission <= 0 || result.storyMissions.putIfAbsent(id, mission) != null)
        throw new IllegalArgumentException("story photo");
    }
    return result;
  }

  boolean itemFits(int slot, int item) {
    var allowed = slots.get(slot);
    var tags = items.get(item);
    return allowed != null && tags != null && !Collections.disjoint(allowed, tags)
        && (slot == 1600201 || slot >= 1600101 && slot <= 1600105);
  }
  boolean pictureFits(int slot, int type) {
    var tags = slots.get(slot);
    return tags != null && (type == 1 && tags.contains(12)
        || type == 3 && tags.contains(10) || type == 4 && tags.contains(11));
  }
}
