package org.hkrpg.server;

import emu.lunarcore.LunarCore;
import io.javalin.Javalin;
import java.net.URI;
import java.net.URISyntaxException;

/** Valid local landing pages for SDK browser entries which are not implemented yet. */
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
    register(app, "details", "跃迁详情", "本服暂未提供网页形式的跃迁详情。",
        "卡池中的角色与光锥可在游戏内的跃迁界面查看。");
    register(app, "history", "跃迁记录", "本服尚未保存逐次跃迁记录。",
        "这里暂时无法查询历史结果，不代表你没有进行过跃迁。已获得的角色与光锥可在游戏内查看。");
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
