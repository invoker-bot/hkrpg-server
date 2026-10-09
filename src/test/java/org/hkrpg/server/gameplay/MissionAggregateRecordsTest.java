package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original 105450232 aggregation; predecessor acceptance and location use existing fixtures. */
class MissionAggregateRecordsTest {
  final MissionRelationsTest fixture = new MissionRelationsTest();
  static final int MAIN = 1054502, AGGREGATE = 105450232, FIRST = 105450231, SECOND = 105450234;

  @BeforeEach
  void setup() throws Exception {
    fixture.setup();
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
  }

  void done() {
    fixture.finish(FIRST, 1);
    fixture.finish(SECOND, 1);
  }

  void pending() {
    assertDoesNotThrow(() -> fixture.actor.getMissionManager().refresh());
    assertEquals(
        QuestState.Phase.DOING, fixture.actor.getMissionManager().sub(AGGREGATE).getPhase());
    assertEquals(0, fixture.actor.getMissionManager().sub(AGGREGATE).getProgress());
    assertEquals(
        0, fixture.actor.getMissionManager().sub(MissionRelationsTest.MONITOR).getProgress());
  }

  void edit(java.util.function.Consumer<JsonObject> change) {
    fixture.load(
        MAIN,
        full -> {
          for (var raw : full.getAsJsonArray("SubMissionList")) {
            var row = raw.getAsJsonObject();
            if (row.get("ID").getAsInt() == AGGREGATE) change.accept(row);
          }
        });
  }

