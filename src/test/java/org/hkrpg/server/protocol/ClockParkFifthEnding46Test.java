package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClockParkFifthEnding46Test {
  @TempDir Path root;
  final ClockParkSlotChapters46Test chapters = new ClockParkSlotChapters46Test();
  final Map<Integer, RewardExcel> oldRewards = new HashMap<>();
  ItemExcel oldItem;
  GameItem owned;
  int deliveries, itemWrites;
  boolean rejectDelivery, failItemSave;

  final List<String> sent = new ArrayList<>();
  com.google.protobuf.DynamicMessage notification;
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
    chapters.root = root;
    chapters.prepare();
    var source =
        JsonParser.parseString(
                new String(
                    getClass()
                        .getResourceAsStream("/fixtures/clock-park-ending-reward-4.6.json")
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var entry : source.getAsJsonObject("files").entrySet()) {
      var bytes =
          Base64.getDecoder()
              .decode(entry.getValue().getAsJsonObject().get("gzipBase64").getAsString());
      try (var input = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
        Files.write(root.resolve(entry.getKey()), input.readAllBytes());
      }
    }
    var rewards =
        JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/RewardData.json")))
            .getAsJsonArray();
    for (int id : List.of(100, 8008006)) {
      oldRewards.put(id, GameData.getRewardExcelMap().get(id));
      var row =
          rewards.asList().stream()
              .map(JsonElement::getAsJsonObject)
              .filter(v -> v.get("RewardID").getAsInt() == id)
              .findFirst()
              .orElseThrow();
      var value = ResourceLoader.getGson().fromJson(row, RewardExcel.class);
      value.onLoad();
      GameData.getRewardExcelMap().add(value);
    }
    oldItem = GameData.getItemExcelMap().get(140485);
    var row =
        JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/ItemConfig.json")))
            .getAsJsonArray()
            .asList()
            .stream()
            .map(JsonElement::getAsJsonObject)
            .filter(v -> v.get("ID").getAsInt() == 140485)
            .findFirst()
            .orElseThrow()
            .deepCopy();
    row.addProperty("ItemName", row.getAsJsonObject("ItemName").get("Hash").getAsLong());
    GameData.getItemExcelMap().add(ResourceLoader.getGson().fromJson(row, ItemExcel.class));
    var inventory =
        new Inventory(fixture().opening.player) {
          public GameItem getMaterialByItemId(int id) {
            return id == 140485 ? owned : super.getMaterialByItemId(id);
          }

          public GrantResult grantItems(ItemParamMap amounts, boolean hint) {
            assertEquals(1, amounts.size());
            assertEquals(1, amounts.get(140485));
            deliveries++;
            if (rejectDelivery) return new GrantResult(List.of(), false);
            owned =
                new GameItem(140485, 1) {
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
    chapters.slot.balanced.resetToProjectDefault();
  }

  @AfterEach
  void restore() throws Exception {
    chapters.restore();
    for (var entry : oldRewards.entrySet()) {
      GameData.getRewardExcelMap().keySet().remove(entry.getKey());
      if (entry.getValue() != null) GameData.getRewardExcelMap().add(entry.getValue());
    }
    GameData.getItemExcelMap().keySet().remove(140485);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
  }

  ClockParkDice46Test fixture() {
    return chapters.fixture();
  }

  ClockParkState.Run run() {
    return chapters.run();
  }

  ClockParkManager manager() {
    return chapters.manager();
  }

  void path(boolean good) throws Exception {
    fixture().begin(5);
    for (int chapter : List.of(510, 520)) {
      assertEquals(chapter, run().getChapter());
      chapters.ordinary(4);
      chapters.slot.ticket(650);
      chapters.gacha(true);
      chapters.gacha(false);
      assertTrue(run().getCheckpointPassed());
      assertEquals(Retcode.SUCC, manager().finishCheckpoint(5, run().getRound()).code());
      if (chapter == 520) chapters.face(good ? 6 : 1);
      chapters.next(chapter == 510 ? 5119 : 5221);
    }
    assertEquals(good ? 530 : 540, run().getChapter());
    chapters.ordinary(5);
    chapters.slot.ticket(650);
    chapters.gacha(true);
    chapters.gacha(false);
    assertTrue(run().getCheckpointPassed());
    assertEquals(Retcode.SUCC, manager().finishCheckpoint(5, run().getRound()).code());
    assertEquals(
        Retcode.SUCC,
        manager()
            .selectBranch(5, run().getRound(), good ? 5327 : 5427, run().getDice().getFirst())
            .code());
    assertEquals(good ? 550 : 560, run().getChapter());
    assertEquals(good ? 50501 : 50601, run().getStory());
    assertEquals("STORY", run().getPhase());
  }

  ClockParkManager.Result finish() {
    return manager().finishStory(5, 0);
  }

  String saved() {
    return chapters.slot.balanced.saved();
  }

  void load(JsonObject raw) throws Exception {
    fixture().opening.replaceGameplay(fixture().opening.gson.fromJson(raw, GameplayState.class));
  }

  void completeStory() throws Exception {
    var request =
        fixture().operation(5, 0, "IKNEKEJEGLM", fixture().request("NKOCCGLHOML").build());
    assertTrue(
        VersionedPacketHandler.handle(
            completion,
            completion.getProtocol().clientCommand("ClockParkHandleWaitOperationCsReq"),
            request.toByteArray()));
  }

  int code() {
    return fixture().number(completion.response, "retcode");
  }

  com.google.protobuf.DynamicMessage notified() {
    return fixture().nested(notification, "NLEOMLNOKCC");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void nativeEndingNotificationMatchesTheAuthoredOutcomeAndActualAward(boolean good)
      throws Exception {
    path(good);
    completeStory();
    assertEquals(0, code());
    assertNull(run());
    assertEquals(
        List.of("ClockParkHandleWaitOperationScRsp", "ClockParkFinishScriptScNotify"), sent);
    assertEquals(
        5,
        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                fixture().value(completion.response, "LNPGHCLDOKG"))
            .getNumber());
    var result = notified();
    assertEquals(good ? 550 : 560, fixture().number(result, "DCAHJELNJNP"));
    assertEquals(5, fixture().number(result, "script_id"));
    assertEquals(good ? 2000 : 0, fixture().number(result, "progress"));
    assertEquals(true, fixture().value(result, "DMNEACGEHEC"));
    assertEquals(false, fixture().value(result, "HGPKMHFPMBJ"));
    var items = (List<?>) fixture().value(fixture().nested(result, "reward"), "item_list");
    assertEquals(good ? 1 : 0, items.size());
    if (good) {
      var item = (com.google.protobuf.DynamicMessage) items.getFirst();
      assertEquals(140485, fixture().number(item, "item_id"));
      assertEquals(1, fixture().number(item, "num"));
    }
    completion.request("ClockParkGetInfoCsReq");
    assertEquals(good ? 2000 : 0, fixture().number(completion.response, "progress"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void repeatActualWholePathDoesNotPayOrCountTheEndingTwice(boolean good) throws Exception {
    path(good);
    completeStory();
    assertEquals(0, code());
    var item = owned;
    path(good);
    sent.clear();
    notification = null;
    completeStory();
    assertEquals(0, code());
    assertEquals(false, fixture().value(notified(), "DMNEACGEHEC"));
    assertEquals(0, fixture().number(notified(), "progress"));
    assertTrue(
        ((List<?>) fixture().value(fixture().nested(notified(), "reward"), "item_list")).isEmpty());
    assertEquals(good ? 1 : 0, deliveries);
    assertSame(item, owned);
    assertEquals(
        good ? 2000 : 0,
        fixture().opening.player.getGameplay().getClockPark().progress(ClockParkManager.catalog()));
  }

  @Test
  void bothActualEndingsKeepTheirOwnProofAndEmptyOrUniqueReceipt() throws Exception {
    path(false);
    assertEquals(Retcode.SUCC, finish().code());
    path(true);
    assertEquals(Retcode.SUCC, finish().code());
    var state = fixture().opening.player.getGameplay().getClockPark();
    assertEquals(2, state.completedEndings().size());
    assertEquals(2000, state.progress(ClockParkManager.catalog()));
    assertEquals(Retcode.SUCC, manager().ongoing().code());
    assertEquals(1, deliveries);
    assertEquals(2, fixture().opening.player.getGameplay().getRewards().size());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4})
  void trueEndingRetriesEachInterruptedPlayerWriteWithoutAnotherUniqueItem(int stage)
      throws Exception {
    path(true);
    fixture().opening.failSaveAt = fixture().opening.saves + stage;
    completeStory();
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    assertNull(notification);
    assertEquals(stage == 1 ? "STORY" : "FINISHING", run().getPhase());
    assertEquals(stage < 3 ? 0 : 1, deliveries);
    completeStory();
    assertEquals(0, code());
    assertNull(run());
    assertEquals(1, deliveries);
    assertEquals(1, owned.getCount());
    assertEquals(
        2000,
        fixture().opening.player.getGameplay().getClockPark().progress(ClockParkManager.catalog()));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4})
  void emptyEndingRetriesEachInterruptedPlayerWriteWithoutInventingItems(int stage)
      throws Exception {
    path(false);
    fixture().opening.failSaveAt = fixture().opening.saves + stage;
    completeStory();
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    assertNull(notification);
    assertEquals(stage == 1 ? "STORY" : "FINISHING", run().getPhase());
    assertEquals(0, deliveries);
    completeStory();
    assertEquals(0, code());
    assertNull(run());
    assertNull(owned);
    assertEquals(0, deliveries);
    assertEquals(
        0,
        fixture().opening.player.getGameplay().getClockPark().progress(ClockParkManager.catalog()));
  }

  @Test
  void rejectedDeliveryPreservesPendingStoryAndPreventsAbandoningIt() throws Exception {
    path(true);
    rejectDelivery = true;
    completeStory();
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), code());
    assertEquals("FINISHING", run().getPhase());
    String before = saved();
    assertEquals(Retcode.SYSTEM_BUSY, manager().quit(5).code());
    assertEquals(before, saved());
    rejectDelivery = false;
    completeStory();
    assertEquals(0, code());
    assertEquals(2, deliveries);
    assertEquals(1, owned.getCount());
  }

  @Test
  void reservedActualItemRestoresTheSameIdentityAfterUnwrittenGrantedReceipt() throws Exception {
    path(true);
    fixture().opening.failSaveAt = fixture().opening.saves + 3;
    completeStory();
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    var item = owned;
    var raw = JsonParser.parseString(saved()).getAsJsonObject();
    raw.getAsJsonObject("rewards")
        .getAsJsonObject("clock-park-ending:5:550")
        .addProperty("granted", false);
    load(raw);
    failItemSave = true;
    completeStory();
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    assertEquals(1, deliveries);
    assertSame(item, owned);
    failItemSave = false;
    completeStory();
    assertEquals(0, code());
    assertEquals(1, deliveries);
    assertEquals(1, itemWrites);
    assertSame(item, owned);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 1, 2})
  void unrelatedOrCorruptUniqueItemCannotBecomeEvidenceOfAnEnding(int count) throws Exception {
    path(true);
    owned = new GameItem(140485, 1);
    owned.setCount(count);
    String before = saved();
    completeStory();
    assertEquals(
        (count == 1 ? Retcode.ITEM_REWARD_EXCEED_LIMIT : Retcode.PLAYER_DATA_ERROR).getVal(),
        code());
    assertEquals(before, saved());
    assertEquals(0, deliveries);
    assertEquals(count, owned.getCount());
    assertNull(notification);
  }

  @ParameterizedTest
  @ValueSource(strings = {"550-source", "560-source", "550-loaded", "560-loaded"})
  void originalFullSourcesAndLoadedEmptyOrUniqueRewardAreRequired(String variant) throws Exception {
    boolean good = variant.startsWith("550");
    path(good);
    if (variant.endsWith("source"))
      Files.writeString(root.resolve("ExcelOutput/RewardData.json"), "[]");
    else {
      var reward = GameData.getRewardExcelMap().get(good ? 8008006 : 100);
      reward.getRewards().clear();
      reward.getRewards().add(140485, 2);
    }
    String before = saved();
    completeStory();
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), code());
    assertEquals(before, saved());
    assertEquals(0, deliveries);
    assertNull(notification);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "550-missing",
        "560-missing",
        "550-ungranted",
        "560-ungranted",
        "550-reward",
        "560-reward",
        "550-foreign",
        "560-foreign",
        "550-source",
        "560-source"
      })
  void completedProofRequiresItsOwnMatchedReceiptAndSource(String variant) throws Exception {
    boolean good = variant.startsWith("550");
    path(good);
    assertEquals(Retcode.SUCC, finish().code());
    var raw = JsonParser.parseString(saved()).getAsJsonObject();
    var rewards = raw.getAsJsonObject("rewards");
    String key = "clock-park-ending:5:" + (good ? 550 : 560);
    var receipt = rewards.getAsJsonObject(key);
    switch (variant.substring(4)) {
      case "missing" -> rewards.remove(key);
      case "ungranted" -> receipt.addProperty("granted", false);
      case "reward" -> receipt.addProperty("rewardId", good ? 100 : 8008006);
      case "foreign" ->
          rewards.add("clock-park-ending:5:" + (good ? 560 : 550), receipt.deepCopy());
      case "source" ->
          raw.getAsJsonObject("clockPark")
              .getAsJsonObject("reachedProofs")
              .getAsJsonObject("5")
              .getAsJsonObject(good ? "550" : "560")
              .addProperty("endingSource", ClockParkEndingRewards.SOURCE);
      default -> fail(variant);
    }
    load(raw);
    String before = saved();
    assertEquals(Retcode.PLAYER_DATA_ERROR, manager().ongoing().code());
    assertEquals(before, saved());
    assertEquals(good ? 1 : 0, deliveries);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void sixConcurrentStoryConfirmationsHaveOneCompletion(boolean good) throws Exception {
    path(good);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<java.util.concurrent.Callable<ClockParkManager.Result>>();
      for (int i = 0; i < 6; i++) calls.add(this::finish);
      int successes = 0;
      for (var result : pool.invokeAll(calls)) {
        var code = result.get().code();
        if (code == Retcode.SUCC) successes++;
        else assertEquals(Retcode.REQ_PARA_INVALID, code);
      }
      assertEquals(1, successes);
    }
    assertNull(run());
    assertEquals(good ? 1 : 0, deliveries);
    assertEquals(
        1, fixture().opening.player.getGameplay().getClockPark().completedEndings().size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "550-STORY",
        "560-STORY",
        "550-RESERVED",
        "560-RESERVED",
        "550-GRANTED",
        "560-GRANTED",
        "550-FINISHED",
        "560-FINISHED"
      })
  void actualPlayerMorphiaRestoresEachEndingPhaseTwice(String variant) throws Exception {
    boolean good = variant.startsWith("550");
    path(good);
    if (variant.endsWith("RESERVED")) {
      fixture().opening.failSaveAt = fixture().opening.saves + 2;
      completeStory();
      assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    }
    if (variant.endsWith("GRANTED")) {
      fixture().opening.failSaveAt = fixture().opening.saves + 4;
      completeStory();
      assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    }
    if (variant.endsWith("FINISHED")) {
      completeStory();
      assertEquals(0, code());
    }
    String before = saved();
    var expected = fixture().query();
    var opening = fixture().opening;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-second-ending-fixture");
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
      uid.set(document, 199992);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, opening.player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored = Objects.requireNonNull(store.find(Player.class).first());
        assertEquals(canonical(before), canonical(opening.gson.toJson(restored.getGameplay())));
        opening.replaceGameplay(restored.getGameplay());
        String loadedBefore = saved();
        assertEquals(expected, fixture().query());
        assertEquals(loadedBefore, saved());
      }
    } finally {
      server.shutdownNow();
    }
    load(JsonParser.parseString(before).getAsJsonObject());
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

  @Test
  void Original550StoryCreditsExactlyItsOwn140485And2000Progress() throws Exception {
    path(true);
    var result = finish();
    assertEquals(Retcode.SUCC, result.code());
    assertNull(run());
    assertTrue(result.newEnding());
    assertEquals(1, deliveries);
    assertEquals(1, owned.getCount());
    assertEquals(
        2000,
        fixture().opening.player.getGameplay().getClockPark().progress(ClockParkManager.catalog()));
    assertTrue(
        fixture()
            .opening
            .player
            .getGameplay()
            .getRewards()
            .get("clock-park-ending:5:550")
            .isGranted());
  }

  @Test
  void Original560StoryCompletesItsEmptyRewardWithoutItemsOrProgress() throws Exception {
    path(false);
    var result = finish();
    assertEquals(Retcode.SUCC, result.code());
    assertNull(run());
    assertTrue(result.newEnding());
    assertTrue(result.rewards().isEmpty());
    assertEquals(0, deliveries);
    assertNull(owned);
    assertEquals(
        0,
        fixture().opening.player.getGameplay().getClockPark().progress(ClockParkManager.catalog()));
    assertTrue(
        fixture()
            .opening
            .player
            .getGameplay()
            .getRewards()
            .get("clock-park-ending:5:560")
            .isGranted());
  }

  @Test
  void RepeatingTheWholeActual550SlotPathKeepsItsFirstItemIdentityAndProgress() throws Exception {
    path(true);
    assertEquals(Retcode.SUCC, finish().code());
    var item = owned;
    path(true);
    var result = finish();
    assertEquals(Retcode.SUCC, result.code());
    assertFalse(result.newEnding());
    assertTrue(result.rewards().isEmpty());
    assertSame(item, owned);
    assertEquals(1, deliveries);
    assertEquals(
        2000,
        fixture().opening.player.getGameplay().getClockPark().progress(ClockParkManager.catalog()));
  }
}
