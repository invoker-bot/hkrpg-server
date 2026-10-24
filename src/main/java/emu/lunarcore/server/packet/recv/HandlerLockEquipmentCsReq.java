package emu.lunarcore.server.packet.recv;

import emu.lunarcore.proto.LockEquipmentCsReqOuterClass.LockEquipmentCsReq;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Opcodes;
import emu.lunarcore.server.packet.PacketHandler;

@Opcodes(CmdId.LockEquipmentCsReq)
public class HandlerLockEquipmentCsReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] data) throws Exception {
        var req = LockEquipmentCsReq.parseFrom(data);

        var ids = new java.util.ArrayList<Integer>();
        for (int id : req.getEquipmentIdList()) ids.add(id);
        var code = org.hkrpg.server.gameplay.EquipmentProtection.lock(session.getPlayer(), ids, req.getIsProtected(), emu.lunarcore.game.enums.ItemMainType.Equipment);
        var response = new emu.lunarcore.server.packet.BasePacket(CmdId.LockEquipmentScRsp);
        response.setData(emu.lunarcore.proto.LockEquipmentScRspOuterClass.LockEquipmentScRsp.newInstance().setRetcode(code.getVal()));
        session.send(response);
    }

}
