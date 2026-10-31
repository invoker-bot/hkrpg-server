package org.hkrpg.server;

import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.gacha.*;
import io.javalin.Javalin;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import com.google.gson.stream.JsonReader;

/** Local SDK browser pages; the details page describes the actual configured pool. */
public final class GachaWebRoutes {
  private GachaWebRoutes() {}

  public static String detailUrl(int bannerId) { return url("details", bannerId); }
  public static String historyUrl(int bannerId) { return url("history", bannerId); }

  private static String url(String page, int bannerId) {
    var config = LunarCore.getConfig().getHttpServer();
    try {
      // Use the operator's advertised origin, including the public port and IPv6 brackets.
      // Do not return an empty URL: the client registers a WebView before parsing it.
      return new URI(config.isUseSSL() ? "https" : "http", null, config.getPublicAddress(),
          config.getPublicPort(), "/gacha/" + page, "gacha_id=" + bannerId, null).toASCIIString();
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Invalid public HTTP address for gacha pages", e);
    }
  }

  public static void register(Javalin app) {
    register(app, () -> LunarCore.getGameServer() == null ? null : LunarCore.getGameServer().getGachaService());
  }

  static void register(Javalin app, Supplier<GachaService> services) {
    app.get("/gacha/details", ctx -> {
      int id;
      try { id = Integer.parseInt(Objects.toString(ctx.queryParam("gacha_id"), "")); }
      catch (NumberFormatException invalid) { id = 0; }
      var service = services.get();
      String content = "<p>卡池数据暂不可用，请稍后重试。</p>";
      if (service != null) {
        final int bannerId = id;
        var banner = service.getBannerSnapshot().stream().filter(row -> row.getId() == bannerId).findFirst().orElse(null);
        if (banner == null) content = "<p>未找到此卡池，请重新打开跃迁页面。</p>";
        else {
          var pool = service.getPool(banner);
          content = pool.canPull() ? details(banner, pool) : "<p>卡池配置暂不可用，请联系管理员。</p>";
        }
      }
      ctx.header("Cache-Control", "no-store")
          .header("Referrer-Policy", "no-referrer")
          .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'")
          .contentType("text/html; charset=utf-8").result(page(content));
    });
    register(app, "history", "跃迁记录", "本服尚未保存逐次跃迁记录。",
        "这里暂时无法查询历史结果，不代表你没有进行过跃迁。已获得的角色与光锥可在游戏内查看。");
  }

  private static String details(GachaBanner banner, GachaPool pool) {
    var type = banner.getGachaType();
    boolean beginner = type == GachaType.Newbie, weapon = type == GachaType.WeaponUp;
    boolean event = type == GachaType.WeaponUp || type == GachaType.AvatarUp;
    String label = beginner ? "始发跃迁" : type == GachaType.Normal ? "群星跃迁"
        : weapon ? "光锥活动跃迁" : "角色活动跃迁";
    var text = new StringBuilder("<div class=\"tag\">" + label + " · 本服配置</div>");
    text.append("<nav><a href=\"#rules\">跃迁规则</a><a href=\"#objects\">对象清单</a></nav>");
    text.append("<section id=\"rules\"><h2>跃迁规则</h2><p>五星基础概率 <strong>")
        .append(weapon ? "0.800%" : "0.600%")
        .append("</strong>，四星基础概率 <strong>").append(weapon ? "6.600%" : "5.100%")
        .append("</strong>。每 10 次至少获得一个四星或以上对象。</p>");
    if (beginner) text.append("<p>每次限十连，消耗 8 张星轨通票；最多跃迁 50 次，期间至少获得一名五星角色。新手池保底独立计算。</p>");
    else text.append("<p>最多 ").append(weapon ? 80 : 90).append(" 次获得五星。同类型活动池共享保底，其他类型分别计算。</p>");
    if (event) text.append("<p>五星 UP 基础占比 ").append(banner.getEventChance())
        .append("%，四星 UP 基础占比 ").append(weapon ? 75 : 50)
        .append("%；未获得当期 UP 时，下次对应星级必定获得当期 UP。若 UP 同时在基础池内，实际占比会增加。</p>");
    text.append("<p class=\"note\">保底递增曲线为本服兼容实现，官方未公布每次跃迁的完整曲线；上述基础概率、硬保底及 UP 规则以本服代码和配置为准。</p></section>");
    var names = names();
    text.append("<section id=\"objects\"><h2>对象清单</h2>");
    if (event) {
      list(text, "五星概率提升", pool.up5(), names);
      list(text, "四星概率提升", pool.up4(), names);
    }
    list(text, "五星基础池", pool.base5(), names);
    list(text, "四星基础池", pool.base4(), names);
    list(text, "三星基础池", pool.base3(), names);
    return text.append("</section>").toString();
  }

