package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.proto.AvatarExpUpCsReqOuterClass.AvatarExpUpCsReq;
import emu.lunarcore.proto.AvatarExpUpScRspOuterClass.AvatarExpUpScRsp;
import emu.lunarcore.proto.PileItemOuterClass.PileItem;
import emu.lunarcore.server.packet.CmdId;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Independent CN4.6 wire: request 3/7, cost list 2, pile 10, item/count 14/1. */
class AvatarUpgrade46Test {
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  static byte[] scalar(int field, int value) throws Exception {
    var out = new ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(out);
    wire.writeUInt32(field, value);
    wire.flush();
    return out.toByteArray();
  }

  static byte[] nested(int field, byte[] value) throws Exception {
    var out = new ByteArrayOutputStream();
    var wire = CodedOutputStream.newInstance(out);
    wire.writeByteArray(field, value);
    wire.flush();
    return out.toByteArray();
  }

  static byte[] join(byte[]... values) {
    var out = new ByteArrayOutputStream();
    for (var value : values) out.writeBytes(value);
    return out.toByteArray();
  }

  @ParameterizedTest
  @ValueSource(ints = {8001, 8002, 1202})
  void sixAdventureLogsReachTheUpgradeHandler(int avatar) throws Exception {
    var pile = join(scalar(14, 212), scalar(1, 6));
    var wire = join(nested(3, nested(2, nested(10, pile))), scalar(7, avatar));
    var converted = bridge.inbound(305, wire);
    assertEquals(CmdId.AvatarExpUpCsReq, converted.cmdId());
    var request = AvatarExpUpCsReq.parseFrom(converted.body());
    assertEquals(avatar, request.getBaseAvatarId());
    assertEquals(1, request.getItemCostList().getItemList().length());
    var material = request.getItemCostList().getItemList().get(0).getPileItem();
    assertEquals(212, material.getItemId());
    assertEquals(6, material.getItemNum());
  }

  @Test void refundsAndErrorCodesUseNativeFields() throws Exception {
    var reply = AvatarExpUpScRsp.newInstance().setRetcode(1202)
        .addReturnItemList(PileItem.newInstance().setItemId(211).setItemNum(2));
    var converted = bridge.outbound(CmdId.AvatarExpUpScRsp, reply.toByteArray());
    assertEquals(375, converted.cmdId());
    assertArrayEquals(join(scalar(7, 1202), nested(15, join(scalar(1, 2), scalar(14, 211)))), converted.body());
  }

  @Test void legacyUpgradeBytesRemainUnchanged() throws Exception {
    var legacy = ProtocolBridge.forVersion("4.2.0");
    var input = AvatarExpUpCsReq.newInstance().setBaseAvatarId(8001).toByteArray();
    assertArrayEquals(input, legacy.inbound(CmdId.AvatarExpUpCsReq, input).body());
  }
}
