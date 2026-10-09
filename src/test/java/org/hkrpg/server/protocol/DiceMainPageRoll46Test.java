package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original achievement/cooldown; completed tutorial and open activity are explicit fixtures. */
class DiceMainPageRoll46Test {
  @TempDir Path root;
  final DiceCombat46Test fixture = new DiceCombat46Test();
  final Gson gson = ResourceLoader.getGson();
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldFinish = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  JsonObject source;
  ItemExcel oldHcoin;

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    try (var in = getClass().getResourceAsStream("/gameplay/activity-dice-const-4.6.json")) {
      Files.write(
          root.resolve("ExcelOutput/ActivityDiceConstValueCommo.json"),
          Objects.requireNonNull(in).readAllBytes());
    }
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldFinish.addAll(GameData.getFinishWayExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldHcoin = GameData.getItemExcelMap().get(1);
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    try (var in = getClass().getResourceAsStream("/fixtures/dice-mainpage-roll-quest-4.6.json")) {
      source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    for (var entry : source.getAsJsonObject("tables").entrySet()) {
      var rows = entry.getValue().getAsJsonObject().getAsJsonArray("rows");
      Files.writeString(root.resolve("ExcelOutput/" + entry.getKey() + ".json"), rows.toString());
      for (var row : rows) {
        switch (entry.getKey()) {
          case "QuestData" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "FinishWay" ->
              GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
          case "RewardData" -> {
            var reward = gson.fromJson(row, RewardExcel.class);
            reward.onLoad();
            GameData.getRewardExcelMap().add(reward);
          }
          case "ItemConfig" -> GameData.getItemExcelMap().add(gson.fromJson(row, ItemExcel.class));
        }
      }
    }
    DiceRollQuests.load(root);
  }

  @AfterEach
  void cleanup() throws Exception {
    DiceRollQuests.load(root.resolve("absent"));
    GameData.getQuestExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    oldFinish.forEach(GameData.getFinishWayExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getItemExcelMap().keySet().remove(1);
    if (oldHcoin != null) GameData.getItemExcelMap().add(oldHcoin);
    fixture.restore();
  }

  DiceCombat46Test.Session session() {
    var s = new DiceCombat46Test.Session();
    s.player.resetPosition();
    s.player.setNickname("dice-mainpage-fixture");
    var tutorial = new QuestState(4000001);
    tutorial.advance(1, 1, 1);
    s.player.getQuestStates().put(4000001, tutorial);
    s.player.getQuestManager().refreshUnlocks();
    return s;
  }

  int roll(DiceCombat46Test.Session s) throws Exception {
    assertTrue(VersionedPacketHandler.handle(s, 9262, new byte[0]));
    assertEquals("DiceCombatMainPageRollDiceScRsp", s.responseName);
    return Gameplay46.number(s.response, "retcode");
  }

  @Test
  void theNativeEmptyRequestAcceptsAnOpenClassicMainPageRoll() throws Exception {
    var s = session();
    assertEquals(0, roll(s));
  }

  @Test
  void anImmediateRepeatedNativeRequestCannotCountAnotherRoll() throws Exception {
    var s = session();
    assertEquals(0, roll(s));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), roll(s));
  }

  @Test
  void theSourceTwentyRollAchievementRecoversFromActualSavedCounterHistory() throws Exception {
    var s = session();
    var state = gson.toJsonTree(s.player.getGameplay()).getAsJsonObject();
    state.add(
        "diceRolls",
        JsonParser.parseString(
            """
{"count":20,"lastMillis":1790000030000,
 "resourceIdentity":"4.6-mainpage:9262:1500:cea8b6ffe42464dbec2162be883350db072baa5fbc8663f69a32e0c562ffaa95"}
"""));
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(s.player, gson.fromJson(state, GameplayState.class));
    s.player.getQuestManager().refreshUnlocks();
    assertTrue(QuestCompletionRecords.completed(s.player, 4055044));
  }

  @Test
  void aFakeFinishedQuestWithoutAcceptedRollHistoryCannotPay() {
    var s = session();
    s.player.getQuestStates().get(4055044).advance(20, 20, 1);
    assertEquals(Retcode.QUEST_NOT_ACCEPT, s.player.getQuestManager().claim(4055044).retcode());
    assertFalse(QuestCompletionRecords.completed(s.player, 4055044));
  }

