package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MainMissionExcel;
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
 * Original monitor 105450235 -> 105450232; acceptance, current place and persistence are fixtures.
 */
class MissionRelationsTest {
  static final int MAIN = 1054502, MONITOR = 105450235, WATCHED = 105450232;
  final Gson gson = new Gson();
  final Map<Integer, MissionInfo> previousMains = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> previousSubs = new HashMap<>();
  final Map<Integer, MainMissionExcel> previousExcel = new HashMap<>();
  final Map<Integer, JsonObject> sourceMains = new HashMap<>(), sourceSubs = new HashMap<>();
  Actor actor;

  static class Actor extends Player {
    int saves, plane = 10551, floor = 10551001;

    @Override
    public void save() {
      saves++;
    }

    @Override
    public int getPlaneId() {
      return plane;
    }

    @Override
    public int getFloorId() {
      return floor;
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
            getClass().getResourceAsStream("/fixtures/submission-monitor-4.6.json"),
            StandardCharsets.UTF_8)) {
      for (var raw : JsonParser.parseReader(reader).getAsJsonArray()) {
        var entry = raw.getAsJsonObject();
        var row = entry.getAsJsonObject("row");
        if (entry.get("kind").getAsString().equals("main"))
          sourceMains.put(row.get("MainMissionID").getAsInt(), row);
        else sourceSubs.put(row.get("ID").getAsInt(), row);
      }
    }
    load(MAIN, j -> {});
    actor = new Actor();
    actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    finish(105450227, 1);
    actor.getMissionManager().refresh();
    assertNotNull(actor.getMissionManager().sub(MONITOR));
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

  MissionInfo load(int main, Consumer<JsonObject> edit) {
    var full = sourceMains.get(main).deepCopy();
    var subs = new JsonArray();
    sourceSubs.values().stream()
        .filter(r -> r.get("MainMissionID").getAsInt() == main)
        .sorted(Comparator.comparingInt(r -> r.get("ID").getAsInt()))
        .forEach(r -> subs.add(r.deepCopy()));
    full.add("SubMissionList", subs);
    edit.accept(full);
    var config = gson.fromJson(full, MissionInfo.class);
    GameData.getMissionInfos().put(main, config);
    for (var sub : config.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
    return config;
  }

  void finish(int id, int progress) {
    var s = new QuestState(id);
    assertTrue(s.advance(progress, progress, 1));
    actor.getGameplay().getSubMissions().put(id, s);
  }

  void watchedDone() {
    finish(WATCHED, 2);
  }

  void staysPending() {
    actor.getMissionManager().refresh();
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(MONITOR).getPhase());
    assertEquals(0, actor.getMissionManager().sub(MONITOR).getProgress());
  }

