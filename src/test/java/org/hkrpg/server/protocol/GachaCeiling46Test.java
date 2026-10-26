package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.GachaCeilingAvatarOuterClass.GachaCeilingAvatar;
import emu.lunarcore.proto.GachaCeilingOuterClass.GachaCeiling;
import emu.lunarcore.proto.GetGachaCeilingCsReqOuterClass.GetGachaCeilingCsReq;
import emu.lunarcore.proto.GetGachaCeilingScRspOuterClass.GetGachaCeilingScRsp;
import emu.lunarcore.proto.ExchangeGachaCeilingCsReqOuterClass.ExchangeGachaCeilingCsReq;
import emu.lunarcore.proto.ExchangeGachaCeilingScRspOuterClass.ExchangeGachaCeilingScRsp;
import emu.lunarcore.server.packet.CmdId;
import java.util.List;
import org.junit.jupiter.api.Test;

class GachaCeiling46Test {
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  @Test void nativeCeilingQueryCarriesTheBannerTypeToTheCore() throws Exception {
    // The pinned client constructs command 1960 with banner type in field 15.
    var translated = bridge.inbound(1960, new byte[] {0x78, 0x02});
    assertEquals(CmdId.GetGachaCeilingCsReq, translated.cmdId());
    assertEquals(2, GetGachaCeilingCsReq.parseFrom(translated.body()).getGachaType());
  }

  @Test void nativeQueryReplyContainsThePersistedProgressAndClaimState() throws Exception {
    var data = GachaCeiling.newInstance().setCeilingNum(300).setIsClaimed(true)
        .addAvatarList(GachaCeilingAvatar.newInstance().setAvatarId(1003).setRepeatedCnt(2));
    var core = GetGachaCeilingScRsp.newInstance().setGachaType(2).setGachaCeiling(data);
    var translated = bridge.outbound(CmdId.GetGachaCeilingScRsp, core.toByteArray());
    assertEquals(1916, translated.cmdId());
    var response = bridge.clientSchema().parse("GetGachaCeilingScRsp", translated.body());
    assertEquals(2, response.getField(response.getDescriptorForType().findFieldByNumber(14)));
    var ceiling = (DynamicMessage) response.getField(response.getDescriptorForType().findFieldByNumber(10));
    assertEquals(300, ceiling.getField(ceiling.getDescriptorForType().findFieldByNumber(1)));
    assertEquals(true, ceiling.getField(ceiling.getDescriptorForType().findFieldByNumber(15)));
    var avatars = (List<?>) ceiling.getField(ceiling.getDescriptorForType().findFieldByNumber(13));
    assertEquals(1, avatars.size());
    var avatar = (DynamicMessage) avatars.getFirst();
    assertEquals(1003, avatar.getField(avatar.getDescriptorForType().findFieldByNumber(10)));
    assertEquals(2, avatar.getField(avatar.getDescriptorForType().findFieldByNumber(9)));
  }

  @Test void nativeExchangeRequestCarriesTheChosenAvatarAndBanner() throws Exception {
    // Fixed client: type at wire 1 and selected avatar at wire 6.
    var translated = bridge.inbound(1958, new byte[] {0x08, 0x02, 0x30, (byte) 0xeb, 0x07});
    var request = ExchangeGachaCeilingCsReq.parseFrom(translated.body());
    assertEquals(2, request.getGachaType());
    assertEquals(1003, request.getAvatarId());
  }

  @Test void nativeExchangeReplyReportsTheSelectionAndTheClaimedFlag() throws Exception {
    var data = GachaCeiling.newInstance().setCeilingNum(300).setIsClaimed(true);
    var core = ExchangeGachaCeilingScRsp.newInstance().setGachaType(2).setAvatarId(1003).setGachaCeiling(data);
    var translated = bridge.outbound(CmdId.ExchangeGachaCeilingScRsp, core.toByteArray());
    assertEquals(1956, translated.cmdId());
    var response = bridge.clientSchema().parse("ExchangeGachaCeilingScRsp", translated.body());
    assertEquals(2, response.getField(response.getDescriptorForType().findFieldByNumber(2)));
    assertEquals(1003, response.getField(response.getDescriptorForType().findFieldByNumber(8)));
    var ceiling = (DynamicMessage) response.getField(response.getDescriptorForType().findFieldByNumber(14));
    assertEquals(true, ceiling.getField(ceiling.getDescriptorForType().findFieldByNumber(15)));
  }
}
