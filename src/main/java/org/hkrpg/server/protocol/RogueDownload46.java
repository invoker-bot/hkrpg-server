package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.List;
import org.hkrpg.server.gameplay.RogueDownload;

/** Native 1864/1821, verified by CN client 617619 and EKFCDGNOAIN/BHLEHMNFHPJ codecs. */
public final class RogueDownload46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || command != 1864) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      boolean valid = wireValid(bytes);
      var request =
          valid ? session.getProtocol().clientSchema().parse("PickRogueAvatarCsReq", bytes) : null;
      @SuppressWarnings("unchecked")
      var ids = valid ? (List<Integer>) value(request, "NOEPBNMJFGL") : List.<Integer>of();
      var status =
          valid
                  && request.getUnknownFields().asMap().isEmpty()
                  && only(request, "NOEPBNMJFGL", "prop_entity_id")
              ? RogueDownload.pick(session.getPlayer(), ids, number(request, "prop_entity_id"))
              : Retcode.REQ_PARA_INVALID;
      var response = builder(session, "PickRogueAvatarScRsp");
      set(response, "retcode", status.getVal());
      if (status == Retcode.SUCC) ids.forEach(id -> add(response, "NOEPBNMJFGL", id));
      session.sendClientPacket("PickRogueAvatarScRsp", response.build().toByteArray());
    }
    return true;
  }

  private static boolean wireValid(byte[] bytes) {
    if (bytes.length > 16384) return false;
    var input = CodedInputStream.newInstance(bytes);
    boolean entitySeen = false;
    int count = 0;
    try {
      int tag;
      while ((tag = input.readTag()) != 0) {
        int number = tag >>> 3, wire = tag & 7;
        if (number == 6) {
          if (entitySeen || wire != 0 || Long.compareUnsigned(input.readUInt64(), 0xffffffffL) > 0)
            return false;
          entitySeen = true;
        } else if (number == 14) {
          if (wire == 2) {
            var packed = CodedInputStream.newInstance(input.readByteArray());
            while (!packed.isAtEnd())
              if (++count > 1 || Long.compareUnsigned(packed.readUInt64(), 0xffffffffL) > 0)
                return false;
          } else if (wire != 0
              || ++count > 1
              || Long.compareUnsigned(input.readUInt64(), 0xffffffffL) > 0) return false;
        } else return false;
      }
      return entitySeen && count == 1;
    } catch (IOException invalid) {
      return false;
    }
  }

  private RogueDownload46() {}
}
