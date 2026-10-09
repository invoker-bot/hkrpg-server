package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.hkrpg.server.gameplay.ClockParkManager;
import org.hkrpg.server.gameplay.ClockParkState;
import org.hkrpg.server.gameplay.GameplayState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reaching a leaf must wait for its original ending story before earning an ending. */
class ClockParkEnding46Test {
  @TempDir Path root;
  final ClockParkBranch46Test branch = new ClockParkBranch46Test();
  RewardExcel beforeReward;
  ItemExcel beforeItem;
  int delivered;
  GameItem owned;
  boolean rejectDelivery, failItemSave;
  int itemWrites;
  final java.util.List<String> sent = new java.util.ArrayList<>();
  DynamicMessage notification;
  final Gameplay46HandlerTest.Session completion =
      new Gameplay46HandlerTest.Session() {
        public Player getPlayer() {
          return fixture().opening.player;
        }

        public void sendClientPacket(String name, byte[] bytes) {
          sent.add(name);
          if (name.equals("ClockParkFinishScriptScNotify")) {
            try {
              notification = getProtocol().clientSchema().parse(name, bytes);
            } catch (Exception invalid) {
              throw new AssertionError(invalid);
            }
          } else super.sendClientPacket(name, bytes);
        }
      };

  @BeforeEach
  void prepare() throws Exception {
    branch.root = root;
    branch.prepare();
    beforeReward = GameData.getRewardExcelMap().get(8008001);
    beforeItem = GameData.getItemExcelMap().get(140484);
    var sources =
        JsonParser.parseString(
                new String(
                    getClass()
                        .getResourceAsStream("/fixtures/clock-park-ending-reward-4.6.json")
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var entry : sources.getAsJsonObject("files").entrySet()) {
      byte[] compressed =
          java.util.Base64.getDecoder()
              .decode(entry.getValue().getAsJsonObject().get("gzipBase64").getAsString());
      try (var input =
          new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
        Files.write(root.resolve(entry.getKey()), input.readAllBytes());
      }
    }
    var rows = sources.getAsJsonObject("rows");
    var reward =
        ResourceLoader.getGson()
            .fromJson(rows.get("ExcelOutput/RewardData.json"), RewardExcel.class);
    reward.onLoad();
    GameData.getRewardExcelMap().add(reward);
    var rawItem = rows.getAsJsonObject("ExcelOutput/ItemConfig.json").deepCopy();
    rawItem.addProperty("ItemName", rawItem.getAsJsonObject("ItemName").get("Hash").getAsLong());
    GameData.getItemExcelMap().add(ResourceLoader.getGson().fromJson(rawItem, ItemExcel.class));
    var inventory =
        new Inventory(fixture().opening.player) {
          public GameItem getMaterialByItemId(int id) {
            return id == 140484 ? owned : super.getMaterialByItemId(id);
          }

          public GrantResult grantItems(ItemParamMap amounts, boolean hint) {
            // Isolate item storage for unit tests; the native H2 probe must verify real delivery.
            assertEquals(1, amounts.get(140484));
            assertEquals(1, amounts.size());
            delivered++;
            if (rejectDelivery) return new GrantResult(List.of(), false);
            owned =
                new GameItem(140484, 1) {
                  public void save() {
                    if (failItemSave)
                      throw new IllegalStateException("fixture item save interruption");
                    itemWrites++;
                  }
                };
            return new GrantResult(List.of(owned), true);
          }
        };
    var field = Player.class.getDeclaredField("inventory");
    field.setAccessible(true);
    field.set(fixture().opening.player, inventory);
  }

  @AfterEach
  void restore() throws Exception {
    branch.restore();
    GameData.getRewardExcelMap().keySet().remove(8008001);
    if (beforeReward != null) GameData.getRewardExcelMap().add(beforeReward);
    GameData.getItemExcelMap().keySet().remove(140484);
    if (beforeItem != null) GameData.getItemExcelMap().add(beforeItem);
  }

  ClockParkDice46Test fixture() {
    return branch.fixture();
  }

  ClockParkState.Run run() {
    return branch.run();
  }

  void secondCheckpoint() throws Exception {
    branch.branch(1);
    fixture().submit(branch.selection(1));
    assertEquals(0, fixture().opening.code());
    fixture().finishStory(1, run().getRound());
    assertEquals(0, fixture().opening.code());
    while (run().getPhase().equals("DICE")) {
      fixture().overrideDice(6, 6, 6);
      fixture().submit(fixture().placement(1, run().getRound()));
      assertEquals(0, fixture().opening.code());
    }
    assertEquals(121, run().getCheckpoint());
    assertTrue(run().getCheckpointPassed());
  }

  void endingStory() throws Exception {
    secondCheckpoint();
    branch.confirm(1);
    assertEquals(0, fixture().opening.code());
    fixture().submit(branch.selection(1, 1102, run().getDice().getFirst()));
    assertEquals(0, fixture().opening.code());
  }

  void completeStory() throws Exception {
    var request =
        fixture()
            .operation(
                1, run().getRound(), "IKNEKEJEGLM", fixture().request("NKOCCGLHOML").build());
    assertTrue(
        VersionedPacketHandler.handle(
            completion,
            completion.getProtocol().clientCommand("ClockParkHandleWaitOperationCsReq"),
            request.toByteArray()));
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4})
  void interruptionsAtReservationLedgerAndFinalCompletionRetryWithoutAnotherItem(int stage)
      throws Exception {
    endingStory();
    fixture().opening.failSaveAt = fixture().opening.saves + stage;
    completeStory();
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture().number(completion.response, "retcode"));
    assertEquals("FINISHING", run().getPhase());
    assertNull(notification);
    assertEquals(
        List.of(101, 102), fixture().opening.player.getGameplay().getClockPark().reached(1));
    int credited = owned == null ? 0 : owned.getCount();
    assertEquals(stage == 2 ? 0 : 1, credited);
    completeStory();
    assertEquals(0, fixture().number(completion.response, "retcode"));
    assertNull(run());
    assertEquals(1, delivered);
    assertEquals(1, owned.getCount());
    completion.request("ClockParkGetInfoCsReq");
    assertEquals(2000, fixture().number(completion.response, "progress"));
  }

  @Test
  void reservedButUndeliveredUniqueItemCanBeRetriedFromTheSameOriginalStoryWait() throws Exception {
    endingStory();
    rejectDelivery = true;
    completeStory();
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(), fixture().number(completion.response, "retcode"));
    assertEquals("FINISHING", run().getPhase());
    assertNull(owned);
    assertNull(notification);
    var wait = fixture().nested(fixture().query(), "HGDLJDAFHLI");
    assertTrue(wait.hasField(wait.getDescriptorForType().findFieldByName("ICPMABKBLCD")));
    rejectDelivery = false;
    completeStory();
    assertEquals(0, fixture().number(completion.response, "retcode"));
    assertEquals(2, delivered);
    assertEquals(1, owned.getCount());
    assertNull(run());
  }

  @Test
  void persistedReservationAndOneUniqueItemReconcileWithoutAddingAnotherCopy() throws Exception {
    endingStory();
    fixture().opening.failSaveAt = fixture().opening.saves + 3;
    completeStory();
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture().number(completion.response, "retcode"));
    var opening = fixture().opening;
    var json = JsonParser.parseString(branch.saved()).getAsJsonObject();
    // This is the earlier durable ledger layout; real H2 recovery must verify actual item storage.
    json.getAsJsonObject("rewards")
        .getAsJsonObject("clock-park-ending:1:103")
        .addProperty("granted", false);
    opening.replaceGameplay(opening.gson.fromJson(json, GameplayState.class));
    failItemSave = true;
    completeStory();
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture().number(completion.response, "retcode"));
    assertEquals(1, delivered);
    assertEquals(1, owned.getCount());
    assertNull(notification);
    failItemSave = false;
    completeStory();
    assertEquals(0, fixture().number(completion.response, "retcode"));
    assertEquals(1, delivered);
    assertEquals(1, itemWrites);
    assertNull(run());
  }

  @Test
  void preexistingUniqueItemCannotBeUsedAsEvidenceOrOverwrittenAtFirstCompletion()
      throws Exception {
    endingStory();
    owned = new GameItem(140484, 1);
    String before = branch.saved();
    completeStory();
    assertEquals(
        Retcode.ITEM_REWARD_EXCEED_LIMIT.getVal(),
        fixture().number(completion.response, "retcode"));
    assertEquals(before, branch.saved());
    assertEquals(0, delivered);
    assertEquals(1, owned.getCount());
    assertNull(notification);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 2})
  void corruptUniqueItemCountsCannotEarnOrRepairAnEnding(int count) throws Exception {
    endingStory();
    owned = new GameItem(140484, count);
    owned.setCount(count); // A stored corrupt count must bypass the constructor's pile limit.
    String before = branch.saved();
    completeStory();
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(), fixture().number(completion.response, "retcode"));
    assertEquals(before, branch.saved());
    assertEquals(0, delivered);
    assertEquals(count, owned.getCount());
    assertNull(notification);
  }

  @ParameterizedTest
  @ValueSource(strings = {"RewardData", "ItemConfig"})
  void changedOriginalRewardSourcesCannotConsumeTheEndingStory(String table) throws Exception {
    endingStory();
    Files.writeString(root.resolve("ExcelOutput/" + table + ".json"), "[]");
    String before = branch.saved();
    completeStory();
    assertEquals(
        Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), fixture().number(completion.response, "retcode"));
    assertEquals(before, branch.saved());
    assertEquals(0, delivered);
    assertNull(notification);
  }

  @Test
  void pendingRewardCannotBeAbandonedByQuit() throws Exception {
    endingStory();
    rejectDelivery = true;
    completeStory();
    String before = branch.saved();
    var result = new ClockParkManager(fixture().opening.player).quit(1);
    assertEquals(Retcode.SYSTEM_BUSY, result.code());
    assertEquals(before, branch.saved());
    rejectDelivery = false;
    completeStory();
    assertEquals(0, fixture().number(completion.response, "retcode"));
    assertEquals(1, owned.getCount());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 2})
  void grantedReceiptRequiresTheActualUniqueItemOnQuery(int count) throws Exception {
    endingStory();
    completeStory();
    if (count == -1) owned = null;
    else owned.setCount(count);
    String before = branch.saved();
    completion.request("ClockParkGetInfoCsReq");
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(), fixture().number(completion.response, "retcode"));
    assertEquals(before, branch.saved());
    assertEquals(1, delivered);
  }

  @ParameterizedTest
  @ValueSource(strings = {"RewardData", "ItemConfig"})
  void completedEndingRequiresItsOriginalRewardSourcesOnQuery(String table) throws Exception {
    endingStory();
    completeStory();
    Files.writeString(root.resolve("ExcelOutput/" + table + ".json"), "[]");
    String before = branch.saved();
    completion.request("ClockParkGetInfoCsReq");
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(), fixture().number(completion.response, "retcode"));
    assertEquals(before, branch.saved());
    assertEquals(1, owned.getCount());
  }

  @Test
  void replayedOriginalStoryAcknowledgmentCannotSendAnotherResultOrPayAgain() throws Exception {
    endingStory();
    var request =
        fixture()
            .operation(
                1, run().getRound(), "IKNEKEJEGLM", fixture().request("NKOCCGLHOML").build());
    completeStory();
    assertEquals(0, fixture().number(completion.response, "retcode"));
    String before = branch.saved();
    notification = null;
    sent.clear();
    VersionedPacketHandler.handle(
        completion,
        completion.getProtocol().clientCommand("ClockParkHandleWaitOperationCsReq"),
        request.toByteArray());
    assertEquals(5, fixture().number(completion.response, "retcode"));
    assertEquals(before, branch.saved());
    assertEquals(1, delivered);
    assertNull(notification);
    assertEquals(List.of("ClockParkHandleWaitOperationScRsp"), sent);
  }

  @Test
  void sixConcurrentStoryCompletionsProduceOneRewardAndOneEnding() throws Exception {
    endingStory();
    var manager = new ClockParkManager(fixture().opening.player);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new java.util.ArrayList<java.util.concurrent.Callable<ClockParkManager.Result>>();
      for (int i = 0; i < 6; i++) calls.add(() -> manager.finishStory(1, 0));
      int passed = 0;
      for (var result : pool.invokeAll(calls)) {
        if (result.get().code() == Retcode.SUCC) passed++;
        else assertEquals(Retcode.REQ_PARA_INVALID, result.get().code());
      }
      assertEquals(1, passed);
    }
    assertEquals(1, delivered);
    assertNull(run());
    assertEquals(
        List.of(101, 102, 103), fixture().opening.player.getGameplay().getClockPark().reached(1));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrong-script",
        "wrong-round",
        "checkpoint",
        "branch",
        "unknown-story",
        "truncated"
      })
  void invalidRequestsCannotConsumeAnEndingStoryOrPayItsItem(String kind) throws Exception {
    endingStory();
    var request =
        fixture()
            .operation(
                kind.equals("wrong-script") ? 2 : 1,
                kind.equals("wrong-round") ? 10301 : 0,
                kind.equals("checkpoint") ? "MLHNHAFPLKD" : "IKNEKEJEGLM",
                fixture()
                    .request(kind.equals("checkpoint") ? "MOEMKBLCKKO" : "NKOCCGLHOML")
                    .build());
    if (kind.equals("branch"))
      request = fixture().operation(1, 0, "JFAJEBGKPEH", fixture().request("KJAIAGDNPGC").build());
    if (kind.equals("unknown-story")) {
      var story = fixture().request("NKOCCGLHOML");
      story.setUnknownFields(
          com.google.protobuf.UnknownFieldSet.newBuilder()
              .addField(
                  1, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
              .build());
      request = fixture().operation(1, 0, "IKNEKEJEGLM", story.build());
    }
    byte[] raw = request.toByteArray();
    if (kind.equals("truncated")) raw = java.util.Arrays.copyOf(raw, raw.length - 1);
    String before = branch.saved();
    VersionedPacketHandler.handle(
        completion,
        completion.getProtocol().clientCommand("ClockParkHandleWaitOperationCsReq"),
        raw);
    assertEquals(5, fixture().number(completion.response, "retcode"));
    assertEquals(before, branch.saved());
    assertEquals(0, delivered);
    assertNull(notification);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "ungranted",
        "wrong-reward",
        "foreign-key",
        "source",
        "attribute",
        "history"
      })
  void damagedCompletedEndingProofOrLedgerIsRejectedWithoutRepair(String kind) throws Exception {
    endingStory();
    completeStory();
    var opening = fixture().opening;
    var json = JsonParser.parseString(branch.saved()).getAsJsonObject();
    var rewards = json.getAsJsonObject("rewards");
    var receipt = rewards.getAsJsonObject("clock-park-ending:1:103");
    var proof =
        json.getAsJsonObject("clockPark")
            .getAsJsonObject("reachedProofs")
            .getAsJsonObject("1")
            .getAsJsonObject("103");
    switch (kind) {
      case "missing" -> rewards.remove("clock-park-ending:1:103");
      case "ungranted" -> receipt.addProperty("granted", false);
      case "wrong-reward" -> receipt.addProperty("rewardId", 8008002);
      case "foreign-key" -> rewards.add("clock-park-ending:1:105", receipt.deepCopy());
      case "source" -> proof.addProperty("endingSource", "changed");
      case "attribute" -> proof.addProperty("attributeA", 49);
      case "history" -> proof.add("history", new JsonArray());
      default -> fail(kind);
    }
    opening.replaceGameplay(opening.gson.fromJson(json, GameplayState.class));
    String before = branch.saved();
    completion.request("ClockParkGetInfoCsReq");
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(), fixture().number(completion.response, "retcode"));
    assertEquals(before, branch.saved());
    assertEquals(1, delivered);
    assertEquals(1, owned.getCount());
  }

  @ParameterizedTest
  @ValueSource(strings = {"BRANCH", "STORY", "FINISHING", "FINISHED"})
  void actualPlayerMorphiaReloadPreservesEndingStoryOrCompletedReceipt(String phase)
      throws Exception {
    if (phase.equals("BRANCH")) {
      secondCheckpoint();
      branch.confirm(1);
    } else endingStory();
    if (phase.equals("FINISHING")) {
      rejectDelivery = true;
      completeStory();
      assertEquals(
          Retcode.PLAYER_DATA_ERROR.getVal(), fixture().number(completion.response, "retcode"));
    }
    if (phase.equals("FINISHED")) {
      completeStory();
      assertEquals(0, fixture().number(completion.response, "retcode"));
    }
    var opening = fixture().opening;
    var query = fixture().query();
    String before = branch.saved();
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-ending-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.set(document, 199987);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, opening.player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored = java.util.Objects.requireNonNull(store.find(Player.class).first());
        assertEquals(before, opening.gson.toJson(restored.getGameplay()));
        opening.replaceGameplay(restored.getGameplay());
        assertEquals(query, fixture().query());
        assertEquals(before, branch.saved());
      }
    } finally {
      server.shutdownNow();
    }
    opening.replaceGameplay(opening.gson.fromJson(before, GameplayState.class));
    assertEquals(query, fixture().query());
  }

  @Test
  void originalEndingStoryCompletionEarnsOneOriginalRewardAndSendsTheFinalNativeResult()
      throws Exception {
    endingStory();
    completeStory();
    assertEquals(0, fixture().number(completion.response, "retcode"));
    assertNull(run());
    assertEquals(1, delivered);
    assertEquals(140484, owned.getItemId());
    assertEquals(1, owned.getCount());
    assertEquals(
        List.of(101, 102, 103), fixture().opening.player.getGameplay().getClockPark().reached(1));
    assertEquals(
        List.of("ClockParkHandleWaitOperationScRsp", "ClockParkFinishScriptScNotify"), sent);
    assertEquals(
        5,
        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                fixture().value(completion.response, "LNPGHCLDOKG"))
            .getNumber());
    var result = fixture().nested(notification, "NLEOMLNOKCC");
    assertEquals(103, fixture().number(result, "DCAHJELNJNP"));
    assertEquals(1, fixture().number(result, "script_id"));
    assertEquals(2000, fixture().number(result, "progress"));
    assertEquals(true, fixture().value(result, "DMNEACGEHEC"));
    assertEquals(false, fixture().value(result, "HGPKMHFPMBJ"));
    var reward = fixture().nested(result, "reward");
    var row = (DynamicMessage) ((List<?>) fixture().value(reward, "item_list")).getFirst();
    assertEquals(140484, fixture().number(row, "item_id"));
    assertEquals(1, fixture().number(row, "num"));
    completion.request("ClockParkGetInfoCsReq");
    assertEquals(2000, fixture().number(completion.response, "progress"));
  }

  @Test
  void repeatingAnActuallyEarnedEndingCannotRegrantItsUniqueItemOrProgress() throws Exception {
    endingStory();
    completeStory();
    assertEquals(0, fixture().number(completion.response, "retcode"));
    endingStory();
    sent.clear();
    notification = null;
    completeStory();
    assertEquals(0, fixture().number(completion.response, "retcode"));
    assertEquals(1, delivered);
    var result = fixture().nested(notification, "NLEOMLNOKCC");
    assertEquals(0, fixture().number(result, "progress"));
    assertEquals(false, fixture().value(result, "DMNEACGEHEC"));
    assertTrue(
        ((List<?>) fixture().value(fixture().nested(result, "reward"), "item_list")).isEmpty());
    completion.request("ClockParkGetInfoCsReq");
    assertEquals(2000, fixture().number(completion.response, "progress"));
  }

  @Test
  void failedInitialEndingSavePreservesStoryAndDoesNotPayOrUnlockTheEnding() throws Exception {
    endingStory();
    String before = branch.saved();
    fixture().opening.failSave = true;
    completeStory();
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture().number(completion.response, "retcode"));
    assertEquals(before, branch.saved());
    assertEquals(0, delivered);
    assertNull(notification);
    fixture().opening.failSave = false;
    completeStory();
    assertEquals(0, fixture().number(completion.response, "retcode"));
    assertEquals(1, delivered);
  }

  @Test
  void secondOriginalCheckpointOffersItsOwnEndingCardWithoutInventingAnEnding() throws Exception {
    secondCheckpoint();
    branch.confirm(1);
    assertEquals(0, fixture().opening.code());
    assertEquals("BRANCH", run().getPhase());
    assertEquals(10207, run().getRound());
    var menu =
        fixture()
            .nested(
                fixture().nested(fixture().nested(fixture().query(), "HGDLJDAFHLI"), "ABIPNABIDPE"),
                "CENIOBBIJMF");
    var rows = (List<?>) fixture().value(menu, "LONFKFCFDND");
    assertEquals(1, rows.size());
    assertEquals(1102, fixture().number((DynamicMessage) rows.getFirst(), "card_id"));
    assertEquals(
        List.of(101, 102), fixture().opening.player.getGameplay().getClockPark().reached(1));
  }

  @Test
  void selectingTheEndingCardPreservesAttributesAndWaitsForOriginalStory10301() throws Exception {
    secondCheckpoint();
    branch.confirm(1);
    assertEquals(0, fixture().opening.code());
    int a = run().getAttributeA(), b = run().getAttributeB();
    String uuid = run().getTransaction();
    fixture().submit(branch.selection(1, 1102, run().getDice().getFirst()));
    assertEquals(0, fixture().opening.code());
    assertEquals(103, run().getChapter());
    assertEquals(10301, run().getStory());
    assertEquals("STORY", run().getPhase());
    assertEquals(a, run().getAttributeA());
    assertEquals(b, run().getAttributeB());
    assertEquals(uuid, run().getTransaction());
    assertTrue(
        fixture()
            .nested(fixture().query(), "HGDLJDAFHLI")
            .hasField(
                fixture()
                    .request("MDDIKNGBPPL")
                    .getDescriptorForType()
                    .findFieldByName("ICPMABKBLCD")));
    assertEquals(List.of(101, 102, 103), fixture().value(fixture().query(), "FKNBOIIMFLD"));
    assertEquals(
        List.of(101, 102), fixture().opening.player.getGameplay().getClockPark().reached(1));
  }
}
