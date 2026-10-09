package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.GroupInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import java.util.*;

/** Authored atmosphere clauses are retained verbatim so unknown fields cannot disappear. */
public final class AtmosphereGroups {
  private static final String PREFIX = "RPG.GameCore.";

  private static Integer integer(JsonObject row, String name) {
    if (!row.has(name)
        || !row.get(name).isJsonPrimitive()
        || !row.getAsJsonPrimitive(name).isNumber()) return null;
    try {
      return row.get(name).getAsBigDecimal().intValueExact();
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  private static String text(JsonObject row, String name) {
    return row.has(name)
            && row.get(name).isJsonPrimitive()
            && row.getAsJsonPrimitive(name).isString()
        ? row.get(name).getAsString()
        : "";
  }

  private static Integer nativeShort(JsonObject row, String name) {
    Integer value = integer(row, name);
    return value == null || value < Short.MIN_VALUE || value > Short.MAX_VALUE ? null : value;
  }

  private static boolean only(JsonObject row, String... names) {
    return Set.of(names).containsAll(row.keySet());
  }

  private static Boolean mission(Player player, JsonObject row) {
    String type = text(row, "$type");
    boolean main = (PREFIX + "LevelGroupAtmosphereMainMissionCompleteCondition").equals(type);
    if (!main && !(PREFIX + "LevelGroupSubMissionCompleteCondition").equals(type)) return null;
    String name = main ? "MainMissionID" : "SubMissionID";
    Integer id = integer(row, name);
    if (!only(row, "$type", name) || id == null || id <= 0) return null;
    var states =
        main ? player.getGameplay().getMainMissions() : player.getGameplay().getSubMissions();
    var cancelled =
        main
            ? player.getGameplay().getCancelledMissions()
            : player.getGameplay().getCancelledSubs();
    var state = states.get(id);
    if (state == null
        || state.getQuestId() != id
        || state.getPhase() == null
        || state.getPhase() == QuestState.Phase.DOING
        || state.getProgress() <= 0
        || state.getFinishTime() <= 0
        || cancelled.contains(id)) return false;
    if (!main) {
      var config = GameplayDefinitions.subs.get(id);
      if (config != null
          && (config.getId() != id
              || player.getGameplay().getCancelledMissions().contains(config.getMainMissionID())))
        return false;
    }
    return true;
  }

  public static boolean allowed(Player player, GroupInfo group) {
    return allowed(player, group, player.getScene());
  }

  private static Boolean missionValue(Player player, JsonObject row) {
    if (!(PREFIX + "LevelGroupMissionSavedValueCondition").equals(text(row, "$type"))) return null;
    Integer id = integer(row, "MainMissionID"), expected = nativeShort(row, "Value");
    if (!only(row, "$type", "MainMissionID", "MissionSavedValue", "Value")
        || id == null
        || id <= 0
        || expected == null
        || !row.has("MissionSavedValue")
        || !row.get("MissionSavedValue").isJsonObject()) return null;
    var value = row.getAsJsonObject("MissionSavedValue");
    Integer index = integer(value, "Index");
    String name = text(value, "Name");
    if (!only(value, "Index", "Name", "Type", "ValidValueParamList", "isRange")
        || index == null
        || index < 0
        || name.isBlank()
        || !"Former".equals(text(value, "Type"))
        || value.has("isRange")
            && (!value.get("isRange").isJsonPrimitive()
                || !value.getAsJsonPrimitive("isRange").isBoolean()
                || value.get("isRange").getAsBoolean())
        || !value.has("ValidValueParamList")
        || !value.get("ValidValueParamList").isJsonArray()) return null;
    var options = new ArrayList<Integer>();
    for (var raw : value.getAsJsonArray("ValidValueParamList")) {
      var wrapper = new JsonObject();
      wrapper.add("value", raw);
      Integer option = integer(wrapper, "value");
      if (option == null || options.contains(option)) return null;
      options.add(option);
    }
    if (options.isEmpty() || !options.contains(expected)) return null;
    var mission = GameData.getMissionInfos().get(id);
    if (mission == null
        || mission.getMainMissionID() != id
        || mission.getMissionCustomValueList() == null) return null;
    var declarations =
        Arrays.stream(mission.getMissionCustomValueList())
            .filter(
                c -> c != null && (Objects.equals(c.getIndex(), index) || name.equals(c.getName())))
            .toList();
    if (declarations.size() != 1) return null;
    var declaration = declarations.getFirst();
    if (!Objects.equals(declaration.getIndex(), index)
        || !name.equals(declaration.getName())
        || !"Former".equals(declaration.getType())
        || declaration.isRange()
        || declaration.getValidValueParamList() == null
        || !Arrays.asList(declaration.getValidValueParamList()).equals(options)) return null;
    var owner = player.getGameplay().getMainMissions().get(id);
    if (owner == null
        || owner.getQuestId() != id
        || owner.getPhase() == null
        || player.getGameplay().getCancelledMissions().contains(id)) return false;
    Integer actual = player.getGameplay().getStoryValues().get("mission:" + id + ":" + name);
    return actual == null || !options.contains(actual) ? null : actual.equals(expected);
  }

  private static Boolean floorValue(Player player, Scene scene, JsonObject row) {
    if (!(PREFIX + "LevelGroupAtmosphereSavedValueCondition").equals(text(row, "$type")))
      return null;
    if (!only(row, "$type", "SavedValueName", "Operation", "Value")
        || scene == null
        || scene.getPlayer() != player
        || scene.getFloorInfo() == null) return null;
    String name = text(row, "SavedValueName");
    if (name.isBlank()) return null;
    String operation = row.has("Operation") ? text(row, "Operation") : "Equal";
    Integer expected = row.has("Value") ? nativeShort(row, "Value") : Integer.valueOf(0);
    if (expected == null) return null;
    String key = "floor:" + scene.getFloorId() + ":" + name;
    var stored = player.getGameplay().getStoryValues();
    Integer actual = stored.get(key);
    if (actual == null) {
      if (stored.containsKey(key)) return null;
      var defaults =
          scene.getFloorInfo().getExtraDatas(scene.getDimensionId()).stream()
              .filter(value -> value != null && name.equals(value.getName()))
              .toList();
      if (defaults.size() != 1) return null;
      actual = defaults.getFirst().getDefaultValue();
    }
    return switch (operation) {
      case "Equal" -> actual.intValue() == expected.intValue();
      case "NotEqual" -> actual.intValue() != expected.intValue();
      case "Greater" -> actual > expected;
      case "GreaterEqual" -> actual >= expected;
      case "Less" -> actual < expected;
      case "LessEqual" -> actual <= expected;
      default -> null;
    };
  }

  public static boolean allowed(Player player, GroupInfo group, Scene scene) {
    var raw = group.getAtmosphereCondition();
    if (raw == null) return true;
    if (!raw.isJsonObject()) return false;
    var set = raw.getAsJsonObject();
    if (!only(set, "Conditions", "Operation")) return false;
    // The local 4.6 field is LogicOperationType: And=1, matching the constructor default.
    String operation = set.has("Operation") ? text(set, "Operation") : "And";
    if (!operation.isEmpty() && !Set.of("And", "Or").contains(operation)
        || set.has("Operation") && operation.isEmpty()) return false;
    if (!set.has("Conditions")) return set.isEmpty();
    if (!set.get("Conditions").isJsonArray()) return false;
    var clauses = set.getAsJsonArray("Conditions");
    if (clauses.isEmpty()) return operation.isEmpty() || operation.equals("And");
    boolean all = true, any = false;
    for (var rawClause : clauses) {
      if (!rawClause.isJsonObject()) return false;
      Boolean result = mission(player, rawClause.getAsJsonObject());
      if (result == null) result = floorValue(player, scene, rawClause.getAsJsonObject());
      if (result == null) result = missionValue(player, rawClause.getAsJsonObject());
      if (result == null) return false;
      all &= result;
      any |= result;
    }
    return operation.equals("Or") ? any : all;
  }

  private AtmosphereGroups() {}
}
