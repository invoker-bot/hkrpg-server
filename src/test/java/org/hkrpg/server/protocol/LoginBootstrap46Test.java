package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.CodedInputStream;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LoginBootstrap46Test {
  // Matched Honey fe00bdb Raw command pairs and response retcode field numbers.
  static Stream<Arguments> queries() {
    return Stream.of(
        Arguments.of(5697, 5653, 15), Arguments.of(31, 91, 8),
        Arguments.of(7172, 7173, 6), Arguments.of(8034, 8025, 11),
        Arguments.of(8110, 8116, 7), Arguments.of(96, 47, 8),
        Arguments.of(9804, 9776, 4), Arguments.of(8991, 8982, 14),
        Arguments.of(10010, 10016, 12), Arguments.of(9894, 9866, 13),
        Arguments.of(9490, 9496, 9), Arguments.of(78, 4, 9),
        Arguments.of(9, 27, 5), Arguments.of(522, 549, 1),
        Arguments.of(2922, 2949, 6), Arguments.of(3405, 3475, 8),
        Arguments.of(5734, 5725, 4), Arguments.of(5834, 5825, 10),
        Arguments.of(8680, 8580, 9),
        Arguments.of(8948, 8914, 13), Arguments.of(9504, 9548, 8),
        Arguments.of(6029, 6017, 2), Arguments.of(9990, 9996, 9));
  }

  @ParameterizedTest @MethodSource("queries")
  void everyObservedInitialQueryCompletesWithoutInventingProgress(int request, int response, int ret)
      throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    var before = new Gson().toJson(session.player.getGameplay());
    byte[] body = switch (request) {
      case 5697 -> new byte[] {106, 2, 1, 2}; // Packed query selectors.
      case 31 -> new byte[] {90, 4, 1, 2, 3, 4}; // Synthetic security request, never logged.
      case 8110 -> new byte[] {48, 1}; // Native request field 6.
      default -> new byte[0];
    };
    assertTrue(VersionedPacketHandler.handle(session, request, body),
        "An observed startup query must have an explicit response contract: " + request);
    assertEquals(1, session.nativePackets.size());
    var packet = session.nativePackets.getFirst();
    assertEquals(response, packet.cmdId());
    var input = CodedInputStream.newInstance(packet.body());
    assertEquals(ret << 3, input.readTag());
    assertEquals(0, input.readUInt32());
    if (request == 9) {
      VideoVersionKeys46Test.openingVideoKey(packet.body());
    } else if (request == 31) {
      assertEquals(72_043_514_036_987_937L, VideoBootstrap46Test.modulus(packet.body()));
    } else if (request == 8034 || request == 8680 || request == 6029) {
      var type = switch (request) {
        case 8034 -> "TrainPartyGetDataScRsp";
        case 8680 -> "GridFightGetDataScRsp";
        default -> "RogueTournQueryScRsp";
      };
      LoginBootstrapNested46Test.noInventedProgress(session.bridge.clientSchema().parse(type, packet.body()));
    } else assertEquals(0, input.readTag(),
        "Inactive initial profiles must not grant progression, purchases, keys or rewards");
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
    assertEquals(SessionState.ACTIVE, session.getState());
  }

  @ParameterizedTest @MethodSource("queries")
  void invalidBodiesFinishWithParameterErrorAndDoNotBecomeSuccess(int request, int response, int ret)
      throws Exception {
    for (byte[] body : new byte[][] {new byte[] {8, 1}, new byte[] {(byte) 0x80}}) {
      var session = new PlayerReturnInitial46Test.Session();
      assertTrue(VersionedPacketHandler.handle(session, request, body));
      assertEquals(response, session.nativePackets.getFirst().cmdId());
      var input = CodedInputStream.newInstance(session.nativePackets.getFirst().body());
      assertEquals(ret << 3, input.readTag());
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), input.readUInt32());
      if (request == 8034 || request == 8680 || request == 6029)
        assertTrue(input.skipField(input.readTag()));
      assertEquals(0, input.readTag());
    }
  }

  @ParameterizedTest @MethodSource("queries")
  void preLoginQueriesCannotReadPlayerData(int request, int response, int ret) throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    session.setState(SessionState.WAITING_FOR_LOGIN);
    assertTrue(VersionedPacketHandler.handle(session, request, new byte[0]));
    assertTrue(session.nativePackets.isEmpty());
  }

  @Test void completeBootstrapBatchStillProcessesTheFollowingSnapshot() {
    var session = new PlayerReturnInitial46Test.Session();
    var input = io.netty.buffer.Unpooled.buffer();
    queries().forEach(args -> input.writeBytes(emu.lunarcore.server.packet.PacketCodec.encode(
        (Integer) args.get()[0], new byte[0], new byte[0])));
    input.writeBytes(emu.lunarcore.server.packet.PacketCodec.encode(6134, new byte[0], new byte[0]));
    input.writeBytes(emu.lunarcore.server.packet.PacketCodec.encode(
        session.bridge.clientCommand("ContentPackageGetDataCsReq"), new byte[0], new byte[0]));
    try { session.onMessage(input); } finally { input.release(); }
    assertFalse(session.closed);
    assertEquals(23, session.nativePackets.size());
    assertTrue(session.clientBodies.containsKey("GetAllServerPrefsDataScRsp"));
    assertTrue(session.clientBodies.containsKey("ContentPackageGetDataScRsp"));
    assertEquals(SessionState.ACTIVE, session.getState());
  }
}
