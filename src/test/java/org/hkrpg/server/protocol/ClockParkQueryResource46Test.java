package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import emu.lunarcore.server.packet.Retcode;
import org.hkrpg.server.gameplay.ClockParkManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Query cost stays bounded by the required source tables, while every new query checks bytes. */
class ClockParkQueryResource46Test {
  @TempDir Path root;
  final ClockParkMiniGames46Test mini = new ClockParkMiniGames46Test();
  static final List<String> CARD_FILES =
      List.of("ClockParkCard", "ClockParkCardAction", "ClockParkEffect", "ClockParkConst");

  @BeforeEach
  void prepare() throws Exception {
    mini.root = root;
    mini.prepare();
  }

  @AfterEach
  void restore() throws Exception {
    mini.restore();
  }

  private boolean valid() {
    return mini.fixture().opening.player.getGameplay().getClockPark().valid(ClockParkManager.catalog());
  }

  @FunctionalInterface
  private interface Query { void execute() throws Exception; }

  private Map<String, Long> sourceReads() throws Exception {
    return sourceReads(() -> assertTrue(valid()));
  }

  private Map<String, Long> sourceReads(Query query) throws Exception {
    var counts = new TreeMap<String, Long>();
    var trace = root.resolve("source-reads-" + UUID.randomUUID() + ".jfr");
    try (var recording = new Recording()) {
      recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withoutStackTrace();
      recording.start();
      query.execute();
      recording.stop();
      recording.dump(trace);
    }
    for (var event : RecordingFile.readAllEvents(trace)) {
      if (!event.getEventType().getName().equals("jdk.FileRead")) continue;
      var raw = event.getString("path");
      if (raw == null) continue;
      var path = Path.of(raw).toAbsolutePath().normalize();
      if (!path.startsWith(root.toAbsolutePath().normalize())) continue;
      String name = path.getFileName().toString();
      if (event.getLong("bytesRead") > 0) counts.merge(name, 1L, Long::sum);
    }
    return counts;
  }

  @ParameterizedTest
  @ValueSource(strings = {"ClockParkGetInfoCsReq", "ClockParkGetOngoingScriptInfoCsReq"})
  void actualNativeReadOnlyQueryChecksEachRequiredCardTableOnce(String request) throws Exception {
    mini.enter(420);
    assertTrue(valid());
    String before = mini.saved();
    int saves = mini.fixture().opening.saves;
    var reads = sourceReads(() -> {
      mini.fixture().opening.handle(request, new byte[0]);
      assertEquals(0, mini.fixture().opening.code());
    });
    System.out.println("nativeSourceReadCounts request=" + request + " " + reads);
    assertAll(CARD_FILES.stream().map(name -> (org.junit.jupiter.api.function.Executable) () ->
        assertEquals(1L, reads.getOrDefault(name + ".json", 0L),
            "Read-only native query must verify a complete card source once: " + name)));
    assertEquals(before, mini.saved());
    assertEquals(saves, mini.fixture().opening.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 410, 420})
  void wholeSavedHistoryReadsEachAuthoredCardTableOncePerValidation(int chapter) throws Exception {
    mini.enter(chapter);
    assertTrue(valid());
    String before = mini.saved();
    int saves = mini.fixture().opening.saves;
    for (int repeat = 0; repeat < 2; repeat++) {
      var reads = sourceReads();
      System.out.println("sourceReadCounts chapter=" + chapter + " " + reads);
      assertAll(
          CARD_FILES.stream().map(name -> (org.junit.jupiter.api.function.Executable) () ->
              assertEquals(1L, reads.getOrDefault(name + ".json", 0L),
                  "A complete source file must be verified once for this query: " + name)));
    }
    assertEquals(before, mini.saved());
    assertEquals(saves, mini.fixture().opening.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ClockParkCard", "ClockParkCardAction", "ClockParkEffect", "ClockParkConst"})
  void followingNativeQueryRejectsSameLengthSameTimestampSourceMutation(String name) throws Exception {
    mini.enter(420);
    assertTrue(valid());
    var path = root.resolve("ExcelOutput/" + name + ".json");
    byte[] original = Files.readAllBytes(path), changed = original.clone();
    FileTime time = Files.getLastModifiedTime(path);
    changed[0] = (byte) (changed[0] == '[' ? '{' : '[');
    String before = mini.saved();
    int saves = mini.fixture().opening.saves;
    Files.write(path, changed);
    Files.setLastModifiedTime(path, time);
    try {
      mini.fixture().opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
      assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), mini.fixture().opening.code());
      assertFalse(valid());
      assertEquals(before, mini.saved());
      assertEquals(saves, mini.fixture().opening.saves);
    } finally {
      Files.write(path, original);
      Files.setLastModifiedTime(path, time);
    }
    mini.fixture().query();
    assertEquals(before, mini.saved());
    assertEquals(saves, mini.fixture().opening.saves);
  }

  @Test
  void movieHistoryDoesNotRequireUnrelatedLotteryResources() throws Exception {
    mini.enter(420);
    assertFalse(Files.exists(root.resolve("ExcelOutput/ClockParkLottery.json")));
    assertTrue(valid());
    mini.fixture().query();
  }

  @Test
  void initialStoryKeepsLazySourceBoundaryAndDoesNotReadCards() throws Exception {
    mini.balanced.resetToProjectDefault();
    mini.fixture().opening.unlock(4);
    mini.fixture().opening.start(4);
    assertEquals(0, mini.fixture().opening.code());
    assertEquals("STORY", mini.run().getPhase());
    for (var name : CARD_FILES) Files.delete(root.resolve("ExcelOutput/" + name + ".json"));
    assertTrue(valid());
    assertTrue(sourceReads().keySet().stream().noneMatch(name -> CARD_FILES.contains(name.replace(".json", ""))));
    mini.fixture().query();
  }
}
