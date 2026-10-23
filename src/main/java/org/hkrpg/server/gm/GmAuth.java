package org.hkrpg.server.gm;

import io.javalin.http.Context;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;

/** Local console sessions retain same-origin and CSRF checks without an operator key. */
final class GmAuth {
  static final String COOKIE = "hkrpg_gm";
  static final long LIFETIME = 8 * 60 * 60 * 1000L;
  private final Clock clock;
  private final Map<String, Session> sessions = new LinkedHashMap<>();

  record Session(String token, String csrf, long expires) {}

  GmAuth(Clock clock) {
    this.clock = clock;
  }

  synchronized Session open(Context ctx) {
    sameOrigin(ctx);
    long now = clock.millis();
    sessions.values().removeIf(s -> s.expires() <= now);
    var existing = sessions.get(ctx.cookie(COOKIE));
    if (existing != null) return existing;
    if (sessions.size() >= 64) sessions.remove(sessions.keySet().iterator().next());
    var session = new Session(randomToken(), randomToken(), now + LIFETIME);
    sessions.put(session.token(), session);
    ctx.header(
        "Set-Cookie",
        COOKIE
            + "="
            + session.token()
            + "; Path=/gm; Max-Age=28800; HttpOnly; SameSite=Strict"
            + (ctx.scheme().equals("https") ? "; Secure" : ""));
    return session;
  }

  synchronized Session require(Context ctx, boolean mutation) {
    var session = sessions.get(ctx.cookie(COOKIE));
    if (session == null || session.expires() <= clock.millis()) {
      if (session != null) sessions.remove(session.token());
      throw new GmError(401, "控制台连接已过期，请重新连接。");
    }
    if (mutation) {
      sameOrigin(ctx);
      if (!equal(session.csrf(), Objects.toString(ctx.header("X-GM-CSRF"), "")))
        throw new GmError(403, "操作校验已失效，请刷新页面后重试。");
    }
    return session;
  }

  static void sameOrigin(Context ctx) {
    String expected = localOrigin(ctx);
    if (!expected.equals(ctx.header("Origin"))) throw new GmError(403, "只接受来自控制台本身的操作。");
  }

  static String localOrigin(Context ctx) {
    // Use the actual Host header rather than forwarded headers; never resolve a supplied hostname.
    String expected = ctx.req().getScheme() + "://" + ctx.req().getHeader("Host");
    try {
      var uri = URI.create(expected);
      if (uri.getHost() != null
          && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost().toLowerCase(Locale.ROOT))
          && uri.getRawUserInfo() == null && uri.getRawPath().isEmpty()
          && uri.getRawQuery() == null && uri.getRawFragment() == null
          && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535)) return expected;
    } catch (IllegalArgumentException ignored) {
      // Invalid authorities are rejected in the same way as remote hostnames.
    }
    throw new GmError(403, "请通过本机地址打开 GM 控制台。");
  }

  static String randomToken() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static boolean equal(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }
}
