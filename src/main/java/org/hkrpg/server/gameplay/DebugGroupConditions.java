package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.scene.Scene;
import java.util.Set;

/** Honor authored debug gates without taking over unrelated saved-value gameplay conditions. */
public final class DebugGroupConditions {
  private static final String FLAG = "Utility_IsDebug";

  public static boolean tracks(GroupInfo group, Scene scene) {
    if (group == null || scene == null
        || !Set.of(PlaneType.Town, PlaneType.Maze, PlaneType.Train).contains(scene.getPlaneType()))
      return false;
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
