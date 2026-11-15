package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Visit captures must be durable, idempotent and independent of a room's owner. */
class TrainCakeVisits46Test {
  @TempDir Path root;
  final TrainCakeCatch46Test fixture = new TrainCakeCatch46Test();
  @BeforeEach void setup() throws Exception { fixture.resources = root; fixture.resources(); }
  @AfterEach void cleanup() { fixture.restoreResources(); }

  TrainCakeRoomBatch room(TrainCakeCatchState owner, int uid) {
    return new TrainCakeRoomBatch(uid, owner.getSource(), owner.getRefreshAt(), owner.getSpots());
  }

  @Test void otherRoomCaptureKeepsTheOwnersBatchAndTheVisitorsOwnCaptureIndependent() {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    assertEquals(Retcode.SUCC, TrainCakeCatch.search(s.actor, 10, now).retcode());
    var own = s.actor.getGameplay().getTrainCakeCatch();
    var otherRoom = room(own, 20001);
    assertEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, otherRoom, 10, now));
    assertEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, otherRoom, 10, now));
    assertEquals(Map.of(10, 1), own.getCounts());
    assertEquals(Set.of(10), own.getCaught());
    assertEquals(Map.of(10, 1), TrainCakeVisits.counts(s.actor));
    assertEquals(Map.of(20001, Set.of(10)), TrainCakeVisits.records(s.actor, now));
    assertEquals(7, TrainCakeCatch.weeklyRemaining(s.actor, now));
    assertEquals(3, s.actor.saves, "Repeated claims must not write twice");
  }

  @Test void nineWeeklyCapturesSpanRoomsAndResetWithoutErasingLifetimeTotals() {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    var own = s.actor.getGameplay().getTrainCakeCatch();
    for (int uid : List.of(20001, 20002, 20003))
      for (int cat : List.of(10, 21, 26))
        assertEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room(own, uid), cat, now));
    assertEquals(0, TrainCakeCatch.weeklyRemaining(s.actor, now));
    assertNotEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room(own, 20004), 10, now));
    assertEquals(Map.of(10, 3, 21, 3, 26, 3), TrainCakeVisits.counts(s.actor));
    long next = own.getRefreshAt();
    assertEquals(9, TrainCakeCatch.weeklyRemaining(s.actor, next));
    assertTrue(TrainCakeVisits.records(s.actor, next).isEmpty());
    assertEquals(Map.of(10, 3, 21, 3, 26, 3), TrainCakeVisits.counts(s.actor));
  }

  @Test void failedSavesRollbackAndCanBeRetried() {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    var room = room(s.actor.getGameplay().getTrainCakeCatch(), 20001);
    s.actor.failSave = true;
    assertEquals(Retcode.SERVER_INTERNAL_ERROR, TrainCakeVisits.capture(s.actor, room, 10, now));
    assertTrue(TrainCakeVisits.counts(s.actor).isEmpty());
    assertTrue(TrainCakeVisits.records(s.actor, now).isEmpty());
    assertEquals(9, TrainCakeCatch.weeklyRemaining(s.actor, now));
    s.actor.failSave = false;
    assertEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room, 10, now));
    assertEquals(Map.of(10, 1), TrainCakeVisits.counts(s.actor));
  }

  @Test void invalidFutureWeekCannotBeReportedAsCurrentReceipts() throws Exception {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    var own = s.actor.getGameplay().getTrainCakeCatch();
    assertEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room(own, 20001), 10, now));
    var state = s.actor.getGameplay().getTrainCakeVisits();
    var refresh = state.getClass().getDeclaredField("refreshAt"); refresh.setAccessible(true);
    refresh.setLong(state, TrainCakeCatch.nextRefresh(own.getRefreshAt()));
    assertThrows(IllegalStateException.class, () -> TrainCakeVisits.records(s.actor, now));
    assertThrows(IllegalStateException.class, () -> TrainCakeCatch.weeklyRemaining(s.actor, now));
    assertEquals(Retcode.PLAYER_DATA_ERROR, TrainCakeVisits.capture(s.actor, room(own, 20001), 10, now));
  }

  @Test void unknownExpiredOrMismatchedRoomsCannotGrantProgress() {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    var own = s.actor.getGameplay().getTrainCakeCatch();
    assertNotEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room(own, 20001), 1, now));
    assertNotEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room(own, 10001), 10, now));
    assertNotEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room(own, 20001), 10, own.getRefreshAt()));
    var badSource = new TrainCakeRoomBatch(20001, "0".repeat(64), own.getRefreshAt(), own.getSpots());
    assertNotEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, badSource, 10, now));
    assertTrue(TrainCakeVisits.counts(s.actor).isEmpty());
    assertEquals(1, s.actor.saves);
  }

  @Test void nativeProfileReturnsCombinedTotalsAndSeparateRoomRecords() throws Exception {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    assertEquals(Retcode.SUCC, TrainCakeCatch.search(s.actor, 10, now).retcode());
    var own = s.actor.getGameplay().getTrainCakeCatch();
    assertEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room(own, 20001), 10, now));
    assertEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room(own, 20002), 21, now));
    var rsp = TrainCakeCatch46Test.request(s, "SocialPlayGetDataCsReq", new byte[0], "SocialPlayGetDataScRsp");
    var profile = (com.google.protobuf.DynamicMessage) TrainCakeCatch46Test.field(rsp, "ELDIONMAAHO");
    var counts = new TreeMap<Integer, Integer>();
    for (var raw : TrainCakeCatch46Test.list(profile, "NIELDCLPOLA")) {
      var row = (com.google.protobuf.DynamicMessage) raw;
      counts.put((int) TrainCakeCatch46Test.field(row, "PCPDHEIEJFO"),
          (int) TrainCakeCatch46Test.field(row, "count"));
    }
    assertEquals(Map.of(10, 2, 21, 1), counts);
    var rooms = new TreeMap<Integer, List<?>>();
    for (var raw : TrainCakeCatch46Test.list(profile, "CEMFBJPOLNP")) {
      var row = (com.google.protobuf.DynamicMessage) raw;
      rooms.put((int) TrainCakeCatch46Test.field(row, "PCPDHEIEJFO"),
          TrainCakeCatch46Test.list(row, "LPDFMJPFGJH"));
    }
    assertEquals(Map.of(10001, List.of(10), 20001, List.of(10), 20002, List.of(21)), rooms);
  }

  @Test void localAndVisitorCapturesShareTheThirteenPerSpeciesLimit() {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    assertEquals(Retcode.SUCC, TrainCakeCatch.search(s.actor, 10, now).retcode());
    var own = s.actor.getGameplay().getTrainCakeCatch();
    for (int week = 0; week < 12; week++) {
      var room = new TrainCakeRoomBatch(20001, own.getSource(), TrainCakeCatch.nextRefresh(now), own.getSpots());
      assertEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room, 10, now));
      now = room.refreshAt();
    }
    var room = new TrainCakeRoomBatch(20001, own.getSource(), TrainCakeCatch.nextRefresh(now), own.getSpots());
    assertNotEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room, 10, now));
    assertEquals(12, TrainCakeVisits.counts(s.actor).get(10));
    assertEquals(1, own.getCounts().get(10));
  }

  @Test void visitReceiptsSurviveAnActualPlayerDatabaseReload() throws Exception {
    var s = fixture.room(true); long now = TrainCakeCatch.now();
    assertEquals(Retcode.SUCC, TrainCakeCatch.open(s.actor, now).retcode());
    var own = s.actor.getGameplay().getTrainCakeCatch();
    assertEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room(own, 20001), 10, now));
    assertEquals(Retcode.SUCC, TrainCakeVisits.capture(s.actor, room(own, 20001), 21, now));
    var mongo = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "train-cake-visits");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var p = new emu.lunarcore.game.player.Player();
      var uid = p.getClass().getDeclaredField("uid"); uid.setAccessible(true); uid.setInt(p, 10001);
      var gameplay = p.getClass().getDeclaredField("gameplay"); gameplay.setAccessible(true);
      gameplay.set(p, fixture.gson.fromJson(fixture.gson.toJson(s.actor.getGameplay()), GameplayState.class));
      store.save(p);
      var restored = store.find(emu.lunarcore.game.player.Player.class).first();
      assertEquals(Map.of(10, 1, 21, 1), TrainCakeVisits.counts(restored));
      assertEquals(Map.of(20001, Set.of(10, 21)), TrainCakeVisits.records(restored, now));
      assertEquals(7, TrainCakeCatch.weeklyRemaining(restored, now));
    } finally { mongo.shutdownNow(); }
  }
}
