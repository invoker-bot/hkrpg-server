package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.hkrpg.server.protocol.AvatarUpgrade46Test.*;

import emu.lunarcore.proto.ExpUpEquipmentCsReqOuterClass.ExpUpEquipmentCsReq;
import emu.lunarcore.proto.ExpUpRelicCsReqOuterClass.ExpUpRelicCsReq;
import emu.lunarcore.proto.RankUpEquipmentCsReqOuterClass.RankUpEquipmentCsReq;
import emu.lunarcore.proto.ItemCostListOuterClass.ItemCostList;
import emu.lunarcore.server.packet.CmdId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Independent native field numbers from Honey fe00bdb CmdItemType/Common, not a round trip. */
class EquipmentUpgrade46Test {
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  @ParameterizedTest
  @CsvSource({"542,2,5,221", "568,1,13,232", "583,8,11,271"})
  void nativeMaterialSelectionReachesTheCore(int command, int targetField, int costField, int material) throws Exception {
    var request = join(scalar(targetField, 1234), nested(costField,
        nested(2, nested(10, join(scalar(14, material), scalar(1, 2))))));
    var converted = bridge.inbound(command, request);
    ItemCostList costs;
    if (command == 542) {
      var core = ExpUpEquipmentCsReq.parseFrom(converted.body());
      assertEquals(CmdId.ExpUpEquipmentCsReq, converted.cmdId());
      assertEquals(1234, core.getEquipmentUniqueId()); costs = core.getItemCostList();
    } else if (command == 568) {
      var core = ExpUpRelicCsReq.parseFrom(converted.body());
      assertEquals(CmdId.ExpUpRelicCsReq, converted.cmdId());
      assertEquals(1234, core.getRelicUniqueId()); costs = core.getItemCostList();
    } else {
      var core = RankUpEquipmentCsReq.parseFrom(converted.body());
      assertEquals(CmdId.RankUpEquipmentCsReq, converted.cmdId());
      assertEquals(1234, core.getEquipmentUniqueId()); costs = core.getItemCostList();
    }
    assertEquals(1, costs.getItemList().length());
    assertEquals(material, costs.getItemList().get(0).getPileItem().getItemId());
    assertEquals(2, costs.getItemList().get(0).getPileItem().getItemNum());
  }

  @ParameterizedTest
  @CsvSource({"542,2,5,2", "568,1,13,4", "583,8,11,2"})
  void uniqueMaterialsRetainTheirTypedIdentity(int command, int targetField, int costField, int materialField) throws Exception {
    var request = join(scalar(targetField, 1234), nested(costField, nested(2, scalar(materialField, 5678))));
    var converted = bridge.inbound(command, request);
    var costs = command == 542 ? ExpUpEquipmentCsReq.parseFrom(converted.body()).getItemCostList()
        : command == 568 ? ExpUpRelicCsReq.parseFrom(converted.body()).getItemCostList()
        : RankUpEquipmentCsReq.parseFrom(converted.body()).getItemCostList();
    var item = costs.getItemList().get(0);
    assertEquals(5678, materialField == 2 ? item.getEquipmentUniqueId() : item.getRelicUniqueId());
    assertFalse(item.hasPileItem());
  }

  @Test void legacyRequestIsUnchanged() {
    var request = ExpUpEquipmentCsReq.newInstance().setEquipmentUniqueId(42).toByteArray();
    assertArrayEquals(request, ProtocolBridge.forVersion("4.2.0").inbound(CmdId.ExpUpEquipmentCsReq, request).body());
  }

  @Test void invalidSuperimpositionReturnsAnErrorAcknowledgement() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    var request = join(scalar(8, 1234), nested(11, nested(2, scalar(2, 5678))));
    assertTrue(VersionedPacketHandler.handle(session, 583, request));
    assertEquals("RankUpEquipmentScRsp", session.responseName);
    assertEquals(emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID.getVal(),
        (Integer) session.response.getField(session.response.getDescriptorForType().findFieldByName("retcode")));
    assertArrayEquals(scalar(1, emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID.getVal()), session.response.toByteArray());
  }

  @Test void malformedSuperimpositionReceivesAReplyWithoutClosingTheSession() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    assertTrue(VersionedPacketHandler.handle(session, 583, new byte[]{90, 100}));
    assertEquals("RankUpEquipmentScRsp", session.responseName);
    assertNotEquals(0, session.response.getField(session.response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(emu.lunarcore.server.packet.SessionState.ACTIVE, session.getState());
  }
}
