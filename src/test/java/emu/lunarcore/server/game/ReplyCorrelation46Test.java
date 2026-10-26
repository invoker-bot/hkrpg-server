package emu.lunarcore.server.game;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.SessionState;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.Test;

/** Real session dispatch must let the client's pending purchase finish, including on failure. */
class ReplyCorrelation46Test {
  static class Session extends GameSession {
    final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
    final List<PacketCodec.Frame> sent = new ArrayList<>();

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    @Override
    public ProtocolBridge getProtocol() {
      return bridge;
    }

    @Override
    protected void writeTransport(byte[] bytes) {
      var input = Unpooled.wrappedBuffer(bytes);
      try {
        while (input.isReadable()) sent.add(PacketCodec.decodeNext(input));
      } finally {
        input.release();
      }
    }

    void request(String name, byte[] header) {
      request(name, header, new byte[0]);
    }

    void request(String name, byte[] header, byte[] body) {
      var input =
          Unpooled.wrappedBuffer(PacketCodec.encode(bridge.clientCommand(name), header, body));
      try {
        onMessage(input);
      } finally {
        input.release();
      }
    }
  }

  @Test
  void purchaseFailureStillCompletesTheExactPendingClientRequest() {
    var session = new Session();
    byte[] packetId = {8, (byte) 0xbd, (byte) 0xcd, 4}; // Actual header shape; no account fields.
    session.request("BuyGoodsCsReq", packetId);
    assertEquals(1, session.sent.size());
    assertEquals(session.bridge.clientCommand("BuyGoodsScRsp"), session.sent.getFirst().cmdId());
    assertArrayEquals(
        packetId,
        session.sent.getFirst().header(),
        "The async ShopModule callback cannot complete with reply packet ID zero");
  }

  @Test
  void successivePurchasesKeepTheirOwnIdsRatherThanReusingTheLastRequest() {
    var session = new Session();
    session.request("BuyGoodsCsReq", new byte[] {8, 41});
    session.request("BuyGoodsCsReq", new byte[] {8, 42});
    assertEquals(2, session.sent.size());
    assertArrayEquals(new byte[] {8, 41}, session.sent.get(0).header());
    assertArrayEquals(new byte[] {8, 42}, session.sent.get(1).header());
  }

  @Test
  void unrelatedLaterRepliesAndNotificationsDoNotInheritAPurchaseId() {
    var session = new Session();
    session.request("BuyGoodsCsReq", new byte[] {8, 77});
    session.sendClientPacket("PlayerSyncScNotify", new byte[0]);
    session.sendClientPacket("BuyGoodsScRsp", new byte[0]);
    assertArrayEquals(new byte[] {8, 77}, session.sent.get(0).header());
    assertEquals(0, session.sent.get(1).header().length);
    assertEquals(0, session.sent.get(2).header().length);
  }

  @Test
  void aPurchaseWithoutAPacketIdRetainsTheExistingHeaderlessProtocol() {
    var session = new Session();
    session.request("BuyGoodsCsReq", new byte[0]);
    assertEquals(1, session.sent.size());
    assertEquals(0, session.sent.getFirst().header().length);
  }

  @Test
  void backToBackFramesRetainTheHeaderOfEachRequest() {
    var session = new Session();
    var input =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(1505, new byte[] {8, 5}, new byte[0]),
            PacketCodec.encode(1505, new byte[] {8, 6}, new byte[0]));
    try {
      session.onMessage(input);
    } finally {
      input.release();
    }
    assertEquals(2, session.sent.size());
    assertArrayEquals(new byte[] {8, 5}, session.sent.get(0).header());
    assertArrayEquals(new byte[] {8, 6}, session.sent.get(1).header());
  }

  @Test
  void anotherNativeRequestUsesItsOwnNamedResponseRatherThanThePurchaseId() {
    var session = new Session();
    session.request("BuyGoodsCsReq", new byte[] {8, 7});
    session.request("GetGachaInfoCsReq", new byte[] {8, 8}, new byte[] {8, 1});
    assertEquals(2, session.sent.size());
    assertEquals(session.bridge.clientCommand("GetGachaInfoScRsp"), session.sent.get(1).cmdId());
    assertArrayEquals(new byte[] {8, 8}, session.sent.get(1).header());
  }
}
