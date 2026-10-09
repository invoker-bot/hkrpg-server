package org.hkrpg.server.protocol;

import com.google.protobuf.CodedInputStream;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.gameplay.ItemComposition;

/**
 * Strict source pile selections; convert lists and unique/ambiguous cost oneofs remain unsupported.
 */
public final class ItemComposition46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("ComposeItemCsReq")) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var schema = session.getProtocol().clientSchema();
      var type = schema.message("ComposeItemCsReq");
      boolean valid = Parkour46.wireValid(type, bytes, 0);
      var request = valid ? schema.parse("ComposeItemCsReq", bytes) : null;
      int id = valid ? Gameplay46.number(request, "compose_id") : 0;
      int count = valid ? Gameplay46.number(request, "count") : 0;
      List<GameItem> received = null;
      Retcode code = Retcode.REQ_PARA_INVALID;
      var costs =
          valid && !request.hasField(type.findFieldByName("convert_item_list"))
              ? pileCosts(schema, bytes)
              : null;
      Instant now = Instant.now();
      if (costs != null) {
        if (session.getPlayer().getGameplay().getCompositionLimits().pending())
          code = Retcode.SYSTEM_BUSY;
        else {
          received = ItemComposition.compose(session.getPlayer(), id, count, costs, now);
          if (received != null) code = Retcode.SUCC;
        }
      }
      var response = Gameplay46.builder(session, "ComposeItemScRsp");
      Gameplay46.set(response, "retcode", code.getVal());
      Gameplay46.set(response, "compose_id", id);
      Gameplay46.set(response, "count", received == null ? 0 : count);
      var items = ItemList.newInstance();
      if (received != null) received.forEach(i -> items.addItemList(i.toProto()));
      Gameplay46.set(
          response,
          "return_item_list",
          schema.parse(
              "ItemList", session.getProtocol().translate("ItemList", items.toByteArray(), true)));
      if (received != null)
        CompositionLimit46.updated(session, GameData.getItemComposeExcelMap().get(id), now);
      session.sendClientPacket("ComposeItemScRsp", response.build().toByteArray());
      return true;
    }
  }

  private static List<ItemParam> pileCosts(ProtocolSchema schema, byte[] bytes) throws IOException {
    var result = new ArrayList<ItemParam>();
    var input = CodedInputStream.newInstance(bytes);
    int tag;
    while ((tag = input.readTag()) != 0) {
      if (tag != (9 << 3 | 2)) {
        input.skipField(tag);
        continue;
      }
      var data = CodedInputStream.newInstance(input.readByteArray());
      int entry;
      while ((entry = data.readTag()) != 0) {
        if (entry != (2 << 3 | 2) || result.size() >= 128) return null;
        var cost = CodedInputStream.newInstance(data.readByteArray());
        if (cost.readTag() != (10 << 3 | 2)) return null;
        var pile = schema.parse("PileItem", cost.readByteArray());
        if (cost.readTag() != 0) return null; // Also rejects different members of the same oneof.
        result.add(
            new ItemParam(Gameplay46.number(pile, "item_id"), Gameplay46.number(pile, "item_num")));
      }
    }
    return List.copyOf(result);
  }

  private ItemComposition46() {}
}
