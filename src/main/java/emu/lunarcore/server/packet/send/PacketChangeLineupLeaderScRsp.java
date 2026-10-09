package emu.lunarcore.server.packet.send;

import emu.lunarcore.proto.ChangeLineupLeaderScRspOuterClass.ChangeLineupLeaderScRsp;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketChangeLineupLeaderScRsp extends BasePacket {

    public PacketChangeLineupLeaderScRsp(int slot) {
        this(slot, emu.lunarcore.server.packet.Retcode.SUCC);
    }

    public PacketChangeLineupLeaderScRsp(int slot, emu.lunarcore.server.packet.Retcode retcode) {
        super(CmdId.ChangeLineupLeaderScRsp);

        var data = ChangeLineupLeaderScRsp.newInstance()
                .setSlot(slot)
                .setRetcode(retcode.getVal());

        this.setData(data);
    }
}
