package emu.lunarcore.server.packet.send;

import emu.lunarcore.proto.FinishQuestScRspOuterClass.FinishQuestScRsp;
import emu.lunarcore.server.packet.*;

public class PacketFinishQuestScRsp extends BasePacket {
    public PacketFinishQuestScRsp(Retcode retcode) {
        super(CmdId.FinishQuestScRsp);
        setData(FinishQuestScRsp.newInstance().setRetcode(retcode.getVal()));
    }
}
