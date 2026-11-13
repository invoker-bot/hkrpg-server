package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.scene.Scene;
import java.util.Map;
import java.util.Set;
import org.hkrpg.server.GameDiagnostics;

/** Honor debug gates and fingerprint-verified dormant tests without changing unrelated groups. */
public final class DebugGroupConditions {
  private static final String FLAG = "Utility_IsDebug";
  private record Source(int floor, int group, String fingerprint) {}
  private static final Map<String, Source> SOURCES = Map.of(
      "LevelArea_P10002_F10002001_G11", new Source(10002001, 11,
          "79514efae3fc4e79bc2361b8189c6ada84b3368190d903f8ec784f730abebdb0"),
      "LevelArea_P10101_F10101003_G4", new Source(10101003, 4,
          "e84e96d3080e90844bf940b3c5fb094a684f2444bf49e734f8cc3ac690f4d2b8"),
      "LevelArea_P10101_F10101004_G15", new Source(10101004, 15,
          "a973b906397ff35d3a553d1a39da3c3bbc4464ef82cd8bf1a0fc5ed429ea10d2"),
      "LevelArea_P10102_F10102002_G16", new Source(10102002, 16,
          "89c20fe38a040d7904e46fe78f1bb8f067812830ec8f6353c0bad3e11e6de588"),
      "LevelArea_P10202_F10202004_G4", new Source(10202004, 4,
          "ba812a06e733eb6500e56dd21526ab6a80dc3304433e147ad5a01c53bb3b1b1a"),
      "LevelArea_P80112_F80112001_G11", new Source(80112001, 11,
          "015304ae6450913c5310d9c90fa929dbafe9604d010b3321764ff53ad2a0342f"));

  static void markSource(GroupInfo group, JsonObject raw) {
    var source = SOURCES.get(text(raw, "AreaAnchorName"));
    if (source != null && source.fingerprint().equals(TimelineActivityRules.fingerprint(raw)))
      group.setDormantDebugSourceFloor(source.floor());
  }

  private static boolean dormantTest(GroupInfo group, Scene scene) {
    int floor = group.getDormantDebugSourceFloor();
    if (floor <= 0 || scene.getFloorId() != floor || scene.getPlaneId() != floor / 1000
        || scene.getFloorInfo() == null || scene.getResourceGroups().get(group.getId()) != group)
      return false;
    var source = SOURCES.get("LevelArea_P" + scene.getPlaneId() + "_F" + floor + "_G" + group.getId());
    return source != null && source.floor() == floor && source.group() == group.getId();
  }

  public static boolean tracks(GroupInfo group, Scene scene) {
    if (group == null || scene == null
        || !Set.of(PlaneType.Town, PlaneType.Maze, PlaneType.Train).contains(scene.getPlaneType()))
      return false;
    if (dormantTest(group, scene)) return true;
    var set = group.getSavedValueCondition();
    if (set == null || !set.isJsonObject()) return false;
    var clauses = set.getAsJsonObject().get("Conditions");
    if (clauses == null || !clauses.isJsonArray()) return false;
    for (var clause : clauses.getAsJsonArray())
      if (clause.isJsonObject() && FLAG.equals(text(clause.getAsJsonObject(), "SavedValueName")))
        return true;
    return false;
  }

  public static boolean allowed(GroupInfo group, Scene scene) {
    if (!tracks(group, scene)) return true;
    if (dormantTest(group, scene)) {
      GameDiagnostics.dormantDebugGroup(scene, group.getId());
      return false;
    }
    if (scene.getFloorInfo() == null) return false;
    try {
      var set = group.getSavedValueCondition().getAsJsonObject();
      if (!Set.of("Conditions", "Operation").containsAll(set.keySet())) return false;
      String operation = set.has("Operation") ? text(set, "Operation") : "And";
      if (!Set.of("And", "Or").contains(operation)) return false;
      boolean result = operation.equals("And");
      for (var clause : set.getAsJsonArray("Conditions")) {
        if (!clause.isJsonObject()) return false;
        var row = clause.getAsJsonObject();
        if (!Set.of("SavedValueName", "Value", "Operation").containsAll(row.keySet())
            || row.has("Operation") && !"Equal".equals(text(row, "Operation"))) return false;
        String name = text(row, "SavedValueName");
        var declarations = scene.getFloorInfo().getExtraDatas(scene.getDimensionId()).stream()
            .filter(v -> name.equals(v.getName())).toList();
        if (declarations.size() != 1) return false;
        var declaration = declarations.getFirst();
        int actual = scene.getPlayer().getGameplay().getStoryValues().getOrDefault(
            "floor:" + scene.getFloorId() + ":" + name, declaration.getDefaultValue());
        if (row.has("Value") && !row.getAsJsonPrimitive("Value").isNumber()) return false;
        int expected = row.has("Value") ? row.getAsJsonPrimitive("Value")
            .getAsBigDecimal().intValueExact() : 0;
        if (FLAG.equals(name) && (actual < 0 || actual > 1 || expected < 0 || expected > 1))
          return false;
        boolean match = actual == expected;
        result = operation.equals("And") ? result && match : result || match;
      }
      return result;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private static String text(JsonObject row, String name) {
    var value = row.get(name);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        ? value.getAsString() : "";
  }

  private DebugGroupConditions() {}
}
