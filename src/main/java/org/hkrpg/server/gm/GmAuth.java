package org.hkrpg.server.gm;

import io.javalin.http.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Clock;
import java.util.*;

/** A separate operator credential; never reuse a game account or log the key. */
final class GmAuth {
  static final String COOKIE = "hkrpg_gm";
  static final long LIFETIME = 8 * 60 * 60 * 1000L;
  private final String key;
  private final Clock clock;
  private final Map<String, Session> sessions = new LinkedHashMap<>();
  private final Map<String, Attempts> attempts = new LinkedHashMap<>();

  record Session(String token, String csrf, long expires) {}

  record Attempts(long since, int count) {}

  GmAuth(Path directory, Clock clock) throws IOException {
    this.clock = clock;
    Files.createDirectories(directory);
    Path path = directory.resolve("gm-console.key");
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      try {
        var content = randomToken();
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
          Files.createFile(
              path,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
          Files.writeString(path, content, StandardOpenOption.WRITE);
        } else {
          Files.writeString(path, content, StandardOpenOption.CREATE_NEW);
        }
      } catch (FileAlreadyExistsException ignored) {
        /* Another startup won creation. */
      }
    }
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 256)
      throw new IOException("Invalid GM key file");
    key = Files.readString(path).strip();
    if (!key.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Invalid GM key format");
  }

  synchronized Session login(Context ctx, String presented) {
    sameOrigin(ctx);
    long now = clock.millis();
    attempts.entrySet().removeIf(e -> now - e.getValue().since() >= 60_000);
    var previous = attempts.get(ctx.ip());
    if (previous != null && previous.count() >= 5) throw new GmError(429, "尝试过于频繁，请一分钟后重试。");
    if (presented == null || !equal(key, presented)) {
      if (attempts.size() >= 1024) attempts.remove(attempts.keySet().iterator().next());
      attempts.put(
          ctx.ip(),
          new Attempts(
              previous == null ? now : previous.since(),
              previous == null ? 1 : previous.count() + 1));
      throw new GmError(401, "管理密钥不正确。");
    }
    attempts.remove(ctx.ip());
    sessions.values().removeIf(s -> s.expires() <= now);
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
      throw new GmError(401, "请先登录 GM 控制台。");
    }
    if (mutation) {
      sameOrigin(ctx);
      if (!equal(session.csrf(), Objects.toString(ctx.header("X-GM-CSRF"), "")))
        throw new GmError(403, "操作校验已失效，请刷新页面后重试。");
    }
    return session;
  }

  synchronized void logout(Context ctx) {
    sessions.remove(ctx.cookie(COOKIE));
    ctx.header("Set-Cookie", COOKIE + "=; Path=/gm; Max-Age=0; HttpOnly; SameSite=Strict");
  }

  static void sameOrigin(Context ctx) {
    String expected = ctx.scheme() + "://" + ctx.host();
    if (!expected.equals(ctx.header("Origin"))) throw new GmError(403, "只接受来自控制台本身的操作。");
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
