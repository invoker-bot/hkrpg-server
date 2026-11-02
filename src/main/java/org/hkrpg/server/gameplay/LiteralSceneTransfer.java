package org.hkrpg.server.gameplay;

import com.google.gson.*;
import java.util.Set;

/** A complete mission graph containing exactly one literal transfer, in init or start. */
record LiteralSceneTransfer(int entry, int group, int anchor, boolean loop) {
  static LiteralSceneTransfer parse(String text) {
    if (text == null || text.isBlank() || text.length() > 4096) return null;
    try {
      var root = JsonParser.parseString(text).getAsJsonObject();
      if (!keys(root, "Type", "OnInitSequece", "OnStartSequece")
          || !string(root.get("Type"), "Mission")) return null;
      var init = root.getAsJsonArray("OnInitSequece");
      var start = root.getAsJsonArray("OnStartSequece");
      if (init.size() + start.size() != 1) return null;
      var sequence = (init.isEmpty() ? start : init).get(0).getAsJsonObject();
      if (sequence.has("Order")) {
        if (integer(sequence.remove("Order")) != 1) return null;
      }
      boolean loop = false;
      if (sequence.has("IsLoop")) {
        var value = sequence.remove("IsLoop");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) return null;
        loop = value.getAsBoolean();
      }
      if (!keys(sequence, "TaskList")) return null;
      var tasks = sequence.getAsJsonArray("TaskList");
      if (tasks.size() != 1) return null;
      var task = tasks.get(0).getAsJsonObject();
      if (!keys(task, "$type", "EntranceID")
          && !(keys(task, "$type", "EntranceID", "GroupID", "AnchorID")
              && integer(task.get("GroupID")) > 0 && integer(task.get("AnchorID")) > 0)) return null;
      if (!string(task.get("$type"), "RPG.GameCore.EnterMap")) return null;
      int entry = integer(task.get("EntranceID"));
      return entry <= 0 ? null : new LiteralSceneTransfer(entry,
          integer(task.get("GroupID")), integer(task.get("AnchorID")), loop);
    } catch (RuntimeException unsupported) {
      return null;
    }
  }

  private static boolean keys(JsonObject row, String... names) {
    return row.keySet().equals(Set.of(names));
  }

  private static boolean string(JsonElement value, String expected) {
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        && expected.equals(value.getAsString());
  }

  private static int integer(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return 0;
    try { return value.getAsBigDecimal().intValueExact(); }
    catch (ArithmeticException | NumberFormatException invalid) { return 0; }
  }
}
