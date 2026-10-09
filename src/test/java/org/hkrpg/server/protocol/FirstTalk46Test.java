package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.Test;

class FirstTalk46Test {
  @Test
  void unknownPerformanceCannotReturnSuccessOrCreateMissionProgress() throws Exception {
    var s = new Gameplay46HandlerTest.Session();
    var req = Gameplay46.builder(s, "FinishFirstTalkByPerformanceNpcCsReq");
    Gameplay46.set(req, "performance_id", 199970099);
    assertTrue(
        VersionedPacketHandler.handle(
            s,
            s.getProtocol().clientCommand("FinishFirstTalkByPerformanceNpcCsReq"),
            req.build().toByteArray()));
    assertEquals("FinishFirstTalkByPerformanceNpcScRsp", s.responseName);
    assertNotEquals(Retcode.SUCC.getVal(), InitialData46Test.field(s.response, "retcode"));
    assertTrue(s.player.getGameplay().getSubMissions().isEmpty());
  }

  @Test
  void unauthenticatedCompletionIsConsumedWithoutChangingState() throws Exception {
    var s = new Gameplay46HandlerTest.Session();
    s.setState(SessionState.WAITING_FOR_TOKEN);
    s.request("FinishFirstTalkByPerformanceNpcCsReq");
    assertNull(s.response);
    assertTrue(s.player.getGameplay().getSubMissions().isEmpty());
  }

  @Test
  void nativeReportCompletesAcceptedObjectiveAndQueryReturnsPersistedSeenStatus() throws Exception {
    int main = 19997003, sub = 199970031, performance = 199970031;
    var gson = new com.google.gson.Gson();
    GameData.getMainMissionExcelMap()
        .add(
            gson.fromJson(
                """
{"MainMissionID":19997003,"TakeParam":[{"Type":"Manual"}],"BeginParam":[{"Type":"Auto"}]}
""",
                emu.lunarcore.data.excel.MainMissionExcel.class));
    var info =
        gson.fromJson(
            """
{"MainMissionID":19997003,"FinishSubMissionList":[199970031],
 "SubMissionList":[{"ID":199970031,"MainMissionID":19997003,"TakeType":"Auto",
  "FinishType":"FinishFirstTalkPerformance","ParamType":"ListContain","ParamIntList":[199970031],
  "LevelPlaneID":10000,"LevelFloorID":10000001}]}
""",
            emu.lunarcore.data.config.MissionInfo.class);
    GameData.getMissionInfos().put(main, info);
    GameplayDefinitions.subs.put(sub, info.getSubMissionList()[0]);
    FirstTalkDefinitions.performances.put(
        performance, new FirstTalkDefinitions.Performance(performance, "fixture", Set.of(), true));
    var actor =
        new Player() {
          public int getPlaneId() {
            return 10000;
          }

          public int getFloorId() {
            return 10000001;
          }

          public void save() {}
        };
    var s =
        new Gameplay46HandlerTest.Session() {
          public Player getPlayer() {
            return actor;
          }
        };
    try {
      actor.getMissionManager().accept(main);
      var q = Gameplay46.builder(s, "GetFirstTalkByPerformanceNpcCsReq");
      Gameplay46.add(q, "performance_id_list", performance);
      assertEquals(0x22, q.build().toByteArray()[0]);
      assertTrue(VersionedPacketHandler.handle(s, 2142, q.build().toByteArray()));
      assertEquals(
          false,
          InitialData46Test.field(
              InitialData46Test.first(s.response, "npc_meet_status_list"), "is_meet"));
      var r = Gameplay46.builder(s, "FinishFirstTalkByPerformanceNpcCsReq");
      Gameplay46.set(r, "performance_id", performance);
      assertEquals(0x68, r.build().toByteArray()[0]);
      assertTrue(VersionedPacketHandler.handle(s, 2161, r.build().toByteArray()));
      assertEquals(Retcode.SUCC.getVal(), InitialData46Test.field(s.response, "retcode"));
      assertEquals(performance, InitialData46Test.field(s.response, "performance_id"));
      assertEquals(1, actor.getMissionManager().sub(sub).getProgress());
      assertTrue(VersionedPacketHandler.handle(s, 2142, q.build().toByteArray()));
      assertEquals(
          true,
          InitialData46Test.field(
              InitialData46Test.first(s.response, "npc_meet_status_list"), "is_meet"));
    } finally {
      GameData.getMainMissionExcelMap().keySet().remove(main);
      GameData.getMissionInfos().remove(main);
      GameplayDefinitions.subs.remove(sub);
      FirstTalkDefinitions.performances.remove(performance);
    }
  }
}
