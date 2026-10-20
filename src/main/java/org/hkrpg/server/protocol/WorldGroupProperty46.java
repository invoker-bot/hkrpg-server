package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import org.hkrpg.server.gameplay.WorldGroupProperties;

/** The pinned 4.6 request's signed field 10 is the value (March7thHoney fe00bdbd handler). */
public final class WorldGroupProperty46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("UpdateGroupPropertyCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    boolean valid = wireValid(bytes);
    var req =
        valid
            ? session.getProtocol().clientSchema().parse("UpdateGroupPropertyCsReq", bytes)
            : builder(session, "UpdateGroupPropertyCsReq").build();
    var result = new WorldGroupProperties.Result(Retcode.REQ_PARA_INVALID, 0, 0);
    if (valid
        && only(
            req,
            "floor_id",
            "group_id",
            "dimension_id",
            "property_name",
            "OLBEPIBLDBD",
            "interact_id")
        && req.getUnknownFields().asMap().isEmpty())
      result =
          WorldGroupProperties.change(
              session.getPlayer(),
              number(req, "floor_id"),
              number(req, "dimension_id"),
              number(req, "group_id"),
              (String) value(req, "property_name"),
              number(req, "OLBEPIBLDBD"));
    var response = builder(session, "UpdateGroupPropertyScRsp");
    set(response, "retcode", result.retcode().getVal());
    for (String key : new String[] {"floor_id", "group_id", "dimension_id", "property_name"})
      set(response, key, value(req, key));
    set(response, "old_value", result.oldValue());
    set(response, "new_value", result.newValue());
    session.sendClientPacket("UpdateGroupPropertyScRsp", response.build().toByteArray());
    return true;
  }

  private static boolean wireValid(byte[] bytes) {
    if (bytes.length > 512) return false;
    var in = com.google.protobuf.CodedInputStream.newInstance(bytes);
    var seen = new java.util.HashSet<Integer>();
    try {
      int tag;
      while ((tag = in.readTag()) != 0) {
        int field = tag >>> 3, wire = tag & 7;
        if (!seen.add(field)) return false;
        if (field == 12) {
          if (wire != 2 || in.readStringRequireUtf8().length() > 80) return false;
        } else if (field == 10) {
          if (wire != 0) return false;
          long value = in.readInt64();
          if (value != (int) value) return false;
        } else if (field == 15) {
          if (wire != 0) return false;
          in.readUInt64();
        } else if (field == 5 || field == 11 || field == 13) {
          if (wire != 0 || Long.compareUnsigned(in.readUInt64(), Integer.MAX_VALUE) > 0)
            return false;
        } else return false;
      }
      return true;
    } catch (IOException malformed) {
      return false;
    }
  }

  private WorldGroupProperty46() {}
}
