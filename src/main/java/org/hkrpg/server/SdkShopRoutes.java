package org.hkrpg.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.http.Handler;
import emu.lunarcore.LunarCore;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.util.AuthTokens;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hkrpg.server.gameplay.FreeDreamCatalog;
import org.hkrpg.server.gameplay.FreeDreamOrders;
import org.hkrpg.server.protocol.FreeDreamProduct46;

/** Local SDK catalog endpoints. No request is sent to a payment provider. */
final class SdkShopRoutes {
  private static final Map<String, Integer> PRICES = prices();
  @FunctionalInterface interface Fulfill { FreeDreamOrders.Result run(SdkOrderRequest request); }

  static void register(Javalin app) {
    register(app, SdkShopRoutes::claim);
  }

  static void register(Javalin app, Fulfill fulfill) {
    both(app, "/hkrpg_cn/mdk/shopwindow/shopwindow/getCurrencyAndCountryByIp",
        ctx -> ctx.contentType("application/json").result(new com.google.gson.Gson().toJson(ok(Map.of("currency", "CNY", "country", "CN")))));
    Handler tiers = ctx -> {
      ctx.header("Cache-Control", "no-store");
      ctx.contentType("application/json").result(priceReply().toString());
    };
    both(app, "/hkrpg_cn/mdk/shopwindow/shopwindow/listPriceTier", tiers);
    both(app, "/hkrpg_cn/mdk/shopwindow/shopwindow/listPriceTierV2", tiers);
    both(app, "/hkrpg_cn/mdk/luckycat/luckycat/listPriceTier", tiers);
    Handler emptyGoods = ctx -> ctx.contentType("application/json")
        .result(new com.google.gson.Gson().toJson(ok(Map.of("goods_list", java.util.List.of()))));
    both(app, "/hkrpg_cn/mdk/shopwindow/shopwindow/listGoods", emptyGoods);
    both(app, "/hkrpg_cn/mdk/luckycat/luckycat/queryGoods", emptyGoods);
    both(app, "/mm-backpack/v1/listGoods", emptyGoods);
    both(app, "/goods", emptyGoods);
    both(app, "/goods_load_all", emptyGoods);
    Handler noPlatforms = ctx -> json(ctx, ok(Map.of("pay_plat", java.util.List.of())));
    for (String path : java.util.List.of("/hkrpg_cn/mdk/luckycat/luckycat/listPayPlat",
        "/hkrpg_cn/mdk/tally/tally/listPayPlat", "/hkrpg_cn/mdk/luckycat/luckycat/listAppPayPlat",
        "/plutus/api/v2/listPayPlat")) both(app, path, noPlatforms);
    Handler empty = ctx -> json(ctx, ok(Map.of()));
    for (String path : java.util.List.of("/hkrpg_cn/mdk/luckycat/luckycat/detectPay",
        "/hkrpg_cn/mdk/luckycat/luckycat/firstPayment",
        "/hkrpg_cn/mdk/luckycat/luckycat/preOrderValidation")) both(app, path, empty);
    Handler order = ctx -> {
      try {
        var request = SdkOrderRequest.parse(ctx);
        var result = fulfill.run(request);
        if (result.status() == FreeDreamOrders.Status.REJECTED) {
          GameDiagnostics.sdkShopRejected(result.reason());
          json(ctx, unavailable());
          return;
        }
        // This is a local receipt only; the checkout page below closes without a payment provider.
        String number = java.util.UUID.randomUUID().toString().replace("-", "");
        String token = java.util.UUID.randomUUID().toString();
        json(ctx, ok(Map.of("order_no", number, "session_token", token, "jwt", token)));
      } catch (Exception invalid) {
        GameDiagnostics.sdkShopRejected("invalid-order-request");
        json(ctx, unavailable());
      }
    };
    both(app, "/hkrpg_cn/mdk/luckycat/luckycat/createOrder", order);
    both(app, "/hkrpg_cn/combo/cashier/cashier/createOrder", order);
    both(app, "/hkrpg_cn/mdk/luckycat/luckycat/checkOrder", ctx -> json(ctx, unavailable()));
    both(app, "/hkrpg_cn/combo/guard/api/checkPay", ctx -> json(ctx, unavailable()));
    Handler close = ctx -> ctx.contentType("text/html; charset=utf-8")
        .header("Cache-Control", "no-store")
        .result("<!doctype html><meta charset=\"utf-8\"><script>location.href='uniwebview://close'</script>");
    app.get("/hkrpg_cn/payment/global-platform/checkout/index.html", close);
    app.get("/hkrpg_cn/payment/platform/checkout-shell/index.html", close);
    app.get("/hkrpg_cn/payment/platform/checkout-order/index.html", close);
  }

