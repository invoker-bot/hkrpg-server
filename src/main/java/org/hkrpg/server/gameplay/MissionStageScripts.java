package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.config.MissionInfo;
import java.util.Set;

/** Narrow source declaration: two client string sequences ending in one literal event battle. */
final class MissionStageScripts {
  static String signature(MissionInfo.SubMissionInfo sub) {
    if (sub.getTask() == null) return "";
    try {
      var text = sub.getTask().getSourceDefinition();
      if (text == null || text.isEmpty() || text.length() > 16384) return null;
      var root = JsonParser.parseString(text).getAsJsonObject();
      if (!keys(root, "Type", "OnInitSequece", "OnStartSequece")
          || !string(root.get("Type"), "Mission")
          || !root.getAsJsonArray("OnInitSequece").isEmpty()) return null;
      var sequences = root.getAsJsonArray("OnStartSequece");
      if (sequences.size() != 2 || sub.getProgress() != 1 || sub.getParamInt1() <= 0) return null;
      var first = tasks(sequences.get(0));
      var second = tasks(sequences.get(1));
      if (first == null
          || second == null
          || first.size() != 2
          || second.size() != 2
          || !custom(first.get(0), "WaitCustomString", "Talk_" + sub.getId(), false)
          || !custom(first.get(1), "TriggerCustomString", "Battle_" + sub.getId(), false)
          || !custom(second.get(0), "WaitCustomString", "Battle_" + sub.getId(), true)) return null;
      var battle = second.get(1).getAsJsonObject();
      if (!keys(battle, "$type", "EventID", "GroupID", "BattleAreaID")
          || !string(battle.get("$type"), "RPG.GameCore.TriggerBattle")
          || literal(battle.get("EventID")) != sub.getParamInt1()
          || literal(battle.get("GroupID")) <= 0
          || literal(battle.get("BattleAreaID")) <= 0
          || !sub.getTask().declaresBattle(sub.getParamInt1())) return null;
      var stage = SceneEventBattles.resolve(sub.getParamInt1(), 0);
      return stage != null && stage.getId() / 10 == sub.getParamInt1() ? text : null;
    } catch (RuntimeException unsupported) {
      return null;
    }
  }

  private static boolean keys(JsonObject object, String... names) {
    return object.keySet().equals(Set.of(names));
  }

  private static boolean string(JsonElement value, String expected) {
    return value != null
        && value.isJsonPrimitive()
        && value.getAsJsonPrimitive().isString()
        && expected.equals(value.getAsString());
  }

  private static JsonArray tasks(JsonElement value) {
    var row = value.getAsJsonObject();
    return keys(row, "TaskList") ? row.getAsJsonArray("TaskList") : null;
  }

  private static boolean custom(JsonElement value, String type, String text, boolean immediate) {
    var row = value.getAsJsonObject();
    if (!(immediate
            ? keys(row, "$type", "CustomString", "GoNextImmediately")
            : keys(row, "$type", "CustomString"))
        || !string(row.get("$type"), "RPG.GameCore." + type)) return false;
    var token = row.getAsJsonObject("CustomString");
    if (!keys(token, "Value") || !string(token.get("Value"), text)) return false;
    if (!immediate) return true;
    var next = row.get("GoNextImmediately");
    return next.isJsonPrimitive() && next.getAsJsonPrimitive().isBoolean() && next.getAsBoolean();
  }

  private static int literal(JsonElement value) {
    var row = value.getAsJsonObject();
    if (!keys(row, "IsDynamic", "FixedValue")) return 0;
    var dynamic = row.get("IsDynamic");
    if (!dynamic.isJsonPrimitive()
        || !dynamic.getAsJsonPrimitive().isBoolean()
        || dynamic.getAsBoolean()) return 0;
    var fixed = row.getAsJsonObject("FixedValue");
    if (!keys(fixed, "Value")
        || !fixed.get("Value").isJsonPrimitive()
        || !fixed.get("Value").getAsJsonPrimitive().isNumber()) return 0;
    return fixed.get("Value").getAsBigDecimal().intValueExact();
  }

  private MissionStageScripts() {}
}
