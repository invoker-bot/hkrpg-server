package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import org.hkrpg.server.gameplay.ClockParkManager;
import org.hkrpg.server.gameplay.ClockParkState;
import org.hkrpg.server.gameplay.GameplayState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A passed original checkpoint must offer its owned ToChapter card, without auto-completing a
 * chapter.
 */
class ClockParkBranch46Test {
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

  ClockParkState.Run run() {
    return checkpoint.run();
  }

  void confirm(int script) throws Exception {
    fixture()
        .submit(
            fixture()
                .operation(
                    script,
                    run().getRound(),
                    "MLHNHAFPLKD",
                    fixture().request("MOEMKBLCKKO").build()));
  }

  void branch(int script) throws Exception {
    checkpoint.advanceToLast(script, 6);
    checkpoint.finish(script);
    confirm(script);
    assertEquals(0, fixture().opening.code());
  }

  DynamicMessage selection(int script, int card, int face) {
    return fixture()
        .placement(script, run().getRound(), new int[] {face}, new int[] {card}, false, false);
  }

  DynamicMessage selection(int script) {
    return selection(script, script == 1 ? 1101 : 3101, run().getDice().getFirst());
  }

  String saved() {
    return fixture().opening.gson.toJson(fixture().opening.player.getGameplay());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3})
  void ownedBranchEntersOriginalNextStoryAndCarriesAttributesBeforeNormalDice(int script)
      throws Exception {
    branch(script);
    int a = run().getAttributeA(), b = run().getAttributeB();
    String transaction = run().getTransaction();
    var request = selection(script);
    fixture().submit(request);
    assertEquals(0, fixture().opening.code());
    assertEquals("STORY", run().getPhase());
    int target = script == 1 ? 102 : 302;
    assertEquals(target, run().getChapter());
    assertEquals(target * 100 + 1, run().getRound());
    assertEquals(target * 100 + 1, run().getStory());
    assertEquals(0, run().getRoundIndex());
    assertEquals(transaction, run().getTransaction());
    assertEquals(a, run().getAttributeA());
    assertEquals(b, run().getAttributeB());
    assertEquals(
        java.util.List.of(target - 1, target), fixture().value(fixture().query(), "FKNBOIIMFLD"));
    assertEquals(
        java.util.List.of(target - 1, target),
        fixture().opening.player.getGameplay().getClockPark().reached(script));
    String before = saved();
    fixture().submit(request);
    assertEquals(5, fixture().opening.code());
    assertEquals(before, saved());
    fixture().finishStory(script, run().getRound());
    assertEquals(0, fixture().opening.code());
    assertEquals("DICE", run().getPhase());
    assertEquals(3, run().getDice().size());
    fixture().overrideDice(1, 2, 3);
    fixture().submit(fixture().placement(script, run().getRound()));
    assertEquals(0, fixture().opening.code());
    assertEquals(target * 100 + 2, run().getRound());
    assertEquals(Math.min(script == 1 ? 50 : 100, a + 4), run().getAttributeA());
    assertEquals(b + 2, run().getAttributeB());
    assertEquals(1, run().getRoundIndex());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "foreign-card",
        "foreign-script",
        "foreign-round",
        "unowned-face",
        "normal-pool",
        "duplicate-row",
        "forged-value",
        "free-face",
        "empty-slots",
        "story-confirm"
      })
  void invalidBranchRequestsPreserveTheSavedMenuAndReachedProgress(String kind) throws Exception {
    branch(1);
    var request = selection(1);
    int face = run().getDice().getFirst();
    switch (kind) {
      case "foreign-card" -> request = selection(1, 1102, face);
      case "foreign-script" -> request = selection(3, 1101, face);
      case "foreign-round" ->
          request = fixture().placement(1, 10108, new int[] {face}, new int[] {1101}, false, false);
      case "unowned-face" -> request = selection(1, 1101, face % 6 + 1);
      case "normal-pool" -> request = fixture().placement(1, run().getRound());
      case "duplicate-row" ->
          request =
              fixture()
                  .placement(
                      1,
                      run().getRound(),
                      new int[] {face, face},
                      new int[] {1101, 1101},
                      false,
                      false);
      case "forged-value" ->
          request =
              fixture()
                  .placement(1, run().getRound(), new int[] {face}, new int[] {1101}, true, false);
      case "free-face" ->
          request =
              fixture()
                  .placement(1, run().getRound(), new int[] {face}, new int[] {1101}, false, true);
      case "empty-slots" ->
          request =
              fixture()
                  .placement(1, run().getRound(), new int[] {}, new int[] {1101}, false, false);
      case "story-confirm" ->
          request =
              fixture()
                  .operation(
                      1, run().getRound(), "IKNEKEJEGLM", fixture().request("NKOCCGLHOML").build());
    }
    String before = saved();
    fixture().submit(request);
    assertEquals(5, fixture().opening.code());
    assertEquals(before, saved());
  }

  @ParameterizedTest
  @ValueSource(strings = {"confirmation", "selection"})
  void failedSaveRollsBackTheWholeTransitionAndAllowsOneRetry(String stage) throws Exception {
    checkpoint.advanceToLast(1, 6);
    checkpoint.finish(1);
    if (stage.equals("selection")) confirm(1);
    var request =
        stage.equals("selection")
            ? selection(1)
            : fixture()
                .operation(
                    1, run().getRound(), "MLHNHAFPLKD", fixture().request("MOEMKBLCKKO").build());
    String before = saved();
    fixture().opening.failSave = true;
    fixture().submit(request);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture().opening.code());
    assertEquals(before, saved());
    fixture().opening.failSave = false;
    fixture().submit(request);
    assertEquals(0, fixture().opening.code());
    assertEquals(stage.equals("selection") ? "STORY" : "BRANCH", run().getPhase());
  }

  @ParameterizedTest
  @CsvSource({"1,BRANCH", "3,BRANCH", "1,STORY", "3,STORY", "1,DICE", "3,DICE"})
  void actualPlayerMorphiaReloadPreservesBranchAndNextChapterProofs(int script, String phase)
      throws Exception {
    branch(script);
    if (!phase.equals("BRANCH")) {
      fixture().submit(selection(script));
      assertEquals(0, fixture().opening.code());
    }
    if (phase.equals("DICE")) fixture().finishStory(script, run().getRound());
    var expected = fixture().query();
    String before = saved();
    var opening = fixture().opening;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-branch-fixture");
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
      uid.set(document, 199988);
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
        assertEquals(expected, fixture().query());
        assertEquals(before, saved());
      }
    } finally {
      server.shutdownNow();
    }
    opening.replaceGameplay(opening.gson.fromJson(before, GameplayState.class));
    assertEquals(expected, fixture().query());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "previous-source",
        "previous-checkpoint",
        "edge-card",
        "edge-face",
        "missing-origin",
        "missing-proof",
        "false-reached",
        "proof-phase",
        "proof-attributes"
      })
  void corruptTransitionOrReachedProofCannotBeRepairedByAQuery(String kind) throws Exception {
    branch(1);
    fixture().submit(selection(1));
    assertEquals(0, fixture().opening.code());
    var opening = fixture().opening;
    var json = JsonParser.parseString(saved()).getAsJsonObject();
    var state = json.getAsJsonObject("clockPark");
    var active = state.getAsJsonObject("active");
    var proof = state.getAsJsonObject("reachedProofs").getAsJsonObject("1").getAsJsonObject("102");
    switch (kind) {
      case "previous-source" ->
          active
              .getAsJsonArray("history")
              .get(0)
              .getAsJsonObject()
              .addProperty("source", "foreign");
      case "previous-checkpoint" ->
          active
              .getAsJsonArray("history")
              .get(0)
              .getAsJsonObject()
              .addProperty("checkpointPassed", false);
      case "edge-card" -> active.addProperty("transitionCard", 1102);
      case "edge-face" -> active.addProperty("transitionFace", run().getTransitionFace() % 6 + 1);
      case "missing-origin" -> active.remove("history");
      case "missing-proof" ->
          state.getAsJsonObject("reachedProofs").getAsJsonObject("1").remove("102");
      case "false-reached" -> state.getAsJsonObject("reached").getAsJsonArray("1").add(103);
      case "proof-phase" -> proof.addProperty("phase", "DICE");
      case "proof-attributes" -> proof.addProperty("attributeA", 49);
    }
    opening.replaceGameplay(opening.gson.fromJson(json, GameplayState.class));
    String before = saved();
    opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), opening.code());
    assertEquals(before, saved());
  }

  @Test
  void reachedProofSurvivesQuitAndStartingAnotherRunWithoutInventingAnEnding() throws Exception {
    branch(1);
    fixture().submit(selection(1));
    int round = run().getRound();
    var manager = new ClockParkManager(fixture().opening.player);
    assertEquals(Retcode.SUCC, manager.quit(1).code());
    var state = fixture().opening.player.getGameplay().getClockPark();
    assertEquals(java.util.List.of(101, 102), state.reached(1));
    assertTrue(state.valid(ClockParkManager.catalog()));
    assertEquals(Retcode.SUCC, manager.start(1).code());
    assertEquals(101, run().getChapter());
    assertEquals(0, run().getAttributeA());
    assertNotEquals(round, run().getRound());
    assertEquals(java.util.List.of(101, 102), state.reached(1));
    assertEquals(java.util.List.of(101), run().chapterPath());
  }

  @ParameterizedTest
  @ValueSource(strings = {"confirmation", "selection"})
  void concurrentRequestsProduceExactlyOneSavedTransition(String stage) throws Exception {
    checkpoint.advanceToLast(1, 6);
    checkpoint.finish(1);
    if (stage.equals("selection")) confirm(1);
    var manager = new ClockParkManager(fixture().opening.player);
    int round = run().getRound();
    int face = stage.equals("selection") ? run().getDice().getFirst() : 0;
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new java.util.ArrayList<java.util.concurrent.Callable<ClockParkManager.Result>>();
      for (int i = 0; i < 6; i++)
        calls.add(
            () ->
                stage.equals("selection")
                    ? manager.selectBranch(1, round, 1101, face)
                    : manager.finishCheckpoint(1, round));
      int success = 0;
      for (var result : pool.invokeAll(calls)) {
        if (result.get().code() == Retcode.SUCC) success++;
        else assertEquals(Retcode.REQ_PARA_INVALID, result.get().code());
      }
      assertEquals(1, success);
    }
    assertEquals(stage.equals("selection") ? "STORY" : "BRANCH", run().getPhase());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3})
  void passedCheckpointConfirmationOffersTheOriginalNextChapterCard(int script) throws Exception {
    checkpoint.advanceToLast(script, 6);
    checkpoint.finish(script);
    assertTrue(run().getCheckpointPassed());
    String transaction = run().getTransaction();
    confirm(script);
    assertEquals(0, fixture().opening.code());
    assertEquals("BRANCH", run().getPhase());
    assertEquals(script == 1 ? 10107 : 30150, run().getRound());
    assertEquals(transaction, run().getTransaction());
    var query = fixture().query();
    var wait = fixture().nested(query, "HGDLJDAFHLI");
    var branch = fixture().nested(wait, "ABIPNABIDPE");
    var cards = fixture().nested(branch, "CENIOBBIJMF");
    var faces = (java.util.List<?>) fixture().value(cards, "KCNEKEGGAGG");
    assertEquals(1, faces.size());
    assertTrue((Integer) faces.getFirst() >= 1 && (Integer) faces.getFirst() <= 6);
    var rows = (java.util.List<?>) fixture().value(cards, "LONFKFCFDND");
    assertEquals(1, rows.size());
    assertEquals(
        script == 1 ? 1101 : 3101, fixture().number((DynamicMessage) rows.getFirst(), "card_id"));
    assertEquals(
        java.util.List.of(script == 1 ? 101 : 301),
        fixture().opening.player.getGameplay().getClockPark().reached(script));
  }

  @Test
  void failedOriginalCheckpointDoesNotOfferOrEnterAnUnattainedNextChapter() throws Exception {
    checkpoint.advanceToLast(2, 6);
    checkpoint.finish(2);
    assertFalse(run().getCheckpointPassed());
    String before = fixture().opening.gson.toJson(fixture().opening.player.getGameplay());
    var result =
        new ClockParkManager(fixture().opening.player).finishCheckpoint(2, run().getRound());
    assertEquals(Retcode.SUCC, result.code());
    assertTrue(result.failed());
    assertNull(run());
    assertNotEquals(before, fixture().opening.gson.toJson(fixture().opening.player.getGameplay()));
    assertEquals(
        java.util.List.of(201), fixture().opening.player.getGameplay().getClockPark().reached(2));
    assertTrue(fixture().opening.player.getGameplay().getClockPark().completedEndings().isEmpty());
  }

  @Test
  void confirmationInNormalDicePhaseDoesNotConsumeOrRerollOwnedDice() throws Exception {
    fixture().begin(1);
    String before = fixture().opening.gson.toJson(fixture().opening.player.getGameplay());
    confirm(1);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), fixture().opening.code());
    assertEquals(before, fixture().opening.gson.toJson(fixture().opening.player.getGameplay()));
  }
}
