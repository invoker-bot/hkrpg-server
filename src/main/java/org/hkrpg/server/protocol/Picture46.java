package org.hkrpg.server.protocol;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;

/** Native 634944 sends 4160 with empty LIOGDHEFOGD; the frozen reply is 4116/retcode 13. */
public final class Picture46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental() || command != 4160) return false;
    if (session.getState() != SessionState.ACTIVE || session.getPlayer() == null) return true;
    var player = session.getPlayer();
    synchronized (player) {
      var result = bytes == null || bytes.length != 0 ? Retcode.REQ_PARA_INVALID : report(player);
      var response = Gameplay46.builder(session, "TakePictureScRsp");
      Gameplay46.set(response, "retcode", result.getVal());
      session.sendClientPacket("TakePictureScRsp", response.build().toByteArray());
    }
    return true;
  }

  private static Retcode report(Player player) {
    if (player.isInBattle()) return Retcode.IN_BATTLE_NOW;
    var scene = player.getScene();
    var entry = GameData.getMapEntranceExcelMap().get(player.getEntryId());
    var plane = GameData.getMazePlaneExcelMap().get(player.getPlaneId());
    if (scene == null
        || scene.getPlayer() != player
        || entry == null
        || plane == null
        || player.getEntryId() <= 0
        || entry.getId() != player.getEntryId()
        || player.getPlaneId() <= 0
        || plane.getId() != player.getPlaneId()
        || scene.getExcel() != plane
        || scene.getPlaneId() != player.getPlaneId()
        || scene.getFloorId() != player.getFloorId()
        || entry.getPlaneID() != player.getPlaneId()
        || entry.getFloorID() != player.getFloorId()) return Retcode.SCENE_ENTRY_ID_NOT_MATCH;
    // This protocol reports a photo action; it contains no image bytes or image identity.
    player.getDailyManager().onPictureTaken();
    return Retcode.SUCC;
  }

  private Picture46() {}
}
