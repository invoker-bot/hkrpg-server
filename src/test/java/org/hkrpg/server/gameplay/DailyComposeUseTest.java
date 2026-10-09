package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.inventory.tabs.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original source rows; prerequisite mission states and database saves are isolated fixtures. */
class DailyComposeUseTest {
  final Gson gson = new Gson();
  JsonArray fixture;
  final List<Runnable> restorations = new ArrayList<>();
  boolean interruptDebit;
  List<GameplayDefinitions.DailyPool> oldPool;
  final Player player =
      new Player() {
        final LineupManager teams = new LineupManager(this);
        final Inventory actual =
            new Inventory(this) {
              public List<GameItem> addItems(List<GameItem> items, boolean hint) {
                var persistent =
                    items.stream().map(i -> savedItem(i.getExcel(), i.getCount())).toList();
                return super.addItems(persistent, hint);
              }
            };

        public Inventory getInventory() {
          return actual;
        }

        public LineupManager getLineupManager() {
          return teams;
        }

        public void save() {
          var receipt = getGameplay().getLastItemUse();
          if (interruptDebit && receipt != null && "DEBITED".equals(receipt.getPhase()))
            throw new IllegalStateException("Isolated save interruption after actual item debit");
        }

        public void sendPacket(BasePacket packet) {}
      };
  final InventoryService service = new InventoryService(null);

  GameItem savedItem(ItemExcel excel, int count) {
    return new GameItem(excel, count) {
      public void save() {}
    };
  }

  <T extends GameResource> void install(ExcelMap<T> map, T row) {
    var old = map.get(row.getId());
    restorations.add(
        () -> {
          map.keySet().remove(row.getId());
          if (old != null) map.add(old);
        });
    map.add(row);
  }

  JsonObject row(String kind, String key, int id) {
    return fixture.asList().stream()
        .map(JsonElement::getAsJsonObject)
        .filter(
            r ->
                kind.equals(r.get("kind").getAsString())
                    && r.getAsJsonObject("row").get(key).getAsInt() == id)
        .findFirst()
        .orElseThrow()
        .getAsJsonObject("row")
        .deepCopy();
  }

