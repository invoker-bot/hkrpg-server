package org.hkrpg.server.protocol;

import emu.lunarcore.server.game.*;
import emu.lunarcore.server.packet.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolFailureTest {
    static class Session extends GameSession {
        int closed;
        Session() { super(null); setState(SessionState.ACTIVE); }
        @Override public void close() { closed++; }
    }
    @Opcodes(CmdId.GetQuestDataCsReq)
    public static class UnsupportedResponse extends PacketHandler {
        @Override public void handle(GameSession session, byte[] bytes) { throw new ProtocolException("Unmapped response field"); }
    }
    @Test void unsupportedResponseClosesPartiallyInitializedSession() {
        var handlers = new GameServerPacketHandler();
        handlers.registerPacketHandler(UnsupportedResponse.class);
        var session = new Session();
        handlers.handle(session, CmdId.GetQuestDataCsReq, new byte[0]);
        assertEquals(1, session.closed);
        assertEquals(SessionState.INACTIVE, session.getState());
    }
}
