package org.hkrpg.server.protocol;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.proto.BuyGoodsCsReqOuterClass.BuyGoodsCsReq;
import emu.lunarcore.proto.BuyGoodsScRspOuterClass.BuyGoodsScRsp;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.List;

/** Honey fe00bdb CmdShopType: native 1505/1575. The legacy response command is unassigned. */
final class ShopPurchase46 {
  static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental() || command != 1505) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var code = Retcode.REQ_PARA_INVALID;
    BuyGoodsCsReq request = null;
    List<GameItem> received = null;
    try {
      var protocol = session.getProtocol();
      var type = protocol.clientSchema().message("BuyGoodsCsReq");
      if (bytes != null && bytes.length <= 16384 && Parkour46.wireValid(type, bytes, 0)) {
        var nativeRequest = protocol.clientSchema().parse("BuyGoodsCsReq", bytes);
        // The source does not identify this selector list; do not silently buy a default variant.
        if (nativeRequest.getRepeatedFieldCount(type.findFieldByName("ALHBEKKLJKK")) == 0) {
          request = BuyGoodsCsReq.parseFrom(protocol.inbound(command, bytes).body());
          var shop = GameData.getShopExcelMap().get(request.getShopId());
          var goods = shop == null ? null : shop.getGoods().get(request.getGoodsId());
          // As in the pinned handler, the catalog's goods ID determines both reward and price.
          if (request.getGoodsNum() > 0 && goods != null) {
            received = session.getServer().getShopService().buyGoods(session.getPlayer(),
                request.getShopId(), request.getGoodsId(), request.getGoodsNum());
            if (received != null) code = Retcode.SUCC;
          }
        }
      }
    } catch (IOException | IllegalArgumentException invalid) {
      code = Retcode.REQ_PARA_INVALID;
    } catch (RuntimeException failed) {
      emu.lunarcore.LunarCore.getLogger().warn("Shop purchase failed for player {}",
          session.getPlayer().getUid(), failed);
      code = Retcode.SYSTEM_BUSY;
    }
    reply(session, request, received, code);
    return true;
  }

  static void reply(GameSession session, BuyGoodsCsReq request, List<GameItem> received, Retcode code) {
    var response = BuyGoodsScRsp.newInstance().setRetcode(code.getVal());
    if (request != null) response.setShopId(request.getShopId()).setGoodsId(request.getGoodsId());
    if (code == Retcode.SUCC && request != null && received != null) {
      var items = ItemList.newInstance();
      received.forEach(item -> items.addItemList(item.toProto()));
      response.setGoodsBuyTimes(request.getGoodsNum()).setReturnItemList(items);
    }
    session.sendClientPacket("BuyGoodsScRsp",
        session.getProtocol().translate("BuyGoodsScRsp", response.toByteArray(), true));
  }

  private ShopPurchase46() {}
}
