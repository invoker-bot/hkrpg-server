package emu.lunarcore.game.player;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.proto.*;
import emu.lunarcore.server.packet.send.*;
import org.junit.jupiter.api.Test;

class GameplayPlaceholderTest {
  @Test
  void missionQueryCannotFinishUnknownOrUnacceptedMissions() throws Exception {
    var request =
        GetMissionStatusCsReqOuterClass.GetMissionStatusCsReq.newInstance()
            .addMainMissionIdList(199999999)
            .addSubMissionIdList(1999999990);
    var packet = new PacketGetMissionStatusScRsp(request);
    var data =
        GetMissionStatusScRspOuterClass.GetMissionStatusScRsp.parseFrom(
            packet.getData().toByteArray());
    assertEquals(0, data.getFinishedMainMissionIdList().length());
    assertEquals(
        MissionStatusOuterClass.MissionStatus.MISSION_NONE,
        data.getSubMissionStatusList().get(0).getStatus());
  }

  @Test
  void newPlayerDoesNotHaveFiveHundredPointsAndClaimedDailyRewards() throws Exception {
    var packet = new PacketGetDailyActiveInfoScRsp(new Player());
    var data =
        GetDailyActiveInfoScRspOuterClass.GetDailyActiveInfoScRsp.parseFrom(
            packet.getData().toByteArray());
    assertEquals(0, data.getDailyActivePoint());
    for (var level : data.getDailyActiveLevelList()) assertFalse(level.getIsHasTaken());
  }
}
