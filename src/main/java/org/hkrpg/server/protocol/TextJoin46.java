package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Read-only initial text selections; custom text and story-driven selection writes remain separate. */
final class TextJoin46 {
  static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("TextJoinQueryCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var response = builder(session, "TextJoinQueryScRsp");
    var rows = new ArrayList<DynamicMessage>();
    var code = Retcode.SUCC;
    try {
      var type = session.getProtocol().clientSchema().message("TextJoinQueryCsReq");
      if (!validQuery(bytes))
        throw new IllegalArgumentException("Unsupported text query shape");
      var request = DynamicMessage.parseFrom(type, bytes);
      var ids = (List<?>) request.getField(type.findFieldByNumber(3));
      if (ids.size() > 1024) throw new IllegalArgumentException("Text query exceeds limit");
      for (Object entry : ids) {
        int id = (Integer) entry;
        if (id <= 0) throw new IllegalArgumentException("Invalid text join id");
        var excel = GameData.getTextJoinExcelMap().get(id);
        if (excel != null && excel.getTextJoinItemList() == null) {
          code = Retcode.REWARD_CONFIG_NOT_EXIST;
          break;
        }
        // Preserve the inherited first-configured-item / id*10 rule without integer wraparound.
        int item = excel != null && !excel.getTextJoinItemList().isEmpty()
            ? excel.getTextJoinItemList().getInt(0) : Math.multiplyExact(id, 10);
        if (item <= 0) {
          code = Retcode.REWARD_CONFIG_NOT_EXIST;
          break;
        }
        var info = builder(session, "JCDNAGMDELF");
        // Pinned native parser: field 10 -> +0x24, used as the dictionary key at e20b239;
        // field 15 -> +0x20 is the selected item. Field 7 custom text stays absent.
        set(info, "PAIPJMKCCEH", id);
        set(info, "HPCIKHIBFHD", item);
        rows.add(info.build());
      }
    } catch (IOException | IllegalArgumentException | ArithmeticException invalid) {
      code = Retcode.REQ_PARA_INVALID;
    }
    set(response, "retcode", code.getVal());
    if (code == Retcode.SUCC) for (var row : rows) add(response, "HPEDOFFJKKP", row);
    session.sendClientPacket("TextJoinQueryScRsp", response.build().toByteArray());
    return true;
  }

  /** This request uses packed uint32 IDs; reject truncating aliases before protobuf decoding. */
  private static boolean validQuery(byte[] bytes) throws IOException {
    if (bytes.length > 16_384) return false;
    var input = com.google.protobuf.CodedInputStream.newInstance(bytes);
    int count = 0;
    for (int tag; (tag = input.readTag()) != 0;) {
      if (tag == 24) {
        if (++count > 1024 || Long.compareUnsigned(input.readUInt64(), 0xffffffffL) > 0)
          return false;
      } else if (tag == 26) {
        int limit = input.pushLimit(input.readRawVarint32());
        while (input.getBytesUntilLimit() > 0) {
          if (++count > 1024 || Long.compareUnsigned(input.readUInt64(), 0xffffffffL) > 0)
            return false;
        }
        input.popLimit(limit);
      } else return false;
    }
    return true;
  }

  private TextJoin46() {}
}
