package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual paid activations; tutorial completion and starting points are explicit fixture gates. */
class RogueTalentQuestTest {
  final Gson gson = ResourceLoader.getGson();
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldRules = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final Map<Integer, GameplayDefinitions.RogueTalent> oldTalents = new HashMap<>();
  final Map<Integer, GameplayDefinitions.RogueUnlock> oldUnlocks = new HashMap<>();
  final Map<Integer, GameplayDefinitions.RogueRule> oldUnlockRules = new HashMap<>();
  final Map<String, String> oldConstants = new HashMap<>();
  final Map<Integer, JsonObject> sourceRules = new HashMap<>();
  ItemExcel oldItem;
  Actor actor;

  static class Actor extends Player {
    int saves;

    @Override
    public void save() {
      saves++;
    }

    @Override
    public void sendPacket(BasePacket packet) {}
  }

  @BeforeEach
  void setup() throws Exception {
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldRules.addAll(GameData.getFinishWayExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldTalents.putAll(GameplayDefinitions.rogueTalents);
    oldUnlocks.putAll(GameplayDefinitions.rogueUnlocks);
    oldUnlockRules.putAll(GameplayDefinitions.rogueRules);
    oldConstants.putAll(GameplayDefinitions.rogueConstants);
    oldItem = GameData.getItemExcelMap().get(1);
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameData.getMainMissionExcelMap().values().clear();
    GameplayDefinitions.rogueTalents.clear();
    GameplayDefinitions.rogueUnlocks.clear();
    GameplayDefinitions.rogueRules.clear();
    GameplayDefinitions.rogueConstants.clear();
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-talent-quests-4.6.json")) {
      var rows =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(567, rows.size());
      for (var entry : rows) {
        var spec = entry.getAsJsonObject();
        var row = spec.getAsJsonObject("row");
        switch (spec.get("kind").getAsString()) {
          case "quest" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "rule" -> {
            var rule = gson.fromJson(row, FinishWayExcel.class);
            GameData.getFinishWayExcelMap().add(rule);
            sourceRules.put(rule.getId(), row);
          }
          case "reward" -> {
            var reward = gson.fromJson(row, RewardExcel.class);
            reward.onLoad();
            GameData.getRewardExcelMap().add(reward);
          }
          case "main" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "talent" -> {
            var talent = gson.fromJson(row, GameplayDefinitions.RogueTalent.class);
            GameplayDefinitions.rogueTalents.put(talent.TalentID(), talent);
          }
          case "unlock" -> {
            var unlock = gson.fromJson(row, GameplayDefinitions.RogueUnlock.class);
            GameplayDefinitions.rogueUnlocks.put(unlock.RogueUnlockID(), unlock);
          }
          case "unlockRule" -> {
            var rule = gson.fromJson(row, GameplayDefinitions.RogueRule.class);
            GameplayDefinitions.rogueRules.put(rule.ID(), rule);
          }
          case "constant" ->
              GameplayDefinitions.rogueConstants.put(
                  row.get("ConstRogueName").getAsString(), row.get("ConstValue").getAsString());
          case "item" -> {
            var copy = row.deepCopy();
            copy.addProperty("ItemName", copy.getAsJsonObject("ItemName").get("Hash").getAsLong());
            GameData.getItemExcelMap().add(gson.fromJson(copy, ItemExcel.class));
          }
          case "achievement" -> {}
          default -> fail(spec.toString());
        }
      }
    }
    actor = new Actor();
    assertTrue(actor.setNickname("TalentGoals"));
    assertTrue(actor.tryChangeTalentPoints(100000));
    for (int id : List.of(1000201, 4030007)) {
      var done = new QuestState(id);
      assertTrue(done.advance(1, 1, 1));
      actor.getGameplay().getMainMissions().put(id, done);
    }
    // This observes the explicit prerequisite fixture; achievement completion is never seeded.
    assertTrue(actor.getQuestManager().onMissionProgress());
    assertEquals(QuestState.Phase.FINISHED, actor.getQuestStates().get(4000001).getPhase());
  }

