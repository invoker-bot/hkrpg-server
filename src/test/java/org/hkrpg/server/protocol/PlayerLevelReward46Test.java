package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.hkrpg.server.protocol.AvatarUpgrade46Test.*;

import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlayerLevelReward46Test {
  @Test void rewardButtonReceivesTheNativeReplyInsteadOfBeingDropped() throws Exception {
    var s = new ProgressionGates46Test.CheckedSession();
    assertTrue(VersionedPacketHandler.handle(s, 19, join(scalar(1,13), scalar(7,3))));
    assertEquals("GetLevelRewardScRsp", s.responseName);
    assertEquals(Retcode.LEVEL_REWARD_LEVEL_ERROR.getVal(), InitialData46Test.field(s.response,"retcode"));
    assertEquals(3, InitialData46Test.field(s.response,"level"));
    assertEquals(SessionState.ACTIVE, s.getState());
  }

  @Test void takenListDoesNotPretendCurrentLevelWasAlreadyClaimed() throws Exception {
    var s = new ProgressionGates46Test.CheckedSession();
    s.player.setLevel(20);
    assertTrue(VersionedPacketHandler.handle(s,78,new byte[0]));
    assertEquals("GetLevelRewardTakenListScRsp",s.responseName);
    assertEquals(0,InitialData46Test.field(s.response,"retcode"));
    assertEquals(0,s.response.getRepeatedFieldCount(s.response.getDescriptorForType().findFieldByNumber(15)));
    assertTrue(s.player.getGameplay().getRewards().isEmpty());
  }

  @ParameterizedTest @ValueSource(strings={"unknown","duplicate","wrong-wire","truncated","overflow","oversize"})
  void invalidClaimReceivesTypedFailureAndDoesNotReserveAReward(String kind) throws Exception {
    byte[] body = switch(kind) {
      case "unknown" -> scalar(8,3);
      case "duplicate" -> join(scalar(7,3),scalar(7,3));
      case "wrong-wire" -> nested(7,new byte[0]);
      case "truncated" -> new byte[]{56,(byte)128};
      case "overflow" -> new byte[]{56,(byte)128,(byte)128,(byte)128,(byte)128,16};
      default -> new byte[16385];
    };
    var s = new ProgressionGates46Test.CheckedSession();
    assertTrue(VersionedPacketHandler.handle(s,19,body));
    assertEquals("GetLevelRewardScRsp",s.responseName);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),InitialData46Test.field(s.response,"retcode"));
    assertEquals(SessionState.ACTIVE,s.getState());
    assertTrue(s.player.getGameplay().getRewards().isEmpty());
  }

  @Test void takenListRejectsUnexpectedFields() throws Exception {
    var s = new ProgressionGates46Test.CheckedSession();
    assertTrue(VersionedPacketHandler.handle(s,78,scalar(7,3)));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(),InitialData46Test.field(s.response,"retcode"));
  }

  @ParameterizedTest @ValueSource(ints={19,78})
  void unauthenticatedRequestsCannotTouchPlayerState(int command) throws Exception {
    var s = new ProgressionGates46Test.CheckedSession();
    s.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(s,command,scalar(7,3)));
    assertNull(s.responseName);
    assertTrue(s.player.getGameplay().getRewards().isEmpty());
  }
}
