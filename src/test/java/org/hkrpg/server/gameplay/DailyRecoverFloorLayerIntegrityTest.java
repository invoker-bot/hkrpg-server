package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Fixed original files stay unchanged while a loaded floor is modified and restored. */
class DailyRecoverFloorLayerIntegrityTest {
  private final DailyRecoverIntroductions46Test introductions = new DailyRecoverIntroductions46Test();
  private final DailyMissionRecoverPerformance46Test performance = introductions.p;
  private final DailyMissionRecoverDelivery46Test delivery = performance.f;
  @TempDir Path root;

  @BeforeAll
  static void database() throws Exception {
    DailyMissionRecoverDelivery46Test.isolateRealItemWrites();
  }

  @AfterAll
  static void close() throws Exception {
    DailyMissionRecoverDelivery46Test.closeOwnedDatabase();
  }

  @BeforeEach
  void setup() throws Exception {
    introductions.root = root;
    introductions.setup();
  }

  @AfterEach
  void cleanup() throws Exception {
    introductions.cleanup();
  }

  @ParameterizedTest
  @CsvSource({"10102,10102001", "10102,10102003", "20121,20121001", "20123,20123001"})
  void eachLoadedOriginalFloorProtectsRelayAndFinishUntilItsMasksAreRestored(
      int plane, int floorId) throws Exception {
    var floor = GameData.getFloorInfo(plane, floorId);
    assertNotNull(floor);
    assertTrue(floor.isLoaded());
    int[] masks = floor.getLayerToAreaMask();
    int[] expected = floorId == 20123001 ? new int[] {16, 8} : new int[] {1};
    assertArrayEquals(expected, masks);
    var path = root.resolve("Config/LevelOutput/RuntimeFloor/P" + plane + "_F" + floorId + ".json");
    byte[] sourceBytes = Files.readAllBytes(path);
    assertArrayEquals(DailyMissionRecoverDelivery46Test.SourceHolder.CONFIG_BYTES.get(
        "Config/LevelOutput/RuntimeFloor/P" + plane + "_F" + floorId + ".json"), sourceBytes);
    assertTrue(DailyMissionRecoverDeliveryRules.source(), "Complete original loaded floors must qualify");

    delivery.first();
    int menu = DailyMissionRecoverDelivery46Test.MENU;
    int intro = DailyRecoverIntroductions46Test.IDS[0];
    assertTrue(performance.actors(122, 400001).isEmpty());
    assertFalse(introductions.seen(intro));
    String gameplay = delivery.daily.gson.toJson(delivery.daily.actor.getGameplay());
    int saves = delivery.daily.actor.saves;
    try {
      masks[0] ^= 32;
      assertFalse(DailyMissionRecoverDeliveryRules.source(), "An in-memory change must not reuse disk integrity");
      assertNotEquals(0, performance.start(menu, 4));
      assertNotEquals(0, performance.start(intro, 4));
      assertNotEquals(0, delivery.report(menu));
      assertNotEquals(0, introductions.finish(intro));
      assertEquals(gameplay, delivery.daily.gson.toJson(delivery.daily.actor.getGameplay()));
      assertEquals(saves, delivery.daily.actor.saves);
      assertTrue(performance.actors(122, 400001).isEmpty());
      assertEquals(QuestState.Phase.DOING, delivery.daily.actor.getMissionManager().sub(menu).getPhase());
      assertFalse(introductions.seen(intro));
      assertArrayEquals(sourceBytes, Files.readAllBytes(path));
    } finally {
      System.arraycopy(expected, 0, masks, 0, expected.length);
    }

    assertTrue(DailyMissionRecoverDeliveryRules.source(), "Restoring only the loaded masks must restore eligibility");
    assertEquals(0, performance.start(menu, 4));
    assertEquals(1, performance.actors(122, 400001).size());
    assertEquals(0, delivery.report(menu));
    assertTrue(performance.actors(122, 400001).isEmpty());
    assertTrue(MissionStateRecords.subCompleted(delivery.daily.actor, menu));
    assertEquals(0, performance.start(intro, 4));
    assertEquals(0, introductions.finish(intro));
    assertTrue(introductions.seen(intro));
    assertEquals(0, delivery.daily.actor.getScoin());
    assertArrayEquals(expected, masks);
    assertArrayEquals(sourceBytes, Files.readAllBytes(path));
  }
}