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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original path, quest and blessing rows; each collection uses the actual selection operation. */
class RogueBlessingQuestTest {
  @TempDir Path temp;
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldRules = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<MazeBuffExcel> oldMaze = new ArrayList<>();
  final List<GameplayDefinitions.Blessing> oldBuffs = new ArrayList<>();
  final Map<Integer, List<Integer>> visible = new TreeMap<>();
  final Map<Integer, JsonObject> sourceRules = new HashMap<>();
  final JsonArray types = new JsonArray();
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

  void catalog(Path root) throws Exception {
    try {
      Class.forName("org.hkrpg.server.gameplay.RogueBlessingQuests")
          .getMethod("load", Path.class)
          .invoke(null, root);
    } catch (ClassNotFoundException absentOnOriginalBaseline) {
    }
  }

  @BeforeEach
  void setup() throws Exception {
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldRules.addAll(GameData.getFinishWayExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldMaze.addAll(GameData.getMazeBuffExcelMap().values());
    oldBuffs.addAll(GameplayDefinitions.blessings);
    oldItem = GameData.getItemExcelMap().get(1);
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameData.getMazeBuffExcelMap().values().clear();
    GameplayDefinitions.blessings.clear();
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-blessing-quests-4.6.json")) {
      var records =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(1109, records.size());
      for (var entry : records) {
        var spec = entry.getAsJsonObject();
        var row = spec.getAsJsonObject("row");
        switch (spec.get("kind").getAsString()) {
          case "type" -> types.add(row);
          case "quest" ->
              GameData.getQuestExcelMap()
                  .add(ResourceLoader.getGson().fromJson(row, QuestExcel.class));
          case "rule" -> {
            var value = ResourceLoader.getGson().fromJson(row, FinishWayExcel.class);
            GameData.getFinishWayExcelMap().add(value);
            sourceRules.put(value.getId(), row);
          }
          case "buff" -> {
            var value = ResourceLoader.getGson().fromJson(row, GameplayDefinitions.Blessing.class);
            GameplayDefinitions.blessings.add(value);
            if (row.has("IsShow")
                && row.get("IsShow").getAsBoolean()
                && value.MazeBuffLevel() == 1
                && value.RogueVersion() == 1)
              visible
                  .computeIfAbsent(value.RogueBuffType(), ignored -> new ArrayList<>())
                  .add(value.MazeBuffID());
          }
          case "maze" ->
              GameData.getMazeBuffExcelMap()
                  .add(ResourceLoader.getGson().fromJson(row, MazeBuffExcel.class));
          case "reward" -> {
            var value = ResourceLoader.getGson().fromJson(row, RewardExcel.class);
            value.onLoad();
            GameData.getRewardExcelMap().add(value);
          }
          case "item" -> {
            var copy = row.deepCopy();
            copy.addProperty("ItemName", copy.getAsJsonObject("ItemName").get("Hash").getAsLong());
            GameData.getItemExcelMap()
                .add(ResourceLoader.getGson().fromJson(copy, ItemExcel.class));
          }
          default -> fail(spec.toString());
        }
      }
    }
    Files.createDirectories(temp.resolve("ExcelOutput"));
    Files.writeString(temp.resolve("ExcelOutput/RogueBuffType.json"), types.toString());
    catalog(temp);
    actor = new Actor();
    assertTrue(actor.setNickname("BlessingBook"));
    actor.getQuestManager().refreshUnlocks();
    actor.saves = 0;
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getQuestExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    oldRules.forEach(GameData.getFinishWayExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getMazeBuffExcelMap().values().clear();
    oldMaze.forEach(GameData.getMazeBuffExcelMap()::add);
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.blessings.addAll(oldBuffs);
    GameData.getItemExcelMap().keySet().remove(1);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
    catalog(temp.resolve("absent"));
  }

  void collect(int id) {
    var run = new GameplayState.RogueRun(100, 1, Map.of());
    actor.getGameplay().setRogue(run);
    run.offer(List.of(id));
    assertEquals(Retcode.SUCC, new UniverseManager(actor).selectBlessing(run.getSelectionId(), id));
    assertTrue(actor.getGameplay().getRogueCollectedBuffs().contains(id));
  }

  void collectPath(int type, int number) {
    visible.get(type).subList(0, number).forEach(this::collect);
  }

  QuestState state(int id) {
    var state =
        actor.getQuestManager().states().stream()
            .filter(s -> s.getQuestId() == id)
            .findFirst()
            .orElse(null);
    assertNotNull(state, "Source handbook quest is missing: " + id);
    return state;
  }

  @Test
  void actualSelectionImmediatelyAdvancesTheAcceptedQuest() {
    collect(visible.get(120).getFirst());
    assertEquals(1, actor.getQuestStates().get(3000101).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {120, 121, 122, 123, 124, 125, 126, 127, 128})
  void fiveDistinctActualBlessingsFinishEachOriginalFirstPathTier(int type) {
    collectPath(type, 5);
    var row =
        types.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(t -> t.get("RogueBuffType").getAsInt() == type)
            .findFirst()
            .orElseThrow();
    int quest = row.getAsJsonArray("RugueBuffTypeRewardQuestList").get(0).getAsInt();
    assertEquals(QuestState.Phase.FINISHED, state(quest).getPhase());
    assertEquals(5, state(quest).getProgress());
  }

  @Test
  void collectingTheSameBlessingInAnotherExplorationDoesNotDoubleCount() {
    int id = visible.get(120).getFirst();
    collect(id);
    collect(id);
    assertEquals(1, state(3000101).getProgress());
  }

  @Test
  void differentPathsKeepIndependentCounts() {
    collect(visible.get(120).getFirst());
    collect(visible.get(121).getFirst());
    assertEquals(1, state(3000101).getProgress());
    assertEquals(1, state(3000105).getProgress());
  }

  @Test
  void sourceBacktrackTierIsAcceptedWithoutAnActivityWindow() {
    assertEquals(QuestState.Phase.DOING, state(3000130).getPhase());
    assertEquals(0, state(3000130).getProgress());
  }

  @Test
  void twentySixDistinctCollectionsFinishTheOriginalPermanentTier() {
    collectPath(120, 26);
    assertEquals(QuestState.Phase.FINISHED, state(3000148).getPhase());
    assertEquals(26, state(3000148).getProgress());
  }

  @Test
  void firstTierActuallyPaysOriginalEightyJadesOnce() {
    collectPath(120, 5);
    assertEquals(QuestState.Phase.FINISHED, state(3000101).getPhase());
    var reward = actor.getQuestManager().claim(3000101);
    assertEquals(Retcode.SUCC, reward.retcode());
    assertEquals(80, actor.getHcoin());
    assertEquals(1, reward.items().size());
    assertEquals(80, reward.items().getFirst().getCount());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getQuestManager().claim(3000101).retcode());
    assertEquals(80, actor.getHcoin());
  }

  @Test
  void permanentTypeThreeTierActuallyPaysThirtyJadesOnce() {
    collectPath(120, 24);
    assertEquals(QuestState.Phase.FINISHED, state(3000130).getPhase());
    assertEquals(Retcode.SUCC, actor.getQuestManager().claim(3000130).retcode());
    assertEquals(30, actor.getHcoin());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getQuestManager().claim(3000130).retcode());
    assertEquals(30, actor.getHcoin());
  }

