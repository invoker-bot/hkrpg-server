package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.TreeSet;
import org.hkrpg.server.GameDiagnostics;

/** A read-only lower bound on collection identities that the current player still owns. */
final class UpdatedArchive46 {
  static boolean handle(GameSession session, int command, byte[] request) {
    if (!session.getProtocol().experimental() || command != 2305) return false;
    if (session.getState() != SessionState.ACTIVE || session.getPlayer() == null) return true;
    synchronized (session.getPlayer()) {
      var response = builder(session, "GetUpdatedArchiveDataScRsp");
      if (request == null || request.length != 0) {
        set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
        GameDiagnostics.outcome(session, "GetUpdatedArchiveDataCsReq", 0,
            Retcode.REQ_PARA_INVALID, "unexpected-query-fields", "");
      } else {
        set(response, "archive_data", snapshot(session, session.getPlayer()).build());
      }
      session.sendClientPacket(2375, response.build().toByteArray());
    }
    return true;
  }

  private static com.google.protobuf.DynamicMessage.Builder snapshot(GameSession session, Player player) {
    var data = builder(session, "ArchiveData");
    var avatars = new TreeSet<Integer>();
    if (player.getUid() > 0) {
      for (var entry : player.getAvatars().getAvatars().int2ObjectEntrySet()) {
        var avatar = entry.getValue();
        if (avatar != null && entry.getIntKey() > 0 && entry.getIntKey() == avatar.getAvatarId()
            && avatar.getOwner() == player && avatar.getOwnerUid() == player.getUid())
          avatars.add(entry.getIntKey());
      }
    }
    avatars.forEach(id -> add(data, "archive_avatar_id_list", id));

    var equipment = new TreeSet<Integer>();
    for (var item : player.getInventory().getItems().values()) {
      if (item == null || player.getUid() <= 0 || item.getOwnerUid() != player.getUid()
          || item.getInternalUid() <= 0
          || player.getInventory().getItemByUid(item.getInternalUid()) != item
          || item.getCount() != 1) continue;
      var definition = GameData.getItemExcelMap().get(item.getItemId());
      if (definition != null && item.getExcel() == definition && definition.isEquipment()
          && definition.getEquipmentExcel().getId() == item.getItemId())
        equipment.add(item.getItemId());
    }
    equipment.forEach(id -> add(data, "archive_equipment_id_list", id));
    return data;
  }

  private UpdatedArchive46() {}
}
