package org.hkrpg.server.protocol;

import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.IOException;
import java.util.List;
import org.hkrpg.server.gameplay.ConsumableUse;

/** Pinned 558/556; optional selections and unverified branches cannot consume items. */
public final class ItemUse46 {
  public static boolean handle(GameSession s, int command, byte[] bytes) throws IOException {
    if (!s.getProtocol().experimental() || command != s.getProtocol().clientCommand("UseItemCsReq"))
      return false;
    if (s.getState() != SessionState.ACTIVE) return true;
    var type = s.getProtocol().clientSchema().message("UseItemCsReq");
    boolean valid = Parkour46.wireValid(type, bytes, 0);
    var r = valid ? s.getProtocol().clientSchema().parse("UseItemCsReq", bytes) : null;
    int item = valid ? Gameplay46.number(r, "use_item_id") : 0;
    var result = new ConsumableUse.Result(Retcode.REQ_PARA_INVALID, List.of(), 0);
    if (valid) {
      var kind =
          (com.google.protobuf.Descriptors.EnumValueDescriptor)
              r.getField(type.findFieldByName("KANPPCMBJHG"));
      if (kind.getNumber() <= 1
          && Gameplay46.number(r, "optional_reward_id") == 0
          && !((Boolean) r.getField(type.findFieldByName("DGEKFCHEAPO"))))
        result =
            new ConsumableUse(s.getPlayer())
                .use(
                    item,
                    Gameplay46.number(r, "use_item_count"),
                    Gameplay46.number(r, "base_avatar_id"));
      else result = new ConsumableUse.Result(Retcode.ITEM_INVALID_USE, List.of(), 0);
    }
    var b = Gameplay46.builder(s, "UseItemScRsp");
    Gameplay46.set(b, "retcode", result.retcode().getVal());
    Gameplay46.set(b, "use_item_id", item);
    Gameplay46.set(b, "use_item_count", result.consumed());
    Gameplay46.set(b, "formula_id", result.formula());
    var items = ItemList.newInstance();
    result.items().forEach(i -> items.addItemList(i.toProto()));
    Gameplay46.set(
        b,
        "return_data",
        s.getProtocol()
            .clientSchema()
            .parse("ItemList", s.getProtocol().translate("ItemList", items.toByteArray(), true)));
    s.sendClientPacket("UseItemScRsp", b.build().toByteArray());
    return true;
  }

  private ItemUse46() {}
}