  void corrupt(int id, java.util.function.Consumer<JsonObject> change) {
    var row = fixture.gson.toJsonTree(fixture.actor.getMissionManager().sub(id)).getAsJsonObject();
    change.accept(row);
    fixture
        .actor
        .getGameplay()
        .getSubMissions()
        .put(id, fixture.gson.fromJson(row, QuestState.class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void aDamagedCompletedChildCannotCompleteTheOriginalAggregate(String mode) {
    done();
    for (int id : List.of(FIRST, SECOND))
      corrupt(
          id,
          row -> {
            switch (mode) {
              case "null-phase" -> row.add("phase", JsonNull.INSTANCE);
              case "zero-time" -> row.addProperty("finishTime", 0);
              case "negative-time" -> row.addProperty("finishTime", -1);
              case "zero-progress" -> row.addProperty("progress", 0);
              case "negative-progress" -> row.addProperty("progress", -1);
              case "too-much-progress" -> row.addProperty("progress", 2);
              case "wrong-id" -> row.addProperty("questId", id + 1000000);
            }
          });
    pending();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "duplicate",
        "null-list",
        "null-member",
        "empty",
        "self",
        "unknown",
        "extra-int",
        "extra-string",
        "null-string",
        "extra-item",
        "null-item",
        "negative-goal",
        "beyond-list"
      })
  void malformedAggregateShapeRemainsPendingWithoutInventingCounts(String mode) {
    done();
    edit(
        row -> {
          switch (mode) {
            case "duplicate" ->
                row.add("ParamIntList", JsonParser.parseString("[105450231,105450231]"));
            case "null-list" -> row.add("ParamIntList", JsonNull.INSTANCE);
            case "null-member" -> row.add("ParamIntList", JsonParser.parseString("[null,null]"));
            case "empty" -> row.add("ParamIntList", new JsonArray());
            case "self" -> row.add("ParamIntList", JsonParser.parseString("[105450232,105450234]"));
            case "unknown" ->
                row.add("ParamIntList", JsonParser.parseString("[199999901,105450234]"));
            case "extra-int" -> row.addProperty("ParamInt1", 5);
            case "extra-string" -> row.addProperty("ParamStr1", "unverified");
            case "null-string" -> row.add("ParamStr1", JsonNull.INSTANCE);
            case "extra-item" ->
                row.add(
                    "ParamItemList", JsonParser.parseString("[{\"ItemID\":235,\"ItemNum\":1}]"));
            case "null-item" -> row.add("ParamItemList", JsonNull.INSTANCE);
            case "negative-goal" -> row.addProperty("Progress", -1);
            case "beyond-list" -> row.addProperty("Progress", 3);
          }
        });
    pending();
  }

  @Test
  void originalFinishedChildrenCompleteAggregateThenMonitorOnce() {
    done();
    fixture.completed();
    assertEquals(2, fixture.actor.getMissionManager().sub(AGGREGATE).getProgress());
    var before = fixture.gson.toJson(fixture.actor.getGameplay());
    int saves = fixture.actor.saves;
    fixture.completed();
    assertEquals(before, fixture.gson.toJson(fixture.actor.getGameplay()));
    assertEquals(saves, fixture.actor.saves);
  }

  @Test
  void aClaimingAndAClosedChildStillCountTheirActualCompletion() {
    done();
    assertTrue(fixture.actor.getMissionManager().sub(FIRST).reserveReward());
    var other = fixture.actor.getMissionManager().sub(SECOND);
    assertTrue(other.reserveReward());
    other.closeReward();
    fixture.completed();
    assertEquals(2, fixture.actor.getMissionManager().sub(AGGREGATE).getProgress());
  }

  @Test
  void missingCanonicalChildDefinitionDoesNotTrustJustItsSavedCompletion() {
    done();
    GameplayDefinitions.subs.remove(FIRST);
    pending();
  }

  @Test
  void cancelledChildDoesNotCountAndTheOtherValidChildCountsOnlyOnce() {
    done();
    fixture.actor.getGameplay().getCancelledSubs().add(FIRST);
    fixture.actor.getMissionManager().refresh();
    assertEquals(1, fixture.actor.getMissionManager().sub(AGGREGATE).getProgress());
    fixture.staysPending();
  }

  @ParameterizedTest
  @ValueSource(strings = {"DOING", "FINISHED", "CLAIMING", "CLOSED"})
  void anOriginalCrossMainReferenceUsesItsAcceptedOwner(String phase) throws Exception {
    try (var reader =
        new java.io.InputStreamReader(
            getClass().getResourceAsStream("/fixtures/mission-aggregate-4.6.json"),
            java.nio.charset.StandardCharsets.UTF_8)) {
      for (var raw : JsonParser.parseReader(reader).getAsJsonArray()) {
        var record = raw.getAsJsonObject();
        var row = record.getAsJsonObject("row");
        if (record.get("kind").getAsString().equals("main"))
          fixture.sourceMains.put(row.get("MainMissionID").getAsInt(), row);
        else fixture.sourceSubs.put(row.get("ID").getAsInt(), row);
      }
    }
    fixture.load(2011406, j -> {});
    fixture.load(2011401, j -> {});
    var parent = new QuestState(2011401);
    if (!phase.equals("DOING")) assertTrue(parent.advance(1, 1, 1));
    if (phase.equals("CLAIMING") || phase.equals("CLOSED")) assertTrue(parent.reserveReward());
    if (phase.equals("CLOSED")) parent.closeReward();
    fixture.actor.getGameplay().getMainMissions().put(2011401, parent);
    fixture.actor.getGameplay().getMainMissions().put(2011406, new QuestState(2011406));
    fixture.actor.getGameplay().getSubMissions().put(201140601, new QuestState(201140601));
    fixture.finish(201140108, 1);
    fixture.actor.getMissionManager().refresh();
    assertEquals(1, fixture.actor.getMissionManager().sub(201140601).getProgress());
  }

  @Test
  void actualPlayerDocumentRestoresPartialAndFinishedAggregationWithoutRepeatingActions()
      throws Exception {
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "aggregate_records");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, QuestState.class);
      fixture.finish(FIRST, 1);
      fixture.actor.getMissionManager().refresh();
      assertEquals(1, fixture.actor.getMissionManager().sub(AGGREGATE).getProgress());
      storePlayer(store);
      fixture.finish(SECOND, 1);
      fixture.completed();
      storePlayer(store);
      var before = fixture.gson.toJson(fixture.actor.getGameplay());
      int saves = fixture.actor.saves;
      fixture.completed();
      assertEquals(before, fixture.gson.toJson(fixture.actor.getGameplay()));
      assertEquals(saves, fixture.actor.saves);
    } finally {
      server.shutdownNow();
    }
  }

  void storePlayer(dev.morphia.Datastore store) throws Exception {
    var base = new emu.lunarcore.game.player.Player();
    var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(base, 123456790);
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(base, fixture.actor.getGameplay());
    store.save(base);
    var restored =
        store
            .find(emu.lunarcore.game.player.Player.class)
            .filter(dev.morphia.query.filters.Filters.eq("_id", 123456790))
            .first();
    assertNotNull(restored);
    var actor = new MissionRelationsTest.Actor();
    field.set(actor, restored.getGameplay());
    fixture.actor = actor;
  }

  @Test
  void concurrentRefreshesKeepOriginalAggregateAndMonitorActionsAtOneCompletion() throws Exception {
    done();
    var workers = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var calls = new ArrayList<java.util.concurrent.Callable<Void>>();
      for (int i = 0; i < 24; i++)
        calls.add(
            () -> {
              fixture.actor.getMissionManager().refresh();
              return null;
            });
      for (var result : workers.invokeAll(calls)) result.get();
      assertEquals(2, fixture.actor.getMissionManager().sub(AGGREGATE).getProgress());
      assertEquals(
          1, fixture.actor.getMissionManager().sub(MissionRelationsTest.MONITOR).getProgress());
      assertEquals(
          1,
          fixture
              .actor
              .getGameplay()
              .getStoryActions()
              .get(MissionRelationsTest.MONITOR)
              .getNext());
    } finally {
      workers.shutdownNow();
    }
  }
}
