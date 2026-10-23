package org.hkrpg.server;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

/** Measures actual resource reads without replacing the production loaders. */
public final class SourceReadAssertions {
  public static Set<Path> reads(Path root, Runnable action) throws Exception {
    Path base = root.toAbsolutePath().normalize();
    Path trace = base.resolve("source-reads-" + UUID.randomUUID() + ".jfr");
    try (var recording = new Recording()) {
      recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withoutStackTrace();
      recording.start();
      action.run();
      recording.stop();
      recording.dump(trace);
    }
    var reads = new TreeSet<Path>();
    for (var event : RecordingFile.readAllEvents(trace)) {
      if (!event.getEventType().getName().equals("jdk.FileRead")
          || event.getLong("bytesRead") <= 0) continue;
      String raw = event.getString("path");
      if (raw == null) continue;
      Path path = Path.of(raw).toAbsolutePath().normalize();
      if (path.startsWith(base) && path.toString().endsWith(".json")) reads.add(base.relativize(path));
    }
    return reads;
  }

  private SourceReadAssertions() {}
}
