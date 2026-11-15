package org.hkrpg.server;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Product prices are scoped: coin top-ups are free, original paid tiers stay unchanged. */
class SdkShopPriceRoutesTest {
  static class OnlineSession extends emu.lunarcore.server.game.GameSession {
    OnlineSession(emu.lunarcore.game.account.Account account) {
      super(null);
      setAccount(account);
      setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    }
  }

  static class OnlinePlayer extends emu.lunarcore.game.player.Player {
    final String account;
    final OnlineSession session;
    OnlinePlayer(String account, OnlineSession session) { this.account = account; this.session = session; }
    @Override public boolean isOnline() { return true; }
    @Override public String getAccountUid() { return account; }
    @Override public int getUid() { return 12345; }
    @Override public OnlineSession getSession() { return session; }
    @Override public void save() {}
  }

  @Test
  void localFreeOrderRequiresTheCorrectSessionAndReplaysWithoutASecondGrant() throws Exception {
    assertTrue(org.hkrpg.server.gameplay.FreeDreamCatalog.load(java.nio.file.Path.of("resources/4.6")));
    var account = new emu.lunarcore.game.account.Account();
    for (var pair : Map.of("uid", "test-account", "comboToken", "test-token").entrySet()) {
      var field = account.getClass().getDeclaredField(pair.getKey());
      field.setAccessible(true);
      field.set(account, pair.getValue());
    }
    var session = new OnlineSession(account);
    var player = new OnlinePlayer("test-account", session);
    var app = Javalin.create();
    SdkShopRoutes.register(app, request -> SdkShopRoutes.authenticated(request, player)
        ? org.hkrpg.server.gameplay.FreeDreamOrders.claim(player, request.product(), request.requestId())
        : new org.hkrpg.server.gameplay.FreeDreamOrders.Result(
            org.hkrpg.server.gameplay.FreeDreamOrders.Status.REJECTED, 0, "account-or-session-mismatch"));
    app.start("127.0.0.1", 0);
    try (var client = HttpClient.newHttpClient()) {
      String url = "http://127.0.0.1:" + app.port() + "/hkrpg_cn/mdk/luckycat/luckycat/createOrder";
      var body = new java.util.HashMap<String, String>(Map.of("open_id", "test-account",
          "combo_token", "test-token", "role_id", "12345", "request_id", "local-order-1",
          "product_id", "rpgchncoin300tier5"));
      for (int n = 0; n < 2; n++) {
        var result = order(client, url, body);
        assertEquals(0, result.get("retcode").getAsInt());
        assertEquals(330, player.getMcoin());
      }
      body.put("request_id", "local-order-2");
      body.put("combo_token", "wrong-token");
      assertNotEquals(0, order(client, url, body).get("retcode").getAsInt());
      body.put("combo_token", "test-token");
      body.put("product_id", "rpgchnmonthcardtier5");
      assertNotEquals(0, order(client, url, body).get("retcode").getAsInt());
      assertEquals(330, player.getMcoin());
    } finally {
      app.stop();
    }
  }

  private static com.google.gson.JsonObject order(HttpClient client, String url, Map<String, String> body)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(new com.google.gson.Gson().toJson(body))).build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  @Test
  void unknownOrdersAreRejectedLocallyWithoutRedirectingToAPaymentService() throws Exception {
    var app = Javalin.create();
    CnSdkRoutes.register(app);
    app.start("127.0.0.1", 0);
    try (var client = HttpClient.newHttpClient()) {
      var url = "http://127.0.0.1:" + app.port() + "/hkrpg_cn/mdk/luckycat/luckycat/createOrder";
      var request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{\"product_id\":\"unsupported-paid-product\"}"))
          .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertNotEquals(0, JsonParser.parseString(response.body()).getAsJsonObject().get("retcode").getAsInt());
      assertTrue(response.headers().firstValue("location").isEmpty());
    } finally {
      app.stop();
    }
  }

  @Test
  void localSdkServesAFreeTierAlongsideTheOriginalRecommendationTiers() throws Exception {
    var app = Javalin.create();
    CnSdkRoutes.register(app);
    app.start("127.0.0.1", 0);
    try (var client = HttpClient.newHttpClient()) {
      var url = "http://127.0.0.1:" + app.port() + "/hkrpg_cn/mdk/shopwindow/shopwindow/listPriceTierV2";
      var response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var data = JsonParser.parseString(response.body()).getAsJsonObject();
      assertEquals(0, data.get("retcode").getAsInt());
      var tiers = new HashMap<String, String>();
      for (var value : data.getAsJsonObject("data").getAsJsonArray("tiers")) {
        var tier = value.getAsJsonObject();
        tiers.put(tier.get("tier_id").getAsString(),
            tier.getAsJsonArray("t_price").get(0).getAsJsonObject().get("price").getAsString());
      }
      assertEquals("0", tiers.get("Local_Free"));
      assertEquals("600", tiers.get("Tier_1"));
      assertEquals("3000", tiers.get("Tier_5"));
      assertEquals("9800", tiers.get("Tier_15"));
    } finally {
      app.stop();
    }
  }
}
