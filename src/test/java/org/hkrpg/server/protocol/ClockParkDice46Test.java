package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.Retcode;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original card effects, with a deliberately named project pool and persisted server dice. */
class ClockParkDice46Test {
  @TempDir Path root;
  final ClockParkOpening46Test opening = new ClockParkOpening46Test();

  @BeforeEach
  void prepare() throws Exception {
    opening.root = root;
    opening.prepare();
    var fixture =
        JsonParser.parseString(
                new String(
                    getClass()
                        .getResourceAsStream("/fixtures/clock-park-dice-4.6.json")
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var e : fixture.getAsJsonObject("files").entrySet()) {
      var file = root.resolve(e.getKey());
      Files.createDirectories(file.getParent());
      Files.write(
          file,
          HexFormat.of().parseHex(e.getValue().getAsJsonObject().get("rawHex").getAsString()));
    }
    var checkpoints =
        JsonParser.parseString(
                new String(
                    getClass()
                        .getResourceAsStream("/fixtures/clock-park-checkpoints-4.6.json")
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var e : checkpoints.getAsJsonObject("files").entrySet())
      Files.write(
          root.resolve(e.getKey()),
          HexFormat.of().parseHex(e.getValue().getAsJsonObject().get("rawHex").getAsString()));
  }

  @AfterEach
  void restore() throws Exception {
    opening.restore();
  }

  Object value(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  int number(DynamicMessage m, String name) {
    return (Integer) value(m, name);
  }

  DynamicMessage nested(DynamicMessage m, String name) {
    return (DynamicMessage) value(m, name);
  }

  DynamicMessage.Builder request(String name) {
    return opening.request(name);
  }

  void set(DynamicMessage.Builder m, String name, Object value) {
    m.setField(m.getDescriptorForType().findFieldByName(name), value);
  }

  void add(DynamicMessage.Builder m, String name, Object value) {
    m.addRepeatedField(m.getDescriptorForType().findFieldByName(name), value);
  }

  DynamicMessage query() throws Exception {
    opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(0, opening.code());
    return opening.session.response;
  }

  List<Integer> dice(DynamicMessage response) {
    return (List<Integer>)
        value(
            nested(nested(nested(response, "HGDLJDAFHLI"), "FDNLIPKGBLM"), "CENIOBBIJMF"),
            "KCNEKEGGAGG");
  }

  DynamicMessage operation(int script, int round, String field, DynamicMessage payload) {
    var req = request("ClockParkHandleWaitOperationCsReq");
    set(req, "script_id", script);
    set(req, "JGPHDBDHKKO", round);
    set(req, field, payload);
    return req.build();
  }

  void submit(DynamicMessage req) throws Exception {
    opening.handle("ClockParkHandleWaitOperationCsReq", req.toByteArray());
  }

  void finishStory(int script, int round) throws Exception {
    submit(operation(script, round, "IKNEKEJEGLM", request("NKOCCGLHOML").build()));
  }

  int first(int script) {
    return new int[] {10101, 20101, 30101, 41001, 51001}[script - 1];
  }

  void begin(int script) throws Exception {
    opening.unlock(script);
    opening.start(script);
    assertEquals(0, opening.code());
    finishStory(script, first(script));
    assertEquals(0, opening.code());
  }

  DynamicMessage placement(
      int script, int round, int[] indices, int[] cards, boolean forgedValue, boolean unused) {
    var dice = request("IIMHLPBEDDH");
    for (int i = 0; i < cards.length; i++) {
      var row = request("NIJHCCNMEGA");
      set(row, "card_id", cards[i]);
      if (i < indices.length) add(row, "KCNEKEGGAGG", indices[i]);
      if (forgedValue) set(row, "NPCBECNBIJK", 999);
      add(dice, "LONFKFCFDND", row.build());
    }
    if (unused) add(dice, "KCNEKEGGAGG", 6);
    var outer = request("KJAIAGDNPGC");
    set(outer, "CENIOBBIJMF", dice.build());
    return operation(script, round, "JFAJEBGKPEH", outer.build());
  }

  DynamicMessage placement(int script, int round) {
    return placement(
        script,
        round,
        opening.player.getGameplay().getClockPark().getActive().getDice().stream()
            .mapToInt(Integer::intValue)
            .toArray(),
        new int[] {1001, 1002, 1003},
        false,
        false);
  }

  void overrideDice(int... faces) throws Exception {
    var json =
        JsonParser.parseString(opening.gson.toJson(opening.player.getGameplay())).getAsJsonObject();
    var values = new JsonArray();
    for (int face : faces) values.add(face);
    json.getAsJsonObject("clockPark").getAsJsonObject("active").add("dice", values);
    opening.replaceGameplay(opening.gson.fromJson(json, GameplayState.class));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void originalStoryAcknowledgementCreatesSavedDiceWaitingPhase(int script) throws Exception {
    begin(script);
    var response = query();
    var faces = dice(response);
    assertEquals(3, faces.size());
    assertTrue(faces.stream().allMatch(n -> n >= 1 && n <= 6));
    assertEquals(first(script), number(nested(response, "HGDLJDAFHLI"), "JGPHDBDHKKO"));
    assertEquals(0, number(response, "JIPPMKBDJJC"));
    assertEquals(0, number(response, "INDJEOAJJEN"));
    var cards =
        (List<DynamicMessage>)
            value(
                nested(nested(nested(response, "HGDLJDAFHLI"), "FDNLIPKGBLM"), "CENIOBBIJMF"),
                "LONFKFCFDND");
    assertEquals(List.of(1001, 1002, 1003), cards.stream().map(c -> number(c, "card_id")).toList());
    assertTrue(
        cards.stream()
            .allMatch(
                c ->
                    ((List<?>) value(c, "KCNEKEGGAGG")).isEmpty()
                        && number(c, "NPCBECNBIJK") == 0));
    assertEquals(2, opening.saves);
    String before = opening.gson.toJson(opening.player.getGameplay());
    assertArrayEquals(response.toByteArray(), query().toByteArray());
    assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
    assertEquals(2, opening.saves);
    finishStory(script, first(script));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), opening.code());
    assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void placementUsesSavedFacesAndOriginalCardActionsThenAdvancesOriginalRound(int script)
      throws Exception {
    begin(script);
    var old = query();
    var faces = dice(old);
    submit(placement(script, first(script)));
    assertEquals(0, opening.code());
    var next = query();
    assertEquals(first(script) + 1, number(nested(next, "HGDLJDAFHLI"), "JGPHDBDHKKO"));
    assertEquals(1, number(next, "JIPPMKBDJJC"));
    assertEquals(value(old, "IMJFLCKFJGF"), value(next, "IMJFLCKFJGF"));
    var attrs = nested(next, "JOFJEIFLGBP");
    assertEquals(faces.get(0) + faces.get(2), number(attrs, "JEKOHIHJFMK"));
    assertEquals(faces.get(1), number(attrs, "AKKLILNJCPL"));
    assertEquals(0, number(attrs, "PNOIJPDMCPG"));
    String before = opening.gson.toJson(opening.player.getGameplay());
    submit(placement(script, first(script)));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), opening.code());
    assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void allOriginalFirstChapterNormalRoundsAccumulateAndDoNotForgeCheckpointCompletion(int script)
      throws Exception {
    begin(script);
    var catalog = ClockParkManager.catalog();
    var rounds = catalog.chapters().get(first(script) / 100).rounds();
    int a = 0, b = 0;
    for (int i = 0; i < rounds.size(); i++) {
      var faces = dice(query());
      if (i + 1 == rounds.size()) {
        String before = opening.gson.toJson(opening.player.getGameplay());
        submit(placement(script, rounds.get(i)));
        if (script <= 3) {
          assertEquals(0, opening.code());
          var run = opening.player.getGameplay().getClockPark().getActive();
          assertEquals("CHECKPOINT", run.getPhase());
          assertEquals(rounds.size(), run.getRoundIndex());
          assertEquals(a + faces.get(0) + faces.get(2), run.getAttributeA());
          assertEquals(b + faces.get(1), run.getAttributeB());
          assertEquals(0, run.getAttributeC());
        } else if (script == 4) {
          assertEquals(0, opening.code());
          var run = opening.player.getGameplay().getClockPark().getActive();
          assertEquals("MOVIE", run.getPhase());
          assertEquals(41009, run.getRound());
          assertEquals(rounds.size(), run.getRoundIndex());
          assertEquals(Math.min(60, a + faces.get(0) + faces.get(2)), run.getAttributeA());
          assertEquals(Math.min(60, b + faces.get(1)), run.getAttributeB());
          assertEquals(0, run.getAttributeC());
          assertEquals(0, run.getCheckpoint());
        } else {
          assertEquals(Retcode.FEATURE_SWITCH_CLOSED.getVal(), opening.code());
          assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
        }
        assertEquals(
            List.of(catalog.scripts().get(script).startChapter()),
            opening.player.getGameplay().getClockPark().reached(script));
        break;
      }
      a = Math.min(script == 1 ? 50 : script == 4 ? 60 : 100, a + faces.get(0) + faces.get(2));
      b = Math.min(script == 1 ? 50 : script == 4 ? 60 : 100, b + faces.get(1));
      submit(placement(script, rounds.get(i)));
      assertEquals(0, opening.code());
      var next = query();
      var attrs = nested(next, "JOFJEIFLGBP");
      assertEquals(a, number(attrs, "JEKOHIHJFMK"));
      assertEquals(b, number(attrs, "AKKLILNJCPL"));
      assertEquals(i + 1, number(next, "JIPPMKBDJJC"));
      assertEquals(rounds.get(i + 1), number(nested(next, "HGDLJDAFHLI"), "JGPHDBDHKKO"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "duplicate",
        "outOfRange",
        "negative",
        "missing",
        "extra",
        "foreignCard",
        "duplicateCard",
        "forgedValue",
        "unusedFaces",
        "wrongScript",
        "wrongRound",
        "wrongPhase"
      })
  void invalidPlacementCannotChangeDiceAttributesReceiptsOrSave(String mode) throws Exception {
    begin(1);
    overrideDice(1, 2, 3);
    String before = opening.gson.toJson(opening.player.getGameplay());
    int saves = opening.saves;
    int[] indices = {1, 2, 3}, cards = {1001, 1002, 1003};
    switch (mode) {
      case "duplicate" -> indices = new int[] {1, 1, 3};
      case "outOfRange" -> indices = new int[] {1, 2, 7};
      case "negative" -> indices = new int[] {1, 2, -1};
      case "missing" -> indices = new int[] {1, 2};
      case "extra" -> cards = new int[] {1001, 1002, 1003, 1001};
      case "foreignCard" -> cards = new int[] {1001, 1002, 1004};
      case "duplicateCard" -> cards = new int[] {1001, 1001, 1003};
    }
    var req =
        mode.equals("wrongPhase")
            ? operation(1, 10101, "MLHNHAFPLKD", request("MOEMKBLCKKO").build())
            : placement(
                mode.equals("wrongScript") ? 2 : 1,
                mode.equals("wrongRound") ? 10102 : 10101,
                indices,
                cards,
                mode.equals("forgedValue"),
                mode.equals("unusedFaces"));
    submit(req);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), opening.code());
    assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
    assertEquals(saves, opening.saves);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedSaveRollsBackStoryOrPlacementAndAllowsARealRetry(boolean placement) throws Exception {
    opening.unlock(1);
    opening.start(1);
    if (placement) {
      finishStory(1, 10101);
      assertEquals(0, opening.code());
    }
    var req =
        placement
            ? placement(1, 10101)
            : operation(1, 10101, "IKNEKEJEGLM", request("NKOCCGLHOML").build());
    String before = opening.gson.toJson(opening.player.getGameplay());
    opening.failSave = true;
    submit(req);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), opening.code());
    assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
    opening.failSave = false;
    submit(req);
    assertEquals(0, opening.code());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"phase", "dice", "cardPool", "attributeA", "roundIndex", "receipts", "round"})
  void corruptSavedDiceStateIsRejectedWithoutQueryRepair(String field) throws Exception {
    begin(1);
    submit(placement(1, 10101));
    assertEquals(0, opening.code());
    var json =
        JsonParser.parseString(opening.gson.toJson(opening.player.getGameplay())).getAsJsonObject();
    var active = json.getAsJsonObject("clockPark").getAsJsonObject("active");
    switch (field) {
      case "phase" -> active.addProperty(field, "Other");
      case "dice" -> active.add(field, JsonParser.parseString("[0,7,1]"));
      case "cardPool" -> active.addProperty(field, "fake");
      case "receipts" -> active.add(field, new JsonArray());
      default -> active.addProperty(field, 999);
    }
    opening.replaceGameplay(opening.gson.fromJson(json, GameplayState.class));
    String before = opening.gson.toJson(opening.player.getGameplay());
    opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), opening.code());
    assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"ClockParkCard", "ClockParkCardAction", "ClockParkEffect", "ClockParkConst"})
  void changedOriginalInputCannotCreateDiceOrAdvanceSavedRound(String name) throws Exception {
    opening.unlock(1);
    opening.start(1);
    Files.writeString(root.resolve("ExcelOutput/" + name + ".json"), "[]");
    String before = opening.gson.toJson(opening.player.getGameplay());
    finishStory(1, 10101);
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), opening.code());
    assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
  }

  @Test
  void twoOneofOperationsInOneWireMessageAreRejectedBeforeParsing() throws Exception {
    begin(1);
    String before = opening.gson.toJson(opening.player.getGameplay());
    var body = new ByteArrayOutputStream();
    body.write(placement(1, 10101).toByteArray());
    body.write(new byte[] {50, 0});
    opening.handle("ClockParkHandleWaitOperationCsReq", body.toByteArray());
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), opening.code());
    assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
  }

  @ParameterizedTest
  @CsvSource({
    "1,false", "2,false", "3,false", "4,false", "5,false", "1,true", "2,true", "3,true", "4,true",
    "5,true"
  })
  void actualPlayerMorphiaReloadPreservesPendingDiceAndEffectReceipts(
      int script, boolean progressed) throws Exception {
    begin(script);
    if (progressed) {
      submit(placement(script, first(script)));
      assertEquals(0, opening.code());
    }
    var expected = query();
    String before = opening.gson.toJson(opening.player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-dice-fixture");
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
      uid.set(document, 199987);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, opening.player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(before, opening.gson.toJson(restored.getGameplay()));
        opening.replaceGameplay(restored.getGameplay());
        assertEquals(expected, query());
        assertEquals(before, opening.gson.toJson(restored.getGameplay()));
      }
    } finally {
      server.shutdownNow();
    }
    opening.replaceGameplay(opening.gson.fromJson(before, GameplayState.class));
    assertEquals(expected, query());
    submit(placement(script, first(script) + (progressed ? 1 : 0)));
    assertEquals(0, opening.code());
  }

  byte[] rawPlacement(boolean packed, boolean overflow) throws Exception {
    var diceOut = new ByteArrayOutputStream();
    var dice = CodedOutputStream.newInstance(diceOut);
    for (int i = 0; i < 3; i++) {
      var cardOut = new ByteArrayOutputStream();
      var card = CodedOutputStream.newInstance(cardOut);
      card.writeUInt32(8, 1001 + i);
      if (packed) {
        var indicesOut = new ByteArrayOutputStream();
        var indices = CodedOutputStream.newInstance(indicesOut);
        int face = opening.player.getGameplay().getClockPark().getActive().getDice().get(i);
        indices.writeUInt64NoTag(overflow && i == 0 ? 4294967296L + face : face);
        indices.flush();
        card.writeByteArray(2, indicesOut.toByteArray());
      } else {
        card.writeTag(2, 0);
        int face = opening.player.getGameplay().getClockPark().getActive().getDice().get(i);
        card.writeUInt64NoTag(overflow && i == 0 ? 4294967296L + face : face);
      }
      card.flush();
      dice.writeByteArray(6, cardOut.toByteArray());
    }
    dice.flush();
    var outerOut = new ByteArrayOutputStream();
    var outer = CodedOutputStream.newInstance(outerOut);
    outer.writeByteArray(8, diceOut.toByteArray());
    outer.flush();
    var reqOut = new ByteArrayOutputStream();
    var req = CodedOutputStream.newInstance(reqOut);
    req.writeUInt32(2, 1);
    req.writeUInt32(11, 10101);
    req.writeByteArray(4, outerOut.toByteArray());
    req.flush();
    return reqOut.toByteArray();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bothNativePackedAndUnpackedPlacedFacesAdvanceOnce(boolean packed) throws Exception {
    begin(1);
    opening.handle("ClockParkHandleWaitOperationCsReq", rawPlacement(packed, false));
    assertEquals(0, opening.code());
    assertEquals(10102, number(nested(query(), "HGDLJDAFHLI"), "JGPHDBDHKKO"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void largeSlotVarintsCannotAliasAnOwnedDiceFace(boolean packed) throws Exception {
    begin(1);
    String before = opening.gson.toJson(opening.player.getGameplay());
    opening.handle("ClockParkHandleWaitOperationCsReq", rawPlacement(packed, true));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), opening.code());
    assertEquals(before, opening.gson.toJson(opening.player.getGameplay()));
  }

  @Test
  void concurrentSameRoundPlacementsApplyOriginalEffectsExactlyOnce() throws Exception {
    begin(1);
    var faces = dice(query());
    var manager = new ClockParkManager(opening.player);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    var latch = new java.util.concurrent.CountDownLatch(1);
    try {
      var first =
          executor.submit(
              () -> {
                latch.await();
                return manager.placeDice(1, 10101, faces).code();
              });
      var second =
          executor.submit(
              () -> {
                latch.await();
                return manager.placeDice(1, 10101, faces).code();
              });
      latch.countDown();
      assertEquals(
          Set.of(Retcode.SUCC, Retcode.REQ_PARA_INVALID), Set.of(first.get(), second.get()));
      var attrs = nested(query(), "JOFJEIFLGBP");
      assertEquals(faces.get(0) + faces.get(2), number(attrs, "JEKOHIHJFMK"));
      assertEquals(faces.get(1), number(attrs, "AKKLILNJCPL"));
      assertEquals(3, opening.saves);
    } finally {
      executor.shutdownNow();
    }
  }
}
