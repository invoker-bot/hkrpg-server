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

class ClockParkMiniGames46Test {
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

  ClockParkDice46Test fixture() {
    return balanced.fixture();
  }

  ClockParkState.Run run() {
    return balanced.run();
  }

  String saved() {
    return balanced.saved();
  }

  JsonObject raw() {
    return JsonParser.parseString(saved()).getAsJsonObject();
  }

  JsonObject mini() {
    return raw().getAsJsonObject("clockPark").getAsJsonObject("active").getAsJsonObject("mini");
  }

  void normals(int count) throws Exception {
    for (int i = 0; i < count; i++) {
      balanced.place(run().getScript(), 6);
      assertEquals(0, fixture().opening.code());
    }
  }

  void checkpoint() throws Exception {
    fixture()
        .submit(
            fixture()
                .operation(
                    run().getScript(),
                    run().getRound(),
                    "MLHNHAFPLKD",
                    fixture().request("MOEMKBLCKKO").build()));
    assertEquals(0, fixture().opening.code());
    assertEquals("BRANCH", run().getPhase());
  }

  void branch(int card) throws Exception {
    fixture()
        .submit(
            fixture()
                .placement(
                    run().getScript(),
                    run().getRound(),
                    new int[] {run().getDice().getFirst()},
                    new int[] {card},
                    false,
                    false));
    assertEquals(0, fixture().opening.code());
    fixture().finishStory(run().getScript(), run().getRound());
    assertEquals(0, fixture().opening.code());
  }

  void enter(int chapter) throws Exception {
    balanced.resetToProjectDefault();
    fixture().begin(chapter == 302 ? 3 : 4);
    if (chapter == 302) {
      normals(4);
      checkpoint();
      branch(3101);
    }
    if (chapter == 420) {
      normals(8);
      finish(false, 0);
      assertEquals(0, fixture().opening.code());
      checkpoint();
      branch(4122);
    }
    normals(chapter == 302 ? 4 : 8);
  }

  DynamicMessage report(int script, int round, String phase, boolean win, int score) {
    boolean movie = phase.equals("MOVIE");
    var payload = fixture().request(movie ? "AIGEDICFHCE" : "AJNKADCOKOL");
    fixture().set(payload, "is_win", win);
    fixture().set(payload, "IMGLLIBJAPC", score);
    return fixture()
        .operation(script, round, movie ? "DIKEDLHGDJJ" : "ACIOPOIJNEF", payload.build());
  }

  void finish(boolean win, int score) throws Exception {
    fixture().submit(report(run().getScript(), run().getRound(), run().getPhase(), win, score));
  }

  @ParameterizedTest
  @CsvSource({
    "302,HANU,30208,3405,13,14",
    "410,MOVIE,41009,4121,11,7",
    "420,MOVIE,42009,4225,11,7"
  })
  void originalGameRoundUsesMatchedWaitAndSavedOwnedCard(
      int chapter, String phase, int round, int card, int waitField, int diceField)
      throws Exception {
    enter(chapter);
    assertEquals(phase, run().getPhase());
    assertEquals(round, run().getRound());
    var query = fixture().query();
    var wait = (DynamicMessage) query.getField(query.getDescriptorForType().findFieldByNumber(2));
    var game =
        (DynamicMessage) wait.getField(wait.getDescriptorForType().findFieldByNumber(waitField));
    var dice =
        (DynamicMessage) game.getField(game.getDescriptorForType().findFieldByNumber(diceField));
    var row = (DynamicMessage) ((List<?>) fixture().value(dice, "LONFKFCFDND")).getFirst();
    assertEquals(card, fixture().number(row, "card_id"));
    assertEquals(List.of(mini().get("face").getAsInt()), fixture().value(row, "KCNEKEGGAGG"));
    assertEquals(mini().get("face").getAsInt(), fixture().number(row, "NPCBECNBIJK"));
  }

