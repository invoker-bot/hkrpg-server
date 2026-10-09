package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.proto.StartCocoonStageCsReqOuterClass.StartCocoonStageCsReq;
import emu.lunarcore.server.packet.CmdId;
import org.junit.jupiter.api.Test;

class CocoonWave46Test {
  @Test
  void nativeWaveFieldReachesTheCountActuallyConsumedByTheCoreHandler() throws Exception {
    var protocol = ProtocolBridge.forVersion("4.6.0");
    // Pinned CN4.6: world level field3, challenge waves field4, cocoon field7, prop field8.
    var packet = protocol.inbound(1432, new byte[] {24, 1, 32, 3, 56, (byte) 233, 7, 64, 2});
    assertEquals(CmdId.StartCocoonStageCsReq, packet.cmdId());
    var request = StartCocoonStageCsReq.parseFrom(packet.body());
    assertEquals(1, request.getWorldLevel());
    assertEquals(1001, request.getCocoonId());
    assertEquals(2, request.getPropEntityId());
    assertEquals(3, request.getWaveCount());
    assertEquals(0, request.getWave());
  }

  @Test
  void legacyRequestsRetainBothOriginalWaveFieldsByteForByte() throws Exception {
    var core = StartCocoonStageCsReq.newInstance().setCocoonId(1001).setWaveCount(3).setWave(2);
    var bytes = core.toByteArray();
    var packet = ProtocolBridge.forVersion("4.2.0").inbound(CmdId.StartCocoonStageCsReq, bytes);
    assertEquals(CmdId.StartCocoonStageCsReq, packet.cmdId());
    assertArrayEquals(bytes, packet.body());
    var restored = StartCocoonStageCsReq.parseFrom(packet.body());
    assertEquals(3, restored.getWaveCount());
    assertEquals(2, restored.getWave());
  }

  @Test
  void quickStartAlsoMapsNativeWaveToTheCountConsumedByItsHandler() throws Exception {
    var protocol = ProtocolBridge.forVersion("4.6.0");
    var packet = protocol.inbound(1360, new byte[] {8, 1, 24, 3, 56, (byte) 233, 7});
    var request =
        emu.lunarcore.proto.QuickStartCocoonStageCsReqOuterClass.QuickStartCocoonStageCsReq
            .parseFrom(packet.body());
    assertEquals(CmdId.QuickStartCocoonStageCsReq, packet.cmdId());
    assertEquals(3, request.getWaveCount());
    assertEquals(0, request.getWave());
    assertEquals(1001, request.getCocoonId());
  }

  @Test
  void quickStartResponseCombinesItsEqualLegacyWaveFieldsIntoTheNativeField() throws Exception {
    var protocol = ProtocolBridge.forVersion("4.6.0");
    var core =
        emu.lunarcore.proto.QuickStartCocoonStageScRspOuterClass.QuickStartCocoonStageScRsp
            .newInstance()
            .setWave(3)
            .setWaveCount(3)
            .setCocoonId(1001);
    var packet = protocol.outbound(CmdId.QuickStartCocoonStageScRsp, core.toByteArray());
    assertEquals(protocol.clientCommand("QuickStartCocoonStageScRsp"), packet.cmdId());
    var parsed = protocol.clientSchema().parse("QuickStartCocoonStageScRsp", packet.body());
    assertTrue(parsed.getUnknownFields().asMap().isEmpty());
    assertEquals(3, parsed.getField(parsed.getDescriptorForType().findFieldByName("wave")));
    assertEquals(1001, parsed.getField(parsed.getDescriptorForType().findFieldByName("cocoon_id")));
  }
}
