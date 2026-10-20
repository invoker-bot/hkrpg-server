package emu.lunarcore.server.packet.send;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.GetTutorialScRspOuterClass.GetTutorialScRsp;
import emu.lunarcore.proto.TutorialOuterClass.Tutorial;
import emu.lunarcore.proto.TutorialStatusOuterClass.TutorialStatus;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketGetTutorialScRsp extends BasePacket {

    public PacketGetTutorialScRsp(Player player) {
        super(CmdId.GetTutorialScRsp);

        var data = GetTutorialScRsp.newInstance();

        synchronized (player) {
            for (var entry : player.getGameplay().getTutorials().snapshot(false).entrySet()) {
                var tutorial = Tutorial.newInstance()
                        .setId(entry.getKey())
                        .setStatus(entry.getValue() == 2 ? TutorialStatus.TUTORIAL_FINISH : TutorialStatus.TUTORIAL_UNLOCK);
                data.addTutorialList(tutorial);
            }
        }

        this.setData(data);
    }
}
