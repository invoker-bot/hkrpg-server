package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original target rules and rewards; stored target history and item delivery are explicit fixtures.
 */
class LocalLegendTargetQuestsTest {
  @TempDir Path root;
  final LocalLegendCatalogTest catalog = new LocalLegendCatalogTest();
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldRules = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<ItemExcel> oldItems = new ArrayList<>();
  Actor actor;

  static class Actor extends Player {
    int saves, grants;
    boolean failSave, partialGrant;
    final Map<Integer, Integer> amounts = new HashMap<>();
    final Inventory delivered =
        new Inventory(this) {
          @Override
          public GrantResult grantItems(ItemParamMap map, boolean hint) {
            grants++;
            var items = new ArrayList<GameItem>();
            map.forEach(
                (id, count) -> {
                  amounts.merge(id, count, Integer::sum);
                  items.add(new GameItem(id, count));
                });
            return new GrantResult(List.copyOf(items), !partialGrant);
          }
        };

    @Override
    public Inventory getInventory() {
      return delivered;
    }

    @Override
    public void save() {
      if (failSave) throw new IllegalStateException("Explicit save failure fixture");
      saves++;
    }

    @Override
    public void sendPacket(BasePacket packet) {}
  }

  @BeforeEach
  void load() throws Exception {
    catalog.root = root;
    catalog.load();
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldRules.addAll(GameData.getFinishWayExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldItems.addAll(GameData.getItemExcelMap().values());
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameData.getItemExcelMap().values().clear();
    try (var stream =
        getClass().getResourceAsStream("/fixtures/local-legend-target-quests-4.6.json")) {
      var rows =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(65, rows.size());
      for (var value : rows) {
        var row = value.getAsJsonObject();
        var data = row.get("row");
        switch (row.get("kind").getAsString()) {
          case "quest" ->
              GameData.getQuestExcelMap()
                  .add(ResourceLoader.getGson().fromJson(data, QuestExcel.class));
          case "rule" ->
              GameData.getFinishWayExcelMap()
                  .add(ResourceLoader.getGson().fromJson(data, FinishWayExcel.class));
          case "reward" -> {
            var reward = ResourceLoader.getGson().fromJson(data, RewardExcel.class);
            reward.onLoad();
            GameData.getRewardExcelMap().add(reward);
          }
          case "item" ->
              GameData.getItemExcelMap()
                  .add(ResourceLoader.getGson().fromJson(data, ItemExcel.class));
        }
      }
    }
    actor = new Actor();
    actor.resetPosition();
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getQuestExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    oldRules.forEach(GameData.getFinishWayExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getItemExcelMap().values().clear();
    oldItems.forEach(GameData.getItemExcelMap()::add);
    catalog.restore();
  }

  @ParameterizedTest
  @CsvSource({
    "1,1,6072101",
    "1,2,6072102",
    "2,1,6072103",
    "2,2,6072104",
    "3,1,6072105",
    "3,2,6072106",
    "4,1,6072107",
    "4,2,6072108",
    "5,1,6072109",
    "5,2,6072110"
  })
  void actualMainTargetUnlocksTheConfiguredNextDifficultyWithoutRequiringEverySideTarget(
      int group, int difficulty, int quest) {
    var level = LocalLegendCatalog.level(group, difficulty);
    actor.getQuestManager().refreshUnlocks();
    assertFalse(QuestCompletionRecords.completed(actor, quest));
    actor.getGameplay().getLocalLegendProgress().merge(level, 1, 100);
    actor.getQuestManager().refreshUnlocks();
    assertTrue(QuestCompletionRecords.completed(actor, quest));
    assertEquals(QuestState.Phase.FINISHED, actor.getQuestStates().get(quest).getPhase());
    assertEquals(0, actor.grants);
  }

  @ParameterizedTest
  @CsvSource({
    "1,1,6072113",
    "1,2,6072114",
    "2,1,6072117",
    "2,2,6072118",
    "3,1,6072121",
    "3,2,6072122",
    "4,1,6072125",
    "4,2,6072126",
    "5,1,6072129",
    "5,2,6072130"
  })
  void originalTypeThreeTargetQuestAppearsAndPaysItsExactSourceRewardOnce(
      int group, int difficulty, int quest) {
    actor
        .getGameplay()
        .getLocalLegendProgress()
        .merge(LocalLegendCatalog.level(group, difficulty), 1, 100);
    actor.getQuestManager().refreshUnlocks();
    assertTrue(
        actor.getQuestManager().states().stream()
            .anyMatch(q -> q.getQuestId() == quest && q.getPhase() == QuestState.Phase.FINISHED));
    assertEquals(Retcode.SUCC, actor.getQuestManager().claim(quest).retcode());
    var expected =
        GameData.getRewardExcelMap()
            .get(GameData.getQuestExcelMap().get(quest).getRewardID())
            .getRewards();
    assertEquals(new HashMap<>(expected), actor.amounts);
    assertEquals(1, actor.grants);
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getQuestManager().claim(quest).retcode());
    assertEquals(1, actor.grants);
  }

  @Test
  void storedFinishedQuestWithoutAnActualTargetCannotSatisfyDifficultyUnlock() {
    var state = new QuestState(6072101);
    state.advance(1, 1, 100);
    actor.getQuestStates().put(6072101, state);
    assertFalse(QuestCompletionRecords.completed(actor, 6072101));
    assertFalse(actor.getQuestManager().states().stream().anyMatch(q -> q.getQuestId() == 6072101));
  }

  @Test
  void incompleteHistoryAndSideTargetsCannotClaimTheMainTargetReward() {
    actor.getGameplay().getLocalLegendProgress().merge(LocalLegendCatalog.level(1, 2), 2, 100);
    assertNotEquals(Retcode.SUCC, actor.getQuestManager().claim(6072114).retcode());
    assertEquals(0, actor.grants);
    assertTrue(actor.amounts.isEmpty());
  }

  @ParameterizedTest
  @CsvSource({"1,6072140", "2,6072141", "3,6072142", "4,6072143", "5,6072144"})
  void originalExtraHardTargetTasksCompleteWithoutInventingAnItemReward(int group, int quest) {
    actor.getGameplay().getLocalLegendProgress().merge(LocalLegendCatalog.level(group, 2), 1, 100);
    actor.getQuestManager().refreshUnlocks();
    assertTrue(QuestCompletionRecords.completed(actor, quest));
    assertEquals(100, GameData.getQuestExcelMap().get(quest).getRewardID());
    assertTrue(GameData.getRewardExcelMap().get(100).getRewards().isEmpty());
    assertEquals(Retcode.SUCC, actor.getQuestManager().claim(quest).retcode());
    assertTrue(actor.amounts.isEmpty());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getQuestManager().claim(quest).retcode());
  }

