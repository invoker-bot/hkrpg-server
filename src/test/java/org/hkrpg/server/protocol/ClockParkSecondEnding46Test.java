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

class ClockParkSecondEnding46Test {
  @TempDir Path root;
  final ClockParkEnding46Test ending = new ClockParkEnding46Test();
  final Map<Integer, RewardExcel> oldRewards = new HashMap<>();
  ItemExcel oldItem;
  GameItem owned;
  int deliveries, itemWrites;
  boolean rejectDelivery, failItemSave;

  @BeforeEach
  void prepare() throws Exception {
    ending.root = root;
    ending.prepare();
    var rewards =
        JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/RewardData.json")))
            .getAsJsonArray();
    for (int id : List.of(100, 8008002)) {
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
    oldItem = GameData.getItemExcelMap().get(140483);
    var row =
        JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/ItemConfig.json")))
            .getAsJsonArray()
            .asList()
            .stream()
            .map(JsonElement::getAsJsonObject)
            .filter(v -> v.get("ID").getAsInt() == 140483)
            .findFirst()
            .orElseThrow()
            .deepCopy();
    row.addProperty("ItemName", row.getAsJsonObject("ItemName").get("Hash").getAsLong());
    GameData.getItemExcelMap().add(ResourceLoader.getGson().fromJson(row, ItemExcel.class));
    var inventory =
        new Inventory(fixture().opening.player) {
          public GameItem getMaterialByItemId(int id) {
            return id == 140483
                ? owned
                : id == 140484 ? ending.owned : super.getMaterialByItemId(id);
          }

          public GrantResult grantItems(ItemParamMap amounts, boolean hint) {
            assertEquals(1, amounts.size());
            assertEquals(1, amounts.get(140483));
            deliveries++;
            if (rejectDelivery) return new GrantResult(List.of(), false);
            owned =
                new GameItem(140483, 1) {
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
    ending.restore();
    for (var entry : oldRewards.entrySet()) {
      GameData.getRewardExcelMap().keySet().remove(entry.getKey());
      if (entry.getValue() != null) GameData.getRewardExcelMap().add(entry.getValue());
    }
    GameData.getItemExcelMap().keySet().remove(140483);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
  }

  ClockParkDice46Test fixture() {
    return ending.fixture();
  }

  ClockParkState.Run run() {
    return ending.run();
  }

  ClockParkManager manager() {
    return new ClockParkManager(fixture().opening.player);
  }

  void normals(int count) throws Exception {
    for (int i = 0; i < count; i++) {
      fixture().overrideDice(6, 6, 6);
      fixture()
          .submit(
              fixture()
                  .placement(
                      2,
                      run().getRound(),
                      new int[] {6, 6, 6},
                      new int[] {1001, 1007, 1008},
                      false,
                      false));
      assertEquals(0, fixture().opening.code());
    }
  }

  void branch(int card) {
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().finishCheckpoint(2, run().getRound()).code());
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().selectBranch(2, run().getRound(), card, run().getDice().getFirst()).code());
  }

  void leaf(boolean good) throws Exception {
    var raw =
        JsonParser.parseString(
                fixture().opening.gson.toJson(fixture().opening.player.getGameplay()))
            .getAsJsonObject();
    raw.add("clockPark", fixture().opening.gson.toJsonTree(new ClockParkState()));
    fixture().opening.replaceGameplay(fixture().opening.gson.fromJson(raw, GameplayState.class));
    path(good);
  }

  void path(boolean good) throws Exception {
    fixture().begin(2);
    normals(4);
    branch(2101);
    fixture().finishStory(2, run().getRound());
    normals(4);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().finishCheckpoint(2, run().getRound()).code());
    var raw =
        JsonParser.parseString(
                fixture().opening.gson.toJson(fixture().opening.player.getGameplay()))
            .getAsJsonObject();
    var dice = new JsonArray();
    dice.add(good ? 6 : 1);
    raw.getAsJsonObject("clockPark").getAsJsonObject("active").add("dice", dice);
    fixture().opening.replaceGameplay(fixture().opening.gson.fromJson(raw, GameplayState.class));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().selectBranch(2, run().getRound(), 2102, good ? 6 : 1).code());
    fixture().finishStory(2, run().getRound());
    normals(5);
    branch(good ? 2103 : 2104);
    assertEquals(good ? 205 : 206, run().getChapter());
    assertEquals("STORY", run().getPhase());
  }

  ClockParkManager.Result finish() {
    return manager().finishStory(2, 0);
  }

