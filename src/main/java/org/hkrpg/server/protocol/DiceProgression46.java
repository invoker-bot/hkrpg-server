package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.time.Instant;
import org.hkrpg.server.gameplay.*;

/** Native CN 4.6 upgrade 9292/9267 and special-die modification 9286/9264. */
public final class DiceProgression46 {
  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (!s.getProtocol().experimental()) return false;
    boolean upgrade = command == s.getProtocol().clientCommand("DiceCombatUpgradeAvatarCsReq");
    if (!upgrade && command != s.getProtocol().clientCommand("DiceCombatModifyAvatarDiceCsReq"))
      return false;
    String name = upgrade ? "DiceCombatUpgradeAvatarCsReq" : "DiceCombatModifyAvatarDiceCsReq";
    if (s.getState() != SessionState.ACTIVE) return true;
    synchronized (s.getPlayer()) {
      var code = Retcode.REQ_PARA_INVALID;
      int id = 0;
      var catalog = DiceCombatCatalog.read();
      if (Offering46.wireValid(s.getProtocol().clientSchema().message(name), bytes)) {
        var req = s.getProtocol().clientSchema().parse(name, bytes);
        id = number(req, upgrade ? "KOEOEHIMOEI" : "avatar_id");
        code =
            upgrade
                ? DiceProgression.upgrade(s.getPlayer(), catalog, id, Instant.now())
                : DiceProgression.modify(
                    s.getPlayer(), catalog, id, number(req, "LLPCNBJIMCN"), Instant.now());
      }
      String response = upgrade ? "UpgradeAvatarRsp" : "ModifyAvatarDiceRsp";
      var out = builder(s, response);
      set(out, "retcode", code.getVal());
      if (code == Retcode.SUCC) set(out, "avatar", DiceCombat46.avatar(s, catalog, id));
      s.sendClientPacket(response, out.build().toByteArray());
    }
    return true;
  }

  private DiceProgression46() {}
}
