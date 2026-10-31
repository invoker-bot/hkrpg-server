package org.hkrpg.server;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.server.http.HttpServer;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GachaWebRoutesTest {
  @TempDir Path directory;
  java.lang.reflect.Field configField;
  Object original;
  HttpServer server;
  HttpClient client;

  @BeforeEach void setup() throws Exception {
    configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    original = configField.get(null);
    var config = new Config();
    config.dataDir = directory.toString();
    configField.set(null, config);
    client = HttpClient.newHttpClient();
  }

  @AfterEach void cleanup() throws Exception {
    if (client != null) client.close();
    if (server != null) server.getApp().stop();
    configField.set(null, original);
  }

  @Test void detailAndHistoryOpenWithoutLoginOrExternalServices() throws Exception {
    server = new HttpServer(LunarCore.ServerType.DISPATCH);
    server.getApp().start("127.0.0.1", 0);
    for (String route : new String[] {"details", "history"}) {
      // The SDK adds query arguments. These pages must neither require nor echo account data.
      var url = "http://127.0.0.1:" + server.getApp().port() + "/gacha/" + route
          + "?gacha_id=2001&authkey=private-test-token&lang=zh-cn";
      var response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), route);
      assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));
      assertTrue(response.body().contains("<!doctype html>"));
      assertTrue(response.body().contains(route.equals("history") ? "跃迁记录" : "跃迁详情"));
      assertFalse(response.body().contains("private-test-token"));
      assertFalse(response.body().contains("<script"));
      assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
      // The server does not store per-draw history; do not misreport this as zero draws.
      if (route.equals("history")) assertTrue(response.body().contains("尚未保存逐次跃迁记录"));
    }
  }

  @Test void everyAdvertisedUrlIsUsableOnTheGameHttpListenerToo() throws Exception {
    server = new HttpServer(LunarCore.ServerType.GAME);
    server.getApp().start("127.0.0.1", 0);
    var config = LunarCore.getConfig().getHttpServer();
    config.publicPort = server.getApp().port();
    for (int id : new int[] {1001, 2001, 3001, 4001}) {
      for (String url : new String[] {GachaWebRoutes.detailUrl(id), GachaWebRoutes.historyUrl(id)}) {
        var response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), url);
      }
    }
  }

  @Test void publicOriginSupportsIpv6AndConfiguredBindFallbacks() {
    var config = LunarCore.getConfig().getHttpServer();
    config.publicAddress = "2001:db8::1";
    config.publicPort = 18443;
    config.useSSL = true;
    assertEquals("https://[2001:db8::1]:18443/gacha/history?gacha_id=3001", GachaWebRoutes.historyUrl(3001));
    config.publicAddress = null;
    config.publicPort = null;
    config.bindAddress = "127.0.0.1";
    config.bindPort = 18080;
    config.useSSL = false;
    assertEquals("http://127.0.0.1:18080/gacha/details?gacha_id=1001", GachaWebRoutes.detailUrl(1001));
  }

  @Test void detailWithoutAGameServiceExplainsWhyDataIsUnavailable() throws Exception {
    server = new HttpServer(LunarCore.ServerType.DISPATCH);
    server.getApp().start("127.0.0.1", 0);
    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
        + server.getApp().port() + "/gacha/details?gacha_id=2001")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertTrue(response.body().contains("卡池数据暂不可用"));
    assertFalse(response.body().contains("本服暂未提供网页形式的跃迁详情"));
  }

  @Test void detailsDescribeTheActivePoolsActualRatesAndItems() throws Exception {
    try (var resources = new org.hkrpg.server.gacha.GachaRarityResources()) {
      var gacha = new emu.lunarcore.game.gacha.GachaService(null) { @Override public void watch() {} };
      gacha.replaceBanners(java.util.List.of(new emu.lunarcore.game.gacha.GachaBanner().createBanner(
          3001, emu.lunarcore.game.gacha.GachaType.WeaponUp, 0, Integer.MAX_VALUE,
          new int[]{23000}, new int[]{21000})));
      var app = io.javalin.Javalin.create();
      try {
        GachaWebRoutes.register(app, () -> gacha);
        app.start("127.0.0.1", 0);
        var url = URI.create("http://127.0.0.1:" + app.port()
            + "/gacha/details?gacha_id=3001&authkey=private-test-token");
        var response = client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("光锥活动跃迁"));
        assertTrue(response.body().contains("0.800%"));
        assertTrue(response.body().contains("6.600%"));
        assertTrue(response.body().contains("75%"));
        assertTrue(response.body().contains("对象清单"));
        assertTrue(response.body().contains("物品 #23000"));
        assertFalse(response.body().contains("private-test-token"));
      } finally { app.stop(); }
    }
  }
}
