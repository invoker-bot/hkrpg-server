package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.*;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.RogueBuffShop;

/** CN 617644/635126: query 5616, packed item IDs in LECIGPPCMAB field 6, entity field 2. */
public final class RogueBuffShop46 {
  private record Request(int entity, List<Integer> ids) {}

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || (command != 5616 && command != 5615)) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      boolean query = command == 5616;
      var request = read(bytes, query);
      var status =
          request == null
              ? Retcode.REQ_PARA_INVALID
              : query
                  ? RogueBuffShop.status(session.getPlayer(), request.entity())
                  : RogueBuffShop.buy(session.getPlayer(), request.entity(), request.ids());
      var name = query ? "GetRogueShopBuffInfoScRsp" : "BuyRogueShopBuffScRsp";
      var response = builder(session, name);
      set(response, "retcode", status.getVal());
      if (status == Retcode.SUCC) {
        var shop = builder(session, "DAENHKENJHK");
        for (var quote : RogueBuffShop.quotes(session.getPlayer(), request.entity())) {
          var row = builder(session, "KJEOBMBMNIN");
          set(row, "buff_id", quote.id());
          set(row, "JBGENFIKGGF", quote.level());
          set(row, "GCIHGKCJOGB", !quote.available());
          set(row, "BELPGDAGFIH", quote.owned());
          var cost = cost(session, quote.cost());
          set(row, "cost_data", cost);
          set(row, "KINNMAGDDJJ", cost);
          add(shop, "buff_list", row.build());
        }
        set(response, "HABJHGPFGEB", shop.build());
      }
      session.sendClientPacket(name, response.build().toByteArray());
    }
    return true;
  }

  static DynamicMessage cost(GameSession session, int coins) {
    var pile = builder(session, "PileItem");
    set(pile, "item_id", 31);
    set(pile, "item_num", coins);
    var item = builder(session, "ItemCost");
    set(item, "pile_item", pile.build());
    var costs = builder(session, "ItemCostData");
    add(costs, "item_list", item.build());
    return costs.build();
  }

  private static int positive(CodedInputStream input) throws IOException {
    long value = input.readUInt64();
    return value > 0 && value <= Integer.MAX_VALUE ? (int) value : -1;
  }

  private static Request read(byte[] bytes, boolean query) {
    if (bytes.length > 4096) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      int entity = 0;
      boolean refreshSeen = false, packedSeen = false;
      var ids = new ArrayList<Integer>();
      int tag;
      while ((tag = input.readTag()) != 0) {
        if (tag == (query ? 120 : 16)) {
          if (entity != 0) return null;
          entity = positive(input);
          if (entity <= 0) return null;
        } else if (query && tag == 48) {
          if (refreshSeen || input.readUInt64() != 0) return null;
          refreshSeen = true;
        } else if (!query && tag == 48) {
          if (packedSeen || ids.size() >= 3) return null;
          int id = positive(input);
          if (id <= 0) return null;
          ids.add(id);
        } else if (!query && tag == 50) {
          if (packedSeen || !ids.isEmpty()) return null;
          packedSeen = true;
          var packed = CodedInputStream.newInstance(input.readByteArray());
          while (!packed.isAtEnd()) {
            if (ids.size() >= 3) return null;
            int id = positive(packed);
            if (id <= 0) return null;
            ids.add(id);
          }
        } else return null;
      }
      return entity > 0 && input.isAtEnd() && (query || !ids.isEmpty())
          ? new Request(entity, List.copyOf(ids))
          : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  private RogueBuffShop46() {}
}