  @AfterEach
  void restore() {
    GameData.getQuestExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    oldRules.forEach(GameData.getFinishWayExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameplayDefinitions.rogueTalents.clear();
    GameplayDefinitions.rogueTalents.putAll(oldTalents);
    GameplayDefinitions.rogueUnlocks.clear();
    GameplayDefinitions.rogueUnlocks.putAll(oldUnlocks);
    GameplayDefinitions.rogueRules.clear();
    GameplayDefinitions.rogueRules.putAll(oldUnlockRules);
    GameplayDefinitions.rogueConstants.clear();
    GameplayDefinitions.rogueConstants.putAll(oldConstants);
    GameData.getItemExcelMap().keySet().remove(1);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
  }

  void activate(int count) {
    int before = actor.getTalentPoints();
    int cost = 0;
    for (int id = 1; id <= count; id++) {
      assertEquals(RogueTalents.Status.UNLOCK, RogueTalents.status(actor, id));
      cost +=
          GameplayDefinitions.rogueTalents.get(id).Cost().stream()
              .mapToInt(GameplayDefinitions.TalentCost::ItemNum)
              .sum();
      assertEquals(Retcode.SUCC, RogueTalents.enable(actor, id));
    }
    assertEquals(before - cost, actor.getTalentPoints());
    assertEquals(count, actor.getGameplay().getRogueTalents().size());
  }

  QuestState state(int id) {
    var matches =
        actor.getQuestManager().states().stream().filter(s -> s.getQuestId() == id).toList();
    assertEquals(1, matches.size());
    return matches.getFirst();
  }

  void held(int id) {
    assertTrue(actor.getQuestManager().states().stream().noneMatch(s -> s.getQuestId() == id));
    assertNotEquals(Retcode.SUCC, actor.getQuestManager().claim(id).retcode());
    assertEquals(0, actor.getHcoin());
  }

  @Test
  void actualFirstActivationImmediatelyAdvancesTheAcceptedAchievement() {
    activate(1);
    assertEquals(1, actor.getQuestStates().get(4092316).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 26})
  void originalThresholdActuallyFinishesAndPaysFiveJadesOnce(int goal) {
    activate(goal);
    int id = goal == 2 ? 4092316 : 4092317;
    assertEquals(QuestState.Phase.FINISHED, state(id).getPhase());
    assertEquals(goal, state(id).getProgress());
    assertEquals(Retcode.SUCC, actor.getQuestManager().claim(id).retcode());
    assertEquals(5, actor.getHcoin());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getQuestManager().claim(id).retcode());
    assertEquals(5, actor.getHcoin());
  }

  @Test
  void twentyFiveActivationsDoNotCompleteTheTwentySixGoal() {
    activate(25);
    assertEquals(25, state(4092317).getProgress());
    assertEquals(QuestState.Phase.DOING, state(4092317).getPhase());
    assertEquals(Retcode.QUEST_NOT_FINISH, actor.getQuestManager().claim(4092317).retcode());
  }

