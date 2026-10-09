package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.util.Position;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.ProtocolBridge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalLegendProgressTest {
  @TempDir Path root;
  final LocalLegendCatalogTest fixture = new LocalLegendCatalogTest();
  LocalLegendProgress progress;

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
    progress = new LocalLegendProgress();
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
  }

  @Test
  void zeroQueriesAndMissingReportsDoNotCreateCompletionOrHistory() {
    assertTrue(progress.valid());
    assertEquals(0, progress.mask(LocalLegendCatalog.level(1, 3)));
    assertFalse(progress.merge(LocalLegendCatalog.level(1, 3), 0, 100));
    assertTrue(progress.entries().isEmpty());
    assertFalse(progress.hasTarget(5001805));
  }

  @Test
  void confirmedTargetBitsMergeAcrossRunsWithoutCompletingMissingGoals() {
    var level = LocalLegendCatalog.level(1, 3);
    assertTrue(progress.merge(level, 2, 100));
    assertEquals(2, progress.mask(level));
    assertFalse(level.complete(progress.mask(level)));
    assertTrue(progress.hasTarget(5001826));
    assertFalse(progress.hasTarget(5001805));
    assertTrue(progress.merge(level, 4, 101));
    assertEquals(6, progress.mask(level));
    assertFalse(progress.merge(level, 2, 102));
    assertEquals(101, progress.entries().getFirst().changedAt());
    assertTrue(progress.merge(level, 1, 103));
    assertEquals(7, progress.mask(level));
    assertTrue(level.complete(progress.mask(level)));
    assertEquals(0, progress.mask(LocalLegendCatalog.level(1, 2)));
    assertEquals(0, progress.mask(LocalLegendCatalog.level(2, 3)));
    assertFalse(progress.hasTarget(5001804));
    assertTrue(progress.valid());
  }

  @Test
  void preparedCopyCannotMutateTheExistingStoredSnapshot() {
    var level = LocalLegendCatalog.level(1, 3);
    progress.merge(level, 1, 100);
    var candidate = progress.copy();
    assertTrue(candidate.merge(level, 4, 101));
    assertEquals(1, progress.mask(level));
    assertEquals(5, candidate.mask(level));
    assertThrows(UnsupportedOperationException.class, () -> candidate.entries().clear());
  }

  @ParameterizedTest
  @ValueSource(ints = {8, -1})
  void impossibleBitsAreRejectedBeforeChangingStoredHistory(int mask) {
    var level = LocalLegendCatalog.level(1, 3);
    progress.merge(level, 1, 100);
    assertThrows(IllegalArgumentException.class, () -> progress.merge(level, mask, 101));
    assertEquals(1, progress.mask(level));
  }

  @Test
  void reversedCompletionClockAndForeignLevelObjectsAreRejected() {
    var level = LocalLegendCatalog.level(1, 3);
    progress.merge(level, 1, 100);
    assertThrows(IllegalArgumentException.class, () -> progress.merge(level, 2, 99));
    var cloned =
        new LocalLegendCatalog.Level(
            level.group(),
            level.difficulty(),
            level.event(),
            level.unlockQuest(),
            level.battleArea(),
            level.goals(),
            level.trials());
    assertThrows(IllegalArgumentException.class, () -> progress.merge(cloned, 2, 101));
    assertThrows(IllegalStateException.class, () -> progress.mask(cloned));
    assertEquals(1, progress.mask(level));
  }

  @Test
  void resourceTargetReorderingDoesNotReassignOldHistoryBits() throws Exception {
    var level = LocalLegendCatalog.level(1, 3);
    progress.merge(level, 2, 100);
    var rows = fixture.rows("LocalLegendStageConfig").deepCopy();
    for (var row : rows) {
      var r = row.getAsJsonObject();
      if (r.get("GroupID").getAsInt() == 1
          && r.get("DifficultyLevel").getAsString().equals("Fearless")) {
        var targets = r.getAsJsonArray("BattleTargetList");
        var first = targets.get(0);
        targets.set(0, targets.get(1));
        targets.set(1, first);
      }
    }
    fixture.write("LocalLegendStageConfig", rows);
    LocalLegendCatalog.load(root);
    assertFalse(progress.valid());
    assertFalse(progress.hasTarget(5001805));
    assertFalse(progress.hasTarget(5001826));
    assertThrows(IllegalStateException.class, () -> progress.mask(LocalLegendCatalog.level(1, 3)));
    assertEquals(2, progress.entries().getFirst().mask());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void malformedStoredBindingsRemainPresentAndCannotBeReadAsCompletion(int variant) {
    progress.merge(LocalLegendCatalog.level(1, 3), 2, 100);
    var data = new Gson().toJsonTree(progress).getAsJsonObject();
    var entry = data.getAsJsonObject("entries").getAsJsonObject("1:3");
    switch (variant) {
      case 0 -> entry.addProperty("group", 2);
      case 1 -> entry.addProperty("difficulty", 2);
      case 2 -> entry.addProperty("mask", 8);
      case 3 -> entry.addProperty("mask", 0);
      case 4 -> entry.addProperty("changedAt", 0);
      case 5 -> entry.addProperty("resourceIdentity", "other");
    }
    var restored = new Gson().fromJson(data, LocalLegendProgress.class);
    assertFalse(restored.valid());
    assertEquals(1, restored.entries().size());
    assertFalse(restored.hasTarget(5001826));
    assertThrows(IllegalStateException.class, () -> restored.mask(LocalLegendCatalog.level(1, 3)));
  }

  @Test
  void nativeProgressFieldContainsTheOrderedBitMaskRatherThanItsPopulationCount() throws Exception {
    var level = LocalLegendCatalog.level(1, 3);
    progress.merge(level, 4, 100);
    var protocol = ProtocolBridge.forVersion("4.6.0");
    var type = protocol.clientSchema().message("JJEFLBCLBFN");
    var wire =
        DynamicMessage.newBuilder(type)
            .setField(type.findFieldByName("group_id"), 1)
            .setField(type.findFieldByName("DBKHHCGGKNN"), 3)
            .setField(type.findFieldByName("FFGFINDLMCF"), progress.mask(level))
            .build();
    var decoded = protocol.clientSchema().parse("JJEFLBCLBFN", wire.toByteArray());
    assertEquals(1, type.findFieldByName("FFGFINDLMCF").getNumber());
    assertEquals(4, decoded.getField(type.findFieldByName("FFGFINDLMCF")));
    assertNotEquals(Integer.bitCount(4), decoded.getField(type.findFieldByName("FFGFINDLMCF")));
  }

  @Test
  void actualPlayerMorphiaReadsPreserveIndependentTargetBitsTwice() throws Exception {
    var player = new Player();
    var uid = Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(player, 7310046);
    player.getGameplay().getLocalLegendProgress().merge(LocalLegendCatalog.level(1, 3), 4, 100);
    player.getGameplay().getLocalLegendProgress().merge(LocalLegendCatalog.level(3, 2), 2, 101);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "local-legend-progress");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      store.save(player);
      for (int i = 0; i < 2; i++) {
        var restored = store.find(Player.class).first();
        assertNotNull(restored);
        var saved = restored.getGameplay().getLocalLegendProgress();
        assertTrue(saved.valid());
        assertEquals(4, saved.mask(LocalLegendCatalog.level(1, 3)));
        assertEquals(2, saved.mask(LocalLegendCatalog.level(3, 2)));
        assertEquals(0, saved.mask(LocalLegendCatalog.level(2, 3)));
        assertTrue(saved.hasTarget(5001827));
        assertFalse(saved.hasTarget(5001805));
        store.save(restored);
      }
    } finally {
      mongo.shutdownNow();
    }
  }
}
