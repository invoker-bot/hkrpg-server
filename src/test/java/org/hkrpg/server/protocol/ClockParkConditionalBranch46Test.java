package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original 2102 actions, selected by the one face actually owned by the run. */
class ClockParkConditionalBranch46Test {
  @TempDir Path root;
  final ClockParkBalancedPool46Test balanced = new ClockParkBalancedPool46Test();

  @BeforeEach
  void prepare() throws Exception {
    balanced.root = root;
    balanced.prepare();
  }

  @AfterEach
  void restore() throws Exception {
    balanced.restore();
  }

  ClockParkState.Run run() {
    return balanced.run();
  }

  void secondCheckpoint() throws Exception {
    balanced.nextStory();
    balanced.fixture().finishStory(2, run().getRound());
    assertEquals(0, balanced.fixture().opening.code());
    for (int i = 0; i < 4; i++) balanced.place(2, 6);
    assertEquals("CHECKPOINT", run().getPhase());
    assertEquals(221, run().getCheckpoint());
    assertTrue(run().getCheckpointPassed());
  }

  void offer(int face) throws Exception {
    secondCheckpoint();
    balanced
        .fixture()
        .submit(
            balanced
                .fixture()
                .operation(
                    2,
                    run().getRound(),
                    "MLHNHAFPLKD",
                    balanced.fixture().request("MOEMKBLCKKO").build()));
    assertEquals(0, balanced.fixture().opening.code());
    assertEquals("BRANCH", run().getPhase());
    var raw = JsonParser.parseString(balanced.saved()).getAsJsonObject();
    raw.getAsJsonObject("clockPark")
        .getAsJsonObject("active")
        .add("dice", balanced.fixture().opening.gson.toJsonTree(List.of(face)));
    balanced.load(raw);
  }

  @ParameterizedTest
  @CsvSource({"1,204", "2,204", "3,203", "4,203", "5,203", "6,203"})
  void originalConditionalCardUsesTheOwnedFaceAndEntersTheMatchingStory(int face, int target)
      throws Exception {
    offer(face);
    String transaction = run().getTransaction();
    int a = run().getAttributeA(), b = run().getAttributeB(), c = run().getAttributeC();
    var request =
        balanced
            .fixture()
            .placement(2, run().getRound(), new int[] {face}, new int[] {2102}, false, false);
    balanced.fixture().submit(request);
    assertEquals(0, balanced.fixture().opening.code());
    assertEquals("STORY", run().getPhase());
    assertEquals(target, run().getChapter());
    assertEquals(target * 100 + 1, run().getStory());
    assertEquals(transaction, run().getTransaction());
    assertEquals(a, run().getAttributeA());
    assertEquals(b, run().getAttributeB());
    assertEquals(c, run().getAttributeC());
    assertEquals(List.of(201, 202, target), run().chapterPath());
    assertTrue(
        balanced
            .fixture()
            .opening
            .player
            .getGameplay()
            .getClockPark()
            .valid(ClockParkManager.catalog()));
    String before = balanced.saved();
    balanced.fixture().submit(request);
    assertEquals(5, balanced.fixture().opening.code());
    assertEquals(before, balanced.saved());
  }

  void select(int face) throws Exception {
    balanced
        .fixture()
        .submit(
            balanced
                .fixture()
                .placement(2, run().getRound(), new int[] {face}, new int[] {2102}, false, false));
    assertEquals(0, balanced.fixture().opening.code());
  }

