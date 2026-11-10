package emu.lunarcore.server.packet.recv;

import emu.lunarcore.data.GameData;
import emu.lunarcore.proto.FinishQuestCsReqOuterClass.FinishQuestCsReq;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Opcodes;
import emu.lunarcore.server.packet.PacketHandler;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketFinishQuestScRsp;
import org.hkrpg.server.GameDiagnostics;

@Opcodes(CmdId.FinishQuestCsReq)
public class HandlerFinishQuestCsReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] data) throws Exception {
        var req = FinishQuestCsReq.parseFrom(data);
        var manager = session.getPlayer().getQuestManager();
        var retcode = manager.finishByClient(req.getQuestId());
        var definition = GameData.getQuestExcelMap().get(req.getQuestId());
        if (retcode == Retcode.SUCC && !session.getProtocol().experimental() && definition.getQuestType() == 3) {
            retcode = manager.claim(req.getQuestId()).retcode();
        }
        GameDiagnostics.outcome(session, "FinishQuestCsReq", req.getQuestId(), retcode,
                "client-quest", "group=" + req.getGroupId() + " prop=" + req.getPropId());
        session.send(new PacketFinishQuestScRsp(retcode));
    }

}
