package org.hkrpg.server.protocol;

import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.proto.RankUpEquipmentCsReqOuterClass.RankUpEquipmentCsReq;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.ArrayList;
import org.hkrpg.server.gameplay.EquipmentSuperimposition;

/** The legacy handler has no typed acknowledgement. Native 583/515 returns the real outcome. */
final class EquipmentRank46 {
  static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || command != 583) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var code = Retcode.REQ_PARA_INVALID;
    RankUpEquipmentCsReq request = null;
    var costs = new ArrayList<ItemParam>();
    try {
      if (bytes == null || bytes.length > 16384) throw new IllegalArgumentException("Rank request size");
      var converted = session.getProtocol().inbound(command, bytes);
      request = RankUpEquipmentCsReq.parseFrom(converted.body());
      for (var item : request.getItemCostList().getItemList()) {
        if (!item.hasPileItem() && !item.hasEquipmentUniqueId())
          throw new IllegalArgumentException("Wrong superimposition material kind");
        costs.add(new ItemParam(item));
      }
    } catch (IllegalArgumentException | IOException invalid) {
      request = null;
    }
    if (request != null) {
      try {
        if (EquipmentSuperimposition.apply(session.getPlayer(), request.getEquipmentUniqueId(), costs))
          code = Retcode.SUCC;
      } catch (RuntimeException failed) {
        emu.lunarcore.LunarCore.getLogger().warn("Equipment rank persistence failed for player {}",
            session.getPlayer().getUid(), failed);
        code = Retcode.SYSTEM_BUSY;
      }
    }
    var response = Gameplay46.builder(session, "RankUpEquipmentScRsp");
    Gameplay46.set(response, "retcode", code.getVal());
    session.sendClientPacket("RankUpEquipmentScRsp", response.build().toByteArray());
    return true;
  }

  private EquipmentRank46() {}
}
