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
          .error("GM console disabled: could not open audit journal", e);
      app.get("/gm", ctx -> ctx.status(503).result("GM 控制台不可用，请检查服务端日志与 data 目录权限。"));
    }
  }

  static void register(Javalin app, GmBackend backend, Path directory, Clock clock)
      throws IOException {
    var auth = new GmAuth(clock);
    var journal = new GmJournal(directory, clock);
    app.get("/gm", safe(ctx -> asset(ctx, "index.html", "text/html; charset=utf-8")));
    app.get("/gm/", safe(ctx -> asset(ctx, "index.html", "text/html; charset=utf-8")));
    for (var file :
        List.of(
            "app.js",
            "api.js",
            "players.js",
            "resources.js",
            "banners.js",
            "shops.js",
            "shortcuts.js",
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
        "/gm/api/session",
        safe(
            ctx -> {
              GmAuth.sameOrigin(ctx);
              body(ctx, com.google.gson.JsonObject.class);
              var session = auth.open(ctx);
              ok(ctx, Map.of("csrf", session.csrf()));
            }));
    app.get(
        "/gm/api/session", safe(ctx -> ok(ctx, Map.of("csrf", auth.require(ctx, false).csrf()))));
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
    app.get("/gm/api/item-shortcuts", safe(ctx -> {
      auth.require(ctx,false);
      String q=query(ctx,"q"),type=query(ctx,"type"),tag=query(ctx,"tag");
      if (!type.isEmpty()&&!GmItemShortcuts.TYPES.containsKey(type)) throw new GmError(400,"物品类型不正确。");
      if (!tag.isEmpty()&&!GmItemShortcuts.TAGS.contains(tag)) throw new GmError(400,"物品标签不正确。");
      int offset=integer(ctx,"offset",0,0,1_000_000),limit=integer(ctx,"limit",30,1,100);
      var all=backend.itemShortcuts();
      var filtered=all.stream().filter(i -> type.isEmpty()||type.equals(i.type()))
          .filter(i -> tag.isEmpty()||i.tags().contains(tag))
          .filter(i -> q.isEmpty()||(i.item().id()+" "+i.item().name()+" "+String.join(" ",i.sources()))
              .toLowerCase(Locale.ROOT).contains(q)).toList();
      ok(ctx,Map.of("types",GmItemShortcuts.TYPES.entrySet().stream().sorted(Map.Entry.comparingByKey())
          .map(e -> Map.of("id",e.getKey(),"name",e.getValue(),"count",all.stream().filter(i -> i.type().equals(e.getKey())).count())).toList(),
          "total",filtered.size(),"items",filtered.stream().skip(offset).limit(limit).toList()));
    }));
    app.get("/gm/api/shops", safe(ctx -> {
      auth.require(ctx, false);
      String q = query(ctx, "q"), state = query(ctx, "state");
      if (!state.isEmpty() && !Set.of("listed", "unlisted").contains(state))
        throw new GmError(400, "商品状态不正确。");
      int shopId = integer(ctx, "shopId", 0, 0, Integer.MAX_VALUE);
      int offset = integer(ctx, "offset", 0, 0, 1_000_000), limit = integer(ctx, "limit", 30, 1, 100);
      var goods = backend.shopGoods();
      var shops = goods.stream().map(g -> new ShopRow(g.shopId(), g.shopType(), g.shopName()))
          .distinct().sorted(Comparator.comparingInt(ShopRow::id)).toList();
      var filtered = goods.stream().filter(g -> shopId == 0 || g.shopId() == shopId)
          .filter(g -> state.isEmpty() || g.listed() == state.equals("listed"))
          .filter(g -> q.isEmpty() || (g.goodsId() + " " + g.itemId() + " " + g.name() + " " + g.shopName())
              .toLowerCase(Locale.ROOT).contains(q)).toList();
      ok(ctx, Map.of("shops", shops, "total", filtered.size(),
          "items", filtered.stream().skip(offset).limit(limit).toList()));
    }));
    app.get("/gm/api/banners/current", safe(ctx -> {
      auth.require(ctx, false);
      String q = query(ctx, "q"), type = query(ctx, "type"), state = query(ctx, "state"), version = query(ctx, "version");
      if (!version.isEmpty() && !version.matches("[0-9]{1,2}\\.[0-9]{1,2}"))
        throw new GmError(400, "版本筛选格式不正确。");
      if (!type.isEmpty() && !Set.of("normal", "newbie", "avatarup", "weaponup").contains(type))
        throw new GmError(400, "卡池类型不正确。");
      if (!state.isEmpty() && !Set.of("active", "stopped", "scheduled", "expired").contains(state))
        throw new GmError(400, "卡池状态不正确。");
      int offset = integer(ctx, "offset", 0, 0, 1_000_000), limit = integer(ctx, "limit", 30, 1, 100);
      var current = backend.currentBanners();
      var filtered = current.stream()
          .filter(b -> version.isEmpty() || version.equals(b.version()))
          .filter(b -> type.isEmpty() || type.equals(b.type().toLowerCase(Locale.ROOT)))
          .filter(b -> state.isEmpty() || state.equals(b.state()))
          .filter(b -> q.isEmpty() || (b.id() + " " + b.name() + " " + b.up5() + " " + b.up4())
              .toLowerCase(Locale.ROOT).contains(q)).toList();
      var versions = current.stream().map(CurrentBannerRow::version).filter(v -> !v.isBlank()).distinct()
          .sorted(java.util.Comparator.comparingInt((String v) -> Integer.parseInt(v.split("\\.")[0]))
              .thenComparingInt(v -> Integer.parseInt(v.split("\\.")[1]))).toList();
      var catalog = new java.util.HashSet<Integer>(List.of(1001,4001));
      backend.bannerHistory().forEach(b -> catalog.add(b.id()));
      current.forEach(b -> catalog.remove(b.id()));
      ok(ctx, Map.of("total", filtered.size(), "versions", versions, "missingCount", catalog.size(),
          "items", filtered.stream().skip(offset).limit(limit).toList()));
    }));
    app.get("/gm/api/banners/history", safe(ctx -> {
      auth.require(ctx, false);
      String version = query(ctx, "version"), q = query(ctx, "q"), type = query(ctx, "type");
      if (!version.isEmpty() && !version.matches("[0-9]{1,2}\\.[0-9]{1,2}"))
        throw new GmError(400, "版本筛选格式不正确。");
      if (!type.isEmpty() && !Set.of("avatarup", "weaponup").contains(type))
        throw new GmError(400, "卡池类型不正确。");
      int offset = integer(ctx, "offset", 0, 0, 1_000_000), limit = integer(ctx, "limit", 30, 1, 100);
      var history = backend.bannerHistory();
      var filtered = history.stream().filter(b -> version.isEmpty() || version.equals(b.version()))
          .filter(b -> type.isEmpty() || type.equals(b.type().toLowerCase(Locale.ROOT)))
          .filter(b -> q.isEmpty() || (b.id() + " " + b.name() + " " + b.up5() + " " + b.up4())
              .toLowerCase(Locale.ROOT).contains(q)).toList();
      ok(ctx, Map.of("versions", history.stream().map(BannerRow::version).distinct().toList(),
          "total", filtered.size(), "items", filtered.stream().skip(offset).limit(limit).toList()));
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
        GmAuth.localOrigin(ctx);
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
