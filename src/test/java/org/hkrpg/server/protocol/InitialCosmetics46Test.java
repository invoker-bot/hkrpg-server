package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.proto.GetPamSkinDataScRspOuterClass.GetPamSkinDataScRsp;
import emu.lunarcore.proto.GetPetDataScRspOuterClass.GetPetDataScRsp;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.send.PacketGetPamSkinDataScRsp;
import java.util.List;
import org.junit.jupiter.api.Test;

class InitialCosmetics46Test {
  final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");

  @Test void noSelectedPetKeepsOwnedIdsAndOmitsTheNativeSelectionUnion() throws Exception {
    var core = GetPetDataScRsp.newInstance().setCurPetId(0).addPetIdList(1001).addPetIdList(1002);
    var packet = assertDoesNotThrow(() -> protocol.outbound(CmdId.GetPetDataScRsp, core.toByteArray()));
    assertEquals(7617, packet.cmdId());
    var wire = protocol.clientSchema().parse("GetPetDataScRsp", packet.body());
    assertEquals(List.of(1001, 1002), InitialData46Test.field(wire, "unlocked_pet_id"));
    assertFalse(wire.hasField(wire.getDescriptorForType().findFieldByName("PLKBLNMOOPH")));
    assertArrayEquals(core.toByteArray(), ProtocolBridge.forVersion("4.2.0")
        .outbound(CmdId.GetPetDataScRsp, core.toByteArray()).body());
  }

  @Test void aSelectedNormalPetUsesTheNativeSelectionUnion() throws Exception {
    var core = GetPetDataScRsp.newInstance().setCurPetId(1001);
    var packet = protocol.outbound(CmdId.GetPetDataScRsp,core.toByteArray());
    var wire = protocol.clientSchema().parse("GetPetDataScRsp",packet.body());
    Pet46Test.selected(wire,11,1001);
  }

  @Test void pamSkinQueryPreservesCurrentAndUnlockedValues() throws Exception {
    var core = GetPamSkinDataScRsp.newInstance().setCurrentPamSkinId(252000)
        .addUnlockedPamSkins(252000).addUnlockedPamSkins(252001);
    var packet = assertDoesNotThrow(() -> protocol.outbound(CmdId.GetPamSkinDataScRsp, core.toByteArray()));
    var wire = protocol.clientSchema().parse("GetPamSkinDataScRsp", packet.body());
    assertEquals(252000, InitialData46Test.field(wire, "cur_skin"));
    assertEquals(List.of(252000, 252001), InitialData46Test.field(wire, "unlock_skin_list"));
    assertArrayEquals(core.toByteArray(), ProtocolBridge.forVersion("4.2.0")
        .outbound(CmdId.GetPamSkinDataScRsp, core.toByteArray()).body());
  }

  @Test void legacyTruncatedPamDefaultIsNormalizedOnlyInTheQueryResponse() {
    checkPamQuery(25200, 252000);
  }

  @Test void absentPamSelectionUsesTheDefaultWithoutChangingTheRecord() {
    checkPamQuery(0, 252000);
  }

  @Test void anExistingValidPamSelectionSurvivesTheQuery() {
    checkPamQuery(252001, 252001);
  }

  private void checkPamQuery(int stored, int expected) {
    var player = new Player();
    player.setCurrentPamSkinId(stored);
    var packet = new PacketGetPamSkinDataScRsp(player);
    assertEquals(expected, ((GetPamSkinDataScRsp) packet.getData()).getCurrentPamSkinId());
    assertEquals(stored, player.getCurrentPamSkinId(), "A read must not overwrite operator data");
  }
}