  @BeforeEach
  void setup() throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/daily-compose-use-4.6.json")) {
      assertNotNull(in);
      fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    oldPool = new ArrayList<>(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    for (var value : fixture) {
      var r = value.getAsJsonObject();
      var raw = r.getAsJsonObject("row").deepCopy();
      switch (r.get("kind").getAsString()) {
        case "quest" -> install(GameData.getQuestExcelMap(), gson.fromJson(raw, QuestExcel.class));
        case "rule" ->
            install(GameData.getFinishWayExcelMap(), gson.fromJson(raw, FinishWayExcel.class));
        case "reward" -> {
          var reward = gson.fromJson(raw, RewardExcel.class);
          reward.onLoad();
          install(GameData.getRewardExcelMap(), reward);
        }
        case "item" -> {
          raw.addProperty("ItemName", raw.getAsJsonObject("ItemName").get("Hash").getAsLong());
          install(GameData.getItemExcelMap(), gson.fromJson(raw, ItemExcel.class));
        }
        case "recipe" ->
            install(GameData.getItemComposeExcelMap(), gson.fromJson(raw, ItemComposeExcel.class));
        case "use" ->
            install(GameData.getItemUseExcelMap(), gson.fromJson(raw, ItemUseExcel.class));
      }
    }
    for (int id : List.of(1010403, 4020102)) {
      var state = new QuestState(id);
      state.advance(1, 1, 1);
      player.getGameplay().getMainMissions().put(id, state);
    }
    player.setNickname("Compose regression");
    player.setWorldLevel(3);
    player.addSCoin(10000);
    player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));
  }

  @AfterEach
  void cleanup() {
    Collections.reverse(restorations);
    restorations.forEach(Runnable::run);
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
  }

  QuestState select(int id) {
    GameplayDefinitions.dailyPool.add(
        gson.fromJson(row("pool", "QuestID", id), GameplayDefinitions.DailyPool.class));
    player.getDailyManager().ensureDay();
    return player.getGameplay().getDailyQuests().get(id);
  }

  GameItem stock(int id, int count) {
    var item = savedItem(GameData.getItemExcelMap().get(id), count);
    assertTrue(player.getInventory().addItem(item));
    return player.getInventory().getMaterialByItemId(id);
  }

  void ingredients(int recipe, int count) {
    for (var p : GameData.getItemComposeExcelMap().get(recipe).getMaterialCost())
      stock(p.getId(), p.getCount() * count);
  }

  int count(int id) {
    var item = player.getInventory().getMaterialByItemId(id);
    return item == null ? 0 : item.getCount();
  }

  void completeAndClaim(QuestState q) {
    assertEquals(1, q.getProgress());
    assertEquals(QuestState.Phase.FINISHED, q.getPhase());
    assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(q.getQuestId()).retcode());
    assertEquals(100, player.getGameplay().getDailyPoints());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN,
        player.getDailyManager().claimQuest(q.getQuestId()).retcode());
    assertEquals(100, player.getGameplay().getDailyPoints());
  }

  @ParameterizedTest
  @ValueSource(ints = {2100111, 2100112, 2100148, 2100155, 2100114})
  void originalObjectiveIsSelectedWithoutCompletingIt(int id) {
    var q = select(id);
    assertNotNull(q, "Original compose/use objective not selected");
    assertEquals(0, q.getProgress());
    assertEquals(QuestState.Phase.DOING, q.getPhase());
    assertEquals(0, player.getGameplay().getDailyPoints());
  }

  @ParameterizedTest
  @ValueSource(ints = {2100111, 2100112, 2100148, 2100155})
  void actualOriginalCompositionPaysMaterialsThenCompletesAndClaimsOnce(int id) {
    var q = select(id);
    assertNotNull(q);
    int recipe = id == 2100112 ? 102 : 2101;
    ingredients(recipe, 2);
    var output = service.composeItem(player, recipe, 2, List.of());
    assertNotNull(output);
    assertEquals(1, output.size());
    assertEquals(2, output.getFirst().getCount());
    int outputId = GameData.getItemComposeExcelMap().get(recipe).getItemID();
    assertEquals(2, count(outputId));
    for (var cost : GameData.getItemComposeExcelMap().get(recipe).getMaterialCost())
      assertEquals(0, count(cost.getId()));
    assertEquals(10000, player.getScoin());
    completeAndClaim(q);
  }

  @Test
  void unrelatedOutputSubtypeCannotCompleteTheFoodGoal() {
    var q = select(2100111);
    ingredients(102, 1);
    assertNotNull(service.composeItem(player, 102, 1, List.of()));
    assertEquals(0, q.getProgress());
    ingredients(2101, 1);
    assertNotNull(service.composeItem(player, 2101, 1, List.of()));
    completeAndClaim(q);
  }

  @Test
  void failedCompositionCannotIncreaseDailyProgress() {
    var q = select(2100148);
    stock(180001, 1);
    assertNull(service.composeItem(player, 2101, 1, List.of()));
    assertEquals(1, count(180001));
    assertEquals(0, q.getProgress());
    assertEquals(0, count(400004));
  }

  @Test
  void originalCreditCostIsPaidExactlyOnceAndInsufficientFundsRejectBeforeMaterials() {
    var q = select(2100148);
    ingredients(201, 2);
    assertNotNull(service.composeItem(player, 201, 2, List.of()));
    assertEquals(9960, player.getScoin());
    assertEquals(2, count(110112));
    assertEquals(0, count(110111));
    completeAndClaim(q);
    ingredients(201, 1);
    player.addSCoin(-9950);
    assertNull(service.composeItem(player, 201, 1, List.of()));
    assertEquals(10, player.getScoin());
    assertEquals(3, count(110111));
    assertEquals(2, count(110112));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "world",
        "recipe",
        "monthly",
        "max",
        "pile",
        "capacity",
        "duplicate",
        "overflow",
        "owner",
        "locked"
      })
  void invalidCompositionDoesNotDebitOrComplete(String kind) {
    var q = select(2100148);
    int recipe = 2101, amount = 1;
    if (kind.equals("world")) {
      recipe = 102;
      player.setWorldLevel(2);
    }
    if (kind.equals("recipe")) recipe = 2102;
    if (kind.equals("monthly")) {
      recipe = 101;
      amount = 3; // Actual source monthly limit is two; enough stock cannot bypass it.
    }
    ingredients(recipe, amount);
    var raw = row("recipe", "ID", recipe);
    switch (kind) {
      case "max" -> {
        amount = 10000;
        ingredients(2101, 9999);
      }
      case "pile" -> stock(400004, 99999);
      case "capacity" -> {
        ingredients(2101, 1); // Both source stacks remain after this debit, so no slot is released.
        var tab = player.getInventory().getTab(InventoryTabType.MATERIAL);
        // Keep actual existing materials but constrain new output capacity.
        var limited = new MaterialInventoryTab(tab.getSize());
        for (var item : player.getInventory().getItems().values()) limited.onAddItem(item);
        player.getInventory().createTab(InventoryTabType.MATERIAL, limited);
      }
      case "duplicate" ->
          raw.getAsJsonArray("MaterialCost")
              .add(raw.getAsJsonArray("MaterialCost").get(0).deepCopy());
      case "overflow" -> {
        amount = 2;
        ingredients(2101, 1);
        raw.addProperty("CoinCost", Integer.MAX_VALUE);
      }
      case "owner" ->
          player
              .getInventory()
              .getMaterialByItemId(180001)
              .setOwner(
                  new Player() {
                    public int getUid() {
                      return 88;
                    }
                  });
      case "locked" -> player.getInventory().getMaterialByItemId(180001).setLocked(true);
    }
    GameData.getItemComposeExcelMap().add(gson.fromJson(raw, ItemComposeExcel.class));
    var before = new HashMap<Integer, Integer>();
    for (var item : player.getInventory().getItems().values())
      before.put(item.getItemId(), item.getCount());
    assertNull(service.composeItem(player, recipe, amount, List.of()), kind);
    assertEquals(10000, player.getScoin(), kind);
    assertEquals(0, q.getProgress(), kind);
    var after = new HashMap<Integer, Integer>();
    for (var item : player.getInventory().getItems().values())
      after.put(item.getItemId(), item.getCount());
    assertEquals(before, after, kind);
  }

  @Test
  void concurrentCompositionsCannotSpendTheSameLastIngredientsTwice() throws Exception {
    var q = select(2100148);
    ingredients(2101, 1);
    var gate = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var a =
          workers.submit(
              () -> {
                gate.await();
                return service.composeItem(player, 2101, 1, List.of());
              });
      var b =
          workers.submit(
              () -> {
                gate.await();
                return service.composeItem(player, 2101, 1, List.of());
              });
      gate.countDown();
      var first = a.get(10, TimeUnit.SECONDS);
      var second = b.get(10, TimeUnit.SECONDS);
      assertEquals(1, (first != null ? 1 : 0) + (second != null ? 1 : 0));
      assertEquals(1, count(400004));
      assertEquals(0, count(180001));
      assertEquals(0, count(180002));
      completeAndClaim(q);
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void originalSpecialExchangeUsesOnlyTheOwnedAggregateMaterials() {
    var q = select(2100148);
    stock(111011, 2);
    assertNotNull(service.composeItem(player, 501, 1, List.of(new ItemParam(111011, 2))));
    assertEquals(0, count(111011));
    assertEquals(1, count(111001));
    completeAndClaim(q);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"negative", "zero", "duplicate", "unique", "weekly", "unlisted", "overStock"})
  void invalidSpecialExchangeCannotDebit(String kind) {
    var q = select(2100148);
    stock(111011, 1);
    int recipe = kind.equals("weekly") ? 801 : 501;
    if (kind.equals("weekly")) stock(110402, 42);
    List<ItemParam> costs =
        switch (kind) {
          case "negative" -> List.of(new ItemParam(111011, -1), new ItemParam(111011, 3));
          case "zero" -> List.of(new ItemParam(111011, 0), new ItemParam(111011, 2));
          case "duplicate" -> List.of(new ItemParam(111011, 1), new ItemParam(111011, 1));
          case "unique" ->
              List.of(
                  new ItemParam(
                      ItemParam.ItemParamType.UNIQUE,
                      player.getInventory().getMaterialByItemId(111011).getInternalUid(),
                      2));
          case "unlisted" -> List.of(new ItemParam(180001, 2));
          case "weekly" -> List.of(new ItemParam(110402, 42));
          default -> List.of(new ItemParam(111011, 2));
        };
    assertNull(service.composeItem(player, recipe, kind.equals("weekly") ? 21 : 1, costs), kind);
    assertEquals(1, count(111011));
    assertEquals(0, count(111001));
    assertEquals(0, q.getProgress());
    if (kind.equals("weekly")) {
      assertEquals(42, count(110402));
      assertEquals(0, count(110401));
    }
  }

  GameAvatar hungryActor() {
    var actor =
        new GameAvatar() {
          public int getAvatarId() {
            return 1202;
          }

          public void save() {}
        };
    player.getAvatars().getAvatars().put(1202, actor);
    player.getCurrentLineup().getAvatars().add(1202);
    actor.setCurrentHp(player.getCurrentLineup(), 8000);
    actor.setCurrentSp(player.getCurrentLineup(), 1000);
    return actor;
  }

  @Test
  void originalFoodActualEnergyEffectCompletesAndClaimsOnce() {
    var q = select(2100114);
    stock(401006, 1);
    var actor = hungryActor();
    var result = new ConsumableUse(player).use(401006, 1, 1202);
    assertEquals(Retcode.SUCC, result.retcode());
    assertEquals(1, result.consumed());
    assertEquals(0, count(401006));
    assertEquals(4000, actor.getCurrentSp(player.getCurrentLineup()));
    completeAndClaim(q);
    assertEquals(Retcode.ITEM_NOT_EXIST, new ConsumableUse(player).use(401006, 1, 1202).retcode());
    assertEquals(1, q.getProgress());
  }

  @Test
  void rejectedFoodUseCannotCompleteTheGoalOrConsumeFood() {
    var q = select(2100114);
    stock(401006, 1);
    hungryActor();
    assertNotEquals(Retcode.SUCC, new ConsumableUse(player).use(401006, 1, 99999).retcode());
    assertEquals(1, count(401006));
    assertEquals(0, q.getProgress());
  }

  @ParameterizedTest
  @ValueSource(strings = {"comparison", "integer", "text", "list", "items", "progress", "type"})
  void unknownCompositionRuleChangedAfterSelectionCannotComplete(String kind) {
    var q = select(2100111);
    var raw = row("rule", "ID", 2100111);
    switch (kind) {
      case "comparison" -> raw.addProperty("ParamType", "Equal");
      case "integer" -> raw.addProperty("ParamInt2", 1);
      case "text" -> raw.addProperty("ParamStr1", "unresolved");
      case "list" -> raw.getAsJsonArray("ParamIntList").add(601);
      case "items" -> {
        var values = new JsonArray();
        values.add(new JsonObject());
        raw.add("ParamItemList", values);
      }
      case "progress" -> raw.addProperty("Progress", 2);
      case "type" -> raw.addProperty("FinishType", "Unknown");
    }
    GameData.getFinishWayExcelMap().add(gson.fromJson(raw, FinishWayExcel.class));
    ingredients(2101, 1);
    assertNotNull(service.composeItem(player, 2101, 1, List.of()));
    assertEquals(0, q.getProgress());
    assertEquals(QuestState.Phase.DOING, q.getPhase());
  }

  @ParameterizedTest
  @ValueSource(strings = {"comparison", "integer", "text", "list", "items", "progress", "type"})
  void unknownFoodRuleChangedAfterSelectionCannotComplete(String kind) {
    var q = select(2100114);
    var raw = row("rule", "ID", 2100114);
    switch (kind) {
      case "comparison" -> raw.addProperty("ParamType", "ListContain");
      case "integer" -> raw.addProperty("ParamInt1", 601);
      case "text" -> raw.addProperty("ParamStr1", "unresolved");
      case "list" -> {
        var values = new JsonArray();
        values.add(502);
        raw.add("ParamIntList", values);
      }
      case "items" -> {
        var values = new JsonArray();
        values.add(new JsonObject());
        raw.add("ParamItemList", values);
      }
      case "progress" -> raw.addProperty("Progress", 2);
      case "type" -> raw.addProperty("FinishType", "Unknown");
    }
    GameData.getFinishWayExcelMap().add(gson.fromJson(raw, FinishWayExcel.class));
    stock(401006, 1);
    hungryActor();
    assertEquals(Retcode.SUCC, new ConsumableUse(player).use(401006, 1, 1202).retcode());
    assertEquals(0, q.getProgress());
    assertEquals(QuestState.Phase.DOING, q.getPhase());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "nullFormula",
        "unknownFormula",
        "nullFunction",
        "nullCosts",
        "unknownLimit",
        "limitArguments",
        "negativeLimit",
        "negativeWorld",
        "negativeCoins",
        "zeroMaximum"
      })
  void unknownRecipeShapesReturnFailureBeforeDebits(String kind) {
    var q = select(2100148);
    ingredients(2101, 1);
    var raw = row("recipe", "ID", 2101);
    switch (kind) {
      case "nullFormula" -> raw.add("FormulaType", JsonNull.INSTANCE);
      case "unknownFormula" -> raw.addProperty("FormulaType", "Unidentified46");
      case "nullFunction" -> {
        raw.addProperty("FormulaType", "Sepcial");
        raw.add("FuncType", JsonNull.INSTANCE);
      }
      case "nullCosts" -> raw.add("MaterialCost", JsonNull.INSTANCE);
      case "unknownLimit" -> raw.addProperty("LimitType", "Unidentified46");
      case "limitArguments" -> raw.getAsJsonArray("LimitTypeParam").add(1);
      case "negativeLimit" -> raw.addProperty("LimitValue", -1);
      case "negativeWorld" -> raw.addProperty("WorldLevelRequire", -1);
      case "negativeCoins" -> raw.addProperty("CoinCost", -1);
      case "zeroMaximum" -> raw.addProperty("MaxCount", 0);
    }
    GameData.getItemComposeExcelMap().add(gson.fromJson(raw, ItemComposeExcel.class));
    assertNull(assertDoesNotThrow(() -> service.composeItem(player, 2101, 1, List.of())), kind);
    assertEquals(1, count(180001));
    assertEquals(1, count(180002));
    assertEquals(0, q.getProgress());
    assertEquals(10000, player.getScoin());
  }

  @Test
  void currentFullBagCanComposeWhenTheActualDebitReleasesSlots() {
    var q = select(2100148);
    ingredients(2101, 1);
    var limited = new MaterialInventoryTab(2);
    for (var item : player.getInventory().getItems().values()) limited.onAddItem(item);
    player.getInventory().createTab(InventoryTabType.MATERIAL, limited);
    assertNotNull(service.composeItem(player, 2101, 1, List.of()));
    assertEquals(1, limited.getSize());
    assertEquals(1, count(400004));
    completeAndClaim(q);
  }

  @Test
  void repeatedSpecialEntriesWithEnoughActualAggregateStockAreAccepted() {
    var q = select(2100148);
    stock(111011, 2);
    assertNotNull(
        service.composeItem(
            player, 501, 1, List.of(new ItemParam(111011, 1), new ItemParam(111011, 1))));
    assertEquals(0, count(111011));
    assertEquals(1, count(111001));
    completeAndClaim(q);
  }

  @Test
  void concurrentFoodUsesApplyOnlyOneActualLastItemEffect() throws Exception {
    var q = select(2100114);
    stock(401006, 1);
    var actor = hungryActor();
    var workers = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      var a =
          workers.submit(
              () -> {
                gate.await();
                return new ConsumableUse(player).use(401006, 1, 1202);
              });
      var b =
          workers.submit(
              () -> {
                gate.await();
                return new ConsumableUse(player).use(401006, 1, 1202);
              });
      gate.countDown();
      var first = a.get(10, TimeUnit.SECONDS);
      var second = b.get(10, TimeUnit.SECONDS);
      assertEquals(
          1,
          (first.retcode() == Retcode.SUCC ? 1 : 0) + (second.retcode() == Retcode.SUCC ? 1 : 0));
      assertEquals(4000, actor.getCurrentSp(player.getCurrentLineup()));
      assertEquals(0, count(401006));
      completeAndClaim(q);
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void interruptedFoodReceiptCannotManufactureTheDailyEvent() {
    var q = select(2100114);
    stock(401006, 1);
    var actor = hungryActor();
    interruptDebit = true;
    assertThrows(IllegalStateException.class, () -> new ConsumableUse(player).use(401006, 1, 1202));
    interruptDebit = false;
    assertEquals(0, count(401006));
    assertEquals(1000, actor.getCurrentSp(player.getCurrentLineup()));
    assertEquals(0, q.getProgress());
    assertTrue(player.getGameplay().getLastItemUse().pending());
    stock(401006, 1);
    assertEquals(Retcode.SYSTEM_BUSY, new ConsumableUse(player).use(401006, 1, 1202).retcode());
    assertEquals(1, count(401006));
    assertEquals(0, q.getProgress());
  }

  @Test
  void queriesAfterFourAmCannotReplayYesterdaysFoodConsumption() {
    player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-04T19:59:59Z"), ZoneOffset.UTC));
    var q = select(2100114);
    stock(401006, 2);
    hungryActor();
    assertEquals(Retcode.SUCC, new ConsumableUse(player).use(401006, 1, 1202).retcode());
    completeAndClaim(q);
    player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-04T20:00:00Z"), ZoneOffset.UTC));
    player.getDailyManager().ensureDay();
    var fresh = player.getGameplay().getDailyQuests().get(2100114);
    assertNotSame(q, fresh);
    assertEquals(0, fresh.getProgress());
    assertEquals(0, player.getGameplay().getDailyPoints());
    assertEquals(Retcode.SUCC, new ConsumableUse(player).use(401006, 1, 1202).retcode());
    completeAndClaim(fresh);
  }
}
