package emu.lunarcore.server.packet.send;

import emu.lunarcore.proto.FinishTutorialGuideScRspOuterClass.FinishTutorialGuideScRsp;
import emu.lunarcore.proto.TutorialGuideOuterClass.TutorialGuide;
import emu.lunarcore.proto.TutorialStatusOuterClass.TutorialStatus;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketFinishTutorialGuideScRsp extends BasePacket {

    public PacketFinishTutorialGuideScRsp(int id, org.hkrpg.server.gameplay.TutorialProgress.Result result) {
        super(CmdId.FinishTutorialGuideScRsp);

        var data = FinishTutorialGuideScRsp.newInstance().setRetcode(result.retcode().getVal());
        result.rewards().forEach(item -> data.getMutableReward().addItemList(item.toProto()));
        if (result.status() > 0) {
            var tutorial = TutorialGuide.newInstance()
                    .setId(id)
                    .setStatus(result.status() == 2 ? TutorialStatus.TUTORIAL_FINISH : TutorialStatus.TUTORIAL_UNLOCK);

            data.setTutorialGuide(tutorial);
        }

        this.setData(data);
    }
}
