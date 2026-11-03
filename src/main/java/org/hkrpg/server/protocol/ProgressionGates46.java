package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.List;
import org.hkrpg.server.gameplay.EquipmentProtection;
import org.hkrpg.server.gameplay.ProgressionUpgrades;

/** Native field meanings cross-checked with Honey fe00bdb receive handlers; client costs are not authoritative. */
final class ProgressionGates46 {
  static boolean handle(GameSession session, int command, byte[] bytes) {
    String name = switch (command) {
      case 358 -> "PromoteAvatar";
      // Honey's raw dump and the pinned client distinguish ordinary promotion (505)
      // from quick growth (563), despite their colliding exported message names.
      case 505 -> "PromoteEquipment";
      case 360 -> "UnlockSkilltree";
      case 560 -> "LockEquipment";
      case 521 -> "LockRelic";
      default -> null;
    };
    if (!session.getProtocol().experimental() || name == null) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var code = Retcode.REQ_PARA_INVALID;
    DynamicMessage request = null;
    try {
      if (bytes == null || bytes.length > 16384) throw new IllegalArgumentException("Progression request size");
      if (command == 505 && !Parkour46.wireValid(
          session.getProtocol().clientSchema().message(name + "CsReq"), bytes, 0))
        throw new IllegalArgumentException("Invalid ordinary promotion fields");
      request = session.getProtocol().clientSchema().parse(name + "CsReq", bytes);
      if (!request.getUnknownFields().asMap().isEmpty()) request = null;
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      request = null;
    }
    if (request != null) {
      try {
        var player = session.getPlayer();
        code = switch (command) {
          case 358 -> ProgressionUpgrades.promoteAvatar(player, Gameplay46.number(request,"base_avatar_id"));
          case 505 -> ProgressionUpgrades.promoteEquipment(player, Gameplay46.number(request,"FANHGFFLIID"));
          case 360 -> ProgressionUpgrades.unlockPoint(player, Gameplay46.number(request,"point_id"), Gameplay46.number(request,"level"));
          case 560 -> EquipmentProtection.lock(player, ids(request,"LDBMBPBGFHK"), (Boolean) Gameplay46.value(request,"is_locked"), ItemMainType.Equipment);
          // The pinned receiver treats field 10 as metadata; explicit IDs and is_locked determine the operation.
          case 521 -> EquipmentProtection.lock(player, ids(request,"relic_ids"), (Boolean) Gameplay46.value(request,"is_locked"), ItemMainType.Relic);
          default -> Retcode.REQ_PARA_INVALID;
        };
      } catch (RuntimeException failure) {
        emu.lunarcore.LunarCore.getLogger().warn("Progression {} failed for player {}", name, session.getPlayer().getUid(), failure);
        code = Retcode.SYSTEM_BUSY;
      }
    }
    var response = Gameplay46.builder(session, name + "ScRsp");
    Gameplay46.set(response, command == 358 || command == 505 ? "PLDKENJLKME" : "retcode", code.getVal());
    if (command == 360 && request != null && code == Retcode.SUCC) {
      Gameplay46.set(response,"point_id",Gameplay46.number(request,"point_id"));
      Gameplay46.set(response,"level",Gameplay46.number(request,"level"));
    }
    session.sendClientPacket(name + "ScRsp", response.build().toByteArray());
    return true;
  }

  @SuppressWarnings("unchecked")
  private static List<Integer> ids(DynamicMessage request, String name) {
    return (List<Integer>) Gameplay46.value(request,name);
  }

  private ProgressionGates46() {}
}
