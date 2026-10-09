package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Both known project pools use original effects; older saved runs keep their original pool. */
class ClockParkBalancedPool46Test {
  @TempDir Path root;
  final ClockParkCheckpoint46Test checkpoint = new ClockParkCheckpoint46Test();

  @BeforeEach
  void prepare() throws Exception {
    checkpoint.root = root;
    checkpoint.prepare();
  }

  @AfterEach
  void restore() throws Exception {
    checkpoint.restore();
  }

  ClockParkDice46Test fixture() {
    return checkpoint.fixture;
  }

  void resetToProjectDefault() throws Exception {
    var opening = fixture().opening;
    var raw =
        JsonParser.parseString(opening.gson.toJson(opening.player.getGameplay())).getAsJsonObject();
    raw.add("clockPark", opening.gson.toJsonTree(new ClockParkState()));
    opening.replaceGameplay(opening.gson.fromJson(raw, GameplayState.class));
  }

  List<Integer> cards() throws Exception {
    var dice =
        fixture()
            .nested(
                fixture().nested(fixture().nested(fixture().query(), "HGDLJDAFHLI"), "FDNLIPKGBLM"),
                "CENIOBBIJMF");
    return ((List<?>) fixture().value(dice, "LONFKFCFDND"))
        .stream().map(v -> fixture().number((DynamicMessage) v, "card_id")).toList();
  }

  ClockParkState.Run run() {
    return checkpoint.run();
  }

  String saved() {
    var opening = fixture().opening;
    return opening.gson.toJson(opening.player.getGameplay());
  }

  void load(JsonObject raw) throws Exception {
    var opening = fixture().opening;
    opening.replaceGameplay(opening.gson.fromJson(raw, GameplayState.class));
  }

  void place(int script, int face) throws Exception {
    fixture().overrideDice(face, face, face);
    fixture()
        .submit(
            fixture()
                .placement(
                    script,
                    run().getRound(),
                    new int[] {face, face, face},
                    new int[] {1001, 1007, 1008},
                    false,
                    false));
    assertEquals(0, fixture().opening.code());
  }

  void checkpoint(int face) throws Exception {
    resetToProjectDefault();
    fixture().begin(2);
    for (int i = 0; i < 4; i++) place(2, face);
    assertEquals("CHECKPOINT", run().getPhase());
  }

