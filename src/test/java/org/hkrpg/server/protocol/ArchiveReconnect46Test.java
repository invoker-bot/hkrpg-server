package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.GetArchiveDataScRspOuterClass.GetArchiveDataScRsp;
import emu.lunarcore.proto.MonsterArchiveOuterClass.MonsterArchive;
import emu.lunarcore.proto.RelicArchiveOuterClass.RelicArchive;
import emu.lunarcore.server.packet.CmdId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Nonempty archive queries are part of the client's menu initialization after reconnect. */
class ArchiveReconnect46Test {
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  static Object field(DynamicMessage message, int number) {
    return message.getField(message.getDescriptorForType().findFieldByNumber(number));
  }

  DynamicMessage convert(GetArchiveDataScRsp source) throws Exception {
    var packet = bridge.outbound(CmdId.GetArchiveDataScRsp, source.toByteArray());
    assertEquals(2325, packet.cmdId());
    var response = bridge.clientSchema().parse("GetArchiveDataScRsp", packet.body());
    assertEquals(0, field(response, 8));
    assertTrue(response.getUnknownFields().asMap().isEmpty());
    return (DynamicMessage) field(response, 4);
  }

  @Test void monsterEntriesUseNativeKillListAndRetainCounts() throws Exception {
    var source = GetArchiveDataScRsp.newInstance();
    source.getMutableArchiveData()
        .addMonsterList(MonsterArchive.newInstance().setMonsterId(1001010).setNum(7))
        .addMonsterList(MonsterArchive.newInstance().setMonsterId(1002010).setNum(2));
    var rows = (List<?>) field(convert(source), 13);
    assertEquals(2, rows.size());
    assertEquals(1001010, field((DynamicMessage) rows.get(0), 2));
    assertEquals(7, field((DynamicMessage) rows.get(0), 6));
    assertEquals(1002010, field((DynamicMessage) rows.get(1), 2));
    assertEquals(2, field((DynamicMessage) rows.get(1), 6));
  }

  @Test void relicEntriesRetainSetAndSlotRatherThanInterchangingThem() throws Exception {
    var source = GetArchiveDataScRsp.newInstance();
    source.getMutableArchiveData()
        .addRelicList(RelicArchive.newInstance().setRelicId(101).setSlot(1))
        .addRelicList(RelicArchive.newInstance().setRelicId(301).setSlot(5));
    var rows = (List<?>) field(convert(source), 5);
    assertEquals(2, rows.size());
    assertEquals(101, field((DynamicMessage) rows.get(0), 3));
    assertEquals(1, field((DynamicMessage) rows.get(0), 15));
    assertEquals(301, field((DynamicMessage) rows.get(1), 3));
    assertEquals(5, field((DynamicMessage) rows.get(1), 15));
  }

  @Test void fullCachedArchiveRemainsIntactAcrossRepeatedReconnectReplies() throws Exception {
    var source = GetArchiveDataScRsp.newInstance();
    source.getMutableArchiveData()
        .addMonsterList(MonsterArchive.newInstance().setMonsterId(1001010).setNum(7))
        .addRelicList(RelicArchive.newInstance().setRelicId(101).setSlot(1))
        .addArchiveEquipmentIdList(20000).addArchiveEquipmentIdList(21000);
    byte[] original = source.toByteArray();
    DynamicMessage first = null;
    for (int reconnect = 0; reconnect < 3; reconnect++) {
      var archive = convert(source);
      assertEquals(List.of(20000, 21000), field(archive, 12));
      assertEquals(1, ((List<?>) field(archive, 13)).size());
      assertEquals(1, ((List<?>) field(archive, 5)).size());
      assertTrue(archive.getUnknownFields().asMap().isEmpty());
      assertTrue(((List<?>) field(archive, 1)).isEmpty(), "No invented avatar collection");
      assertTrue(((List<?>) field(archive, 11)).isEmpty(), "No invented missing equipment");
      if (first != null) assertEquals(first, archive);
      first = archive;
      assertArrayEquals(original, source.toByteArray());
    }
    assertArrayEquals(original,
        ProtocolBridge.forVersion("4.2.0").outbound(CmdId.GetArchiveDataScRsp, original).body());
  }

  @Test void unauditedArchiveFieldsStillFailClosed() {
    var source = GetArchiveDataScRsp.newInstance();
    source.getMutableArchiveData().addGLHNOAKHHPD(1234);
    assertThrows(ProtocolException.class, () -> convert(source));
  }

  @Test void nativeArchiveQueryRoutesToExistingHandlerWithoutInventedParameters() {
    var request = bridge.inbound(2334, new byte[0]);
    assertEquals(CmdId.GetArchiveDataCsReq, request.cmdId());
    assertEquals(0, request.body().length);
    assertThrows(ProtocolException.class, () -> bridge.inbound(2334, new byte[] {8, 1}));
  }
}
