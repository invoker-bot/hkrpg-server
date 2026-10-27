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
    assertEquals(122, input.readTag(), "Post-boss video is registered in the same ordinary dictionary");
    var boss = CodedInputStream.newInstance(input.readByteArray());
    assertEquals(48, boss.readTag());
    assertEquals(75_563_019_682_118_330L, boss.readUInt64());
    assertEquals(64, boss.readTag());
    assertEquals(3_385_157_949_291_746L, boss.readUInt64());
    assertEquals(112, boss.readTag());
    assertEquals(21, boss.readUInt32());
    assertEquals(0, boss.readTag());
    assertEquals(0, input.readTag(), "Do not invent other movie keys");
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

  @Test void postBossMovieReceivesItsOwnSourceMatchedKey() throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    assertTrue(VersionedPacketHandler.handle(session, 9, new byte[0]));
    var packet = com.google.protobuf.UnknownFieldSet.parseFrom(session.nativePackets.getFirst().body());
    var matched = packet.getField(15).getLengthDelimitedList().stream()
        .map(bytes -> {
          try { return com.google.protobuf.UnknownFieldSet.parseFrom(bytes); }
          catch (com.google.protobuf.InvalidProtocolBufferException e) { throw new AssertionError(e); }
        })
        .filter(entry -> entry.hasField(14) && entry.getField(14).getVarintList().contains(21L))
        .toList();
    assertEquals(1, matched.size(), "The post-boss movie must have an ordinary video entry");
    assertEquals(java.util.List.of(75_563_019_682_118_330L), matched.getFirst().getField(6).getVarintList());
    assertEquals(java.util.List.of(3_385_157_949_291_746L), matched.getFirst().getField(8).getVarintList());
    assertFalse(matched.getFirst().hasField(13));
  }

  @Test void postBossAudioComponentMatchesBothInstalledGenderVariants() {
    assertEquals(16_653_020_713_184_805L, audioKey("CS_Chap00_Act300_f"));
    assertEquals(16_653_020_718_949_606L, audioKey("CS_Chap00_Act300_m"));
  }

  private static long audioKey(String name) {
    long hash = 0;
    int rotation = 7;
    for (int i = 0; i < name.length(); i++)
      hash = hash * 7 + name.charAt((i + rotation) % name.length());
    return Long.remainderUnsigned(hash + rotation + 3_385_157_949_291_746L, 72_043_514_036_987_937L);
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
