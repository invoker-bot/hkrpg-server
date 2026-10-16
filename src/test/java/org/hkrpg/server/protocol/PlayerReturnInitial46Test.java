package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.CodedInputStream;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlayerReturnInitial46Test {
  static class Session extends LoginFlow46Test.Session {
    final List<ProtocolBridge.Packet> nativePackets = new ArrayList<>();
    boolean closed;

    Session() {
      setState(SessionState.ACTIVE);
      try {
        var field = emu.lunarcore.server.game.GameSession.class.getDeclaredField("protocol");
        field.setAccessible(true);
        field.set(this, bridge);
      } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    @Override public void sendClientPacket(int command, byte[] bytes) {
      nativePackets.add(new ProtocolBridge.Packet(command, bytes));
    }

    @Override public void close() { closed = true; }
  }

  @Test void actualInitialQueryNoLongerClosesTheSessionBeforeTheNextSnapshot() {
    var session = new Session();
    var input = Unpooled.wrappedBuffer(
        PacketCodec.encode(4583, new byte[] {8, 8}, new byte[0]),
        PacketCodec.encode(session.bridge.clientCommand("ContentPackageGetDataCsReq"),
            new byte[] {8, 9}, new byte[0]));
    try { session.onMessage(input); } finally { input.release(); }
    assertFalse(session.closed, "The real client's query must not terminate an authenticated session");
    assertEquals(SessionState.ACTIVE, session.getState());
    assertEquals(1, session.nativePackets.size());
    assertTrue(session.clientBodies.containsKey("ContentPackageGetDataScRsp"));
  }

  @Test void inactiveReturnProfileHasNoInventedActivityOrRewardAndUsesTheNativeResponse() throws Exception {
    var session = new Session();
    var before = new Gson().toJson(session.player.getGameplay());
    assertTrue(VersionedPacketHandler.handle(session, 4583, new byte[0]));
    var response = session.nativePackets.getFirst();
    assertEquals(4515, response.cmdId());
    var input = CodedInputStream.newInstance(response.body());
    assertEquals(24, input.readTag()); // Native EBCEGDPGHLE retcode field 3, not DressRelic field 2.
    assertEquals(0, input.readUInt32());
    assertEquals(0, input.readTag()); // Neither configuration 12 nor active return record 14.
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
  }

  @Test void unknownAndTruncatedQueryFieldsReturnAnErrorWithoutActivatingAnything() throws Exception {
    for (byte[] bytes : List.of(new byte[] {8, 1}, new byte[] {(byte) 0x80})) {
      var session = new Session();
      assertTrue(VersionedPacketHandler.handle(session, 4583, bytes));
      var input = CodedInputStream.newInstance(session.nativePackets.getFirst().body());
      assertEquals(24, input.readTag());
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), input.readUInt32());
      assertEquals(0, input.readTag());
      assertEquals(SessionState.ACTIVE, session.getState());
    }
  }

  @Test void unauthenticatedSessionsReceiveNoReturnInformation() throws Exception {
    var session = new Session();
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(session, 4583, new byte[0]));
    assertTrue(session.nativePackets.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {10010, 8110})
  void anUnsupportedOptionalCommandDoesNotDiscardTheFollowingInitialSnapshot(int command) {
    var session = new Session();
    var input = Unpooled.wrappedBuffer(
        PacketCodec.encode(command, new byte[] {8, 10}, new byte[0]),
        PacketCodec.encode(session.bridge.clientCommand("ContentPackageGetDataCsReq"),
            new byte[] {8, 11}, new byte[0]));
    try { session.onMessage(input); } finally { input.release(); }
    assertFalse(session.closed);
    assertEquals(SessionState.ACTIVE, session.getState());
    assertTrue(session.clientBodies.containsKey("ContentPackageGetDataScRsp"));
    assertTrue(session.nativePackets.isEmpty(), "Unsupported commands must never receive fake success");
  }

  @Test void malformedMappedRequestsStillTerminateTheSession() {
    var session = new Session();
    var input = Unpooled.wrappedBuffer(PacketCodec.encode(934, new byte[0], new byte[] {8, 1}));
    try { session.onMessage(input); } finally { input.release(); }
    assertTrue(session.closed);
    assertEquals(SessionState.INACTIVE, session.getState());
  }
}
