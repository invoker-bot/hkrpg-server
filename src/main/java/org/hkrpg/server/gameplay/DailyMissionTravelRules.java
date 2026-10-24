package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.enums.PlaneType;
import java.util.*;

/** Stateless original starts and literal client-directed EnterMap objectives for daily graphs. */
final class DailyMissionTravelRules {
  static boolean auto(MissionInfo graph, MissionInfo.SubMissionInfo sub) {
    var params = sub.getTakeParamIntList();
    var strings = sub.getTakeParamStrList();
    if (params == null || strings == null || strings.length != 0) return false;
    if (params.length == 0) return true;
    var starts = graph.getStartSubMissionList();
    return params.length == 1
        && Objects.equals(params[0], graph.getMainMissionID())
        && starts != null
        && starts.length == 1
        && starts[0] == sub.getId();
  }

  static boolean enterFloor(MissionInfo.SubMissionInfo sub) {
    if (!"EnterFloor".equals(sub.getFinishType())
        || !"Equal".equals(sub.getParamType())
        || sub.getProgress() != 1
        || sub.getParamInt3() != 0
        || !"".equals(sub.getParamStr1())
        || sub.getParamIntList() == null || sub.getParamIntList().length != 0
        || sub.getParamItemList() == null || !sub.getParamItemList().isEmpty()
        || sub.getTakeParamStrList() == null || sub.getTakeParamStrList().length != 0
        || sub.isBackTrack()
        || sub.getMazePlaneID() != 0 || sub.getMazeFloorID() != 0
        || sub.getStoryLineID() != 0
        || sub.getStoryLineIDList() == null || !sub.getStoryLineIDList().isEmpty()
        || sub.getIgnoreVerseParamList() == null || !sub.getIgnoreVerseParamList().isEmpty()
        || !ordinary(sub.getLevelPlaneID(), sub.getLevelFloorID())
        || !ordinary(sub.getParamInt1(), sub.getParamInt2())
        || sub.getLevelPlaneID() == sub.getParamInt1() && sub.getLevelFloorID() == sub.getParamInt2()
        || sub.getTask() == null) return false;
    var text = sub.getTask().getSourceDefinition();
    if (text == null || text.isBlank() || text.length() > 4096) return false;
    try {
      var root = JsonParser.parseString(text).getAsJsonObject();
      if (!keys(root, "Type", "OnInitSequece", "OnStartSequece")
          || !string(root.get("Type"), "Mission")
          || !root.getAsJsonArray("OnInitSequece").isEmpty()) return false;
      var sequences = root.getAsJsonArray("OnStartSequece");
      if (sequences.size() != 1) return false;
      var sequence = sequences.get(0).getAsJsonObject();
      // A single authored sequence may explicitly be first (e.g. 100020118).
      // Its order cannot reorder any other work; retain all other structural checks.
      if (sequence.has("Order")) {
        if (integer(sequence.get("Order")) != 1) return false;
        sequence.remove("Order");
      }
      if (!keys(sequence, "TaskList")
          && !(keys(sequence, "IsLoop", "TaskList") && truth(sequence.get("IsLoop")))) return false;
      var tasks = sequence.getAsJsonArray("TaskList");
      if (tasks.size() != 1) return false;
      var task = tasks.get(0).getAsJsonObject();
      if (!keys(task, "$type", "EntranceID")
          && !(keys(task, "$type", "EntranceID", "GroupID", "AnchorID")
              && integer(task.get("GroupID")) > 0 && integer(task.get("AnchorID")) > 0)) return false;
      if (!string(task.get("$type"), "RPG.GameCore.EnterMap")) return false;
      int entrance = integer(task.get("EntranceID"));
      var entry = GameData.getMapEntranceExcelMap().get(entrance);
      return entrance > 0 && entry != null && entry.getId() == entrance
          && entry.getPlaneID() == sub.getParamInt1() && entry.getFloorID() == sub.getParamInt2();
    } catch (RuntimeException unsupported) {
      return false;
    }
  }

  private static boolean ordinary(int plane, int floor) {
    var row = GameData.getMazePlaneExcelMap().get(plane);
    var info = GameData.getFloorInfo(plane, floor);
    return plane > 0 && floor > 0 && row != null && row.getId() == plane
        && (row.getPlaneType() == PlaneType.Town || row.getPlaneType() == PlaneType.Maze || row.getPlaneType() == PlaneType.Train)
        && row.getFloorIDList() != null && Collections.frequency(row.getFloorIDList(), floor) == 1
        && info != null && info.getFloorID() == floor;
  }

  private static boolean keys(JsonObject row, String... names) { return row.keySet().equals(Set.of(names)); }
  private static boolean string(JsonElement value, String expected) {
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        && expected.equals(value.getAsString());
  }
  private static boolean truth(JsonElement value) {
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
        && value.getAsBoolean();
  }
  private static int integer(JsonElement value) {
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return 0;
    try { return value.getAsBigDecimal().intValueExact(); }
    catch (ArithmeticException | NumberFormatException invalid) { return 0; }
  }

  private DailyMissionTravelRules() {}
}
