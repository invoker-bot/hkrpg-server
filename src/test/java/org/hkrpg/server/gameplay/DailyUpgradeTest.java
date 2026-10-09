package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original upgrade goals and XP rows; prerequisite mission completion is an explicit fixture. */
class DailyUpgradeTest {
  final Gson gson = new Gson();
  JsonArray fixture;
  List<GameplayDefinitions.DailyPool> oldPool;
  final List<Runnable> restorations = new ArrayList<>();
  boolean detachedActorLookup;
  final Player player =
      new Player() {
        public void save() {}

        public void sendPacket(BasePacket packet) {}

        public GameAvatar getAvatarById(int id) {
          return detachedActorLookup && id == 1202 ? avatar : super.getAvatarById(id);
        }
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

  <T extends MultiKeyGameResource> void install(MultiKeyExcelMap<T> map, T row) {
    var old = map.get(row.getPrimaryKey(), row.getSecondaryKey());
    restorations.add(
        () -> {
          map.values()
              .removeIf(
                  r ->
                      r.getPrimaryKey() == row.getPrimaryKey()
                          && r.getSecondaryKey() == row.getSecondaryKey());
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
    try (var in = getClass().getResourceAsStream("/fixtures/daily-upgrade-4.6.json")) {
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
        case "avatar" -> {
          raw.addProperty("AvatarName", raw.getAsJsonObject("AvatarName").get("Hash").getAsLong());
          install(GameData.getAvatarExcelMap(), gson.fromJson(raw, AvatarExcel.class));
        }
        case "avatar-promotion" ->
            install(
                GameData.getAvatarPromotionExcelMap(),
                gson.fromJson(raw, AvatarPromotionExcel.class));
        case "equipment-promotion" ->
            install(
                GameData.getEquipmentPromotionExcelMap(),
                gson.fromJson(raw, EquipmentPromotionExcel.class));
        case "equipment" -> {
          var equip = gson.fromJson(raw, EquipmentExcel.class);
          install(GameData.getEquipExcelMap(), equip);
          equip.onLoad();
        }
        case "relic" -> {
          var relic = gson.fromJson(raw, RelicExcel.class);
          install(GameData.getRelicExcelMap(), relic);
          relic.onLoad();
        }
        case "avatar-exp" ->
            install(GameData.getExpTypeExcelMap(), gson.fromJson(raw, ExpTypeExcel.class));
        case "equipment-exp" ->
            install(
                GameData.getEquipmentExpTypeExcelMap(),
                gson.fromJson(raw, EquipmentExpTypeExcel.class));
        case "relic-exp" ->
            install(
                GameData.getRelicExpTypeExcelMap(), gson.fromJson(raw, RelicExpTypeExcel.class));
        case "avatar-material" ->
            GameData.getItemExcelMap()
                .get(raw.get("ItemID").getAsInt())
                .setAvatarExp(raw.get("Exp").getAsInt());
        case "equipment-material" -> {
          var item = GameData.getItemExcelMap().get(raw.get("ItemID").getAsInt());
          item.setEquipmentExp(raw.get("ExpProvide").getAsInt());
          item.setExpCost(raw.get("CoinCost").getAsInt());
        }
        case "relic-material" -> {
          var item = GameData.getItemExcelMap().get(raw.get("ItemID").getAsInt());
          item.setRelicExp(raw.get("ExpProvide").getAsInt());
          item.setExpCost(raw.get("CoinCost").getAsInt());
        }
      }
    }
    GameData.getAvatarExcelMap().get(1202).onLoad();
    // Original unlock rules remain unchanged. These two prerequisite states are test-only.
    for (int id : List.of(1000202, 1010503)) {
      var state = new QuestState(id);
      state.advance(1, 1, 1);
      player.getGameplay().getMainMissions().put(id, state);
    }
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

  @ParameterizedTest
  @ValueSource(ints = {2100107, 2100146, 2100110, 2100147, 2100108, 2100154})
  void originalUpgradeObjectiveIsSelectedAndStartsAtZero(int id) {
    var state = select(id);
    assertNotNull(state, "Original upgrade daily objective not selected");
    assertEquals(0, state.getProgress());
    assertEquals(QuestState.Phase.DOING, state.getPhase());
    assertEquals(0, player.getGameplay().getDailyPoints());
  }

  final InventoryService service = new InventoryService(null);
  GameAvatar avatar;
  GameItem target, material;
  int quest;

  GameItem item(int id, int count) {
    var item =
        new GameItem(GameData.getItemExcelMap().get(id), count) {
          public void save() {}
        };
    item.setOwner(player);
    player.getInventory().getItems().put(item.getInternalUid(), item);
    player.getInventory().getTabByItemType(item.getItemMainType()).onAddItem(item);
    return item;
  }

  String type() {
    return GameData.getFinishWayExcelMap().get(quest).getFinishType();
  }

  void prepare(int id) {
    quest = id;
    assertNotNull(select(id));
    player.setNickname("original-daily-upgrade");
    player.addSCoin(100000);
    avatar =
        new GameAvatar(GameData.getAvatarExcelMap().get(1202)) {
          public void save() {}
        };
    avatar.setOwner(player);
    player.getAvatars().getAvatars().put(1202, avatar);
    switch (type()) {
      case "AnyAvatarLevelUp" -> material = item(211, 10);
      case "AnyEquipmentLevelUp" -> {
        target = item(20000, 1);
        material = item(221, 10);
      }
      case "AnyRelicLevelUp" -> {
        target = item(31011, 1);
        material = item(232, 10);
      }
    }
  }

  List<GameItem> upgrade(List<ItemParam> items) {
    return switch (type()) {
      case "AnyAvatarLevelUp" -> service.levelUpAvatar(player, 1202, items);
      case "AnyEquipmentLevelUp" ->
          service.levelUpEquipment(player, target.getInternalUid(), items);
      case "AnyRelicLevelUp" -> service.levelUpRelic(player, target.getInternalUid(), items);
      default -> throw new AssertionError("Unknown authored upgrade rule");
    };
  }

  int level() {
    return target == null ? avatar.getLevel() : target.getLevel();
  }

  int exp() {
    return target == null ? avatar.getExp() : target.getExp();
  }

  QuestState state() {
    return player.getGameplay().getDailyQuests().get(quest);
  }

  List<ItemParam> one() {
    return List.of(new ItemParam(material.getItemId(), 1));
  }

  @ParameterizedTest
  @ValueSource(ints = {2100107, 2100146, 2100110, 2100147, 2100108, 2100154})
  void aRealSourceUpgradeConsumesTheMaterialAndCreditsTheOriginalDailyOnce(int id) {
    prepare(id);
    int before = level();
    int coins = player.getScoin();
    int sourceCost =
        switch (type()) {
          case "AnyAvatarLevelUp" -> material.getExcel().getAvatarExp() / 10;
          case "AnyEquipmentLevelUp" -> material.getExcel().getEquipmentExpCost();
          case "AnyRelicLevelUp" -> material.getExcel().getRelicExpCost();
          default -> throw new AssertionError("Unknown source cost");
        };
    assertNotNull(upgrade(one()));
    assertTrue(level() > before);
    assertEquals(9, material.getCount());
    assertEquals(coins - sourceCost, player.getScoin());
    assertEquals(1, state().getProgress());
    assertEquals(QuestState.Phase.FINISHED, state().getPhase());
    assertEquals(Retcode.SUCC, player.getDailyManager().claimQuest(id).retcode());
    assertEquals(100, player.getGameplay().getDailyPoints());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getDailyManager().claimQuest(id).retcode());
    assertEquals(100, player.getGameplay().getDailyPoints());
  }

  @ParameterizedTest
  @ValueSource(ints = {2100107, 2100110, 2100108})
  void validXpBelowTheNextLevelDoesNotCompleteTheDaily(int id) {
    prepare(id);
    if (target == null) avatar.setLevel(19);
    else if (target.getItemId() == 20000) target.setLevel(19);
    else {
      material = item(231, 10);
    }
    int before = level();
    assertNotNull(upgrade(one()));
    assertEquals(before, level());
    assertTrue(exp() > 0);
    assertEquals(0, state().getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {2100107, 2100110, 2100108})
  void aCappedTargetRejectsTheUpgradeBeforeChargingOrConsumingAnything(int id) {
    prepare(id);
    if (target == null) avatar.setLevel(20);
    else target.setLevel(target.getItemId() == 20000 ? 20 : 6);
    int coins = player.getScoin();
    assertNull(upgrade(one()));
    assertEquals(coins, player.getScoin());
    assertEquals(10, material.getCount());
    assertEquals(0, state().getProgress());
  }

  static Stream<Arguments> invalidMaterials() {
    return Stream.of(2100107, 2100110, 2100108)
        .flatMap(
            id ->
                Stream.of(
                        "negative",
                        "zero",
                        "duplicate",
                        "overflow",
                        "borrowed",
                        "locked",
                        "wrong-xp")
                    .map(mode -> Arguments.of(id, mode)));
  }

  @ParameterizedTest
  @MethodSource("invalidMaterials")
  void invalidMaterialsCannotChargeAdvanceXpOrCompleteDaily(int id, String mode) throws Exception {
    prepare(id);
    List<ItemParam> params = one();
    switch (mode) {
      case "negative" -> params = List.of(new ItemParam(material.getItemId(), -1));
      case "zero" -> params = List.of(new ItemParam(material.getItemId(), 0));
      case "duplicate" -> {
        material.setCount(1);
        params =
            List.of(new ItemParam(material.getItemId(), 1), new ItemParam(material.getItemId(), 1));
      }
      case "overflow" -> {
        material.setCount(Integer.MAX_VALUE);
        params = List.of(new ItemParam(material.getItemId(), Integer.MAX_VALUE));
      }
      case "borrowed" -> {
        var owner = GameItem.class.getDeclaredField("ownerUid");
        owner.setAccessible(true);
        owner.setInt(material, player.getUid() + 1);
      }
      case "locked" -> material.setLocked(true);
      case "wrong-xp" -> {
        var excel = material.getExcel();
        excel.setAvatarExp(0);
        excel.setEquipmentExp(0);
        excel.setRelicExp(0);
      }
    }
    int before = level(), stock = material.getCount(), coins = player.getScoin();
    assertNull(upgrade(params));
    assertEquals(before, level());
    assertEquals(0, exp());
    assertEquals(stock, material.getCount());
    assertEquals(coins, player.getScoin());
    assertEquals(0, state().getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {2100107, 2100110, 2100108})
  void concurrentAttemptsConsumeOneAvailableSourceMaterialAndCreditOneDaily(int id)
      throws Exception {
    prepare(id);
    material.setCount(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var result = workers.invokeAll(List.of(() -> upgrade(one()), () -> upgrade(one())));
      int accepted = 0;
      for (var item : result) if (item.get() != null) accepted++;
      assertEquals(1, accepted);
    }
    assertEquals(0, material.getCount());
    assertEquals(1, state().getProgress());
  }

  @ParameterizedTest
  @ValueSource(strings = {"comparison", "number", "text", "list", "items", "progress"})
  void aHotChangedUnknownDailyRuleCannotBorrowAnOriginalUpgrade(String mode) {
    prepare(2100107);
    var raw = row("rule", "ID", quest);
    switch (mode) {
      case "comparison" -> raw.addProperty("ParamType", "Equal");
      case "number" -> raw.addProperty("ParamInt1", 1);
      case "text" -> raw.addProperty("ParamStr1", "Unknown");
      case "list" -> raw.add("ParamIntList", JsonParser.parseString("[1]"));
      case "items" -> raw.add("ParamItemList", JsonParser.parseString("[{}]"));
      case "progress" -> raw.addProperty("Progress", 2);
    }
    GameData.getFinishWayExcelMap().add(gson.fromJson(raw, FinishWayExcel.class));
    assertNotNull(upgrade(one()));
    assertTrue(level() > 1);
    assertEquals(0, state().getProgress());
  }

  @Test
  void aSuccessfulUpgradeAfterFourAmCreditsTheNewDailyState() {
    prepare(2100107);
    var before = state();
    player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T20:00:00Z"), ZoneOffset.UTC));
    assertNotNull(upgrade(one()));
    assertNotSame(before, state());
    assertEquals(0, before.getProgress());
    assertEquals(1, state().getProgress());
  }

  @Test
  void aDetachedTrialActorCannotReceiveFormalUpgradeXpOrDailyCredit() {
    prepare(2100107);
    player.getAvatars().getAvatars().remove(1202);
    detachedActorLookup = true; // Simulate the existing trial-before-formal actor lookup.
    int coins = player.getScoin();
    assertNull(upgrade(one()));
    assertEquals(1, avatar.getLevel());
    assertEquals(10, material.getCount());
    assertEquals(coins, player.getScoin());
    assertEquals(0, state().getProgress());
  }

  @Test
  void relicTotalXpOverflowIsRejectedBeforeChargingOrConsumingTheSourceMaterial() {
    prepare(2100108);
    target.setTotalExp(Integer.MAX_VALUE);
    int coins = player.getScoin();
    assertNull(upgrade(one()));
    assertEquals(Integer.MAX_VALUE, target.getTotalExp());
    assertEquals(0, target.getLevel());
    assertEquals(10, material.getCount());
    assertEquals(coins, player.getScoin());
    assertEquals(0, state().getProgress());
  }
}
