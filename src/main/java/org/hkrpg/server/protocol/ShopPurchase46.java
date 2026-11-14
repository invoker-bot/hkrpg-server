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
    int bought = 0;
    try {
      var protocol = session.getProtocol();
      if (validWire(bytes)) {
        var parsed = parse(protocol, bytes);
        request = parsed.request();
        var shop = GameData.getShopExcelMap().get(request.getShopId());
        var goods = shop == null ? null : shop.getGoods().get(request.getGoodsId());
        if (request.getGoodsNum() > 0 && goods != null) {
          var result = session.getServer().getShopService().buy(session.getPlayer(),
              request.getShopId(), request.getGoodsId(), request.getGoodsNum(), request.getItemId(),
              parsed.choices(), java.time.Instant.now());
          received = result.items();
          code = result.code();
          bought = result.bought();
        }
      }
    } catch (IOException invalid) {
      code = Retcode.REQ_PARA_INVALID;
    } catch (RuntimeException failed) {
      emu.lunarcore.LunarCore.getLogger().warn("Shop purchase failed for player {}",
          session.getPlayer().getUid(), failed);
      code = Retcode.SYSTEM_BUSY;
    }
    if (code != Retcode.SUCC)
      org.hkrpg.server.GameDiagnostics.outcome(session, "BuyGoodsCsReq",
          request == null ? 0 : request.getGoodsId(), code, "shop-purchase-rejected",
          request == null ? "" : "shop=" + request.getShopId());
    reply(session, request, received, code, bought);
    return true;
  }

  static void reply(GameSession session, BuyGoodsCsReq request, List<GameItem> received, Retcode code) {
    reply(session, request, received, code, request == null ? 0 : request.getGoodsNum());
  }

  record Request(BuyGoodsCsReq request, List<Integer> choices) {}

  static boolean validWire(byte[] bytes) {
    if (bytes == null || bytes.length > 512) return false;
    var input = com.google.protobuf.CodedInputStream.newInstance(bytes);
    var seen = new java.util.HashSet<Integer>();
    int choices = 0;
    try {
      int tag;
      while ((tag = input.readTag()) != 0) {
        int field = tag >>> 3, wire = tag & 7;
        if (!java.util.Set.of(1,3,4,9,13,15).contains(field)) return false;
        if (field == 4 && wire == 2) {
          var packed = com.google.protobuf.CodedInputStream.newInstance(input.readByteArray());
          while (!packed.isAtEnd())
            if (++choices > 32 || Long.compareUnsigned(packed.readUInt64(),Integer.MAX_VALUE) > 0) return false;
        } else {
          if (wire != 0 || field != 4 && !seen.add(field)) return false;
          if (Long.compareUnsigned(input.readUInt64(),Integer.MAX_VALUE) > 0) return false;
          if (field == 4 && ++choices > 32) return false;
        }
      }
      return input.isAtEnd();
    } catch (IOException invalid) { return false; }
  }

  static Request parse(ProtocolBridge protocol, byte[] bytes) throws IOException {
    var nativeRequest = protocol.clientSchema().parse("BuyGoodsCsReq", bytes);
    var type = nativeRequest.getDescriptorForType();
    // Pinned client ShopModule._BuyGoodsAsync.optionalRewardIDList -> request offset 0x10,
    // GICAKCOPBBO codec 0x22 (packed field 4). The generated legacy type has no equivalent.
    var request = BuyGoodsCsReq.newInstance()
        .setShopId((int) nativeRequest.getField(type.findFieldByNumber(1)))
        .setGoodsNum((int) nativeRequest.getField(type.findFieldByNumber(3)))
        .setGoodsId((int) nativeRequest.getField(type.findFieldByNumber(9)))
        .setItemId((int) nativeRequest.getField(type.findFieldByNumber(13)))
        .setServerEntityId((int) nativeRequest.getField(type.findFieldByNumber(15)));
    var choices = new java.util.ArrayList<Integer>();
    var field = type.findFieldByNumber(4);
    for (int i = 0; i < nativeRequest.getRepeatedFieldCount(field); i++)
      choices.add((Integer) nativeRequest.getRepeatedField(field, i));
    return new Request(request, List.copyOf(choices));
  }

  static void reply(GameSession session, BuyGoodsCsReq request, List<GameItem> received, Retcode code, int bought) {
    var response = BuyGoodsScRsp.newInstance().setRetcode(code.getVal());
    if (request != null) response.setShopId(request.getShopId()).setGoodsId(request.getGoodsId());
    if (code == Retcode.SUCC && request != null && received != null) {
      var items = ItemList.newInstance();
      received.forEach(item -> items.addItemList(item.toProto()));
      response.setGoodsBuyTimes(bought).setReturnItemList(items);
    }
    session.sendClientPacket("BuyGoodsScRsp",
        session.getProtocol().translate("BuyGoodsScRsp", response.toByteArray(), true));
  }

  private ShopPurchase46() {}
}
