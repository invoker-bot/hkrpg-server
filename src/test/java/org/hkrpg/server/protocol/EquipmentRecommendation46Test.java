package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.AvatarUpgrade46Test.join;
import static org.hkrpg.server.protocol.AvatarUpgrade46Test.scalar;
import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.BigDataRecommendTypeOuterClass.BigDataRecommendType;
import emu.lunarcore.proto.GetBigDataRecommendCsReqOuterClass.GetBigDataRecommendCsReq;
import emu.lunarcore.proto.GetBigDataRecommendScRspOuterClass.GetBigDataRecommendScRsp;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.recv.HandlerGetBigDataRecommendCsReq;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pinned CN4.6 request 2416 (10,12) and reply 2411 (9,12,14), independently of aliases. */
class EquipmentRecommendation46Test {
  private final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  static class Session extends GameSession {
    final List<BasePacket> sent = new ArrayList<>();

    Session() {
      super(null);
    }

    @Override
    public void send(BasePacket packet) {
      sent.add(packet);
    }
  }

  @ParameterizedTest
  @CsvSource({"1003,1", "8001,1", "1011003,1", "1003,2"})
  void recommendationBeforeOpeningEquipmentListGetsAnAvatarMatchedNoDataReply(
      int avatarId, int kind) throws Exception {
    var nativeRequest = join(scalar(10, avatarId), scalar(12, kind));
    var converted = bridge.inbound(2416, nativeRequest);
    assertEquals(CmdId.GetBigDataRecommendCsReq, converted.cmdId());
    var request = GetBigDataRecommendCsReq.parseFrom(converted.body());
    assertEquals(avatarId, request.getTrailblazerPathId());
    assertEquals(kind, request.getRecommendType().getNumber());

    // Exercise the actual inherited handler, not a fabricated test response. This is read-only
    // even without a player, and must return exactly one reply before the UI can open the list.
    var session = new Session();
    new HandlerGetBigDataRecommendCsReq().handle(session, converted.body());
    assertEquals(1, session.sent.size());
    var response = session.sent.getFirst();
    assertEquals(CmdId.GetBigDataRecommendScRsp, response.getCmdId());
    var outbound = bridge.outbound(response.getCmdId(), response.getData().toByteArray());
    assertEquals(2411, outbound.cmdId());
    // The client matches its pending promise by avatar AND recommendation kind. An empty success
    // packet, a trial-avatar substitution or copied 4.2 field numbers would leave it pending.
    assertArrayEquals(join(scalar(9, avatarId), scalar(12, kind)), outbound.body());
    DynamicMessage wire = bridge.clientSchema().parse("GetBigDataRecommendScRsp", outbound.body());
    var type = wire.getDescriptorForType();
    assertEquals(avatarId, wire.getField(type.findFieldByName("equip_avatar")));
    assertEquals(
        kind,
        ((EnumValueDescriptor) wire.getField(type.findFieldByName("big_data_recommend_type")))
            .getNumber());
    assertEquals(0, wire.getField(type.findFieldByName("retcode")));
    assertEquals(false, wire.getField(type.findFieldByName("has_recommand")));
    assertFalse(wire.hasField(type.findFieldByName("equipment_recommend")));
    assertFalse(wire.hasField(type.findFieldByName("relic_recommend")));
  }

  @Test
  void serverErrorStillCarriesItsAvatarAndRecommendationKind() throws Exception {
    var response =
        GetBigDataRecommendScRsp.newInstance()
            .setTrailblazerPathId(1003)
            .setRecommendType(BigDataRecommendType.BIG_DATA_RECOMMEND_TYPE_EQUIPMENT)
            .setRetcode(1);
    var result = bridge.outbound(CmdId.GetBigDataRecommendScRsp, response.toByteArray());
    assertArrayEquals(join(scalar(9, 1003), scalar(12, 1), scalar(14, 1)), result.body());
  }

  @Test
  void malformedAndUnverifiedRecommendationRequestsRemainRejected() {
    assertThrows(ProtocolException.class, () -> bridge.inbound(2416, new byte[] {80}));
    assertThrows(
        ProtocolException.class,
        () -> bridge.inbound(2416, join(scalar(10, 1003), scalar(12, 1), scalar(15, 1))));
    assertThrows(
        ProtocolException.class, () -> bridge.inbound(2416, join(scalar(10, 1003), scalar(12, 8))));
  }

  @Test
  void baselineRequestAndReplyKeepTheirOriginalBytes() {
    var baseline = ProtocolBridge.forVersion("4.2.0");
    var request =
        GetBigDataRecommendCsReq.newInstance()
            .setTrailblazerPathId(1003)
            .setRecommendType(BigDataRecommendType.BIG_DATA_RECOMMEND_TYPE_EQUIPMENT)
            .toByteArray();
    var response =
        GetBigDataRecommendScRsp.newInstance()
            .setTrailblazerPathId(1003)
            .setRecommendType(BigDataRecommendType.BIG_DATA_RECOMMEND_TYPE_EQUIPMENT)
            .toByteArray();
    assertArrayEquals(request, baseline.inbound(CmdId.GetBigDataRecommendCsReq, request).body());
    assertArrayEquals(response, baseline.outbound(CmdId.GetBigDataRecommendScRsp, response).body());
  }
}
