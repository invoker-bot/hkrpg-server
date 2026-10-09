package org.hkrpg.server.protocol;

import emu.lunarcore.proto.FinishQuestScRspOuterClass.FinishQuestScRsp;
import emu.lunarcore.proto.GetQuestDataScRspOuterClass.GetQuestDataScRsp;
import emu.lunarcore.proto.QuestOuterClass.Quest;
import emu.lunarcore.proto.QuestStatusOuterClass.QuestStatus;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolEnvelopeTest {
    private final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

    @Test void transportPreservesHeadersAndAliasesQuestErrors() throws Exception {
        byte[] header = {8, 17, 16, 42};
        byte[] frame = PacketCodec.encode(CmdId.FinishQuestScRsp, header,
            FinishQuestScRsp.newInstance().setRetcode(Retcode.QUEST_NOT_ACCEPT.getVal()).toByteArray());
        var buffer = Unpooled.wrappedBuffer(bridge.outboundFrames(frame));
        try {
            var converted = PacketCodec.decodeNext(buffer);
            assertEquals(942, converted.cmdId());
            assertArrayEquals(header, converted.header());
            var error = bridge.clientSchema().parse("FinishQuestScRsp", converted.body());
            assertEquals(1101, error.getField(error.getDescriptorForType().findFieldByName("PLDKENJLKME")));
        } finally { buffer.release(); }
    }

    @Test void questEnumsAreMappedByMeaning() throws Exception {
        var old = GetQuestDataScRsp.newInstance().addQuestList(Quest.newInstance().setId(22001).setProgress(3).setStatus(QuestStatus.QUEST_FINISH));
        var wire = bridge.clientSchema().parse("GetQuestDataScRsp", bridge.outbound(CmdId.GetQuestDataScRsp, old.toByteArray()).body());
        var quests = (java.util.List<?>) wire.getField(wire.getDescriptorForType().findFieldByName("quest_list"));
        var row = (com.google.protobuf.DynamicMessage) quests.getFirst();
        var status = (com.google.protobuf.Descriptors.EnumValueDescriptor) row.getField(row.getDescriptorForType().findFieldByName("status"));
        assertEquals("QuestStatus_QuestFinish", status.getName());
        assertEquals(3, row.getField(row.getDescriptorForType().findFieldByName("progress")));
    }

    @Test void unmappedFilledFieldsAreRejectedAndLegacyWireIsUnchanged() {
        var core = GetQuestDataScRsp.newInstance().setTotalAchievementExp(999);
        assertThrows(ProtocolException.class, () -> bridge.outbound(CmdId.GetQuestDataScRsp, core.toByteArray()));
        byte[] payload = {1, 2, 3};
        var legacy = ProtocolBridge.forVersion("4.2.0");
        assertArrayEquals(payload, legacy.inbound(CmdId.GetQuestDataCsReq, payload).body());
        assertArrayEquals(payload, legacy.outbound(CmdId.GetQuestDataScRsp, payload).body());
    }
}
