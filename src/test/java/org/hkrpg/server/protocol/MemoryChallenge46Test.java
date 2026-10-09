package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.*;
import org.junit.jupiter.api.Test;

class MemoryChallenge46Test {
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  @Test
  void packedNativeFirstAndSecondLineupsKeepTheirHalfThroughTheVersionBridge() throws Exception {
    var builder = DynamicMessage.newBuilder(bridge.clientSchema().message("StartChallengeCsReq"));
    var descriptor = builder.getDescriptorForType();
    builder.setField(descriptor.findFieldByName("challenge_id"), 15);
    builder.addRepeatedField(descriptor.findFieldByName("first_lineup"), 8001);
    builder.addRepeatedField(descriptor.findFieldByName("second_lineup"), 1001);
    var request = MemoryChallenge46.read(builder.build().toByteArray());
    assertNotNull(request);
    assertEquals(15, request.challenge());
    assertEquals(java.util.List.of(8001), request.first());
    assertEquals(java.util.List.of(1001), request.second());
  }

  @Test
  void memoryStartCarriesTheNativeCurrentChallengeIdentityAndStatus() throws Exception {
    var core =
        StartChallengeScRspOuterClass.StartChallengeScRsp.newInstance()
            .setChallengeInfo(
                ChallengeInfoOuterClass.ChallengeInfo.newInstance()
                    .setChallengeId(15)
                    .setStatusValue(1)
                    .setExtraLineupTypeValue(1)
                    .setRoundCount(3)
                    .setScore(0));
    var nativeMessage =
        bridge
            .clientSchema()
            .parse(
                "StartChallengeScRsp",
                bridge.translate("StartChallengeScRsp", core.toByteArray(), true));
    var current =
        (DynamicMessage)
            nativeMessage.getField(
                nativeMessage.getDescriptorForType().findFieldByName("cur_challenge"));
    assertEquals(
        15, current.getField(current.getDescriptorForType().findFieldByName("challenge_id")));
    assertEquals(
        1,
        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                current.getField(current.getDescriptorForType().findFieldByName("status")))
            .getNumber());
    assertEquals(
        3, current.getField(current.getDescriptorForType().findFieldByName("round_count")));
  }

  @Test
  void actualMemorySettlementCarriesTheStarMaskAndDeclaredRewardMessage() throws Exception {
    var core =
        ChallengeSettleNotifyOuterClass.ChallengeSettleNotify.newInstance()
            .setChallengeId(15)
            .setIsWin(true)
            .setStars(5);
    core.getMutableRewardList();
    var nativeMessage =
        bridge
            .clientSchema()
            .parse(
                "ChallengeSettleNotify",
                bridge.translate("ChallengeSettleNotify", core.toByteArray(), true));
    assertEquals(
        5, nativeMessage.getField(nativeMessage.getDescriptorForType().findFieldByName("star")));
    assertEquals(
        true,
        nativeMessage.getField(nativeMessage.getDescriptorForType().findFieldByName("is_win")));
    assertTrue(
        nativeMessage.hasField(nativeMessage.getDescriptorForType().findFieldByName("reward")));
  }

  DynamicMessage.Builder nativeRequest(int id) {
    var builder = DynamicMessage.newBuilder(bridge.clientSchema().message("StartChallengeCsReq"));
    builder.setField(builder.getDescriptorForType().findFieldByName("challenge_id"), id);
    return builder;
  }

  DynamicMessage avatar(int id, int type) {
    var builder = DynamicMessage.newBuilder(bridge.clientSchema().message("AvatarIdentifier"));
    builder.setField(builder.getDescriptorForType().findFieldByName("id"), id);
    var field = builder.getDescriptorForType().findFieldByName("avatar_type");
    builder.setField(field, field.getEnumType().findValueByNumberCreatingIfUnknown(type));
    return builder.build();
  }

  @Test
  void formalAvatarIdentifiersRetainTheHalfAndRejectTrialAssistOrMixedRepresentations() {
    var request = nativeRequest(15);
    request.addRepeatedField(
        request.getDescriptorForType().findFieldByName("avatar_lineup_first"), avatar(8001, 3));
    request.addRepeatedField(
        request.getDescriptorForType().findFieldByName("avatar_lineup_second"), avatar(1001, 3));
    var parsed = MemoryChallenge46.read(request.build().toByteArray());
    assertNotNull(parsed);
    assertEquals(java.util.List.of(8001), parsed.first());
    assertEquals(java.util.List.of(1001), parsed.second());
    request.addRepeatedField(request.getDescriptorForType().findFieldByName("first_lineup"), 8001);
    assertNull(MemoryChallenge46.read(request.build().toByteArray()));
    for (int type : new int[] {0, 1, 2, 4, 5, 6, 99}) {
      var invalid = nativeRequest(15);
      invalid.addRepeatedField(
          invalid.getDescriptorForType().findFieldByName("avatar_lineup_first"),
          avatar(8001, type));
      assertNull(MemoryChallenge46.read(invalid.build().toByteArray()));
    }
  }

  @Test
  void rawWrappedAndDuplicateIdentitiesAreRejectedBeforeProtobufTruncation() throws Exception {
    for (int variant = 0; variant < 4; variant++) {
      var bytes = new java.io.ByteArrayOutputStream();
      var out = com.google.protobuf.CodedOutputStream.newInstance(bytes);
      out.writeUInt64(3, variant == 0 ? (1L << 32) + 15 : 15);
      if (variant == 1) out.writeUInt32(3, 15);
      out.writeUInt64(5, variant == 2 ? (1L << 32) + 8001 : 8001);
      if (variant == 3) out.writeUInt32(5, 8001);
      out.flush();
      assertNull(MemoryChallenge46.read(bytes.toByteArray()));
    }
  }

  @Test
  void emptyOversizedAndUnsupportedRequestsDoNotChangeTheParty() {
    assertNull(MemoryChallenge46.read(new byte[0]));
    var request = nativeRequest(15);
    var list = request.getDescriptorForType().findFieldByName("first_lineup");
    for (int id = 1; id <= 5; id++) request.addRepeatedField(list, id);
    assertNull(MemoryChallenge46.read(request.build().toByteArray()));
    assertNull(MemoryChallenge46.read(new byte[4097]));
  }
}
