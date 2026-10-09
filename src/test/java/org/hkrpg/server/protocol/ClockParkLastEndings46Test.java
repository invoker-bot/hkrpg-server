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
import org.junit.jupiter.params.provider.*;

class ClockParkLastEndings46Test {
  @TempDir Path root;
  final ClockParkMiniGames46Test games = new ClockParkMiniGames46Test();
  final Map<Integer, RewardExcel> oldRewards = new HashMap<>();
  final Map<Integer, ItemExcel> oldItems = new HashMap<>();
  final Map<Integer, GameItem> owned = new HashMap<>();
  final Map<Integer, Integer> awarded = new HashMap<>();
  final List<List<Integer>> deliveries = new ArrayList<>();
  int partialItem, saveFailureItem, currentScript;
  boolean throwAfterPartial;

  @BeforeEach
  void prepare() throws Exception {
    games.root = root;
    games.prepare();
    var source =
        JsonParser.parseString(
                new String(
                    getClass()
                        .getResourceAsStream("/fixtures/clock-park-ending-reward-4.6.json")
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var e : source.getAsJsonObject("files").entrySet()) {
      try (var input =
          new java.util.zip.GZIPInputStream(
              new java.io.ByteArrayInputStream(
                  Base64.getDecoder()
                      .decode(e.getValue().getAsJsonObject().get("gzipBase64").getAsString())))) {
        Files.write(root.resolve(e.getKey()), input.readAllBytes());
      }
    }
    var rewards =
        JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/RewardData.json")))
            .getAsJsonArray();
    for (int id : List.of(100, 8008003, 8008005)) {
      oldRewards.put(id, GameData.getRewardExcelMap().get(id));
      var row =
          rewards.asList().stream()
              .map(JsonElement::getAsJsonObject)
              .filter(r -> r.get("RewardID").getAsInt() == id)
              .findFirst()
              .orElseThrow();
      var value = ResourceLoader.getGson().fromJson(row, RewardExcel.class);
      value.onLoad();
      GameData.getRewardExcelMap().add(value);
    }
    var items =
        JsonParser.parseString(Files.readString(root.resolve("ExcelOutput/ItemConfig.json")))
            .getAsJsonArray();
    for (int id : List.of(140486, 140487, 140488)) {
      oldItems.put(id, GameData.getItemExcelMap().get(id));
      var row =
          items.asList().stream()
              .map(JsonElement::getAsJsonObject)
              .filter(r -> r.get("ID").getAsInt() == id)
              .findFirst()
              .orElseThrow()
              .deepCopy();
      row.addProperty("ItemName", row.getAsJsonObject("ItemName").get("Hash").getAsLong());
      GameData.getItemExcelMap().add(ResourceLoader.getGson().fromJson(row, ItemExcel.class));
    }
    var inventory =
        new Inventory(fixture().opening.player) {
          public GameItem getMaterialByItemId(int id) {
            return owned.get(id);
          }

          public GrantResult grantItems(ItemParamMap amounts, boolean hint) {
            var ids = amounts.keySet().stream().sorted().toList();
            deliveries.add(ids);
            var results = new ArrayList<GameItem>();
            for (int id : ids) {
              if (partialItem != 0 && id != partialItem) continue;
              assertEquals(1, amounts.get(id));
              assertFalse(
                  owned.containsKey(id) && owned.get(id).getCount() == 1,
                  "Repeated unique delivery");
              var item =
                  new GameItem(id, 1) {
                    public void save() {
                      if (saveFailureItem == id)
                        throw new IllegalStateException("fixture item save interruption");
                    }
                  };
              owned.put(id, item);
              awarded.merge(id, 1, Integer::sum);
              results.add(item);
            }
            if (throwAfterPartial)
              throw new IllegalStateException("fixture partial delivery interruption");
            return new GrantResult(results, partialItem == 0);
          }
        };
    var inventoryField = Player.class.getDeclaredField("inventory");
    inventoryField.setAccessible(true);
    inventoryField.set(fixture().opening.player, inventory);
    games.balanced.resetToProjectDefault();
  }

  @AfterEach
  void restore() throws Exception {
    games.restore();
    for (var e : oldRewards.entrySet()) {
      GameData.getRewardExcelMap().keySet().remove(e.getKey());
      if (e.getValue() != null) GameData.getRewardExcelMap().add(e.getValue());
    }
    for (var e : oldItems.entrySet()) {
      GameData.getItemExcelMap().keySet().remove(e.getKey());
      if (e.getValue() != null) GameData.getItemExcelMap().add(e.getValue());
    }
  }

  ClockParkDice46Test fixture() {
    return games.fixture();
  }

  ClockParkState.Run run() {
    return games.run();
  }

  ClockParkManager manager() {
    return new ClockParkManager(fixture().opening.player);
  }

  String saved() {
    return games.saved();
  }

  void face(int face) throws Exception {
    var raw = games.raw();
    var dice = new JsonArray();
    dice.add(face);
    raw.getAsJsonObject("clockPark").getAsJsonObject("active").add("dice", dice);
    games.balanced.load(raw);
  }

  void path(int script, boolean good) throws Exception {
    currentScript = script;
    fixture().begin(script);
    if (script == 3) {
      games.normals(4);
      games.checkpoint();
      games.branch(3101);
      games.normals(4);
    } else games.normals(8);
    games.finish(false, 0);
    assertEquals(0, fixture().opening.code());
    games.checkpoint();
    if (script == 4) {
      games.branch(4122);
      games.normals(8);
      games.finish(false, 0);
      assertEquals(0, fixture().opening.code());
      games.checkpoint();
    }
    face(good ? 6 : 1);
    games.branch(script == 3 ? 3102 : 4226);
    games.normals(script == 3 ? 5 : 8);
    games.checkpoint();
    int card = script == 3 ? (good ? 3103 : 3104) : (good ? 4322 : 4422);
    assertEquals(
        Retcode.SUCC,
        manager().selectBranch(script, run().getRound(), card, run().getDice().getFirst()).code());
    assertEquals(script == 3 ? (good ? 305 : 306) : (good ? 450 : 460), run().getChapter());
    assertEquals("STORY", run().getPhase());
  }

  ClockParkManager.Result finish() {
    return manager().finishStory(currentScript, 0);
  }

  int progress() {
    return fixture()
        .opening
        .player
        .getGameplay()
        .getClockPark()
        .progress(ClockParkManager.catalog());
  }

  @ParameterizedTest
  @CsvSource({
    "3,true,305,30501,2",
    "3,false,306,30601,0",
    "4,true,450,40501,1",
    "4,false,460,40601,0"
  })
  void originalEndingStoryCompletesWithExactRealUniqueItemsAndOwnProgress(
      int script, boolean good, int chapter, int story, int count) throws Exception {
    path(script, good);
    assertEquals(story, run().getStory());
    var result = finish();
    assertEquals(Retcode.SUCC, result.code());
    assertNull(run());
    assertTrue(result.newEnding());
    assertEquals(count, result.rewards().size());
    assertEquals(count, owned.size());
    assertEquals(good ? 2000 : 0, progress());
    assertTrue(
        fixture()
            .opening
            .player
            .getGameplay()
            .getRewards()
            .get("clock-park-ending:" + script + ":" + chapter)
            .isGranted());
    assertEquals(Retcode.SUCC, manager().ongoing().code());
    for (var item : owned.values()) assertEquals(1, item.getCount());
  }

  @ParameterizedTest
  @CsvSource({"3,true", "3,false", "4,true", "4,false"})
  void completeOriginalPathAgainDoesNotPayOrCreditAnotherProgress(int script, boolean good)
      throws Exception {
    path(script, good);
    assertEquals(Retcode.SUCC, finish().code());
    var firstItems = new HashMap<>(owned);
    int delivered = deliveries.size();
    path(script, good);
    var result = finish();
    assertEquals(Retcode.SUCC, result.code());
    assertFalse(result.newEnding());
    assertTrue(result.rewards().isEmpty());
    assertEquals(good ? 2000 : 0, progress());
    assertEquals(delivered, deliveries.size());
    assertEquals(firstItems, owned);
    for (var e : firstItems.entrySet()) assertSame(e.getValue(), owned.get(e.getKey()));
    assertEquals(Retcode.REQ_PARA_INVALID, finish().code());
  }

  @Test
  void allFourCompletedOutcomesKeepExactThreeUniqueMaterialsAndIndependentReceipts()
      throws Exception {
    for (int script : List.of(3, 4))
      for (boolean good : List.of(false, true)) {
        path(script, good);
        assertEquals(Retcode.SUCC, finish().code());
      }
    assertEquals(Set.of(140486, 140487, 140488), owned.keySet());
    assertEquals(4000, progress());
    assertEquals(
        4, fixture().opening.player.getGameplay().getClockPark().completedEndings().size());
    assertEquals(4, fixture().opening.player.getGameplay().getRewards().size());
    assertEquals(Retcode.SUCC, manager().ongoing().code());
  }

  @ParameterizedTest
  @CsvSource({
    "3,true,1",
    "3,true,2",
    "3,true,3",
    "3,true,4",
    "3,false,1",
    "3,false,2",
    "3,false,3",
    "3,false,4",
    "4,true,1",
    "4,true,2",
    "4,true,3",
    "4,true,4",
    "4,false,1",
    "4,false,2",
    "4,false,3",
    "4,false,4"
  })
  void everyInterruptedPlayerWriteRetriesWithoutAnyUniqueDuplicate(
      int script, boolean good, int stage) throws Exception {
    path(script, good);
    fixture().opening.failSaveAt = fixture().opening.saves + stage;
    assertEquals(Retcode.SYSTEM_BUSY, finish().code());
    assertEquals(stage == 1 ? "STORY" : "FINISHING", run().getPhase());
    assertEquals(Retcode.SUCC, finish().code());
    assertNull(run());
    assertEquals(good ? 2000 : 0, progress());
    assertEquals(good ? 1 : 0, deliveries.size());
    for (int count : awarded.values()) assertEquals(1, count);
  }

  @ParameterizedTest
  @CsvSource({"140486,false", "140487,false", "140486,true", "140487,true"})
  void partialTwoItemDeliveryPaysOnlyMissingItemAndKeepsFirstIdentity(
      int first, boolean throwsAfter) throws Exception {
    path(3, true);
    partialItem = first;
    throwAfterPartial = throwsAfter;
    assertEquals(throwsAfter ? Retcode.SYSTEM_BUSY : Retcode.PLAYER_DATA_ERROR, finish().code());
    assertEquals("FINISHING", run().getPhase());
    assertEquals(Set.of(first), owned.keySet());
    var identity = owned.get(first);
    assertEquals(0, progress());
    String before = saved();
    assertEquals(Retcode.SYSTEM_BUSY, manager().quit(3).code());
    assertEquals(before, saved());
    partialItem = 0;
    throwAfterPartial = false;
    assertEquals(Retcode.SUCC, finish().code());
    assertSame(identity, owned.get(first));
    assertEquals(List.of(first == 140486 ? 140487 : 140486), deliveries.get(1));
    assertEquals(Map.of(140486, 1, 140487, 1), awarded);
    assertEquals(2000, progress());
    assertEquals(2, deliveries.size());
  }

  @ParameterizedTest
  @ValueSource(ints = {140486, 140487, 140488})
  void unrelatedExistingUniqueItemRejectsWholeRewardBeforeReservation(int id) throws Exception {
    int script = id == 140488 ? 4 : 3;
    path(script, true);
    owned.put(id, new GameItem(id, 1));
    String before = saved();
    int saves = fixture().opening.saves;
    assertEquals(Retcode.ITEM_REWARD_EXCEED_LIMIT, finish().code());
    assertEquals(before, saved());
    assertEquals(saves, fixture().opening.saves);
    assertTrue(deliveries.isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"140486,-1", "140486,2", "140487,-1", "140487,2", "140488,-1", "140488,2"})
  void corruptedOwnedCountRejectsBeforePayingOtherUniqueItem(int id, int count) throws Exception {
    path(id == 140488 ? 4 : 3, true);
    var item = new GameItem(id, 1);
    item.setCount(count);
    owned.put(id, item);
    String before = saved();
    assertEquals(Retcode.PLAYER_DATA_ERROR, finish().code());
    assertEquals(before, saved());
    assertTrue(deliveries.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {140486, 140487})
  void reservedFirstIdentitySaveFailureNeverRepeatsEitherDeliveredItem(int first) throws Exception {
    path(3, true);
    partialItem = first;
    assertEquals(Retcode.PLAYER_DATA_ERROR, finish().code());
    var identity = owned.get(first);
    partialItem = 0;
    saveFailureItem = first;
    assertEquals(Retcode.SYSTEM_BUSY, finish().code());
    assertEquals(2, deliveries.size());
    assertEquals(2, owned.size());
    saveFailureItem = 0;
    assertEquals(Retcode.SUCC, finish().code());
    assertSame(identity, owned.get(first));
    assertEquals(2, deliveries.size());
    assertEquals(Map.of(140486, 1, 140487, 1), awarded);
  }

  @ParameterizedTest
  @CsvSource({"3,true", "3,false", "4,true", "4,false"})
  void sixConcurrentConfirmationsProduceOneWholeEnding(int script, boolean good) throws Exception {
    path(script, good);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<ClockParkManager.Result>>();
      for (int i = 0; i < 6; i++) tasks.add(this::finish);
      int successes = 0;
      for (var task : pool.invokeAll(tasks)) {
        var code = task.get().code();
        if (code == Retcode.SUCC) successes++;
        else assertEquals(Retcode.REQ_PARA_INVALID, code);
      }
      assertEquals(1, successes);
    }
    assertEquals(good ? 2000 : 0, progress());
    assertEquals(good ? 1 : 0, deliveries.size());
    for (int count : awarded.values()) assertEquals(1, count);
  }

  @ParameterizedTest
  @CsvSource({"3,true", "3,false", "4,true", "4,false"})
  void completedNativeResultHasExactOriginalItemsAndNoDuplicateNotification(
      int script, boolean good) throws Exception {
    path(script, good);
    var sent = new ArrayList<String>();
    var notify = new com.google.protobuf.DynamicMessage[1];
    var completion =
        new Gameplay46HandlerTest.Session() {
          public Player getPlayer() {
            return fixture().opening.player;
          }

          public void sendClientPacket(String name, byte[] bytes) {
            sent.add(name);
            if (name.equals("ClockParkFinishScriptScNotify"))
              try {
                notify[0] = getProtocol().clientSchema().parse(name, bytes);
              } catch (Exception invalid) {
                throw new AssertionError(invalid);
              }
            else super.sendClientPacket(name, bytes);
          }
        };
    var request =
        fixture().operation(script, 0, "IKNEKEJEGLM", fixture().request("NKOCCGLHOML").build());
    ClockPark46.handle(
        completion,
        completion.getProtocol().clientCommand("ClockParkHandleWaitOperationCsReq"),
        request.toByteArray());
    assertEquals(0, fixture().number(completion.response, "retcode"));
    assertEquals(
        List.of("ClockParkHandleWaitOperationScRsp", "ClockParkFinishScriptScNotify"), sent);
    var result = fixture().nested(notify[0], "NLEOMLNOKCC");
    assertEquals(script, fixture().number(result, "script_id"));
    assertEquals(good ? 2000 : 0, fixture().number(result, "progress"));
    assertEquals(true, fixture().value(result, "DMNEACGEHEC"));
    assertEquals(false, fixture().value(result, "HGPKMHFPMBJ"));
    var rows = (List<?>) fixture().value(fixture().nested(result, "reward"), "item_list");
    var ids = new TreeSet<Integer>();
    for (var raw : rows) {
      var item = (com.google.protobuf.DynamicMessage) raw;
      assertEquals(1, fixture().number(item, "num"));
      ids.add(fixture().number(item, "item_id"));
    }
    assertEquals(good ? (script == 3 ? Set.of(140486, 140487) : Set.of(140488)) : Set.of(), ids);
    sent.clear();
    ClockPark46.handle(
        completion,
        completion.getProtocol().clientCommand("ClockParkHandleWaitOperationCsReq"),
        request.toByteArray());
    assertEquals(5, fixture().number(completion.response, "retcode"));
    assertEquals(List.of("ClockParkHandleWaitOperationScRsp"), sent);
  }

  @ParameterizedTest
  @CsvSource({
    "3,true,missing",
    "3,true,ungranted",
    "3,true,reward",
    "3,true,source",
    "3,false,missing",
    "3,false,ungranted",
    "3,false,reward",
    "3,false,source",
    "4,true,missing",
    "4,true,ungranted",
    "4,true,reward",
    "4,true,source",
    "4,false,missing",
    "4,false,ungranted",
    "4,false,reward",
    "4,false,source"
  })
  void completedEndingCannotBeReadWithoutMatchedRewardReceiptAndFullSource(
      int script, boolean good, String kind) throws Exception {
    path(script, good);
    int chapter = run().getChapter();
    assertEquals(Retcode.SUCC, finish().code());
    var raw = games.raw();
    var receipts = raw.getAsJsonObject("rewards");
    String key = "clock-park-ending:" + script + ":" + chapter;
    switch (kind) {
      case "missing" -> receipts.remove(key);
      case "ungranted" -> receipts.getAsJsonObject(key).addProperty("granted", false);
      case "reward" -> receipts.getAsJsonObject(key).addProperty("rewardId", 999);
      case "source" ->
          raw.getAsJsonObject("clockPark")
              .getAsJsonObject("reachedProofs")
              .getAsJsonObject(Integer.toString(script))
              .getAsJsonObject(Integer.toString(chapter))
              .addProperty("endingSource", ClockParkEndingRewards.SOURCE);
    }
    games.balanced.load(raw);
    String before = saved();
    assertEquals(Retcode.PLAYER_DATA_ERROR, manager().ongoing().code());
    assertEquals(before, saved());
  }

  @ParameterizedTest
  @ValueSource(ints = {140486, 140487, 140488})
  void grantedWholeRecipeRequiresEveryActualUniqueItem(int item) throws Exception {
    path(item == 140488 ? 4 : 3, true);
    assertEquals(Retcode.SUCC, finish().code());
    owned.remove(item);
    String before = saved();
    assertEquals(Retcode.PLAYER_DATA_ERROR, manager().ongoing().code());
    assertEquals(before, saved());
  }

  @ParameterizedTest
  @CsvSource({
    "3,true,source",
    "3,false,source",
    "4,true,source",
    "4,false,source",
    "3,true,loaded",
    "3,false,loaded",
    "4,true,loaded",
    "4,false,loaded"
  })
  void unverifiedFullRewardOrLoadedRecipeDoesNotReserveOrDeliverAnything(
      int script, boolean good, String kind) throws Exception {
    path(script, good);
    if (kind.equals("source")) Files.writeString(root.resolve("ExcelOutput/RewardData.json"), "[]");
    else {
      var reward = GameData.getRewardExcelMap().get(good ? (script == 3 ? 8008003 : 8008005) : 100);
      reward.getRewards().clear();
      reward.getRewards().add(140488, 2);
    }
    String before = saved();
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST, finish().code());
    assertEquals(before, saved());
    assertTrue(deliveries.isEmpty());
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> restorationPhases() {
    var args = new ArrayList<org.junit.jupiter.params.provider.Arguments>();
    for (int script : List.of(3, 4))
      for (boolean good : List.of(false, true))
        for (String phase : List.of("STORY", "PENDING", "GRANTED", "FINISHED"))
          args.add(org.junit.jupiter.params.provider.Arguments.of(script, good, phase));
    args.add(org.junit.jupiter.params.provider.Arguments.of(3, true, "PARTIAL1"));
    args.add(org.junit.jupiter.params.provider.Arguments.of(3, true, "PARTIAL2"));
    return args.stream();
  }

  @ParameterizedTest
  @MethodSource("restorationPhases")
  void actualPlayerMorphiaPreservesCompleteHistoryAndEveryRewardPhaseTwice(
      int script, boolean good, String phase) throws Exception {
    path(script, good);
    if (phase.equals("FINISHED")) assertEquals(Retcode.SUCC, finish().code());
    else if (phase.equals("PENDING") || phase.equals("GRANTED")) {
      fixture().opening.failSaveAt = fixture().opening.saves + (phase.equals("PENDING") ? 2 : 3);
      assertEquals(Retcode.SYSTEM_BUSY, finish().code());
    } else if (phase.startsWith("PARTIAL")) {
      partialItem = phase.equals("PARTIAL1") ? 140486 : 140487;
      assertEquals(Retcode.PLAYER_DATA_ERROR, finish().code());
    }
    var expected = fixture().query();
    String before = saved();
    var opening = fixture().opening;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "clock-park-last-endings-fixture");
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
      uid.set(document, 199994);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, opening.player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var restored = Objects.requireNonNull(store.find(Player.class).first());
        assertEquals(
            games.canonical(before), games.canonical(opening.gson.toJson(restored.getGameplay())));
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
    if (!phase.equals("FINISHED")) {
      partialItem = 0;
      assertEquals(Retcode.SUCC, finish().code());
    }
    assertEquals(good ? 2000 : 0, progress());
    for (int count : awarded.values()) assertEquals(1, count);
    assertEquals(Retcode.SUCC, manager().ongoing().code());
  }
}
