package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original paid enhancement operations; scene, roster and tutorial are explicit domain fixtures.
 */
class RogueEnhancementQuestTest {
  static final int BUFF = 612050;
  final UniverseEnhance46Test fixture = new UniverseEnhance46Test();
  final Gson gson = ResourceLoader.getGson();
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldRules = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final List<MazeBuffExcel> oldMaze = new ArrayList<>();
  final List<GameplayDefinitions.Blessing> oldBuffs = new ArrayList<>();
  final Map<Integer, JsonObject> rules = new HashMap<>();
  ItemExcel oldItem;

  @BeforeEach
  void setup() throws Exception {
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldRules.addAll(GameData.getFinishWayExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldMaze.addAll(GameData.getMazeBuffExcelMap().values());
    oldBuffs.addAll(GameplayDefinitions.blessings);
    oldItem = GameData.getItemExcelMap().get(1);
    fixture.setup();
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getMazeBuffExcelMap().values().clear();
    GameplayDefinitions.blessings.clear();
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-enhancement-quests-4.6.json")) {
      var source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(16, source.size());
      for (var entry : source) {
        var spec = entry.getAsJsonObject();
        var row = spec.getAsJsonObject("row");
        switch (spec.get("kind").getAsString()) {
          case "quest" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "rule" -> {
            var r = gson.fromJson(row, FinishWayExcel.class);
            GameData.getFinishWayExcelMap().add(r);
            rules.put(r.getId(), row);
          }
          case "reward" -> {
            var r = gson.fromJson(row, RewardExcel.class);
            r.onLoad();
            GameData.getRewardExcelMap().add(r);
          }
          case "main" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "maze" ->
              GameData.getMazeBuffExcelMap().add(gson.fromJson(row, MazeBuffExcel.class));
          case "buff" ->
              GameplayDefinitions.blessings.add(
                  gson.fromJson(row, GameplayDefinitions.Blessing.class));
          case "achievement" -> {}
          case "item" -> {
            var r = row.deepCopy();
            r.addProperty("ItemName", r.getAsJsonObject("ItemName").get("Hash").getAsLong());
            GameData.getItemExcelMap().add(gson.fromJson(r, ItemExcel.class));
          }
          default -> fail(spec.toString());
        }
      }
    }
    assertTrue(player().setNickname("EnhanceGoals"));
    var tutorial = new QuestState(1000201);
    assertTrue(tutorial.advance(1, 1, 1));
    player().getGameplay().getMainMissions().put(1000201, tutorial);
    assertTrue(player().getQuestManager().onMissionProgress());
    assertEquals(QuestState.Phase.FINISHED, player().getQuestStates().get(4000001).getPhase());
  }

