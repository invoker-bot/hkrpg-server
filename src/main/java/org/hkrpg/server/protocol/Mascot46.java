package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import org.hkrpg.server.gameplay.Mascots;

/** Catch groups share the existing scene command; local QTE notifications are not packets. */
public final class Mascot46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("GroupStateChangeCsReq")
        || session.getState() != SessionState.ACTIVE) return false;
    var req = session.getProtocol().clientSchema().parse("GroupStateChangeCsReq", bytes);
    var f = req.getDescriptorForType().findFieldByName("group_state_info");
    var group = req.hasField(f) ? (DynamicMessage) req.getField(f) : null;
    if (group == null
        || !(Mascots.applies(session.getPlayer().getScene(), number(group, "group_id"))
            || org.hkrpg.server.gameplay.CompatibleMechanisms.ownsGroupState(
                session.getPlayer().getScene(), number(group, "group_id")))) return false;
    synchronized (session.getPlayer()) {
      Retcode code = Retcode.REQ_PARA_INVALID;
      if (Parkour46.wireValid(req.getDescriptorForType(), bytes, 0)
          && req.getUnknownFields().asMap().isEmpty()
          && group.getUnknownFields().asMap().isEmpty()
          && (Long) value(req, "interact_id") == 0)
        code =
            org.hkrpg.server.gameplay.CompatibleMechanisms.ownsGroupState(
                    session.getPlayer().getScene(), number(group, "group_id"))
                ? org.hkrpg.server.gameplay.CompatibleMechanisms.groupState(
                    session.getPlayer(),
                    number(group, "group_id"),
                    number(group, "group_state"),
                    number(group, "entry_id"),
                    number(group, "GMEHAMCHJBK"))
                : Mascots.change(
                    session.getPlayer(),
                    number(group, "group_id"),
                    number(group, "group_state"),
                    number(group, "entry_id"),
                    number(group, "GMEHAMCHJBK"));
      var b = builder(session, "GroupStateChangeScRsp");
      set(b, "retcode", code.getVal());
      if (code == Retcode.SUCC) set(b, "group_state_info", group);
      session.sendClientPacket("GroupStateChangeScRsp", b.build().toByteArray());
    }
    return true;
  }

  private Mascot46() {}
}