  @Test
  void endedExplorationRetainsTheUnclaimedPermanentTier() {
    collectPath(120, 24);
    actor.getGameplay().setRogue(new GameplayState().getRogue());
    assertEquals(QuestState.Phase.FINISHED, state(3000130).getPhase());
    assertEquals(Retcode.SUCC, actor.getQuestManager().claim(3000130).retcode());
    assertEquals(30, actor.getHcoin());
  }

  void notExposed(int id) {
    var rows = actor.getQuestManager().states();
    assertEquals(rows.size(), rows.stream().map(QuestState::getQuestId).distinct().count());
    assertFalse(rows.stream().anyMatch(s -> s.getQuestId() == id));
    assertNotEquals(Retcode.SUCC, actor.getQuestManager().claim(id).retcode());
    assertEquals(0, actor.getHcoin());
  }

  @Test
  void fabricatedCompletionWithoutActualCollectionsIsNeitherShownNorPaid() {
    actor
        .getQuestStates()
        .put(
            3000101,
            new Gson()
                .fromJson(
                    "{\"questId\":3000101,\"progress\":5,\"finishTime\":1,\"phase\":\"FINISHED\"}",
                    QuestState.class));
    notExposed(3000101);
  }

  @ParameterizedTest
  @ValueSource(strings = {"identity", "negative", "clock"})
  void damagedAcceptedRecordIsNeitherShownNorPaid(String damage) {
    collect(visible.get(120).getFirst());
    var row =
        JsonParser.parseString(
                "{\"questId\":3000101,\"progress\":1,\"finishTime\":0,\"phase\":\"DOING\"}")
            .getAsJsonObject();
    if (damage.equals("identity")) row.addProperty("questId", 3000105);
    if (damage.equals("negative")) row.addProperty("progress", -1);
    if (damage.equals("clock")) row.addProperty("finishTime", 1);
    actor.getQuestStates().put(3000101, new Gson().fromJson(row, QuestState.class));
    notExposed(3000101);
  }

