package emu.lunarcore.server.packet.send;

import java.util.Arrays;
import java.util.List;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.DailyActiveInfoNotifyOuterClass.DailyActiveInfoNotify;
import emu.lunarcore.proto.DailyActivityInfoOuterClass.DailyActivityInfo;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketDailyActiveInfoNotify extends BasePacket {

    public PacketDailyActiveInfoNotify(Player player) {
        super(CmdId.DailyActiveInfoNotify);

        player.getDailyManager().ensureDay();
        var questIdList = player.getGameplay().getDailyQuests().keySet().stream().sorted().toList();

        var data = DailyActiveInfoNotify.newInstance()
                .setDailyActivePoint(player.getGameplay().getDailyPoints());

        for (int questId : questIdList) {
            data.addDailyActiveQuestIdList(questId);
        }

        for (var levelInfo : player.getDailyManager().levels()) {
            data.addDailyActiveLevelList(levelInfo);
        }

        this.setData(data);
    }
}
