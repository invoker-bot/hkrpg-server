package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** The final normal die must reach the actual original checkpoint, without skipping minigames. */
class ClockParkCheckpoint46Test {
  @TempDir Path root;
  final ClockParkDice46Test fixture = new ClockParkDice46Test();

  @BeforeEach
  void prepare() throws Exception {
    fixture.root = root;
    fixture.prepare();
    var files =
        JsonParser.parseString(
                new String(
                    getClass()
                        .getResourceAsStream("/fixtures/clock-park-checkpoints-4.6.json")
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject()
            .getAsJsonObject("files");
    for (var entry : files.entrySet())
      Files.write(
          root.resolve(entry.getKey()),
          HexFormat.of().parseHex(entry.getValue().getAsJsonObject().get("rawHex").getAsString()));
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
  }

  ClockParkState.Run run() {
    return fixture.opening.player.getGameplay().getClockPark().getActive();
  }

  int count(int script) {
    return ClockParkManager.catalog().chapters().get(run().getChapter()).rounds().size();
  }

  Object field(DynamicMessage m, int number) {
    return m.getField(m.getDescriptorForType().findFieldByNumber(number));
  }

  void advanceToLast(int script, int face) throws Exception {
    fixture.begin(script);
    while (run().getRoundIndex() + 1 < count(script)) {
      fixture.overrideDice(face, face, face);
      fixture.submit(fixture.placement(script, run().getRound()));
      assertEquals(0, fixture.opening.code());
    }
    fixture.overrideDice(face, face, face);
  }

  DynamicMessage finish(int script) throws Exception {
    var request = fixture.placement(script, run().getRound());
    fixture.submit(request);
    assertEquals(0, fixture.opening.code());
    return request;
  }

  @ParameterizedTest
  @CsvSource({"1,6,true", "1,1,false", "2,6,false", "2,1,false", "3,6,true", "3,1,false"})
  void finalNormalPlacementSavesAndReturnsTheActualOriginalCheckpointResult(
      int script, int face, boolean passed) throws Exception {
    advanceToLast(script, face);
    int last = run().getRound(), rounds = count(script);
    var request = finish(script);
    assertEquals("CHECKPOINT", run().getPhase());
    assertEquals(rounds, run().getRoundIndex());
    assertEquals(last, run().getRound());
    assertEquals(2 * face * rounds, run().getAttributeA());
    assertEquals(face * rounds, run().getAttributeB());
    assertEquals(0, run().getAttributeC());
    assertTrue(run().getDice().isEmpty());
    var query = fixture.query();
    assertEquals(new int[] {111, 211, 311}[script - 1], field(query, 6));
    var wait = (DynamicMessage) field(query, 2);
    var checkpoint = (DynamicMessage) field(wait, 15);
    assertEquals(passed, field(checkpoint, 14));
    String before = fixture.opening.gson.toJson(fixture.opening.player.getGameplay());
    fixture.submit(request);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), fixture.opening.code());
    assertEquals(before, fixture.opening.gson.toJson(fixture.opening.player.getGameplay()));
    assertEquals(
        List.of(new int[] {101, 201, 301}[script - 1]),
        fixture.opening.player.getGameplay().getClockPark().reached(script));
  }

  @ParameterizedTest
  @ValueSource(ints = {4, 5})
  void originalMovieAndSlotMachineRoundsAreNotSkipped(int script) throws Exception {
    advanceToLast(script, 6);
    String before = fixture.opening.gson.toJson(fixture.opening.player.getGameplay());
    fixture.submit(fixture.placement(script, run().getRound()));
    if (script == 4) {
      assertEquals(0, fixture.opening.code());
      assertEquals("MOVIE", run().getPhase());
      assertEquals(41009, run().getRound());
      assertEquals(0, run().getCheckpoint());
      assertFalse(run().getMini().isCompleted());
    } else {
      assertEquals(Retcode.FEATURE_SWITCH_CLOSED.getVal(), fixture.opening.code());
      assertEquals(before, fixture.opening.gson.toJson(fixture.opening.player.getGameplay()));
    }
  }

