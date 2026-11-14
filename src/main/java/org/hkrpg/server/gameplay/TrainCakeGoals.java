package org.hkrpg.server.gameplay;

import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import java.util.Arrays;

/** Only the two authored single-step objectives in mission 4020800 are supported. */
final class TrainCakeGoals {
  private static boolean shape(MissionInfo.SubMissionInfo c, boolean open) {
    int id = open ? 402080008 : 402080009;
    int previous = open ? 402080005 : 402080006;
    return c != null && c.getId() == id && c.getMainMissionID() == 4020800
        && c.getLevelPlaneID() == 10000 && c.getLevelFloorID() == 10000003
        && "AnySequence".equals(c.getTakeType())
        && Arrays.equals(c.getTakeParamIntList(), new Integer[] {previous})
        && "NoPara".equals(c.getParamType())
        && (open ? "CakeCatchOpenBox" : "CakeCatchSearchCake").equals(c.getFinishType())
        && c.isBackTrack() != open && c.getProgress() == 1
        && c.getParamInt1() == 0 && c.getParamInt2() == 0 && c.getParamInt3() == 0
        && "".equals(c.getParamStr1()) && c.getParamIntList() != null
        && c.getParamIntList().length == 0 && c.getParamItemList() != null
        && c.getParamItemList().isEmpty() && c.getFinishActionList() != null
        && c.getFinishActionList().length == 0 && c.getSubRewardID() == 0;
  }

  private static boolean place(Player p) {
    var scene = p.getScene();
    return scene != null && scene.getPlaneId() == 10000 && scene.getFloorId() == 10000003
        && scene.getDimensionId() == 0 && scene.getStoryLineId() == 0
        && p.getPlaneId() == 10000 && p.getFloorId() == 10000003;
  }

  static boolean matches(Player p, MissionInfo.SubMissionInfo c, boolean open) {
    int prerequisite = open ? 402080005 : 402080006;
    return shape(c, open) && place(p) && MissionStateRecords.subCompleted(p, prerequisite);
  }

  static boolean searchSatisfied(Player p, MissionInfo.SubMissionInfo c) {
    if (!matches(p, c, false)) return false;
    try {
      // Cumulative counts survive the weekly rollover; a capture is genuine only when
      // the complete saved state still matches the resources used to create it.
      return !TrainCakeCatch.snapshot(p).getCounts().isEmpty();
    } catch (RuntimeException invalid) {
      org.hkrpg.server.GameDiagnostics.outcome(p.getSession(), "TrainCakeGoal", 402080009,
          emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR, "capture-source-invalid", "");
      return false;
    }
  }

  private TrainCakeGoals() {}
}
