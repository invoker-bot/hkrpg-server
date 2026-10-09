package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Uses the original bundled level; checkpoint response must agree with saved player coordinates.
 */
class WolfBroRestore46Test {
  WolfBro46Test fixture = new WolfBro46Test();

  @BeforeEach
  void prepare() throws Exception {
    fixture.prepare();
    fixture.start();
    assertEquals(0, fixture.code(fixture.archive(140000)));
    actor().getPos().set(1, 2, 3);
    actor().getRot().set(4, 5, 6);
    fixture.saves = 0;
  }

  @AfterEach
  void cleanup() {
    fixture.restore();
  }

  Player actor() {
    return fixture.session.actor;
  }

  DynamicMessage restore() throws Exception {
    return fixture.request("RestoreWolfBroGameArchiveCsReq", 3);
  }

  void atCheckpoint() {
    assertEquals(List.of(140000, -30000, 150000), coordinates(actor().getPos()));
    assertEquals(List.of(-90000, 0, 0), coordinates(actor().getRot()));
  }

  static List<Integer> coordinates(Position p) {
    return List.of(p.getX(), p.getY(), p.getZ());
  }

  void unchanged() {
    assertEquals(List.of(1, 2, 3), coordinates(actor().getPos()));
    assertEquals(List.of(4, 5, 6), coordinates(actor().getRot()));
    assertEquals(0, fixture.saves);
  }

  static void field(Object o, Class<?> type, String name, Object value) throws Exception {
    var f = type.getDeclaredField(name);
    f.setAccessible(true);
    f.set(o, value);
  }

  @Test
  void savedPositionAndRotationAreRestoredBeforeSavingAndAcknowledging() throws Exception {
    var run = actor().getGameplay().getWolfBro().getRuns().get(3);
    fixture.beforeSave = this::atCheckpoint;
    assertEquals(0, fixture.code(restore()));
    atCheckpoint();
    assertEquals(1, fixture.saves);
    assertSame(run, actor().getGameplay().getWolfBro().getRuns().get(3));
  }

  @Test
  void repeatedRestoreAtTheSameCheckpointDoesNotWriteAgain() throws Exception {
    assertEquals(0, fixture.code(restore()));
    atCheckpoint();
    assertEquals(1, fixture.saves);
    assertEquals(0, fixture.code(restore()));
    assertEquals(1, fixture.saves);
  }