  String saved() {
    return ending.branch.saved();
  }

  void load(JsonObject raw) throws Exception {
    fixture().opening.replaceGameplay(fixture().opening.gson.fromJson(raw, GameplayState.class));
  }

  void completeStory() throws Exception {
    var request =
        fixture().operation(2, 0, "IKNEKEJEGLM", fixture().request("NKOCCGLHOML").build());
    assertTrue(
        VersionedPacketHandler.handle(
            ending.completion,
            ending.completion.getProtocol().clientCommand("ClockParkHandleWaitOperationCsReq"),
            request.toByteArray()));
  }

  int code() {
    return fixture().number(ending.completion.response, "retcode");
  }

  com.google.protobuf.DynamicMessage notified() {
    return fixture().nested(ending.notification, "NLEOMLNOKCC");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void nativeEndingNotificationMatchesTheAuthoredOutcomeAndActualAward(boolean good)
      throws Exception {
    leaf(good);
    completeStory();
    assertEquals(0, code());
    assertNull(run());
    assertEquals(
        List.of("ClockParkHandleWaitOperationScRsp", "ClockParkFinishScriptScNotify"), ending.sent);
    assertEquals(
        5,
        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                fixture().value(ending.completion.response, "LNPGHCLDOKG"))
            .getNumber());
    var result = notified();
    assertEquals(good ? 205 : 206, fixture().number(result, "DCAHJELNJNP"));
    assertEquals(2, fixture().number(result, "script_id"));
    assertEquals(good ? 2000 : 0, fixture().number(result, "progress"));
    assertEquals(true, fixture().value(result, "DMNEACGEHEC"));
    assertEquals(false, fixture().value(result, "HGPKMHFPMBJ"));
    var items = (List<?>) fixture().value(fixture().nested(result, "reward"), "item_list");
    assertEquals(good ? 1 : 0, items.size());
    if (good) {
      var item = (com.google.protobuf.DynamicMessage) items.getFirst();
      assertEquals(140483, fixture().number(item, "item_id"));
      assertEquals(1, fixture().number(item, "num"));
    }
    ending.completion.request("ClockParkGetInfoCsReq");
    assertEquals(good ? 2000 : 0, fixture().number(ending.completion.response, "progress"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void repeatActualWholePathDoesNotPayOrCountTheEndingTwice(boolean good) throws Exception {
    leaf(good);
    completeStory();
    assertEquals(0, code());
    var item = owned;
    path(good);
    ending.sent.clear();
    ending.notification = null;
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
    leaf(false);
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
    leaf(true);
    fixture().opening.failSaveAt = fixture().opening.saves + stage;
    completeStory();
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    assertNull(ending.notification);
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
    leaf(false);
    fixture().opening.failSaveAt = fixture().opening.saves + stage;
    completeStory();
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    assertNull(ending.notification);
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
    leaf(true);
    rejectDelivery = true;
    completeStory();
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), code());
    assertEquals("FINISHING", run().getPhase());
    String before = saved();
    assertEquals(Retcode.SYSTEM_BUSY, manager().quit(2).code());
    assertEquals(before, saved());
    rejectDelivery = false;
    completeStory();
    assertEquals(0, code());
    assertEquals(2, deliveries);
    assertEquals(1, owned.getCount());
  }

  @Test
  void reservedActualItemRestoresTheSameIdentityAfterUnwrittenGrantedReceipt() throws Exception {
    leaf(true);
    fixture().opening.failSaveAt = fixture().opening.saves + 3;
    completeStory();
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), code());
    var item = owned;
    var raw = JsonParser.parseString(saved()).getAsJsonObject();
    raw.getAsJsonObject("rewards")
        .getAsJsonObject("clock-park-ending:2:205")
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
    leaf(true);
    owned = new GameItem(140483, 1);
    owned.setCount(count);
    String before = saved();
    completeStory();
    assertEquals(
        (count == 1 ? Retcode.ITEM_REWARD_EXCEED_LIMIT : Retcode.PLAYER_DATA_ERROR).getVal(),
        code());
    assertEquals(before, saved());
    assertEquals(0, deliveries);
    assertEquals(count, owned.getCount());
    assertNull(ending.notification);
  }

  @ParameterizedTest
  @ValueSource(strings = {"205-source", "206-source", "205-loaded", "206-loaded"})
  void originalFullSourcesAndLoadedEmptyOrUniqueRewardAreRequired(String variant) throws Exception {
    boolean good = variant.startsWith("205");
    leaf(good);
    if (variant.endsWith("source"))
      Files.writeString(root.resolve("ExcelOutput/RewardData.json"), "[]");
    else {
      var reward = GameData.getRewardExcelMap().get(good ? 8008002 : 100);
      reward.getRewards().clear();
      reward.getRewards().add(140483, 2);
    }
    String before = saved();
    completeStory();
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), code());
    assertEquals(before, saved());
    assertEquals(0, deliveries);
    assertNull(ending.notification);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "205-missing",
        "206-missing",
        "205-ungranted",
        "206-ungranted",
        "205-reward",
        "206-reward",
        "205-foreign",
        "206-foreign",
        "205-source",
        "206-source"
      })
  void completedProofRequiresItsOwnMatchedReceiptAndSource(String variant) throws Exception {
    boolean good = variant.startsWith("205");
    leaf(good);
    assertEquals(Retcode.SUCC, finish().code());
    var raw = JsonParser.parseString(saved()).getAsJsonObject();
    var rewards = raw.getAsJsonObject("rewards");
    String key = "clock-park-ending:2:" + (good ? 205 : 206);
    var receipt = rewards.getAsJsonObject(key);
    switch (variant.substring(4)) {
      case "missing" -> rewards.remove(key);
      case "ungranted" -> receipt.addProperty("granted", false);
      case "reward" -> receipt.addProperty("rewardId", good ? 100 : 8008002);
      case "foreign" ->
          rewards.add("clock-park-ending:2:" + (good ? 206 : 205), receipt.deepCopy());
      case "source" ->
          raw.getAsJsonObject("clockPark")
              .getAsJsonObject("reachedProofs")
              .getAsJsonObject("2")
              .getAsJsonObject(good ? "205" : "206")
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
    leaf(good);
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
        "205-STORY",
        "206-STORY",
        "205-RESERVED",
        "206-RESERVED",
        "205-GRANTED",
        "206-GRANTED",
        "205-FINISHED",
        "206-FINISHED"
      })
  void actualPlayerMorphiaRestoresEachEndingPhaseTwice(String variant) throws Exception {
    boolean good = variant.startsWith("205");
    leaf(good);
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
  void Authored205StoryActuallyCreditsItsOwnUniqueItemAnd2000Progress() throws Exception {
    leaf(true);
    var result = finish();
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, result.code());
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
            .get("clock-park-ending:2:205")
            .isGranted());
  }

  @Test
  void Authored206EmptyRewardCompletesItsStoryWithoutManufacturingAnItemOrProgress()
      throws Exception {
    leaf(false);
    var result = finish();
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, result.code());
    assertNull(run());
    assertTrue(result.newEnding());
    assertTrue(result.rewards().isEmpty());
    assertEquals(0, deliveries);
    assertNull(owned);
    var state = fixture().opening.player.getGameplay().getClockPark();
    assertEquals(0, state.progress(ClockParkManager.catalog()));
    assertEquals(206, state.completedEndings().getFirst().getChapter());
    assertTrue(
        fixture()
            .opening
            .player
            .getGameplay()
            .getRewards()
            .get("clock-park-ending:2:206")
            .isGranted());
  }

  @Test
  void Repeating205KeepsTheAlreadyEarnedUniqueItemAndDoesNotAddProgressAgain() throws Exception {
    leaf(true);
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, finish().code());
    // Start another actual run while preserving the completed proof and receipt.
    fixture().begin(2);
    normals(4);
    branch(2101);
    fixture().finishStory(2, run().getRound());
    normals(4);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().finishCheckpoint(2, run().getRound()).code());
    var raw =
        JsonParser.parseString(
                fixture().opening.gson.toJson(fixture().opening.player.getGameplay()))
            .getAsJsonObject();
    var dice = new JsonArray();
    dice.add(6);
    raw.getAsJsonObject("clockPark").getAsJsonObject("active").add("dice", dice);
    fixture().opening.replaceGameplay(fixture().opening.gson.fromJson(raw, GameplayState.class));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        manager().selectBranch(2, run().getRound(), 2102, 6).code());
    fixture().finishStory(2, run().getRound());
    normals(5);
    branch(2103);
    var item = owned;
    var result = finish();
    assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, result.code());
    assertFalse(result.newEnding());
    assertEquals(1, deliveries);
    assertSame(item, owned);
    assertEquals(
        2000,
        fixture().opening.player.getGameplay().getClockPark().progress(ClockParkManager.catalog()));
  }
}
