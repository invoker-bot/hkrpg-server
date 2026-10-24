package emu.lunarcore.server.packet.recv;

import emu.lunarcore.proto.LockRelicCsReqOuterClass.LockRelicCsReq;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Opcodes;
import emu.lunarcore.server.packet.PacketHandler;

@Opcodes(CmdId.LockRelicCsReq)
public class HandlerLockRelicCsReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] data) throws Exception {
        var req = LockRelicCsReq.parseFrom(data);

        var ids = new java.util.ArrayList<Integer>();
        for (int id : req.getRelicIdList()) ids.add(id);
        var code = org.hkrpg.server.gameplay.EquipmentProtection.lock(session.getPlayer(), ids, req.getIsProtected(), emu.lunarcore.game.enums.ItemMainType.Relic);
        var response = new emu.lunarcore.server.packet.BasePacket(CmdId.LockRelicScRsp);
        response.setData(emu.lunarcore.proto.LockRelicScRspOuterClass.LockRelicScRsp.newInstance().setRetcode(code.getVal()));
        session.send(response);
    }

}