  @Test
  void repeatedActivationAndRepeatedQueriesNeverAddCountOrDebit() {
    activate(1);
    int points = actor.getTalentPoints();
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, RogueTalents.enable(actor, 1));
    for (int i = 0; i < 8; i++) assertEquals(1, state(4092316).getProgress());
    assertEquals(points, actor.getTalentPoints());
  }

  @Test
  void changingOrEndingTheExplorationKeepsPermanentTalentProgress() {
    activate(2);
    actor.getGameplay().setRogue(new GameplayState().getRogue());
    assertEquals(2, state(4092316).getProgress());
    assertEquals(QuestState.Phase.FINISHED, state(4092316).getPhase());
  }

  @Test
  void unknownPositiveHistoricalTalentDoesNotCount() {
    activate(1);
    actor.getGameplay().getRogueTalents().add(1999999999);
    assertEquals(1, state(4092316).getProgress());
  }

  @Test
  void realAdditionalTalentFortyTwoIsOutsideTheOriginalAchievementList() {
    activate(1);
    actor.getGameplay().getRogueTalents().add(42);
    assertEquals(1, state(4092316).getProgress());
  }

  @Test
  void sourcePrerequisiteStillBlocksAnUnacceptedAchievement() {
    actor.getQuestStates().remove(4092316);
    actor.getQuestStates().remove(4092317);
    actor.getQuestStates().remove(4000001);
    actor.getGameplay().getMainMissions().remove(1000201);
    activate(2);
    assertFalse(actor.getQuestStates().containsKey(4092316));
    assertEquals(Retcode.QUEST_NOT_ACCEPT, actor.getQuestManager().claim(4092316).retcode());
  }

  @Test
  void failedActivationWithInsufficientPointsDoesNotAddProgress() {
    assertTrue(actor.tryChangeTalentPoints(-actor.getTalentPoints()));
    assertEquals(Retcode.ITEM_NOT_ENOUGH, RogueTalents.enable(actor, 1));
    assertEquals(0, state(4092316).getProgress());
    assertTrue(actor.getGameplay().getRogueTalents().isEmpty());
  }

  @Test
  void fabricatedFinishedRecordWithoutActualActivationsCannotPay() {
    var fake = new QuestState(4092316);
    fake.advance(2, 2, 1);
    actor.getQuestStates().put(4092316, fake);
    held(4092316);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void nonpositiveHistoricalTalentHoldsTheWholeRecord(int id) {
    actor.getGameplay().getRogueTalents().add(id);
    held(4092316);
  }

  @Test
  void nullHistoricalTalentHoldsTheWholeRecord() {
    actor.getGameplay().getRogueTalents().add(null);
    held(4092316);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknown",
        "duplicate",
        "empty",
        "extraInt",
        "extraText",
        "unknownItem",
        "tooLarge",
        "wrongIdentity"
      })
  void malformedSourceRuleCannotExposeOrPayAnAchievement(String shape) throws Exception {
    var row = sourceRules.get(4092316).deepCopy();
    switch (shape) {
      case "unknown" -> row.add("ParamIntList", JsonParser.parseString("[1,1999999999]"));
      case "duplicate" -> row.add("ParamIntList", JsonParser.parseString("[1,1]"));
      case "empty" -> row.add("ParamIntList", new JsonArray());
      case "extraInt" -> row.addProperty("ParamInt2", 1);
      case "extraText" -> row.addProperty("ParamStr1", "UnknownCondition()");
      case "unknownItem" ->
          row.add("ParamItemList", JsonParser.parseString("[{\"ItemID\":1,\"ItemNum\":1}]"));
      case "tooLarge" -> row.addProperty("Progress", 42);
      case "wrongIdentity" -> {}
    }
    var replacement = gson.fromJson(row, FinishWayExcel.class);
    GameData.getFinishWayExcelMap().add(replacement);
    if (shape.equals("wrongIdentity")) {
      var identity = FinishWayExcel.class.getDeclaredField("ID");
      identity.setAccessible(true);
      identity.setInt(replacement, 99999999);
    }
    held(4092316);
  }

  @ParameterizedTest
  @ValueSource(strings = {"identity", "progress", "clock"})
  void incoherentSavedQuestCannotBeQueriedOrClaimed(String shape) throws Exception {
    var target = actor.getQuestStates().get(4092316);
    String field =
        shape.equals("identity") ? "questId" : shape.equals("progress") ? "progress" : "finishTime";
    var member = QuestState.class.getDeclaredField(field);
    member.setAccessible(true);
    member.set(
        target,
        shape.equals("clock") ? (Object) 1L : (Object) (shape.equals("identity") ? 99999999 : -1));
    held(4092316);
  }

  @Test
  void canonicalTalentIdentityMustMatchTheListedKey() {
    activate(2);
    var t = GameplayDefinitions.rogueTalents.get(2);
    GameplayDefinitions.rogueTalents.put(
        2,
        new GameplayDefinitions.RogueTalent(
            999, t.NextTalentIDList(), t.Cost(), t.UnlockIDList(), t.EffectDescParamList()));
    held(4092316);
  }

  @Test
  void missingListedTalentDefinitionHoldsTheSavedCompletion() {
    activate(2);
    GameplayDefinitions.rogueTalents.remove(2);
    held(4092316);
  }

  @Test
  void changedFinishTypeDoesNotExposeThePreviouslyCompletedSourceAchievement() {
    activate(2);
    var changed = sourceRules.get(4092316).deepCopy();
    changed.addProperty("FinishType", "Unknown");
    GameData.getFinishWayExcelMap().add(gson.fromJson(changed, FinishWayExcel.class));
    held(4092316);
  }

  @Test
  void removingTheActualPrerequisiteCannotMakeItsSavedAchievementClaimable() {
    activate(2);
    actor.getQuestStates().remove(4000001);
    held(4092316);
  }

  @Test
  void actualPurchasedOwnershipCanRestoreMissingProgressAfterAnInterruptedCallback() {
    activate(2);
    actor.getQuestStates().put(4092316, new QuestState(4092316));
    assertEquals(QuestState.Phase.FINISHED, state(4092316).getPhase());
    assertEquals(Retcode.SUCC, actor.getQuestManager().claim(4092316).retcode());
    assertEquals(5, actor.getHcoin());
  }

  @Test
  void pendingClaimNeverPaysAgain() {
    activate(2);
    assertTrue(state(4092316).reserveReward());
    assertEquals(Retcode.SYSTEM_BUSY, actor.getQuestManager().claim(4092316).retcode());
    assertEquals(0, actor.getHcoin());
  }

  @Test
  void concurrentSourceAchievementClaimsPayFiveJadesOnce() throws Exception {
    activate(2);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var jobs = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 24; i++) jobs.add(() -> actor.getQuestManager().claim(4092316).retcode());
      int successes = 0;
      for (var future : pool.invokeAll(jobs)) if (future.get() == Retcode.SUCC) successes++;
      assertEquals(1, successes);
      assertEquals(5, actor.getHcoin());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentActivationChargesOneNodeAndAdvancesOneStep() throws Exception {
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    int points = actor.getTalentPoints();
    try {
      var jobs = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 24; i++) jobs.add(() -> RogueTalents.enable(actor, 1));
      int successes = 0;
      for (var future : pool.invokeAll(jobs)) if (future.get() == Retcode.SUCC) successes++;
      assertEquals(1, successes);
      assertEquals(points - 50, actor.getTalentPoints());
      assertEquals(1, state(4092316).getProgress());
    } finally {
      pool.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoPlayerMorphiaReloadsKeepActualOwnershipProgressAndPayment(boolean claimed)
      throws Exception {
    activate(26);
    if (claimed) assertEquals(Retcode.SUCC, actor.getQuestManager().claim(4092317).retcode());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "talent-achievements");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      for (String name :
          List.of("uid", "name", "gameplay", "questStates", "hcoin", "talentPoints")) {
        var field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(document, name.equals("uid") ? 123456784 : field.get(actor));
      }
      store.save(document);
      var first = store.find(Player.class).first();
      assertEquals(26, first.getGameplay().getRogueTalents().size());
      assertEquals(claimed ? 5 : 0, first.getHcoin());
      assertEquals(
          claimed ? QuestState.Phase.CLOSED : QuestState.Phase.FINISHED,
          first.getQuestStates().get(4092317).getPhase());
      store.save(first);
      var second = store.find(Player.class).first();
      for (String name : List.of("gameplay", "questStates", "hcoin", "talentPoints")) {
        var field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(actor, field.get(second));
      }
      assertEquals(
          claimed ? Retcode.QUEST_REWARD_ALREADY_TAKEN : Retcode.SUCC,
          actor.getQuestManager().claim(4092317).retcode());
      assertEquals(5, actor.getHcoin());
      assertEquals(26, state(4092317).getProgress());
    } finally {
      mongo.shutdownNow();
    }
  }
}
