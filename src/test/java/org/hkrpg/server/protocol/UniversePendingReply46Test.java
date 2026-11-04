package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.SessionState;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Stream;
import org.hkrpg.server.gameplay.GameplayState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Exercise real session framing: a saved reward alone cannot complete the client's click. */
class UniversePendingReply46Test {
  final UniversePendingWire46Test fixture = new UniversePendingWire46Test();
  final Gson gson = new Gson();
  final Session session = new Session();

  class Session extends GameSession {
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    final List<PacketCodec.Frame> sent = new ArrayList<>();

    Session() { super(null); setState(SessionState.ACTIVE); }
    @Override public ProtocolBridge getProtocol() { return bridge; }
    @Override public Player getPlayer() { return fixture.session.getPlayer(); }
    @Override protected void writeTransport(byte[] bytes) {
      var input = Unpooled.wrappedBuffer(bytes);
      try {
        while (input.isReadable()) sent.add(PacketCodec.decodeNext(input));
      } finally { input.release(); }
    }

    void request(byte[] header, byte[] body) {
      sent.clear();
      var input = Unpooled.wrappedBuffer(PacketCodec.encode(5676, header, body));
      try { onMessage(input); } finally { input.release(); }
      assertEquals(SessionState.ACTIVE, getState());
    }
  }

  @BeforeEach void setup() throws Exception { fixture.prepare(); }
  @AfterEach void cleanup() { fixture.restore(); }

  void domain(String kind) throws Exception {
    fixture.domain(kind);
    session.getPlayer().setSession(session);
    var online = Player.class.getDeclaredField("loggedIn");
    online.setAccessible(true);
    online.setBoolean(session.getPlayer(), true);
  }

  static byte[] header(int id) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var wire = com.google.protobuf.CodedOutputStream.newInstance(bytes);
    wire.writeUInt32(1, id); wire.flush();
    return bytes.toByteArray();
  }

  void reply(byte[] header, boolean success) throws Exception {
    var replies = session.sent.stream().filter(f -> f.cmdId() == 5657).toList();
    assertEquals(1, replies.size(), "Each native pending action must have exactly one reply");
    var frame = replies.getFirst();
    var body = session.bridge.clientSchema().parse("HandleRogueCommonPendingActionScRsp", frame.body());
    assertEquals(success, Gameplay46.number(body, "retcode") == 0);
    assertArrayEquals(header, frame.header(), "The client must match this click to its saved result");
    for (var notification : session.sent)
      if (notification != frame) assertEquals(0, notification.header().length);
  }

  static Stream<Arguments> operations() {
    return Stream.of("blessing", "curio", "reroll").flatMap(kind ->
        Stream.of(1, 73597, Integer.MIN_VALUE, -1).map(id -> Arguments.of(kind, id)));
  }

  @ParameterizedTest @MethodSource("operations")
  void acceptedAndRepeatedActionsCompleteTheirOwnClickWithoutDuplicatingTheReward(String kind, int id)
      throws Exception {
    domain(kind);
    byte[] body = fixture.request(kind, "valid"), first = header(id);
    session.request(first, body);
    reply(first, true);
    var saved = gson.toJsonTree(session.getPlayer().getGameplay());
    // Restore the saved gameplay, then reject the stale selection with its new click identity.
    var state = Player.class.getDeclaredField("gameplay");
    state.setAccessible(true);
    state.set(session.getPlayer(), gson.fromJson(saved, GameplayState.class));
    byte[] next = header(id + 17);
    session.request(next, body);
    reply(next, false);
    assertEquals(saved, gson.toJsonTree(session.getPlayer().getGameplay()));
    session.sendClientPacket("PlayerSyncScNotify", new byte[0]);
    assertEquals(0, session.sent.getLast().header().length);
  }

  @ParameterizedTest @ValueSource(strings = {"blessing", "curio", "reroll"})
  void malformedActionsReplyToTheirClickAndLeaveTheMenuAndWalletIntact(String kind) throws Exception {
    domain(kind);
    var before = gson.toJsonTree(session.getPlayer().getGameplay());
    var id = header(73597);
    session.request(id, fixture.request(kind, "mergedOneof"));
    reply(id, false);
    assertEquals(before, gson.toJsonTree(session.getPlayer().getGameplay()));
  }

  @Test void headerlessNativeSelectionKeepsTheExistingTransportContract() throws Exception {
    domain("blessing");
    session.request(new byte[0], fixture.request("blessing", "valid"));
    reply(new byte[0], true);
  }

  @Test void nativePendingPairDoesNotChangeLegacyResponseRouting() {
    assertEquals(0, ProtocolBridge.forVersion("4.2.0").clientResponseCommand(5676));
  }
}
