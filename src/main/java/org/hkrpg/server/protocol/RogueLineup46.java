package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.*;
import java.util.*;

/** Classic team edit. CN JHICJLAIPLD response is 719 fields 7/9, not Join's field 6. */
public final class RogueLineup46 {
  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (!s.getProtocol().experimental() || command != 704) return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    var req = s.getProtocol().clientSchema().parse("ReplaceLineupCsReq", bytes);
    if (enumNumber(req, "extra_lineup_type") != 2) return false;
    synchronized (s.getPlayer()) {
      Retcode status = Retcode.REQ_PARA_INVALID;
      @SuppressWarnings("unchecked")
      var rows = (List<DynamicMessage>) value(req, "lineup_slot_list");
      boolean valid =
          req.getUnknownFields().asMap().isEmpty()
              && only(
                  req, "index", "extra_lineup_type", "lineup_slot_list", "leader_slot", "plane_id")
              && number(req, "index") == 0
              && (number(req, "plane_id") == 0
                  || number(req, "plane_id") == s.getPlayer().getPlaneId())
              && !rows.isEmpty()
              && rows.size() <= 4
              && number(req, "leader_slot") >= 0
              && number(req, "leader_slot") < rows.size();
      var slots = new TreeMap<Integer, Integer>();
      for (var row : rows) {
        int slot = number(row, "slot"), id = number(row, "id");
        valid &=
            row.getUnknownFields().asMap().isEmpty()
                && only(row, "slot", "id", "avatar_type")
                && "AvatarType_AvatarFormalType"
                    .equals(
                        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                                value(row, "avatar_type"))
                            .getName())
                && id > 0
                && slot >= 0
                && slot < rows.size()
                && slots.putIfAbsent(slot, id) == null;
      }
      if (valid && slots.size() == rows.size())
        status =
            s.getPlayer()
                .getUniverseManager()
                .lineup()
                .tryReplace(new ArrayList<>(slots.values()), number(req, "leader_slot"));
      var buffer = new ByteArrayOutputStream();
      var out = CodedOutputStream.newInstance(buffer);
      if (number(req, "index") != 0) out.writeUInt32(7, number(req, "index"));
      if (status != Retcode.SUCC) out.writeUInt32(9, status.getVal());
      out.flush();
      s.sendClientPacket(719, buffer.toByteArray());
    }
    return true;
  }

  private RogueLineup46() {}

  private static int enumNumber(DynamicMessage m, String name) {
    return ((com.google.protobuf.Descriptors.EnumValueDescriptor) value(m, name)).getNumber();
  }
}
