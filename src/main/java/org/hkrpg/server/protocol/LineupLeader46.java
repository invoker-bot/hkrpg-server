package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import org.hkrpg.server.gameplay.LineupLeaders;

/** Strict 4.6 single-slot requests; wide values must not truncate into valid party slots. */
public final class LineupLeader46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("ChangeLineupLeaderCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var code = Retcode.REQ_PARA_INVALID;
      int slot = 0;
      var type = session.getProtocol().clientSchema().message("ChangeLineupLeaderCsReq");
      if (Offering46.wireValid(type, bytes)) {
        var req = session.getProtocol().clientSchema().parse("ChangeLineupLeaderCsReq", bytes);
        slot = number(req, "slot");
        code = LineupLeaders.change(session.getPlayer(), slot);
      }
      var out = builder(session, "ChangeLineupLeaderScRsp");
      set(out, "retcode", code.getVal());
      if (code == Retcode.SUCC) set(out, "slot", slot);
      session.sendClientPacket("ChangeLineupLeaderScRsp", out.build().toByteArray());
    }
    return true;
  }

  private LineupLeader46() {}
}