  @Test
  void failedSaveRollsBackBothCoordinatesAndKeepsTheOriginalCheckpoint() throws Exception {
    var pos = actor().getPos();
    var rot = actor().getRot();
    var run = actor().getGameplay().getWolfBro().getRuns().get(3);
    fixture.beforeSave = this::atCheckpoint;
    fixture.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture.code(restore()));
    assertSame(pos, actor().getPos());
    assertSame(rot, actor().getRot());
    unchanged();
    assertSame(run, actor().getGameplay().getWolfBro().getRuns().get(3));
  }

  @Test
  void aFailedRestoreCanBeRetriedWithoutChangingTheSavedIdentity() throws Exception {
    var uuid = actor().getGameplay().getWolfBro().getRuns().get(3).getUuid();
    fixture.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture.code(restore()));
    unchanged();
    fixture.failSave = false;
    assertEquals(0, fixture.code(restore()));
    atCheckpoint();
    assertEquals(uuid, actor().getGameplay().getWolfBro().getRuns().get(3).getUuid());
    assertEquals(1, fixture.saves);
  }

  @Test
  void twentyFourConcurrentRestoresSaveTheActualPositionOnlyOnce() throws Exception {
    var pool = Executors.newFixedThreadPool(8);
    try {
      var tasks = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 24; i++)
        tasks.add(
            () -> new org.hkrpg.server.gameplay.WolfBroManager(actor()).restore(3).code().getVal());
      for (var answer : pool.invokeAll(tasks)) assertEquals(0, answer.get());
      atCheckpoint();
      assertEquals(1, fixture.saves);
    } finally {
      pool.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"pos", "rot"})
  void missingPlayerCoordinatesCannotAcknowledgeARecovery(String name) throws Exception {
    field(actor(), Player.class, name, null);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), fixture.code(restore()));
    assertNull(name.equals("pos") ? actor().getPos() : actor().getRot());
    assertEquals(0, fixture.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pos", "rot"})
  void aDamagedCheckpointDoesNotMoveTheActualPlayer(String name) throws Exception {
    var archive = actor().getGameplay().getWolfBro().getRuns().get(3).getArchive();
    field(archive, archive.getClass(), name, null);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), fixture.code(restore()));
    unchanged();
  }

  @Test
  void aliasedPlayerCoordinatesCannotOverwriteEachOtherDuringRecovery() throws Exception {
    var pos = actor().getPos();
    field(actor(), Player.class, "rot", pos);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), fixture.code(restore()));
    assertSame(pos, actor().getRot());
    assertEquals(List.of(1, 2, 3), coordinates(pos));
    assertEquals(0, fixture.saves);
  }

  @Test
  void queryingDoesNotTeleportThePlayerToTheSavedCheckpoint() throws Exception {
    assertEquals(0, fixture.code(fixture.request("GetWolfBroGameDataCsReq", 3)));
    unchanged();
  }

  @Test
  void savingANewCheckpointDoesNotTeleportTheCurrentPlayer() throws Exception {
    assertEquals(0, fixture.code(fixture.archive(160000)));
    assertEquals(List.of(1, 2, 3), coordinates(actor().getPos()));
    assertEquals(List.of(4, 5, 6), coordinates(actor().getRot()));
    assertEquals(1, fixture.saves);
  }

  @Test
  void aQuitGameCannotTeleportToAnOldCheckpoint() throws Exception {
    assertEquals(0, fixture.code(fixture.request("QuitWolfBroGameCsReq", 3)));
    fixture.saves = 0;
    assertNotEquals(0, fixture.code(restore()));
    unchanged();
  }

  @Test
  void aDetachedSceneCannotTeleportToAnOldCheckpoint() throws Exception {
    fixture.current = null;
    assertNotEquals(0, fixture.code(restore()));
    unchanged();
  }

  @Test
  void aChangedSourceSignatureCannotTeleportToAnOldCheckpoint() throws Exception {
    var run = actor().getGameplay().getWolfBro().getRuns().get(3);
    field(run, run.getClass(), "sourceSignature", "changed");
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), fixture.code(restore()));
    unchanged();
  }

  @Test
  void twoActualPlayerMorphiaReadsRetainRestoredCoordinatesAndCheckpointTogether()
      throws Exception {
    assertEquals(0, fixture.code(restore()));
    atCheckpoint();
    var backend = new de.bwaldvogel.mongo.backend.memory.MemoryBackend();
    var server = new de.bwaldvogel.mongo.MongoServer(backend);
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "wolfbro-restored-position");
      store.getMapper().map(Player.class, Position.class, emu.lunarcore.game.quest.QuestState.class);
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      var document = new Player();
      field(document, Player.class, "uid", 123456787);
      field(document, Player.class, "name", "position-fixture");
      field(document, Player.class, "gameplay", actor().getGameplay());
      field(document, Player.class, "pos", new Position(actor().getPos()));
      field(document, Player.class, "rot", new Position(actor().getRot()));
      for (int i = 0; i < 2; i++) {
        store.save(document);
        document =
            store
                .find(Player.class)
                .filter(dev.morphia.query.filters.Filters.eq("uid", 123456787))
                .first();
        assertNotNull(document);
        assertEquals(List.of(140000, -30000, 150000), coordinates(document.getPos()));
        assertEquals(List.of(-90000, 0, 0), coordinates(document.getRot()));
        assertEquals(
            140000,
            document.getGameplay().getWolfBro().getRuns().get(3).getArchive().getPos().getX());
      }
    } finally {
      server.shutdownNow();
    }
  }
}
