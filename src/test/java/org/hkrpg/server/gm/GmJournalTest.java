package org.hkrpg.server.gm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.hkrpg.server.gm.GmModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GmJournalTest {
  @TempDir Path directory;

  static class Backend implements GmBackend {
    final AtomicInteger count = new AtomicInteger();

    public Status status() {
      return new Status(true, true, 1, 1, 1, 1);
    }

    public List<PlayerRow> players() {
      return List.of();
    }

    public List<CatalogItem> catalog() {
      return List.of();
    }

    public List<CommandRow> commands() {
      return List.of();
    }

    public Result execute(Operation op) {
      count.incrementAndGet();
      return Result.completed("stored");
    }
  }

  static Operation operation() {
    return new Operation(
        UUID.randomUUID().toString(),
        "grant",
        10001,
        101,
        1,
        0,
        0,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void completedRequestSurvivesRestartAndDifferentPayloadCannotReuseItsId() throws Exception {
    var backend = new Backend();
    var op = operation();
    var first = new GmJournal(directory, Clock.systemUTC()).execute(op, backend);
    var restarted = new GmJournal(directory, Clock.systemUTC());
    assertEquals(first, restarted.execute(op, backend));
    assertEquals(1, backend.count.get());
    var changed =
        new Operation(
            op.requestId(), "grant", 10002, 101, 1, 0, 0, null, null, null, null, null, null);
    assertEquals(
        409, assertThrows(GmError.class, () -> restarted.execute(changed, backend)).status);
    assertEquals(1, backend.count.get());
  }

  @Test
  void unfinishedIntentAfterCrashIsVisibleAndNeverReexecuted() throws Exception {
    var backend = new Backend();
    var op = operation();
    new GmJournal(directory, Clock.systemUTC()).execute(op, backend);
    Path path = directory.resolve("gm-audit.jsonl");
    Files.writeString(path, Files.readAllLines(path).getFirst() + "\n");
    var restarted = new GmJournal(directory, Clock.systemUTC());
    assertEquals(409, assertThrows(GmError.class, () -> restarted.execute(op, backend)).status);
    assertEquals("pending", restarted.history().getFirst().get("state"));
    assertEquals(1, backend.count.get());
  }

  @Test
  void persistenceFailurePreventsMutation() throws Exception {
    var journal = new GmJournal(directory, Clock.systemUTC());
    Files.createDirectory(directory.resolve("gm-audit.jsonl"));
    var backend = new Backend();
    assertEquals(
        503, assertThrows(GmError.class, () -> journal.execute(operation(), backend)).status);
    assertEquals(0, backend.count.get());
  }

  @Test
  void failedExecutionKeepsItsResultAcrossReplay() throws Exception {
    var backend =
        new Backend() {
          public Result execute(Operation op) {
            count.incrementAndGet();
            throw new GmError(409, "背包已满");
          }
        };
    var op = operation();
    var journal = new GmJournal(directory, Clock.systemUTC());
    assertEquals("failed", journal.execute(op, backend).state());
    assertEquals(List.of("背包已满"), journal.execute(op, backend).output());
    assertEquals(1, backend.count.get());
  }
}