  @Test
  void changedPinnedConstantsCannotPayHistoricalRollRewards() throws Exception {
    var s = session();
    restoreRolls(
        s.player,
        20,
        1790000030000L,
        "4.6-mainpage:9262:1500:cea8b6ffe42464dbec2162be883350db072baa5fbc8663f69a32e0c562ffaa95");
    s.player.getQuestManager().refreshUnlocks();
    Files.writeString(root.resolve("ExcelOutput/ActivityDiceConstValueCommo.json"), "[]");
    assertEquals(Retcode.QUEST_NOT_ACCEPT, s.player.getQuestManager().claim(4055044).retcode());
    assertEquals(0, s.player.getHcoin());
  }

  void restoreRolls(Player player, long count, long time, String identity) throws Exception {
    var state = gson.toJsonTree(player.getGameplay()).getAsJsonObject();
    var rolls = new JsonObject();
    rolls.addProperty("count", count);
    rolls.addProperty("lastMillis", time);
    rolls.addProperty("resourceIdentity", identity);
    state.add("diceRolls", rolls);
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(player, gson.fromJson(state, GameplayState.class));
  }

  static final String IDENTITY =
      "4.6-mainpage:9262:1500:cea8b6ffe42464dbec2162be883350db072baa5fbc8663f69a32e0c562ffaa95";

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
  void onlyTheTwentiethAcceptedReportCanPayTheSourceFiveJadeReward(int count) {
    var s = session();
    var catalog = DiceCombatCatalog.read();
    var start = Instant.ofEpochMilli(1790000030000L);
    for (int n = 0; n < count; n++)
      assertEquals(
          Retcode.SUCC, DiceMainPageRolls.accept(s.player, catalog, start.plusMillis(n * 1500L)));
    var quest = DiceRollQuests.state(s.player, 4055044);
    assertNotNull(quest);
    assertEquals(count, quest.getProgress());
    assertEquals(count, s.player.getGameplay().getDiceRolls().getCount());
    assertEquals(0, s.player.getHcoin());
    assertEquals(
        count == 20 ? Retcode.SUCC : Retcode.QUEST_NOT_FINISH,
        s.player.getQuestManager().claim(4055044).retcode());
    assertEquals(count == 20 ? 5 : 0, s.player.getHcoin());
    if (count == 20) {
      assertEquals(
          Retcode.QUEST_REWARD_ALREADY_TAKEN, s.player.getQuestManager().claim(4055044).retcode());
      assertEquals(5, s.player.getHcoin());
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 1499, 1500, 1501})
  void exactSourceCooldownBoundaryControlsCounting(long elapsed) {
    var s = session();
    var now = Instant.ofEpochMilli(1790000030000L);
    var catalog = DiceCombatCatalog.read();
    assertEquals(Retcode.SUCC, DiceMainPageRolls.accept(s.player, catalog, now));
    assertEquals(
        elapsed < 1500 ? Retcode.SYSTEM_BUSY : Retcode.SUCC,
        DiceMainPageRolls.accept(s.player, catalog, now.plusMillis(elapsed)));
    assertEquals(elapsed < 1500 ? 1 : 2, s.player.getGameplay().getDiceRolls().getCount());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0801", "08", "00", "0a00", "0b0c", "4001"})
  void malformedOrNonemptyNativeRequestCannotCount(String hex) throws Exception {
    var s = session();
    assertTrue(VersionedPacketHandler.handle(s, 9262, HexFormat.of().parseHex(hex)));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(s.response, "retcode"));
    assertEquals(0, s.player.getGameplay().getDiceRolls().getCount());
  }

  @ParameterizedTest
  @CsvSource({"-1,1", "4294967296,1", "1,0", "1,-1", "1,253402300799001", "0,1"})
  void corruptCounterCannotCreateQuestOrAcceptAnotherRoll(long count, long time) throws Exception {
    var s = session();
    restoreRolls(s.player, count, time, IDENTITY);
    assertEquals(
        Retcode.PLAYER_DATA_ERROR,
        DiceMainPageRolls.accept(s.player, DiceCombatCatalog.read(), Instant.now()));
    assertNull(DiceRollQuests.state(s.player, 4055044));
    assertEquals(0, s.player.getHcoin());
  }

