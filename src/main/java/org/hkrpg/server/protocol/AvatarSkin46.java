package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import org.hkrpg.server.GameDiagnostics;

/** Native 4.6 requests and response fields from the pinned avatar protocol. */
final class AvatarSkin46 {
  static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental() || command != 378 && command != 319) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    boolean dress = command == 378;
    String request = dress ? "DressAvatarSkinCsReq" : "TakeOffAvatarSkinCsReq";
    String response = dress ? "DressAvatarSkinScRsp" : "TakeOffAvatarSkinScRsp";
    int avatarId = 0, skinId = 0;
    Retcode code = Retcode.REQ_PARA_INVALID;
    String reason = "invalid-request";
    try {
      var schema = session.getProtocol().clientSchema();
      if (bytes != null && bytes.length <= 64 && Parkour46.wireValid(schema.message(request), bytes, 0)) {
        DynamicMessage input = schema.parse(request, bytes);
        avatarId = Gameplay46.number(input, "avatar_id");
        skinId = dress ? Gameplay46.number(input, "skin_id") : 0;
        var player = session.getPlayer();
        synchronized (player) {
          var avatar = player.getAvatars().getAvatarById(avatarId);
          if (avatar != null && !player.getAvatars().getAvatars().containsValue(avatar)) avatar = null;
          if (dress && !player.getUnlocks().getAvatarSkins().contains(skinId)) {
            code = Retcode.AVATAR_SKIN_ITEM_NOT_EXIST;
            reason = "skin-not-owned";
          } else if (avatar == null) {
            code = Retcode.AVATAR_NOT_EXIST;
            reason = "avatar-not-owned";
          } else if (dress && GameData.getAvatarSkinExcelMap().get(skinId) == null) {
            code = Retcode.ITEM_CONFIG_NOT_EXIST;
            reason = "skin-resource-missing";
          } else if (dress && GameData.getAvatarSkinExcelMap().get(skinId).getAvatarID() != avatar.getExcelId()) {
            code = Retcode.AVATAR_SKIN_NOT_MATCH_AVATAR;
            reason = "skin-avatar-mismatch";
          } else if (avatar.getData().getSkinId() == skinId) {
            code = dress ? Retcode.AVATAR_SKIN_ALREADY_DRESSED : Retcode.AVATAR_NOT_DRESS_SKIN;
            reason = dress ? "already-dressed" : "already-removed";
          } else {
            avatar.setSkin(skinId);
            code = Retcode.SUCC;
            reason = "applied";
          }
        }
      }
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      code = Retcode.REQ_PARA_INVALID;
    } catch (RuntimeException failed) {
      code = Retcode.SYSTEM_BUSY;
      reason = "apply-failed";
    }
    if (code != Retcode.SUCC)
      GameDiagnostics.outcome(session, request, avatarId, code, reason, "skin=" + skinId);
    var out = Gameplay46.builder(session, response);
    Gameplay46.set(out, "PLDKENJLKME", code.getVal());
    session.sendClientPacket(response, out.build().toByteArray());
    return true;
  }

  private AvatarSkin46() {}
}
