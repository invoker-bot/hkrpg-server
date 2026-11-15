package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Local, no-money fulfillment; HTTP callers authenticate the active player first. */
public final class FreeDreamOrders {
  public enum Status { APPLIED, DUPLICATE, REJECTED }
  public record Result(Status status, int amount, String reason) {}
  private static final Map<Player, Boolean> BLOCKED = Collections.synchronizedMap(new WeakHashMap<>());

  public static Result claim(Player player, String product, String requestId) {
    var offer = product == null ? null : FreeDreamCatalog.find(product);
    if (player == null || offer == null || requestId == null
        || !requestId.matches("[A-Za-z0-9_-]{1,128}"))
      return new Result(Status.REJECTED, 0, "invalid-order");
    String key = digest(requestId);
    String value = offer.productId() + ":" + offer.amount();
    synchronized (player) {
      if (BLOCKED.containsKey(player))
        return new Result(Status.REJECTED, 0, "uncertain-save");
      var receipts = player.getGameplay().getFreeDreamReceipts();
      String existing = receipts.get(key);
      if (existing != null) return existing.equals(value)
          ? new Result(Status.DUPLICATE, offer.amount(), "already-granted")
          : new Result(Status.REJECTED, 0, "order-conflict");
      if (receipts.size() >= 4096 || !player.tryChangeMCoin(offer.amount()))
        return new Result(Status.REJECTED, 0, "limit");
      receipts.put(key, value);
      try {
        player.save();
      } catch (RuntimeException failed) {
        receipts.remove(key);
        if (!player.tryChangeMCoin(-offer.amount())) throw new IllegalStateException("wallet rollback failed", failed);
        BLOCKED.put(player, true);
        return new Result(Status.REJECTED, 0, "uncertain-save");
      }
      return new Result(Status.APPLIED, offer.amount(), "granted");
    }
  }

  private static String digest(String value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
  private FreeDreamOrders() {}
}
