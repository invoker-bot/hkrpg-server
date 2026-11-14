package emu.lunarcore.server.packet.recv;

import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Opcodes;
import emu.lunarcore.server.packet.PacketHandler;
import emu.lunarcore.server.packet.send.PacketGetQuestDataScRsp;

@Opcodes(CmdId.GetQuestDataCsReq)
public class HandlerGetQuestDataCsReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] data) throws Exception {
        // The snapshot refreshes unlocks to a fixed point after applying the daily rollover.
        synchronized(session.getPlayer()){
            org.hkrpg.server.gameplay.ActivityReplayScope.in(session.getPlayer().getGameplay(),50022,()->{
                var snapshot=new PacketGetQuestDataScRsp(session.getPlayer());
                session.getActivityReplayLeases().viewed(50022,org.hkrpg.server.gameplay.ActivityReplayScope.identity(session.getPlayer().getGameplay(),50022));
                session.send(snapshot);return null;
            });
        }
    }
}
