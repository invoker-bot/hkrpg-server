package emu.lunarcore.server.packet.recv;

import emu.lunarcore.game.trial.TrialInstance;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Opcodes;
import emu.lunarcore.server.packet.PacketHandler;

@Opcodes(CmdId.LeaveTrialActivityCsReq)
public class HandlerLeaveTrialActivityCsReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] data) throws Exception {
        // Get trial instance
        TrialInstance trial = session.getPlayer().getInstance(TrialInstance.class);
        if (trial != null) {
            var result = session.getPlayer().getTrialManager().leaveTrial(trial.getStageId());
            if (result != emu.lunarcore.server.packet.Retcode.SUCC)
                throw new org.hkrpg.server.protocol.ProtocolException("Trial return failed: " + result);
        }

        // Leave
        session.send(CmdId.LeaveTrialActivityScRsp);
    }

}
