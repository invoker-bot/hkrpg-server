package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.hkrpg.server.gameplay.RogueEnhancement;

/** CN 617621/617622, CINMCBDCBMJ/NAHANHPHGGF; frozen upstream descriptors stay intact. */
public final class RogueEnhancement46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || (command != 1804 && command != 1885)) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      if (command == 1804) {
        var response = builder(session, "GetRogueBuffEnhanceInfoScRsp");
        var status =
            bytes.length == 0
                ? RogueEnhancement.context(session.getPlayer())
                : Retcode.REQ_PARA_INVALID;
        set(response, "PLDKENJLKME", status.getVal());
        if (status == Retcode.SUCC) {
          var shop = builder(session, "HFGBCEKGFHK");
          RogueEnhancement.quotes(session.getPlayer())
              .forEach(
                  (id, cost) -> {
                    var pile = builder(session, "PileItem");
                    set(pile, "item_id", 31);
                    set(pile, "item_num", cost);
                    var item = builder(session, "ItemCost");
                    set(item, "pile_item", pile.build());
                    var costs = builder(session, "ItemCostData");
                    add(costs, "item_list", item.build());
                    var quote = builder(session, "AEFFOCFJNBF");
                    set(quote, "buff_id", id);
                    set(quote, "cost_data", costs.build());
                    add(shop, "EMAGFIBLNCJ", quote.build());
                  });
          set(response, "retcode", shop.build()); // Misnamed upstream field 11 is the shop message.
        }
        session.sendClientPacket("GetRogueBuffEnhanceInfoScRsp", response.build().toByteArray());
      } else {
        int id = requestId(bytes);
        var status =
            id > 0 ? RogueEnhancement.enhance(session.getPlayer(), id) : Retcode.REQ_PARA_INVALID;
        var buffer = new ByteArrayOutputStream();
        var output = CodedOutputStream.newInstance(buffer);
        if (status == Retcode.SUCC) {
          var buff = builder(session, "KJAMNOGFAMJ");
          set(buff, "buff_id", id);
          set(buff, "HAGDKKBAAKK", 2);
          output.writeByteArray(3, buff.build().toByteArray());
        } else output.writeUInt32(6, status.getVal());
        // Field 13 is an unconfirmed boolean. Leave it at the native false default.
        output.flush();
        session.sendClientPacket(1807, buffer.toByteArray());
      }
    }
    return true;
  }

  static int requestId(byte[] bytes) {
    try {
      var input = CodedInputStream.newInstance(bytes);
      if (input.readTag() != 104) return -1; // CINMCBDCBMJ.WriteTo tag 0x68, field 13.
      long id = input.readUInt64();
      return id > 0 && id <= Integer.MAX_VALUE && input.readTag() == 0 && input.isAtEnd()
          ? (int) id
          : -1;
    } catch (IOException invalid) {
      return -1;
    }
  }

  private RogueEnhancement46() {}
}
