package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** One durable batch per owner and week even when an offline owner's room has many visitors. */
class TrainCakeRoomStore46Test {
  @TempDir Path root;
  final TrainCakeCatch46Test fixture = new TrainCakeCatch46Test();
  MongoServer mongo;
  com.mongodb.client.MongoClient client;
  TrainCakeRoomStore store;

  @BeforeEach void setup() throws Exception {
    fixture.resources = root; fixture.resources();
    mongo = new MongoServer(new MemoryBackend()); var address = mongo.bind();
    client = MongoClients.create("mongodb://127.0.0.1:" + address.getPort());
    store = new TrainCakeRoomStore(client.getDatabase("train-cake-rooms"));
  }
  @AfterEach void cleanup() { client.close(); mongo.shutdownNow(); fixture.restoreResources(); }

  static Map<Integer, Integer> spots(TrainCakeRoomBatch batch) {
    var rows = new TreeMap<Integer, Integer>();
    batch.spots().forEach(spot -> rows.put(spot.getCat(), spot.getSlot()));
    return rows;
  }

  @Test void migratesTheExistingBatchWithoutChangingPlayerProgress() {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    assertEquals(Retcode.SUCC, TrainCakeCatch.search(s.actor, 10, now).retcode());
    var before = fixture.gson.toJson(s.actor.getGameplay()); int saves = s.actor.saves;
    var batch = store.open(s.actor, now);
    assertEquals(10001, batch.ownerUid());
    assertEquals(Map.of(10, 4, 21, 6, 26, 5), spots(batch));
    assertEquals(s.actor.getGameplay().getTrainCakeCatch().getRefreshAt(), batch.refreshAt());
    assertEquals(before, fixture.gson.toJson(s.actor.getGameplay()));
    assertEquals(saves, s.actor.saves);
    assertEquals(spots(batch), spots(store.open(s.actor, now)));
  }

  @Test void concurrentOpensResolveToOnePersistedBatchAndOneRollover() throws Exception {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    var workers = Executors.newFixedThreadPool(6);
    try {
      List<Callable<TrainCakeRoomBatch>> calls = new ArrayList<>();
      for (int i = 0; i < 12; i++) calls.add(() -> store.open(s.actor, now));
      var results = workers.invokeAll(calls);
      var first = results.get(0).get();
      for (var result : results) {
        assertEquals(first.refreshAt(), result.get().refreshAt());
        assertEquals(spots(first), spots(result.get()));
      }
      long next = first.refreshAt(); calls.clear();
      for (int i = 0; i < 12; i++) calls.add(() -> store.open(s.actor, next));
      results = workers.invokeAll(calls); var rolled = results.get(0).get();
      assertEquals(TrainCakeCatch.nextRefresh(next), rolled.refreshAt());
      for (var result : results) assertEquals(spots(rolled), spots(result.get()));
      assertEquals(0, s.actor.saves, "Opening an offline owner's room cannot save a stale owner document");
    } finally { workers.shutdownNow(); }
  }

  @Test void theRoomBatchSurvivesAnActualH2DatabaseRestart() throws Exception {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    var path = root.resolve("room-restart.mv");
    TrainCakeRoomBatch before;
    var first = new MongoServer(new de.bwaldvogel.mongo.backend.h2.H2Backend(path.toString()));
    var address = first.bind();
    try (var connection = MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var persistent = new TrainCakeRoomStore(connection.getDatabase("restart-test"));
      before = persistent.open(s.actor, now);
    } finally { first.shutdownNow(); }
    var second = new MongoServer(new de.bwaldvogel.mongo.backend.h2.H2Backend(path.toString()));
    address = second.bind();
    try (var connection = MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var persistent = new TrainCakeRoomStore(connection.getDatabase("restart-test"));
      var restored = persistent.peek(s.actor.getUid(), now);
      assertNotNull(restored);
      assertEquals(before.refreshAt(), restored.refreshAt());
      assertEquals(spots(before), spots(restored));
      assertEquals(spots(before), spots(persistent.open(s.actor, now)));
      assertEquals(0, s.actor.saves);
    } finally { second.shutdownNow(); }
  }

  @Test void changedResourcesFailClosedWithoutRewritingTheExistingBatch() throws Exception {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    var first = store.open(s.actor, now);
    var path = root.resolve("ExcelOutput/CakeConfig.json");
    java.nio.file.Files.writeString(path, java.nio.file.Files.readString(path) + " ");
    assertThrows(RuntimeException.class, () -> store.open(s.actor, now));
    java.nio.file.Files.writeString(path, java.nio.file.Files.readString(path).stripTrailing());
    assertEquals(spots(first), spots(store.open(s.actor, now)));
    assertEquals(0, s.actor.saves);
  }

  @Test void ownersAdoptTheSameBatchOpenedEarlierByAVisitor() {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    for (int week = 0; week < 3; week++) {
      now = s.actor.getGameplay().getTrainCakeCatch().getRefreshAt();
      var visitorView = fixture.roomStore.open(s.actor, now);
      var ownerView = TrainCakeCatch.open(s.actor, now);
      assertEquals(Retcode.SUCC, ownerView.retcode());
      var actual = new TreeMap<Integer, Integer>();
      ownerView.state().getSpots().forEach(spot -> actual.put(spot.getCat(), spot.getSlot()));
      assertEquals(spots(visitorView), actual, "The owner's retry must not redraw the visitor's batch");
      assertEquals(visitorView.refreshAt(), ownerView.state().getRefreshAt());
    }
  }

  @Test void anOwnerCanSearchAVisitorsExistingBatchFromTheOrdinaryTrain() {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    var visitorView = fixture.roomStore.open(s.actor, now);
    assertEquals(0, s.actor.getGameplay().getTrainCakeCatch().getRefreshAt());
    assertEquals(Retcode.SUCC, TrainCakeCatch.search(s.actor, 10, now).retcode());
    assertEquals(spots(visitorView), spots(new TrainCakeRoomBatch(s.actor.getUid(),
        s.actor.getGameplay().getTrainCakeCatch().getSource(),
        s.actor.getGameplay().getTrainCakeCatch().getRefreshAt(),
        s.actor.getGameplay().getTrainCakeCatch().getSpots())));
    assertEquals(Map.of(10, 1), s.actor.getGameplay().getTrainCakeCatch().getCounts());
  }
}
