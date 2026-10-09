package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.*;
import emu.lunarcore.proto.GetLineupAvatarDataScRspOuterClass.GetLineupAvatarDataScRsp;
import emu.lunarcore.proto.LineupAvatarDataOuterClass.LineupAvatarData;
import emu.lunarcore.server.packet.CmdId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LineupAvatarData46Test {
  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void populatedOwnedFormalAndTrialActorDetailsReachTheNamed46List(int type) throws Exception {
    var core = GetLineupAvatarDataScRsp.newInstance();
    core.addLineupAvatarDataList(
        LineupAvatarData.newInstance()
            .setId(type == 1 ? 8001 : 1001006)
            .setHp(7300)
            .setAvatarTypeValue(type));
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var actual = bridge.outbound(CmdId.GetLineupAvatarDataScRsp, core.toByteArray());
    assertEquals(761, actual.cmdId());
    var wire = bridge.clientSchema().parse("GetLineupAvatarDataScRsp", actual.body());
    assertTrue(wire.getUnknownFields().asMap().isEmpty());
    var list = wire.getDescriptorForType().findFieldByName("avatar_data_list");
    assertEquals(3, list.getNumber());
    assertEquals(1, wire.getRepeatedFieldCount(list));
    var row = (DynamicMessage) wire.getRepeatedField(list, 0);
    assertEquals(
        type == 1 ? 8001 : 1001006, row.getField(row.getDescriptorForType().findFieldByName("id")));
    assertEquals(7300, row.getField(row.getDescriptorForType().findFieldByName("hp")));
    assertEquals(
        type,
        ((Descriptors.EnumValueDescriptor)
                row.getField(row.getDescriptorForType().findFieldByName("avatar_type")))
            .getNumber());
    assertEquals(7, row.getDescriptorForType().findFieldByName("id").getNumber());
    assertEquals(14, row.getDescriptorForType().findFieldByName("hp").getNumber());
    assertEquals(3, row.getDescriptorForType().findFieldByName("avatar_type").getNumber());
    assertTrue(row.getUnknownFields().asMap().isEmpty());
  }

  @Test
  void emptyActualPartyDoesNotInventActorDetails() {
    var bridge = ProtocolBridge.forVersion("4.6.0");
    var actual =
        bridge.outbound(
            CmdId.GetLineupAvatarDataScRsp, GetLineupAvatarDataScRsp.newInstance().toByteArray());
    assertArrayEquals(new byte[0], actual.body());
    assertEquals(761, actual.cmdId());
  }

  @Test
  void the42CommandAndPopulatedFieldBytesRemainUnchanged() {
    var core = GetLineupAvatarDataScRsp.newInstance();
    core.addLineupAvatarDataList(
        LineupAvatarData.newInstance().setId(8001).setHp(9999).setAvatarTypeValue(1));
    var actual =
        ProtocolBridge.forVersion("4.2.0")
            .outbound(CmdId.GetLineupAvatarDataScRsp, core.toByteArray());
    assertEquals(CmdId.GetLineupAvatarDataScRsp, actual.cmdId());
    assertArrayEquals(core.toByteArray(), actual.body());
  }
}
