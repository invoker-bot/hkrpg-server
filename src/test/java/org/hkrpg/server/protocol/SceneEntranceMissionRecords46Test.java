package org.hkrpg.server.protocol;

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
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native entrance preflight; completion histories and the physical transition are fixtures. */
class SceneEntranceMissionRecords46Test {
  static final int ENTRY = 1020101, MAIN = 1020201, SUB = 102020110, PLANE = 10201;
  final Gson gson = new Gson();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  MapEntranceExcel oldEntry;
  MazePlaneExcel oldPlane;
  MainMissionExcel oldMain;
  MissionInfo oldDefinition;
  Actor actor;
  Gameplay46HandlerTest.Session session;
  MissionInfo.SubMissionInfo sourceSub;

  static class Actor extends Player {
    int transitions, saves;

    @Override
    public boolean enterScene(int entry, int teleport, boolean sendPacket) {
      assertEquals(ENTRY, entry);
      assertEquals(0, teleport);
      transitions++;
      return true;
    }

    @Override
    public void save() {
      saves++;
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    oldEntry = GameData.getMapEntranceExcelMap().get(ENTRY);
    oldPlane = GameData.getMazePlaneExcelMap().get(PLANE);
    oldMain = GameData.getMainMissionExcelMap().get(MAIN);
    oldDefinition = GameData.getMissionInfos().get(MAIN);
    oldSubs.putAll(GameplayDefinitions.subs);
    GameplayDefinitions.subs.clear();
    JsonObject definition = null;
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/scene-entrance-missions-4.6.json"),
            StandardCharsets.UTF_8)) {
      var entries = JsonParser.parseReader(reader).getAsJsonArray();
      assertEquals(5, entries.size());
      for (var raw : entries) {
        var entry = raw.getAsJsonObject();
        var row = entry.getAsJsonObject("row");
        switch (entry.get("kind").getAsString()) {
          case "entrance" ->
              GameData.getMapEntranceExcelMap().add(gson.fromJson(row, MapEntranceExcel.class));
          case "plane" ->
              GameData.getMazePlaneExcelMap()
                  .add(
                      emu.lunarcore.data.ResourceLoader.getGson()
                          .fromJson(row, MazePlaneExcel.class));
          case "main" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "definition" -> definition = row;
          case "sub" -> sourceSub = gson.fromJson(row, MissionInfo.SubMissionInfo.class);
          default -> fail("Unknown fixture kind");
        }
      }
    }
    assertNotNull(definition);
    assertNotNull(sourceSub);
    var children = new JsonArray();
    children.add(gson.toJsonTree(sourceSub));
    definition.add("SubMissionList", children);
    GameData.getMissionInfos().put(MAIN, gson.fromJson(definition, MissionInfo.class));
    GameplayDefinitions.subs.put(SUB, sourceSub);
    actor = new Actor();
    bind();
    actor.getGameplay().getMainMissions().put(MAIN, complete(MAIN, "CLOSED"));
    actor.getGameplay().getSubMissions().put(SUB, complete(SUB, "CLOSED"));
  }

  void bind() {
    session =
        new Gameplay46HandlerTest.Session() {
          @Override
          public Player getPlayer() {
            return actor;
          }
        };
  }

  @AfterEach
  void restore() {
    if (oldEntry == null) GameData.getMapEntranceExcelMap().keySet().remove(ENTRY);
    else GameData.getMapEntranceExcelMap().add(oldEntry);
    if (oldPlane == null) GameData.getMazePlaneExcelMap().keySet().remove(PLANE);
    else GameData.getMazePlaneExcelMap().add(oldPlane);
    if (oldMain == null) GameData.getMainMissionExcelMap().keySet().remove(MAIN);
    else GameData.getMainMissionExcelMap().add(oldMain);
    if (oldDefinition == null) GameData.getMissionInfos().remove(MAIN);
    else GameData.getMissionInfos().put(MAIN, oldDefinition);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
  }

  static QuestState complete(int id, String phase) {
    var state = new QuestState(id);
    assertTrue(state.advance(1, 1, 1));
    if (!phase.equals("FINISHED")) assertTrue(state.reserveReward());
    if (phase.equals("CLOSED")) state.closeReward();
    return state;
  }

  QuestState damaged(int id, String mode) {
    if (mode.equals("null-record")) return null;
    if (mode.equals("doing")) return new QuestState(id);
    var row = gson.toJsonTree(complete(id, "CLOSED")).getAsJsonObject();
    switch (mode) {
      case "null-phase" -> row.add("phase", JsonNull.INSTANCE);
      case "zero-time" -> row.addProperty("finishTime", 0);
      case "negative-time" -> row.addProperty("finishTime", -1);
      case "zero-progress" -> row.addProperty("progress", 0);
      case "negative-progress" -> row.addProperty("progress", -1);
      case "too-much-progress" -> row.addProperty("progress", 2);
      case "wrong-id" -> row.addProperty("questId", id + 1);
      default -> fail("Unknown damage shape");
    }
    return gson.fromJson(row, QuestState.class);
  }

  void enter(Gameplay46HandlerTest.Session target, Retcode code) throws Exception {
    var req = Gameplay46.builder(target, "EnterSceneCsReq");
    Gameplay46.set(req, "entry_id", ENTRY);
    assertTrue(
        VersionedPacketHandler.handle(
            target,
            target.getProtocol().clientCommand("EnterSceneCsReq"),
            req.build().toByteArray()));
    assertEquals("EnterSceneScRsp", target.responseName);
    assertTrue(target.response.getUnknownFields().asMap().isEmpty());
    assertEquals(code.getVal(), Gameplay46.number(target.response, "retcode"));
    var field = target.response.getDescriptorForType().findFieldByName("scene_identifier");
    assertEquals(code == Retcode.SUCC, target.response.hasField(field));
  }

  void blocked() throws Exception {
    String before = gson.toJson(actor.getGameplay());
    int saves = actor.saves;
    enter(session, Retcode.MISSION_NOT_FINISH);
    assertEquals(0, actor.transitions);
    assertEquals(saves, actor.saves);
    assertEquals(before, gson.toJson(actor.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"FINISHED", "CLAIMING", "CLOSED"})
  void intactSubCompletionAtEveryRewardStageAllowsOriginalEntrance(String phase) throws Exception {
    actor.getGameplay().getSubMissions().put(SUB, complete(SUB, phase));
    enter(session, Retcode.SUCC);
    assertEquals(1, actor.transitions);
    assertEquals(0, actor.saves);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-record",
        "doing",
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void damagedSubHistoryCannotPassTheOriginalGate(String mode) throws Exception {
    actor.getGameplay().getSubMissions().put(SUB, damaged(SUB, mode));
    blocked();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-record",
        "doing",
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void damagedParentHistoryCannotPassTheOriginalGate(String mode) throws Exception {
    actor.getGameplay().getMainMissions().put(MAIN, damaged(MAIN, mode));
    blocked();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing-definition",
        "wrong-definition-id",
        "negative-goal",
        "different-parent",
        "missing-parent",
        "cancelled-sub",
        "cancelled-parent"
      })
  void orphanedOrCancelledSubCannotAuthorizePhysicalTransition(String mode) throws Exception {
    var row = gson.toJsonTree(sourceSub).getAsJsonObject();
    switch (mode) {
      case "missing-definition" -> GameplayDefinitions.subs.remove(SUB);
      case "wrong-definition-id" -> {
        row.addProperty("ID", SUB + 1);
        GameplayDefinitions.subs.put(SUB, gson.fromJson(row, MissionInfo.SubMissionInfo.class));
      }
      case "negative-goal" -> {
        row.addProperty("Progress", -1);
        GameplayDefinitions.subs.put(SUB, gson.fromJson(row, MissionInfo.SubMissionInfo.class));
      }
      case "different-parent" -> {
        row.addProperty("MainMissionID", MAIN + 1);
        GameplayDefinitions.subs.put(SUB, gson.fromJson(row, MissionInfo.SubMissionInfo.class));
      }
      case "missing-parent" -> actor.getGameplay().getMainMissions().remove(MAIN);
      case "cancelled-sub" -> actor.getGameplay().getCancelledSubs().add(SUB);
      case "cancelled-parent" -> actor.getGameplay().getCancelledMissions().add(MAIN);
    }
    blocked();
  }

  @Test
  void historicalZeroGoalStillUsesTheExistingEffectiveOneGoal() throws Exception {
    var row = gson.toJsonTree(sourceSub).getAsJsonObject();
    row.addProperty("Progress", 0);
    GameplayDefinitions.subs.put(SUB, gson.fromJson(row, MissionInfo.SubMissionInfo.class));
    enter(session, Retcode.SUCC);
    assertEquals(1, actor.transitions);
  }

  @Test
  void actualPlayerDocumentKeepsAnInvalidGateBlockedAfterReloadThenAllowsHealthyHistory()
      throws Exception {
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "scene_entrance_records");
      store.getMapper().map(Player.class, QuestState.class);
      actor.getGameplay().getSubMissions().put(SUB, damaged(SUB, "zero-time"));
      reload(store);
      blocked();
      actor.getGameplay().getSubMissions().put(SUB, complete(SUB, "CLOSED"));
      reload(store);
      enter(session, Retcode.SUCC);
      assertEquals(1, actor.transitions);
    } finally {
      server.shutdownNow();
    }
  }

  void reload(dev.morphia.Datastore store) throws Exception {
    var base = new Player();
    var uid = Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(base, 123456795);
    var gameplay = Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(base, actor.getGameplay());
    store.save(base);
    var restored =
        store
            .find(Player.class)
            .filter(dev.morphia.query.filters.Filters.eq("_id", 123456795))
            .first();
    assertNotNull(restored);
    actor = new Actor();
    gameplay.set(actor, restored.getGameplay());
    bind();
  }

  @Test
  void concurrentNativeRequestsCannotTurnInvalidCompletionIntoAnEntrance() throws Exception {
    actor.getGameplay().getSubMissions().put(SUB, damaged(SUB, "wrong-id"));
    String before = gson.toJson(actor.getGameplay());
    var workers = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var calls = new ArrayList<java.util.concurrent.Callable<Void>>();
      for (int i = 0; i < 24; i++)
        calls.add(
            () -> {
              var requestSession =
                  new Gameplay46HandlerTest.Session() {
                    public Player getPlayer() {
                      return actor;
                    }
                  };
              enter(requestSession, Retcode.MISSION_NOT_FINISH);
              return null;
            });
      for (var result : workers.invokeAll(calls)) result.get();
      assertEquals(0, actor.transitions);
      assertEquals(0, actor.saves);
      assertEquals(before, gson.toJson(actor.getGameplay()));
    } finally {
      workers.shutdownNow();
    }
  }
}