  @Test
  void losingTheCanonicalMazeResourceHoldsAnEarlierStoredCompletion() {
    collectPath(120, 5);
    GameData.getMazeBuffExcelMap()
        .values()
        .removeIf(row -> row.getID() == visible.get(120).getFirst());
    notExposed(3000101);
  }

  @Test
  void changingTheOriginalPathDoesNotExposeAnEarlierStoredCompletion() {
    collectPath(120, 5);
    var row = sourceRules.get(3000101).deepCopy();
    row.addProperty("ParamInt1", 121);
    GameData.getFinishWayExcelMap()
        .add(ResourceLoader.getGson().fromJson(row, FinishWayExcel.class));
    notExposed(3000101);
  }

  @Test
  void missingPathCatalogueDoesNotExposeAnEarlierStoredCompletion() throws Exception {
    collectPath(120, 5);
    catalog(temp.resolve("absent"));
    notExposed(3000101);
  }

  static java.util.stream.Stream<Integer> allSourceTiers() throws Exception {
    try (var in =
        RogueBlessingQuestTest.class.getResourceAsStream(
            "/fixtures/rogue-blessing-quests-4.6.json")) {
      var rows =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      return rows.asList().stream()
          .map(JsonElement::getAsJsonObject)
          .filter(r -> r.get("kind").getAsString().equals("type"))
          .flatMap(
              r ->
                  r
                      .getAsJsonObject("row")
                      .getAsJsonArray("RugueBuffTypeRewardQuestList")
                      .asList()
                      .stream())
          .map(JsonElement::getAsInt)
          .toList()
          .stream();
    }
  }

