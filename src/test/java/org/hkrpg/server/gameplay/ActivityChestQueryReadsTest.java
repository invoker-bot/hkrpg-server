package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ActivityChestQueryReadsTest {
  @TempDir Path root;
  final WorldChestActivity46Test fixture = new WorldChestActivity46Test();
  @BeforeEach void prepare() throws Exception { fixture.root = root; fixture.load(); }
  @AfterEach void restore() throws Exception { fixture.restore(); }

  @Test void lockedActivityDoesNotScanItsWorldCatalogDuringLoginRefresh() throws Exception {
    assertTrue(ActivityAccess.unlocked(fixture.player, 30013));
    var unlockedMissions = new HashMap<>(fixture.player.getGameplay().getMainMissions());
    fixture.player.getGameplay().getMainMissions().clear();
    assertFalse(ActivityAccess.unlocked(fixture.player, 30013));
    int saves = fixture.player.saves;
    var reads = org.hkrpg.server.SourceReadAssertions.reads(root, () -> {
      assertFalse(ActivityChestQuests.refresh(fixture.player));
      assertTrue(ActivityChestQuests.states(fixture.player).isEmpty());
    });
    assertTrue(reads.isEmpty(), "Locked activity scanned resources: " + reads);
    assertEquals(saves, fixture.player.saves);
    fixture.player.getGameplay().getMainMissions().putAll(unlockedMissions);
    assertTrue(ActivityChestQuests.refresh(fixture.player));
    assertFalse(ActivityChestQuests.states(fixture.player).isEmpty());
  }

  @Test void alreadyObservedMainCompletionDoesNotReloadUnrelatedQuestCatalogs() throws Exception {
    var data = emu.lunarcore.data.GameData.getQuestExcelMap();
    var finishes = emu.lunarcore.data.GameData.getFinishWayExcelMap();
    var gson = emu.lunarcore.data.ResourceLoader.getGson();
    data.add(gson.fromJson("{\"QuestID\":19996901,\"QuestType\":1,\"FinishWayID\":19996901,\"UnlockType\":\"AutoUnlock\"}",
        emu.lunarcore.data.excel.QuestExcel.class));
    finishes.add(gson.fromJson("{\"ID\":19996901,\"FinishType\":\"FinishMission\",\"Progress\":1,\"ParamType\":\"ListContain\",\"ParamIntList\":[1000510]}",
        emu.lunarcore.data.excel.FinishWayExcel.class));
    assertTrue(fixture.player.getQuestManager().onMissionProgress());
    var completed = fixture.player.getQuestStates().get(19996901);
    assertEquals(emu.lunarcore.game.quest.QuestState.Phase.FINISHED, completed.getPhase());
    int saves = fixture.player.saves;
    var reads = org.hkrpg.server.SourceReadAssertions.reads(root,
        () -> assertFalse(fixture.player.getQuestManager().onMissionProgress()));
    assertTrue(reads.isEmpty(), "An unchanged completion reloaded unrelated quest catalogs: " + reads);
    assertSame(completed, fixture.player.getQuestStates().get(19996901));
    assertEquals(saves, fixture.player.saves);
  }

  @Test void oneSnapshotReadsEveryRequiredSourceOnceWithoutSavingPlayerData() throws Exception {
    ActivityChestQuests.refresh(fixture.player);
    var expected = fixture.player.getGameplay().getWorldChests().getQuests();
    assertFalse(expected.isEmpty());
    int saves = fixture.player.saves;
    var trace = root.resolve("query-reads.jfr");
    try (var recording = new Recording()) {
      recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withoutStackTrace();
      recording.start();
      var actual = ActivityChestQuests.states(fixture.player);
      assertEquals(expected.keySet(), actual.stream().map(q -> q.getQuestId()).collect(java.util.stream.Collectors.toSet()));
      actual.forEach(q -> assertSame(expected.get(q.getQuestId()), q));
      recording.stop(); recording.dump(trace);
    }
    var counts = new TreeMap<String, Long>();
    for (var event : RecordingFile.readAllEvents(trace)) {
      if (!event.getEventType().getName().equals("jdk.FileRead") || event.getLong("bytesRead") <= 0) continue;
      var raw = event.getString("path");
      if (raw == null) continue;
      var path = Path.of(raw).toAbsolutePath().normalize();
      if (path.startsWith(root.toAbsolutePath().normalize()) && path.toString().endsWith(".json"))
        counts.merge(root.toAbsolutePath().normalize().relativize(path).toString(), 1L, Long::sum);
    }
    assertFalse(counts.isEmpty());
    counts.forEach((path, count) -> assertEquals(1L, count,
        "A single snapshot must read each complete source once: " + path));
    assertEquals(saves, fixture.player.saves);
  }

  @Test void theNextSnapshotStillDetectsSameLengthSameTimestampResourceChanges() throws Exception {
    ActivityChestQuests.refresh(fixture.player);
    var expected = ActivityChestQuests.states(fixture.player);
    assertFalse(expected.isEmpty());
    int saves = fixture.player.saves;
    var source = root.resolve("ExcelOutput/QuestData.json");
    byte[] original = Files.readAllBytes(source), changed = original.clone();
    var time = Files.getLastModifiedTime(source);
    changed[0] = '[' == changed[0] ? (byte)'{' : (byte)'[';
    Files.write(source, changed); Files.setLastModifiedTime(source, time);
    try { assertTrue(ActivityChestQuests.states(fixture.player).isEmpty()); }
    finally { Files.write(source, original); Files.setLastModifiedTime(source, time); }
    assertEquals(expected, ActivityChestQuests.states(fixture.player));
    assertEquals(saves, fixture.player.saves);
  }
}