  private static void list(StringBuilder html, String title, List<Integer> ids, Map<Long,String> names) {
    html.append("<h3>").append(title).append("</h3><ul>");
    for (int id : ids) {
      var item = GameData.getItemExcelMap().get(id);
      String name = item == null ? null : names.get(item.getItemName());
      html.append("<li>").append(escape(name == null || name.isBlank() ? "物品 #" + id : name))
          .append("</li>");
    }
    html.append("</ul>");
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;");
  }

  private static volatile Path namesRoot;
  private static volatile Map<Long,String> nameCache;
  private static synchronized Map<Long,String> names() {
    Path root = Path.of(LunarCore.getConfig().getResourceDir()).toAbsolutePath().normalize();
    if (root.equals(namesRoot) && nameCache != null) return nameCache;
    var wanted = new HashSet<Long>();
    GameData.getItemExcelMap().values().forEach(item -> wanted.add(item.getItemName()));
    var result = new HashMap<Long,String>();
    Path source = root.resolve("TextMap/TextMapCHS.json");
    if (Files.isRegularFile(source)) try (var reader = new JsonReader(Files.newBufferedReader(source, StandardCharsets.UTF_8))) {
      reader.beginObject();
      while (reader.hasNext()) {
        String key = reader.nextName();
        long hash;
        try { hash = Long.parseUnsignedLong(key); }
        catch (NumberFormatException bad) { reader.skipValue(); continue; }
        if (wanted.contains(hash)) result.put(hash, reader.nextString().replaceAll("<[^>]*>", "").replace("\\n", " "));
        else reader.skipValue();
      }
      reader.endObject();
    } catch (Exception unavailable) { LunarCore.getLogger().warn("Unable to load gacha item names", unavailable); }
    namesRoot = root; nameCache = Map.copyOf(result); return nameCache;
  }

  private static String page(String content) {
    return """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>跃迁详情</title>
        <style>
        :root{font-family:"Microsoft YaHei",sans-serif;color:#38363b;background:#f2eee9;line-height:1.8}
        body{margin:0}main{max-width:840px;margin:28px auto;padding:28px 36px 48px;background:#fff;box-shadow:0 8px 40px #42345014}
        h1{font-size:28px;margin:0 0 10px;color:#2e2734}h2{font-size:21px;color:#4f3b61;border-left:4px solid #9b82b5;padding-left:12px;margin-top:32px}
        h3{font-size:16px;margin:22px 0 6px;color:#685573}p{margin:10px 0}.tag{color:#806b94;font-size:14px}
        nav{display:flex;gap:24px;border-bottom:1px solid #e8e2ea;padding:16px 0}a{color:#705486;text-decoration:none}
        ul{display:flex;flex-wrap:wrap;gap:7px;list-style:none;padding:0;margin:0}li{border:1px solid #e8e2ec;border-radius:5px;padding:3px 10px;background:#faf9fb}
        .note{font-size:14px;color:#806d83}strong{color:#ad8053}@media(max-width:600px){main{margin:0;padding:22px 20px}h1{font-size:24px}}
        </style></head><body><main><h1>跃迁详情</h1>%s</main></body></html>
        """.formatted(content);
  }

  private static void register(Javalin app, String path, String title, String message, String detail) {
    // Public, static content only. Ignore SDK-added authkey/UID/query arguments entirely;
    // never redirect them or turn this browser entry into a GM/account-data endpoint.
    String html = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>%s</title>
        <style>
          :root { color-scheme: dark; font-family: "Microsoft YaHei", sans-serif; }
          body { margin: 0; background: #111b2b; color: #eef1f6; line-height: 1.8; }
          main { max-width: 680px; margin: 10vh auto; padding: 32px; }
          h1 { font-size: 30px; color: #e4c98b; }
          p { font-size: 18px; }
          .hint { margin-top: 36px; color: #b9c6d8; font-size: 16px; }
        </style></head><body><main>
        <h1>%s</h1><p>%s</p><p>%s</p>
        <p class="hint">点击网页窗口的关闭按钮，返回游戏。</p>
        </main></body></html>
        """.formatted(title, title, message, detail);
    app.get("/gacha/" + path, ctx -> ctx
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'")
        .contentType("text/html; charset=utf-8")
        .result(html));
  }
}
