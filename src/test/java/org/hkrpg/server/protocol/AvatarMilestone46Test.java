package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.hkrpg.server.protocol.AvatarUpgrade46Test.*;

import emu.lunarcore.proto.RankUpAvatarCsReqOuterClass.RankUpAvatarCsReq;
import emu.lunarcore.server.packet.CmdId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AvatarMilestone46Test {
  @Test void nativeEidolonSelectionReachesTheHandler() throws Exception {
    var bridge = ProtocolBridge.forVersion("4.6.0");
    byte[] cost = nested(2,nested(10,join(scalar(14,11202),scalar(1,1))));
    var converted = bridge.inbound(308, join(scalar(7,1202),scalar(6,1),nested(14,cost)));
    assertEquals(CmdId.RankUpAvatarCsReq,converted.cmdId());
    var request = RankUpAvatarCsReq.parseFrom(converted.body());
    assertEquals(1202,request.getAvatarId()); assertEquals(1,request.getRank());
    assertEquals(11202,request.getItemCostList().getItemList().get(0).getPileItem().getItemId());
    assertEquals(1,request.getItemCostList().getItemList().get(0).getPileItem().getItemNum());
  }

  @ParameterizedTest @CsvSource({"308,RankUpAvatarScRsp,9","317,TakePromotionRewardScRsp,13"})
  void malformedRequestGetsTypedFailureAndKeepsSession(int command,String response,int field) throws Exception {
    var session=new ProgressionGates46Test.CheckedSession();
    assertTrue(VersionedPacketHandler.handle(session,command,new byte[]{90,100}));
    assertEquals(response,session.responseName);
    assertNotEquals(0,session.response.getField(session.response.getDescriptorForType().findFieldByNumber(field)));
    assertEquals(emu.lunarcore.server.packet.SessionState.ACTIVE,session.getState());
  }

  @ParameterizedTest @CsvSource({"308,RankUpAvatarScRsp,9","317,TakePromotionRewardScRsp,13"})
  void unknownAvatarGetsTypedFailure(int command,String response,int field) throws Exception {
    var session=new ProgressionGates46Test.CheckedSession();
    byte[] input=command==308?join(scalar(7,999999),scalar(6,1),nested(14,nested(2,nested(10,join(scalar(14,11202),scalar(1,1))))))
        :join(scalar(5,999999),scalar(2,1));
    assertTrue(VersionedPacketHandler.handle(session,command,input));
    assertEquals(response,session.responseName);
    assertNotEquals(0,session.response.getField(session.response.getDescriptorForType().findFieldByNumber(field)));
  }
}
