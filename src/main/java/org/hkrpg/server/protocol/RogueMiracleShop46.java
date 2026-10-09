package org.hkrpg.server.protocol;

import static org.hkrpg.server.protocol.Gameplay46.*;

import com.google.protobuf.*;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import org.hkrpg.server.gameplay.RogueMiracleShop;

/** CN 617704/617705: entity field 12, miracle field 6; actual buy command is 5656. */
public final class RogueMiracleShop46 {
  private record Request(int entity, int miracle) {}

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || (command != 5675 && command != 5656)) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      boolean query = command == 5675;
      var request = read(bytes, query);
      var status =
          request == null
              ? Retcode.REQ_PARA_INVALID
              : query
                  ? RogueMiracleShop.status(session.getPlayer(), request.entity())
                  : RogueMiracleShop.buy(session.getPlayer(), request.entity(), request.miracle());
      var name = query ? "GetRogueShopMiracleInfoScRsp" : "BuyRogueShopMiracleScRsp";
      var response = builder(session, name);
      set(response, "retcode", status.getVal());
      if (status == Retcode.SUCC) {
        var shop = builder(session, "AGKONMPOJKP");
        for (var quote : RogueMiracleShop.quotes(session.getPlayer(), request.entity())) {
          var row = builder(session, "ELGOBKCNOHH");
          set(row, "LJCEOGPOGBE", quote.id());
          // Native consumer inverts this bit into Available, independently of AlreadyHave.
          set(row, "GCIHGKCJOGB", !quote.available());
          set(row, "BELPGDAGFIH", quote.owned());
          var cost = RogueBuffShop46.cost(session, quote.cost());
          set(row, "cost_data", cost);
          set(row, "KINNMAGDDJJ", cost);
          add(shop, "MAJGMMCOCIG", row.build());
        }
        set(response, "DNPCCFFFBHN", shop.build());
      }
      session.sendClientPacket(name, response.build().toByteArray());
    }
    return true;
  }

  private static int positive(CodedInputStream input) throws IOException {
    long value = input.readUInt64();
    return value > 0 && value <= Integer.MAX_VALUE ? (int) value : -1;
  }

  private static Request read(byte[] bytes, boolean query) {
    if (bytes.length > 4096) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      int entity = 0, miracle = 0;
      boolean refreshSeen = false;
      int tag;
      while ((tag = input.readTag()) != 0) {
        if (tag == 96) {
          if (entity != 0 || (entity = positive(input)) <= 0) return null;
        } else if (!query && tag == 48) {
          if (miracle != 0 || (miracle = positive(input)) <= 0) return null;
        } else if (query && tag == 104) {
          if (refreshSeen || input.readUInt64() != 0) return null;
          refreshSeen = true;
        } else return null;
      }
      return entity > 0 && (query || miracle > 0) && input.isAtEnd()
          ? new Request(entity, miracle)
          : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  private RogueMiracleShop46() {}
}
