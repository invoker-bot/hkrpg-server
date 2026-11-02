package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.hkrpg.server.gm.GmModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GmRoutesTest {
  @Test void historicalActivitiesHaveVersionPhaseSearchAndRerunOperations() throws Exception {
    assertEquals(401,request("GET","/gm/api/activities/history",null,false).statusCode());
    openSession();
    var response=request("GET","/gm/api/activities/history?version=1.1&limit=1",null,true);
    assertEquals(200,response.statusCode(),response.body());
    assertTrue(json(response).getAsJsonObject("data").has("versions"));
    assertEquals(400,request("GET","/gm/api/activities/history?version=4.7",null,true).statusCode());
    assertEquals(400,request("GET","/gm/api/activities/history?phase=invalid",null,true).statusCode());
    String body="{\"requestId\":\""+UUID.randomUUID()+"\",\"action\":\"activity-rerun\",\"activityKey\":\"1.1-gift-of-odyssey\",\"durationDays\":7}";
    assertEquals(200,request("POST","/gm/api/operate",body,true).statusCode());
    assertEquals(200,request("POST","/gm/api/operate",body,true).statusCode());
    assertEquals(1,executions.get());
  }
  @TempDir Path directory;
  Javalin app;
  HttpClient client;
  String base;
  String cookie;
  String csrf;
  AtomicInteger executions = new AtomicInteger();
  AtomicLong now = new AtomicLong(System.currentTimeMillis());
  List<ItemShortcut> additionalShortcuts = List.of();
  static final String KEY = "a".repeat(43);

  @BeforeEach
  void start() throws Exception {
    app = Javalin.create();
    GmRoutes.register(
        app,
        new GmBackend() {
          public Status status() {
            return new Status(true, true, 1, 10, 128, 2048);
          }

          public List<PlayerRow> players() {
            return List.of(new PlayerRow(10001, "开拓者", 5, 0, 10000, 10000001, false));
          }

          public List<CatalogItem> catalog() {
            return List.of(new CatalogItem(101, "星琼", "item", 5, 0, 0));
          }

          public List<CommandRow> commands() {
            return List.of(new CommandRow("heal", "/heal", true, false));
          }

          public List<BannerRow> bannerHistory() {
            return List.of(
                new BannerRow("1.1-2005", "1.1", "上半", "零号协议", 2005, "AvatarUp",
                    List.of(new BannerItem(1006, "银狼")), List.of(), true, "未配置", 0, 0),
                new BannerRow("1.0-2001", "1.0", "上半", "蝶立锋锷", 2001, "AvatarUp",
                    List.of(new BannerItem(1102, "希儿")), List.of(), true, "未配置", 0, 0),
                new BannerRow("1.0-3001", "1.0", "上半", "流光定影", 3001, "WeaponUp",
                    List.of(new BannerItem(23001, "于夜色中")), List.of(), true, "未配置", 0, 0));
          }

          public List<CurrentBannerRow> currentBanners() {
            return List.of(
                new CurrentBannerRow(1001,"Normal","常驻跃迁","","","",List.of(),List.of(),
                    "active","正在开放",0,0,true,false,true,false),
                new CurrentBannerRow(4001,"Newbie","新手跃迁","","","",List.of(),List.of(),
                    "active","正在开放",0,0,true,false,true,false),
                new CurrentBannerRow(99999,"AvatarUp","自定义卡池 #99999","","1.1","上半",
                    List.of(new BannerItem(1006,"银狼")),List.of(),"stopped","已停止",1,2000000000,false,true,false,true));
          }

          public List<ShopGoodsRow> shopGoods() {
            return List.of(
                new ShopGoodsRow(101,1,"星芒兑换",101001,101,"星轨通票",1,
                    List.of(new ShopPrice(252,"未熄的星芒",20)),0,"",true,false),
                new ShopGoodsRow(102,1,"余烬兑换",102001,101,"星轨通票",1,
                    List.of(new ShopPrice(253,"未熄的余烬",90)),5,"MONTH",false,false));
          }

          public List<ItemShortcut> itemShortcuts() {
            var rows = new java.util.ArrayList<>(List.of(new ItemShortcut(new CatalogItem(221002,"列车主题","item",4,0,0),"theme",
                List.of("hidden","collectible"),List.of("PhoneThemeConfig：解锁后显示"),true,"每次1件",1),
                new ItemShortcut(new CatalogItem(254001,"限定手机壳","item",4,0,0),"case",
                    List.of("limited","collectible"),List.of("活动排期"),false,"仅供查询",1)));
            rows.addAll(additionalShortcuts);
            return List.copyOf(rows);
          }

          public List<ActivityRow> activities() {
            return List.of(
                new ActivityRow(30013,30013,"往期活动", "event",List.of(new ActivityModuleRow(3001301,false,true,false,"active",1,2)),"active","正在开放",1,2,true,false,true,"保留进度",""),
                new ActivityRow(30014,30014,"常驻活动", "resident",List.of(),"stopped","已关闭",1,2,true,true,false,"保留进度",""));
          }

          public Result execute(Operation op) {
            executions.incrementAndGet();
            return Result.completed("已执行 " + op.action());
          }
        },
        directory,
        new Clock() {
          public ZoneId getZone() {
            return ZoneId.of("UTC");
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return Instant.ofEpochMilli(now.get());
          }
        });
    app.start("127.0.0.1", 0);
    base = "http://127.0.0.1:" + app.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stop() {
    client.close();
    app.stop();
  }

  @Test
  void activityCatalogRequiresSessionAndSupportsGlobalScheduleOperations() throws Exception {
    assertEquals(401,request("GET","/gm/api/activities",null,false).statusCode());
    openSession();
    assertEquals(200,request("GET","/gm/api/activities?limit=1",null,true).statusCode());
    for(var query:List.of("q=3001301","q=往期","type=event&state=active","q=30013")){
      var page=json(request("GET","/gm/api/activities?"+query,null,true)).getAsJsonObject("data");
      assertEquals(1,page.get("total").getAsInt());assertEquals(30013,page.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsInt());
    }
    assertEquals(0,json(request("GET","/gm/api/activities?offset=2",null,true)).getAsJsonObject("data").getAsJsonArray("items").size());
    assertEquals(400,request("GET","/gm/api/activities?state=bad",null,true).statusCode());
    assertEquals(400,request("GET","/gm/api/activities?type=bad",null,true).statusCode());
    assertEquals(400,request("GET","/gm/api/activities?limit=101",null,true).statusCode());
    assertEquals(200,request("GET","/gm/activities.js",null,false).statusCode());
    String body="{\"requestId\":\""+UUID.randomUUID()+"\",\"action\":\"activity-open\",\"activityId\":30013,\"durationDays\":7}";
    assertEquals(401,request("POST","/gm/api/operate",body,false).statusCode());
    var first=request("POST","/gm/api/operate",body,true);
    assertEquals(200,first.statusCode(),first.body());
    assertEquals(first.body(),request("POST","/gm/api/operate",body,true).body());
    assertEquals(1,executions.get());
    assertEquals(400,request("POST","/gm/api/operate",body.replace("\"durationDays\":7","\"durationDays\":0"),true).statusCode());
    assertEquals(400,request("POST","/gm/api/operate",body.replace("\"durationDays\":7","\"uid\":10001"),true).statusCode());
  }

  @Test
  void clothingCanBeFoundByCommonWordsAndTypeNamesWithoutChangingOtherFilters() throws Exception {
    additionalShortcuts=List.of(
        new ItemShortcut(new CatalogItem(1100101,"冬去煦至","item",5,0,0),"skin",
            List.of("limited","collectible"),List.of("原始物品"),true,"每次1件",1),
        new ItemShortcut(new CatalogItem(227001,"救世主的桂冠","item",4,0,0),"outfit",
            List.of("collectible"),List.of("原始物品"),true,"每次1件",1),
        new ItemShortcut(new CatalogItem(252003,"开拓岁月","item",4,0,0),"pam",
            List.of("collectible"),List.of("原始物品"),true,"每次1件",1));
    openSession();
    for(String word:List.of("服饰","服装","衣服"))
      assertEquals(3,json(request("GET","/gm/api/item-shortcuts?q="+word,null,true)).getAsJsonObject("data").get("total").getAsInt(),word);
    assertEquals(2,json(request("GET","/gm/api/item-shortcuts?q=皮肤",null,true)).getAsJsonObject("data").get("total").getAsInt());
    assertEquals(1,json(request("GET","/gm/api/item-shortcuts?q=开拓者装扮",null,true)).getAsJsonObject("data").get("total").getAsInt());
    assertEquals(1,json(request("GET","/gm/api/item-shortcuts?type=skin&tag=limited&q=服饰",null,true)).getAsJsonObject("data").get("total").getAsInt());
    assertEquals(0,json(request("GET","/gm/api/item-shortcuts?type=theme&q=服饰",null,true)).getAsJsonObject("data").get("total").getAsInt());
    assertEquals(0,executions.get());
  }

  @Test
  void itemShortcutsRequireSessionAndValidateFilters() throws Exception {
    assertEquals(401,request("GET","/gm/api/item-shortcuts",null,false).statusCode());
    openSession();
    var response=request("GET","/gm/api/item-shortcuts?limit=1",null,true);
    assertEquals(200,response.statusCode(),response.body());
    assertTrue(json(response).getAsJsonObject("data").has("types"));
    assertEquals(400,request("GET","/gm/api/item-shortcuts?tag=unknown",null,true).statusCode());
    assertEquals(400,request("GET","/gm/api/item-shortcuts?type=unknown",null,true).statusCode());
    assertEquals(200,request("GET","/gm/shortcuts.js",null,false).statusCode());
    var data=json(request("GET","/gm/api/item-shortcuts?type=theme&tag=hidden&q=列车",null,true)).getAsJsonObject("data");
    assertEquals(1,data.get("total").getAsInt());
    assertEquals(221002,data.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("item").get("id").getAsInt());
    assertEquals(1,json(request("GET","/gm/api/item-shortcuts?q=活动排期",null,true)).getAsJsonObject("data").get("total").getAsInt());
    assertEquals(0,json(request("GET","/gm/api/item-shortcuts?type=theme&offset=1",null,true)).getAsJsonObject("data").getAsJsonArray("items").size());
    assertEquals(400,request("GET","/gm/api/item-shortcuts?limit=101",null,true).statusCode());
    var body="{\"requestId\":\""+UUID.randomUUID()+"\",\"action\":\"shortcut-grant\",\"uid\":10001,\"itemId\":221002,\"amount\":1}";
    assertEquals(401,request("POST","/gm/api/operate",body,false).statusCode());
    assertEquals(200,request("POST","/gm/api/operate",body,true).statusCode());
    assertEquals(200,request("POST","/gm/api/operate",body,true).statusCode());
    assertEquals(1,executions.get());
  }

  @Test
  void shopCatalogRequiresSessionAndServesItsModule() throws Exception {
    assertEquals(401, request("GET", "/gm/api/shops", null, false).statusCode());
    openSession();
    var response = request("GET", "/gm/api/shops?limit=1", null, true);
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(json(response).getAsJsonObject("data").has("shops"));
    assertEquals(200, request("GET", "/gm/shops.js", null, false).statusCode());
    assertEquals(400, request("GET", "/gm/api/shops?state=unknown", null, true).statusCode());
    assertEquals(400, request("GET", "/gm/api/shops?shopId=-1", null, true).statusCode());
    assertEquals(400, request("GET", "/gm/api/shops?limit=101", null, true).statusCode());
    var listed = json(request("GET", "/gm/api/shops?state=listed&q=星轨", null, true)).getAsJsonObject("data");
    assertEquals(1, listed.get("total").getAsInt());
    assertEquals(101, listed.getAsJsonArray("items").get(0).getAsJsonObject().get("shopId").getAsInt());
    var shop = json(request("GET", "/gm/api/shops?shopId=102&state=unlisted", null, true)).getAsJsonObject("data");
    assertEquals(1, shop.get("total").getAsInt());
    assertEquals(2, shop.getAsJsonArray("shops").size());
    assertTrue(request("GET", "/gm/api/shops?q=102001", null, true).body().contains("未熄的余烬"));
    var page = json(request("GET", "/gm/api/shops?offset=1&limit=1", null, true)).getAsJsonObject("data");
    assertEquals(2, page.get("total").getAsInt());
    assertEquals(102001, page.getAsJsonArray("items").get(0).getAsJsonObject().get("goodsId").getAsInt());
    assertEquals(0, json(request("GET", "/gm/api/shops?shopId=999", null, true)).getAsJsonObject("data").get("total").getAsInt());
  }

  @Test
  void shopListingOperationsAreGlobalCsrfProtectedAndDeduplicated() throws Exception {
    openSession();
    for (String action : List.of("shop-list", "shop-unlist")) {
      String body = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"action\":\"" + action
          + "\",\"shopGoods\":[{\"shopId\":101,\"goodsId\":101001}]}";
      assertEquals(401, request("POST", "/gm/api/operate", body, false).statusCode());
      var missingCsrf = HttpRequest.newBuilder(URI.create(base + "/gm/api/operate"))
          .header("Cookie", cookie).header("Origin", base).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      assertEquals(403, client.send(missingCsrf, HttpResponse.BodyHandlers.ofString()).statusCode());
      int before = executions.get();
      var result = request("POST", "/gm/api/operate", body, true);
      assertEquals(200, result.statusCode(), result.body());
      assertEquals(result.body(), request("POST", "/gm/api/operate", body, true).body());
      assertEquals(before + 1, executions.get());
      assertEquals(400, request("POST", "/gm/api/operate", body.replace("101001}]", "101001}],\"uid\":10001"), true).statusCode());
    }
    assertTrue(request("GET", "/gm/api/audit", null, true).body().contains("101001"));
  }

  @Test
  void currentBannersAreSessionProtectedAndSupportPermanentTypes() throws Exception {
    assertEquals(401, request("GET", "/gm/api/banners/current", null, false).statusCode());
    openSession();
    assertEquals(200, request("GET", "/gm/api/banners/current?type=normal&state=active", null, true).statusCode());
    assertEquals(200, request("GET", "/gm/api/banners/current?type=newbie", null, true).statusCode());
    var normal=json(request("GET", "/gm/api/banners/current?type=normal", null, true)).getAsJsonObject("data");
    assertEquals(1,normal.get("total").getAsInt());
    assertEquals(1001,normal.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsInt());
    assertEquals(2,json(request("GET", "/gm/api/banners/current?state=active", null, true))
        .getAsJsonObject("data").get("total").getAsInt());
    assertEquals(1,json(request("GET", "/gm/api/banners/current?q=银狼&state=stopped", null, true))
        .getAsJsonObject("data").get("total").getAsInt());
    assertEquals(0,json(request("GET", "/gm/api/banners/current?state=stopped&offset=1", null, true))
        .getAsJsonObject("data").getAsJsonArray("items").size());
    assertEquals(400, request("GET", "/gm/api/banners/current?type=unknown", null, true).statusCode());
    assertEquals(400, request("GET", "/gm/api/banners/current?state=unknown", null, true).statusCode());
    var version=json(request("GET","/gm/api/banners/current?version=1.1",null,true)).getAsJsonObject("data");
    assertEquals(1,version.get("total").getAsInt());
    assertEquals("1.1",version.getAsJsonArray("versions").get(0).getAsString());
    assertEquals(3,version.get("missingCount").getAsInt());
    assertEquals(400,request("GET","/gm/api/banners/current?version=bad",null,true).statusCode());
  }

  @Test void batchAndCatalogEndpointsUseExistingSessionCsrfAndReplayProtection() throws Exception {
    openSession();
    for(String action:List.of("banner-batch-enable","banner-batch-stop","banner-complete")) {
      String body="{\"requestId\":\""+UUID.randomUUID()+"\",\"action\":\""+action+"\",\"bannerIds\":[1001,4001],\"durationDays\":7}";
      int before=executions.get();
      assertEquals(401,request("POST","/gm/api/operate",body,false).statusCode());
      var withoutCsrf=HttpRequest.newBuilder(URI.create(base+"/gm/api/operate"))
          .header("Cookie",cookie).header("Origin",base).header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      assertEquals(403,client.send(withoutCsrf,HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(200,request("POST","/gm/api/operate",body,true).statusCode());
      assertEquals(200,request("POST","/gm/api/operate",body,true).statusCode());
      assertEquals(before+1,executions.get());
    }
  }

  @Test
  void stoppingAndEnablingAreGlobalJournaledOperationsWithoutAHistoryTemplate() throws Exception {
    openSession();
    for (String action : List.of("banner-stop", "banner-enable")) {
      String body = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"action\":\"" + action
          + "\",\"bannerId\":99999}";
      var first = request("POST", "/gm/api/operate", body, true);
      assertEquals(200, first.statusCode(), first.body());
      assertEquals(first.body(), request("POST", "/gm/api/operate", body, true).body());
      assertEquals(400, request("POST", "/gm/api/operate", body.replace(":99999}", ":0}"), true).statusCode());
      assertEquals(400, request("POST", "/gm/api/operate", body.replace(":99999}", ":99999,\"uid\":10001}"), true).statusCode());
    }
    assertEquals(2, executions.get());
    assertTrue(request("GET", "/gm/api/audit", null, true).body().contains("99999"));
  }

  @Test
  void bannerHistoryRequiresSessionAndSupportsVersionFiltering() throws Exception {
    assertEquals(401, request("GET", "/gm/api/banners/history", null, false).statusCode());
    openSession();
    var response = request("GET", "/gm/api/banners/history?version=1.0", null, true);
    assertEquals(200, response.statusCode());
    assertTrue(json(response).getAsJsonObject("data").has("versions"));
    assertEquals(2, json(response).getAsJsonObject("data").get("total").getAsInt());
    var filtered = request("GET", "/gm/api/banners/history?version=1.0&type=weaponup&limit=1", null, true);
    assertEquals(1, json(filtered).getAsJsonObject("data").getAsJsonArray("items").size());
    assertEquals(3001, json(filtered).getAsJsonObject("data").getAsJsonArray("items").get(0)
        .getAsJsonObject().get("id").getAsInt());
    assertEquals(0, json(request("GET", "/gm/api/banners/history?q=1006&offset=1", null, true))
        .getAsJsonObject("data").getAsJsonArray("items").size());
    assertEquals(400, request("GET", "/gm/api/banners/history?version=../bad", null, true).statusCode());
  }

  @Test
  void globalBannerRerunIsJournaledAndReplayDoesNotExtendTheWindow() throws Exception {
    openSession();
    String id = UUID.randomUUID().toString();
    String body = "{\"requestId\":\"" + id
        + "\",\"action\":\"banner-rerun\",\"bannerKey\":\"1.0-2001\",\"durationDays\":7}";
    var first = request("POST", "/gm/api/operate", body, true);
    assertEquals(200, first.statusCode());
    assertEquals("completed", json(first).getAsJsonObject("data").get("state").getAsString());
    assertEquals(first.body(), request("POST", "/gm/api/operate", body, true).body());
    assertEquals(1, executions.get());
    assertEquals(409, request("POST", "/gm/api/operate", body.replace(":7}", ":8}"), true).statusCode());
    assertTrue(request("GET", "/gm/api/audit", null, true).body().contains("1.0-2001"));
  }

  @Test
  void bannerRerunRejectsMissingKeysInvalidDurationsAndPlayerTargets() throws Exception {
    openSession();
    for (String fields : List.of(
        "\"bannerKey\":\"1.0-2001\",\"durationDays\":0",
        "\"bannerKey\":\"1.0-2001\",\"durationDays\":366",
        "\"bannerKey\":\"1.0-2001\",\"durationDays\":1.5",
        "\"bannerKey\":\"../Banners.json\",\"durationDays\":7",
        "\"durationDays\":7",
        "\"bannerKey\":\"1.0-2001\",\"durationDays\":7,\"uid\":10001")) {
      String body = "{\"requestId\":\"" + UUID.randomUUID()
          + "\",\"action\":\"banner-rerun\"," + fields + "}";
      assertEquals(400, request("POST", "/gm/api/operate", body, true).statusCode(), fields);
    }
    assertEquals(0, executions.get());
  }

  @Test
  void publicPageHasNoKeyAndEveryDataRouteRequiresSession() throws Exception {
    var page = request("GET", "/gm", null, false);
    assertEquals(200, page.statusCode());
    assertTrue(page.body().contains("HKRPG"));
    assertFalse(page.body().contains(KEY));
    assertTrue(
        page.headers()
            .firstValue("Content-Security-Policy")
            .orElse("")
            .contains("frame-ancestors 'none'"));
    for (var route : List.of("session", "status", "players", "catalog", "commands", "audit", "banners/history"))
      assertEquals(401, request("GET", "/gm/api/" + route, null, false).statusCode(), route);
    assertEquals(401, request("POST", "/gm/api/operate", "{}", false).statusCode());
    assertEquals(0, executions.get());
  }

  @Test
  void opensWithoutAKeyButStillChecksOriginAndCsrf() throws Exception {
    var foreign =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/gm/api/session"))
                .header("Origin", "https://foreign.invalid")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(403, foreign.statusCode());
    assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(base + "/gm/api/session"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    openSession();
    var session = request("GET", "/gm/api/session", null, true);
    assertEquals(200, session.statusCode());
    assertFalse(session.body().contains(KEY));
    csrf = "wrong";
    assertEquals(
        403,
        request("POST", "/gm/api/operate", operation(UUID.randomUUID().toString()), true)
            .statusCode());
    csrf = json(session).getAsJsonObject("data").get("csrf").getAsString();
    assertEquals(200, request("GET", "/gm/api/players", null, true).statusCode());
    assertEquals(0, executions.get());
  }

  @Test
  void startupAndOpeningDoNotCreateOrReadAnOperatorKey() throws Exception {
    var path = directory.resolve("gm-console.key");
    assertFalse(Files.exists(path));
    Files.createDirectory(path); // A leftover invalid key must not block opening the console.
    openSession();
    assertTrue(Files.isDirectory(path));
    assertEquals(200, request("GET", "/gm/api/status", null, true).statusCode());
    assertEquals(404, request("POST", "/gm/api/login", "{}", false).statusCode());
  }

  @Test
  void reopeningReusesAnActiveSessionAndAnExpiredSessionCanBeReplaced() throws Exception {
    openSession();
    String originalCookie = cookie, originalCsrf = csrf;
    var reopened = request("POST", "/gm/api/session", "{}", true);
    assertEquals(200, reopened.statusCode());
    assertEquals(originalCsrf, json(reopened).getAsJsonObject("data").get("csrf").getAsString());
    assertTrue(reopened.headers().firstValue("Set-Cookie").isEmpty());
    now.addAndGet(GmAuth.LIFETIME);
    assertEquals(401, request("GET", "/gm/api/players", null, true).statusCode());
    openSession();
    assertNotEquals(originalCookie, cookie);
    assertEquals(200, request("GET", "/gm/api/players", null, true).statusCode());
    assertEquals(0, executions.get());
  }

  @Test
  void rejectsForeignHostEvenWhenOriginMatchesIt() throws Exception {
    try (var socket = new java.net.Socket("127.0.0.1", app.port())) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write(("POST /gm/api/session HTTP/1.1\r\n"
          + "Host: foreign.invalid:" + app.port() + "\r\nOrigin: http://foreign.invalid:" + app.port()
          + "\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var response = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(response.startsWith("HTTP/1.1 403"), response);
    }
    assertEquals(0, executions.get());
  }

  @Test
  void operationsAreValidatedJournaledAndSameRequestCannotExecuteTwice() throws Exception {
    openSession();
    var id = UUID.randomUUID().toString();
    var body = operation(id);
    assertEquals(200, request("POST", "/gm/api/operate", body, true).statusCode());
    assertEquals(200, request("POST", "/gm/api/operate", body, true).statusCode());
    assertEquals(1, executions.get());
    assertEquals(
        409, request("POST", "/gm/api/operate", body.replace("heal", "refill"), true).statusCode());
    assertEquals(
        400, request("POST", "/gm/api/operate", "{\"action\":\"heal\"}", true).statusCode());
    assertEquals(400, request("POST", "/gm/api/operate", "{", true).statusCode());
    assertEquals(
        400,
        request(
                "POST",
                "/gm/api/operate",
                body.replace(id, UUID.randomUUID().toString()).replace("heal", "unknown"),
                true)
            .statusCode());
    assertEquals(1, executions.get());
    var audit = request("GET", "/gm/api/audit", null, true);
    assertTrue(audit.body().contains(id));
    assertTrue(audit.body().contains("completed"));
    assertFalse(audit.body().contains("fingerprint"));
    assertFalse(audit.body().contains(KEY));
  }

  @Test
  void listSearchDoesNotExposeDomainObjectsAndCatalogIsPaged() throws Exception {
    openSession();
    assertTrue(request("GET", "/gm/api/players?q=10001", null, true).body().contains("开拓者"));
    assertFalse(request("GET", "/gm/api/players?q=offline", null, true).body().contains("开拓者"));
    var catalog = request("GET", "/gm/api/catalog?q=101&limit=1&offset=0", null, true);
    assertEquals(200, catalog.statusCode());
    assertTrue(catalog.body().contains("星琼"));
    assertEquals(400, request("GET", "/gm/api/catalog?limit=9999", null, true).statusCode());
  }

  @Test
  void rejectsWrongContentTypeAndOversizedRequestsBeforeExecution() throws Exception {
    openSession();
    var builder =
        HttpRequest.newBuilder(URI.create(base + "/gm/api/operate"))
            .header("Cookie", cookie)
            .header("X-GM-CSRF", csrf)
            .header("Origin", base)
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString("{}"));
    assertEquals(
        415, client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(413, request("POST", "/gm/api/operate", "x".repeat(65537), true).statusCode());
    assertEquals(0, executions.get());
  }

  void openSession() throws Exception {
    var response = request("POST", "/gm/api/session", "{}", false);
    assertEquals(200, response.statusCode());
    var setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
    assertTrue(setCookie.contains("HttpOnly"));
    assertTrue(setCookie.contains("SameSite=Strict"));
    cookie = setCookie.split(";", 2)[0];
    csrf = json(response).getAsJsonObject("data").get("csrf").getAsString();
  }

  static String operation(String id) {
    return "{\"requestId\":\"" + id + "\",\"action\":\"heal\",\"uid\":10001}";
  }

  static JsonObject json(HttpResponse<String> response) {
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  HttpResponse<String> request(String method, String path, String body, boolean auth)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path));
    if (auth && cookie != null) builder.header("Cookie", cookie);
    if (auth && csrf != null) builder.header("X-GM-CSRF", csrf);
    if (body != null) builder.header("Origin", base).header("Content-Type", "application/json");
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
