package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import org.hkrpg.server.GameDiagnostics;
import org.hkrpg.server.gameplay.FreeDreamCatalog;
import org.hkrpg.server.gameplay.FreeDreamOrders;

/** Native product query; only locally configured free coin offers are synthesized. */
public final class FreeDreamProduct46 {
  static boolean handle(GameSession session, int command, byte[] bytes) {
    if (!session.getProtocol().experimental()) return false;
    if (command == 4183) return confirm(session, bytes);
    if (command != 96) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    var response = Gameplay46.builder(session, "QueryProductInfoScRsp");
    if (bytes == null || bytes.length != 0 || FreeDreamCatalog.offers().isEmpty()) {
      Gameplay46.set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
      GameDiagnostics.outcome(session, "QueryProductInfoCsReq", 0, Retcode.REQ_PARA_INVALID,
          "free-dream-query-rejected", bytes == null || bytes.length != 0 ? "invalid-wire" : "missing-catalog");
    } else {
      session.ensureLocalDreamOffers(FreeDreamCatalog.offers().stream().map(FreeDreamCatalog.Offer::productId).toList());
      var field = response.getDescriptorForType().findFieldByName("product_list");
      var product = field.getMessageType();
      for (var offer : FreeDreamCatalog.offers()) {
        var row = DynamicMessage.newBuilder(product);
        Gameplay46.set(row, "product_id", offer.productId());
        Gameplay46.set(row, "price_tier", FreeDreamCatalog.FREE_TIER);
        var kind = product.findFieldByName("gift_type");
        row.setField(kind, kind.getEnumType().findValueByNumber(1));
        Gameplay46.set(row, "begin_time", 1577854800L);
        Gameplay46.set(row, "end_time", 4070926800L);
        response.addRepeatedField(field, row.build());
      }
    }
    session.sendClientPacket("QueryProductInfoScRsp", response.build().toByteArray());
    return true;
  }

  private static boolean confirm(GameSession session, byte[] bytes) {
    if (session.getState() != SessionState.ACTIVE) return true;
    boolean valid = false;
    int count = 0;
    java.util.List<String> productIds = new java.util.ArrayList<>();
    try {
      if (bytes != null && bytes.length <= 2048) {
        var request = session.getProtocol().clientSchema().parse("CancelAutoConversionCacheCsReq", bytes);
        var desc = request.getDescriptorForType();
        var ids = desc.findFieldByName("OKFMBDEECCH");
        var extra = desc.findFieldByName("JALHKMEOOPN");
        var type = desc.findFieldByName("type");
        count = request.getRepeatedFieldCount(ids);
        valid = request.getUnknownFields().asMap().isEmpty() && count > 0 && count <= 32
            && request.getRepeatedFieldCount(extra) == 0
            && ((com.google.protobuf.Descriptors.EnumValueDescriptor) request.getField(type)).getNumber() == 2;
        for (int i = 0; valid && i < count; i++)
          productIds.add((String) request.getRepeatedField(ids, i));
      }
    } catch (java.io.IOException | RuntimeException invalid) {
      valid = false;
    }
    if (valid && count == 1 && FreeDreamCatalog.find(productIds.getFirst()) != null)
      return claimFromSession(session, productIds.getFirst());
    if (!session.hasLocalRechargeNotice()) return false;
    valid = valid && productIds.stream().allMatch(FreeDreamCatalog.FREE_TIER::equals)
        && session.consumeLocalRechargeNotices(count);
    var response = Gameplay46.builder(session, "CancelAutoConversionCacheScRsp");
    if (!valid) {
      Gameplay46.set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
      GameDiagnostics.outcome(session, "CancelAutoConversionCacheCsReq", 0,
          Retcode.REQ_PARA_INVALID, "local-recharge-confirm-rejected", "invalid-confirmation");
    }
    session.sendClientPacket("CancelAutoConversionCacheScRsp", response.build().toByteArray());
    return true;
  }

  /** Only the six fixed product IDs sent by the local client use this session-bound path. */
  private static boolean claimFromSession(GameSession session, String productId) {
    synchronized (session) {
      var player = session.getPlayer();
      String requestId = session.localDreamOfferRequest(productId);
      var result = player == null || player.getSession() != session || requestId == null
          ? new FreeDreamOrders.Result(FreeDreamOrders.Status.REJECTED, 0, "missing-session-offer")
          : FreeDreamOrders.claim(player, productId, requestId);
      var response = Gameplay46.builder(session, "CancelAutoConversionCacheScRsp");
      if (result.status() == FreeDreamOrders.Status.REJECTED) {
        Gameplay46.set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
        GameDiagnostics.outcome(session, "CancelAutoConversionCacheCsReq", 0,
            Retcode.REQ_PARA_INVALID, "local-recharge-claim-rejected", result.reason());
      } else if (result.status() == FreeDreamOrders.Status.APPLIED) {
        try { granted(player, productId, result.amount()); }
        catch (RuntimeException failure) {
          GameDiagnostics.outcome(session, "CancelAutoConversionCacheCsReq", 0,
              Retcode.SYSTEM_BUSY, "local-recharge-notification-failed", "");
        }
      }
      session.sendClientPacket("CancelAutoConversionCacheScRsp", response.build().toByteArray());
      return true;
    }
  }

  /** The wallet is already persisted; this only publishes the exact local grant. */
  public static void granted(emu.lunarcore.game.player.Player player, String productId, int amount) {
    var session = player.getSession();
    if (session == null || !session.getProtocol().experimental() || amount <= 0) return;
    if (player.isOnline()) player.sendPacket(new emu.lunarcore.server.packet.send.PacketPlayerSyncScNotify(player));
    var response = Gameplay46.builder(session, "RechargeSuccNotify");
    Gameplay46.set(response, "product_id", productId);
    Gameplay46.set(response, "price_tier", FreeDreamCatalog.FREE_TIER);
    var itemListField = response.getDescriptorForType().findFieldByName("item_list");
    var items = DynamicMessage.newBuilder(itemListField.getMessageType());
    var entries = items.getDescriptorForType().findFieldByName("item_list");
    var item = DynamicMessage.newBuilder(entries.getMessageType());
    Gameplay46.set(item, "item_id", 3);
    Gameplay46.set(item, "num", amount);
    items.addRepeatedField(entries, item.build());
    response.setField(itemListField, items.build());
    session.markLocalRechargeNotice(productId);
    session.sendClientPacket("RechargeSuccNotify", response.build().toByteArray());
  }
  private FreeDreamProduct46() {}
}
