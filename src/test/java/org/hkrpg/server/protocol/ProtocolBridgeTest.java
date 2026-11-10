package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.DressRelicAvatarCsReqOuterClass.DressRelicAvatarCsReq;
import emu.lunarcore.proto.PlayerGetTokenCsReqOuterClass.PlayerGetTokenCsReq;
import emu.lunarcore.proto.PlayerGetTokenScRspOuterClass.PlayerGetTokenScRsp;
import emu.lunarcore.proto.PlayerHeartBeatScRspOuterClass.PlayerHeartBeatScRsp;
import emu.lunarcore.proto.PlayerLoginScRspOuterClass.PlayerLoginScRsp;
import emu.lunarcore.proto.TakeOffRelicCsReqOuterClass.TakeOffRelicCsReq;
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

    @Test void relicDressRequestPreservesAvatarAndEachSlotAndItem() throws Exception {
        // CN4.6 uses avatar field 8 and switch_list field 4 with slot 1 and item 7 inside.
        byte[] wire = {0x40, (byte) 0xC2, 0x3E, 0x22, 4, 8, 3, 56, 91, 0x22, 4, 8, 6, 56, 92};
        var converted = bridge.inbound(364, wire);
        assertEquals(CmdId.DressRelicAvatarCsReq, converted.cmdId());
        var request = DressRelicAvatarCsReq.parseFrom(converted.body());
        assertEquals(8002, request.getAvatarId());
        assertEquals(2, request.getParamList().length());
        assertEquals(3, request.getParamList().get(0).getSlot());
        assertEquals(91, request.getParamList().get(0).getRelicUniqueId());
        assertEquals(6, request.getParamList().get(1).getSlot());
        assertEquals(92, request.getParamList().get(1).getRelicUniqueId());
        assertEquals(321, bridge.outbound(CmdId.DressRelicAvatarScRsp, new byte[0]).cmdId());
    }

    @Test void relicTakeOffRequestPreservesAvatarAndPackedSlots() throws Exception {
        // CN4.6 uses avatar field 10 and packed relic_type_list field 2.
        byte[] wire = {0x50, (byte) 0xC2, 0x3E, 0x12, 2, 3, 6};
        var converted = bridge.inbound(312, wire);
        assertEquals(CmdId.TakeOffRelicCsReq, converted.cmdId());
        var request = TakeOffRelicCsReq.parseFrom(converted.body());
        assertEquals(8002, request.getAvatarId());
        assertArrayEquals(new int[] {3, 6}, request.getSlotList().toArray());
        assertEquals(311, bridge.outbound(CmdId.TakeOffRelicScRsp, new byte[0]).cmdId());
    }
}
