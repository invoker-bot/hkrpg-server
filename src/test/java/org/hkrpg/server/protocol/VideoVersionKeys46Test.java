package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.CodedInputStream;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class VideoVersionKeys46Test {
  // Independently matched native consumer: ordinary list wire 15, id 14, legacy key 6.
  static void openingVideoKey(byte[] bytes) throws Exception {
    var input = CodedInputStream.newInstance(bytes);
    assertEquals(40, input.readTag());
    assertEquals(0, input.readUInt32());
    assertEquals(122, input.readTag(), "Encrypted opening video must receive its ordinary video key");
    var key = CodedInputStream.newInstance(input.readByteArray());
    assertEquals(48, key.readTag());
    assertEquals(70_220_919L, key.readUInt64());
    assertEquals(112, key.readTag());
    assertEquals(8, key.readUInt32());
    assertEquals(0, key.readTag(), "Do not invent modern encryption components");
    assertEquals(0, input.readTag(), "Only the source-matched opening video is supported");
  }

  @Test void encryptedOpeningVideoGetsItsSourceMatchedKeyWithoutChangingPlayerState() throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    var before = new Gson().toJson(session.player.getGameplay());
    assertTrue(VersionedPacketHandler.handle(session, 9, new byte[0]));
    assertEquals(27, session.nativePackets.getFirst().cmdId());
    openingVideoKey(session.nativePackets.getFirst().body());
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
    assertEquals(SessionState.ACTIVE, session.getState());
  }

  @Test void sameOpeningKeyIsAvailableAfterReconnection() throws Exception {
    for (int attempt = 0; attempt < 2; attempt++) {
      var session = new PlayerReturnInitial46Test.Session();
      var input = Unpooled.wrappedBuffer(
          PacketCodec.encode(9, new byte[0], new byte[0]),
          PacketCodec.encode(session.bridge.clientCommand("ContentPackageGetDataCsReq"),
              new byte[0], new byte[0]));
      try { session.onMessage(input); } finally { input.release(); }
      assertFalse(session.closed);
      openingVideoKey(session.nativePackets.getFirst().body());
      assertTrue(session.clientBodies.containsKey("ContentPackageGetDataScRsp"));
    }
  }

  @Test void invalidVideoKeyRequestsDoNotReceiveTheCatalog() throws Exception {
    for (byte[] bytes : new byte[][] {new byte[] {8, 1}, new byte[] {(byte) 0x80}, new byte[16_385]}) {
      var session = new PlayerReturnInitial46Test.Session();
      assertTrue(VersionedPacketHandler.handle(session, 9, bytes));
      var input = CodedInputStream.newInstance(session.nativePackets.getFirst().body());
      assertEquals(40, input.readTag());
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), input.readUInt32());
      assertEquals(0, input.readTag());
    }
  }

  @Test void preLoginRequestsDoNotReadTheCatalog() throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(session, 9, new byte[0]));
    assertTrue(session.nativePackets.isEmpty());
  }
}
