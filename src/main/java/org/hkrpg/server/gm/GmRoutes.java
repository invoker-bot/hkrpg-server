package org.hkrpg.server.gm;

import com.google.gson.Gson;
import emu.lunarcore.LunarCore;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;

public final class GmRoutes {
  private GmRoutes() {}

  private static final Gson JSON = new Gson();

  public static void register(Javalin app) {
    try {
      register(
          app, new LiveGmBackend(), Path.of(LunarCore.getConfig().getDataDir()), Clock.systemUTC());
    } catch (Exception e) {
      LunarCore.getLogger()
          .error("GM console disabled: could not open operator credential or audit journal", e);
      app.get("/gm", ctx -> ctx.status(503).result("GM 控制台不可用，请检查服务端日志与 data 目录权限。"));
    }
  }

  static void register(Javalin app, GmBackend backend, Path directory, Clock clock)
      throws IOException {
    var auth = new GmAuth(directory, clock);
    var journal = new GmJournal(directory, clock);
    app.get("/gm", safe(ctx -> asset(ctx, "index.html", "text/html; charset=utf-8")));
    app.get("/gm/", safe(ctx -> asset(ctx, "index.html", "text/html; charset=utf-8")));
    for (var file :
        List.of(
            "app.js",
            "api.js",
            "players.js",
            "resources.js",
            "mail.js",
            "terminal.js",
            "audit.js",
            "ui.js",
            "style.css")) {
      app.get(
          "/gm/" + file,
          safe(
              ctx ->
                  asset(
                      ctx,
                      file,
                      file.endsWith(".css")
                          ? "text/css; charset=utf-8"
                          : "text/javascript; charset=utf-8")));
    }
    app.post(
        "/gm/api/login",
        safe(
            ctx -> {
              GmAuth.sameOrigin(ctx);
              var body = body(ctx, com.google.gson.JsonObject.class);
              String key =
                  body.has("key")
                          && body.get("key").isJsonPrimitive()
                          && body.get("key").getAsJsonPrimitive().isString()
                      ? body.get("key").getAsString()
                      : null;
              var session = auth.login(ctx, key);
              ok(ctx, Map.of("csrf", session.csrf()));
            }));
    app.get(
        "/gm/api/session", safe(ctx -> ok(ctx, Map.of("csrf", auth.require(ctx, false).csrf()))));
    app.post(
        "/gm/api/logout",
        safe(
            ctx -> {
              auth.require(ctx, true);
              auth.logout(ctx);
              ok(ctx, Map.of());
            }));
    app.get(
        "/gm/api/status",
        safe(
            ctx -> {
              auth.require(ctx, false);
              ok(ctx, backend.status());
            }));
    app.get(
        "/gm/api/players",
        safe(
            ctx -> {
              auth.require(ctx, false);
              String q = query(ctx, "q");
              ok(
                  ctx,
                  backend.players().stream()
                      .filter(
                          p ->
                              q.isEmpty()
                                  || Integer.toString(p.uid()).contains(q)
                                  || p.name().toLowerCase(Locale.ROOT).contains(q))
                      .toList());
            }));
    app.get(
        "/gm/api/catalog",
        safe(
            ctx -> {
              auth.require(ctx, false);
              String q = query(ctx, "q"), category = query(ctx, "category");
              int offset = integer(ctx, "offset", 0, 0, 1_000_000),
                  limit = integer(ctx, "limit", 50, 1, 100);
              var filtered =
                  backend.catalog().stream()
                      .filter(i -> category.isEmpty() || i.category().equals(category))
                      .filter(
                          i ->
                              q.isEmpty()
                                  || Integer.toString(i.id()).contains(q)
                                  || i.name().toLowerCase(Locale.ROOT).contains(q))
                      .toList();
              ok(
                  ctx,
                  Map.of(
                      "total",
                      filtered.size(),
                      "items",
                      filtered.stream().skip(offset).limit(limit).toList()));
            }));
    app.get(
        "/gm/api/commands",
        safe(
            ctx -> {
              auth.require(ctx, false);
              ok(ctx, backend.commands());
            }));
    app.get(
        "/gm/api/audit",
        safe(
            ctx -> {
              auth.require(ctx, false);
              ok(ctx, journal.history());
            }));
    app.post(
        "/gm/api/operate",
        safe(
            ctx -> {
              auth.require(ctx, true);
              var op = body(ctx, Operation.class);
              GmValidation.validate(op);
              if (!backend.status().ready() || !backend.status().gameAvailable())
                throw new GmError(503, "游戏服务尚未就绪。");
              ok(ctx, GmJournal.publicEntry(journal.execute(op, backend)));
            }));
  }

  private static Handler safe(Handler handler) {
    return ctx -> {
      headers(ctx);
      try {
        handler.handle(ctx);
      } catch (GmError e) {
        ctx.status(e.status)
            .contentType("application/json; charset=utf-8")
            .result(JSON.toJson(Map.of("ok", false, "error", e.getMessage())));
      } catch (Exception e) {
        LunarCore.getLogger().error("GM request failed: {}", ctx.path(), e);
        ctx.status(500)
            .contentType("application/json; charset=utf-8")
            .result(JSON.toJson(Map.of("ok", false, "error", "请求失败，请检查服务端日志。")));
      }
    };
  }

  private static void headers(Context ctx) {
    ctx.header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header("X-Frame-Options", "DENY")
        .header("Referrer-Policy", "no-referrer")
        .header(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
                + " connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors"
                + " 'none'");
  }

  private static void asset(Context ctx, String name, String type) throws IOException {
    try (var stream = GmRoutes.class.getResourceAsStream("/gm/" + name)) {
      if (stream == null) {
        ctx.status(404);
        return;
      }
      ctx.contentType(type).result(stream.readAllBytes());
    }
  }

  private static void ok(Context ctx, Object data) {
    ctx.contentType("application/json; charset=utf-8")
        .result(JSON.toJson(Map.of("ok", true, "data", data)));
  }

  private static <T> T body(Context ctx, Class<T> type) {
    if (ctx.contentType() == null
        || !ctx.contentType().split(";", 2)[0].equalsIgnoreCase("application/json"))
      throw new GmError(415, "请使用 JSON 请求。");
    if (ctx.contentLength() > 65536) throw new GmError(413, "请求内容过长。");
    var bytes = ctx.bodyAsBytes();
    if (bytes.length > 65536) throw new GmError(413, "请求内容过长。");
    try {
      var value = JSON.fromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), type);
      if (value == null) throw new IllegalArgumentException();
      return value;
    } catch (RuntimeException e) {
      throw new GmError(400, "JSON 格式不正确。");
    }
  }

  private static String query(Context ctx, String name) {
    var value = Objects.toString(ctx.queryParam(name), "").strip().toLowerCase(Locale.ROOT);
    if (value.length() > 100) throw new GmError(400, "搜索条件过长。");
    return value;
  }

  private static int integer(Context ctx, String name, int fallback, int min, int max) {
    var value = ctx.queryParam(name);
    if (value == null) return fallback;
    try {
      int number = Integer.parseInt(value);
      GmValidation.range(number, min, max, name);
      return number;
    } catch (NumberFormatException e) {
      throw new GmError(400, "分页参数不正确。");
    }
  }
}
