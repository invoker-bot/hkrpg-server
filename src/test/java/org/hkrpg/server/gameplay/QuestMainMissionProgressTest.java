package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original final tutorial talk and two branch graphs; five tutorial predecessors are fixtures. */
class QuestMainMissionProgressTest {
  static final int BASE = 4000001, PAIR = 4052910;
  final Gson gson = new Gson();
  final Map<Integer, MissionInfo> previousMains = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> previousSubs = new HashMap<>();
  final List<MainMissionExcel> previousExcel = new ArrayList<>();
  final List<QuestExcel> previousQuests = new ArrayList<>();
  final List<FinishWayExcel> previousRules = new ArrayList<>();
  final Map<Integer, JsonObject> definitions = new HashMap<>(), subs = new HashMap<>();
  Actor actor;

  static class Actor extends Player {
    int floor = 20002001, saves;

    public int getFloorId() {
      return floor;
    }

    public void save() {
      saves++;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    previousMains.putAll(GameData.getMissionInfos());
    previousSubs.putAll(GameplayDefinitions.subs);
    previousExcel.addAll(GameData.getMainMissionExcelMap().values());
    previousQuests.addAll(GameData.getQuestExcelMap().values());
    previousRules.addAll(GameData.getFinishWayExcelMap().values());
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/quest-main-completion-4.6.json"),
            StandardCharsets.UTF_8)) {
      var records = JsonParser.parseReader(reader).getAsJsonArray();
      assertEquals(28, records.size());
      for (var raw : records) {
        var entry = raw.getAsJsonObject();
        var row = entry.getAsJsonObject("row");
        switch (entry.get("kind").getAsString()) {
          case "main" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "quest" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "rule" ->
              GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
          case "definition" -> definitions.put(row.get("MainMissionID").getAsInt(), row);
          case "sub" -> subs.put(row.get("ID").getAsInt(), row);
          default -> fail("Unexpected fixture kind");
        }
      }
    }
    actor = new Actor();
    for (var entry : definitions.entrySet()) {
      var full = entry.getValue().deepCopy();
      var children = new JsonArray();
      subs.values().stream()
          .filter(row -> row.get("MainMissionID").getAsInt() == entry.getKey())
          .forEach(row -> children.add(row.deepCopy()));
      full.add("SubMissionList", children);
      var main = gson.fromJson(full, MissionInfo.class);
      GameData.getMissionInfos().put(entry.getKey(), main);
      for (var child : main.getSubMissionList()) GameplayDefinitions.subs.put(child.getId(), child);
      actor.getGameplay().getMainMissions().put(entry.getKey(), new QuestState(entry.getKey()));
    }
    for (int id : List.of(100020104, 100020105, 100020106, 100020107, 100020131))
      actor.getGameplay().getSubMissions().put(id, complete(id, "CLOSED"));
    actor.getMissionManager().refresh();
    actor.getQuestManager().refreshUnlocks();
    assertNotNull(actor.getMissionManager().sub(100020109));
    assertEquals(QuestState.Phase.DOING, actor.getQuestStates().get(BASE).getPhase());
    // Pair acceptance is an isolated persisted prerequisite; its two source mains remain actual
    // records.
    actor.getQuestStates().put(PAIR, new QuestState(PAIR));
  }

  @AfterEach
  void cleanup() {
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(previousMains);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(previousSubs);
    GameData.getMainMissionExcelMap().values().clear();
    previousExcel.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getQuestExcelMap().values().clear();
    previousQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    previousRules.forEach(GameData.getFinishWayExcelMap()::add);
  }

  static QuestState complete(int id, String phase) {
    var state = new QuestState(id);
    assertTrue(state.advance(1, 1, 1));
    if (!phase.equals("FINISHED")) assertTrue(state.reserveReward());
    if (phase.equals("CLOSED")) state.closeReward();
    return state;
  }

  void tutorial() {
    assertEquals(
        Retcode.SUCC, actor.getMissionManager().finishTalk(100020109, "Mission_100020109"));
    assertTrue(actor.getMissionManager().finished(1000201));
    assertEquals(QuestState.Phase.FINISHED, actor.getQuestStates().get(BASE).getPhase());
    assertEquals(1, actor.getQuestStates().get(BASE).getProgress());
  }

  void pair(int expected) {
    assertDoesNotThrow(() -> actor.getMissionManager().refresh());
    var state = actor.getQuestStates().get(PAIR);
    assertEquals(expected, state.getProgress());
    assertEquals(
        expected == 2 ? QuestState.Phase.FINISHED : QuestState.Phase.DOING, state.getPhase());
    assertEquals(expected == 2, state.getFinishTime() > 0);
  }

  @Test
  void missionRefreshDoesNotCreateUnrelatedQuestsWithoutAnObservedCompletedSource() {
    actor.getQuestStates().clear();
    actor.getMissionManager().refresh();
    assertTrue(actor.getQuestStates().isEmpty());
    assertEquals(
        Retcode.SUCC, actor.getMissionManager().finishTalk(100020109, "Mission_100020109"));
    assertEquals(QuestState.Phase.FINISHED, actor.getQuestStates().get(BASE).getPhase());
    assertEquals(QuestState.Phase.DOING, actor.getQuestStates().get(4045407).getPhase());
  }

  @Test
  void actualFinalTutorialTalkCompletesTheBaseQuestAndUnlocksOriginalClientQuest() {
    assertFalse(actor.getQuestStates().containsKey(4045407));
    tutorial();
    assertEquals(QuestState.Phase.DOING, actor.getQuestStates().get(4045407).getPhase());
    assertEquals(0, actor.getQuestStates().get(4045407).getProgress());
    assertEquals(Retcode.SUCC, actor.getQuestManager().finishByClient(4045407));
    assertEquals(QuestState.Phase.FINISHED, actor.getQuestStates().get(4045407).getPhase());
  }

  @Test
  void eachActualOriginalBranchMainCompletesItsOwnQuestWithoutAQueryOrRewardClaim() {
    tutorial();
    for (int main : List.of(4040204, 4040208)) {
      int quest = main == 4040204 ? 4052344 : 4052348;
      actor.floor = main == 4040204 ? 10201001 : 10202001;
      int first = main * 100 + 1, last = main * 100 + (main == 4040204 ? 3 : 2);
      assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(first, "Mission_" + first));
      assertEquals(QuestState.Phase.DOING, actor.getQuestStates().get(quest).getPhase());
      assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(last, "Mission_" + last));
      assertTrue(actor.getMissionManager().finished(main));
      assertEquals(QuestState.Phase.FINISHED, actor.getQuestStates().get(quest).getPhase());
      assertEquals(1, actor.getQuestStates().get(quest).getProgress());
      assertEquals(
          Retcode.MISSION_NOT_DOING, actor.getMissionManager().finishTalk(last, "Mission_" + last));
      assertEquals(1, actor.getQuestStates().get(quest).getProgress());
    }
  }

  @Test
  void theOriginalTwoMainQuestAdvancesFromOneToTwoOnlyOnce() {
    actor.getGameplay().getMainMissions().put(4140119, complete(4140119, "CLOSED"));
    pair(1);
    actor.getGameplay().getMainMissions().put(4140120, complete(4140120, "CLOSED"));
    pair(2);
    int saves = actor.saves;
    String before = gson.toJson(actor.getQuestStates());
    pair(2);
    assertEquals(before, gson.toJson(actor.getQuestStates()));
    assertEquals(saves, actor.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"FINISHED", "CLAIMING", "CLOSED"})
  void allThreeActualMainCompletionPhasesCount(String phase) {
    actor.getGameplay().getMainMissions().put(4140119, complete(4140119, phase));
    pair(1);
  }

  QuestState damaged(int id, String mode) {
    var row = gson.toJsonTree(complete(id, "CLOSED")).getAsJsonObject();
    switch (mode) {
      case "null-phase" -> row.add("phase", JsonNull.INSTANCE);
      case "zero-time" -> row.addProperty("finishTime", 0);
      case "negative-time" -> row.addProperty("finishTime", -1);
      case "zero-progress" -> row.addProperty("progress", 0);
      case "negative-progress" -> row.addProperty("progress", -1);
      case "too-much-progress" -> row.addProperty("progress", 2);
      case "wrong-id" -> row.addProperty("questId", id + 1000000);
    }
    return gson.fromJson(row, QuestState.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void damagedMainHistoryDoesNotCountButTheOtherHealthySourceDoes(String mode) {
    actor.getGameplay().getMainMissions().put(4140119, damaged(4140119, mode));
    actor.getGameplay().getMainMissions().put(4140120, complete(4140120, "CLOSED"));
    pair(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void damagedBaseQuestCannotUnlockAnOriginalDependentQuest(String mode) {
    actor.getQuestStates().put(BASE, damaged(BASE, mode));
    String before = gson.toJson(actor.getQuestStates().get(BASE));
    assertDoesNotThrow(() -> actor.getQuestManager().refreshUnlocks());
    assertFalse(actor.getQuestStates().containsKey(4045407));
    assertEquals(before, gson.toJson(actor.getQuestStates().get(BASE)));
  }

  @Test
  void cancelledMainDoesNotCountAsOneOfTheTwoMainGoals() {
    actor.getGameplay().getMainMissions().put(4140119, complete(4140119, "CLOSED"));
    actor.getGameplay().getMainMissions().put(4140120, complete(4140120, "CLOSED"));
    actor.getGameplay().getCancelledMissions().add(4140119);
    pair(1);
  }

  @Test
  void baseQuestWithoutItsFinishRuleCannotUnlockTheClientQuest() {
    actor.getQuestStates().put(BASE, complete(BASE, "CLOSED"));
    GameData.getFinishWayExcelMap().values().remove(GameData.getFinishWayExcelMap().get(BASE));
    actor.getQuestManager().refreshUnlocks();
    assertFalse(actor.getQuestStates().containsKey(4045407));
  }

  @Test
  void baseQuestWithoutItsDefinitionCannotUnlockTheClientQuest() {
    actor.getQuestStates().put(BASE, complete(BASE, "CLOSED"));
    GameData.getQuestExcelMap().values().remove(GameData.getQuestExcelMap().get(BASE));
    actor.getQuestManager().refreshUnlocks();
    assertFalse(actor.getQuestStates().containsKey(4045407));
  }

  @Test
  void baseQuestWithDifferentSourceGoalCannotUnlockTheClientQuest() {
    actor.getQuestStates().put(BASE, complete(BASE, "CLOSED"));
    var row = gson.toJsonTree(GameData.getFinishWayExcelMap().get(BASE)).getAsJsonObject();
    row.addProperty("Progress", 2);
    GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    actor.getQuestManager().refreshUnlocks();
    assertFalse(actor.getQuestStates().containsKey(4045407));
  }

  void doneMains() {
    actor.getGameplay().getMainMissions().put(4140119, complete(4140119, "CLOSED"));
    actor.getGameplay().getMainMissions().put(4140120, complete(4140120, "CLOSED"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "duplicate",
        "null-list",
        "null-member",
        "empty",
        "unknown",
        "negative-member",
        "zero-member",
        "extra-int1",
        "extra-int2",
        "extra-int3",
        "extra-string",
        "null-string",
        "extra-item",
        "null-item",
        "zero-goal",
        "negative-goal",
        "beyond-list",
        "scalar-type",
        "unknown-type"
      })
  void malformedMainFinishRulesStayPending(String mode) {
    doneMains();
    var row = gson.toJsonTree(GameData.getFinishWayExcelMap().get(PAIR)).getAsJsonObject();
    switch (mode) {
      case "duplicate" -> row.add("ParamIntList", JsonParser.parseString("[4140119,4140119]"));
      case "null-list" -> row.add("ParamIntList", JsonNull.INSTANCE);
      case "null-member" -> row.add("ParamIntList", JsonParser.parseString("[null,4140120]"));
      case "empty" -> row.add("ParamIntList", new JsonArray());
      case "unknown" -> row.add("ParamIntList", JsonParser.parseString("[199999991,4140120]"));
      case "negative-member" ->
          row.add("ParamIntList", JsonParser.parseString("[-4140119,4140120]"));
      case "zero-member" -> row.add("ParamIntList", JsonParser.parseString("[0,4140120]"));
      case "extra-int1" -> row.addProperty("ParamInt1", 4140119);
      case "extra-int2" -> row.addProperty("ParamInt2", 1);
      case "extra-int3" -> row.addProperty("ParamInt3", 1);
      case "extra-string" -> row.addProperty("ParamStr1", "unverified");
      case "null-string" -> row.add("ParamStr1", JsonNull.INSTANCE);
      case "extra-item" ->
          row.add("ParamItemList", JsonParser.parseString("[{\"ItemID\":235,\"ItemNum\":1}]"));
      case "null-item" -> row.add("ParamItemList", JsonNull.INSTANCE);
      case "zero-goal" -> row.addProperty("Progress", 0);
      case "negative-goal" -> row.addProperty("Progress", -1);
      case "beyond-list" -> row.addProperty("Progress", 3);
      case "scalar-type" -> row.addProperty("ParamType", "Equal");
      case "unknown-type" -> row.addProperty("ParamType", "unknown");
    }
    GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    pair(0);
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 5})
  void ordinaryMainHistoryDoesNotCompleteACycleOrDailyQuest(int type) {
    doneMains();
    var row = gson.toJsonTree(GameData.getQuestExcelMap().get(PAIR)).getAsJsonObject();
    row.addProperty("QuestType", type);
    GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
    pair(0);
  }

  @Test
  void missingReferencedMainDefinitionKeepsTheWholeQuestPending() {
    doneMains();
    GameData.getMainMissionExcelMap()
        .values()
        .remove(GameData.getMainMissionExcelMap().get(4140119));
    pair(0);
  }

  @Test
  void concurrentMissionRefreshesFinishTheOriginalQuestOnce() throws Exception {
    doneMains();
    var workers = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var calls = new ArrayList<java.util.concurrent.Callable<Void>>();
      for (int i = 0; i < 24; i++)
        calls.add(
            () -> {
              actor.getMissionManager().refresh();
              return null;
            });
      for (var result : workers.invokeAll(calls)) result.get();
      pair(2);
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void actualPlayerMorphiaReloadKeepsPartialThenFinishedQuestProgress() throws Exception {
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "quest_main_progress");
      store.getMapper().map(Player.class, QuestState.class);
      actor.getGameplay().getMainMissions().put(4140119, complete(4140119, "CLOSED"));
      pair(1);
      reload(store);
      pair(1);
      actor.getGameplay().getMainMissions().put(4140120, complete(4140120, "CLOSED"));
      pair(2);
      reload(store);
      pair(2);
    } finally {
      server.shutdownNow();
    }
  }

  void reload(dev.morphia.Datastore store) throws Exception {
    var base = new Player();
    var uid = Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(base, 123456794);
    var gameplay = Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(base, actor.getGameplay());
    var quests = Player.class.getDeclaredField("questStates");
    quests.setAccessible(true);
    quests.set(base, actor.getQuestStates());
    store.save(base);
    var restored =
        store
            .find(Player.class)
            .filter(dev.morphia.query.filters.Filters.eq("_id", 123456794))
            .first();
    assertNotNull(restored);
    actor = new Actor();
    gameplay.set(actor, restored.getGameplay());
    quests.set(actor, restored.getQuestStates());
  }
}
