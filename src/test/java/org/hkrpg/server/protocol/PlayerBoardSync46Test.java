package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.proto.BoardDataSyncOuterClass.BoardDataSync;
import emu.lunarcore.proto.HeadIconOuterClass.HeadIcon;
import emu.lunarcore.proto.PlayerSyncScNotifyOuterClass.PlayerSyncScNotify;
import emu.lunarcore.server.packet.CmdId;
import org.junit.jupiter.api.Test;

class PlayerBoardSync46Test {
  final ProtocolBridge bridge = ProtocolBridge.forVersion("4.6.0");

  static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  BoardDataSync board() {
    var board = BoardDataSync.newInstance().setSignature("真实角色取得");
    board.addUnlockedHeadIconList(HeadIcon.newInstance().setId(201209));
    board.addUnlockedHeadIconList(HeadIcon.newInstance().setId(208001));
    board.getMutableHeadIconFrameInfo().setHeadIconFrameId(0);
    return board;
  }

  DynamicMessage convert(BoardDataSync board) throws Exception {
    var packet =
        bridge.outbound(
            CmdId.PlayerSyncScNotify,
            PlayerSyncScNotify.newInstance().setBoardDataSync(board).toByteArray());
    var message = bridge.clientSchema().parse("PlayerSyncScNotify", packet.body());
    assertTrue(message.getUnknownFields().asMap().isEmpty());
    assertEquals(
        781,
        message
            .getDescriptorForType()
            .findFieldByName("update_player_board_module_list")
            .getNumber());
    return (DynamicMessage) field(message, "update_player_board_module_list");
  }

  @Test
  void unlockedHeadIconsAndSignatureSurviveTheNativeSyncWithoutLosingThePacket() throws Exception {
    var mapped = convert(board());
    assertEquals("真实角色取得", field(mapped, "signature"));
    var icons = (java.util.List<?>) field(mapped, "unlocked_head_icon_list");
    assertEquals(2, icons.size());
    assertEquals(201209, field((DynamicMessage) icons.get(0), "id"));
    assertEquals(208001, field((DynamicMessage) icons.get(1), "id"));
    assertEquals(0, field((DynamicMessage) field(mapped, "head_frame_info"), "head_frame_item_id"));
  }

  @Test
  void anAuthoredHeadFrameIdentityUsesTheNamedFieldInsteadOfTheOldTag() throws Exception {
    var board = board();
    board.getMutableHeadIconFrameInfo().setHeadIconFrameId(221001);
    var mapped = (DynamicMessage) field(convert(board), "head_frame_info");
    assertEquals(221001, field(mapped, "head_frame_item_id"));
    assertEquals(
        1, mapped.getDescriptorForType().findFieldByName("head_frame_item_id").getNumber());
    assertFalse(
        mapped.hasField(mapped.getDescriptorForType().findFieldByName("head_frame_expire_time")));
  }

  @Test
  void theStillUnknownHeadFrameUnlockListRemainsRejected() {
    var board = board();
    board.addUnlockedHeadIconFrameIdList(221001);
    assertThrows(ProtocolException.class, () -> convert(board));
  }
}
