package emu.lunarcore.server.packet.recv;

import emu.lunarcore.proto.ChangeLineupLeaderCsReqOuterClass.ChangeLineupLeaderCsReq;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Opcodes;
import emu.lunarcore.server.packet.PacketHandler;
import emu.lunarcore.server.packet.send.PacketChangeLineupLeaderScRsp;

@Opcodes(CmdId.ChangeLineupLeaderCsReq)
public class HandlerChangeLineupLeaderCsReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] data) throws Exception {
        var req = ChangeLineupLeaderCsReq.parseFrom(data);

        var code = org.hkrpg.server.gameplay.LineupLeaders.change(session.getPlayer(), req.getSlot());
        session.send(new PacketChangeLineupLeaderScRsp(
                code == emu.lunarcore.server.packet.Retcode.SUCC ? req.getSlot() : 0, code));
    }

}
