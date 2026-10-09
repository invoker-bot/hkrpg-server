package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import org.hkrpg.server.gameplay.*;

/**
 * Original Hanu control only; a general group-state command does not authorize arbitrary groups.
 */
public final class WolfBroGroup46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("GroupStateChangeCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var req = session.getProtocol().clientSchema().parse("GroupStateChangeCsReq", bytes);
    synchronized (session.getPlayer()) {
      var field = req.getDescriptorForType().findFieldByName("group_state_info");
      var group = req.hasField(field) ? (DynamicMessage) req.getField(field) : null;
      Retcode code = Retcode.REQ_PARA_INVALID;
      if (group != null
          && Parkour46.wireValid(req.getDescriptorForType(), bytes, 0)
          && req.getUnknownFields().asMap().isEmpty()
          && group.getUnknownFields().asMap().isEmpty()
          && (Long) value(req, "interact_id") == 0L) {
        code =
            new WolfBroManager(session.getPlayer())
                .control(
                    number(group, "group_id"),
                    number(group, "group_state"),
                    number(group, "entry_id"),
                    number(group, "GMEHAMCHJBK"));
      }
      var b = builder(session, "GroupStateChangeScRsp");
      set(b, "retcode", code.getVal());
      if (code == Retcode.SUCC) set(b, "group_state_info", group);
      session.sendClientPacket("GroupStateChangeScRsp", b.build().toByteArray());
    }
    return true;
  }

  private WolfBroGroup46() {}
}