  @ParameterizedTest
  @CsvSource({"302,false", "410,false", "410,true", "420,false", "420,true"})
  void zeroGradeEndsOnlyGameAndComputesActualCheckpointWithoutAnyAttributeGain(
      int chapter, boolean win) throws Exception {
    enter(chapter);
    int a = run().getAttributeA(), b = run().getAttributeB(), c = run().getAttributeC();
    var req = report(run().getScript(), run().getRound(), run().getPhase(), win, 0);
    fixture().submit(req);
    assertEquals(0, fixture().opening.code());
    assertEquals("CHECKPOINT", run().getPhase());
    assertEquals(chapter == 302 ? 321 : chapter == 410 ? 411 : 421, run().getCheckpoint());
    assertEquals(a, run().getAttributeA());
    assertEquals(b, run().getAttributeB());
    assertEquals(c, run().getAttributeC());
    assertTrue(mini().get("completed").getAsBoolean());
    assertEquals(win, mini().get("win").getAsBoolean());
    assertEquals(
        0,
        fixture().opening.player.getGameplay().getClockPark().progress(ClockParkManager.catalog()));
    String before = saved();
    int saves = fixture().opening.saves;
    fixture().submit(req);
    assertEquals(5, fixture().opening.code());
    assertEquals(before, saved());
    assertEquals(saves, fixture().opening.saves);
  }