  private static void json(io.javalin.http.Context ctx, Object value) {
    ctx.contentType("application/json").result(new com.google.gson.Gson().toJson(value));
  }

  private static Map<String, Object> unavailable() {
    return Map.of("retcode", -1, "message", "Local order unavailable", "data", Map.of());
  }

  static FreeDreamOrders.Result claim(SdkOrderRequest request) {
    if (request.product() == null || FreeDreamCatalog.find(request.product()) == null)
      return new FreeDreamOrders.Result(FreeDreamOrders.Status.REJECTED, 0, "unsupported-product");
    if (request.account() == null || request.token() == null)
      return new FreeDreamOrders.Result(FreeDreamOrders.Status.REJECTED, 0, "missing-identity-or-order");
    var server = LunarCore.getGameServer();
    Player player = server == null ? null : server.getOnlinePlayerByAccountId(request.account());
    if (!authenticated(request, player))
      return new FreeDreamOrders.Result(FreeDreamOrders.Status.REJECTED, 0, "account-or-session-mismatch");
    String requestId = request.requestId();
    if (requestId == null || requestId.isBlank()) requestId = player.getSession().localDreamOfferRequest(request.product());
    if (requestId == null)
      return new FreeDreamOrders.Result(FreeDreamOrders.Status.REJECTED, 0, "missing-offer-request");
    var result = FreeDreamOrders.claim(player, request.product(), requestId);
    if (result.status() == FreeDreamOrders.Status.APPLIED) {
      try { FreeDreamProduct46.granted(player, request.product(), result.amount()); }
      catch (RuntimeException failure) { GameDiagnostics.sdkShopRejected("notification-failed"); }
    }
    return result;
  }

  static boolean authenticated(SdkOrderRequest request, Player player) {
    if (request.account() == null || request.token() == null || player == null || !player.isOnline()) return false;
    var session = player.getSession();
    if (session == null || session.getState() != SessionState.ACTIVE || session.getAccount() == null
        || !request.account().equals(player.getAccountUid())
        || !request.account().equals(session.getAccount().getUid())
        || !AuthTokens.matches(session.getAccount().getComboToken(), request.token())) return false;
    return request.roleId() == null || request.roleId().equals(Integer.toString(player.getUid()));
  }

  private static void both(Javalin app, String path, Handler handler) {
    app.get(path, handler);
    app.post(path, handler);
  }

  private static Map<String, Object> ok(Object data) {
    return Map.of("retcode", 0, "message", "OK", "data", data);
  }

  static JsonObject priceReply() {
    var rows = new JsonArray();
    for (var entry : PRICES.entrySet()) {
      var amount = new JsonObject();
      amount.addProperty("enable", 1);
      amount.addProperty("country", "CN");
      amount.addProperty("currency", "CNY");
      amount.addProperty("price", Integer.toString(entry.getValue()));
      amount.addProperty("symbol", "¥");
      amount.addProperty("amount_display", "");
      var amounts = new JsonArray();
      amounts.add(amount);
      var row = new JsonObject();
      row.addProperty("tier_id", entry.getKey());
      row.add("t_price", amounts);
      rows.add(row);
    }
    var data = new JsonObject();
    data.addProperty("suggest_currency", "");
    data.addProperty("price_tier_version", "2026111501");
    data.add("tiers", rows);
    var result = new JsonObject();
    result.addProperty("retcode", 0);
    result.addProperty("message", "OK");
    result.add("data", data);
    return result;
  }

  private static Map<String, Integer> prices() {
    try (var input = SdkShopRoutes.class.getResourceAsStream("/sdk-shop-prices.json")) {
      if (input == null) throw new IllegalStateException("Missing SDK price tiers");
      var data = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
      var result = new LinkedHashMap<String, Integer>();
      for (var row : data.entrySet()) {
        int value = row.getValue().getAsInt();
        if (!row.getKey().matches("[A-Za-z0-9_]{1,64}") || value < 0)
          throw new IllegalStateException("Invalid SDK price tier");
        result.put(row.getKey(), value);
      }
      if (result.size() != 88 || result.putIfAbsent(FreeDreamCatalog.FREE_TIER, 0) != null)
        throw new IllegalStateException("Unexpected SDK price tiers");
      return java.util.Collections.unmodifiableMap(result);
    } catch (java.io.IOException | RuntimeException invalid) {
      throw new IllegalStateException("Cannot read SDK price tiers", invalid);
    }
  }
  private SdkShopRoutes() {}
}
