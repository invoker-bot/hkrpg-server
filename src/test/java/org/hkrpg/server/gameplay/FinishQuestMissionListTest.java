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
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real quest goals differ from the mission's threshold; accepting parents is an isolated fixture.
 */
class FinishQuestMissionListTest {
  static final int MAIN = 4010120,
      TARGET = 401012001,
      CLIENT_MAIN = 4010143,
      CLIENT_TARGET = 401014301;
  static final List<Integer> SOURCES = List.of(2200647, 2200648, 2200649, 2200650);
  final Gson gson = new Gson();
  final Map<Integer, MissionInfo> previousMains = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> previousSubs = new HashMap<>();
  final List<MainMissionExcel> previousExcel = new ArrayList<>();
  final List<QuestExcel> previousQuests = new ArrayList<>();
  final List<FinishWayExcel> previousRules = new ArrayList<>();
  final Map<Integer, JsonObject> definitions = new HashMap<>(), subs = new HashMap<>();
  Actor actor;

  static class Actor extends Player {
    int saves;

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
            getClass().getResourceAsStream("/fixtures/finish-quest-list-4.6.json"),
            StandardCharsets.UTF_8)) {
      var records = JsonParser.parseReader(reader).getAsJsonArray();
      assertEquals(29, records.size());
      for (var raw : records) {
        var entry = raw.getAsJsonObject();
        assertEquals(
            "724b139d8c9c32d12552eb95745a4fee72bfe48b", entry.get("sourceCommit").getAsString());
        var row = entry.getAsJsonObject("row");
        switch (entry.get("kind").getAsString()) {
          case "main" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "quest" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "rule" ->
              GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
          case "definition" -> definitions.put(row.get("MainMissionID").getAsInt(), row);
          case "sub" -> subs.put(row.get("ID").getAsInt(), row);
          default -> fail("Unexpected fixture record kind");
        }
      }
    }
    actor = new Actor();
    for (int main : List.of(4010100, MAIN, CLIENT_MAIN)) {
      load(main, row -> {});
      actor.getGameplay().getMainMissions().put(main, new QuestState(main));
    }
    actor.getMissionManager().refresh();
    assertNotNull(actor.getMissionManager().sub(TARGET));
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

  void load(int main, Consumer<JsonObject> edit) {
    var full = definitions.get(main).deepCopy();
    var children = new JsonArray();
    subs.values().stream()
        .filter(row -> row.get("MainMissionID").getAsInt() == main)
        .forEach(row -> children.add(row.deepCopy()));
    full.add("SubMissionList", children);
    edit.accept(full);
    var definition = gson.fromJson(full, MissionInfo.class);
    GameData.getMissionInfos().put(main, definition);
    for (var child : definition.getSubMissionList())
      GameplayDefinitions.subs.put(child.getId(), child);
  }

  void edit(Consumer<JsonObject> change) {
    load(
        MAIN,
        full -> {
          for (var row : full.getAsJsonArray("SubMissionList"))
            if (row.getAsJsonObject().get("ID").getAsInt() == TARGET)
              change.accept(row.getAsJsonObject());
        });
  }

  int goal(int id) {
    return GameData.getFinishWayExcelMap()
        .get(GameData.getQuestExcelMap().get(id).getFinishWayID())
        .getProgress();
  }

  QuestState completed(int id, String phase) {
    var state = new QuestState(id);
    assertTrue(state.advance(goal(id), goal(id), 1));
    if (!phase.equals("FINISHED")) assertTrue(state.reserveReward());
    if (phase.equals("CLOSED")) state.closeReward();
    return state;
  }

  void finish(int id) {
    actor.getQuestStates().put(id, completed(id, "CLOSED"));
  }

  void allDone() {
    SOURCES.forEach(this::finish);
  }

  void progress(int expected) {
    assertDoesNotThrow(() -> actor.getMissionManager().refresh());
    var state = actor.getMissionManager().sub(TARGET);
    assertEquals(expected, state.getProgress());
    assertEquals(
        expected == 4 ? QuestState.Phase.FINISHED : QuestState.Phase.DOING, state.getPhase());
    assertEquals(expected == 4, state.getFinishTime() > 0);
    assertFalse(
        actor.getMissionManager().finished(MAIN), "unloaded original final children stay pending");
  }

  @Test
  void fourActualQuestCompletionsAdvanceTheOriginalFourQuestMissionOneAtATime() {
    for (int i = 0; i < SOURCES.size(); i++) {
      finish(SOURCES.get(i));
      progress(i + 1);
    }
    String before = gson.toJson(actor.getGameplay());
    int saves = actor.saves;
    progress(4);
    assertEquals(before, gson.toJson(actor.getGameplay()));
    assertEquals(saves, actor.saves);
    assertFalse(actor.getGameplay().getStoryActions().get(TARGET).isPending());
  }

  @ParameterizedTest
  @ValueSource(ints = {2200801, 2200802, 2200803, 2200804})
  void oneOfFourOriginalQuestsCountsOnlyAtItsOwnThreeOrFourStepGoal(int id) {
    var state = new QuestState(id);
    state.advance(goal(id) - 1, goal(id), 1);
    actor.getQuestStates().put(id, state);
    actor.getMissionManager().refresh();
    assertEquals(0, actor.getMissionManager().sub(401010004).getProgress());
    state.advance(1, goal(id), 1);
    actor.getMissionManager().refresh();
    assertEquals(1, actor.getMissionManager().sub(401010004).getProgress());
    assertEquals(QuestState.Phase.FINISHED, actor.getMissionManager().sub(401010004).getPhase());
  }

  @ParameterizedTest
  @ValueSource(strings = {"FINISHED", "CLAIMING", "CLOSED"})
  void actualQuestCompletionCountsDuringAllThreeRewardPhases(String phase) {
    actor.getQuestStates().put(SOURCES.get(0), completed(SOURCES.get(0), phase));
    progress(1);
  }

  QuestState damaged(int id, String mode) {
    var row = gson.toJsonTree(completed(id, "CLOSED")).getAsJsonObject();
    switch (mode) {
      case "null-phase" -> row.add("phase", JsonNull.INSTANCE);
      case "zero-time" -> row.addProperty("finishTime", 0);
      case "negative-time" -> row.addProperty("finishTime", -1);
      case "zero-progress" -> row.addProperty("progress", 0);
      case "negative-progress" -> row.addProperty("progress", -1);
      case "too-much-progress" -> row.addProperty("progress", goal(id) + 1);
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
  void invalidQuestHistoryDoesNotCountButTheOtherThreeCompletionsDo(String mode) {
    allDone();
    actor.getQuestStates().put(SOURCES.get(0), damaged(SOURCES.get(0), mode));
    progress(3);
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 5})
  void ordinarySavedHistoryCannotImpersonateAnotherCycleOrDailyDomain(int kind) {
    allDone();
    var raw = gson.toJsonTree(GameData.getQuestExcelMap().get(SOURCES.get(0))).getAsJsonObject();
    raw.addProperty("QuestType", kind);
    GameData.getQuestExcelMap().add(gson.fromJson(raw, QuestExcel.class));
    progress(0);
  }

  @Test
  void missingQuestDefinitionKeepsTheWholeConditionPending() {
    allDone();
    GameData.getQuestExcelMap().values().remove(GameData.getQuestExcelMap().get(SOURCES.get(0)));
    progress(0);
  }

  @Test
  void missingFinishRuleKeepsTheWholeConditionPending() {
    allDone();
    var rule =
        GameData.getFinishWayExcelMap()
            .get(GameData.getQuestExcelMap().get(SOURCES.get(0)).getFinishWayID());
    GameData.getFinishWayExcelMap().values().remove(rule);
    progress(0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"QuestID", "ID"})
  void aDefinitionStoredUnderTheWrongIdentityCannotAuthorizeCounts(String field) throws Exception {
    allDone();
    Object object =
        field.equals("QuestID")
            ? GameData.getQuestExcelMap().get(SOURCES.get(0))
            : GameData.getFinishWayExcelMap()
                .get(GameData.getQuestExcelMap().get(SOURCES.get(0)).getFinishWayID());
    var actual = object.getClass().getDeclaredField(field);
    actual.setAccessible(true);
    actual.setInt(object, 199999991);
    progress(0);
  }

  @Test
  void aDetachedMissionDefinitionCannotReadTheQuestCompletions() {
    allDone();
    GameplayDefinitions.subs.put(
        TARGET, gson.fromJson(subs.get(TARGET), MissionInfo.SubMissionInfo.class));
    progress(0);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "duplicate",
        "null-list",
        "null-member",
        "empty",
        "negative-member",
        "zero-member",
        "unknown",
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
  void malformedQuestListsCannotBorrowTheOldScalarCompletionShortcut(String mode) {
    allDone();
    edit(
        row -> {
          switch (mode) {
            case "duplicate" ->
                row.add(
                    "ParamIntList", JsonParser.parseString("[2200647,2200647,2200649,2200650]"));
            case "null-list" -> row.add("ParamIntList", JsonNull.INSTANCE);
            case "null-member" ->
                row.add("ParamIntList", JsonParser.parseString("[2200647,null,2200649,2200650]"));
            case "empty" -> row.add("ParamIntList", new JsonArray());
            case "negative-member" ->
                row.add(
                    "ParamIntList", JsonParser.parseString("[-2200647,2200648,2200649,2200650]"));
            case "zero-member" ->
                row.add("ParamIntList", JsonParser.parseString("[0,2200648,2200649,2200650]"));
            case "unknown" ->
                row.add(
                    "ParamIntList", JsonParser.parseString("[199999990,2200648,2200649,2200650]"));
            case "extra-int1" -> row.addProperty("ParamInt1", SOURCES.get(0));
            case "extra-int2" -> row.addProperty("ParamInt2", 1);
            case "extra-int3" -> row.addProperty("ParamInt3", 1);
            case "extra-string" -> row.addProperty("ParamStr1", "unverified");
            case "null-string" -> row.add("ParamStr1", JsonNull.INSTANCE);
            case "extra-item" ->
                row.add(
                    "ParamItemList", JsonParser.parseString("[{\"ItemID\":235,\"ItemNum\":1}]"));
            case "null-item" -> row.add("ParamItemList", JsonNull.INSTANCE);
            case "zero-goal" -> row.addProperty("Progress", 0);
            case "negative-goal" -> row.addProperty("Progress", -1);
            case "beyond-list" -> row.addProperty("Progress", 5);
            case "scalar-type", "unknown-type" -> {
              row.addProperty("ParamType", mode.equals("scalar-type") ? "Equal" : "unknown");
              row.addProperty("ParamInt1", SOURCES.get(0));
            }
          }
        });
    progress(0);
  }

  @Test
  void aQuestRecordAliasedToAnotherMapKeyDoesNotCountAsTheReferencedQuest() {
    allDone();
    actor.getQuestStates().put(SOURCES.get(0), completed(SOURCES.get(1), "CLOSED"));
    progress(3);
  }

  @Test
  void absentQuestRecordsDoNotCreateCompletedObjectives() {
    progress(0);
  }

  @Test
  void aStillDoingQuestDoesNotCountAtItsIncompleteGoal() {
    actor.getQuestStates().put(SOURCES.get(0), new QuestState(SOURCES.get(0)));
    progress(0);
  }

  @Test
  void completingTheOriginalClientReportQuestImmediatelyAdvancesTheDependentMission() {
    // The original common unlock quest requires tutorial completion, outside this fixture's scope.
    actor.getQuestStates().put(4000001, completed(4000001, "CLOSED"));
    actor.getQuestManager().refreshUnlocks();
    assertEquals(QuestState.Phase.DOING, actor.getQuestStates().get(4045407).getPhase());
    assertEquals(Retcode.SUCC, actor.getQuestManager().finishByClient(4045407));
    assertEquals(
        QuestState.Phase.FINISHED, actor.getMissionManager().sub(CLIENT_TARGET).getPhase());
    assertEquals(1, actor.getMissionManager().sub(CLIENT_TARGET).getProgress());
    assertEquals(Retcode.QUEST_STATUS_ERROR, actor.getQuestManager().finishByClient(4045407));
    assertEquals(1, actor.getMissionManager().sub(CLIENT_TARGET).getProgress());
  }

  @Test
  void concurrentRefreshesCompleteTheFourQuestListOnlyOnce() throws Exception {
    allDone();
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
      progress(4);
      assertFalse(actor.getGameplay().getStoryActions().get(TARGET).isPending());
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void actualPlayerMorphiaReloadKeepsPartialQuestCountThenCompletedHistory() throws Exception {
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "finish_quest_list");
      store.getMapper().map(Player.class, QuestState.class);
      finish(SOURCES.get(0));
      progress(1);
      reload(store);
      progress(1);
      for (int id : SOURCES) finish(id);
      progress(4);
      reload(store);
      int saves = actor.saves;
      progress(4);
      assertEquals(saves, actor.saves);
      assertEquals(4, actor.getQuestStates().size());
    } finally {
      server.shutdownNow();
    }
  }

  void reload(dev.morphia.Datastore store) throws Exception {
    var base = new Player();
    var uid = Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(base, 123456793);
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
            .filter(dev.morphia.query.filters.Filters.eq("_id", 123456793))
            .first();
    assertNotNull(restored);
    actor = new Actor();
    gameplay.set(actor, restored.getGameplay());
    quests.set(actor, restored.getQuestStates());
  }
}
