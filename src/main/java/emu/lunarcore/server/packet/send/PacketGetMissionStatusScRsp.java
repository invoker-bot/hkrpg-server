package emu.lunarcore.server.packet.send;

import emu.lunarcore.proto.GetMissionStatusCsReqOuterClass.GetMissionStatusCsReq;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.GetMissionStatusScRspOuterClass.GetMissionStatusScRsp;
import emu.lunarcore.proto.MissionOuterClass.Mission;
import emu.lunarcore.proto.MissionStatusOuterClass.MissionStatus;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketGetMissionStatusScRsp extends BasePacket {

    public PacketGetMissionStatusScRsp(GetMissionStatusCsReq req) {
        this(null,req);
    }
    public PacketGetMissionStatusScRsp(Player player,GetMissionStatusCsReq req) {
        super(CmdId.GetMissionStatusScRsp);

        var data = GetMissionStatusScRsp.newInstance();

        for (int missionId : req.getMainMissionIdList()) {
            if (player != null && player.getMissionManager().finished(missionId)) {
                data.addFinishedMainMissionIdList(missionId);
            } else {
                // The client resolves a pending dependency for each returned ID. Omitting a
                // negative answer leaves menu/story initialization waiting even after reconnect.
                data.addUnfinishedMainMissionIdList(missionId);
            }
        }

        for (int missionId : req.getSubMissionIdList()) {
            var state=player==null?null:player.getMissionManager().sub(missionId);
            boolean retired = player != null
                    && org.hkrpg.server.gameplay.MissionSnapshotRecords.retiredSub(player, missionId);
            var mission = Mission.newInstance()
                    .setId(missionId)
                    .setProgress(state==null || retired?0:state.getProgress())
                    .setStatus(state==null || retired?MissionStatus.MISSION_NONE:state.getPhase()==emu.lunarcore.game.quest.QuestState.Phase.DOING?MissionStatus.MISSION_DOING:MissionStatus.MISSION_FINISH);

            data.addSubMissionStatusList(mission);
        }

        this.setData(data);
    }
}
