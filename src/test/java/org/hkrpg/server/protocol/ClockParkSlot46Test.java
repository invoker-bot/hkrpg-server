package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class ClockParkSlot46Test {
  @TempDir Path root;
  final ClockParkBalancedPool46Test balanced = new ClockParkBalancedPool46Test();

  @BeforeEach
  void prepare() throws Exception {
    balanced.root = root;
    balanced.prepare();
    var data =
        JsonParser.parseString(
                new String(
                    getClass()
                        .getResourceAsStream("/fixtures/clock-park-slot-4.6.json")
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var entry : data.getAsJsonObject("files").entrySet()) {
      var bytes =
          Base64.getDecoder()
              .decode(entry.getValue().getAsJsonObject().get("gzipBase64").getAsString());
      try (var stream =
          new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
        Files.write(root.resolve(entry.getKey()), stream.readAllBytes());
      }
    }
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

  JsonObject raw() {
    return JsonParser.parseString(balanced.saved()).getAsJsonObject();
  }

  JsonObject slot() {
    return raw().getAsJsonObject("clockPark").getAsJsonObject("active").getAsJsonObject("slot");
  }

  Object field(DynamicMessage m, int number) {
    return m.getField(m.getDescriptorForType().findFieldByNumber(number));
  }

  void enter() throws Exception {
    balanced.resetToProjectDefault();
    fixture().begin(5);
    for (int i = 0; i < 3; i++) balanced.place(5, 6);
    fixture().overrideDice(6, 6, 6);
    fixture()
        .submit(
            fixture()
                .placement(
                    5,
                    run().getRound(),
                    new int[] {6, 6, 6},
                    new int[] {1001, 1007, 1008},
                    false,
                    false));
  }

  DynamicMessage gacha(boolean draw, int ticket) {
    var payload = fixture().request("IGJLLHMJKPC");
    fixture().set(payload, "IMKGOKGKDPH", draw);
    fixture().set(payload, "gacha_random", ticket);
    return fixture().operation(5, 51008, "EJLKEGLKGDN", payload.build());
  }

  void ticket(int value) throws Exception {
    var json = raw();
    json.getAsJsonObject("clockPark")
        .getAsJsonObject("active")
        .getAsJsonObject("slot")
        .addProperty("ticket", value);
    balanced.load(json);
  }

  @Test
  void originalSlotRoundFollowsFourNormalsAndCarriesPrefilledCardAndOwnedTicket() throws Exception {
    enter();
    assertEquals(0, fixture().opening.code());
    assertEquals("SLOT", run().getPhase());
    assertEquals(51008, run().getRound());
    assertEquals(4, run().getRoundIndex());
    assertEquals(48, run().getAttributeB());
    var wait = (DynamicMessage) field(fixture().query(), 2);
    var slot = (DynamicMessage) field(wait, 10);
    assertEquals(1, field(slot, 2));
    assertTrue((Integer) field(slot, 5) >= 0 && (Integer) field(slot, 5) < 1000);
    var dice = (DynamicMessage) field(slot, 12);
    assertEquals(List.of(), field(dice, 5));
    var card = (DynamicMessage) ((List<?>) field(dice, 6)).getFirst();
    assertEquals(5150, field(card, 8));
    assertEquals(List.of(1), field(card, 2));
    assertEquals(1, field(card, 3));
    assertTrue(
        fixture().opening.player.getGameplay().getClockPark().valid(ClockParkManager.catalog()));
  }

  @Test
  void originalLotteryRewardUsesAckFieldEightAndCachedReplayAddsNoSecondGain() throws Exception {
    enter();
    assertEquals(0, fixture().opening.code());
    ticket(10);
    var request = gacha(true, 10);
    fixture().submit(request);
    assertEquals(0, fixture().opening.code());
    assertEquals(22, field(fixture().opening.session.response, 8));
    assertEquals(56, run().getAttributeB());
    assertEquals("SLOT", run().getPhase());
    String before = balanced.saved();
    int saves = fixture().opening.saves;
    fixture().submit(request);
    assertEquals(0, fixture().opening.code());
    assertEquals(22, field(fixture().opening.session.response, 8));
    assertEquals(before, balanced.saved());
    assertEquals(saves, fixture().opening.saves);
  }

  @Test
  void ExitBeforeDrawingSkipsGainAndComputesTheOriginalCheckpointFromNormalReceipts()
      throws Exception {
    enter();
    assertEquals(0, fixture().opening.code());
    fixture().submit(gacha(false, slot().get("ticket").getAsInt()));
    assertEquals(0, fixture().opening.code());
    assertEquals("CHECKPOINT", run().getPhase());
    assertEquals(501, run().getCheckpoint());
    assertTrue(run().getCheckpointPassed());
    assertEquals(48, run().getAttributeB());
    assertEquals(0, field(fixture().opening.session.response, 8));
  }

  @ParameterizedTest
  @CsvSource({"0,42,4", "1,32,6", "9,32,6", "10,22,8", "649,22,8", "650,12,12", "999,12,12"})
  void allOriginalWeightBoundariesHaveExactlyOneGainAndThenExit(int ticket, int lottery, int gain)
      throws Exception {
    enter();
    ticket(ticket);
    fixture().submit(gacha(true, ticket));
    assertEquals(0, fixture().opening.code());
    assertEquals(lottery, field(fixture().opening.session.response, 8));
    assertEquals(48 + gain, run().getAttributeB());
    var wait = (DynamicMessage) field(fixture().query(), 2);
    assertEquals(0, field((DynamicMessage) field(wait, 10), 2));
    fixture().submit(gacha(false, ticket));
    assertEquals(0, fixture().opening.code());
    assertEquals("CHECKPOINT", run().getPhase());
    assertEquals(48 + gain, run().getAttributeB());
    String before = balanced.saved();
    fixture().submit(gacha(false, ticket));
    assertEquals(5, fixture().opening.code());
    assertEquals(before, balanced.saved());
  }

  @ParameterizedTest
  @ValueSource(strings = {"last-normal", "draw", "exit"})
  void failedSaveRetainsThePreviousOwnedDiceTicketAndGainBeforeRetry(String phase)
      throws Exception {
    if (phase.equals("last-normal")) {
      balanced.resetToProjectDefault();
      fixture().begin(5);
      for (int i = 0; i < 3; i++) balanced.place(5, 6);
      fixture().overrideDice(6, 6, 6);
    } else {
      enter();
      ticket(650);
    }
    var req =
        phase.equals("last-normal")
            ? fixture()
                .placement(
                    5,
                    run().getRound(),
                    new int[] {6, 6, 6},
                    new int[] {1001, 1007, 1008},
                    false,
                    false)
            : gacha(phase.equals("draw"), 650);
    String before = balanced.saved();
    fixture().opening.failSave = true;
    fixture().submit(req);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(), fixture().opening.code());
    assertEquals(before, balanced.saved());
    fixture().opening.failSave = false;
    fixture().submit(req);
    assertEquals(0, fixture().opening.code());
    assertEquals(phase.equals("exit") ? "CHECKPOINT" : "SLOT", run().getPhase());
    assertEquals(phase.equals("draw") ? 60 : 48, run().getAttributeB());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ticket",
        "foreign-script",
        "foreign-round",
        "uint-alias",
        "duplicate-ticket",
        "bool-alias",
        "merged-oneof",
        "truncated",
        "unknown"
      })
  void unsupportedNativeOperationsDoNotReplaceAnOwnedTicketOrAddRewards(String kind)
      throws Exception {
    enter();
    ticket(10);
    var req = gacha(true, 10).toBuilder();
    byte[] bytes = req.build().toByteArray();
    switch (kind) {
      case "ticket" -> bytes = gacha(true, 11).toByteArray();
      case "foreign-script" -> {
        fixture().set(req, "script_id", 2);
        bytes = req.build().toByteArray();
      }
      case "foreign-round" -> {
        fixture().set(req, "JGPHDBDHKKO", 51009);
        bytes = req.build().toByteArray();
      }
      case "uint-alias" -> bytes = wire(4294967306L, 1, false, false, false);
      case "duplicate-ticket" -> bytes = wire(10, 1, true, false, false);
      case "bool-alias" -> bytes = wire(10, 2, false, false, false);
      case "merged-oneof" -> bytes = wire(10, 1, false, true, false);
      case "unknown" -> bytes = wire(10, 1, false, false, true);
      case "truncated" -> bytes = Arrays.copyOf(bytes, bytes.length - 1);
    }
    String before = balanced.saved();
    int saves = fixture().opening.saves;
    fixture().opening.handle("ClockParkHandleWaitOperationCsReq", bytes);
    assertEquals(5, fixture().opening.code());
    assertEquals(before, balanced.saved());
    assertEquals(saves, fixture().opening.saves);
  }

  byte[] wire(long ticket, long bool, boolean duplicate, boolean merged, boolean unknown)
      throws Exception {
    var payload = new java.io.ByteArrayOutputStream();
    var p = com.google.protobuf.CodedOutputStream.newInstance(payload);
    p.writeUInt64(8, bool);
    p.writeUInt64(9, ticket);
    if (duplicate) p.writeUInt64(9, ticket);
    if (unknown) p.writeUInt32(1, 1);
    p.flush();
    var raw = new java.io.ByteArrayOutputStream();
    var out = com.google.protobuf.CodedOutputStream.newInstance(raw);
    out.writeUInt32(2, 5);
    out.writeUInt32(11, 51008);
    out.writeByteArray(8, payload.toByteArray());
    if (merged) out.writeByteArray(9, new byte[0]);
    out.flush();
    return raw.toByteArray();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ticket", "lottery", "source", "rule", "card", "face", "round", "exited", "gain", "missing"
      })
  void invalidSavedSlotProofFailsWithoutReadTimeRepair(String kind) throws Exception {
    enter();
    ticket(10);
    fixture().submit(gacha(true, 10));
    var raw = raw();
    var active = raw.getAsJsonObject("clockPark").getAsJsonObject("active");
    var slot = active.getAsJsonObject("slot");
    switch (kind) {
      case "ticket" -> slot.addProperty("ticket", 650);
      case "lottery" -> slot.addProperty("lotteryId", 12);
      case "source" -> slot.addProperty("source", "foreign");
      case "rule" -> slot.addProperty("rule", "unverified");
      case "card" -> slot.addProperty("card", 5250);
      case "face" -> slot.addProperty("face", 0);
      case "round" -> slot.addProperty("round", 52008);
      case "exited" -> slot.addProperty("exited", true);
      case "gain" -> active.addProperty("attributeB", 100);
      case "missing" -> active.remove("slot");
    }
    balanced.load(raw);
    String before = balanced.saved();
    int saves = fixture().opening.saves;
    fixture().opening.handle("ClockParkGetOngoingScriptInfoCsReq", new byte[0]);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR.getVal(), fixture().opening.code());
    assertEquals(before, balanced.saved());
    assertEquals(saves, fixture().opening.saves);
  }

  @Test
  void SixConcurrentRetriesReturnTheSameSavedLotteryAndCommitOneGain() throws Exception {
    enter();
    ticket(650);
    int saves = fixture().opening.saves;
    var manager = new ClockParkManager(fixture().opening.player);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<java.util.concurrent.Callable<ClockParkManager.Result>>();
      for (int i = 0; i < 6; i++) calls.add(() -> manager.slotOperation(5, 51008, true, 650));
      for (var reply : pool.invokeAll(calls)) {
        assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, reply.get().code());
        assertEquals(12, reply.get().lotteryId());
      }
    }
    assertEquals(saves + 1, fixture().opening.saves);
    assertEquals(60, run().getAttributeB());
  }

  @ParameterizedTest
  @ValueSource(strings = {"DICE", "PENDING", "PLAYED", "SKIPPED", "CHECKPOINT"})
  void ActualPlayerMorphiaReadsTwiceWithoutChangingSavedSlotOrItsNextNativeResponse(String phase)
      throws Exception {
    enter();
    ticket(650);
    if (phase.equals("DICE")) {
      balanced.resetToProjectDefault();
      fixture().begin(5);
      balanced.place(5, 6);
    } else if (phase.equals("PLAYED") || phase.equals("CHECKPOINT"))
      fixture().submit(gacha(true, 650));
    if (phase.equals("CHECKPOINT") || phase.equals("SKIPPED")) fixture().submit(gacha(false, 650));
    String before = balanced.saved();
    var expected = fixture().query();
    var opening = fixture().opening;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-slot-fixture");
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
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(before, opening.gson.toJson(restored.getGameplay()));
        opening.replaceGameplay(restored.getGameplay());
        assertEquals(expected, fixture().query());
        assertEquals(before, balanced.saved());
      }
    } finally {
      server.shutdownNow();
    }
    balanced.load(JsonParser.parseString(before).getAsJsonObject());
    if (phase.equals("PLAYED")) {
      int saves = opening.saves;
      fixture().submit(gacha(true, 650));
      assertEquals(12, field(opening.session.response, 8));
      assertEquals(saves, opening.saves);
    }
  }

  @Test
  void AFalseOriginalSumCheckpointCanStillFailAfterSkippingTheSlot() throws Exception {
    balanced.resetToProjectDefault();
    fixture().begin(5);
    for (int i = 0; i < 4; i++) balanced.place(5, 1);
    assertEquals("SLOT", run().getPhase());
    fixture().submit(gacha(false, slot().get("ticket").getAsInt()));
    assertEquals(0, fixture().opening.code());
    assertFalse(run().getCheckpointPassed());
    assertEquals(501, run().getCheckpoint());
    var result = new ClockParkManager(fixture().opening.player).finishCheckpoint(5, 51008);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, result.code());
    assertTrue(result.failed());
    assertNull(run());
    assertEquals(
        0,
        fixture().opening.player.getGameplay().getClockPark().progress(ClockParkManager.catalog()));
    assertTrue(
        fixture().opening.player.getGameplay().getClockPark().valid(ClockParkManager.catalog()));
  }

  @Test
  void MissingLotterySourceDoesNotConsumeTheLastNormalDiceOrSkipItsGame() throws Exception {
    balanced.resetToProjectDefault();
    fixture().begin(5);
    for (int i = 0; i < 3; i++) balanced.place(5, 6);
    fixture().overrideDice(6, 6, 6);
    Files.delete(root.resolve("ExcelOutput/ClockParkLottery.json"));
    String before = balanced.saved();
    int saves = fixture().opening.saves;
    fixture()
        .submit(
            fixture()
                .placement(
                    5,
                    run().getRound(),
                    new int[] {6, 6, 6},
                    new int[] {1001, 1007, 1008},
                    false,
                    false));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.FEATURE_SWITCH_CLOSED.getVal(),
        fixture().opening.code());
    assertEquals(before, balanced.saved());
    assertEquals(saves, fixture().opening.saves);
  }

  @Test
  void SourceTamperingAfterACommittedDrawDoesNotPermitASecondGainOrReadRepair() throws Exception {
    enter();
    ticket(10);
    fixture().submit(gacha(true, 10));
    Files.writeString(root.resolve("ExcelOutput/ClockParkLottery.json"), "[]");
    String before = balanced.saved();
    int saves = fixture().opening.saves;
    fixture().submit(gacha(true, 10));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR.getVal(), fixture().opening.code());
    assertEquals(before, balanced.saved());
    assertEquals(saves, fixture().opening.saves);
  }

  @Test
  void BothOriginalLfAndOperatorCrlfBytesHaveTheSameLotteryIdentityAndBAttributeCap()
      throws Exception {
    var path = root.resolve("ExcelOutput/ClockParkLottery.json");
    String original = Files.readString(path);
    var lf = ClockParkSlots.load(root);
    assertNotNull(lf);
    Files.writeString(path, original.replace("\r\n", "\n").replace("\n", "\r\n"));
    var crlf = ClockParkSlots.load(root);
    assertNotNull(crlf);
    assertEquals(lf.fingerprint(), crlf.fingerprint());
    assertEquals(100, crlf.attributeB(95, 650));
    assertEquals(99, crlf.attributeB(95, 0));
  }

  @Test
  void AnExpiredWindowBlocksNewDrawAndExitButAllowsReadingAnAlreadySavedAck() throws Exception {
    enter();
    ticket(10);
    GameplayDefinitions.windows.clear();
    String before = balanced.saved();
    fixture().submit(gacha(true, 10));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.FUNC_CHECK_FAILED.getVal(), fixture().opening.code());
    fixture().submit(gacha(false, 10));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.FUNC_CHECK_FAILED.getVal(), fixture().opening.code());
    assertEquals(before, balanced.saved());
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(50012, 5001202, 0, Long.MAX_VALUE));
    fixture().submit(gacha(true, 10));
    assertEquals(0, fixture().opening.code());
    before = balanced.saved();
    int saves = fixture().opening.saves;
    GameplayDefinitions.windows.clear();
    fixture().submit(gacha(true, 10));
    assertEquals(0, fixture().opening.code());
    assertEquals(22, field(fixture().opening.session.response, 8));
    assertEquals(before, balanced.saved());
    assertEquals(saves, fixture().opening.saves);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC,
        new ClockParkManager(fixture().opening.player).quit(5).code());
  }
}
