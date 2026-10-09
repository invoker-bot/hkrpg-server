package emu.lunarcore.server.packet.recv;

import emu.lunarcore.proto.TakeMailAttachmentCsReqOuterClass.TakeMailAttachmentCsReq;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Opcodes;
import emu.lunarcore.server.packet.PacketHandler;
import emu.lunarcore.server.packet.send.PacketTakeMailAttachmentScRsp;

@Opcodes(CmdId.TakeMailAttachmentCsReq)
public class HandlerTakeMailAttachmentCsReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] data) throws Exception {
        var req = TakeMailAttachmentCsReq.parseFrom(data);

        var attachments = session.getPlayer().getMailbox().claimMailAttachments(req.getMailIdList());

        session.send(new PacketTakeMailAttachmentScRsp(attachments));
    }

}
