package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class ClockParkSlotChapters46Test {
  @TempDir Path root;
  final ClockParkSlot46Test slot = new ClockParkSlot46Test();

  @BeforeEach
  void prepare() throws Exception {
    slot.root = root;
    slot.prepare();
  }

  @AfterEach
  void restore() throws Exception {
    slot.restore();
  }

  ClockParkDice46Test fixture() {
    return slot.fixture();
  }

  ClockParkState.Run run() {
    return slot.run();
  }

  ClockParkManager manager() {
    return new ClockParkManager(fixture().opening.player);
  }

  void gacha(boolean draw) throws Exception {
    var proof = run().getSlot();
    var payload = fixture().request("IGJLLHMJKPC");
    fixture().set(payload, "IMKGOKGKDPH", draw);
    fixture().set(payload, "gacha_random", proof.getTicket());
    fixture().submit(fixture().operation(5, run().getRound(), "EJLKEGLKGDN", payload.build()));
    assertEquals(0, fixture().opening.code());
  }

  void firstBranch() throws Exception {
    slot.enter();
    slot.ticket(650);
    gacha(true);
    gacha(false);
    assertEquals(501, run().getCheckpoint());
    assertTrue(run().getCheckpointPassed());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().finishCheckpoint(5, run().getRound()).code());
    assertEquals("BRANCH", run().getPhase());
  }

  void next(int card) throws Exception {
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().selectBranch(5, run().getRound(), card, run().getDice().getFirst()).code());
    assertEquals("STORY", run().getPhase());
    fixture().finishStory(5, run().getRound());
    assertEquals(0, fixture().opening.code());
  }

  void ordinary(int count) throws Exception {
    for (int i = 0; i < count; i++) slot.balanced.place(5, 6);
  }

  void secondBranch() throws Exception {
    firstBranch();
    next(5119);
    ordinary(4);
    assertEquals("SLOT", run().getPhase());
    assertEquals(52008, run().getRound());
    assertEquals(5250, run().getSlot().getCard());
    gacha(true);
    gacha(false);
    assertEquals(502, run().getCheckpoint());
    assertTrue(run().getCheckpointPassed());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().finishCheckpoint(5, run().getRound()).code());
  }

  void face(int face) throws Exception {
    var raw = JsonParser.parseString(slot.balanced.saved()).getAsJsonObject();
    var dice = new JsonArray();
    dice.add(face);
    raw.getAsJsonObject("clockPark").getAsJsonObject("active").add("dice", dice);
    slot.balanced.load(raw);
  }

  @Test
  void Original5119BranchPreservesSlotProofAndCarriesAttributesInto520Story() throws Exception {
    firstBranch();
    int before = run().getAttributeB();
    String uuid = run().getTransaction();
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().selectBranch(5, run().getRound(), 5119, run().getDice().getFirst()).code());
    assertEquals(520, run().getChapter());
    assertEquals("STORY", run().getPhase());
    assertEquals(before, run().getAttributeB());
    assertEquals(uuid, run().getTransaction());
    assertNull(run().getSlot());
    assertEquals(
        List.of(510, 520), fixture().opening.player.getGameplay().getClockPark().reached(5));
    assertTrue(
        fixture().opening.player.getGameplay().getClockPark().valid(ClockParkManager.catalog()));
  }

  @ParameterizedTest
  @CsvSource({"1,540,5450,54010,5427,560,50601", "6,530,5350,53010,5327,550,50501"})
  void OriginalConditionalBranchAndThirdSlotCanReachBothAuthoredEndingStories(
      int face, int chapter, int card, int round, int transition, int ending, int story)
      throws Exception {
    secondBranch();
    face(face);
    next(5221);
    assertEquals(chapter, run().getChapter());
    ordinary(5);
    assertEquals("SLOT", run().getPhase());
    assertEquals(round, run().getRound());
    assertEquals(card, run().getSlot().getCard());
    gacha(true);
    gacha(false);
    assertEquals(503, run().getCheckpoint());
    assertTrue(run().getCheckpointPassed());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().finishCheckpoint(5, run().getRound()).code());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().selectBranch(5, run().getRound(), transition, run().getDice().getFirst()).code());
    assertEquals(ending, run().getChapter());
    assertEquals(story, run().getStory());
    assertEquals("STORY", run().getPhase());
    assertEquals(
        0,
        fixture().opening.player.getGameplay().getClockPark().progress(ClockParkManager.catalog()));
    assertTrue(
        fixture().opening.player.getGameplay().getClockPark().valid(ClockParkManager.catalog()));
  }

  void secondSlot(int normalFace) throws Exception {
    firstBranch();
    next(5119);
    for (int i = 0; i < 4; i++) slot.balanced.place(5, normalFace);
    assertEquals("SLOT", run().getPhase());
    assertEquals(52008, run().getRound());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6})
  void EveryOriginal5221FaceConsumesTheOwnedBranchAndCarriesTwoSlotProofs(int face)
      throws Exception {
    secondBranch();
    face(face);
    var before = run().getTransaction();
    int a = run().getAttributeA(), b = run().getAttributeB(), c = run().getAttributeC();
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().selectBranch(5, run().getRound(), 5221, face).code());
    assertEquals(face <= 2 ? 540 : 530, run().getChapter());
    assertEquals("STORY", run().getPhase());
    assertEquals(a, run().getAttributeA());
    assertEquals(b, run().getAttributeB());
    assertEquals(c, run().getAttributeC());
    assertEquals(before, run().getTransaction());
    assertNull(run().getSlot());
    String saved = slot.balanced.saved();
    assertTrue(
        fixture().opening.player.getGameplay().getClockPark().valid(ClockParkManager.catalog()));
    fixture().query();
    assertEquals(saved, slot.balanced.saved());
  }

  @ParameterizedTest
  @CsvSource({"0,42,4", "1,32,6", "9,32,6", "10,22,8", "649,22,8", "650,12,12", "999,12,12"})
  void SecondOriginalCardHasZeroCostAndOneOriginalGainAtAllWeightBoundaries(
      int ticket, int id, int gain) throws Exception {
    secondSlot(3);
    slot.ticket(ticket);
    int a = run().getAttributeA(), c = run().getAttributeC();
    assertEquals(84, run().getAttributeB());
    gacha(true);
    assertEquals(id, slot.field(fixture().opening.session.response, 8));
    assertEquals(84 + gain, run().getAttributeB());
    assertEquals(a, run().getAttributeA());
    assertEquals(c, run().getAttributeC());
    String before = slot.balanced.saved();
    int saves = fixture().opening.saves;
    gacha(true);
    assertEquals(id, slot.field(fixture().opening.session.response, 8));
    assertEquals(before, slot.balanced.saved());
    assertEquals(saves, fixture().opening.saves);
    gacha(false);
    assertTrue(run().getCheckpointPassed());
    assertEquals(502, run().getCheckpoint());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "foreign-round",
        "foreign-script",
        "foreign-ticket",
        "normal-card",
        "foreign-branch"
      })
  void NewChapterCannotReuseAFirstChapterLotteryOrOtherPlacement(String kind) throws Exception {
    secondSlot(3);
    slot.ticket(650);
    var payload = fixture().request("IGJLLHMJKPC");
    fixture().set(payload, "IMKGOKGKDPH", true);
    fixture().set(payload, "gacha_random", kind.equals("foreign-ticket") ? 649 : 650);
    var req =
        fixture()
            .operation(
                kind.equals("foreign-script") ? 2 : 5,
                kind.equals("foreign-round") ? 51008 : 52008,
                "EJLKEGLKGDN",
                payload.build());
    if (kind.equals("normal-card"))
      req =
          fixture()
              .placement(5, 52008, new int[] {1, 1, 1}, new int[] {1001, 1007, 1008}, false, false);
    if (kind.equals("foreign-branch"))
      req = fixture().placement(5, 52008, new int[] {1}, new int[] {5119}, false, false);
    String before = slot.balanced.saved();
    int saves = fixture().opening.saves;
    fixture().submit(req);
    assertEquals(5, fixture().opening.code());
    assertEquals(before, slot.balanced.saved());
    assertEquals(saves, fixture().opening.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"source", "card", "ticket", "played", "gain", "rule", "round", "target"})
  void DamagedPreviousChapterSlotReceiptCannotBeRepairedOrUsedForLaterDraws(String kind)
      throws Exception {
    secondSlot(3);
    var raw = JsonParser.parseString(slot.balanced.saved()).getAsJsonObject();
    var active = raw.getAsJsonObject("clockPark").getAsJsonObject("active");
    var history = active.getAsJsonArray("history").get(0).getAsJsonObject();
    var proof = history.getAsJsonObject("slot");
    switch (kind) {
      case "source" -> proof.addProperty("source", "unverified");
      case "card" -> proof.addProperty("card", 5250);
      case "ticket" -> proof.addProperty("ticket", 10);
      case "played" -> proof.addProperty("played", false);
      case "gain" -> history.addProperty("attributeB", 100);
      case "rule" -> proof.addProperty("rule", ClockParkSlots.CHAPTER_RULE);
      case "round" -> proof.addProperty("round", 52008);
      case "target" -> active.addProperty("chapter", 530);
    }
    slot.balanced.load(raw);
    String before = slot.balanced.saved();
    int saves = fixture().opening.saves;
    fixture().opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR.getVal(), fixture().opening.code());
    assertEquals(before, slot.balanced.saved());
    assertEquals(saves, fixture().opening.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"draw", "exit", "confirmation", "selection"})
  void LaterChapterSaveFailureKeepsItsOriginalTicketCheckpointOrConditionalFace(String action)
      throws Exception {
    secondSlot(3);
    slot.ticket(650);
    if (action.equals("confirmation") || action.equals("selection")) {
      gacha(true);
      gacha(false);
    }
    if (action.equals("selection")) {
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SUCC,
          manager().finishCheckpoint(5, run().getRound()).code());
      face(2);
    }
    String before = slot.balanced.saved();
    fixture().opening.failSave = true;
    var result =
        switch (action) {
          case "draw" -> manager().slotOperation(5, run().getRound(), true, 650);
          case "exit" -> manager().slotOperation(5, run().getRound(), false, 650);
          case "confirmation" -> manager().finishCheckpoint(5, run().getRound());
          default -> manager().selectBranch(5, run().getRound(), 5221, 2);
        };
    assertEquals(emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY, result.code());
    assertEquals(before, slot.balanced.saved());
    fixture().opening.failSave = false;
    result =
        switch (action) {
          case "draw" -> manager().slotOperation(5, run().getRound(), true, 650);
          case "exit" -> manager().slotOperation(5, run().getRound(), false, 650);
          case "confirmation" -> manager().finishCheckpoint(5, run().getRound());
          default -> manager().selectBranch(5, run().getRound(), 5221, 2);
        };
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, result.code());
  }

  @Test
  void SixConcurrentSecondChapterDrawsUseOneTicketAndOneNonClampedGain() throws Exception {
    secondSlot(3);
    slot.ticket(650);
    int saves = fixture().opening.saves;
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<java.util.concurrent.Callable<ClockParkManager.Result>>();
      for (int i = 0; i < 6; i++) calls.add(() -> manager().slotOperation(5, 52008, true, 650));
      for (var reply : pool.invokeAll(calls)) {
        assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, reply.get().code());
        assertEquals(12, reply.get().lotteryId());
      }
    }
    assertEquals(96, run().getAttributeB());
    assertEquals(saves + 1, fixture().opening.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 6})
  void Actual503FailureAfterBothConditionalPathsPreservesPreviousSlotsWithoutInventingAnEnding(
      int face) throws Exception {
    secondSlot(3);
    gacha(true);
    gacha(false);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().finishCheckpoint(5, run().getRound()).code());
    face(face);
    next(5221);
    for (int i = 0; i < 5; i++) slot.balanced.place(5, 1);
    gacha(false);
    assertFalse(run().getCheckpointPassed());
    assertEquals(503, run().getCheckpoint());
    var result = manager().finishCheckpoint(5, run().getRound());
    assertTrue(result.failed());
    assertNull(run());
    var state = fixture().opening.player.getGameplay().getClockPark();
    assertEquals(face <= 2 ? 540 : 530, state.getLastFailure().getChapter());
    assertTrue(state.valid(ClockParkManager.catalog()));
    assertEquals(0, state.progress(ClockParkManager.catalog()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "520-STORY",
        "520-PENDING",
        "520-PLAYED",
        "520-BRANCH",
        "530-PENDING",
        "540-PENDING",
        "550-STORY",
        "560-STORY"
      })
  void ActualPlayerMorphiaReloadPreservesAllSlotHistoryAndTheConditionalChapter(String phase)
      throws Exception {
    if (phase.equals("520-STORY")) {
      firstBranch();
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SUCC,
          manager().selectBranch(5, run().getRound(), 5119, run().getDice().getFirst()).code());
    } else if (phase.equals("520-PENDING") || phase.equals("520-PLAYED")) {
      secondSlot(3);
      if (phase.equals("520-PLAYED")) gacha(true);
    } else {
      secondBranch();
      if (!phase.equals("520-BRANCH")) {
        int face = phase.startsWith("540") || phase.startsWith("560") ? 1 : 6;
        face(face);
        next(5221);
        ordinary(5);
        if (phase.endsWith("STORY")) {
          gacha(true);
          gacha(false);
          assertEquals(
              emu.lunarcore.server.packet.Retcode.SUCC,
              manager().finishCheckpoint(5, run().getRound()).code());
          assertEquals(
              emu.lunarcore.server.packet.Retcode.SUCC,
              manager()
                  .selectBranch(
                      5, run().getRound(), face == 1 ? 5427 : 5327, run().getDice().getFirst())
                  .code());
        }
      }
    }
    String before = slot.balanced.saved();
    var expected = fixture().query();
    var opening = fixture().opening;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-slot-chapters-fixture");
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
      uid.set(document, 199991);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, opening.player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(
            canonicalSaved(before), canonicalSaved(opening.gson.toJson(restored.getGameplay())));
        opening.replaceGameplay(restored.getGameplay());
        String loadedBefore = slot.balanced.saved();
        assertEquals(expected, fixture().query());
        assertEquals(loadedBefore, slot.balanced.saved());
      }
    } finally {
      server.shutdownNow();
    }
    slot.balanced.load(JsonParser.parseString(before).getAsJsonObject());
  }

  JsonObject canonicalSaved(String text) {
    var raw = JsonParser.parseString(text).getAsJsonObject();
    var reached = raw.getAsJsonObject("clockPark").getAsJsonObject("reached");
    for (var entry : reached.entrySet()) {
      var values =
          entry.getValue().getAsJsonArray().asList().stream()
              .map(JsonElement::getAsInt)
              .sorted()
              .toList();
      var ordered = new JsonArray();
      values.forEach(ordered::add);
      entry.setValue(ordered);
    }
    return raw;
  }
}
