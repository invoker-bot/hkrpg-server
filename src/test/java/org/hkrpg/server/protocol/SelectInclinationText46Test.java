package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.proto.SelectInclinationTextCsReqOuterClass.SelectInclinationTextCsReq;
import emu.lunarcore.proto.SelectInclinationTextScRspOuterClass.SelectInclinationTextScRsp;
import emu.lunarcore.server.game.GameServerPacketHandler;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.server.packet.recv.HandlerPlayerHeartBeatCsReq;
import emu.lunarcore.server.packet.recv.HandlerSelectInclinationTextCsReq;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SelectInclinationText46Test {
  private final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  // Independent wire numbers from the pinned CN4.6 TalkEvent schema: request 4, reply 11/15.
  private static byte[] scalar(int number, int value) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var writer = CodedOutputStream.newInstance(bytes);
    writer.writeUInt32(number, value);
    writer.flush();
    return bytes.toByteArray();
  }

  @ParameterizedTest
  @ValueSource(ints = {100000417, 100000419, 100001119})
  void namedSentenceReachesTheLegacyHandlerWithoutBecomingZero(int id) throws Exception {
    var converted = bridge.inbound(2183, scalar(4, id));
    assertEquals(CmdId.SelectInclinationTextCsReq, converted.cmdId());
    assertEquals(id, SelectInclinationTextCsReq.parseFrom(converted.body()).getInclinationTextId());
  }

  @Test void responseEchoAndErrorUseTheirOwn46FieldNumbers() throws Exception {
    var packet = SelectInclinationTextScRsp.newInstance().setInclinationTextId(100000417).setRetcode(7);
    var converted = bridge.outbound(CmdId.SelectInclinationTextScRsp, packet.toByteArray());
    assertEquals(2115, converted.cmdId());
    var expected = new ByteArrayOutputStream();
    expected.writeBytes(scalar(11, 100000417));
    expected.writeBytes(scalar(15, 7));
    assertArrayEquals(expected.toByteArray(), converted.body());
  }

  static final class Session extends GameSession {
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    final List<ProtocolBridge.Packet> responses = new ArrayList<>();
    final Player player = new Player();
    boolean closed;
    Session() { super(null); setState(SessionState.ACTIVE); }
    @Override public ProtocolBridge getProtocol() { return bridge; }
    @Override public Player getPlayer() { return player; }
    @Override public void send(BasePacket packet) {
      responses.add(bridge.outbound(packet.getCmdId(), packet.getData().toByteArray()));
    }
    @Override public void close() { closed = true; }
  }

  private static GameServerPacketHandler handlers() {
    return new GameServerPacketHandler() {
      @Override public void registerHandlers() {
        registerPacketHandler(HandlerSelectInclinationTextCsReq.class);
        registerPacketHandler(HandlerPlayerHeartBeatCsReq.class);
      }
    };
  }

  @Test void acknowledgedChoiceAndRetryKeepTheSessionReadyForTheNextRequest() throws Exception {
    var session = new Session();
    var handlers = handlers();
    String before = new Gson().toJson(session.player.getGameplay());
    for (int id : new int[] {100000417, 100000417, 100000419}) {
      var request = bridge.inbound(2183, scalar(4, id));
      handlers.handle(session, request.cmdId(), request.body());
      assertFalse(session.closed);
      assertEquals(SessionState.ACTIVE, session.getState());
      var reply = session.responses.getLast();
      assertEquals(2115, reply.cmdId());
      assertArrayEquals(scalar(11, id), reply.body());
    }
    handlers.handle(session, CmdId.PlayerHeartBeatCsReq, new byte[0]);
    assertEquals(62, session.responses.getLast().cmdId());
    assertFalse(session.closed);
    assertEquals(before, new Gson().toJson(session.player.getGameplay()),
        "A dialogue acknowledgement must not auto-finish missions or grant rewards");
  }

  @Test void unauthenticatedChoiceCannotReachTheCoreAcknowledgement() throws Exception {
    var session = new Session();
    session.setState(SessionState.WAITING_FOR_LOGIN);
    var request = bridge.inbound(2183, scalar(4, 100000417));
    handlers().handle(session, request.cmdId(), request.body());
    assertTrue(session.responses.isEmpty());
  }

  @Test void malformedAndLegacyNumberedRequestsRemainRejectedOn46() throws Exception {
    assertThrows(ProtocolException.class, () -> bridge.inbound(2183, scalar(9, 100000417)));
    assertThrows(ProtocolException.class, () -> bridge.inbound(2183, new byte[] {32, (byte) 128}));
  }

  @Test void legacyCommandAndBytesRemainUnchanged() throws Exception {
    var legacy = ProtocolBridge.forVersion("4.2.0");
    byte[] body = scalar(9, 100000417);
    var request = legacy.inbound(CmdId.SelectInclinationTextCsReq, body);
    assertEquals(CmdId.SelectInclinationTextCsReq, request.cmdId());
    assertArrayEquals(body, request.body());
    var response = legacy.outbound(CmdId.SelectInclinationTextScRsp, body);
    assertEquals(CmdId.SelectInclinationTextScRsp, response.cmdId());
    assertArrayEquals(body, response.body());
  }
}
