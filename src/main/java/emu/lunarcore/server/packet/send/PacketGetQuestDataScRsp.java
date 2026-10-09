package emu.lunarcore.server.packet.send;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.GetQuestDataScRspOuterClass.GetQuestDataScRsp;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketGetQuestDataScRsp extends BasePacket {
    public PacketGetQuestDataScRsp(Player player) {
        super(CmdId.GetQuestDataScRsp);
        var data = GetQuestDataScRsp.newInstance();
        player.getQuestManager().states().forEach(state -> data.addQuestList(state.toProto()));
        setData(data);
    }
}
