package org.hkrpg.server.gameplay;

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
    return transfer(sub) != null;
  }

  static LiteralSceneTransfer transfer(MissionInfo.SubMissionInfo sub) {
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
        || sub.getTask() == null) return null;
    var transfer = LiteralSceneTransfer.parse(sub.getTask().getSourceDefinition());
    var entry = transfer == null ? null : GameData.getMapEntranceExcelMap().get(transfer.entry());
    return entry != null && entry.getId() == transfer.entry()
        && entry.getPlaneID() == sub.getParamInt1() && entry.getFloorID() == sub.getParamInt2()
            ? transfer : null;
  }

  private static boolean ordinary(int plane, int floor) {
    var row = GameData.getMazePlaneExcelMap().get(plane);
    var info = GameData.getFloorInfo(plane, floor);
    return plane > 0 && floor > 0 && row != null && row.getId() == plane
        && (row.getPlaneType() == PlaneType.Town || row.getPlaneType() == PlaneType.Maze || row.getPlaneType() == PlaneType.Train)
        && row.getFloorIDList() != null && Collections.frequency(row.getFloorIDList(), floor) == 1
        && info != null && info.getFloorID() == floor;
  }

  private DailyMissionTravelRules() {}
}
