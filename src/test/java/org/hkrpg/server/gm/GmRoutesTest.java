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
  @TempDir Path directory;
  Javalin app;
  HttpClient client;
  String base;
  String cookie;
  String csrf;
  AtomicInteger executions = new AtomicInteger();
  AtomicLong now = new AtomicLong(System.currentTimeMillis());
  static final String KEY = "a".repeat(43);

  @BeforeEach
  void start() throws Exception {
    Files.writeString(directory.resolve("gm-console.key"), KEY);
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
  void publicPageHasNoKeyAndEveryDataRouteRequiresLogin() throws Exception {
    var page = request("GET", "/gm", null, false);
    assertEquals(200, page.statusCode());
    assertTrue(page.body().contains("HKRPG"));
    assertFalse(page.body().contains(KEY));
    assertTrue(
        page.headers()
            .firstValue("Content-Security-Policy")
            .orElse("")
            .contains("frame-ancestors 'none'"));
    for (var route : List.of("session", "status", "players", "catalog", "commands", "audit"))
      assertEquals(401, request("GET", "/gm/api/" + route, null, false).statusCode(), route);
    assertEquals(401, request("POST", "/gm/api/operate", "{}", false).statusCode());
    assertEquals(0, executions.get());
  }

  @Test
  void loginChecksOriginKeyAndCsrfAndLogoutRevokesTheSession() throws Exception {
    var foreign =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/gm/api/login"))
                .header("Origin", "https://foreign.invalid")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"key\":\"" + KEY + "\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(403, foreign.statusCode());
    assertEquals(401, request("POST", "/gm/api/login", "{\"key\":\"wrong\"}", false).statusCode());
    login();
    var session = request("GET", "/gm/api/session", null, true);
    assertEquals(200, session.statusCode());
    assertFalse(session.body().contains(KEY));
    csrf = "wrong";
    assertEquals(
        403,
        request("POST", "/gm/api/operate", operation(UUID.randomUUID().toString()), true)
            .statusCode());
    csrf = json(session).getAsJsonObject("data").get("csrf").getAsString();
    assertEquals(200, request("POST", "/gm/api/logout", "{}", true).statusCode());
    assertEquals(401, request("GET", "/gm/api/players", null, true).statusCode());
    assertEquals(0, executions.get());
  }

  @Test
  void operationsAreValidatedJournaledAndSameRequestCannotExecuteTwice() throws Exception {
    login();
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
    login();
    assertTrue(request("GET", "/gm/api/players?q=10001", null, true).body().contains("开拓者"));
    assertFalse(request("GET", "/gm/api/players?q=offline", null, true).body().contains("开拓者"));
    var catalog = request("GET", "/gm/api/catalog?q=101&limit=1&offset=0", null, true);
    assertEquals(200, catalog.statusCode());
    assertTrue(catalog.body().contains("星琼"));
    assertEquals(400, request("GET", "/gm/api/catalog?limit=9999", null, true).statusCode());
  }

  @Test
  void throttlesFailedLoginsAndExpiresSessions() throws Exception {
    for (int i = 0; i < 5; i++)
      assertEquals(
          401, request("POST", "/gm/api/login", "{\"key\":\"wrong\"}", false).statusCode());
    assertEquals(
        429, request("POST", "/gm/api/login", "{\"key\":\"" + KEY + "\"}", false).statusCode());
    now.addAndGet(60_001);
    login();
    now.addAndGet(GmAuth.LIFETIME);
    assertEquals(401, request("GET", "/gm/api/players", null, true).statusCode());
    assertEquals(0, executions.get());
  }

  @Test
  void rejectsWrongContentTypeAndOversizedRequestsBeforeExecution() throws Exception {
    login();
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

  void login() throws Exception {
    var response = request("POST", "/gm/api/login", "{\"key\":\"" + KEY + "\"}", false);
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
