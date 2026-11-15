package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.CodedInputStream;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class LoginBootstrap46Test {
  @BeforeAll static void loadShopCatalog() {
    assertTrue(org.hkrpg.server.gameplay.FreeDreamCatalog.load(java.nio.file.Path.of("resources/4.6")));
  }
  // Matched Honey fe00bdb Raw command pairs and response retcode field numbers.
  static Stream<Arguments> queries() {
    return Stream.of(
        Arguments.of(5697, 5653, 15), Arguments.of(31, 91, 8),
        Arguments.of(7172, 7173, 6), Arguments.of(8034, 8025, 11),
        Arguments.of(8110, 8116, 7), Arguments.of(96, 47, 8),
        Arguments.of(9804, 9776, 4), Arguments.of(8991, 8982, 14),
        Arguments.of(10010, 10016, 12), Arguments.of(9894, 9866, 13),
        Arguments.of(9490, 9496, 9),
        Arguments.of(9, 27, 5), Arguments.of(522, 549, 1),
        Arguments.of(2922, 2949, 6), Arguments.of(3405, 3475, 8),
        Arguments.of(5734, 5725, 4), Arguments.of(5834, 5825, 10),
        Arguments.of(8680, 8580, 9),
        Arguments.of(8948, 8914, 13), Arguments.of(9504, 9548, 8),
        Arguments.of(6029, 6017, 2), Arguments.of(9990, 9996, 9),
        Arguments.of(3758, 3756, 3));
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
      case 3758 -> new byte[] {32, 1}; // Initial train visitor registration.
      default -> new byte[0];
    };
    assertTrue(VersionedPacketHandler.handle(session, request, body),
        "An observed startup query must have an explicit response contract: " + request);
    if (request == 96) {
      var data = session.bridge.clientSchema().parse("QueryProductInfoScRsp",
          session.clientBodies.get("QueryProductInfoScRsp"));
      assertEquals(0, InitialData46Test.field(data, "retcode"));
      assertEquals(6, data.getRepeatedFieldCount(data.getDescriptorForType().findFieldByName("product_list")));
      assertEquals(before, new Gson().toJson(session.player.getGameplay()));
      assertEquals(0, session.player.getMcoin());
      assertEquals(SessionState.ACTIVE, session.getState());
      return;
    }
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
    } else if (request == 7172) {
      assertEquals(90, input.readTag()); // Present empty badge snapshot, not an invented badge.
      assertEquals(0, input.readByteArray().length);
      assertEquals(0, input.readTag());
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

  @ParameterizedTest @ValueSource(ints = {0, 1, 2})
  void trainVisitorRegisterCompletesPamMenuQueries(int selector) throws Exception {
    var session = new PlayerReturnInitial46Test.Session();
    byte[] body = selector == 0 ? new byte[0] : new byte[] {32, (byte) selector};
    var before = new Gson().toJson(session.player.getGameplay());

    assertTrue(VersionedPacketHandler.handle(session, 3758, body));

    var packet = session.nativePackets.getFirst();
    assertEquals(3756, packet.cmdId());
    var response = session.bridge.clientSchema().parse("GetTrainVisitorRegisterScRsp", packet.body());
    var type = response.getDescriptorForType();
    assertEquals(0, response.getField(type.findFieldByNumber(3)));
    assertEquals(0, response.getRepeatedFieldCount(type.findFieldByNumber(11)));
    assertEquals(0, response.getRepeatedFieldCount(type.findFieldByNumber(13)));
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
  }

  @Test void trainVisitorRegisterRejectsUnknownOrAmbiguousSelectors() throws Exception {
    for (byte[] body : new byte[][] {
        {32, 3}, {32, 1, 32, 2}, {34, 1, 2}, {32, (byte) 0x82, (byte) 0x80,
            (byte) 0x80, (byte) 0x80, 0x10}
    }) {
      var session = new PlayerReturnInitial46Test.Session();
      assertTrue(VersionedPacketHandler.handle(session, 3758, body));
      var packet = session.nativePackets.getFirst();
      assertEquals(3756, packet.cmdId());
      var response = session.bridge.clientSchema().parse("GetTrainVisitorRegisterScRsp", packet.body());
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(response, "retcode"));
      assertEquals(SessionState.ACTIVE, session.getState());
    }
  }

  @ParameterizedTest @MethodSource("queries")
  void invalidBodiesFinishWithParameterErrorAndDoNotBecomeSuccess(int request, int response, int ret)
      throws Exception {
    for (byte[] body : new byte[][] {new byte[] {8, 1}, new byte[] {(byte) 0x80}}) {
      var session = new PlayerReturnInitial46Test.Session();
      assertTrue(VersionedPacketHandler.handle(session, request, body));
      if (request == 96) {
        var data = session.bridge.clientSchema().parse("QueryProductInfoScRsp",
            session.clientBodies.get("QueryProductInfoScRsp"));
        assertEquals(Retcode.REQ_PARA_INVALID.getVal(), InitialData46Test.field(data, "retcode"));
        assertEquals(0, data.getRepeatedFieldCount(data.getDescriptorForType().findFieldByName("product_list")));
        assertEquals(0, session.player.getMcoin());
        continue;
      }
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
    // The former empty bootstrap placeholder now reads actual level-reward receipts.
    input.writeBytes(emu.lunarcore.server.packet.PacketCodec.encode(78, new byte[0], new byte[0]));
    input.writeBytes(emu.lunarcore.server.packet.PacketCodec.encode(6134, new byte[0], new byte[0]));
    input.writeBytes(emu.lunarcore.server.packet.PacketCodec.encode(
        session.bridge.clientCommand("ContentPackageGetDataCsReq"), new byte[0], new byte[0]));
    try { session.onMessage(input); } finally { input.release(); }
    assertFalse(session.closed);
    assertEquals(22, session.nativePackets.size());
    assertTrue(session.clientBodies.containsKey("QueryProductInfoScRsp"));
    assertTrue(session.clientBodies.containsKey("GetLevelRewardTakenListScRsp"));
    assertTrue(session.clientBodies.containsKey("GetAllServerPrefsDataScRsp"));
    assertTrue(session.clientBodies.containsKey("ContentPackageGetDataScRsp"));
    assertEquals(SessionState.ACTIVE, session.getState());
  }
}
