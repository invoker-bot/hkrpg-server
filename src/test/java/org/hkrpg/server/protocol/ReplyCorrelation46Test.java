package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ReplyCorrelation46Test {
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");
  final ReplyCorrelation46 correlation = new ReplyCorrelation46();

  byte[] requestId(int id) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var output = com.google.protobuf.CodedOutputStream.newInstance(bytes);
    output.writeUInt32(1, id);
    output.flush();
    return bytes.toByteArray();
  }

  PacketCodec.Frame reply(String name) {
    var input =
        Unpooled.wrappedBuffer(
            correlation.outbound(
                PacketCodec.encode(bridge.clientCommand(name), new byte[0], new byte[] {8, 9})));
    try {
      return PacketCodec.decodeNext(input);
    } finally {
      input.release();
    }
  }

  @Test
  void notificationAndOtherResponseCannotConsumeThePurchaseContinuation() throws Exception {
    try (var scope =
        correlation.enter(bridge, bridge.clientCommand("BuyGoodsCsReq"), requestId(53))) {
      assertEquals(0, reply("PlayerSyncScNotify").header().length);
      assertEquals(0, reply("GetShopListScRsp").header().length);
      var purchase = reply("BuyGoodsScRsp");
      assertArrayEquals(requestId(53), purchase.header());
      assertArrayEquals(new byte[] {8, 9}, purchase.body());
      assertEquals(0, reply("BuyGoodsScRsp").header().length, "Complete the request only once");
    }
  }

  @Test
  void nestedDispatchRestoresTheOriginalIdAndClosesAfterAnException() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () -> {
          try (var outer = correlation.enter(bridge, 1505, requestId(11))) {
            try (var inner = correlation.enter(bridge, 1505, requestId(12))) {
              assertArrayEquals(requestId(12), reply("BuyGoodsScRsp").header());
            }
            assertArrayEquals(requestId(11), reply("BuyGoodsScRsp").header());
            throw new IllegalStateException("Fixture handler failure");
          }
        });
    assertEquals(0, reply("BuyGoodsScRsp").header().length);
  }

  @Test
  void anotherThreadCannotBorrowTheActiveRequest() throws Exception {
    try (var worker = Executors.newSingleThreadExecutor();
        var scope = correlation.enter(bridge, 1505, requestId(91))) {
      assertEquals(0, worker.submit(() -> reply("BuyGoodsScRsp").header().length).get());
      assertArrayEquals(requestId(91), reply("BuyGoodsScRsp").header());
    }
  }

  @Test
  void concatenatedNativeFramesCorrelateOnlyTheMatchingResponse() throws Exception {
    var bytes = new ByteArrayOutputStream();
    for (String name : List.of("PlayerSyncScNotify", "BuyGoodsScRsp", "GetShopListScRsp"))
      bytes.writeBytes(
          PacketCodec.encode(bridge.clientCommand(name), new byte[0], new byte[] {8, 1}));
    try (var scope = correlation.enter(bridge, 1505, requestId(29))) {
      var input = Unpooled.wrappedBuffer(correlation.outbound(bytes.toByteArray()));
      try {
        assertEquals(0, PacketCodec.decodeNext(input).header().length);
        assertArrayEquals(requestId(29), PacketCodec.decodeNext(input).header());
        assertEquals(0, PacketCodec.decodeNext(input).header().length);
        assertFalse(input.isReadable());
      } finally {
        input.release();
      }
    }
  }

  @Test
  void coreBackedReplyIsMatchedAfterConversionToNativeCommandIds() throws Exception {
    try (var scope =
        correlation.enter(bridge, bridge.clientCommand("GetBagCsReq"), requestId(31))) {
      var core =
          new emu.lunarcore.server.packet.BasePacket(emu.lunarcore.server.packet.CmdId.GetBagScRsp);
      var input = Unpooled.wrappedBuffer(correlation.outbound(bridge.outboundFrames(core.build())));
      try {
        var frame = PacketCodec.decodeNext(input);
        assertEquals(bridge.clientCommand("GetBagScRsp"), frame.cmdId());
        assertArrayEquals(requestId(31), frame.header());
        assertEquals(0, frame.body().length);
      } finally {
        input.release();
      }
    }
  }

  @Test
  void allUint32IdsIncludingValuesAboveSignedIntSurvive() throws Exception {
    for (int id : new int[] {1, 73597, Integer.MAX_VALUE, Integer.MIN_VALUE, -1})
      assertArrayEquals(requestId(id), ReplyCorrelation46.replyHeader(requestId(id)));
    assertEquals(0, ReplyCorrelation46.replyHeader(new byte[0]).length);
    assertEquals(0, ReplyCorrelation46.replyHeader(new byte[] {8, 0}).length);
  }

  @Test
  void clientMetadataIsNotReflectedIntoReplies() throws Exception {
    var header = new ByteArrayOutputStream();
    header.writeBytes(requestId(33));
    header.writeBytes(new byte[] {18, 3, 97, 98, 99, 24, 99});
    assertArrayEquals(requestId(33), ReplyCorrelation46.replyHeader(header.toByteArray()));
  }

  @Test
  void malformedOrAmbiguousIdsFailBeforeAnyHandlerCanTakePayment() {
    for (byte[] header :
        List.of(
            new byte[] {8, (byte) 0x80},
            new byte[] {8, 1, 8, 2},
            new byte[] {10, 0},
            new byte[] {8, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 16},
            new byte[] {12},
            new byte[4097]))
      assertThrows(ProtocolException.class, () -> correlation.enter(bridge, 1505, header));
    assertEquals(0, reply("BuyGoodsScRsp").header().length);
  }

  @Test
  void legacyUnknownAndHeaderlessNestedRequestsDoNotAcquireCorrelation() throws Exception {
    var legacy = ProtocolBridge.forVersion("4.2.0");
    try (var outer = correlation.enter(bridge, 1505, requestId(61))) {
      for (var protocol : List.of(bridge, legacy)) {
        try (var inner = correlation.enter(protocol, 1505, new byte[0])) {
          assertEquals(0, reply("BuyGoodsScRsp").header().length);
        }
      }
      try (var unknown = correlation.enter(bridge, 65535, requestId(62))) {
        assertEquals(0, reply("BuyGoodsScRsp").header().length);
      }
      assertArrayEquals(requestId(61), reply("BuyGoodsScRsp").header());
    }
    try (var scope = correlation.enter(legacy, 1505, requestId(63))) {
      assertEquals(0, reply("BuyGoodsScRsp").header().length);
    }
  }
}
