package org.hkrpg.server.protocol;

import emu.lunarcore.proto.PlayerGetTokenCsReqOuterClass.PlayerGetTokenCsReq;
import emu.lunarcore.proto.PlayerGetTokenScRspOuterClass.PlayerGetTokenScRsp;
import emu.lunarcore.server.packet.CmdId;
import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolWireFixtureTest {
    private final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    private static byte[] hex(String value) { return HexFormat.of().parseHex(value); }

    @Test void independentlyEncoded46LoginFieldsAreReadCorrectly() throws Exception {
        // 4.6: token=3, account_uid=6, platform=10. Literal wire bytes, independent of descriptor encoding.
        byte[] fixture = hex("1a03616263320531303030315001");
        var parsed = PlayerGetTokenCsReq.parseFrom(bridge.inbound(60, fixture).body());
        assertEquals("abc", parsed.getToken());
        assertEquals("10001", parsed.getAccountUid());
        assertEquals(1, parsed.getPlatformType());
    }

    @Test void independentlyExpected46FailureFieldNumbersAreWritten() {
        // retcode=3 (1101), stop_desc=10 ('denied'), uid=15 (10001).
        var core = PlayerGetTokenScRsp.newInstance().setRetcode(1101).setMsg("denied").setUid(10001);
        var wire = bridge.outbound(CmdId.PlayerGetTokenScRsp, core.toByteArray());
        assertEquals(16, wire.cmdId());
        assertArrayEquals(hex("18cd08520664656e69656478914e"), wire.body());
    }
}