  @AfterEach
  void restore() {
    fixture.cleanup();
    GameData.getQuestExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    oldRules.forEach(GameData.getFinishWayExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getMazeBuffExcelMap().values().clear();
    oldMaze.forEach(GameData.getMazeBuffExcelMap()::add);
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.blessings.addAll(oldBuffs);
    GameData.getItemExcelMap().keySet().remove(1);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
  }

  UniverseEnhance46Test.Explorer player() {
    return fixture.s.explorer;
  }

  void begin() {
    var previous = player().getGameplay().getRogue();
    if (previous.isInside()) {
      assertEquals(Retcode.SUCC, player().getUniverseManager().leave(false));
      assertEquals(Retcode.SUCC, player().getUniverseManager().quit(previous.getAreaId()));
    }
    fixture.begin();
    var run = player().getGameplay().getRogue();
    run.getBlessings().clear();
    run.offer(List.of(BUFF));
    assertEquals(
        Retcode.SUCC, new UniverseManager(player()).selectBlessing(run.getSelectionId(), BUFF));
    assertEquals(1, run.getBlessings().get(BUFF));
  }

  void enhance(int times) {
    for (int i = 0; i < times; i++) {
      begin();
      assertEquals(Map.of(BUFF, 100), RogueEnhancement.quotes(player()));
      assertEquals(Retcode.SUCC, RogueEnhancement.enhance(player(), BUFF));
      assertEquals(2, player().getGameplay().getRogue().getBlessings().get(BUFF));
      assertEquals(200, player().getGameplay().getRogue().getCoins());
    }
  }

  QuestState state(int id) {
    var rows =
        player().getQuestManager().states().stream().filter(s -> s.getQuestId() == id).toList();
    assertEquals(1, rows.size());
    return rows.getFirst();
  }

  void held(int id) {
    assertTrue(player().getQuestManager().states().stream().noneMatch(s -> s.getQuestId() == id));
    assertNotEquals(Retcode.SUCC, player().getQuestManager().claim(id).retcode());
    assertEquals(0, player().getHcoin());
  }

  @Test
  void actualEnhancementImmediatelyAdvancesTheOriginalTenGoal() {
    enhance(1);
    assertEquals(1, player().getQuestStates().get(4092314).getProgress());
  }

  @Test
  void tenActualPaidEnhancementsFinishTheOriginalGoalAndPayFiveJades() {
    enhance(10);
    assertEquals(10, state(4092314).getProgress());
    assertEquals(Retcode.SUCC, player().getQuestManager().claim(4092314).retcode());
    assertEquals(5, player().getHcoin());
  }

  @Test
  void nineActualEnhancementsCannotCompleteOrPayTheTenGoal() {
    enhance(9);
    assertEquals(9, state(4092314).getProgress());
    assertEquals(QuestState.Phase.DOING, state(4092314).getPhase());
    assertEquals(Retcode.QUEST_NOT_FINISH, player().getQuestManager().claim(4092314).retcode());
    assertEquals(0, player().getHcoin());
  }

  @Test
  void twoHundredActualPaidEnhancementsPayTheOriginalTenJades() {
    enhance(200);
    assertEquals(200, state(4092315).getProgress());
    assertEquals(Retcode.SUCC, player().getQuestManager().claim(4092315).retcode());
    assertEquals(10, player().getHcoin());
  }

  @Test
  void oneHundredNinetyNineEnhancementsCannotCompleteTheLargerGoal() {
    enhance(199);
    assertEquals(199, state(4092315).getProgress());
    assertEquals(QuestState.Phase.DOING, state(4092315).getPhase());
    assertEquals(Retcode.QUEST_NOT_FINISH, player().getQuestManager().claim(4092315).retcode());
  }

  @Test
  void bothGoalsPayExactlyFifteenJadesAndRepeatPaysNothing() {
    enhance(200);
    for (int id : List.of(4092314, 4092315))
      assertEquals(Retcode.SUCC, player().getQuestManager().claim(id).retcode());
    assertEquals(15, player().getHcoin());
    for (int id : List.of(4092314, 4092315))
      assertEquals(
          Retcode.QUEST_REWARD_ALREADY_TAKEN, player().getQuestManager().claim(id).retcode());
    assertEquals(15, player().getHcoin());
  }

  @Test
  void repeatedEnhancementOfTheSamePaidOfferDoesNotCountOrChargeTwice() {
    enhance(1);
    assertNotEquals(Retcode.SUCC, RogueEnhancement.enhance(player(), BUFF));
    assertEquals(200, player().getGameplay().getRogue().getCoins());
    assertEquals(1, state(4092314).getProgress());
  }

  @Test
  void collectionAndQuoteQueriesDoNotCountAsAnEnhancement() {
    begin();
    for (int i = 0; i < 6; i++) assertEquals(Map.of(BUFF, 100), RogueEnhancement.quotes(player()));
    assertEquals(0, state(4092314).getProgress());
    assertEquals(300, player().getGameplay().getRogue().getCoins());
  }

  @Test
  void endedExplorationAndRepeatedQueriesRetainActualEnhancementCount() {
    enhance(10);
    var run = player().getGameplay().getRogue();
    assertEquals(Retcode.SUCC, player().getUniverseManager().leave(false));
    assertEquals(Retcode.SUCC, player().getUniverseManager().quit(run.getAreaId()));
    for (int i = 0; i < 6; i++) assertEquals(10, state(4092314).getProgress());
    assertEquals(0, player().getHcoin());
  }

  @Test
  void fabricatedFinishedStateWithoutAnyEnhancementCannotPay() {
    var fake = new QuestState(4092314);
    fake.advance(10, 10, 1);
    player().getQuestStates().put(4092314, fake);
    held(4092314);
  }

  @Test
  void missingPrerequisitePreventsAchievementAcceptance() {
    player().getQuestStates().clear();
    player().getGameplay().getMainMissions().clear();
    enhance(10);
    assertFalse(player().getQuestStates().containsKey(4092314));
    assertEquals(Retcode.QUEST_NOT_ACCEPT, player().getQuestManager().claim(4092314).retcode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"extraInt", "extraList", "extraText", "extraItem", "tooLarge", "wrongIdentity"})
  void malformedOriginalEnhancementRuleIsHeld(String shape) throws Exception {
    var row = rules.get(4092314).deepCopy();
    switch (shape) {
      case "extraInt" -> row.addProperty("ParamInt1", 1);
      case "extraList" -> row.add("ParamIntList", JsonParser.parseString("[1]"));
      case "extraText" -> row.addProperty("ParamStr1", "UnknownRule()");
      case "extraItem" ->
          row.add("ParamItemList", JsonParser.parseString("[{\"ItemID\":1,\"ItemNum\":1}]"));
      case "tooLarge" -> row.addProperty("Progress", Integer.MAX_VALUE);
      case "wrongIdentity" -> {}
    }
    var rule = gson.fromJson(row, FinishWayExcel.class);
    GameData.getFinishWayExcelMap().add(rule);
    if (shape.equals("wrongIdentity")) {
      var f = FinishWayExcel.class.getDeclaredField("ID");
      f.setAccessible(true);
      f.setInt(rule, 99999999);
    }
    held(4092314);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void damagedCounterRejectsEnhancementWithoutDebitAndHoldsGoals(boolean absent) throws Exception {
    begin();
    assertEquals(Map.of(BUFF, 100), RogueEnhancement.quotes(player()));
    if (absent) {
      var f = GameplayState.class.getDeclaredField("rogueEnhancements");
      f.setAccessible(true);
      f.set(player().getGameplay(), null);
    } else {
      var f = RogueEnhancementState.class.getDeclaredField("count");
      f.setAccessible(true);
      f.setInt(player().getGameplay().getRogueEnhancements(), -1);
    }
    assertEquals(Retcode.PLAYER_DATA_ERROR, RogueEnhancement.enhance(player(), BUFF));
    assertEquals(300, player().getGameplay().getRogue().getCoins());
    assertEquals(1, player().getGameplay().getRogue().getBlessings().get(BUFF));
    held(4092314);
  }

  @Test
  void exhaustedCounterCapacityIsPreflightedBeforeTheDebitAndUpgrade() throws Exception {
    begin();
    assertEquals(Map.of(BUFF, 100), RogueEnhancement.quotes(player()));
    var f = RogueEnhancementState.class.getDeclaredField("count");
    f.setAccessible(true);
    f.setInt(player().getGameplay().getRogueEnhancements(), Integer.MAX_VALUE);
    assertEquals(Retcode.PLAYER_DATA_ERROR, RogueEnhancement.enhance(player(), BUFF));
    assertEquals(300, player().getGameplay().getRogue().getCoins());
    assertEquals(1, player().getGameplay().getRogue().getBlessings().get(BUFF));
    assertEquals(Integer.MAX_VALUE, player().getGameplay().getRogueEnhancements().total());
  }

  @Test
  void oldLevelTwoOwnershipCannotReconstructAnUnrecordedEnhancement() {
    begin();
    player().getGameplay().getRogue().getBlessings().put(BUFF, 2);
    assertEquals(0, player().getGameplay().getRogueEnhancements().total());
    assertEquals(0, state(4092314).getProgress());
    assertEquals(Retcode.QUEST_NOT_FINISH, player().getQuestManager().claim(4092314).retcode());
  }

  @Test
  void removedPrerequisiteHoldsThePreviouslyCompletedGoal() {
    enhance(10);
    player().getQuestStates().remove(4000001);
    player().getGameplay().getMainMissions().clear();
    held(4092314);
  }

  @Test
  void savedActualCounterRecoversProgressWhenTheNotificationCallbackWasInterrupted() {
    enhance(10);
    player().getQuestStates().put(4092314, new QuestState(4092314));
    assertEquals(10, state(4092314).getProgress());
    assertEquals(QuestState.Phase.FINISHED, state(4092314).getPhase());
    assertEquals(0, player().getHcoin());
  }

  @Test
  void operationCountAndActualDebitAreSavedBeforeTheProgressNotification() throws Exception {
    var saved = new ArrayList<Integer>();
    var notifications = new int[1];
    var actor =
        new UniverseEnhance46Test.Explorer() {
          @Override
          public emu.lunarcore.server.game.GameSession getSession() {
            return fixture.s;
          }

          @Override
          public void save() {
            saved.add(getGameplay().getRogueEnhancements().total());
          }

          @Override
          public void sendPacket(BasePacket packet) {
            if (packet instanceof emu.lunarcore.server.packet.send.PacketGetQuestDataScRsp
                && getGameplay().getRogueEnhancements().total() > 0) {
              assertEquals(getGameplay().getRogueEnhancements().total(), saved.getLast());
              assertEquals(200, getGameplay().getRogue().getCoins());
              assertEquals(2, getGameplay().getRogue().getBlessings().get(BUFF));
              assertEquals(1, getQuestStates().get(4092314).getProgress());
              notifications[0]++;
            }
          }
        };
    for (String name : List.of("gameplay", "questStates", "name")) {
      var f = Player.class.getDeclaredField(name);
      f.setAccessible(true);
      f.set(actor, f.get(player()));
    }
    var f = UniverseEnhance46Test.Session.class.getDeclaredField("explorer");
    f.setAccessible(true);
    f.set(fixture.s, actor);
    enhance(1);
    assertEquals(1, notifications[0]);
    assertEquals(1, saved.getLast());
  }

  @Test
  void concurrentEnhancementRequestsChargeAndCountTheActualOfferOnce() throws Exception {
    begin();
    assertEquals(Map.of(BUFF, 100), RogueEnhancement.quotes(player()));
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var jobs = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 24; i++) jobs.add(() -> RogueEnhancement.enhance(player(), BUFF));
      int successes = 0;
      for (var future : pool.invokeAll(jobs)) if (future.get() == Retcode.SUCC) successes++;
      assertEquals(1, successes);
      assertEquals(200, player().getGameplay().getRogue().getCoins());
      assertEquals(1, player().getGameplay().getRogueEnhancements().total());
      assertEquals(1, state(4092314).getProgress());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentActualGoalClaimsPayFiveJadesOnce() throws Exception {
    enhance(10);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var jobs = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 24; i++)
        jobs.add(() -> player().getQuestManager().claim(4092314).retcode());
      int successes = 0;
      for (var future : pool.invokeAll(jobs)) if (future.get() == Retcode.SUCC) successes++;
      assertEquals(1, successes);
      assertEquals(5, player().getHcoin());
    } finally {
      pool.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoPlayerMorphiaReloadsRetainTwoHundredActualOperationsAndBothRewardStates(boolean claimed)
      throws Exception {
    enhance(200);
    if (claimed)
      for (int id : List.of(4092314, 4092315))
        assertEquals(Retcode.SUCC, player().getQuestManager().claim(id).retcode());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "enhancement-achievements");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      for (String name : List.of("uid", "name", "gameplay", "questStates", "hcoin")) {
        var f = Player.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(document, name.equals("uid") ? 123456786 : f.get(player()));
      }
      store.save(document);
      var first = store.find(Player.class).first();
      assertEquals(200, first.getGameplay().getRogueEnhancements().total());
      assertEquals(claimed ? 15 : 0, first.getHcoin());
      for (int id : List.of(4092314, 4092315))
        assertEquals(
            claimed ? QuestState.Phase.CLOSED : QuestState.Phase.FINISHED,
            first.getQuestStates().get(id).getPhase());
      store.save(first);
      var second = store.find(Player.class).first();
      for (String name : List.of("gameplay", "questStates", "hcoin")) {
        var f = Player.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(player(), f.get(second));
      }
      for (int id : List.of(4092314, 4092315))
        assertEquals(
            claimed ? Retcode.QUEST_REWARD_ALREADY_TAKEN : Retcode.SUCC,
            player().getQuestManager().claim(id).retcode());
      assertEquals(15, player().getHcoin());
      assertEquals(200, state(4092315).getProgress());
      assertEquals(200, player().getGameplay().getRogueEnhancements().total());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void changedKnownSourceRuleTypeCannotFallBackToAnUnvalidatedReward() {
    enhance(10);
    var row = rules.get(4092314).deepCopy();
    row.addProperty("FinishType", "UnknownEnhancement");
    GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    held(4092314);
  }

  @ParameterizedTest
  @ValueSource(strings = {"questId", "progress", "finishTime"})
  void incoherentCompletedStateIsNotExposedOrPaid(String property) throws Exception {
    enhance(10);
    var f = QuestState.class.getDeclaredField(property);
    f.setAccessible(true);
    if (property.equals("finishTime")) f.setLong(player().getQuestStates().get(4092314), 0);
    else
      f.setInt(player().getQuestStates().get(4092314), property.equals("progress") ? 11 : 99999999);
    held(4092314);
  }

  @Test
  void insufficientFragmentsCannotUpgradeOrAdvanceTheCounter() {
    begin();
    assertEquals(Map.of(BUFF, 100), RogueEnhancement.quotes(player()));
    assertTrue(player().getGameplay().getRogue().spend(201));
    assertNotEquals(Retcode.SUCC, RogueEnhancement.enhance(player(), BUFF));
    assertEquals(99, player().getGameplay().getRogue().getCoins());
    assertEquals(1, player().getGameplay().getRogue().getBlessings().get(BUFF));
    assertEquals(0, player().getGameplay().getRogueEnhancements().total());
    assertEquals(0, state(4092314).getProgress());
  }
}
