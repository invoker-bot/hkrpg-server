package emu.lunarcore.server.packet.send;

import java.util.Arrays;
import java.util.List;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.DailyActivityInfoOuterClass.DailyActivityInfo;
import emu.lunarcore.proto.GetDailyActiveInfoScRspOuterClass.GetDailyActiveInfoScRsp;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketGetDailyActiveInfoScRsp extends BasePacket {

    public PacketGetDailyActiveInfoScRsp(Player target) {
        super(CmdId.GetDailyActiveInfoScRsp);

        target.getDailyManager().ensureDay();
        var questIdList = target.getGameplay().getDailyQuests().keySet().stream().sorted().toList();

        var data = GetDailyActiveInfoScRsp.newInstance()
                .setDailyActivePoint(target.getGameplay().getDailyPoints());

        for (int questId : questIdList) {
            data.addDailyActiveQuestIdList(questId);
        }

        for (var levelInfo : target.getDailyManager().levels()) {
            data.addDailyActiveLevelList(levelInfo);
        }

        this.setData(data);
    }
}