  void completed() {
    actor.getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, actor.getMissionManager().sub(MONITOR).getPhase());
    assertEquals(1, actor.getMissionManager().sub(MONITOR).getProgress());
    assertEquals(1, actor.getGameplay().getStoryActions().get(MONITOR).getNext());
    assertFalse(actor.getGameplay().getStoryActions().get(MONITOR).isPending());
  }

  @Test
  void originalRelationSurvivesDefinitionParsing() {
    var saved =
        JsonParser.parseString(gson.toJson(GameData.getMissionInfos().get(MAIN))).getAsJsonObject();
    assertEquals(
        sourceMains.get(MAIN).get("SubMissionRelationList"), saved.get("SubMissionRelationList"));
  }

  @Test
  void originalCompletedWatchedAggregateFinishesItsAcceptedMonitorOnce() {
    watchedDone();
    completed();
    String before = gson.toJson(actor.getGameplay());
    int saves = actor.saves;
    completed();
    assertEquals(before, gson.toJson(actor.getGameplay()));
    assertEquals(saves, actor.saves);
  }

  @Test
  void originalTwoDialoguesFinishTheAggregateThenMonitorWithoutAClientCompletionFlag() {
    assertEquals(
        Retcode.SUCC, actor.getMissionManager().finishTalk(105450230, "Mission_105450230"));
    assertEquals(
        Retcode.SUCC, actor.getMissionManager().finishTalk(105450231, "Mission_105450231"));
    assertEquals(1, actor.getMissionManager().sub(WATCHED).getProgress());
    staysPending();
    assertEquals(
        Retcode.SUCC, actor.getMissionManager().finishTalk(105450234, "Mission_105450234"));
    assertEquals(2, actor.getMissionManager().sub(WATCHED).getProgress());
    completed();
  }

  @Test
  void closedWatchedObjectiveStillCarriesCompletedProgress() {
    watchedDone();
    var s = actor.getMissionManager().sub(WATCHED);
    assertTrue(s.reserveReward());
    s.closeReward();
    completed();
  }

  @Test
  void restoredAcceptedHistoryCompletesThroughTheOriginalDefinition() throws Exception {
    watchedDone();
    var state = gson.fromJson(gson.toJson(actor.getGameplay()), GameplayState.class);
    var replacement = new Actor();
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(replacement, state);
    actor = replacement;
    completed();
    var restored = gson.fromJson(gson.toJson(actor.getGameplay()), GameplayState.class);
    assertEquals(1, restored.getStoryActions().get(MONITOR).getNext());
    assertFalse(restored.getStoryActions().get(MONITOR).isPending());
    assertEquals(1, restored.getSubMissions().get(MONITOR).getProgress());
  }

  @Test
  void aStillDoingWatchedAggregateCannotFinishTheMonitor() {
    var state = actor.getMissionManager().sub(WATCHED);
    assertTrue(state.advance(1, 2, 1));
    staysPending();
  }

  @Test
  void cancelledWatchedAggregateCannotFinishTheMonitor() {
    watchedDone();
    actor.getGameplay().getCancelledSubs().add(WATCHED);
    staysPending();
  }

  @Test
  void cancelledParentCannotFinishTheMonitor() {
    watchedDone();
    actor.getGameplay().getCancelledMissions().add(MAIN);
    staysPending();
  }

  @Test
  void wrongCurrentFloorCannotFinishTheMonitor() {
    watchedDone();
    actor.floor++;
    staysPending();
  }

  @Test
  void wrongCurrentPlaneCannotFinishTheMonitor() {
    watchedDone();
    actor.plane++;
    staysPending();
  }

  @Test
  void completedFlagWithoutItsOriginalTwoStepsCannotFinishTheMonitor() {
    finish(WATCHED, 1);
    staysPending();
  }

  @Test
  void aSavedObjectWithWrongIdentityCannotFinishTheMonitor() {
    watchedDone();
    actor.getGameplay().getSubMissions().put(WATCHED, actor.getMissionManager().sub(105450227));
    staysPending();
  }

  @Test
  void concurrentRefreshExecutesTheOriginalFinishActionOnlyOnce() throws Exception {
    watchedDone();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var calls = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 24; i++)
        calls.add(pool.submit(() -> actor.getMissionManager().refresh()));
      for (var future : calls) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    completed();
    assertEquals(1, actor.getGameplay().getStoryActions().get(MONITOR).getNext());
  }

  @Test
  void morphiaRoundTripKeepsTheWatchedCompletionAndFinishedMonitor() throws Exception {
    watchedDone();
    completed();
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://localhost:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "mission-relation-persistence");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var original = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(original, 123456799);
      var savedGameplay = Player.class.getDeclaredField("gameplay");
      savedGameplay.setAccessible(true);
      savedGameplay.set(original, actor.getGameplay());
      store.save(original);
      var restored = store.find(Player.class).first().getGameplay();
      assertNotNull(restored);
      assertEquals(2, restored.getSubMissions().get(WATCHED).getProgress());
      assertEquals(1, restored.getSubMissions().get(MONITOR).getProgress());
      assertEquals(QuestState.Phase.FINISHED, restored.getSubMissions().get(MONITOR).getPhase());
      assertEquals(1, restored.getStoryActions().get(MONITOR).getNext());
      assertFalse(restored.getStoryActions().get(MONITOR).isPending());
      var replacement = new Actor();
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(replacement, restored);
      actor = replacement;
      String before = gson.toJson(actor.getGameplay());
      int saves = actor.saves;
      completed();
      assertEquals(before, gson.toJson(actor.getGameplay()));
      assertEquals(saves, actor.saves);
    } finally {
      server.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"nullPhase", "noTime", "negativeProgress", "beyondGoal"})
  void damagedCompletedRecordCannotFinishTheMonitor(String mode) {
    watchedDone();
    var value = gson.toJsonTree(actor.getMissionManager().sub(WATCHED)).getAsJsonObject();
    switch (mode) {
      case "nullPhase" -> value.add("phase", JsonNull.INSTANCE);
      case "noTime" -> value.addProperty("finishTime", 0);
      case "negativeProgress" -> value.addProperty("progress", -1);
      case "beyondGoal" -> value.addProperty("progress", 3);
    }
    actor.getGameplay().getSubMissions().put(WATCHED, gson.fromJson(value, QuestState.class));
    staysPending();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "null",
        "empty",
        "duplicate",
        "unknown",
        "self",
        "multiple",
        "cross-main"
      })
  void ambiguousOrUnsupportedRelationCannotInventCompletion(String mode) {
    watchedDone();
    load(
        MAIN,
        full -> {
          var list = full.getAsJsonArray("SubMissionRelationList");
          switch (mode) {
            case "missing" -> full.remove("SubMissionRelationList");
            case "null" -> full.add("SubMissionRelationList", JsonNull.INSTANCE);
            case "empty" -> full.add("SubMissionRelationList", new JsonArray());
            case "duplicate" -> list.add(list.get(0).deepCopy());
            default -> {
              var ids = list.get(0).getAsJsonObject().getAsJsonArray("MonitoredSubMissionIDList");
              ids.set(
                  0,
                  new JsonPrimitive(
                      mode.equals("self")
                          ? MONITOR
                          : mode.equals("cross-main") ? 804010119 : 199999901));
              if (mode.equals("multiple")) ids.add(WATCHED);
            }
          }
        });
    if (mode.equals("cross-main")) {
      load(8040101, j -> {});
      finish(804010119, 1);
    }
    staysPending();
  }

  @ParameterizedTest
  @ValueSource(ints = {105450212, 105452804, 804010119, 804010120, 804010121})
  void otherOriginalMultiFinishTargetsWithoutRelationsRemainPending(int id) {
    int main = sourceSubs.get(id).get("MainMissionID").getAsInt();
    load(main, j -> {});
    actor.getGameplay().getMainMissions().put(main, new QuestState(main));
    actor.getGameplay().getSubMissions().put(id, new QuestState(id));
    actor.getMissionManager().refresh();
    assertEquals(QuestState.Phase.DOING, actor.getMissionManager().sub(id).getPhase());
  }
}