  @Test
  void failedCheckpointSavePreservesTheUnconsumedFinalDiceAndCanBeRetried() throws Exception {
    advanceToLast(1, 6);
    var request = fixture.placement(1, run().getRound());
    String before = fixture.opening.gson.toJson(fixture.opening.player.getGameplay());
    fixture.opening.failSave = true;
    fixture.submit(request);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture.opening.code());
    assertEquals(before, fixture.opening.gson.toJson(fixture.opening.player.getGameplay()));
    fixture.opening.failSave = false;
    fixture.submit(request);
    assertEquals(0, fixture.opening.code());
    assertEquals("CHECKPOINT", run().getPhase());
  }

  @Test
  void changedOriginalCheckpointSourceCannotConsumeFinalDice() throws Exception {
    advanceToLast(1, 6);
    String before = fixture.opening.gson.toJson(fixture.opening.player.getGameplay());
    Files.writeString(root.resolve("ExcelOutput/ClockParkCheckPoint.json"), "[]");
    fixture.submit(fixture.placement(1, run().getRound()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), fixture.opening.code());
    assertEquals(before, fixture.opening.gson.toJson(fixture.opening.player.getGameplay()));
  }

  @ParameterizedTest
  @CsvSource({"1,6", "1,1", "2,6", "2,1", "3,6", "3,1"})
  void actualPlayerMorphiaReloadPreservesComputedPassAndFailure(int script, int face)
      throws Exception {
    advanceToLast(script, face);
    finish(script);
    var expected = fixture.query();
    var opening = fixture.opening;
    String before = opening.gson.toJson(opening.player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-checkpoint-fixture");
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
      uid.set(document, 199989);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, opening.player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(before, opening.gson.toJson(restored.getGameplay()));
        opening.replaceGameplay(restored.getGameplay());
        assertEquals(expected, fixture.query());
        assertEquals(before, opening.gson.toJson(restored.getGameplay()));
      }
    } finally {
      server.shutdownNow();
    }
    opening.replaceGameplay(opening.gson.fromJson(before, GameplayState.class));
    assertEquals(expected, fixture.query());
  }

  @ParameterizedTest
  @ValueSource(strings = {"checkpoint", "checkpointPassed", "checkpointSource", "roundIndex"})
  void corruptSavedCheckpointIsRejectedWithoutQueryRepair(String key) throws Exception {
    advanceToLast(1, 6);
    finish(1);
    var opening = fixture.opening;
    var state =
        JsonParser.parseString(opening.gson.toJson(opening.player.getGameplay())).getAsJsonObject();
    var saved = state.getAsJsonObject("clockPark").getAsJsonObject("active");
    switch (key) {
      case "checkpoint" -> saved.addProperty(key, 121);
      case "checkpointPassed" -> saved.addProperty(key, false);
      case "checkpointSource" -> saved.addProperty(key, "foreign");
      case "roundIndex" -> saved.addProperty(key, 2);
    }
    opening.replaceGameplay(opening.gson.fromJson(state, GameplayState.class));
    String before = opening.gson.toJson(opening.player.getGameplay());
    opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), opening.code());
    assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
  }

  @Test
  void concurrentFinalPlacementsProduceOneCheckpointAndOneEffectReceipt() throws Exception {
    advanceToLast(1, 6);
    int round = run().getRound();
    var manager = new ClockParkManager(fixture.opening.player);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<java.util.concurrent.Callable<ClockParkManager.Result>>();
      for (int i = 0; i < 6; i++) calls.add(() -> manager.placeDice(1, round, List.of(6, 6, 6)));
      var results = pool.invokeAll(calls);
      int successful = 0;
      for (var result : results) {
        var code = result.get().code();
        if (code == Retcode.SUCC) successful++;
        else assertEquals(Retcode.REQ_PARA_INVALID, code);
      }
      assertEquals(1, successful);
    }
    assertEquals(36, run().getAttributeA());
    assertEquals(18, run().getAttributeB());
    assertEquals(3, run().getRoundIndex());
    assertEquals("CHECKPOINT", run().getPhase());
  }

  @Test
  void passedCheckpointConfirmationEntersBranchAndWrongScriptCannotChangeIt() throws Exception {
    advanceToLast(1, 6);
    finish(1);
    var opening = fixture.opening;
    fixture.submit(
        fixture.operation(
            1, run().getRound(), "MLHNHAFPLKD", fixture.request("MOEMKBLCKKO").build()));
    assertEquals(0, opening.code());
    assertEquals("BRANCH", run().getPhase());
    String before = opening.gson.toJson(opening.player.getGameplay());
    fixture.submit(
        fixture.operation(
            2, run().getRound(), "MLHNHAFPLKD", fixture.request("MOEMKBLCKKO").build()));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), opening.code());
    assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
  }

  @Test
  void originalLfGitBytesAndTheirCrLfCheckoutHaveTheSameCheckpointMeaning() throws Exception {
    var bytes = Files.readString(root.resolve("ExcelOutput/ClockParkCheckPoint.json"));
    Files.writeString(
        root.resolve("ExcelOutput/ClockParkCheckPoint.json"), bytes.replace("\r\n", "\n"));
    advanceToLast(1, 6);
    finish(1);
    assertTrue(run().getCheckpointPassed());
    assertEquals(0, fixture.opening.code());
  }
}