  @Test
  void oldSaveWithoutAnyCounterStartsAtZero() throws Exception {
    var s = session();
    var json = gson.toJsonTree(s.player.getGameplay()).getAsJsonObject();
    json.remove("diceRolls");
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(s.player, gson.fromJson(json, GameplayState.class));
    assertTrue(s.player.getGameplay().getDiceRolls().valid());
    assertEquals(0, s.player.getGameplay().getDiceRolls().getCount());
    assertFalse(QuestCompletionRecords.completed(s.player, 4055044));
    assertEquals(0, roll(s));
    assertEquals(1, s.player.getGameplay().getDiceRolls().getCount());
  }

  @Test
  void aForeignVersionCounterCannotPayOrRoll() throws Exception {
    var s = session();
    restoreRolls(s.player, 20, 1790000030000L, "4.2-or-unverified-counter");
    s.player.getQuestStates().get(4055044).advance(20, 20, 1);
    assertEquals(Retcode.QUEST_NOT_ACCEPT, s.player.getQuestManager().claim(4055044).retcode());
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), roll(s));
  }

  @Test
  void aClockRollbackCannotAdvanceTheStoredCounter() {
    var s = session();
    var now = Instant.ofEpochMilli(1790000030000L);
    assertEquals(Retcode.SUCC, DiceMainPageRolls.accept(s.player, DiceCombatCatalog.read(), now));
    assertEquals(
        Retcode.REQ_PARA_INVALID,
        DiceMainPageRolls.accept(s.player, DiceCombatCatalog.read(), now.minusMillis(1)));
    assertEquals(1, s.player.getGameplay().getDiceRolls().getCount());
  }

  @Test
  void unsignedCounterOverflowDoesNotWrapIntoAnotherRoll() throws Exception {
    var s = session();
    restoreRolls(s.player, 0xffffffffL, 1790000030000L, IDENTITY);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), roll(s));
    assertEquals(0xffffffffL, s.player.getGameplay().getDiceRolls().getCount());
  }

  @Test
  void closedActivityRejectsReportsButRetainsPermanentEarnedAchievement() throws Exception {
    var s = session();
    restoreRolls(s.player, 20, 1790000030000L, IDENTITY);
    s.player.getQuestManager().refreshUnlocks();
    GameplayDefinitions.windows.clear();
    assertEquals(Retcode.SHOP_NOT_OPEN.getVal(), roll(s));
    assertEquals(20, s.player.getGameplay().getDiceRolls().getCount());
    assertEquals(Retcode.SUCC, s.player.getQuestManager().claim(4055044).retcode());
    assertEquals(5, s.player.getHcoin());
  }

  @Test
  void anotherCurrentRunRejectsAHomepageReport() throws Exception {
    var s = session();
    s.player.getGameplay().getRogue().setInside(true);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), roll(s));
    assertEquals(0, s.player.getGameplay().getDiceRolls().getCount());
  }

  @Test
  void concurrentReportsAtTheSameTimeCountExactlyOnce() throws Exception {
    var s = session();
    var start = Instant.ofEpochMilli(1790000030000L);
    var catalog = DiceCombatCatalog.read();
    try (var threads = Executors.newFixedThreadPool(8)) {
      var jobs = new ArrayList<Callable<Retcode>>();
      for (int n = 0; n < 8; n++)
        jobs.add(() -> DiceMainPageRolls.accept(s.player, catalog, start));
      var results = threads.invokeAll(jobs);
      int accepted = 0;
      for (var job : results) {
        var code = job.get();
        assertTrue(code == Retcode.SUCC || code == Retcode.SYSTEM_BUSY);
        if (code == Retcode.SUCC) accepted++;
      }
      assertEquals(1, accepted);
    }
    assertEquals(1, s.player.getGameplay().getDiceRolls().getCount());
    assertEquals(1, DiceRollQuests.state(s.player, 4055044).getProgress());
  }

  @Test
  void failedCounterSaveRollsBackBeforeAchievementProgress() {
    var actor =
        new Player() {
          @Override
          public void save() {
            throw new IllegalStateException("isolated roll save interruption");
          }

          @Override
          public void sendPacket(BasePacket p) {}
        };
    var before = actor.getGameplay().getDiceRolls();
    assertThrows(
        IllegalStateException.class,
        () -> DiceMainPageRolls.accept(actor, DiceCombatCatalog.read(), Instant.now()));
    assertSame(before, actor.getGameplay().getDiceRolls());
    assertEquals(0, before.getCount());
    assertTrue(actor.getQuestStates().isEmpty());
  }

  @Test
  void unfinishedTutorialCannotBorrowACompletedRollAchievement() throws Exception {
    var s = session();
    s.player.getQuestStates().remove(4000001);
    restoreRolls(s.player, 20, 1790000030000L, IDENTITY);
    s.player.getQuestStates().get(4055044).advance(20, 20, 1);
    assertEquals(Retcode.QUEST_NOT_ACCEPT, s.player.getQuestManager().claim(4055044).retcode());
    assertFalse(QuestCompletionRecords.completed(s.player, 4055044));
  }

  @Test
  void aQuestAheadOfItsSavedRollHistoryCannotBeClaimed() throws Exception {
    var s = session();
    restoreRolls(s.player, 19, 1790000030000L, IDENTITY);
    s.player.getQuestStates().get(4055044).advance(20, 20, 1);
    assertEquals(Retcode.QUEST_NOT_ACCEPT, s.player.getQuestManager().claim(4055044).retcode());
  }

  @Test
  void anUnauthenticatedNativeRequestHasNoReplyOrSideEffect() throws Exception {
    var s = session();
    s.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(DiceMainPageRoll46.handle(s, 9262, new byte[0]));
    assertNull(s.response);
    assertEquals(0, s.player.getGameplay().getDiceRolls().getCount());
  }

  @ParameterizedTest
  @ValueSource(strings = {"QuestData", "FinishWay", "RewardData"})
  void changedSourceQuestRulesFailClosed(String table) throws Exception {
    var rows =
        source.getAsJsonObject("tables").getAsJsonObject(table).getAsJsonArray("rows").deepCopy();
    var row = rows.get(rows.size() - 1).getAsJsonObject();
    switch (table) {
      case "QuestData" -> row.addProperty("RewardID", 100);
      case "FinishWay" -> row.addProperty("Progress", 1);
      case "RewardData" -> row.addProperty("Hcoin", 500);
    }
    Files.writeString(root.resolve("ExcelOutput/" + table + ".json"), rows.toString());
    assertThrows(IllegalStateException.class, () -> DiceRollQuests.load(root));
    var s = session();
    restoreRolls(s.player, 20, 1790000030000L, IDENTITY);
    s.player.getQuestStates().get(4055044).advance(20, 20, 1);
    assertEquals(Retcode.QUEST_NOT_ACCEPT, s.player.getQuestManager().claim(4055044).retcode());
  }

  @Test
  void actualPlayerMorphiaStorageKeepsCounterAndClosedFiveJadeClaim() throws Exception {
    var s = session();
    for (int i = 0; i < 20; i++)
      assertEquals(
          Retcode.SUCC,
          DiceMainPageRolls.accept(
              s.player,
              DiceCombatCatalog.read(),
              Instant.ofEpochMilli(1790000030000L + i * 1500L)));
    assertEquals(Retcode.SUCC, s.player.getQuestManager().claim(4055044).retcode());
    var uid = Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(s.player, 7310346);
    // Save the real mapped Player, not the anonymous test double's class.
    var stored = gson.fromJson(gson.toJson(s.player, Player.class), Player.class);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "dice-mainpage-rolls");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, emu.lunarcore.util.Position.class);
      store.save(stored);
      for (int i = 0; i < 2; i++) {
        var restored = store.find(Player.class).first();
        assertNotNull(restored);
        assertTrue(restored.getGameplay().getDiceRolls().valid());
        assertEquals(20, restored.getGameplay().getDiceRolls().getCount());
        assertEquals(1790000058500L, restored.getGameplay().getDiceRolls().getLastMillis());
        assertEquals(5, restored.getHcoin());
        assertEquals(QuestState.Phase.CLOSED, DiceRollQuests.state(restored, 4055044).getPhase());
        assertTrue(QuestCompletionRecords.completed(restored, 4055044));
        store.save(restored);
      }
    } finally {
      mongo.shutdownNow();
    }
  }
}
