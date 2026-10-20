package emu.lunarcore.server.packet.send;

import emu.lunarcore.proto.TutorialOuterClass.Tutorial;
import emu.lunarcore.proto.TutorialStatusOuterClass.TutorialStatus;
import emu.lunarcore.proto.UnlockTutorialScRspOuterClass.UnlockTutorialScRsp;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketUnlockTutorialScRsp extends BasePacket {

    public PacketUnlockTutorialScRsp(int id, org.hkrpg.server.gameplay.TutorialProgress.Result result) {
        super(CmdId.UnlockTutorialScRsp);

        var data = UnlockTutorialScRsp.newInstance().setRetcode(result.retcode().getVal());
        if (result.status() > 0) data.setTutorial(Tutorial.newInstance()
                        .setId(id)
                        .setStatus(result.status() == 2 ? TutorialStatus.TUTORIAL_FINISH : TutorialStatus.TUTORIAL_UNLOCK)
                        );

        this.setData(data);
    }
}
