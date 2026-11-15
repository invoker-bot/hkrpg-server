package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.server.packet.SessionState;
import org.junit.jupiter.api.Test;

/** The client must receive a real answer instead of waiting on an unsupported room command. */
class TrainCakeSocial46Test {
  @Test void unavailableRoomReturnsAnErrorWithoutChangingPlayerData() throws Exception {
    var s = new TrainCakeCatch46Test.Session();
    String before = new com.google.gson.Gson().toJson(s.actor.getGameplay());
    var request = Gameplay46.builder(s, "SocialPlayPreCheckEnterRoomCsReq");
    Gameplay46.set(request, "teleport_id", 321321);
    Gameplay46.set(request, "entry_id", 100000353);
    assertTrue(VersionedPacketHandler.handle(s, 9736, request.build().toByteArray()),
        "A missing or unavailable room must receive a bounded error response");
    assertEquals("SocialPlayPreCheckEnterRoomScRsp", s.responseName);
    assertNotEquals(0, TrainCakeCatch46Test.field(s.response, "retcode"));
    assertEquals(before, new com.google.gson.Gson().toJson(s.actor.getGameplay()));
    assertEquals(0, s.actor.saves);
  }

  @Test void onlineRoomQueryOutsideARoomCannotFabricateAnotherPlayersRoom() throws Exception {
    var s = new TrainCakeCatch46Test.Session();
    assertTrue(VersionedPacketHandler.handle(s, 9726, new byte[0]),
        "The client's room query must be handled");
    assertEquals("SocialPlayGetOnlineRoomDataScRsp", s.responseName);
    assertNotEquals(0, TrainCakeCatch46Test.field(s.response, "retcode"));
    assertEquals(0, s.actor.saves);
  }

  @Test void unauthenticatedRoomPrecheckNeverReadsOrCreatesPlayerData() throws Exception {
    var s = new TrainCakeCatch46Test.Session(); s.setState(SessionState.WAITING_FOR_TOKEN);
    var request = Gameplay46.builder(s, "SocialPlayPreCheckEnterRoomCsReq");
    Gameplay46.set(request, "teleport_id", 321321);
    assertTrue(VersionedPacketHandler.handle(s, 9736, request.build().toByteArray()));
    assertNull(s.response);
    assertEquals(0, s.actor.saves);
  }
}