  @Test
  void emptyQueryCannotCreateActivityTargetTasksOrCompleteAnOrdinaryUnlock() {
    var first = actor.getQuestManager().states();
    assertTrue(
        first.stream()
            .noneMatch(q -> GameData.getQuestExcelMap().get(q.getQuestId()).getQuestType() == 3));
    assertTrue(first.stream().allMatch(q -> q.getPhase() == QuestState.Phase.DOING));
    int saved = actor.saves;
    assertEquals(first, actor.getQuestManager().states());
    assertEquals(saved, actor.saves);
    assertEquals(0, actor.grants);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
  void malformedCurrentRuleCannotPublishOrPayAnAlreadyFinishedTargetTask(int variant) {
    actor.getGameplay().getLocalLegendProgress().merge(LocalLegendCatalog.level(1, 1), 1, 100);
    actor.getQuestManager().refreshUnlocks();
    var gson = ResourceLoader.getGson();
    var definition = gson.toJsonTree(GameData.getQuestExcelMap().get(6072113)).getAsJsonObject();
    var row = gson.toJsonTree(GameData.getFinishWayExcelMap().get(6072113)).getAsJsonObject();
    switch (variant) {
      case 0 -> row.addProperty("Progress", 2);
      case 1 -> row.addProperty("ParamInt1", 999999);
      case 2 -> row.addProperty("ParamInt2", 1);
      case 3 -> row.addProperty("ParamInt3", 1);
      case 4 -> row.addProperty("ParamType", "GreaterEqual");
      case 5 -> row.addProperty("ParamStr1", "unresolved");
      case 6 -> row.add("ParamIntList", JsonParser.parseString("[5001804]"));
      case 7 -> row.add("ParamItemList", JsonParser.parseString("[1]"));
      case 8 -> row.addProperty("FinishType", "Unknown");
      case 9 -> row.add("ParamIntList", JsonNull.INSTANCE);
      case 10 -> row.add("ParamItemList", JsonNull.INSTANCE);
      case 11 -> definition.addProperty("UnlockType", "FinishQuest");
      case 12 -> definition.add("UnlockParamList", JsonParser.parseString("[6072101]"));
      case 13 -> definition.add("UnlockParamList", JsonNull.INSTANCE);
      case 14 -> definition.addProperty("QuestType", 4);
      case 15 -> definition.addProperty("FinishWayID", 999999);
      case 16 -> GameData.getFinishWayExcelMap().values().removeIf(r -> r.getId() == 6072113);
    }
    GameData.getQuestExcelMap().add(gson.fromJson(definition, QuestExcel.class));
    if (variant != 16)
      GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    assertTrue(actor.getQuestManager().states().stream().noneMatch(q -> q.getQuestId() == 6072113));
    assertNotEquals(Retcode.SUCC, actor.getQuestManager().claim(6072113).retcode());
    assertEquals(QuestState.Phase.FINISHED, actor.getQuestStates().get(6072113).getPhase());
    assertEquals(0, actor.grants);
  }

  @Test
  void catalogReorderingQuarantinesStoredTargetsAndPreservesTheExistingQuest() throws Exception {
    actor.getGameplay().getLocalLegendProgress().merge(LocalLegendCatalog.level(1, 2), 1, 100);
    actor.getQuestManager().refreshUnlocks();
    var original = actor.getQuestStates().get(6072102);
    var rows = catalog.rows("LocalLegendStageConfig").deepCopy();
    for (var value : rows) {
      var row = value.getAsJsonObject();
      if (row.get("GroupID").getAsInt() == 1
          && row.get("DifficultyLevel").getAsString().equals("Hard")) {
        var targets = row.getAsJsonArray("BattleTargetList");
        var first = targets.get(0);
        targets.set(0, targets.get(1));
        targets.set(1, first);
      }
    }
    catalog.write("LocalLegendStageConfig", rows);
    LocalLegendCatalog.load(root);
    assertFalse(QuestCompletionRecords.completed(actor, 6072102));
    assertTrue(actor.getQuestManager().states().stream().noneMatch(q -> q.getQuestId() == 6072114));
    assertNotEquals(Retcode.SUCC, actor.getQuestManager().claim(6072114).retcode());
    assertSame(original, actor.getQuestStates().get(6072102));
    assertEquals(QuestState.Phase.FINISHED, original.getPhase());
    assertEquals(1, actor.getGameplay().getLocalLegendProgress().entries().getFirst().mask());
    assertEquals(0, actor.grants);
  }

  @Test
  void concurrentClaimsPayTheOriginalRewardOnlyOnce() throws Exception {
    actor.getGameplay().getLocalLegendProgress().merge(LocalLegendCatalog.level(1, 1), 1, 100);
    actor.getQuestManager().refreshUnlocks();
    var start = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var a =
          threads.submit(
              () -> {
                start.await();
                return actor.getQuestManager().claim(6072113).retcode();
              });
      var b =
          threads.submit(
              () -> {
                start.await();
                return actor.getQuestManager().claim(6072113).retcode();
              });
      start.countDown();
      assertEquals(
          Set.of(Retcode.SUCC, Retcode.QUEST_REWARD_ALREADY_TAKEN),
          Set.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)));
    }
    assertEquals(Map.of(1, 40, 2, 40000, 213, 4), actor.amounts);
    assertEquals(1, actor.grants);
  }

  @Test
  void partialDeliveryRetainsTheReservationAndCannotPayAgain() {
    actor.getGameplay().getLocalLegendProgress().merge(LocalLegendCatalog.level(1, 1), 1, 100);
    actor.getQuestManager().refreshUnlocks();
    actor.partialGrant = true;
    assertEquals(Retcode.PLAYER_DATA_ERROR, actor.getQuestManager().claim(6072113).retcode());
    assertEquals(QuestState.Phase.CLAIMING, actor.getQuestStates().get(6072113).getPhase());
    assertEquals(Retcode.SYSTEM_BUSY, actor.getQuestManager().claim(6072113).retcode());
    assertEquals(1, actor.grants);
    assertEquals(Map.of(1, 40, 2, 40000, 213, 4), actor.amounts);
  }

  @Test
  void reservationSaveFailureCannotTouchInventoryOrRetryPayment() {
    actor.getGameplay().getLocalLegendProgress().merge(LocalLegendCatalog.level(1, 1), 1, 100);
    actor.getQuestManager().refreshUnlocks();
    actor.getDailyManager().ensureDay();
    actor.failSave = true;
    assertThrows(IllegalStateException.class, () -> actor.getQuestManager().claim(6072113));
    assertEquals(QuestState.Phase.CLAIMING, actor.getQuestStates().get(6072113).getPhase());
    actor.failSave = false;
    assertEquals(Retcode.SYSTEM_BUSY, actor.getQuestManager().claim(6072113).retcode());
    assertEquals(0, actor.grants);
    assertTrue(actor.amounts.isEmpty());
  }

  @Test
  void actualPlayerDocumentAndNativeQuestBytesPreserveFinishedReservedAndPaidTasksTwice()
      throws Exception {
    for (int[] key : List.of(new int[] {1, 1}, new int[] {1, 2}, new int[] {2, 1}))
      actor
          .getGameplay()
          .getLocalLegendProgress()
          .merge(LocalLegendCatalog.level(key[0], key[1]), 1, 100);
    actor.getQuestManager().refreshUnlocks();
    assertTrue(actor.getQuestStates().get(6072113).reserveReward());
    actor.getQuestStates().get(6072113).closeReward();
    assertTrue(actor.getQuestStates().get(6072114).reserveReward());
    var player = new Player();
    player.resetPosition();
    var uid = Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(player, 7310146);
    player.getQuestStates().putAll(actor.getQuestStates());
    for (var entry : actor.getGameplay().getLocalLegendProgress().entries())
      player
          .getGameplay()
          .getLocalLegendProgress()
          .merge(
              LocalLegendCatalog.level(entry.group(), entry.difficulty()),
              entry.mask(),
              entry.changedAt());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "local-legend-target-quests");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, emu.lunarcore.util.Position.class);
      store.save(player);
      for (int i = 0; i < 2; i++) {
        var restored = store.find(Player.class).first();
        assertNotNull(restored);
        assertTrue(QuestCompletionRecords.completed(restored, 6072101));
        assertTrue(QuestCompletionRecords.completed(restored, 6072102));
        assertTrue(QuestCompletionRecords.completed(restored, 6072103));
        var paid = LocalLegendQuests.state(restored, 6072113);
        var reserved = LocalLegendQuests.state(restored, 6072114);
        var finished = LocalLegendQuests.state(restored, 6072117);
        assertEquals(QuestState.Phase.CLOSED, paid.getPhase());
        assertEquals(QuestState.Phase.CLAIMING, reserved.getPhase());
        assertEquals(QuestState.Phase.FINISHED, finished.getPhase());
        var core = emu.lunarcore.proto.GetQuestDataScRspOuterClass.GetQuestDataScRsp.newInstance();
        LocalLegendQuests.activityStates(restored).forEach(q -> core.addQuestList(q.toProto()));
        var protocol = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
        var nativeReply =
            protocol
                .clientSchema()
                .parse(
                    "GetQuestDataScRsp",
                    protocol.translate("GetQuestDataScRsp", core.toByteArray(), true));
        var listField = nativeReply.getDescriptorForType().findFieldByName("quest_list");
        var rows = (List<?>) nativeReply.getField(listField);
        assertEquals(3, rows.size());
        var identities = new HashSet<Integer>();
        for (var value : rows) {
          var row = (com.google.protobuf.DynamicMessage) value;
          var type = row.getDescriptorForType();
          assertEquals(1, row.getField(type.findFieldByName("progress")));
          identities.add((Integer) row.getField(type.findFieldByName("id")));
        }
        assertEquals(Set.of(6072113, 6072114, 6072117), identities);
        assertFalse(reserved.reserveReward());
        assertFalse(paid.reserveReward());
        store.save(restored);
      }
    } finally {
      mongo.shutdownNow();
    }
  }
}
