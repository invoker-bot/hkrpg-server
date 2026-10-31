package org.hkrpg.server.protocol;

import com.google.protobuf.CodedInputStream;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.common.ItemParam.ItemParamType;
import emu.lunarcore.game.enums.ItemMainType;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.ItemSale;

/** Native command 511, request fields 2/12 and typed unique-item oneofs 2/4. */
public final class ItemSale46 {
  private record Request(
      boolean materials, List<ItemParam> costs, Map<Integer, ItemMainType> types) {}

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("SellItemCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var player = session.getPlayer();
      var request = decode(bytes);
      List<GameItem> received = null;
      var code = Retcode.REQ_PARA_INVALID;
      try {
        if (request != null) {
          boolean typed =
              request.types().entrySet().stream()
                  .allMatch(
                      e -> {
                        var item = player.getInventory().getItemByUid(e.getKey());
                        return item != null
                            && item.getExcel() != null
                            && item.getItemMainType() == e.getValue();
                      });
          var use = player.getGameplay().getLastItemUse();
          if (!player.getGameplay().getItemSale().available()
              || player.getGameplay().getCompositionLimits().pending()
              || use != null && use.pending()) code = Retcode.SYSTEM_BUSY;
          else if (typed) {
            received = ItemSale.sell(player, request.materials(), request.costs());
            if (received != null) code = Retcode.SUCC;
            else if (!player.getGameplay().getItemSale().available())
              code = Retcode.PLAYER_DATA_ERROR;
          }
        }
      } catch (RuntimeException failure) {
        // The sale ledger preserves an interrupted transaction for reconciliation. Do not
        // acknowledge success or retry any debit, but always answer the client's request.
        emu.lunarcore.LunarCore.getLogger().warn("Item sale interrupted for player {} ({})",
            player.getUid(), failure.getClass().getSimpleName());
        received = null;
        code = Retcode.SYSTEM_BUSY;
      }
      var response = Gameplay46.builder(session, "SellItemScRsp");
      Gameplay46.set(response, "retcode", code.getVal());
      var list = ItemList.newInstance();
      if (received != null) received.forEach(i -> list.addItemList(i.toProto()));
      Gameplay46.set(
          response,
          "return_item_list",
          session
              .getProtocol()
              .clientSchema()
              .parse(
                  "ItemList",
                  session.getProtocol().translate("ItemList", list.toByteArray(), true)));
      session.sendClientPacket("SellItemScRsp", response.build().toByteArray());
      return true;
    }
  }

  private static Request decode(byte[] bytes) {
    if (bytes == null || bytes.length > 16384) return null;
    try {
      var input = CodedInputStream.newInstance(bytes);
      boolean costSeen = false, boolSeen = false, materials = false;
      var costs = new ArrayList<ItemParam>();
      var types = new LinkedHashMap<Integer, ItemMainType>();
      int tag;
      while ((tag = input.readTag()) != 0) {
        if (tag == 18 && !costSeen) {
          costSeen = true;
          var data = CodedInputStream.newInstance(input.readByteArray());
          int entryTag;
          while ((entryTag = data.readTag()) != 0) {
            if (entryTag != 18 || costs.size() >= ItemSale.MAX_ITEMS) return null;
            byte[] raw = data.readByteArray();
            if (raw.length > 16) return null;
            var entry = CodedInputStream.newInstance(raw);
            int itemTag = entry.readTag();
            if (itemTag != 16 && itemTag != 32) return null;
            long id = entry.readUInt64();
            if (id <= 0 || id > Integer.MAX_VALUE || !entry.isAtEnd()) return null;
            var type = itemTag == 16 ? ItemMainType.Equipment : ItemMainType.Relic;
            if (types.put((int) id, type) != null) return null;
            costs.add(new ItemParam(ItemParamType.UNIQUE, (int) id, 1));
          }
        } else if (tag == 96 && !boolSeen) {
          boolSeen = true;
          long value = input.readUInt64();
          if (value < 0 || value > 1) return null;
          materials = value == 1;
        } else return null;
      }
      return costSeen && !costs.isEmpty()
          ? new Request(materials, List.copyOf(costs), Map.copyOf(types))
          : null;
    } catch (IOException invalid) {
      return null;
    }
  }

  private ItemSale46() {}
}
