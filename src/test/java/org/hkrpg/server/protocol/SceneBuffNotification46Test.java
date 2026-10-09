package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.scene.SceneBuff;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.send.PacketSyncEntityBuffChangeListScNotify;
import io.netty.buffer.Unpooled;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SceneBuffNotification46Test {
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  DynamicMessage wire(BasePacket packet) throws Exception {
    var bytes =
        Unpooled.wrappedBuffer(
            assertDoesNotThrow(
                () -> bridge.outboundFrames(packet.build()),
                "A known scene buff notification must survive outgoing conversion"));
    try {
      var frame = PacketCodec.decodeNext(bytes);
      assertNotNull(frame);
      assertEquals(bridge.clientCommand("SyncEntityBuffChangeListScNotify"), frame.cmdId());
      assertFalse(bytes.isReadable());
      var m = bridge.clientSchema().parse("SyncEntityBuffChangeListScNotify", frame.body());
      assertTrue(m.getUnknownFields().asMap().isEmpty());
      return m;
    } finally {
      bytes.release();
    }
  }

  List<?> changes(DynamicMessage message) {
    return (List<?>) field(message, "entity_buff_change_list");
  }

  @ParameterizedTest
  @ValueSource(doubles = {1.1, -1})
  void completeAdditionNotificationKeepsEntityBuffAndSeconds(double seconds) throws Exception {
    var buff = new SceneBuff(1199932, 130801, seconds);
    var rows = changes(wire(new PacketSyncEntityBuffChangeListScNotify(19996001, buff)));
    assertEquals(1, rows.size(), "Outer buff list was silently dropped");
    var change = (DynamicMessage) rows.getFirst();
    assertEquals(19996001, field(change, "entity_id"));
    var add = change.getDescriptorForType().findFieldByName("buff_change_info");
    assertTrue(change.hasField(add));
    assertFalse(change.hasField(change.getDescriptorForType().findFieldByName("remove_buff_id")));
    var info = (DynamicMessage) change.getField(add);
    assertEquals(130801, field(info, "buff_id"));
    assertEquals(1199932, field(info, "base_avatar_id"));
    assertEquals((float) seconds, field(info, "life_time"));
    assertEquals(
        emu.lunarcore.LunarCore.convertToServerTime(buff.getCreateTime()),
        field(info, "add_time_ms"));
    assertEquals(
        "SCENE_ENTITY_BUFF_CHANGE_TYPE_ADD_MAZEBUFF",
        ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(change, "reason")).getName());
    assertTrue(change.getUnknownFields().asMap().isEmpty());
    assertTrue(info.getUnknownFields().asMap().isEmpty());
  }

  @Test
  void removalNotificationKeepsTheRemovalBranchAndEntity() throws Exception {
    var rows = changes(wire(new PacketSyncEntityBuffChangeListScNotify(19996001, 130801)));
    assertEquals(1, rows.size(), "Outer removal list was silently dropped");
    var change = (DynamicMessage) rows.getFirst();
    assertEquals(19996001, field(change, "entity_id"));
    assertEquals(130801, field(change, "remove_buff_id"));
    assertFalse(change.hasField(change.getDescriptorForType().findFieldByName("buff_change_info")));
    assertEquals(
        0,
        ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(change, "reason"))
            .getNumber());
  }

  @Test
  void aBatchKeepsIndependentAdditionAndRemovalOneofs() throws Exception {
    var first =
        new PacketSyncEntityBuffChangeListScNotify(19996001, new SceneBuff(1199932, 130801, 1.1));
    var second = new PacketSyncEntityBuffChangeListScNotify(19996002, 121101);
    var data =
        emu.lunarcore.proto.SyncEntityBuffChangeListScNotifyOuterClass
            .SyncEntityBuffChangeListScNotify.parseFrom(first.getData().toByteArray());
    var removed =
        emu.lunarcore.proto.SyncEntityBuffChangeListScNotifyOuterClass
            .SyncEntityBuffChangeListScNotify.parseFrom(second.getData().toByteArray());
    data.addEntityBuffInfoList(removed.getEntityBuffInfoList().get(0));
    var packet = new BasePacket(CmdId.SyncEntityBuffChangeListScNotify);
    packet.setData(data);
    var rows = changes(wire(packet));
    assertEquals(2, rows.size(), "Repeated changes were dropped");
    assertTrue(
        ((DynamicMessage) rows.get(0))
            .hasField(
                ((DynamicMessage) rows.get(0))
                    .getDescriptorForType()
                    .findFieldByName("buff_change_info")));
    assertEquals(121101, field((DynamicMessage) rows.get(1), "remove_buff_id"));
    assertEquals(19996002, field((DynamicMessage) rows.get(1), "entity_id"));
  }

  @Test
  void legacyNotificationFramesRemainByteIdentical() {
    var packet =
        new PacketSyncEntityBuffChangeListScNotify(19996001, new SceneBuff(1199932, 130801, 5));
    assertArrayEquals(
        packet.build(), ProtocolBridge.forVersion("4.2.0").outboundFrames(packet.build()));
  }
}
