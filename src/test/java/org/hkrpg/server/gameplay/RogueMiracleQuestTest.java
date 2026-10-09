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
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original collection achievements; offers, tutorial and normal-blessing eligibility are fixtures.
 */
class RogueMiracleQuestTest {
  @TempDir Path temp;
  final Gson gson = ResourceLoader.getGson();
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldRules = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final List<MazeBuffExcel> oldMaze = new ArrayList<>();
  final List<GameplayDefinitions.Blessing> oldBuffs = new ArrayList<>();
  final Map<Integer, GameplayDefinitions.Miracle> oldMiracles = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleGroup> oldGroups = new HashMap<>();
  final Map<Integer, JsonObject> sourceRules = new HashMap<>();
  final JsonArray handbook = new JsonArray();
  ItemExcel oldItem;
  Actor actor;

  static class Actor extends Player {
    @Override
    public void save() {}

    @Override
    public void sendPacket(BasePacket packet) {}
  }

  @BeforeEach
  void setup() throws Exception {
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldRules.addAll(GameData.getFinishWayExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldMaze.addAll(GameData.getMazeBuffExcelMap().values());
    oldBuffs.addAll(GameplayDefinitions.blessings);
    oldMiracles.putAll(GameplayDefinitions.miracles);
    oldEffects.putAll(GameplayDefinitions.miracleEffects);
    oldGroups.putAll(GameplayDefinitions.miracleGroups);
    oldItem = GameData.getItemExcelMap().get(1);
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getMazeBuffExcelMap().values().clear();
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracleEffects.clear();
    GameplayDefinitions.miracleGroups.clear();
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-miracle-quests-4.6.json")) {
      var rows =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(1601, rows.size());
      for (var entry : rows) {
        var spec = entry.getAsJsonObject();
        var row = spec.getAsJsonObject("row");
        switch (spec.get("kind").getAsString()) {
          case "quest" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "rule" -> {
            var r = gson.fromJson(row, FinishWayExcel.class);
            GameData.getFinishWayExcelMap().add(r);
            sourceRules.put(r.getId(), row);
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
          case "miracle" -> {
            var r = gson.fromJson(row, GameplayDefinitions.Miracle.class);
            GameplayDefinitions.miracles.put(r.MiracleID(), r);
          }
          case "effect" -> {
            var r = gson.fromJson(row, GameplayDefinitions.MiracleEffect.class);
            GameplayDefinitions.miracleEffects.put(r.MiracleEffectID(), r);
          }
          case "group" -> {
            var r = gson.fromJson(row, GameplayDefinitions.MiracleGroup.class);
            GameplayDefinitions.miracleGroups.put(r.RogueMiracleGroupID(), r);
          }
          case "handbook" -> handbook.add(row);
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
    Files.createDirectories(temp.resolve("ExcelOutput"));
    // Explicit collection-test qualification: permit ordinary source blessings from locked paths.
    // Original fixture rows stay intact; resonance cannot replace a normal Firewax reward.
    GameplayDefinitions.blessings.replaceAll(
        b ->
            b.AeonID() == 0 && b.BattleEventBuffType() == null
                ? new GameplayDefinitions.Blessing(
                    b.MazeBuffID(),
                    b.MazeBuffLevel(),
                    b.RogueBuffType(),
                    b.RogueBuffCategory(),
                    b.RogueVersion(),
                    List.of(),
                    b.IsShow(),
                    b.RogueBuffTag(),
                    b.AeonID(),
                    b.BattleEventBuffType())
                : b);
    Files.writeString(temp.resolve("ExcelOutput/RogueHandbookMiracle.json"), handbook.toString());
    RogueHandbookCatalog.load(temp);
    actor = new Actor();
    assertTrue(actor.setNickname("MiracleGoals"));
    var prerequisite = new QuestState(1000201);
    assertTrue(prerequisite.advance(1, 1, 1));
    actor.getGameplay().getMainMissions().put(1000201, prerequisite);
    assertTrue(actor.getQuestManager().onMissionProgress());
    assertEquals(QuestState.Phase.FINISHED, actor.getQuestStates().get(4000001).getPhase());
  }

  @AfterEach
  void restore() throws Exception {
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
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracles.putAll(oldMiracles);
    GameplayDefinitions.miracleEffects.clear();
    GameplayDefinitions.miracleEffects.putAll(oldEffects);
    GameplayDefinitions.miracleGroups.clear();
    GameplayDefinitions.miracleGroups.putAll(oldGroups);
    GameData.getItemExcelMap().keySet().remove(1);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
    RogueHandbookCatalog.load(temp.resolve("absent"));
  }

  void collect(int id) {
    var run = new GameplayState.RogueRun(100, 1, Map.of());
    actor.getGameplay().setRogue(run);
    run.setInside(true);
    run.offerMiracles(List.of(id));
    assertEquals(
        Retcode.SUCC,
        new UniverseManager(actor).selectMiracle(run.getSelectionId(), id),
        "Source acquisition failed id=" + id);
    assertTrue(
        actor
            .getGameplay()
            .getRogueHandbook()
            .getMiracles()
            .contains(RogueHandbookCatalog.unlock(id)));
  }

  void collectThree() {
    for (int id : List.of(2, 7, 12)) collect(id);
  }

  void collectForty() {
    collectCount(40);
  }

  void collectCount(int count) {
    var ids =
        GameplayDefinitions.miracles.keySet().stream()
            .filter(
                id ->
                    id != 21 && RogueMiracles.supported(id) && RogueHandbookCatalog.unlock(id) > 0)
            .sorted()
            .limit(count)
            .toList();
    assertEquals(count, ids.size());
    ids.forEach(this::collect);
    assertEquals(count, actor.getGameplay().getRogueHandbook().getMiracles().size());
  }

  QuestState state(int id) {
    var rows = actor.getQuestManager().states().stream().filter(s -> s.getQuestId() == id).toList();
    assertEquals(1, rows.size());
    return rows.getFirst();
  }

  void held(int id) {
    assertTrue(actor.getQuestManager().states().stream().noneMatch(s -> s.getQuestId() == id));
    assertNotEquals(Retcode.SUCC, actor.getQuestManager().claim(id).retcode());
    assertEquals(0, actor.getHcoin());
  }

  @Test
  void actualSelectionImmediatelyAdvancesTheOriginalThreeGoal() {
    collect(2);
    assertEquals(1, actor.getQuestStates().get(4092318).getProgress());
  }

  @Test
  void threeRealDistinctSelectionsFinishTheOriginalGoal() {
    collectThree();
    assertEquals(3, state(4092318).getProgress());
    assertEquals(QuestState.Phase.FINISHED, state(4092318).getPhase());
  }

  @Test
  void originalThreeCollectionRewardActuallyPaysFiveJadesOnce() {
    collectThree();
    assertEquals(Retcode.SUCC, actor.getQuestManager().claim(4092318).retcode());
    assertEquals(5, actor.getHcoin());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getQuestManager().claim(4092318).retcode());
    assertEquals(5, actor.getHcoin());
  }

  @Test
  void fortyActualSelectionsFinishTheOriginalLargerGoalAndPayTenJades() {
    collectForty();
    assertEquals(40, state(4092319).getProgress());
    assertEquals(Retcode.SUCC, actor.getQuestManager().claim(4092319).retcode());
    assertEquals(10, actor.getHcoin());
  }

  @Test
  void repeatedSameCurioInAnotherRunDoesNotAddCount() {
    collect(2);
    collect(2);
    assertEquals(1, state(4092318).getProgress());
  }

  @Test
  void endedRunAndRepeatedQueriesRetainHistoryWithoutExtraProgress() {
    collectThree();
    actor.getGameplay().setRogue(new GameplayState().getRogue());
    for (int i = 0; i < 6; i++) assertEquals(3, state(4092318).getProgress());
  }

  @Test
  void unlinkedEffectAliasDoesNotCreateASecondCollection() {
    collect(2);
    var run = new GameplayState.RogueRun(100, 1, Map.of());
    actor.getGameplay().setRogue(run);
    assertTrue(RogueMiracles.acquire(actor, 10));
    assertEquals(0, RogueHandbookCatalog.unlock(10));
    assertEquals(1, state(4092318).getProgress());
  }

  @Test
  void actualRepairedEffectWithoutAnUnlockLinkDoesNotBecomeACollectedIdentity() {
    collect(2);
    var run = new GameplayState.RogueRun(100, 1, Map.of());
    actor.getGameplay().setRogue(run);
    assertTrue(RogueMiracles.acquire(actor, 46));
    assertEquals(0, RogueHandbookCatalog.unlock(46));
    assertEquals(1, state(4092318).getProgress());
  }

  @Test
  void unknownPositiveHistoricalHandbookDoesNotCount() {
    collect(2);
    assertTrue(actor.getGameplay().getRogueHandbook().record(1999999999));
    assertEquals(1, state(4092318).getProgress());
  }

  @Test
  void sourcePrerequisiteStillBlocksAnUnacceptedAchievement() {
    actor.getQuestStates().remove(4092318);
    actor.getQuestStates().remove(4092319);
    actor.getQuestStates().remove(4000001);
    actor.getGameplay().getMainMissions().clear();
    collectThree();
    assertFalse(actor.getQuestStates().containsKey(4092318));
    assertFalse(
        actor.getQuestStates().containsKey(4000001),
        "Collection must not initialize an unrelated uncompleted tutorial goal");
    assertEquals(Retcode.QUEST_NOT_ACCEPT, actor.getQuestManager().claim(4092318).retcode());
  }

  @Test
  void fabricatedFinishedRecordWithoutCollectedHistoryCannotPay() {
    var fake = new QuestState(4092318);
    fake.advance(3, 3, 1);
    actor.getQuestStates().put(4092318, fake);
    held(4092318);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void damagedHistoryDoesNotExposeOrPayTheAchievement(int kind) throws Exception {
    var f = RogueHandbookState.class.getDeclaredField("miracles");
    f.setAccessible(true);
    f.set(
        actor.getGameplay().getRogueHandbook(),
        kind == 0 ? null : new HashSet<>(kind == 1 ? List.of(-1) : Arrays.asList((Integer) null)));
    held(4092318);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"extraInt", "extraList", "extraText", "extraItem", "tooLarge", "wrongIdentity"})
  void malformedOriginalRuleIsHeld(String shape) throws Exception {
    var row = sourceRules.get(4092318).deepCopy();
    switch (shape) {
      case "extraInt" -> row.addProperty("ParamInt1", 1);
      case "extraList" -> row.add("ParamIntList", JsonParser.parseString("[2]"));
      case "extraText" -> row.addProperty("ParamStr1", "UnknownCondition()");
      case "extraItem" ->
          row.add("ParamItemList", JsonParser.parseString("[{\"ItemID\":1,\"ItemNum\":1}]"));
      case "tooLarge" -> row.addProperty("Progress", 4097);
      case "wrongIdentity" -> {}
    }
    var r = gson.fromJson(row, FinishWayExcel.class);
    GameData.getFinishWayExcelMap().add(r);
    if (shape.equals("wrongIdentity")) {
      var f = FinishWayExcel.class.getDeclaredField("ID");
      f.setAccessible(true);
      f.setInt(r, 99999999);
    }
    held(4092318);
  }

  @Test
  void thirtyNineActualCollectionsCannotFinishOrPayTheFortyGoal() {
    collectCount(39);
    assertEquals(39, state(4092319).getProgress());
    assertEquals(QuestState.Phase.DOING, state(4092319).getPhase());
    assertEquals(Retcode.QUEST_NOT_FINISH, actor.getQuestManager().claim(4092319).retcode());
    assertEquals(0, actor.getHcoin());
  }

  @Test
  void bothOriginalGoalsPayExactlyFifteenJadesAndRepeatedClaimsPayNothing() {
    collectForty();
    for (int id : List.of(4092318, 4092319))
      assertEquals(Retcode.SUCC, actor.getQuestManager().claim(id).retcode());
    assertEquals(15, actor.getHcoin());
    for (int id : List.of(4092318, 4092319)) {
      assertEquals(QuestState.Phase.CLOSED, state(id).getPhase());
      assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getQuestManager().claim(id).retcode());
    }
    assertEquals(15, actor.getHcoin());
  }

  @Test
  void pendingClaimIsHeldWithoutAnotherPayment() {
    collectThree();
    assertTrue(state(4092318).reserveReward());
    assertEquals(Retcode.SYSTEM_BUSY, actor.getQuestManager().claim(4092318).retcode());
    assertEquals(0, actor.getHcoin());
  }

  @Test
  void concurrentActualCollectionRewardClaimsPayFiveJadesOnce() throws Exception {
    collectThree();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var jobs = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 24; i++) jobs.add(() -> actor.getQuestManager().claim(4092318).retcode());
      int successes = 0;
      for (var future : pool.invokeAll(jobs)) if (future.get() == Retcode.SUCC) successes++;
      assertEquals(1, successes);
      assertEquals(5, actor.getHcoin());
    } finally {
      pool.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoPlayerMorphiaReloadsRetainFortyActualCollectionsAndBothRewardStates(boolean claimed)
      throws Exception {
    collectForty();
    if (claimed)
      for (int id : List.of(4092318, 4092319))
        assertEquals(Retcode.SUCC, actor.getQuestManager().claim(id).retcode());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "miracle-achievements");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      for (String name : List.of("uid", "name", "gameplay", "questStates", "hcoin")) {
        var field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(document, name.equals("uid") ? 123456785 : field.get(actor));
      }
      store.save(document);
      var first = store.find(Player.class).first();
      assertEquals(40, first.getGameplay().getRogueHandbook().getMiracles().size());
      assertEquals(claimed ? 15 : 0, first.getHcoin());
      for (int id : List.of(4092318, 4092319))
        assertEquals(
            claimed ? QuestState.Phase.CLOSED : QuestState.Phase.FINISHED,
            first.getQuestStates().get(id).getPhase());
      store.save(first);
      var second = store.find(Player.class).first();
      for (String name : List.of("gameplay", "questStates", "hcoin")) {
        var field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(actor, field.get(second));
      }
      for (int id : List.of(4092318, 4092319))
        assertEquals(
            claimed ? Retcode.QUEST_REWARD_ALREADY_TAKEN : Retcode.SUCC,
            actor.getQuestManager().claim(id).retcode());
      assertEquals(15, actor.getHcoin());
      assertEquals(40, state(4092319).getProgress());
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void removedCanonicalCurioDefinitionCannotSupportAnEarlierCompletedGoal() {
    collectThree();
    GameplayDefinitions.miracles.remove(2);
    held(4092318);
  }

  @Test
  void changedKnownSourceRuleTypeCannotFallBackToAnUnvalidatedReward() {
    collectThree();
    var row = sourceRules.get(4092318).deepCopy();
    row.addProperty("FinishType", "UnknownCollection");
    GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    held(4092318);
  }

  @Test
  void removedActualSourcePrerequisiteCannotExposeOrPayACompletedCollectionGoal() {
    collectThree();
    actor.getQuestStates().remove(4000001);
    actor.getGameplay().getMainMissions().clear();
    held(4092318);
  }

  @Test
  void actualHistoryRecoversProgressAfterTheAcquisitionCallbackWasInterrupted() {
    collectThree();
    actor.getQuestStates().put(4092318, new QuestState(4092318));
    assertEquals(3, state(4092318).getProgress());
    assertEquals(QuestState.Phase.FINISHED, state(4092318).getPhase());
    assertEquals(0, actor.getHcoin());
  }

  @ParameterizedTest
  @ValueSource(strings = {"questId", "progress", "finishTime"})
  void incoherentCompletedStateIsNotExposedOrPaid(String property) throws Exception {
    collectThree();
    var f = QuestState.class.getDeclaredField(property);
    f.setAccessible(true);
    if (property.equals("finishTime")) f.setLong(actor.getQuestStates().get(4092318), 0);
    else f.setInt(actor.getQuestStates().get(4092318), property.equals("progress") ? 4 : 99999999);
    held(4092318);
  }
}
