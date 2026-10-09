package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.server.packet.BasePacket;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CompositionPeriodTest {
  final Gson gson = new Gson();
  JsonArray fixture;
  List<GameplayDefinitions.DailyPool> oldPool;
  final List<Runnable> restorations = new ArrayList<>();
  String interruptPhase;
  boolean refuseOutput;
  final Player player =
      new Player() {
        final Inventory actual =
            new Inventory(this) {
              public List<GameItem> addItems(List<GameItem> values, boolean hint) {
                if (refuseOutput && values.stream().anyMatch(v -> v.getItemId() == 236))
                  return List.of();
                var proposed =
                    values.stream()
                        .map(
                            v ->
                                new GameItem(v.getExcel(), v.getCount()) {
                                  public void save() {}
                                })
                        .map(v -> (GameItem) v)
                        .toList();
                return super.addItems(proposed, hint);
              }
            };

        public Inventory getInventory() {
          return actual;
        }

        public void save() {
          var attempt = getGameplay().getCompositionLimits().getLastAttempt();
          if (attempt != null && Objects.equals(interruptPhase, attempt.getPhase()))
            throw new IllegalStateException("Isolated composition save interruption");
        }

        public void sendPacket(BasePacket packet) {}
      };

  <T extends GameResource> void install(ExcelMap<T> map, T row) {
    var old = map.get(row.getId());
    restorations.add(
        () -> {
          map.keySet().remove(row.getId());
          if (old != null) map.add(old);
        });
    map.add(row);
  }

  JsonObject row(String kind, int id) {
    return fixture.asList().stream()
        .map(JsonElement::getAsJsonObject)
        .filter(
            r ->
                kind.equals(r.get("kind").getAsString())
                    && r.getAsJsonObject("row").get("ID").getAsInt() == id)
        .findFirst()
        .orElseThrow()
        .getAsJsonObject("row")
        .deepCopy();
  }

  @BeforeEach
  void setup() throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/composition-period-4.6.json")) {
      assertNotNull(in);
      fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    player.setNickname("Periodic regression");
    player.setWorldLevel(3);
    oldPool = new ArrayList<>(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    for (var value : fixture) {
      var r = value.getAsJsonObject();
      var raw = r.getAsJsonObject("row").deepCopy();
      if ("recipe".equals(r.get("kind").getAsString()))
        install(GameData.getItemComposeExcelMap(), gson.fromJson(raw, ItemComposeExcel.class));
      else {
        raw.addProperty("ItemName", raw.getAsJsonObject("ItemName").get("Hash").getAsLong());
        install(GameData.getItemExcelMap(), gson.fromJson(raw, ItemExcel.class));
      }
    }
  }

  @AfterEach
  void cleanup() {
    Collections.reverse(restorations);
    restorations.forEach(Runnable::run);
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
  }

  GameItem stock(int id, int count) {
    var item =
        new GameItem(GameData.getItemExcelMap().get(id), count) {
          public void save() {}
        };
    assertTrue(
        player.getInventory().addItem(item),
        "Original formula or material cannot be held in actual inventory");
    return player.getInventory().getMaterialByItemId(id);
  }

  int count(int id) {
    var item = player.getInventory().getMaterialByItemId(id);
    return item == null ? 0 : item.getCount();
  }

  @Test
  void originalMonthlyRecipeActuallyConsumesSourceMaterialsAndEnforcesTwoOutputs() {
    stock(235, 2400);
    var service = new InventoryService(null);
    assertNotNull(service.composeItem(player, 101, 2, List.of()));
    assertEquals(800, count(235));
    assertEquals(2, count(236));
    assertNull(service.composeItem(player, 101, 1, List.of()));
    assertEquals(800, count(235));
    assertEquals(2, count(236));
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        801, 802, 803, 804, 805, 806, 807, 808, 809, 810, 811, 812, 813, 814, 815, 816, 817, 818,
        819, 820, 821, 822, 823, 824, 825, 826, 827, 828, 829
      })
  void everyOriginalWeeklyRecipeActuallyExchangesOnlyTwentyOutputs(int id) {
    var recipe = GameData.getItemComposeExcelMap().get(id);
    assertEquals("Weekly", recipe.getLimitType());
    assertEquals(20, recipe.getLimitValue());
    int input = recipe.getSpecialMaterialCost().iterator().nextInt();
    stock(input, 42);
    var service = new InventoryService(null);
    var cost = List.of(new emu.lunarcore.data.common.ItemParam(input, 40));
    assertNotNull(service.composeItem(player, id, 20, cost));
    assertEquals(2, count(input));
    assertEquals(20, count(recipe.getItemID()));
    assertNull(
        service.composeItem(
            player, id, 1, List.of(new emu.lunarcore.data.common.ItemParam(input, 2))));
    assertEquals(2, count(input));
    assertEquals(20, count(recipe.getItemID()));
  }

  @Test
  void failedMonthlyPaymentCannotSpendTheRemainingQuota() {
    stock(235, 799);
    var service = new InventoryService(null);
    assertNull(service.composeItem(player, 101, 1, List.of()));
    stock(235, 1);
    assertNotNull(service.composeItem(player, 101, 1, List.of()));
    assertEquals(0, count(235));
    assertEquals(1, count(236));
  }

  final java.time.Instant october = java.time.Instant.parse("2026-10-05T12:00:00Z");

  List<GameItem> monthly(int amount, java.time.Instant now) {
    return ItemComposition.compose(player, 101, amount, List.of(), now);
  }

  List<GameItem> weekly(int amount, java.time.Instant now) {
    return ItemComposition.compose(
        player,
        801,
        amount,
        List.of(new emu.lunarcore.data.common.ItemParam(110402, amount * 2)),
        now);
  }

  int used(int recipe, java.time.Instant now) {
    return player
        .getGameplay()
        .getCompositionLimits()
        .used(GameData.getItemComposeExcelMap().get(recipe), now)
        .orElseThrow();
  }

  @Test
  void monthlyQuotaRefreshesAtChinaFourAndCannotBeResetByEarlierQueries() {
    stock(235, 4000);
    assertNotNull(monthly(2, october));
    var before = java.time.Instant.parse("2026-10-31T19:59:59.999Z");
    var after = java.time.Instant.parse("2026-10-31T20:00:00Z");
    assertEquals(2, used(101, before));
    assertNull(monthly(1, before));
    assertEquals(2400, count(235));
    assertEquals(0, used(101, after));
    assertNotNull(monthly(2, after));
    assertEquals(800, count(235));
    assertEquals(4, count(236));
    assertEquals(2, used(101, after));
    assertNull(monthly(1, after));
  }

  @Test
  void weeklyQuotaRefreshesAtMondayChinaFourRatherThanMidnightOrEverySevenDays() {
    stock(110402, 44);
    assertNotNull(weekly(20, october));
    var before = java.time.Instant.parse("2026-10-11T19:59:59.999Z");
    var after = java.time.Instant.parse("2026-10-11T20:00:00Z");
    assertEquals(20, used(801, before));
    assertNull(weekly(1, before));
    assertEquals(4, count(110402));
    assertEquals(0, used(801, after));
    assertNotNull(weekly(1, after));
    assertEquals(2, count(110402));
    assertEquals(21, count(110401));
    assertEquals(1, used(801, after));
  }

  @Test
  void clockRollbackCannotReopenAnAlreadySpentNewPeriod() {
    stock(235, 2400);
    var november = java.time.Instant.parse("2026-11-01T12:00:00Z");
    assertNotNull(monthly(1, november));
    assertNull(monthly(1, october));
    assertTrue(
        player
            .getGameplay()
            .getCompositionLimits()
            .used(GameData.getItemComposeExcelMap().get(101), october)
            .isEmpty());
    assertEquals(1600, count(235));
    assertEquals(1, count(236));
  }

  @Test
  void queryInFutureCannotEraseTheCurrentPeriodOrItsAttempt() {
    stock(235, 800);
    assertNotNull(monthly(1, october));
    var ledger = player.getGameplay().getCompositionLimits();
    var attempt = ledger.getLastAttempt();
    var start = ledger.getWindows().get(101).getStart();
    assertEquals(0, used(101, java.time.Instant.parse("2026-11-01T12:00:00Z")));
    assertEquals(1, used(101, october));
    assertSame(attempt, ledger.getLastAttempt());
    assertEquals(start, ledger.getWindows().get(101).getStart());
  }

  @Test
  void differentWeeklyRecipeIdsHaveIndependentSourceQuotas() {
    var first = GameData.getItemComposeExcelMap().get(801);
    var second = GameData.getItemComposeExcelMap().get(802);
    int material =
        first
            .getSpecialMaterialCost()
            .intStream()
            .filter(second.getSpecialMaterialCost()::contains)
            .findFirst()
            .orElseThrow();
    stock(material, 80);
    var costs = List.of(new emu.lunarcore.data.common.ItemParam(material, 40));
    assertNotNull(ItemComposition.compose(player, 801, 20, costs, october));
    assertNotNull(ItemComposition.compose(player, 802, 20, costs, october));
    assertEquals(20, used(801, october));
    assertEquals(20, used(802, october));
    assertEquals(0, count(material));
    assertEquals(20, count(first.getItemID()));
    assertEquals(20, count(second.getItemID()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void simultaneousLastQuotaRequestsCannotBothProduceEvenWithEnoughMaterial(boolean week)
      throws Exception {
    if (week) {
      stock(110402, 42);
      assertNotNull(weekly(19, october));
    } else {
      stock(235, 2400);
      assertNotNull(monthly(1, october));
    }
    var gate = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<List<GameItem>> request =
          () -> {
            gate.await();
            return week ? weekly(1, october) : monthly(1, october);
          };
      var a = executor.submit(request);
      var b = executor.submit(request);
      gate.countDown();
      assertEquals(
          1,
          (a.get(10, TimeUnit.SECONDS) == null ? 0 : 1)
              + (b.get(10, TimeUnit.SECONDS) == null ? 0 : 1));
      assertEquals(week ? 20 : 2, used(week ? 801 : 101, october));
      assertEquals(week ? 2 : 800, count(week ? 110402 : 235));
      assertEquals(week ? 20 : 2, count(week ? 110401 : 236));
    } finally {
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"RESERVED", "DEBITED"})
  void saveInterruptionKeepsTheActualDebitPhaseAndBlocksAnyLaterComposition(String phase) {
    stock(235, 1600);
    interruptPhase = phase;
    assertThrows(IllegalStateException.class, () -> monthly(1, october));
    interruptPhase = null;
    var ledger = player.getGameplay().getCompositionLimits();
    assertEquals(phase, ledger.getLastAttempt().getPhase());
    assertEquals(Map.of(235, 800), ledger.getLastAttempt().getCosts());
    assertEquals(236, ledger.getLastAttempt().getOutput());
    assertEquals("RESERVED".equals(phase) ? 1600 : 800, count(235));
    assertEquals(0, count(236));
    assertEquals(0, used(101, october));
    assertTrue(ledger.pending());
    assertNull(monthly(1, october));
    assertNull(monthly(1, java.time.Instant.parse("2026-11-01T12:00:00Z")));
    assertEquals("RESERVED".equals(phase) ? 1600 : 800, count(235));
  }

  @Test
  void incompleteActualOutputKeepsAnUnresolvedDebitWithoutCreditingQuotaOrDaily() {
    stock(235, 1600);
    refuseOutput = true;
    assertNull(monthly(1, october));
    var ledger = player.getGameplay().getCompositionLimits();
    assertEquals("PARTIAL", ledger.getLastAttempt().getPhase());
    assertEquals(Map.of(), ledger.getLastAttempt().getReceived());
    assertTrue(ledger.pending());
    assertEquals(0, used(101, october));
    assertEquals(800, count(235));
    assertEquals(0, count(236));
    refuseOutput = false;
    assertNull(monthly(1, october));
    assertEquals(800, count(235));
    assertEquals(0, player.getGameplay().getDailyPoints());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Unknown",
        "Daily",
        "ModuleId",
        "null",
        "params",
        "zero",
        "negative",
        "relics",
        "nullRelics"
      })
  void unsupportedSourceLimitsCannotDebitOrCreateAnAttempt(String kind) {
    stock(235, 800);
    var raw = row("recipe", 101);
    if (kind.equals("null")) raw.add("LimitType", JsonNull.INSTANCE);
    else if (kind.equals("params")) raw.getAsJsonArray("LimitTypeParam").add(1);
    else if (kind.equals("relics")) raw.getAsJsonArray("RelicList").add(31011);
    else if (kind.equals("nullRelics")) raw.add("RelicList", JsonNull.INSTANCE);
    else if (kind.equals("zero") || kind.equals("negative"))
      raw.addProperty("LimitValue", kind.equals("zero") ? 0 : -1);
    else raw.addProperty("LimitType", kind);
    GameData.getItemComposeExcelMap().add(gson.fromJson(raw, ItemComposeExcel.class));
    assertNull(monthly(1, october));
    assertEquals(800, count(235));
    assertEquals(0, count(236));
    assertNull(player.getGameplay().getCompositionLimits().getLastAttempt());
  }

  void replaceState(JsonObject raw) throws Exception {
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(player, gson.fromJson(raw, GameplayState.class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "negative",
        "overLimit",
        "wrongBoundary",
        "wrongType",
        "nullWindow",
        "maxSequence",
        "nullPhase"
      })
  void malformedSavedQuotaOrAttemptCannotResetTheBudget(String kind) throws Exception {
    stock(235, 1600);
    assertNotNull(monthly(1, october));
    var state = gson.toJsonTree(player.getGameplay()).getAsJsonObject();
    var ledger = state.getAsJsonObject("compositionLimits");
    var windows = ledger.getAsJsonObject("windows");
    var window = windows.getAsJsonObject("101");
    switch (kind) {
      case "negative" -> window.addProperty("used", -1);
      case "overLimit" -> window.addProperty("used", 3);
      case "wrongBoundary" -> window.addProperty("start", window.get("start").getAsLong() + 1);
      case "wrongType" -> window.addProperty("type", "Weekly");
      case "nullWindow" -> windows.add("101", JsonNull.INSTANCE);
      case "maxSequence" ->
          ledger.getAsJsonObject("lastAttempt").addProperty("sequence", Long.MAX_VALUE);
      case "nullPhase" -> ledger.getAsJsonObject("lastAttempt").add("phase", JsonNull.INSTANCE);
    }
    replaceState(state);
    assertNull(monthly(1, october));
    assertEquals(800, count(235));
    assertEquals(1, count(236));
  }

  @Test
  void savedLimitedRecipeCannotBeHotChangedToUnlimitedAndBypassItsBudget() {
    stock(235, 1600);
    assertNotNull(monthly(1, october));
    var raw = row("recipe", 101);
    raw.addProperty("LimitType", "NotLimit");
    raw.addProperty("LimitValue", 0);
    GameData.getItemComposeExcelMap().add(gson.fromJson(raw, ItemComposeExcel.class));
    assertNull(monthly(1, october));
    assertEquals(800, count(235));
    assertEquals(1, count(236));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualMorphiaReloadRetainsTheSpentQuotaOrUnresolvedDebit(boolean pending) throws Exception {
    stock(235, 1600);
    if (pending) {
      interruptPhase = "DEBITED";
      assertThrows(IllegalStateException.class, () -> monthly(1, october));
      interruptPhase = null;
    } else assertNotNull(monthly(2, october));
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "composition-quota");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, emu.lunarcore.game.quest.QuestState.class);
      var persisted = new Player();
      persisted.setWorldLevel(3);
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(persisted, 123460028);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(persisted, player.getGameplay());
      store.save(persisted);
      var loaded = store.find(Player.class).first();
      assertNotNull(loaded);
      var ledger = loaded.getGameplay().getCompositionLimits();
      assertEquals(
          pending ? 0 : 2,
          ledger.used(GameData.getItemComposeExcelMap().get(101), october).orElseThrow());
      assertEquals(pending ? "DEBITED" : "COMPLETE", ledger.getLastAttempt().getPhase());
      assertEquals(Map.of(235, pending ? 800 : 1600), ledger.getLastAttempt().getCosts());
      assertEquals(pending, ledger.pending());
      assertNull(ItemComposition.compose(loaded, 101, 1, List.of(), october));
    } finally {
      server.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"compositionLimits\":null}"})
  void oldOrNullQuotaStateStartsEmptyWithoutInventingCompletedCompositions(String json)
      throws Exception {
    replaceState(JsonParser.parseString(json).getAsJsonObject());
    assertEquals(0, used(101, october));
    assertNull(player.getGameplay().getCompositionLimits().getLastAttempt());
    stock(235, 800);
    assertNotNull(monthly(1, october));
    assertEquals(1, used(101, october));
    assertEquals(0, count(235));
    assertEquals(1, count(236));
  }

  @Test
  void returnedQuotaMapCannotResetActualSpentCompositions() {
    stock(235, 1600);
    assertNotNull(monthly(2, october));
    assertThrows(
        UnsupportedOperationException.class,
        () -> player.getGameplay().getCompositionLimits().getWindows().clear());
    assertNull(monthly(1, october));
    assertEquals(2, used(101, october));
  }

  class NativeSession extends GameSession {
    final Map<String, DynamicMessage> messages = new LinkedHashMap<>();

    NativeSession() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    public void send(BasePacket packet) {}

    public void sendClientPacket(String name, byte[] bytes) {
      try {
        messages.put(name, getProtocol().clientSchema().parse(name, bytes));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    DynamicMessage.Builder request(int recipe, int count) {
      var type = getProtocol().clientSchema().message("ComposeItemCsReq");
      return DynamicMessage.newBuilder(type)
          .setField(type.findFieldByName("compose_id"), recipe)
          .setField(type.findFieldByName("count"), count);
    }

    void sendCompose(byte[] bytes) throws Exception {
      assertTrue(VersionedPacketHandler.handle(this, 561, bytes));
    }
  }

  @Test
  void nativeLoginCarriesAllOriginalPeriodIdsAndZeroActualUsage() throws Exception {
    var session = new NativeSession();
    new emu.lunarcore.server.packet.recv.HandlerPlayerLoginCsReq().handle(session, new byte[0]);
    var snapshot = session.messages.get("ComposeLimitNumCompleteScNotify");
    assertNotNull(snapshot, "Native login did not include actual composition quotas");
    var rows = (List<?>) snapshot.getField(snapshot.getDescriptorForType().findFieldByNumber(4));
    assertEquals(30, rows.size());
    var ids = new HashSet<Integer>();
    for (var value : rows) {
      var r = (DynamicMessage) value;
      assertEquals(0, r.getField(r.getDescriptorForType().findFieldByNumber(1)));
      ids.add((Integer) r.getField(r.getDescriptorForType().findFieldByNumber(9)));
    }
    assertEquals(30, ids.size());
    assertTrue(ids.containsAll(List.of(101, 801, 802)));
    assertNull(player.getGameplay().getCompositionLimits().getLastAttempt());
  }

  @Test
  void nativeMonthlyCompositionPublishesActualOutputAndUsedCountNotRemainingCount()
      throws Exception {
    stock(235, 1600);
    var session = new NativeSession();
    session.sendCompose(session.request(101, 1).build().toByteArray());
    var response = session.messages.get("ComposeItemScRsp");
    assertNotNull(response);
    assertEquals(0, response.getField(response.getDescriptorForType().findFieldByName("retcode")));
    session.sendCompose(session.request(101, 1).build().toByteArray());
    var update = session.messages.get("ComposeLimitNumUpdateScNotify");
    assertNotNull(update);
    var value =
        (DynamicMessage) update.getField(update.getDescriptorForType().findFieldByNumber(3));
    assertEquals(2, value.getField(value.getDescriptorForType().findFieldByNumber(1)));
    assertEquals(101, value.getField(value.getDescriptorForType().findFieldByNumber(9)));
    assertEquals(0, count(235));
    assertEquals(2, count(236));
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown", "duplicate", "wrongWire", "overflow", "convert"})
  void malformedNativeCompositionHasFailureResponseAndCannotConsumeStock(String kind)
      throws Exception {
    stock(235, 1600);
    var session = new NativeSession();
    var request = session.request(101, 1);
    byte[] bytes = request.build().toByteArray();
    var out = new java.io.ByteArrayOutputStream();
    out.write(bytes);
    var coded = com.google.protobuf.CodedOutputStream.newInstance(out);
    switch (kind) {
      case "unknown" -> coded.writeUInt32(99, 1);
      case "duplicate" -> coded.writeUInt32(7, 1);
      case "wrongWire" -> coded.writeByteArray(7, new byte[0]);
      case "overflow" -> {
        out.reset();
        coded.writeUInt32(2, 101);
        coded.writeUInt64(7, 0x100000001L);
      }
      case "convert" -> coded.writeByteArray(1, new byte[0]);
    }
    coded.flush();
    session.sendCompose(out.toByteArray());
    var response = session.messages.get("ComposeItemScRsp");
    assertNotNull(response);
    assertNotEquals(
        0, response.getField(response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(1600, count(235));
    assertEquals(0, count(236));
    assertNull(player.getGameplay().getCompositionLimits().getLastAttempt());
    assertFalse(session.messages.containsKey("ComposeLimitNumUpdateScNotify"));
  }

  DynamicMessage.Builder sourcePileCost(NativeSession s, int id, int amount) {
    var schema = s.getProtocol().clientSchema();
    var type = schema.message("PileItem");
    var pile =
        DynamicMessage.newBuilder(type)
            .setField(type.findFieldByName("item_id"), id)
            .setField(type.findFieldByName("item_num"), amount)
            .build();
    var costType = schema.message("ItemCost");
    return DynamicMessage.newBuilder(costType)
        .setField(costType.findFieldByName("pile_item"), pile);
  }

  byte[] sourceCostData(NativeSession session, byte[] cost) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    var coded = com.google.protobuf.CodedOutputStream.newInstance(out);
    coded.writeByteArray(2, cost);
    coded.flush();
    return out.toByteArray();
  }

  byte[] requestWithSourceCost(NativeSession session, byte[] cost) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    out.write(session.request(801, 1).build().toByteArray());
    var coded = com.google.protobuf.CodedOutputStream.newInstance(out);
    coded.writeByteArray(9, sourceCostData(session, cost));
    coded.flush();
    return out.toByteArray();
  }

  @Test
  void nativeWeeklyPileSelectionConsumesActualSourceInputAndReportsOneUsedOutOfTwenty()
      throws Exception {
    stock(110402, 4);
    var session = new NativeSession();
    session.sendCompose(
        requestWithSourceCost(session, sourcePileCost(session, 110402, 2).build().toByteArray()));
    var response = session.messages.get("ComposeItemScRsp");
    assertNotNull(response);
    assertEquals(0, response.getField(response.getDescriptorForType().findFieldByName("retcode")));
    var update = session.messages.get("ComposeLimitNumUpdateScNotify");
    assertNotNull(update);
    var value =
        (DynamicMessage) update.getField(update.getDescriptorForType().findFieldByNumber(3));
    assertEquals(1, value.getField(value.getDescriptorForType().findFieldByNumber(1)));
    assertEquals(801, value.getField(value.getDescriptorForType().findFieldByNumber(9)));
    assertEquals(2, count(110402));
    assertEquals(1, count(110401));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeUniqueOrConflictingOneofCostsCannotSpendQuotaOrStock(boolean conflicting)
      throws Exception {
    stock(110402, 4);
    var session = new NativeSession();
    var raw = new java.io.ByteArrayOutputStream();
    var coded = com.google.protobuf.CodedOutputStream.newInstance(raw);
    coded.writeUInt32(2, 123);
    coded.flush();
    if (conflicting) raw.write(sourcePileCost(session, 110402, 2).build().toByteArray());
    session.sendCompose(requestWithSourceCost(session, raw.toByteArray()));
    var response = session.messages.get("ComposeItemScRsp");
    assertNotNull(response);
    assertNotEquals(
        0, response.getField(response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(4, count(110402));
    assertEquals(0, count(110401));
    assertNull(player.getGameplay().getCompositionLimits().getLastAttempt());
    assertFalse(session.messages.containsKey("ComposeLimitNumUpdateScNotify"));
  }

  @Test
  void actualPlayerTicksRefreshConnectedNativeQuotaSnapshotOnceAcrossTheMonthAndWeek() {
    stock(235, 1600);
    stock(110402, 40);
    assertNotNull(monthly(2, october));
    assertNotNull(weekly(20, october));
    var session = new NativeSession();
    player.setSession(session);
    player.onTick(october.toEpochMilli(), 1000);
    var first = session.messages.get("ComposeLimitNumCompleteScNotify");
    assertNotNull(first);
    var initial = nativeUsed(first);
    assertEquals(2, initial.get(101));
    assertEquals(20, initial.get(801));
    session.messages.clear();
    player.onTick(october.plusSeconds(1).toEpochMilli(), 1000);
    assertFalse(session.messages.containsKey("ComposeLimitNumCompleteScNotify"));
    var november = java.time.Instant.parse("2026-11-02T12:00:00Z");
    player.onTick(november.toEpochMilli(), 1000);
    var refreshed = session.messages.get("ComposeLimitNumCompleteScNotify");
    assertNotNull(refreshed);
    var now = nativeUsed(refreshed);
    assertEquals(0, now.get(101));
    assertEquals(0, now.get(801));
    session.messages.clear();
    player.onTick(november.plusSeconds(1).toEpochMilli(), 1000);
    assertFalse(session.messages.containsKey("ComposeLimitNumCompleteScNotify"));
    assertEquals(2, count(236));
    assertEquals(20, count(110401));
    assertEquals(0, count(235));
    assertEquals(0, count(110402));
  }

  Map<Integer, Integer> nativeUsed(DynamicMessage snapshot) {
    var result = new HashMap<Integer, Integer>();
    for (var value :
        (List<?>) snapshot.getField(snapshot.getDescriptorForType().findFieldByNumber(4))) {
      var r = (DynamicMessage) value;
      int id = (Integer) r.getField(r.getDescriptorForType().findFieldByNumber(9));
      int used = (Integer) r.getField(r.getDescriptorForType().findFieldByNumber(1));
      assertNull(result.put(id, used));
    }
    return result;
  }
}