  @ParameterizedTest
  @CsvSource({"302,1", "302,2", "302,3", "410,1", "410,2", "410,3", "420,1", "420,2", "420,3"})
  void legacyPositiveGradesRemainClosedAndKeepOriginalOwnedGameWaiting(int chapter, int score)
      throws Exception {
    enter(chapter);
    var raw = raw();
    var proof = raw.getAsJsonObject("clockPark").getAsJsonObject("active").getAsJsonObject("mini");
    proof.addProperty("rule", ClockParkMiniGames.LEGACY_RULE);
    proof.addProperty(
        "source", ClockParkMiniGames.load(root).fingerprint(ClockParkMiniGames.LEGACY_RULE));
    balanced.load(raw);
    String before = saved();
    int saves = fixture().opening.saves;
    finish(true, score);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.FEATURE_SWITCH_CLOSED.getVal(),
        fixture().opening.code());
    assertEquals(before, saved());
    assertEquals(saves, fixture().opening.saves);
  }

  @ParameterizedTest
  @CsvSource({
    "302,true,0",
    "302,false,1",
    "410,false,1",
    "420,false,3",
    "302,true,4",
    "410,true,4",
    "420,true,-1"
  })
  void impossibleResultAndOutsideUIntGradeCannotAlterGame(int chapter, boolean win, int score)
      throws Exception {
    enter(chapter);
    String before = saved();
    int saves = fixture().opening.saves;
    finish(win, score);
    assertEquals(5, fixture().opening.code());
    assertEquals(before, saved());
    assertEquals(saves, fixture().opening.saves);
  }

  @ParameterizedTest
  @CsvSource({
    "302,foreign-script",
    "302,foreign-round",
    "302,foreign-type",
    "410,foreign-script",
    "410,foreign-round",
    "410,foreign-type"
  })
  void wrongIdentityOrGameTypeDoesNotConsumeStoredCard(int chapter, String kind) throws Exception {
    enter(chapter);
    int script = run().getScript(), round = run().getRound();
    String phase = run().getPhase();
    if (kind.equals("foreign-script")) script = script == 3 ? 4 : 3;
    if (kind.equals("foreign-round")) round++;
    if (kind.equals("foreign-type")) phase = phase.equals("HANU") ? "MOVIE" : "HANU";
    String before = saved();
    fixture().submit(report(script, round, phase, false, 0));
    assertEquals(5, fixture().opening.code());
    assertEquals(before, saved());
  }

  @ParameterizedTest
  @CsvSource({"302,entry", "302,result", "410,entry", "410,result", "420,entry", "420,result"})
  void saveFailureRollsBackEntryOrResultAndAllowsExactlyOneRetry(int chapter, String stage)
      throws Exception {
    if (stage.equals("result")) enter(chapter);
    else {
      balanced.resetToProjectDefault();
      fixture().begin(chapter == 302 ? 3 : 4);
      if (chapter == 302) {
        normals(4);
        checkpoint();
        branch(3101);
      }
      if (chapter == 420) {
        normals(8);
        finish(false, 0);
        assertEquals(0, fixture().opening.code());
        checkpoint();
        branch(4122);
      }
      normals((chapter == 302 ? 4 : 8) - 1);
      fixture().overrideDice(6, 6, 6);
    }
    var req =
        stage.equals("result")
            ? report(run().getScript(), run().getRound(), run().getPhase(), false, 0)
            : fixture()
                .placement(
                    run().getScript(),
                    run().getRound(),
                    new int[] {6, 6, 6},
                    new int[] {1001, 1007, 1008},
                    false,
                    false);
    String before = saved();
    fixture().opening.failSave = true;
    fixture().submit(req);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(), fixture().opening.code());
    assertEquals(before, saved());
    fixture().opening.failSave = false;
    fixture().submit(req);
    assertEquals(0, fixture().opening.code());
    assertEquals(
        stage.equals("result") ? "CHECKPOINT" : chapter == 302 ? "HANU" : "MOVIE",
        run().getPhase());
    String after = saved();
    fixture().submit(req);
    assertEquals(5, fixture().opening.code());
    assertEquals(after, saved());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "source",
        "rule",
        "round",
        "card",
        "face0",
        "face7",
        "score",
        "completed",
        "wrong-phase",
        "slot"
      })
  void corruptedGameProofClosesReadWithoutRewritingIt(String kind) throws Exception {
    enter(302);
    var raw = raw();
    var active = raw.getAsJsonObject("clockPark").getAsJsonObject("active");
    var proof = active.getAsJsonObject("mini");
    switch (kind) {
      case "source", "rule" -> proof.addProperty(kind, "wrong");
      case "round", "card", "score" -> proof.addProperty(kind, 1);
      case "face0" -> proof.addProperty("face", 0);
      case "face7" -> proof.addProperty("face", 7);
      case "completed" -> proof.addProperty("completed", true);
      case "wrong-phase" -> active.addProperty("phase", "MOVIE");
      case "slot" -> active.add("slot", new JsonObject());
    }
    balanced.load(raw);
    String before = saved();
    int saves = fixture().opening.saves;
    fixture().opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR.getVal(), fixture().opening.code());
    assertEquals(before, saved());
    assertEquals(saves, fixture().opening.saves);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"ClockParkCard", "ClockParkCardAction", "ClockParkEffect", "ClockParkConst"})
  void warmedDefinitionsStillRejectSameSizeAndSameTimestampOriginalMutation(String file)
      throws Exception {
    enter(410);
    var path = root.resolve("ExcelOutput/" + file + ".json");
    var original = java.nio.file.Files.readAllBytes(path);
    var time = java.nio.file.Files.getLastModifiedTime(path);
    var changed = original.clone();
    for (int i = 0; i < changed.length; i++)
      if (changed[i] == '1') {
        changed[i] = '2';
        break;
      }
    java.nio.file.Files.write(path, changed);
    java.nio.file.Files.setLastModifiedTime(path, time);
    String before = saved();
    fixture().opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR.getVal(), fixture().opening.code());
    assertEquals(before, saved());
    java.nio.file.Files.write(path, original);
    java.nio.file.Files.setLastModifiedTime(path, time);
    fixture().query();
    assertEquals(0, fixture().opening.code());
    assertEquals(before, saved());
  }

  @Test
  void fourthScriptZeroBranchDeltasCarryActualAttributesToSecondOriginalMovieRound()
      throws Exception {
    enter(410);
    finish(false, 0);
    assertEquals(0, fixture().opening.code());
    checkpoint();
    int a = run().getAttributeA(), b = run().getAttributeB(), c = run().getAttributeC();
    branch(4122);
    assertEquals(420, run().getChapter());
    assertEquals(a, run().getAttributeA());
    assertEquals(b, run().getAttributeB());
    assertEquals(c, run().getAttributeC());
    normals(8);
    assertEquals("MOVIE", run().getPhase());
    assertEquals(4225, mini().get("card").getAsInt());
  }

  @ParameterizedTest
  @CsvSource({
    "302,WAIT",
    "302,CHECKPOINT",
    "302,BRANCH",
    "302,STORY",
    "410,WAIT",
    "410,CHECKPOINT",
    "410,BRANCH",
    "420,WAIT"
  })
  void actualPlayerMorphiaRestoresGameAndPriorGameHistoryTwiceWithoutReadChanges(
      int chapter, String phase) throws Exception {
    enter(chapter);
    if (!phase.equals("WAIT")) {
      finish(false, 0);
      assertEquals(0, fixture().opening.code());
    }
    if (phase.equals("BRANCH") || phase.equals("STORY")) checkpoint();
    if (phase.equals("STORY")) branch(3102);
    var expected = fixture().query();
    String before = saved();
    var opening = fixture().opening;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-mini-game-fixture");
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
      uid.set(document, 199993);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, opening.player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(canonical(before), canonical(opening.gson.toJson(restored.getGameplay())));
        opening.replaceGameplay(restored.getGameplay());
        String loaded = saved();
        int saves = opening.saves;
        assertEquals(expected, fixture().query());
        assertEquals(loaded, saved());
        assertEquals(saves, opening.saves);
      }
    } finally {
      server.shutdownNow();
    }
    balanced.load(JsonParser.parseString(before).getAsJsonObject());
  }

  JsonObject canonical(String text) {
    var raw = JsonParser.parseString(text).getAsJsonObject();
    for (var entry : raw.getAsJsonObject("clockPark").getAsJsonObject("reached").entrySet()) {
      var array = new JsonArray();
      entry.getValue().getAsJsonArray().asList().stream()
          .map(JsonElement::getAsInt)
          .sorted()
          .forEach(array::add);
      entry.setValue(array);
    }
    return raw;
  }

  @ParameterizedTest
  @CsvSource({
    "302,overflow",
    "302,bool2",
    "302,duplicate-score",
    "302,duplicate-win",
    "302,unknown",
    "410,overflow",
    "410,bool2",
    "410,duplicate-score",
    "410,duplicate-win",
    "410,unknown"
  })
  void malformedNativeUnsignedOrBooleanReportIsRejectedBeforeConsumption(int chapter, String kind)
      throws Exception {
    enter(chapter);
    boolean movie = chapter != 302;
    int scoreField = movie ? 6 : 1, winField = movie ? 11 : 13;
    var bytes = new java.io.ByteArrayOutputStream();
    var wire = com.google.protobuf.CodedOutputStream.newInstance(bytes);
    switch (kind) {
      case "overflow" -> wire.writeUInt64(scoreField, 1L << 32);
      case "bool2" -> wire.writeUInt32(winField, 2);
      case "duplicate-score" -> {
        wire.writeUInt32(scoreField, 0);
        wire.writeUInt32(scoreField, 0);
      }
      case "duplicate-win" -> {
        wire.writeBool(winField, false);
        wire.writeBool(winField, false);
      }
      case "unknown" -> wire.writeUInt32(3, 1);
    }
    wire.flush();
    var outer = new java.io.ByteArrayOutputStream();
    wire = com.google.protobuf.CodedOutputStream.newInstance(outer);
    wire.writeUInt32(2, run().getScript());
    wire.writeUInt32(11, run().getRound());
    wire.writeByteArray(movie ? 14 : 12, bytes.toByteArray());
    wire.flush();
    String before = saved();
    int saves = fixture().opening.saves;
    fixture().opening.handle("ClockParkHandleWaitOperationCsReq", outer.toByteArray());
    assertEquals(5, fixture().opening.code());
    assertEquals(before, saved());
    assertEquals(saves, fixture().opening.saves);
  }
}
