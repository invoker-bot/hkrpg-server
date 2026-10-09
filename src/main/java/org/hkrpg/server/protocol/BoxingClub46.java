package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import emu.lunarcore.LunarCore;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.nio.file.Path;
import org.hkrpg.server.gameplay.BoxingClubCatalog;

/** Source-bound first-season catalog. Fight progress and rewards are not manufactured here. */
public final class BoxingClub46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("GetBoxingClubInfoCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var response = builder(session, "GetBoxingClubInfoScRsp");
      var catalog =
          bytes.length == 0
              ? BoxingClubCatalog.load(Path.of(LunarCore.getConfig().getResourceDir()))
              : null;
      if (catalog == null) set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
      else
        for (var challenge : catalog.challenges().values()) {
          var row = builder(session, "CIEEKOEECHB");
          set(row, "challenge_id", challenge.id());
          add(response, "challenge_list", row.build());
        }
      session.sendClientPacket("GetBoxingClubInfoScRsp", response.build().toByteArray());
      return true;
    }
  }
}
