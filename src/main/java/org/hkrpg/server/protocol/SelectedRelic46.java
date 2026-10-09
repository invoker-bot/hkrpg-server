package org.hkrpg.server.protocol;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.*;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.hkrpg.server.gameplay.SelectedRelicComposition;

/** Frozen selected-affix fields; client-supplied cost-list semantics remain unverified. */
public final class SelectedRelic46 {
  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()
        || command != session.getProtocol().clientCommand("ComposeSelectedRelicCsReq"))
      return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    synchronized (session.getPlayer()) {
      var schema = session.getProtocol().clientSchema();
      var type = schema.message("ComposeSelectedRelicCsReq");
      boolean valid = wireValid(type, bytes);
      var req = valid ? schema.parse("ComposeSelectedRelicCsReq", bytes) : null;
      int recipe = valid ? Gameplay46.number(req, "compose_id") : 0;
      List<GameItem> received = null;
      var code = Retcode.REQ_PARA_INVALID;
      if (valid) {
        if (session.getPlayer().getGameplay().getCompositionLimits().pending())
          code = Retcode.SYSTEM_BUSY;
        else {
          @SuppressWarnings("unchecked")
          var subs = (List<Integer>) req.getField(type.findFieldByName("sub_affix_id_list"));
          received =
              SelectedRelicComposition.compose(
                  session.getPlayer(),
                  recipe,
                  Gameplay46.number(req, "compose_relic_id"),
                  Gameplay46.number(req, "count"),
                  Gameplay46.number(req, "main_affix_id"),
                  subs,
                  Instant.now());
          if (received != null) code = Retcode.SUCC;
        }
      }
      var response = Gameplay46.builder(session, "ComposeSelectedRelicScRsp");
      Gameplay46.set(response, "retcode", code.getVal());
      Gameplay46.set(response, "compose_id", recipe);
      var list = ItemList.newInstance();
      if (received != null) received.forEach(i -> list.addItemList(i.toProto()));
      Gameplay46.set(
          response,
          "return_item_list",
          schema.parse(
              "ItemList", session.getProtocol().translate("ItemList", list.toByteArray(), true)));
      session.sendClientPacket("ComposeSelectedRelicScRsp", response.build().toByteArray());
      return true;
    }
  }

  private static boolean wireValid(Descriptor type, byte[] bytes) {
    if (bytes.length > 16384) return false;
    var input = CodedInputStream.newInstance(bytes);
    var seen = new HashSet<Integer>();
    int subCount = 0;
    try {
      int tag;
      while ((tag = input.readTag()) != 0) {
        int number = tag >>> 3, wire = tag & 7;
        var field = type.findFieldByNumber(number);
        if (field == null
            || field.getType() != FieldDescriptor.Type.UINT32
            || !field.isRepeated() && !seen.add(number)) return false;
        if (field.isRepeated()) {
          if (number != 11) return false;
          if (wire == 2) {
            var packed = CodedInputStream.newInstance(input.readByteArray());
            while (!packed.isAtEnd()) {
              if (++subCount > 2 || Long.compareUnsigned(packed.readUInt64(), 0xffffffffL) > 0)
                return false;
            }
          } else if (wire != 0
              || ++subCount > 2
              || Long.compareUnsigned(input.readUInt64(), 0xffffffffL) > 0) return false;
        } else if (wire != 0 || Long.compareUnsigned(input.readUInt64(), 0xffffffffL) > 0)
          return false;
      }
      return true;
    } catch (IOException invalid) {
      return false;
    }
  }

  private SelectedRelic46() {}
}
