package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClockParkRankedGames46Test {
  @TempDir Path root;
  final ClockParkMiniGames46Test mini = new ClockParkMiniGames46Test();

  @BeforeEach
  void prepare() throws Exception {
    mini.root = root;
    mini.prepare();
  }

  @AfterEach
  void restore() throws Exception {
    mini.restore();
  }

  @ParameterizedTest
  @CsvSource({"302,1", "302,2", "302,3", "410,1", "410,2", "410,3", "420,1", "420,2", "420,3"})
  void legalPositiveNativeGradesSettleExactlyOnceWithProjectPolicy(int chapter, int grade)
      throws Exception {
    mini.enter(chapter);
    var before = mini.run();
    int a = before.getAttributeA(), b = before.getAttributeB(), c = before.getAttributeC();
    var request =
        mini.report(before.getScript(), before.getRound(), before.getPhase(), true, grade);
    mini.fixture().submit(request);
    assertEquals(0, mini.fixture().opening.code());
    assertEquals("CHECKPOINT", mini.run().getPhase());
    assertEquals(
        chapter == 302 ? Math.min(100, a + grade * 2) : Math.min(60, a + grade),
        mini.run().getAttributeA());
    assertEquals(chapter == 302 ? b : Math.min(60, b + grade), mini.run().getAttributeB());
    assertEquals(chapter == 302 ? c : Math.min(60, c + grade), mini.run().getAttributeC());
    assertEquals(grade, mini.mini().get("score").getAsInt());
    assertEquals(
        "hkrpg.clockpark.project-cumulative-mini-grade.v2", mini.mini().get("rule").getAsString());
    assertTrue(mini.mini().get("completed").getAsBoolean());
    assertTrue(mini.mini().get("win").getAsBoolean());
    String settled = mini.saved();
    int saves = mini.fixture().opening.saves;
    mini.fixture().submit(request);
    assertEquals(5, mini.fixture().opening.code());
    assertEquals(settled, mini.saved());
    assertEquals(saves, mini.fixture().opening.saves);
    assertEquals(
        0,
        mini.fixture()
            .opening
            .player
            .getGameplay()
            .getClockPark()
            .progress(org.hkrpg.server.gameplay.ClockParkManager.catalog()));
  }

  void normals(int count, int face) throws Exception {
    for (int i = 0; i < count; i++) mini.balanced.place(mini.run().getScript(), face);
  }

  void uncapped(int chapter) throws Exception {
    mini.balanced.resetToProjectDefault();
    mini.fixture().begin(chapter == 302 ? 3 : 4);
    if (chapter == 302) {
      normals(4, 4);
      mini.checkpoint();
      mini.branch(3101);
      normals(4, 3);
    } else if (chapter == 410) normals(8, 3);
    else {
      normals(8, 4);
      mini.finish(false, 0);
      assertEquals(0, mini.fixture().opening.code());
      mini.checkpoint();
      mini.branch(4122);
      normals(8, 1);
    }
  }

  void legacy() throws Exception {
    var raw = mini.raw();
    var proof = raw.getAsJsonObject("clockPark").getAsJsonObject("active").getAsJsonObject("mini");
    proof.addProperty("rule", ClockParkMiniGames.LEGACY_RULE);
    proof.addProperty(
        "source", ClockParkMiniGames.load(root).fingerprint(ClockParkMiniGames.LEGACY_RULE));
    mini.balanced.load(raw);
  }

  @ParameterizedTest
  @CsvSource({"302,1", "302,2", "302,3", "410,1", "410,2", "410,3", "420,1", "420,2", "420,3"})
  void originalCheckpointUsesNewGradeIncrementAndOriginalAttributeCaps(int chapter, int grade)
      throws Exception {
    uncapped(chapter);
    int a = mini.run().getAttributeA(),
        b = mini.run().getAttributeB(),
        c = mini.run().getAttributeC();
    if (chapter == 410) assertEquals(24, c);
    mini.finish(true, grade);
    assertEquals(0, mini.fixture().opening.code());
    assertEquals(
        chapter == 302 ? Math.min(100, a + 2 * grade) : Math.min(60, a + grade),
        mini.run().getAttributeA());
    assertEquals(chapter == 302 ? b : Math.min(60, b + grade), mini.run().getAttributeB());
    assertEquals(chapter == 302 ? c : Math.min(60, c + grade), mini.run().getAttributeC());
    var checks = ClockParkChecks.load(root);
    assertEquals(
        checks.passed(
            mini.run().getCheckpoint(),
            mini.run().getAttributeA(),
            mini.run().getAttributeB(),
            mini.run().getAttributeC()),
        mini.run().getCheckpointPassed());
    if (chapter == 410)
      assertTrue(
          mini.run().getCheckpointPassed(),
          "Positive movie grade must rescue the actual C=24 checkpoint");
    String saved = mini.saved();
    mini.fixture().query();
    assertEquals(saved, mini.saved());
  }

  @ParameterizedTest
  @CsvSource({"302,1", "302,2", "302,3", "410,1", "410,2", "410,3", "420,1", "420,2", "420,3"})
  void resultSaveFailureKeepsWaitingPolicyFacesAndAttributesAndRetriesOnce(int chapter, int grade)
      throws Exception {
    uncapped(chapter);
    String before = mini.saved();
    mini.fixture().opening.failSave = true;
    mini.finish(true, grade);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(), mini.fixture().opening.code());
    assertEquals(before, mini.saved());
    mini.fixture().opening.failSave = false;
    mini.finish(true, grade);
    assertEquals(0, mini.fixture().opening.code());
    assertEquals(grade, mini.run().getMini().getScore());
    String after = mini.saved();
    mini.finish(true, grade);
    assertEquals(5, mini.fixture().opening.code());
    assertEquals(after, mini.saved());
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 410, 420})
  void oldZeroGradePolicyRetainsSourceAndDoesNotGainAttributesOrRewriteOnRead(int chapter)
      throws Exception {
    mini.enter(chapter);
    legacy();
    String before = mini.saved();
    int saves = mini.fixture().opening.saves;
    mini.fixture().query();
    assertEquals(before, mini.saved());
    assertEquals(saves, mini.fixture().opening.saves);
    int a = mini.run().getAttributeA(),
        b = mini.run().getAttributeB(),
        c = mini.run().getAttributeC();
    mini.finish(false, 0);
    assertEquals(0, mini.fixture().opening.code());
    assertEquals(a, mini.run().getAttributeA());
    assertEquals(b, mini.run().getAttributeB());
    assertEquals(c, mini.run().getAttributeC());
    assertEquals(ClockParkMiniGames.LEGACY_RULE, mini.run().getMini().getRule());
    assertEquals(
        ClockParkMiniGames.load(root).fingerprint(ClockParkMiniGames.LEGACY_RULE),
        mini.run().getMini().getSource());
    String settled = mini.saved();
    mini.fixture().query();
    assertEquals(settled, mini.saved());
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 410, 420})
  void oldZeroPolicyCannotReadForgedPositiveCompletedResult(int chapter) throws Exception {
    mini.enter(chapter);
    legacy();
    mini.finish(false, 0);
    assertEquals(0, mini.fixture().opening.code());
    var raw = mini.raw();
    var proof = raw.getAsJsonObject("clockPark").getAsJsonObject("active").getAsJsonObject("mini");
    proof.addProperty("win", true);
    proof.addProperty("score", 1);
    mini.balanced.load(raw);
    String before = mini.saved();
    mini.fixture().opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR.getVal(),
        mini.fixture().opening.code());
    assertEquals(before, mini.saved());
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 410, 420})
  void concurrentPositiveNativeResultsProduceOneSavedSettlement(int chapter) throws Exception {
    uncapped(chapter);
    int script = mini.run().getScript(), round = mini.run().getRound();
    String phase = mini.run().getPhase();
    int saves = mini.fixture().opening.saves;
    var manager = new ClockParkManager(mini.fixture().opening.player);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var start = new java.util.concurrent.CountDownLatch(1);
      var results =
          new ArrayList<java.util.concurrent.Future<emu.lunarcore.server.packet.Retcode>>();
      for (int i = 0; i < 6; i++)
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  return manager.finishMiniGame(script, round, phase, true, 3).code();
                }));
      start.countDown();
      int completed = 0;
      for (var future : results) {
        var code = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        if (code == emu.lunarcore.server.packet.Retcode.SUCC) completed++;
        else assertEquals(emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID, code);
      }
      assertEquals(1, completed);
      assertEquals(saves + 1, mini.fixture().opening.saves);
      assertEquals(3, mini.run().getMini().getScore());
      mini.fixture().query();
    } finally {
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "302,source",
    "302,legacy-rule",
    "302,score4",
    "302,win",
    "302,attributes",
    "410,source",
    "410,legacy-rule",
    "410,score4",
    "410,win",
    "410,attributes",
    "420,source",
    "420,legacy-rule",
    "420,score4",
    "420,win",
    "420,attributes"
  })
  void completedGradeProofMustRecomputeWithMatchingSavedRuleSourceAndAttributes(
      int chapter, String kind) throws Exception {
    uncapped(chapter);
    mini.finish(true, 1);
    assertEquals(0, mini.fixture().opening.code());
    var raw = mini.raw();
    var active = raw.getAsJsonObject("clockPark").getAsJsonObject("active");
    var proof = active.getAsJsonObject("mini");
    switch (kind) {
      case "source" -> proof.addProperty("source", "wrong");
      case "legacy-rule" -> proof.addProperty("rule", ClockParkMiniGames.LEGACY_RULE);
      case "score4" -> proof.addProperty("score", 4);
      case "win" -> proof.addProperty("win", false);
      case "attributes" ->
          active.addProperty("attributeA", active.get("attributeA").getAsInt() + 1);
    }
    mini.balanced.load(raw);
    String before = mini.saved();
    int saves = mini.fixture().opening.saves;
    mini.fixture().opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR.getVal(),
        mini.fixture().opening.code());
    assertEquals(before, mini.saved());
    assertEquals(saves, mini.fixture().opening.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 410, 420})
  void closedActivityDoesNotConsumePositiveResult(int chapter) throws Exception {
    mini.enter(chapter);
    String before = mini.saved();
    int saves = mini.fixture().opening.saves;
    var windows = new ArrayList<>(GameplayDefinitions.windows);
    GameplayDefinitions.windows.clear();
    try {
      mini.finish(true, 3);
      assertEquals(
          emu.lunarcore.server.packet.Retcode.FUNC_CHECK_FAILED.getVal(),
          mini.fixture().opening.code());
      assertEquals(before, mini.saved());
      assertEquals(saves, mini.fixture().opening.saves);
    } finally {
      GameplayDefinitions.windows.addAll(windows);
    }
    mini.finish(true, 3);
    assertEquals(0, mini.fixture().opening.code());
  }

  @Test
  void legacyCompletedMovieHistoryAndNewPolicySecondMovieCoexistWithoutMigration()
      throws Exception {
    mini.enter(410);
    legacy();
    mini.finish(false, 0);
    assertEquals(0, mini.fixture().opening.code());
    mini.checkpoint();
    mini.branch(4122);
    normals(8, 1);
    assertEquals(ClockParkMiniGames.RULE, mini.run().getMini().getRule());
    mini.finish(true, 3);
    assertEquals(0, mini.fixture().opening.code());
    var history =
        mini.raw().getAsJsonObject("clockPark").getAsJsonObject("active").getAsJsonArray("history");
    assertEquals(
        ClockParkMiniGames.LEGACY_RULE,
        history.get(0).getAsJsonObject().getAsJsonObject("mini").get("rule").getAsString());
    String before = mini.saved();
    mini.fixture().query();
    assertEquals(before, mini.saved());
  }

  @ParameterizedTest
  @CsvSource({
    "302,1,CHECKPOINT",
    "302,2,CHECKPOINT",
    "302,3,CHECKPOINT",
    "410,1,CHECKPOINT",
    "410,2,CHECKPOINT",
    "410,3,CHECKPOINT",
    "420,1,CHECKPOINT",
    "420,2,CHECKPOINT",
    "420,3,CHECKPOINT",
    "302,1,BRANCH",
    "302,2,BRANCH",
    "302,3,BRANCH",
    "410,1,BRANCH",
    "410,2,BRANCH",
    "410,3,BRANCH",
    "420,1,BRANCH",
    "420,2,BRANCH",
    "420,3,BRANCH",
    "302,1,NEXT_CHAPTER",
    "302,2,NEXT_CHAPTER",
    "302,3,NEXT_CHAPTER",
    "410,1,NEXT_CHAPTER",
    "410,2,NEXT_CHAPTER",
    "410,3,NEXT_CHAPTER",
    "420,1,NEXT_CHAPTER",
    "420,2,NEXT_CHAPTER",
    "420,3,NEXT_CHAPTER",
    "302,0,LEGACY_WAIT",
    "410,0,LEGACY_WAIT",
    "420,0,LEGACY_WAIT",
    "302,0,LEGACY_CHECKPOINT",
    "410,0,LEGACY_CHECKPOINT",
    "420,0,LEGACY_CHECKPOINT",
    "420,3,MIXED_CHECKPOINT"
  })
  void actualPlayerMorphiaRestoresPositiveCheckpointsBranchesAndChapterHistoryTwice(
      int chapter, int grade, String phase) throws Exception {
    if (phase.startsWith("MIXED_")) {
      mini.enter(410);
      legacy();
      mini.finish(false, 0);
      assertEquals(0, mini.fixture().opening.code());
      mini.checkpoint();
      mini.branch(4122);
      normals(8, 6);
      phase = phase.substring(6);
    } else {
      mini.enter(chapter);
      if (phase.startsWith("LEGACY_")) {
        legacy();
        phase = phase.substring(7);
      }
    }
    if (!phase.equals("WAIT")) {
      mini.finish(grade > 0, grade);
      assertEquals(0, mini.fixture().opening.code());
      if (!phase.equals("CHECKPOINT")) mini.checkpoint();
      if (phase.equals("NEXT_CHAPTER"))
        mini.branch(chapter == 302 ? 3102 : chapter == 410 ? 4122 : 4226);
    }
    var expected = mini.fixture().query();
    String before = mini.saved();
    var opening = mini.fixture().opening;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-ranked-game-fixture");
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
      uid.set(document, 199994);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, opening.player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(
            mini.canonical(before), mini.canonical(opening.gson.toJson(restored.getGameplay())));
        opening.replaceGameplay(restored.getGameplay());
        String loaded = mini.saved();
        int saves = opening.saves;
        assertEquals(expected, mini.fixture().query());
        assertEquals(loaded, mini.saved());
        assertEquals(saves, opening.saves);
      }
    } finally {
      server.shutdownNow();
    }
    mini.balanced.load(JsonParser.parseString(before).getAsJsonObject());
  }
}
