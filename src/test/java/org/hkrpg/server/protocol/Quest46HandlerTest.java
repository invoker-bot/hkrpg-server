package org.hkrpg.server.protocol;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Quest46HandlerTest {
    static class Session extends GameSession {
        final Player player = new Player() { @Override public void save() {} };
        DynamicMessage response;
        Session() { super(null); setState(SessionState.ACTIVE); }
        @Override public Player getPlayer() { return player; }
        @Override public ProtocolBridge getProtocol() { return ProtocolBridge.forVersion("4.6.0"); }
        @Override public void send(BasePacket packet) {}
        @Override public void sendClientPacket(String name, byte[] bytes) {
            try { response = getProtocol().clientSchema().parse(name, bytes); }
            catch (java.io.IOException ex) { throw new AssertionError(ex); }
        }
    }

    @Test void duplicateBatchIdsAndRetriesCannotReclaimQuest() throws Exception {
        int id = 1999000030;
        var gson = new Gson();
        var definition = gson.fromJson("{\"QuestID\":" + id + ",\"QuestType\":1,\"FinishWayID\":" + id + ",\"UnlockType\":\"AutoUnlock\"}", QuestExcel.class);
        var way = gson.fromJson("{\"ID\":" + id + ",\"FinishType\":\"AutoFinish\",\"Progress\":1}", FinishWayExcel.class);
        GameData.getQuestExcelMap().add(definition);
        GameData.getFinishWayExcelMap().add(way);
        var session = new Session();
        session.player.getQuestManager().refreshUnlocks();
        var type = session.getProtocol().clientSchema().message("TakeQuestRewardCsReq");
        var request = DynamicMessage.newBuilder(type).addRepeatedField(type.findFieldByName("ODJIJFJCHHB"), id)
            .addRepeatedField(type.findFieldByName("ODJIJFJCHHB"), id).build().toByteArray();
        assertTrue(VersionedPacketHandler.handle(session, 905, request));
        assertEquals(QuestState.Phase.CLOSED, session.player.getQuestStates().get(id).getPhase());
        var responseType = session.response.getDescriptorForType();
        assertEquals(List.of(id), session.response.getField(responseType.findFieldByName("succ_quest_id_list")));
        assertTrue(VersionedPacketHandler.handle(session, 905, request));
        assertEquals(1100, session.response.getField(responseType.findFieldByName("retcode")));
        assertEquals(List.of(), session.response.getField(responseType.findFieldByName("succ_quest_id_list")));
    }

    @Test void unauthenticatedBatchDoesNotMutatePlayer() throws Exception {
        var session = new Session();
        session.setState(SessionState.WAITING_FOR_TOKEN);
        assertTrue(VersionedPacketHandler.handle(session, 905, new byte[0]));
        assertNull(session.response);
        assertTrue(session.player.getQuestStates().isEmpty());
    }
}
