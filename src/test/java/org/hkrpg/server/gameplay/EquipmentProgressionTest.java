package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.common.ItemParam;
import emu.lunarcore.data.common.ItemParam.ItemParamType;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.GameItem;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real domain mutations with pinned source XP data; malformed selections must be no-ops. */
class EquipmentProgressionTest {
  final DailyUpgradeTest f = new DailyUpgradeTest();
  final Gson gson = new Gson();

  @BeforeEach void setup() throws Exception {
    f.setup();
    try (var in = getClass().getResourceAsStream("/fixtures/equipment-progression-4.6.json")) {
      var source = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
      for (var file : source.getAsJsonArray("files")) {
        String path = file.getAsJsonObject().get("path").getAsString();
        for (var entry : file.getAsJsonObject().getAsJsonArray("rows")) {
          var row = entry.getAsJsonObject().getAsJsonObject("row").deepCopy();
          if (path.endsWith("EquipmentConfig.json")) f.install(GameData.getEquipExcelMap(), gson.fromJson(row, EquipmentExcel.class));
          else {
            row.add("ItemName", row.getAsJsonObject("ItemName").get("Hash"));
            f.install(GameData.getItemExcelMap(), gson.fromJson(row, ItemExcel.class));
          }
        }
      }
      for (int id : List.of(22000, 24000)) GameData.getEquipExcelMap().get(id).onLoad();
    }
  }
  @AfterEach void cleanup() { f.cleanup(); }

  ItemParam unique(GameItem item) { return new ItemParam(ItemParamType.UNIQUE, item.getInternalUid(), 1); }