  @ParameterizedTest
  @MethodSource("allSourceTiers")
  void everyOriginalPathTierFinishesAtItsExactDistinctCountAndPaysItsOriginalAmount(int id) {
    var rule =
        GameData.getFinishWayExcelMap().get(GameData.getQuestExcelMap().get(id).getFinishWayID());
    var choices = visible.get(rule.getParamInt1());
    collectPath(rule.getParamInt1(), rule.getProgress() - 1);
    assertEquals(QuestState.Phase.DOING, state(id).getPhase());
    assertEquals(rule.getProgress() - 1, state(id).getProgress());
    collect(choices.get(rule.getProgress() - 1));
    assertEquals(QuestState.Phase.FINISHED, state(id).getPhase());
    assertEquals(rule.getProgress(), state(id).getProgress());
    int expected =
        GameData.getRewardExcelMap()
            .get(GameData.getQuestExcelMap().get(id).getRewardID())
            .getRewards()
            .get(1);
    assertEquals(Retcode.SUCC, actor.getQuestManager().claim(id).retcode());
    assertEquals(expected, actor.getHcoin());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getQuestManager().claim(id).retcode());
    assertEquals(expected, actor.getHcoin());
  }

  @Test
  void hiddenSourceResonanceDoesNotCountAsAVisibleCollectedBlessing() {
    var row =
        GameplayDefinitions.blessings.stream()
            .filter(r -> r.RogueBuffType() == 120 && r.MazeBuffLevel() == 1 && !r.IsShow())
            .findFirst()
            .orElseThrow();
    collect(row.MazeBuffID());
    assertEquals(0, state(3000101).getProgress());
    collect(visible.get(120).getFirst());
    assertEquals(1, state(3000101).getProgress());
  }

  @Test
  void sourceEnhancementDoesNotCreateAnotherDistinctHandbookEntry() {
    int id = visible.get(120).getFirst();
    collect(id);
    actor.getGameplay().getRogue().getBlessings().put(id, 2);
    assertEquals(1, state(3000101).getProgress());
  }

  @Test
  void staleUnknownPositiveHistoryDoesNotInflateTheActualPathCount() {
    actor.getGameplay().getRogueCollectedBuffs().add(1999999999);
    collect(visible.get(120).getFirst());
    assertEquals(1, state(3000101).getProgress());
  }

  @Test
  void originalAchievementUnlockConditionsAreRetainedAfterActualCollections() {
    collectPath(120, 12);
    for (int id :
        List.of(4092323, 4092324, 4092325, 4092326, 4092327, 4092328, 4092329, 4092633, 4092902)) {
      assertFalse(actor.getQuestStates().containsKey(id));
      assertEquals(Retcode.QUEST_NOT_ACCEPT, actor.getQuestManager().claim(id).retcode());
    }
    assertEquals(0, actor.getHcoin());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void damagedWholeCollectionHistoryHoldsItsSavedProgress(int damage) throws Exception {
    collect(visible.get(120).getFirst());
    if (damage == 0) actor.getGameplay().getRogueCollectedBuffs().add(null);
    if (damage == 1) actor.getGameplay().getRogueCollectedBuffs().add(-1);
    if (damage == 2) {
      var field = GameplayState.class.getDeclaredField("rogueCollectedBuffs");
      field.setAccessible(true);
      field.set(actor.getGameplay(), null);
    }
    notExposed(3000101);
  }

  @Test
  void ambiguousDuplicateBlessingDefinitionCannotSupportSavedProgress() {
    collect(visible.get(120).getFirst());
    var row =
        GameplayDefinitions.blessings.stream()
            .filter(r -> r.MazeBuffID() == visible.get(120).getFirst() && r.MazeBuffLevel() == 1)
            .findFirst()
            .orElseThrow();
    GameplayDefinitions.blessings.add(row);
    notExposed(3000101);
  }

  @Test
  void unknownAdditionalFinishParameterDoesNotSupportSavedCompletion() {
    collectPath(120, 5);
    var row = sourceRules.get(3000101).deepCopy();
    row.addProperty("ParamStr1", "unsupported");
    GameData.getFinishWayExcelMap()
        .add(ResourceLoader.getGson().fromJson(row, FinishWayExcel.class));
    notExposed(3000101);
  }

  @Test
  void visibilityChangedAfterCompletionHoldsTheReward() {
    collectPath(120, 5);
    int id = visible.get(120).getFirst();
    for (int i = 0; i < GameplayDefinitions.blessings.size(); i++) {
      var r = GameplayDefinitions.blessings.get(i);
      if (r.MazeBuffID() == id && r.MazeBuffLevel() == 1)
        GameplayDefinitions.blessings.set(
            i,
            new GameplayDefinitions.Blessing(
                r.MazeBuffID(),
                r.MazeBuffLevel(),
                r.RogueBuffType(),
                r.RogueBuffCategory(),
                r.RogueVersion(),
                r.UnlockIDList(),
                false));
    }
    notExposed(3000101);
  }

  @Test
  void concurrentClaimAttemptsPayTheOriginalEightyJadesOnlyOnce() throws Exception {
    collectPath(120, 5);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      var jobs = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 24; i++) jobs.add(() -> actor.getQuestManager().claim(3000101).retcode());
      int successes = 0;
      for (var result : pool.invokeAll(jobs)) if (result.get() == Retcode.SUCC) successes++;
      assertEquals(1, successes);
      assertEquals(80, actor.getHcoin());
    } finally {
      pool.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoActualPlayerMorphiaReloadsRetainThePermanentTierHistoryWalletAndClaim(boolean claimed)
      throws Exception {
    collectPath(120, 24);
    state(3000130);
    if (claimed) assertEquals(Retcode.SUCC, actor.getQuestManager().claim(3000130).retcode());
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "blessing-handbook");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var document = new Player();
      for (String name : List.of("uid", "name", "gameplay", "questStates", "hcoin")) {
        var field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(document, name.equals("uid") ? 123456783 : field.get(actor));
      }
      store.save(document);
      var first = store.find(Player.class).first();
      assertEquals(
          claimed ? QuestState.Phase.CLOSED : QuestState.Phase.FINISHED,
          first.getQuestStates().get(3000130).getPhase());
      assertEquals(24, first.getGameplay().getRogueCollectedBuffs().size());
      assertEquals(claimed ? 30 : 0, first.getHcoin());
      first.getGameplay().setRogue(new GameplayState().getRogue());
      store.save(first);
      var second = store.find(Player.class).first();
      assertEquals(24, second.getGameplay().getRogueCollectedBuffs().size());
      for (String name : List.of("gameplay", "questStates", "hcoin")) {
        var field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(actor, field.get(second));
      }
      assertEquals(
          claimed ? Retcode.QUEST_REWARD_ALREADY_TAKEN : Retcode.SUCC,
          actor.getQuestManager().claim(3000130).retcode());
      assertEquals(30, actor.getHcoin());
    } finally {
      mongo.shutdownNow();
    }
  }
}
