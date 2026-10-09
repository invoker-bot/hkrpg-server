package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original list conditions; parent acceptance and completed prerequisite records are fixtures. */
class FinishMainMissionListTest {
  static final int MAIN = 2000106, TARGET = 200010601, STAGES = 4040290;
  static final List<Integer> SOURCES = List.of(2000310, 2000311, 2000312);
  final Gson gson = new Gson();
  final Map<Integer, MissionInfo> previousMains = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> previousSubs = new HashMap<>();
  final Map<Integer, MainMissionExcel> previousExcel = new HashMap<>();
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
    for (var row : GameData.getMainMissionExcelMap().values()) previousExcel.put(row.getId(), row);
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    GameData.getMainMissionExcelMap().values().clear();
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/finish-main-list-4.6.json"),
            StandardCharsets.UTF_8)) {
      var records = JsonParser.parseReader(reader).getAsJsonArray();
      assertEquals(22, records.size());
      for (var entry : records) {
        var wrapper = entry.getAsJsonObject();
        assertEquals(
            "724b139d8c9c32d12552eb95745a4fee72bfe48b", wrapper.get("sourceCommit").getAsString());
        var row = wrapper.getAsJsonObject("row");
        if (wrapper.get("kind").getAsString().equals("main"))
          GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
        else if (row.has("ID")) subs.put(row.get("ID").getAsInt(), row);
        else definitions.put(row.get("MainMissionID").getAsInt(), row);
      }
    }
    load(MAIN, row -> {});
    load(STAGES, row -> {});
    actor = new Actor();
    actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    actor.getGameplay().getMainMissions().put(STAGES, new QuestState(STAGES));
    for (int id : List.of(200010614, 200010615))
      actor.getGameplay().getSubMissions().put(id, completed(id, 1, "FINISHED"));
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
    previousExcel.values().forEach(GameData.getMainMissionExcelMap()::add);
  }

  void load(int main, Consumer<JsonObject> edit) {
    var full = definitions.get(main).deepCopy();
    var children = new JsonArray();
    subs.values().stream()
        .filter(row -> row.get("MainMissionID").getAsInt() == main)
        .sorted(Comparator.comparingInt(row -> row.get("ID").getAsInt()))
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

  static QuestState completed(int id, int goal, String phase) {
    var state = new QuestState(id);
    assertTrue(state.advance(goal, goal, 1));
    if (!phase.equals("FINISHED")) assertTrue(state.reserveReward());
    if (phase.equals("CLOSED")) state.closeReward();
    return state;
  }

  void finish(int id) {
    actor.getGameplay().getMainMissions().put(id, completed(id, 1, "CLOSED"));
  }

  void allDone() {
    SOURCES.forEach(this::finish);
  }

  void progress(int expected) {
    assertDoesNotThrow(() -> actor.getMissionManager().refresh());
    var state = actor.getMissionManager().sub(TARGET);
    assertEquals(expected, state.getProgress());
    assertEquals(
        expected == 3 ? QuestState.Phase.FINISHED : QuestState.Phase.DOING, state.getPhase());
    assertEquals(expected == 3, state.getFinishTime() > 0);
    assertFalse(
        actor.getMissionManager().finished(MAIN),
        "unloaded original final children remain pending");
  }

  @Test
  void threeOriginalDependenciesAdvanceOneAtATimeAndFinishOnlyOnce() {
    for (int i = 0; i < SOURCES.size(); i++) {
      finish(SOURCES.get(i));
      progress(i + 1);
    }
    var before = gson.toJson(actor.getGameplay());
    int saves = actor.saves;
    progress(3);
    assertEquals(before, gson.toJson(actor.getGameplay()));
    assertEquals(saves, actor.saves);
    assertEquals(0, actor.getGameplay().getStoryActions().get(TARGET).getNext());
    assertFalse(actor.getGameplay().getStoryActions().get(TARGET).isPending());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6})
  void sixOriginalThresholdsUseActualCountInsteadOfRequiringTheWholeList(int count) {
    for (int i = 0; i < count; i++) finish(4040203 + i);
    actor.getMissionManager().refresh();
    for (int goal = 1; goal <= 6; goal++) {
      var state = actor.getMissionManager().sub(404029000 + goal);
      assertEquals(Math.min(count, goal), state.getProgress());
      assertEquals(
          count >= goal ? QuestState.Phase.FINISHED : QuestState.Phase.DOING, state.getPhase());
      assertEquals(count >= goal, state.getFinishTime() > 0);
    }
    assertFalse(actor.getMissionManager().finished(STAGES));
  }

  @ParameterizedTest
  @ValueSource(strings = {"FINISHED", "CLAIMING", "CLOSED"})
  void legitimateMainCompletionCountsDuringAllThreeRewardPhases(String phase) {
    actor.getGameplay().getMainMissions().put(SOURCES.get(0), completed(SOURCES.get(0), 1, phase));
    progress(1);
  }

  QuestState damage(int id, int goal, String mode) {
    var row = gson.toJsonTree(completed(id, goal, "CLOSED")).getAsJsonObject();
    switch (mode) {
      case "null-phase" -> row.add("phase", JsonNull.INSTANCE);
      case "zero-time" -> row.addProperty("finishTime", 0);
      case "negative-time" -> row.addProperty("finishTime", -1);
      case "zero-progress" -> row.addProperty("progress", 0);
      case "negative-progress" -> row.addProperty("progress", -1);
      case "too-much-progress" -> row.addProperty("progress", goal + 1);
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
  void anInvalidMainDoesNotCountButTwoValidCompletionsStillDo(String mode) {
    allDone();
    actor.getGameplay().getMainMissions().put(SOURCES.get(0), damage(SOURCES.get(0), 1, mode));
    progress(2);
  }

  @Test
  void cancelledMainDoesNotCountAlongsideValidCompletedMains() {
    allDone();
    actor.getGameplay().getCancelledMissions().add(SOURCES.get(0));
    progress(2);
  }

  @Test
  void missingCanonicalMainDefinitionLeavesTheWholeConditionPending() {
    allDone();
    GameData.getMainMissionExcelMap()
        .values()
        .remove(GameData.getMainMissionExcelMap().get(SOURCES.get(0)));
    progress(0);
  }

  @Test
  void wrongCanonicalMainIdLeavesTheWholeConditionPending() {
    allDone();
    var definition = GameData.getMainMissionExcelMap().get(SOURCES.get(0));
    try {
      var id = MainMissionExcel.class.getDeclaredField("MainMissionID");
      id.setAccessible(true);
      id.setInt(definition, SOURCES.get(0) + 1000000);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
    progress(0);
  }

  @Test
  void aDetachedSubDefinitionCannotTurnTheCanonicalTaskIntoACompletedTask() {
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
        "self-main",
        "extra-int1",
        "extra-int2",
        "extra-int3",
        "extra-string",
        "null-string",
        "extra-item",
        "null-item",
        "zero-goal",
        "beyond-list",
        "scalar-type",
        "unknown-type"
      })
  void unverifiedListShapesCannotBorrowTheOldScalarCompletionShortcut(String mode) {
    allDone();
    edit(
        row -> {
          switch (mode) {
            case "duplicate" ->
                row.add("ParamIntList", JsonParser.parseString("[2000310,2000310,2000311]"));
            case "null-list" -> row.add("ParamIntList", JsonNull.INSTANCE);
            case "null-member" ->
                row.add("ParamIntList", JsonParser.parseString("[2000310,null,2000312]"));
            case "empty" -> row.add("ParamIntList", new JsonArray());
            case "negative-member" ->
                row.add("ParamIntList", JsonParser.parseString("[-2000310,2000311,2000312]"));
            case "zero-member" ->
                row.add("ParamIntList", JsonParser.parseString("[0,2000311,2000312]"));
            case "unknown" ->
                row.add("ParamIntList", JsonParser.parseString("[199999990,2000311,2000312]"));
            case "self-main" ->
                row.add("ParamIntList", JsonParser.parseString("[2000106,2000311,2000312]"));
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
            case "beyond-list" -> row.addProperty("Progress", 4);
            case "scalar-type", "unknown-type" -> {
              row.addProperty("ParamType", mode.equals("scalar-type") ? "Equal" : "unknown");
              row.addProperty("ParamInt1", SOURCES.get(0));
            }
          }
        });
    progress(0);
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
  void anInvalidCompletedTargetCannotBeRewrittenByRefresh(String mode) {
    allDone();
    actor.getGameplay().getSubMissions().put(TARGET, damage(TARGET, 3, mode));
    String before = gson.toJson(actor.getMissionManager().sub(TARGET));
    assertDoesNotThrow(() -> actor.getMissionManager().refresh());
    assertEquals(before, gson.toJson(actor.getMissionManager().sub(TARGET)));
  }

  @Test
  void concurrentRefreshesProduceOneCompletionAndKeepExactSourceCount() throws Exception {
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
      progress(3);
      assertFalse(actor.getGameplay().getStoryActions().get(TARGET).isPending());
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void actualPlayerMorphiaReloadRetainsPartialCountThenCompletedHistory() throws Exception {
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "finish_main_list");
      store.getMapper().map(Player.class, QuestState.class);
      finish(SOURCES.get(0));
      progress(1);
      reload(store);
      progress(1);
      finish(SOURCES.get(1));
      finish(SOURCES.get(2));
      progress(3);
      reload(store);
      int saves = actor.saves;
      progress(3);
      assertEquals(saves, actor.saves);
    } finally {
      server.shutdownNow();
    }
  }

  void reload(dev.morphia.Datastore store) throws Exception {
    var base = new Player();
    var uid = Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(base, 123456792);
    var gameplay = Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(base, actor.getGameplay());
    store.save(base);
    var restored =
        store
            .find(Player.class)
            .filter(dev.morphia.query.filters.Filters.eq("_id", 123456792))
            .first();
    assertNotNull(restored);
    actor = new Actor();
    gameplay.set(actor, restored.getGameplay());
  }
}