  @ParameterizedTest
  @ValueSource(strings = {"self", "duplicate", "locked", "equipped", "zero", "negative", "empty", "wrong-kind", "overflow"})
  void invalidSuperimpositionCannotLoseItemsOrInventRanks(String kind) throws Exception {
    f.prepare(2100110);
    var material = f.item(20000, 1);
    List<ItemParam> costs = List.of(unique(material));
    switch (kind) {
      case "self" -> costs = List.of(unique(f.target));
      case "duplicate" -> costs = List.of(unique(material), unique(material));
      case "locked" -> material.setLocked(true);
      case "equipped" -> {
        var field = GameItem.class.getDeclaredField("equipAvatarId"); field.setAccessible(true);
        field.set(material, new org.bson.types.ObjectId());
      }
      case "zero" -> costs = List.of(new ItemParam(ItemParamType.UNIQUE, material.getInternalUid(), 0));
      case "negative" -> costs = List.of(new ItemParam(ItemParamType.UNIQUE, material.getInternalUid(), -1));
      case "empty" -> costs = List.of();
      case "wrong-kind" -> costs = List.of(new ItemParam(20000, 1));
      case "overflow" -> { f.target.setRank(4); material.setRank(2); }
    }
    int rank = f.target.getRank(), credits = f.player.getScoin();
    assertFalse(f.service.rankUpEquipment(f.player, f.target.getInternalUid(), costs));
    assertSame(f.target, f.player.getInventory().getItemByUid(f.target.getInternalUid()));
    assertSame(material, f.player.getInventory().getItemByUid(material.getInternalUid()));
    assertEquals(1, f.target.getCount()); assertEquals(1, material.getCount());
    assertEquals(rank, f.target.getRank()); assertEquals(credits, f.player.getScoin());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingNextLevelCurveRejectsBeforeAnyDebit(boolean relic) {
    f.prepare(relic ? 2100108 : 2100110);
    if (relic) {
      f.install(GameData.getRelicExpTypeExcelMap(), gson.fromJson("{\"TypeID\":1,\"Level\":1}", RelicExpTypeExcel.class));
    } else {
      f.install(GameData.getEquipmentExpTypeExcelMap(), gson.fromJson("{\"ExpType\":1,\"Level\":2}", EquipmentExpTypeExcel.class));
    }
    int level = f.target.getLevel(), credits = f.player.getScoin(), count = f.material.getCount();
    assertNull(f.upgrade(List.of(new ItemParam(f.material.getItemId(), 1))));
    assertEquals(level, f.target.getLevel()); assertEquals(0, f.target.getExp());
    assertEquals(count, f.material.getCount()); assertEquals(credits, f.player.getScoin());
    assertEquals(0, f.state().getProgress());
  }

  @Test void aHigherRankDuplicateContributesItsSavedRankExactlyOnce() {
    f.prepare(2100110);
    var material = f.item(20000, 1); material.setRank(3);
    assertTrue(f.service.rankUpEquipment(f.player, f.target.getInternalUid(), List.of(unique(material))));
    assertEquals(4, f.target.getRank());
    assertNull(f.player.getInventory().getItemByUid(material.getInternalUid()));
    assertFalse(f.service.rankUpEquipment(f.player, f.target.getInternalUid(), List.of(unique(material))));
    assertEquals(4, f.target.getRank());
  }

  @ParameterizedTest
  @ValueSource(ints = {22000, 24000})
  void configuredStackableSuperimposersUseTheirQuantity(int id) {
    f.prepare(2100110);
    var target = f.item(id, 1);
    int materialId = id == 22000 ? 121000 : 271;
    var material = f.item(materialId, 4);
    assertTrue(f.service.rankUpEquipment(f.player, target.getInternalUid(), List.of(new ItemParam(materialId, 3))));
    assertEquals(4, target.getRank()); assertEquals(1, material.getCount());
    assertTrue(f.service.rankUpEquipment(f.player, target.getInternalUid(), List.of(new ItemParam(materialId, 1))));
    assertEquals(5, target.getRank());
    assertNull(f.player.getInventory().getItemByUid(material.getInternalUid()));
  }

  @Test void repeatedStackEntriesAreAggregatedBeforeCheckingOwnedQuantity() {
    f.prepare(2100110);
    var target = f.item(24000, 1); var material = f.item(271, 2);
    assertFalse(f.service.rankUpEquipment(f.player, target.getInternalUid(), List.of(new ItemParam(271, 2), new ItemParam(271, 1))));
    assertEquals(1, target.getRank()); assertEquals(2, material.getCount());
    assertTrue(f.service.rankUpEquipment(f.player, target.getInternalUid(), List.of(new ItemParam(271, 1), new ItemParam(271, 1))));
    assertEquals(3, target.getRank()); assertEquals(0, material.getCount());
  }

  @Test void aLaterInvalidMaterialCannotConsumeAnEarlierValidOne() {
    f.prepare(2100110);
    var material = f.item(20000, 1);
    assertFalse(f.service.rankUpEquipment(f.player, f.target.getInternalUid(), List.of(unique(material), new ItemParam(271, 1))));
    assertEquals(1, f.target.getRank()); assertEquals(1, material.getCount());
  }

  @Test void simultaneousRequestsCanConsumeOneDuplicateOnlyOnce() throws Exception {
    f.prepare(2100110);
    var material = f.item(20000, 1);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 2; i++) results.add(pool.submit(() -> {
        start.await();
        return f.service.rankUpEquipment(f.player, f.target.getInternalUid(), List.of(unique(material)));
      }));
      start.countDown();
      int successes = 0;
      for (var result : results) if (result.get(10, java.util.concurrent.TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes); assertEquals(2, f.target.getRank());
      assertNull(f.player.getInventory().getItemByUid(material.getInternalUid()));
    } finally { pool.shutdownNow(); }
  }

  @Test void lockOnTheTargetDoesNotPreventUpgradingIt() {
    f.prepare(2100110);
    f.target.setLocked(true);
    var material = f.item(20000, 1);
    assertTrue(f.service.rankUpEquipment(f.player, f.target.getInternalUid(), List.of(unique(material))));
    assertEquals(2, f.target.getRank()); assertTrue(f.target.isLocked());
  }
}
