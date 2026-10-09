package emu.lunarcore.server.packet.recv;

import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Opcodes;
import emu.lunarcore.server.packet.PacketHandler;
import org.hkrpg.server.gameplay.ChallengeLeave;

@Opcodes(CmdId.LeaveChallengeCsReq)
public class HandlerLeaveChallengeCsReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] data) throws Exception {
        ChallengeLeave.leave(session.getPlayer());

        // Send rsp packet to keep the client happy
        session.send(CmdId.LeaveChallengeScRsp);
    }

}
