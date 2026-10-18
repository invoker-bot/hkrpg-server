package org.hkrpg.server.gm;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.hkrpg.server.gm.GmModels.*;

/** Write intent before executing. Unfinished entries are never automatically retried. */
final class GmJournal {
  private static final Gson JSON = new Gson();
  private static final int KEEP = 1000;
  private final Path path;
  private final Clock clock;
  private final LinkedHashMap<String, AuditEntry> entries = new LinkedHashMap<>();

  GmJournal(Path directory, Clock clock) throws IOException {
    this.path = directory.resolve("gm-audit.jsonl");
    this.clock = clock;
    for (int i = 3; i >= 0; i--) {
      Path file = i == 0 ? path : Path.of(path + "." + i);
      if (!Files.exists(file)) continue;
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4_300_000)
        throw new IOException("Invalid GM audit file");
      try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
        lines.forEach(
            line -> {
              try {
                var entry = JSON.fromJson(line, AuditEntry.class);
                if (entry != null && entry.requestId() != null) remember(entry);
              } catch (RuntimeException ignored) {
                /* Ignore a crash-truncated last record. */
              }
            });
      }
    }
  }

  synchronized AuditEntry execute(Operation operation, GmBackend backend) {
    String fingerprint = fingerprint(operation);
    var old = entries.get(operation.requestId());
    if (old != null) {
      if (!old.fingerprint().equals(fingerprint)) throw new GmError(409, "请求编号已用于另一项操作。");
      if (old.state().equals("pending")) throw new GmError(409, "这项操作的结果尚未确认，请核对玩家状态与操作记录，勿重复发放。");
      return old;
    }
    var pending =
        new AuditEntry(
            operation.requestId(),
            fingerprint,
            clock.instant().toString(),
            operation.action(),
            operation.uid(),
            summary(operation),
            "pending",
            List.of("操作已登记，等待执行结果。"));
    append(pending);
    Result result;
    try {
      result = backend.execute(operation);
    } catch (GmError e) {
      result = new Result("failed", List.of(e.getMessage()));
    } catch (Exception e) {
      emu.lunarcore.LunarCore.getLogger()
          .error(
              "GM operation {} failed; inspect player state before retrying",
              operation.requestId(),
              e);
      result = new Result("uncertain", List.of("操作异常，可能已有部分变更。请核对玩家状态后再决定下一步。"));
    }
    var finished =
        new AuditEntry(
            pending.requestId(),
            fingerprint,
            pending.at(),
            pending.action(),
            pending.uid(),
            pending.summary(),
            result.state(),
            List.copyOf(result.output()));
    append(finished);
    return finished;
  }

  synchronized List<Map<String, Object>> history() {
    var result = new ArrayList<Map<String, Object>>();
    var reversed = new ArrayList<>(entries.values());
    Collections.reverse(reversed);
    for (var e : reversed) result.add(publicEntry(e));
    return result;
  }

  static Map<String, Object> publicEntry(AuditEntry e) {
    return Map.of(
        "requestId",
        e.requestId(),
        "at",
        e.at(),
        "action",
        e.action(),
        "uid",
        e.uid(),
        "summary",
        e.summary(),
        "state",
        e.state(),
        "output",
        e.output());
  }

  private void remember(AuditEntry entry) {
    entries.put(entry.requestId(), entry);
    while (entries.size() > KEEP) entries.remove(entries.keySet().iterator().next());
  }

  private void append(AuditEntry entry) {
    try {
      if (Files.isSymbolicLink(path)) throw new IOException("Symlink audit path");
      if (Files.exists(path) && Files.size(path) >= 4_000_000) {
        for (int i = 3; i >= 1; i--) {
          Path source = i == 1 ? path : Path.of(path + "." + (i - 1));
          if (Files.exists(source))
            Files.move(source, Path.of(path + "." + i), StandardCopyOption.REPLACE_EXISTING);
        }
      }
      byte[] line = (JSON.toJson(entry) + "\n").getBytes(StandardCharsets.UTF_8);
      try (var channel =
          FileChannel.open(
              path,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND)) {
        ByteBuffer buffer = ByteBuffer.wrap(line);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      remember(entry);
    } catch (IOException e) {
      throw new GmError(503, "操作记录暂时无法保存。请核对已有记录与玩家状态后再操作。");
    }
  }

  private static String fingerprint(Operation op) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(JSON.toJson(op).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String summary(Operation op) {
    return switch (op.action()) {
      case "grant" ->
          "发放 #" + op.itemId() + " × " + op.amount() + " · Lv." + op.level() + " · 阶 " + op.rank();
      case "mail" -> "邮件：" + op.title();
      case "command" -> op.command();
      case "heal" -> "恢复生命";
      case "refill" -> "补满秘技点";
      case "unstuck" -> "返回当前场景起点";
      default -> op.action();
    };
  }
}