  @ParameterizedTest
  @CsvSource({"1,204,206,2104", "6,203,205,2103"})
  void thirdChapterOriginalGateAndCardReachTheAuthoredEndingStoryWithoutCreditingIt(
      int face, int chapter, int ending, int card) throws Exception {
    offer(face);
    select(face);
    assertEquals(chapter, run().getChapter());
    balanced.fixture().finishStory(2, run().getRound());
    for (int i = 0; i < 5; i++) balanced.place(2, 6);
    assertEquals(chapter == 203 ? 231 : 241, run().getCheckpoint());
    assertTrue(run().getCheckpointPassed());
    var manager = new ClockParkManager(balanced.fixture().opening.player);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager.finishCheckpoint(2, run().getRound()).code());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager.selectBranch(2, run().getRound(), card, run().getDice().getFirst()).code());
    assertEquals("STORY", run().getPhase());
    assertEquals(ending, run().getChapter());
    assertEquals(ending * 100 + 1, run().getStory());
    assertTrue(
        balanced
            .fixture()
            .opening
            .player
            .getGameplay()
            .getClockPark()
            .completedEndings()
            .isEmpty());
    assertEquals(
        0,
        balanced
            .fixture()
            .opening
            .player
            .getGameplay()
            .getClockPark()
            .progress(ClockParkManager.catalog()));
    String before = balanced.saved();
    balanced.fixture().finishStory(2, 0);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.REWARD_CONFIG_NOT_EXIST.getVal(),
        balanced.fixture().opening.code());
    assertEquals(before, balanced.saved());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unowned-face",
        "foreign-card",
        "foreign-script",
        "foreign-round",
        "normal-pool",
        "duplicated-row",
        "forged-value"
      })
  void malformedConditionalSelectionDoesNotChangeTheOwnedMenuOrReachedProofs(String kind)
      throws Exception {
    offer(2);
    var req =
        balanced
            .fixture()
            .placement(2, run().getRound(), new int[] {2}, new int[] {2102}, false, false);
    switch (kind) {
      case "unowned-face" ->
          req =
              balanced
                  .fixture()
                  .placement(2, run().getRound(), new int[] {3}, new int[] {2102}, false, false);
      case "foreign-card" ->
          req =
              balanced
                  .fixture()
                  .placement(2, run().getRound(), new int[] {2}, new int[] {3102}, false, false);
      case "foreign-script" ->
          req =
              balanced
                  .fixture()
                  .placement(3, run().getRound(), new int[] {2}, new int[] {2102}, false, false);
      case "foreign-round" ->
          req =
              balanced
                  .fixture()
                  .placement(
                      2, run().getRound() + 1, new int[] {2}, new int[] {2102}, false, false);
      case "normal-pool" -> req = balanced.fixture().placement(2, run().getRound());
      case "duplicated-row" ->
          req =
              balanced
                  .fixture()
                  .placement(
                      2, run().getRound(), new int[] {2, 2}, new int[] {2102, 2102}, false, false);
      case "forged-value" ->
          req =
              balanced
                  .fixture()
                  .placement(2, run().getRound(), new int[] {2}, new int[] {2102}, true, false);
    }
    String before = balanced.saved();
    balanced.fixture().submit(req);
    assertEquals(5, balanced.fixture().opening.code());
    assertEquals(before, balanced.saved());
  }

  @ParameterizedTest
  @ValueSource(strings = {"confirmation", "selection"})
  void conditionalSaveFailureRollsBackTheEntireMenuOrTransitionBeforeRetry(String phase)
      throws Exception {
    if (phase.equals("confirmation")) secondCheckpoint();
    else offer(2);
    var req =
        phase.equals("confirmation")
            ? balanced
                .fixture()
                .operation(
                    2,
                    run().getRound(),
                    "MLHNHAFPLKD",
                    balanced.fixture().request("MOEMKBLCKKO").build())
            : balanced
                .fixture()
                .placement(2, run().getRound(), new int[] {2}, new int[] {2102}, false, false);
    String before = balanced.saved();
    balanced.fixture().opening.failSave = true;
    balanced.fixture().submit(req);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(),
        balanced.fixture().opening.code());
    assertEquals(before, balanced.saved());
    balanced.fixture().opening.failSave = false;
    balanced.fixture().submit(req);
    assertEquals(0, balanced.fixture().opening.code());
    assertEquals(phase.equals("confirmation") ? "BRANCH" : "STORY", run().getPhase());
  }

  @ParameterizedTest
  @CsvSource({
    "1,BRANCH",
    "6,BRANCH",
    "1,STORY",
    "6,STORY",
    "1,DICE",
    "6,DICE",
    "1,ENDING",
    "6,ENDING"
  })
  void actualPlayerMorphiaReloadPreservesConditionalFacesAndThreeChapterHistory(
      int face, String phase) throws Exception {
    offer(face);
    if (!phase.equals("BRANCH")) select(face);
    if (phase.equals("DICE") || phase.equals("ENDING")) {
      balanced.fixture().finishStory(2, run().getRound());
      balanced.place(2, 6);
    }
    if (phase.equals("ENDING")) {
      for (int i = 1; i < 5; i++) balanced.place(2, 6);
      var manager = new ClockParkManager(balanced.fixture().opening.player);
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SUCC,
          manager.finishCheckpoint(2, run().getRound()).code());
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SUCC,
          manager
              .selectBranch(
                  2, run().getRound(), face == 1 ? 2104 : 2103, run().getDice().getFirst())
              .code());
    }
    var expected = balanced.fixture().query();
    String before = balanced.saved();
    var opening = balanced.fixture().opening;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-conditional-fixture");
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
      uid.set(document, 199983);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, opening.player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored =
            java.util.Objects.requireNonNull(
                store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(before, opening.gson.toJson(restored.getGameplay()));
        opening.replaceGameplay(restored.getGameplay());
        assertEquals(expected, balanced.fixture().query());
        assertEquals(before, balanced.saved());
      }
    } finally {
      server.shutdownNow();
    }
    balanced.load(JsonParser.parseString(before).getAsJsonObject());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"wrong-face", "foreign-card", "unknown-rule", "position", "attribute", "target"})
  void corruptedConditionalTransitionProofCannotBeRepairedByQuery(String kind) throws Exception {
    offer(1);
    select(1);
    var raw = JsonParser.parseString(balanced.saved()).getAsJsonObject();
    var active = raw.getAsJsonObject("clockPark").getAsJsonObject("active");
    var previous = active.getAsJsonArray("history").get(1).getAsJsonObject();
    switch (kind) {
      case "wrong-face" -> {
        previous.addProperty("face", 6);
        active.addProperty("transitionFace", 6);
      }
      case "foreign-card" -> {
        previous.addProperty("card", 3102);
        active.addProperty("transitionCard", 3102);
      }
      case "unknown-rule" -> previous.addProperty("branchRule", "unknown.v9");
      case "position" -> previous.addProperty("round", 20251);
      case "attribute" -> previous.addProperty("attributeB", 95);
      case "target" -> active.addProperty("chapter", 203);
    }
    balanced.load(raw);
    String before = balanced.saved();
    balanced.fixture().opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR.getVal(),
        balanced.fixture().opening.code());
    assertEquals(before, balanced.saved());
  }

  @Test
  void sixConcurrentSelectionsConsumeOneOwnedFaceAndSaveOneTargetProof() throws Exception {
    offer(1);
    int round = run().getRound();
    var manager = new ClockParkManager(balanced.fixture().opening.player);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new java.util.ArrayList<java.util.concurrent.Callable<ClockParkManager.Result>>();
      for (int i = 0; i < 6; i++) calls.add(() -> manager.selectBranch(2, round, 2102, 1));
      int successes = 0;
      for (var value : pool.invokeAll(calls)) {
        var result = value.get();
        if (result.code() == emu.lunarcore.server.packet.Retcode.SUCC) successes++;
        else assertEquals(emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID, result.code());
      }
      assertEquals(1, successes);
    }
    assertEquals(204, run().getChapter());
    assertEquals(2, run().getHistory().size());
    assertEquals(
        List.of(201, 202, 204),
        balanced.fixture().opening.player.getGameplay().getClockPark().reached(2));
  }
}
