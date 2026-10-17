package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.GameConstants;
import emu.lunarcore.proto.GetChallengePeakDataScRspOuterClass.GetChallengePeakDataScRsp;
import emu.lunarcore.server.game.GameServerPacketHandler;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.SessionState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChallengePeakInitial46Test {
  private final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  @Test void actualGroupAndErrorCodeUseNamed46FieldsWithoutInventingProgress() throws Exception {
    var legacy = GetChallengePeakDataScRsp.newInstance().setCurChallengePeakGroupId(6).setRetcode(100);
    var bytes = legacy.toByteArray();
    var packet = bridge.outbound(CmdId.GetChallengePeakDataScRsp, bytes);
    assertEquals(8906, packet.cmdId());
    var reply = bridge.clientSchema().parse("GetChallengePeakDataScRsp", packet.body());
    assertEquals(6, InitialData46Test.field(reply, "current_peak_group_id"));
    assertEquals(100, InitialData46Test.field(reply, "retcode"));
    assertEquals(0, reply.getRepeatedFieldCount(reply.getDescriptorForType().findFieldByNumber(5)));
    assertArrayEquals(bytes, ProtocolBridge.forVersion("4.2.0")
        .outbound(CmdId.GetChallengePeakDataScRsp, bytes).body());
  }

  @Test void realSnapshotHandlerDoesNotDisconnectTheAuthenticatedSession() throws Exception {
    class Session extends GameSession {
      boolean closed;
      final List<ProtocolBridge.Packet> writes = new ArrayList<>();
      Session() { super(null); setState(SessionState.ACTIVE); }
      @Override public ProtocolBridge getProtocol() { return bridge; }
      @Override public void close() { closed = true; }
      @Override public void send(BasePacket packet) {
        writes.add(bridge.outbound(packet.getCmdId(), packet.getData().toByteArray()));
      }
    }
    var session = new Session();
    new GameServerPacketHandler().handle(session, CmdId.GetChallengePeakDataCsReq, new byte[0]);
    assertFalse(session.closed, "The observed follow-up query must not disconnect initialization");
    assertEquals(SessionState.ACTIVE, session.getState());
    assertEquals(1, session.writes.size());
    var reply = bridge.clientSchema().parse("GetChallengePeakDataScRsp", session.writes.getFirst().body());
    assertEquals(GameConstants.CHALLENGE_PEAK_GROUP_ID, InitialData46Test.field(reply, "current_peak_group_id"));
  }
}
