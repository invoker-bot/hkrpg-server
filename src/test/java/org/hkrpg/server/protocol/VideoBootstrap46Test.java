package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class VideoBootstrap46Test {
  // Native ba59a90 reads only type 2; 15b57d70 XORs this mask into the divisor.
  static long modulus(byte[] bytes) throws Exception {
    var input = CodedInputStream.newInstance(bytes);
    assertEquals(64, input.readTag());
    assertEquals(0, input.readUInt32());
    long modulus = 0; // Native MKECAGMHDAC constructor leaves the field at zero.
    for (int tag; (tag = input.readTag()) != 0; ) {
      assertEquals(122, tag, "Only the verified video bootstrap is returned; no challenge echo");
      var item = CodedInputStream.newInstance(input.readByteArray());
      assertEquals(16, item.readTag());
      assertEquals(2, item.readEnum(), "Do not invent server-check or battle-time negotiation");
      assertEquals(114, item.readTag());
      String value = item.readStringRequireUtf8();
      assertEquals("10120425825329403", value);
      assertEquals(0, item.readTag());
      assertEquals(0, modulus, "No duplicate video bootstrap entry");
      modulus = Long.parseUnsignedLong(value) ^ 0x00dc0748fcc0c8daL;
    }
    return modulus;
  }

  @Test void actualOpeningMovieCrashNumeratorCanBeDividedAfterTheBootstrapResponse() throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    assertTrue(VersionedPacketHandler.handle(session, 31, new byte[] {90, 4, 1, 2, 3, 4}));
    assertEquals(91, session.nativePackets.getFirst().cmdId());
    long divisor = modulus(session.nativePackets.getFirst().body());
    long nameHash = 0;
    for (char character : "CS_Chap00_Act210".toCharArray()) nameHash = nameHash * 11 + character;
    long numerator = nameHash + 70_220_919L;
    // Actual crash report RAX at GameAssembly+b9898b3, before `div rsi` with RSI=0.
    assertEquals(0x045ecf944c2a1cb2L, numerator);
    long decoderKey = assertDoesNotThrow(() -> Long.remainderUnsigned(numerator, divisor),
        "The real client crashed dividing this numerator by an uninitialized video modulus");
    assertEquals(72_043_514_036_987_937L, divisor);
    assertEquals(26_743_203_654_499_374L, decoderKey);
  }

  @Test void reconnectAndEmptyQueryStillSupplyTheVideoBootstrap() throws Exception {
    for (int attempt = 0; attempt < 2; attempt++) {
      var session = new PlayerReturnInitial46Test.Session();
      assertTrue(VersionedPacketHandler.handle(session, 31, new byte[0]));
      assertEquals(72_043_514_036_987_937L, modulus(session.nativePackets.getFirst().body()));
      assertEquals(SessionState.ACTIVE, session.getState());
    }
  }

  @Test void malformedOrDuplicateOpaqueChallengeDoesNotReceiveBootstrap() throws Exception {
    for (byte[] body : new byte[][] {
        {90, 2, 1}, {90, 0, 90, 0}, {(byte) 0xda, (byte) 0x80, (byte) 0x80, (byte) 0x80, 16, 0},
        {90, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 16}, {8, 1}}) {
      var session = new PlayerReturnInitial46Test.Session();
      assertTrue(VersionedPacketHandler.handle(session, 31, body));
      var input = CodedInputStream.newInstance(session.nativePackets.getFirst().body());
      assertEquals(64, input.readTag());
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), input.readUInt32());
      assertEquals(0, input.readTag());
    }
  }

  @Test void opaqueChallengeFramingAcceptsTheBoundedVarintLengthsWithoutEchoingTheValue()
      throws Exception {
    for (int length : new int[] {0, 127, 128, 16_381, 16_382}) {
      var body = new ByteArrayOutputStream();
      var output = CodedOutputStream.newInstance(body);
      output.writeByteArray(11, new byte[length]);
      output.flush();
      var session = new PlayerReturnInitial46Test.Session();
      assertTrue(VersionedPacketHandler.handle(session, 31, body.toByteArray()));
      var response = session.nativePackets.getFirst();
      assertEquals(91, response.cmdId());
      if (body.size() <= 16_384) {
        assertEquals(72_043_514_036_987_937L, modulus(response.body()));
      } else {
        var input = CodedInputStream.newInstance(response.body());
        assertEquals(64, input.readTag());
        assertEquals(Retcode.REQ_PARA_INVALID.getVal(), input.readUInt32());
        assertEquals(0, input.readTag());
      }
    }
  }

  @Test void unauthenticatedSessionReceivesNoBootstrap() throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(session, 31, new byte[0]));
    assertTrue(session.nativePackets.isEmpty());
  }
}