  void branch() throws Exception {
    checkpoint(6);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        new ClockParkManager(fixture().opening.player)
            .finishCheckpoint(2, run().getRound())
            .code());
    assertEquals("BRANCH", run().getPhase());
  }

  void nextStory() throws Exception {
    branch();
    var result =
        new ClockParkManager(fixture().opening.player)
            .selectBranch(2, run().getRound(), 2101, run().getDice().getFirst());
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, result.code());
    assertEquals("STORY", run().getPhase());
    assertEquals(202, run().getChapter());
  }

  @Test
  void newRunDefaultOffersOriginalCardsThatCanIncreaseAllThreeAttributes() throws Exception {
    resetToProjectDefault();
    fixture().begin(2);
    assertEquals(List.of(1001, 1007, 1008), cards());
  }

  @Test
  void originalBalancedEffectsActuallyAdvanceAttributesAndCanPassTheSecondScriptGate()
      throws Exception {
    resetToProjectDefault();
    fixture().begin(2);
    for (int i = 0; i < 4; i++) {
      fixture().overrideDice(6, 6, 6);
      fixture()
          .submit(
              fixture()
                  .placement(
                      2,
                      checkpoint.run().getRound(),
                      new int[] {6, 6, 6},
                      new int[] {1001, 1007, 1008},
                      false,
                      false));
      assertEquals(0, fixture().opening.code());
    }
    assertEquals("CHECKPOINT", checkpoint.run().getPhase());
    assertEquals(48, checkpoint.run().getAttributeA());
    assertEquals(48, checkpoint.run().getAttributeB());
    assertEquals(24, checkpoint.run().getAttributeC());
    assertTrue(checkpoint.run().getCheckpointPassed());
  }

  @Test
  void legacySavedDiceReceiptsContinueUsingTheirOriginalPoolWithoutRewritingThem()
      throws Exception {
    fixture().begin(1);
    fixture().overrideDice(6, 6, 6);
    fixture().submit(fixture().placement(1, checkpoint.run().getRound()));
    var opening = fixture().opening;
    var raw =
        JsonParser.parseString(opening.gson.toJson(opening.player.getGameplay())).getAsJsonObject();
    var state = raw.getAsJsonObject("clockPark");
    state.remove("nextCardPool");
    state.getAsJsonObject("active").remove("poolPolicy");
    opening.replaceGameplay(opening.gson.fromJson(raw, GameplayState.class));
    String before = opening.gson.toJson(opening.player.getGameplay());
    assertEquals(List.of(1001, 1002, 1003), cards());
    assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
    assertEquals(12, checkpoint.run().getAttributeA());
    assertEquals(6, checkpoint.run().getAttributeB());
    assertEquals(0, checkpoint.run().getAttributeC());
  }

  @ParameterizedTest
  @CsvSource({"1,2,3", "6,5,4", "2,2,5", "6,1,6", "3,5,1", "4,4,4"})
  void originalEffectsUseAssignedFacesEvenWhenWireRowsAreReordered(int a, int b, int c)
      throws Exception {
    resetToProjectDefault();
    fixture().begin(2);
    fixture().overrideDice(a, b, c);
    fixture()
        .submit(
            fixture()
                .placement(
                    2,
                    run().getRound(),
                    new int[] {c, a, b},
                    new int[] {1008, 1001, 1007},
                    false,
                    false));
    assertEquals(0, fixture().opening.code());
    assertEquals(a + b, run().getAttributeA());
    assertEquals(b + c, run().getAttributeB());
    assertEquals(c, run().getAttributeC());
    assertEquals(ClockParkCards.BALANCED_RULE, run().getPoolRule());
    assertTrue(
        fixture().opening.player.getGameplay().getClockPark().valid(ClockParkManager.catalog()));
  }

  @Test
  void legacyCardsAreNotAcceptedInTheBalancedWaitingMenu() throws Exception {
    resetToProjectDefault();
    fixture().begin(2);
    fixture().overrideDice(6, 6, 6);
    String before = saved();
    fixture().submit(fixture().placement(2, run().getRound()));
    assertEquals(5, fixture().opening.code());
    assertEquals(before, saved());
    assertEquals(List.of(1001, 1007, 1008), cards());
  }

  @Test
  void futurePoolChangeDoesNotRewriteActiveRunOrCrossChapterReceipts() throws Exception {
    nextStory();
    String transaction = run().getTransaction();
    var raw = JsonParser.parseString(saved()).getAsJsonObject();
    raw.getAsJsonObject("clockPark").addProperty("nextCardPool", ClockParkCards.POOL_RULE);
    load(raw);
    fixture().finishStory(2, run().getRound());
    assertEquals(0, fixture().opening.code());
    assertEquals(List.of(1001, 1007, 1008), cards());
    place(2, 6);
    assertEquals(60, run().getAttributeA());
    assertEquals(60, run().getAttributeB());
    assertEquals(30, run().getAttributeC());
    assertEquals(transaction, run().getTransaction());
    assertTrue(
        fixture().opening.player.getGameplay().getClockPark().valid(ClockParkManager.catalog()));
    var manager = new ClockParkManager(fixture().opening.player);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, manager.quit(2).code());
    fixture().begin(1);
    assertEquals(List.of(1001, 1002, 1003), cards());
    assertEquals(ClockParkCards.POOL_RULE, run().getPoolRule());
  }

  @Test
  void legacyStoryWithoutPolicyUsesLegacyEffectsAndOnlyTheNextNewRunUsesTheDefault()
      throws Exception {
    fixture().opening.unlock(1);
    fixture().opening.start(1);
    var raw = JsonParser.parseString(saved()).getAsJsonObject();
    raw.getAsJsonObject("clockPark").remove("nextCardPool");
    raw.getAsJsonObject("clockPark").getAsJsonObject("active").remove("poolPolicy");
    load(raw);
    fixture().finishStory(1, run().getRound());
    assertEquals(0, fixture().opening.code());
    assertEquals(List.of(1001, 1002, 1003), cards());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        new ClockParkManager(fixture().opening.player).quit(1).code());
    fixture().begin(1);
    assertEquals(List.of(1001, 1007, 1008), cards());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"STORY", "DICE", "CHECKPOINT", "BRANCH", "NEXT_STORY", "NEXT_DICE", "FAILURE"})
  void realPlayerMorphiaRoundTripPreservesVersionedReceiptsAtEachPhase(String phase)
      throws Exception {
    if (phase.equals("STORY")) {
      resetToProjectDefault();
      fixture().opening.unlock(2);
      fixture().opening.start(2);
    } else if (phase.equals("DICE")) {
      resetToProjectDefault();
      fixture().begin(2);
      place(2, 6);
    } else if (phase.equals("CHECKPOINT")) checkpoint(6);
    else if (phase.equals("BRANCH")) branch();
    else if (phase.equals("FAILURE")) {
      checkpoint(1);
      assertFalse(run().getCheckpointPassed());
      var result =
          new ClockParkManager(fixture().opening.player).finishCheckpoint(2, run().getRound());
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, result.code());
      assertTrue(result.failed());
      assertEquals(
          ClockParkCards.BALANCED_RULE,
          fixture().opening.player.getGameplay().getClockPark().getLastFailure().getPoolRule());
    } else {
      nextStory();
      if (phase.equals("NEXT_DICE")) {
        fixture().finishStory(2, run().getRound());
        place(2, 6);
      }
    }
    var expected = fixture().query();
    String before = saved();
    var opening = fixture().opening;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-balanced-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.set(document, 199985);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, opening.player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(before, opening.gson.toJson(restored.getGameplay()));
        opening.replaceGameplay(restored.getGameplay());
        assertEquals(expected, fixture().query());
        assertEquals(before, saved());
      }
    } finally {
      server.shutdownNow();
    }
    load(JsonParser.parseString(before).getAsJsonObject());
  }

  @Test
  void actualLegacyPlayerDocumentWithoutNewFieldsKeepsItsOriginalActivePool() throws Exception {
    fixture().begin(1);
    fixture().overrideDice(6, 6, 6);
    fixture().submit(fixture().placement(1, run().getRound()));
    var expected = fixture().query();
    var opening = fixture().opening;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-legacy-pool-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.set(document, 199984);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, opening.player.getGameplay());
      store.save(document);
      var collection = store.getDatabase().getCollection("players");
      assertEquals(
          1,
          collection
              .updateOne(
                  new org.bson.Document("_id", 199984),
                  new org.bson.Document(
                      "$unset",
                      new org.bson.Document("gameplay.clockPark.nextCardPool", "")
                          .append("gameplay.clockPark.active.poolPolicy", "")))
              .getModifiedCount());
      var before = collection.find(new org.bson.Document("_id", 199984)).first();
      for (int i = 0; i < 2; i++) {
        var restored =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        opening.replaceGameplay(restored.getGameplay());
        assertEquals(expected, fixture().query());
        assertEquals(ClockParkCards.POOL_RULE, run().getPoolRule());
        assertEquals(before, collection.find(new org.bson.Document("_id", 199984)).first());
      }
    } finally {
      server.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknown-next",
        "null-next",
        "unknown-active",
        "pool-mismatch",
        "replayed-other-pool"
      })
  void unknownOrMismatchedSavedRulesAreRejectedWithoutQueryRepair(String kind) throws Exception {
    resetToProjectDefault();
    fixture().begin(2);
    place(2, 6);
    var raw = JsonParser.parseString(saved()).getAsJsonObject();
    var state = raw.getAsJsonObject("clockPark");
    var active = state.getAsJsonObject("active");
    switch (kind) {
      case "unknown-next" -> state.addProperty("nextCardPool", "unverified.v99");
      case "null-next" -> state.add("nextCardPool", JsonNull.INSTANCE);
      case "unknown-active" -> active.addProperty("poolPolicy", "unverified.v99");
      case "pool-mismatch" -> active.addProperty("cardPool", ClockParkCards.POOL_RULE);
      case "replayed-other-pool" -> {
        active.addProperty("poolPolicy", ClockParkCards.POOL_RULE);
        active.addProperty("cardPool", ClockParkCards.POOL_RULE);
      }
    }
    load(raw);
    String before = saved();
    fixture().opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR.getVal(), fixture().opening.code());
    assertEquals(before, saved());
  }

  @Test
  void sixConcurrentBalancedPlacementsCommitOneOriginalEffectReceipt() throws Exception {
    resetToProjectDefault();
    fixture().begin(2);
    fixture().overrideDice(6, 6, 6);
    int round = run().getRound();
    var manager = new ClockParkManager(fixture().opening.player);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<java.util.concurrent.Callable<ClockParkManager.Result>>();
      for (int i = 0; i < 6; i++) calls.add(() -> manager.placeDice(2, round, List.of(6, 6, 6)));
      int successes = 0;
      for (var result : pool.invokeAll(calls)) {
        var value = result.get();
        if (value.code() == emu.lunarcore.server.packet.Retcode.SUCC) successes++;
        else assertEquals(emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID, value.code());
      }
      assertEquals(1, successes);
    }
    assertEquals(12, run().getAttributeA());
    assertEquals(12, run().getAttributeB());
    assertEquals(6, run().getAttributeC());
    assertEquals(1, run().getReceipts().size());
  }

  @Test
  void failedBalancedPlacementSaveRestoresAllAttributesAndVersionedDice() throws Exception {
    resetToProjectDefault();
    fixture().begin(2);
    fixture().overrideDice(6, 6, 6);
    var request =
        fixture()
            .placement(
                2,
                run().getRound(),
                new int[] {6, 6, 6},
                new int[] {1001, 1007, 1008},
                false,
                false);
    String before = saved();
    fixture().opening.failSave = true;
    fixture().submit(request);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(), fixture().opening.code());
    assertEquals(before, saved());
    fixture().opening.failSave = false;
    fixture().submit(request);
    assertEquals(0, fixture().opening.code());
    assertEquals(12, run().getAttributeB());
    assertEquals(1, run().getReceipts().size());
  }
}
