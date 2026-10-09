package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Source-declared achievement reports; does not simulate the underlying official conditions. */
class LocalLegendAchievementQuestsTest {
  @TempDir Path root;
  final LocalLegendEntry46Test entry = new LocalLegendEntry46Test();
  JsonObject fixture;

  @BeforeEach
  void load() throws Exception {
    entry.root = root;
    entry.load();
    try (var in =
        getClass().getResourceAsStream("/fixtures/local-legend-achievement-quests-4.6.json")) {
      fixture =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    assertEquals(
        "724b139d8c9c32d12552eb95745a4fee72bfe48b", fixture.get("sourceCommit").getAsString());
    for (var table : fixture.getAsJsonObject("tables").entrySet()) {
      var rows = table.getValue().getAsJsonObject().getAsJsonArray("rows");
      Files.writeString(root.resolve("ExcelOutput/" + table.getKey() + ".json"), rows.toString());
      for (var row : rows) {
        switch (table.getKey()) {
          case "QuestData" ->
              GameData.getQuestExcelMap()
                  .add(ResourceLoader.getGson().fromJson(row, QuestExcel.class));
          case "FinishWay" ->
              GameData.getFinishWayExcelMap()
                  .add(ResourceLoader.getGson().fromJson(row, FinishWayExcel.class));
          case "RewardData" -> {
            var reward = ResourceLoader.getGson().fromJson(row, RewardExcel.class);
            reward.onLoad();
            GameData.getRewardExcelMap().add(reward);
          }
          case "ItemConfig" ->
              GameData.getItemExcelMap()
                  .add(ResourceLoader.getGson().fromJson(row, ItemExcel.class));
        }
      }
    }
    LocalLegendCatalog.load(root);
    entry.session.actor.getQuestManager().refreshUnlocks();
  }

  @AfterEach
  void restore() throws Exception {
    entry.restore();
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 6})
  void sourceAchievementReportAdvancesOnlyItsDeclaredRewardAndClaimIsPaidOnce(int category)
      throws Exception {
    var battle = enter(1, 2);
    assertSame(
        battle,
        entry.finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(category, 30213, 1, 999)));
    assertFalse(entry.session.actor.isInBattle());
    assertTrue(QuestCompletionRecords.completed(entry.session.actor, 6072147));
    assertFalse(QuestCompletionRecords.completed(entry.session.actor, 6072148));
    assertFalse(QuestCompletionRecords.completed(entry.session.actor, 6072150));
    assertEquals(1, entry.session.actor.getGameplay().getLocalLegendProgress().entries().size());
    assertEquals(Retcode.SUCC, entry.session.actor.getQuestManager().claim(6072147).retcode());
    assertEquals(Map.of(1, 30, 2, 10000, 213, 2), entry.session.actor.amounts);
    assertEquals(1, entry.session.actor.grants);
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN,
        entry.session.actor.getQuestManager().claim(6072147).retcode());
    assertEquals(1, entry.session.actor.grants);
    assertEquals(0, entry.formal.saves);
  }

  @ParameterizedTest
  @CsvSource({
    "1,2,6072147,30213,30,213,2", "1,3,6072148,30214,20,232,5",
    "2,2,6072150,30216,30,213,2", "2,3,6072151,30217,20,232,5",
    "3,2,6072153,30219,30,213,2", "3,3,6072154,30220,20,232,5",
    "4,2,6072156,30222,30,213,2", "4,3,6072157,30223,20,232,5",
    "5,2,6072159,30225,30,213,2", "5,3,6072160,30226,20,232,5"
  })
  void everyOriginalTaskPaysOnlyItsOwnExactReward(
      int group, int difficulty, int quest, int achievement, int jade, int item, int count)
      throws Exception {
    var battle = enter(group, difficulty);
    int initialTargets =
        entry.session.actor.getGameplay().getLocalLegendProgress().entries().size();
    assertSame(
        battle,
        entry.finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(6, achievement, 1, 0)));
    var history = entry.session.actor.getGameplay().getLocalLegendProgress();
    assertTrue(history.valid());
    assertEquals(1, history.achievementEntries().size());
    assertEquals(quest, history.achievementEntries().getFirst().quest());
    assertEquals(achievement, history.achievementEntries().getFirst().achievement());
    for (var rule : LocalLegendAchievementRules.rules())
      assertEquals(
          rule.quest() == quest,
          QuestCompletionRecords.completed(entry.session.actor, rule.quest()));
    assertEquals(Retcode.SUCC, entry.session.actor.getQuestManager().claim(quest).retcode());
    assertEquals(Map.of(1, jade, 2, 10000, item, count), entry.session.actor.amounts);
    assertEquals(initialTargets + (difficulty == 3 ? 1 : 0), history.entries().size());
    assertEquals(difficulty == 3 ? 1 : 0, history.mask(battle.level()));
  }

  @ParameterizedTest
  @ValueSource(ints = {30213, 30214, 30216, 30217, 30219, 30220, 30222, 30223, 30225, 30226})
  void sourceAchievementsFromAnotherLevelRejectSettlementAndPreserveHistory(int achievement)
      throws Exception {
    var battle = entry.enter();
    var history = entry.session.actor.getGameplay().getLocalLegendProgress();
    var states = new Gson().toJson(entry.session.actor.getQuestStates());
    assertNull(
        entry.finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(6, achievement, 1, 1)));
    assertSame(battle, entry.session.actor.getBattle());
    assertSame(history, entry.session.actor.getGameplay().getLocalLegendProgress());
    assertEquals(states, new Gson().toJson(entry.session.actor.getQuestStates()));
    assertTrue(history.achievementEntries().isEmpty());
    assertEquals(0, entry.session.actor.grants);
  }

  @ParameterizedTest
  @ValueSource(strings = {"QuestData", "FinishWay", "RewardData", "BattleAchievement"})
  void unsupportedSourceConditionsAreRejectedRatherThanIgnored(String table) throws Exception {
    var rows =
        fixture.getAsJsonObject("tables").getAsJsonObject(table).getAsJsonArray("rows").deepCopy();
    rows.get(0).getAsJsonObject().addProperty("UnsupportedCondition", 1);
    Files.writeString(root.resolve("ExcelOutput/" + table + ".json"), rows.toString());
    var failure = assertThrows(IllegalStateException.class, () -> LocalLegendCatalog.load(root));
    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    assertTrue(LocalLegendAchievementRules.rules().isEmpty());
    assertNull(LocalLegendAchievementRules.rule(6072147));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3, 4})
  void otherNativeCategoriesDoNotAwardAchievements(int category) throws Exception {
    var battle = entry.enter();
    assertSame(
        battle,
        entry.finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(category, 30213, 1, 1)));
    assertTrue(
        entry.session.actor.getGameplay().getLocalLegendProgress().achievementEntries().isEmpty());
    assertEquals(
        Retcode.QUEST_NOT_FINISH, entry.session.actor.getQuestManager().claim(6072147).retcode());
    assertEquals(0, entry.session.actor.grants);
  }

  @ParameterizedTest
  @ValueSource(strings = {"BATTLE_END_LOSE", "BATTLE_END_QUIT"})
  void nonWinningReportsDoNotAwardAchievements(String result) throws Exception {
    var battle = enter(1, 2);
    assertSame(
        battle,
        entry.finish(
            battle,
            BattleEndStatus.valueOf(result),
            LocalLegendCatalogTest.report(6, 30213, 1, 1)));
    assertTrue(
        entry.session.actor.getGameplay().getLocalLegendProgress().achievementEntries().isEmpty());
    assertFalse(QuestCompletionRecords.completed(entry.session.actor, 6072147));
    assertEquals(0, entry.session.actor.grants);
  }

  @Test
  void zeroAndUnrelatedStatisticsCannotUndoTheSourceWinnerOrInventOtherRewards() throws Exception {
    var battle = enter(1, 2);
    assertSame(
        battle,
        entry.finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(6, 30213, 0, 999)));
    battle = enter(1, 2);
    assertSame(
        battle,
        entry.finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(6, 1234567, 1, 1)));
    assertEquals(
        1, entry.session.actor.getGameplay().getLocalLegendProgress().achievementEntries().size());
    assertTrue(QuestCompletionRecords.completed(entry.session.actor, 6072147));
    assertFalse(QuestCompletionRecords.completed(entry.session.actor, 6072150));
  }

  @Test
  void fabricatedFinishedQuestCannotSubstituteForTheAcceptedReport() {
    var state = entry.session.actor.getQuestStates().get(6072147);
    assertNotNull(state);
    assertTrue(state.advance(1, 1, 100));
    assertNull(LocalLegendQuests.state(entry.session.actor, 6072147));
    assertFalse(QuestCompletionRecords.completed(entry.session.actor, 6072147));
    assertEquals(
        Retcode.QUEST_NOT_ACCEPT, entry.session.actor.getQuestManager().claim(6072147).retcode());
    assertEquals(0, entry.session.actor.grants);
  }

  @Test
  void concurrentClaimsPayOneRewardAndRepeatedWinningReportPreservesReceipt() throws Exception {
    win();
    var history = entry.session.actor.getGameplay().getLocalLegendProgress();
    var receipt = history.achievementEntries().getFirst();
    win();
    assertSame(history, entry.session.actor.getGameplay().getLocalLegendProgress());
    assertSame(receipt, history.achievementEntries().getFirst());
    var pool = Executors.newFixedThreadPool(8);
    try {
      var start = new CountDownLatch(1);
      var tasks = new ArrayList<Future<Retcode>>();
      for (int i = 0; i < 8; i++)
        tasks.add(
            pool.submit(
                () -> {
                  start.await();
                  return entry.session.actor.getQuestManager().claim(6072147).retcode();
                }));
      start.countDown();
      int paid = 0;
      for (var task : tasks) {
        var code = task.get(10, TimeUnit.SECONDS);
        if (code == Retcode.SUCC) paid++;
        else assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, code);
      }
      assertEquals(1, paid);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, entry.session.actor.grants);
    assertEquals(Map.of(1, 30, 2, 10000, 213, 2), entry.session.actor.amounts);
  }

  @Test
  void failedHistorySaveRestoresThePreviousSnapshotWithoutRewardOrReplay() throws Exception {
    var history = entry.session.actor.getGameplay().getLocalLegendProgress();
    var battle = enter(1, 2);
    entry.session.actor.failSave = true;
    assertThrows(
        IllegalStateException.class,
        () ->
            entry.finish(
                battle,
                BattleEndStatus.BATTLE_END_WIN,
                LocalLegendCatalogTest.report(6, 30213, 1, 1)));
    assertSame(history, entry.session.actor.getGameplay().getLocalLegendProgress());
    assertTrue(history.achievementEntries().isEmpty());
    assertSame(battle, entry.session.actor.getBattle());
    entry.session.actor.failSave = false;
    assertNull(
        entry.finish(
            battle, BattleEndStatus.BATTLE_END_WIN, LocalLegendCatalogTest.report(6, 30213, 1, 1)));
    assertFalse(QuestCompletionRecords.completed(entry.session.actor, 6072147));
    assertEquals(0, entry.session.actor.grants);
  }

  @Test
  void incompleteInventoryGrantRetainsReservationAndCannotBeAutomaticallyRepaid() throws Exception {
    win();
    entry.session.actor.partialGrant = true;
    assertEquals(
        Retcode.PLAYER_DATA_ERROR, entry.session.actor.getQuestManager().claim(6072147).retcode());
    assertEquals(
        QuestState.Phase.CLAIMING, entry.session.actor.getQuestStates().get(6072147).getPhase());
    entry.session.actor.partialGrant = false;
    assertEquals(
        Retcode.SYSTEM_BUSY, entry.session.actor.getQuestManager().claim(6072147).retcode());
    assertEquals(1, entry.session.actor.grants);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void changingRuntimeDefinitionsCannotReassignAnAcceptedReceipt(int variant) throws Exception {
    win();
    var rule = LocalLegendAchievementRules.rule(6072147);
    assertNotNull(rule);
    switch (variant) {
      case 0 -> {
        var data =
            ResourceLoader.getGson()
                .toJsonTree(GameData.getQuestExcelMap().get(6072147))
                .getAsJsonObject();
        data.addProperty("RewardID", 139412);
        GameData.getQuestExcelMap().add(ResourceLoader.getGson().fromJson(data, QuestExcel.class));
      }
      case 1 -> {
        var data =
            ResourceLoader.getGson()
                .toJsonTree(GameData.getFinishWayExcelMap().get(6072147))
                .getAsJsonObject();
        data.addProperty("ParamInt1", 30214);
        GameData.getFinishWayExcelMap()
            .add(ResourceLoader.getGson().fromJson(data, FinishWayExcel.class));
      }
      case 2 -> {
        var data =
            ResourceLoader.getGson()
                .toJsonTree(GameData.getRewardExcelMap().get(rule.reward()))
                .getAsJsonObject();
        data.addProperty("Hcoin", 1000);
        var reward = ResourceLoader.getGson().fromJson(data, RewardExcel.class);
        reward.onLoad();
        GameData.getRewardExcelMap().add(reward);
      }
      case 3 -> GameData.getRewardExcelMap().get(rule.reward()).getRewards().put(1, 1000);
    }
    assertNull(LocalLegendAchievementRules.rule(6072147));
    assertFalse(entry.session.actor.getGameplay().getLocalLegendProgress().valid());
    assertFalse(QuestCompletionRecords.completed(entry.session.actor, 6072147));
    assertEquals(
        Retcode.QUEST_NOT_ACCEPT, entry.session.actor.getQuestManager().claim(6072147).retcode());
    assertEquals(
        1, entry.session.actor.getGameplay().getLocalLegendProgress().achievementEntries().size());
    assertEquals(0, entry.session.actor.grants);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
  void malformedStoredReceiptsRemainPresentAndCannotAuthorizeClaims(int variant) throws Exception {
    win();
    var gson = new Gson();
    var data =
        gson.toJsonTree(entry.session.actor.getGameplay().getLocalLegendProgress())
            .getAsJsonObject();
    var receipts = data.getAsJsonObject("achievements");
    var receipt = receipts.getAsJsonObject("6072147");
    switch (variant) {
      case 0 -> receipt.addProperty("quest", 6072148);
      case 1 -> receipt.addProperty("achievement", 30214);
      case 2 -> receipt.addProperty("group", 99);
      case 3 -> receipt.addProperty("difficulty", 99);
      case 4 -> receipt.addProperty("changedAt", 0);
      case 5 -> receipt.addProperty("resourceIdentity", "other");
      case 6 -> receipt.addProperty("levelIdentity", "other");
      case 7 -> {
        receipts.remove("6072147");
        receipts.add("other", receipt);
      }
      case 8 -> receipts.add("6072147", JsonNull.INSTANCE);
      case 9, 10 -> {
        int group = variant == 9 ? 2 : 1;
        int difficulty = variant == 9 ? 1 : 3;
        receipt.addProperty("group", group);
        receipt.addProperty("difficulty", difficulty);
        receipt.addProperty(
            "levelIdentity", LocalLegendCatalog.level(group, difficulty).identity());
      }
    }
    var restored = gson.fromJson(data, LocalLegendProgress.class);
    assertFalse(restored.valid());
    entry.session.actor.getGameplay().localLegendProgress(restored);
    assertFalse(QuestCompletionRecords.completed(entry.session.actor, 6072147));
    assertEquals(
        Retcode.QUEST_NOT_ACCEPT, entry.session.actor.getQuestManager().claim(6072147).retcode());
    assertEquals(0, entry.session.actor.grants);
    if (variant != 8) assertEquals(1, restored.achievementEntries().size());
  }

  @Test
  void existingTargetOnlyJsonRemainsReadableWithoutCreatingAchievements() {
    var history = entry.session.actor.getGameplay().getLocalLegendProgress();
    history.merge(LocalLegendCatalog.level(1, 1), 1, 100);
    var gson = new Gson();
    var data = gson.toJsonTree(history).getAsJsonObject();
    data.remove("achievements");
    var restored = gson.fromJson(data, LocalLegendProgress.class);
    assertTrue(restored.valid());
    assertEquals(1, restored.mask(LocalLegendCatalog.level(1, 1)));
    assertTrue(restored.achievementEntries().isEmpty());
    assertFalse(restored.hasAchievement(6072147));
  }

  @Test
  void actualPlayerMorphiaReadsPreserveTheReceiptAndClosedClaimTwice() throws Exception {
    win();
    assertEquals(Retcode.SUCC, entry.session.actor.getQuestManager().claim(6072147).retcode());
    var player = new Player();
    var uid = Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(player, 7310346);
    player
        .getGameplay()
        .localLegendProgress(entry.session.actor.getGameplay().getLocalLegendProgress().copy());
    player.getQuestStates().put(6072147, entry.session.actor.getQuestStates().get(6072147));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "local-legend-achievements");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      store.save(player);
      for (int i = 0; i < 2; i++) {
        var restored = store.find(Player.class).first();
        assertNotNull(restored);
        var history = restored.getGameplay().getLocalLegendProgress();
        assertTrue(history.valid());
        assertTrue(history.hasAchievement(6072147));
        assertFalse(history.hasAchievement(6072148));
        assertEquals(30213, history.achievementEntries().getFirst().achievement());
        assertEquals(1, history.entries().size());
        assertTrue(QuestCompletionRecords.completed(restored, 6072147));
        assertEquals(
            QuestState.Phase.CLOSED, LocalLegendQuests.state(restored, 6072147).getPhase());
        store.save(restored);
      }
    } finally {
      mongo.shutdownNow();
    }
  }

  void win() throws Exception {
    var battle = enter(1, 2);
    assertSame(
        battle,
        entry.finish(
            battle, BattleEndStatus.BATTLE_END_WIN, LocalLegendCatalogTest.report(6, 30213, 1, 1)));
  }

  LocalLegendBattle enter(int group, int difficulty) throws Exception {
    var history = entry.session.actor.getGameplay().getLocalLegendProgress();
    for (int level = 1; level < difficulty; level++)
      history.merge(LocalLegendCatalog.level(group, level), 1, 100 + level);
    entry.session.actor.getQuestManager().refreshUnlocks();
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(
            50070, LocalLegendCatalog.group(group).module(), 1, Long.MAX_VALUE));
    var type = entry.session.getProtocol().clientSchema().message("EnterLocalLegendLevelCsReq");
    var req =
        DynamicMessage.parseFrom(type, entry.request(difficulty, 8001)).toBuilder()
            .setField(type.findFieldByName("group_id"), group)
            .build();
    assertTrue(VersionedPacketHandler.handle(entry.session, 6591, req.toByteArray()));
    assertEquals(0, entry.retcode());
    return assertInstanceOf(LocalLegendBattle.class, entry.session.actor.getBattle());
  }

  @Test
  void fullOriginalWinnerLeavesBindEachRuleToTheActualTargetAbility() throws Exception {
    JsonObject source;
    try (var input =
        getClass().getResourceAsStream("/fixtures/local-legend-achievement-abilities-4.6.json")) {
      source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    assertEquals(
        "724b139d8c9c32d12552eb95745a4fee72bfe48b", source.get("sourceCommit").getAsString());
    assertEquals(
        "1d03573599903693f0d4099e73fb4dcaabd5460021dd2e0327100203868288d3",
        source.get("sourceSHA256").getAsString());
    var bindings = new TreeMap<Integer, String>();
    for (var element : source.getAsJsonArray("abilities")) {
      var ability = element.getAsJsonObject();
      for (var modifier : ability.getAsJsonObject("Modifiers").entrySet())
        for (var elementCallback :
            modifier.getValue().getAsJsonObject().getAsJsonArray("_CallbackList")) {
          var callback = elementCallback.getAsJsonObject();
          if (!callback.get("Event").getAsString().equals("OnLeaveBattle")) continue;
          for (var elementTask : callback.getAsJsonArray("CallbackConfig")) {
            var task = elementTask.getAsJsonObject();
            assertEquals("RPG.GameCore.PredicateTaskList", task.get("$type").getAsString());
            assertEquals(
                JsonParser.parseString("{\"$type\":\"RPG.GameCore.ByLocalWin\"}"),
                task.get("Predicate"));
            for (var elementLeaf : task.getAsJsonArray("SuccessTaskList")) {
              var leaf = elementLeaf.getAsJsonObject();
              if (!leaf.get("$type").getAsString().equals("RPG.GameCore.SetBattleAchievement"))
                continue;
              assertEquals(Set.of("$type", "AchievementID"), leaf.keySet());
              int id = leaf.get("AchievementID").getAsInt();
              assertNull(bindings.putIfAbsent(id, ability.get("Name").getAsString()));
            }
          }
        }
    }
    assertEquals(10, bindings.size());
    for (var rule : LocalLegendAchievementRules.rules()) {
      assertEquals(bindings.get(rule.achievement()), rule.ability());
      for (var level : LocalLegendCatalog.levels())
        assertEquals(
            level.goals().stream()
                .anyMatch(g -> bindings.get(rule.achievement()).equals(g.ability())),
            LocalLegendAchievementRules.allowed(rule, level));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "1,2,6072147",
    "1,3,6072148",
    "2,2,6072150",
    "2,3,6072151",
    "3,2,6072153",
    "3,3,6072154",
    "4,2,6072156",
    "4,3,6072157",
    "5,2,6072159",
    "5,3,6072160"
  })
  void actualSourceWinnerLeafCompletesItsQuestWithoutAnExtraAchievementReport(
      int group, int difficulty, int quest) throws Exception {
    var battle = enter(group, difficulty);
    assertSame(
        battle,
        entry.finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance()));
    assertTrue(QuestCompletionRecords.completed(entry.session.actor, quest));
    assertEquals(
        1, entry.session.actor.getGameplay().getLocalLegendProgress().achievementEntries().size());
    assertEquals(Retcode.SUCC, entry.session.actor.getQuestManager().claim(quest).retcode());
    for (var rule : LocalLegendAchievementRules.rules())
      assertEquals(
          rule.quest() == quest,
          QuestCompletionRecords.completed(entry.session.actor, rule.quest()));
  }

  @Test
  void sourceTargetAliasesCannotMoveAnAchievementToAnotherGroup() throws Exception {
    var path = root.resolve("ExcelOutput/BattleTargetConfig.json");
    var rows = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    for (var value : rows) {
      var row = value.getAsJsonObject();
      int id = row.get("ID").getAsInt();
      if (id == 5001801) row.addProperty("AbilityName", "BattleTarget_LocalLegend_S2_1");
      if (id == 5001806) row.addProperty("AbilityName", "BattleTarget_LocalLegend_S1_1");
    }
    Files.writeString(path, rows.toString());
    assertThrows(IllegalStateException.class, () -> LocalLegendCatalog.load(root));
    assertTrue(LocalLegendAchievementRules.rules().isEmpty());
  }
}
