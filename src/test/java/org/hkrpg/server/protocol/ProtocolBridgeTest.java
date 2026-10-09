package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.PlayerGetTokenCsReqOuterClass.PlayerGetTokenCsReq;
import emu.lunarcore.proto.PlayerGetTokenScRspOuterClass.PlayerGetTokenScRsp;
import emu.lunarcore.proto.PlayerHeartBeatScRspOuterClass.PlayerHeartBeatScRsp;
import emu.lunarcore.proto.PlayerLoginScRspOuterClass.PlayerLoginScRsp;
import emu.lunarcore.server.packet.CmdId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolBridgeTest {
    private final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

    @Test void tokenRequestUses46CommandAndFields() throws Exception {
        var type = bridge.clientSchema().message("PlayerGetTokenCsReq");
        var wire = DynamicMessage.newBuilder(type)
            .setField(type.findFieldByName("account_uid"), "10001")
            .setField(type.findFieldByName("token"), "combo-token")
            .setField(type.findFieldByName("platform"), 1).build().toByteArray();
        var converted = bridge.inbound(60, wire);
        assertEquals(CmdId.PlayerGetTokenCsReq, converted.cmdId());
        var request = PlayerGetTokenCsReq.parseFrom(converted.body());
        assertEquals("10001", request.getAccountUid());
        assertEquals("combo-token", request.getToken());
        assertEquals(1, request.getPlatformType());
    }

    @Test void tokenErrorAndNestedBanSurviveConversion() throws Exception {
        var core = PlayerGetTokenScRsp.newInstance().setUid(10001).setRetcode(101).setMsg("denied");
        core.getMutableBlackInfo().setBanType(2).setBeginTime(100).setEndTime(200);
        var result = bridge.outbound(CmdId.PlayerGetTokenScRsp, core.toByteArray());
        assertEquals(16, result.cmdId());
        var wire = bridge.clientSchema().parse("PlayerGetTokenScRsp", result.body());
        assertEquals(101, wire.getField(wire.getDescriptorForType().findFieldByName("retcode")));
        assertEquals("denied", wire.getField(wire.getDescriptorForType().findFieldByName("stop_desc")));
        var ban = (DynamicMessage) wire.getField(wire.getDescriptorForType().findFieldByName("black_info"));
        assertEquals(2, ban.getField(ban.getDescriptorForType().findFieldByName("ban_type")));
    }

    @Test void heartbeatAndNegativeTimezoneAreReencoded() throws Exception {
        var heartbeat = PlayerHeartBeatScRsp.newInstance().setClientTimeMs(1234567890123L).setServerTimeMs(1234567899999L);
        var result = bridge.outbound(CmdId.PlayerHeartBeatScRsp, heartbeat.toByteArray());
        assertEquals(62, result.cmdId());
        var wire = bridge.clientSchema().parse("PlayerHeartBeatScRsp", result.body());
        assertEquals(1234567890123L, wire.getField(wire.getDescriptorForType().findFieldByName("client_time_ms")));
        var login = PlayerLoginScRsp.newInstance().setCurTimezone(-8).setStamina(120);
        login.getMutableBasicInfo().setNickname("测试玩家").setLevel(30).setHcoin(456);
        var loginWire = bridge.clientSchema().parse("PlayerLoginScRsp", bridge.outbound(CmdId.PlayerLoginScRsp, login.toByteArray()).body());
        assertEquals(-8, loginWire.getField(loginWire.getDescriptorForType().findFieldByName("cur_timezone")));
        var info = (DynamicMessage) loginWire.getField(loginWire.getDescriptorForType().findFieldByName("basic_info"));
        assertEquals("测试玩家", info.getField(info.getDescriptorForType().findFieldByName("nickname")));
    }

    @Test void unsupportedPacketsNeverReuseLegacyCommandOrFieldNumbers() {
        assertThrows(ProtocolException.class, () -> bridge.inbound(65535, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> ProtocolBridge.forVersion("4.6.51"));
    }

    @Test void emptyQuestQueryReachesTheCoreAndRejectsUnexpectedFields() {
        assertNull(bridge.coreSchema().find("GetQuestDataCsReq"));
        var query = bridge.inbound(934, new byte[0]);
        assertEquals(CmdId.GetQuestDataCsReq, query.cmdId());
        assertArrayEquals(new byte[0], query.body());
        assertThrows(ProtocolException.class, () -> bridge.inbound(934, new byte[] {8, 1}));
    }
}
