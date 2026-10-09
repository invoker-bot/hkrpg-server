package org.hkrpg.server.protocol;

import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.time.Instant;
import org.hkrpg.server.gameplay.*;

/** The native main-page request has no scalar, dice result or reward payload. */
public final class DiceMainPageRoll46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("DiceCombatMainPageRollDiceCsReq"))
      return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var code =
          MatchThree46.wireValid(
                  session.getProtocol().clientSchema().message("DiceCombatMainPageRollDiceCsReq"),
                  bytes,
                  0)
              ? DiceMainPageRolls.accept(
                  session.getPlayer(), DiceCombatCatalog.read(), Instant.now())
              : Retcode.REQ_PARA_INVALID;
      var response = Gameplay46.builder(session, "DiceCombatMainPageRollDiceScRsp");
      Gameplay46.set(response, "retcode", code.getVal());
      session.sendClientPacket("DiceCombatMainPageRollDiceScRsp", response.build().toByteArray());
      return true;
    }
  }

  private DiceMainPageRoll46() {}
}
