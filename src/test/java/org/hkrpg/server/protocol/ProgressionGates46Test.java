package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.hkrpg.server.protocol.AvatarUpgrade46Test.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import emu.lunarcore.server.packet.SessionState;

/** Raw fields pinned to Honey fe00bdb; failures must also receive the correct 4.6 reply. */
class ProgressionGates46Test {
  static class CheckedSession extends Gameplay46HandlerTest.Session {
    @Override public void sendClientPacket(String name, byte[] bytes) {
      assertTrue(getProtocol().clientCommand(name)>0, "Native response must have an actual registered command");
      super.sendClientPacket(name,bytes);
    }
  }
  @ParameterizedTest @CsvSource({"358,5,PromoteAvatarScRsp,1","505,10,PromoteEquipmentScRsp,1",
      "360,11,UnlockSkilltreeScRsp,14","560,7,LockEquipmentScRsp,7","521,5,LockRelicScRsp,12"})
  void unknownOwnedTargetReceivesTypedFailure(int command,int idField,String response,int retcodeField) throws Exception {
    var s=new CheckedSession();
    assertTrue(VersionedPacketHandler.handle(s,command,scalar(idField,999999)));
    assertEquals(response,s.responseName);
    assertNotEquals(0,s.response.getField(s.response.getDescriptorForType().findFieldByNumber(retcodeField)));
    assertEquals(SessionState.ACTIVE,s.getState());
  }

  @ParameterizedTest @CsvSource({"358,PromoteAvatarScRsp,1","505,PromoteEquipmentScRsp,1",
      "360,UnlockSkilltreeScRsp,14","560,LockEquipmentScRsp,7","521,LockRelicScRsp,12"})
  void malformedRequestReceivesFailureWithoutSessionLoss(int command,String response,int retcodeField) throws Exception {
    var s=new CheckedSession();
    assertTrue(VersionedPacketHandler.handle(s,command,new byte[]{90,100}));
    assertEquals(response,s.responseName);
    assertNotEquals(0,s.response.getField(s.response.getDescriptorForType().findFieldByNumber(retcodeField)));
    assertEquals(SessionState.ACTIVE,s.getState());
  }
}
