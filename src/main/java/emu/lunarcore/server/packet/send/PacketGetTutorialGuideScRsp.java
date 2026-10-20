package emu.lunarcore.server.packet.send;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.GetTutorialGuideScRspOuterClass.GetTutorialGuideScRsp;
import emu.lunarcore.proto.TutorialGuideOuterClass.TutorialGuide;
import emu.lunarcore.proto.TutorialStatusOuterClass.TutorialStatus;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketGetTutorialGuideScRsp extends BasePacket {

    public PacketGetTutorialGuideScRsp(Player player) {
        super(CmdId.GetTutorialGuideScRsp);

        var data = GetTutorialGuideScRsp.newInstance();

        synchronized (player) {
            for (var entry : player.getGameplay().getTutorials().snapshot(true).entrySet()) {
                var tutorial = TutorialGuide.newInstance()
                        .setId(entry.getKey())
                        .setStatus(entry.getValue() == 2 ? TutorialStatus.TUTORIAL_FINISH : TutorialStatus.TUTORIAL_UNLOCK);
                data.addTutorialGuideList(tutorial);
            }
        }

        this.setData(data);
    }
}
