package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A corrupted saved flag must not become a completed client snapshot. */
class MissionSnapshotProof46Test {
  static final int MAIN = 19884001, SUB = 198840011;
  final Gson gson = new Gson();
  Session session;
  MissionInfo.SubMissionInfo old;

  static class Session extends Gameplay46HandlerTest.Session {
    int sent;
    final Player actor =
        new Player() {
          public void save() {}

          public boolean isOnline() {
            return true;
          }

          public GameSession getSession() {
            return Session.this;
          }
        };

    public Player getPlayer() {
      return actor;
    }

    public void sendClientPacket(String name, byte[] bytes) {
      sent++;
      super.sendClientPacket(name, bytes);
    }
  }

  @BeforeEach
  void setup() {
    old =
        GameplayDefinitions.subs.put(
            SUB,
            gson.fromJson(
                "{\"ID\":" + SUB + ",\"MainMissionID\":" + MAIN + ",\"Progress\":3}",
                MissionInfo.SubMissionInfo.class));
    session = new Session();
    session.actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    session.actor.getGameplay().getSubMissions().put(SUB, new QuestState(SUB));
  }

  @AfterEach
  void cleanup() {
    if (old == null) GameplayDefinitions.subs.remove(SUB);
    else GameplayDefinitions.subs.put(SUB, old);
  }

  QuestState damage(int id, int goal, String mode) {
    var state = new QuestState(id);
    state.advance(goal, goal, 1);
    var row = gson.toJsonTree(state).getAsJsonObject();
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

  void rejectSnapshot() throws Exception {
    String before = gson.toJson(session.actor.getGameplay());
    session.request("GetMissionDataCsReq");
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(), Gameplay46.number(session.response, "retcode"));
    for (var name : List.of("mission_list", "main_mission_list", "finished_main_mission_id_list"))
      assertTrue(((List<?>) Gameplay46.value(session.response, name)).isEmpty());
    assertEquals(before, gson.toJson(session.actor.getGameplay()));
    assertTrue(session.response.getUnknownFields().asMap().isEmpty());
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
  void badMainHistoryCannotBecomeACompletedNativeQuery(String mode) throws Exception {
    session.actor.getGameplay().getMainMissions().put(MAIN, damage(MAIN, 1, mode));
    rejectSnapshot();
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
  void badChildHistoryCannotBecomeACompletedNativeQuery(String mode) throws Exception {
    session.actor.getGameplay().getSubMissions().put(SUB, damage(SUB, 3, mode));
    rejectSnapshot();
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
  void customValueQueryCannotBypassMainRecordValidation(String mode) throws Exception {
    session.actor.getGameplay().getMainMissions().put(MAIN, damage(MAIN, 1, mode));
    var request = Gameplay46.builder(session, "GetMainMissionCustomValueCsReq");
    request.addRepeatedField(
        request.getDescriptorForType().findFieldByName("main_mission_id_list"), MAIN);
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("GetMainMissionCustomValueCsReq"),
            request.build().toByteArray()));
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(), Gameplay46.number(session.response, "retcode"));
    assertTrue(((List<?>) Gameplay46.value(session.response, "main_mission_list")).isEmpty());
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
  void badMainHistoryCannotBecomeAnUnsolicitedCompletionNotify(String mode) {
    session.actor.getGameplay().getMainMissions().put(MAIN, damage(MAIN, 1, mode));
    var before = gson.toJson(session.actor.getGameplay());
    Gameplay46.syncMissions(session.actor);
    assertEquals(0, session.sent);
    assertEquals(before, gson.toJson(session.actor.getGameplay()));
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
  void badChildHistoryCannotBecomeAnUnsolicitedCompletionNotify(String mode) {
    session.actor.getGameplay().getSubMissions().put(SUB, damage(SUB, 3, mode));
    var before = gson.toJson(session.actor.getGameplay());
    Gameplay46.syncMissions(session.actor);
    assertEquals(0, session.sent);
    assertEquals(before, gson.toJson(session.actor.getGameplay()));
  }

  @Test
  void absentChildGoalDoesNotAuthorizeCompletedWireHistory() throws Exception {
    GameplayDefinitions.subs.remove(SUB);
    session.actor.getGameplay().getSubMissions().put(SUB, damage(SUB, 3, "valid"));
    rejectSnapshot();
  }

  @ParameterizedTest
  @ValueSource(strings = {"FINISHED", "CLAIMING", "CLOSED"})
  void explicitCompletedHistoryKeepsNativeMainAndChildStatus(String phase) throws Exception {
    for (int id : List.of(MAIN, SUB)) {
      var state = damage(id, id == MAIN ? 1 : 3, "valid");
      if (!phase.equals("FINISHED")) state.reserveReward();
      if (phase.equals("CLOSED")) state.closeReward();
      (id == MAIN
              ? session.actor.getGameplay().getMainMissions()
              : session.actor.getGameplay().getSubMissions())
          .put(id, state);
    }
    session.request("GetMissionDataCsReq");
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    assertEquals(
        List.of(MAIN), Gameplay46.value(session.response, "finished_main_mission_id_list"));
    for (String name : List.of("mission_list", "main_mission_list")) {
      var row = (DynamicMessage) ((List<?>) Gameplay46.value(session.response, name)).getFirst();
      assertEquals(2, ((EnumValueDescriptor) Gameplay46.value(row, "status")).getNumber());
    }
    assertEquals(
        3,
        Gameplay46.number(
            (DynamicMessage)
                ((List<?>) Gameplay46.value(session.response, "mission_list")).getFirst(),
            "progress"));
  }

  @Test
  void anUnknownDoingRecordKeepsItsExistingReadOnlyProgress() throws Exception {
    GameplayDefinitions.subs.remove(SUB);
    var state = new QuestState(SUB);
    state.advance(2, 3, 1);
    session.actor.getGameplay().getSubMissions().put(SUB, state);
    session.request("GetMissionDataCsReq");
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    var row =
        (DynamicMessage) ((List<?>) Gameplay46.value(session.response, "mission_list")).getFirst();
    assertEquals(2, Gameplay46.number(row, "progress"));
    assertEquals(1, ((EnumValueDescriptor) Gameplay46.value(row, "status")).getNumber());
  }

  @Test
  void emptyActualHistoryHasANormalEmptySnapshot() throws Exception {
    session.actor.getGameplay().getMainMissions().clear();
    session.actor.getGameplay().getSubMissions().clear();
    session.request("GetMissionDataCsReq");
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    assertTrue(((List<?>) Gameplay46.value(session.response, "mission_list")).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"main", "sub"})
  void aNullSavedMapKeyReturnsADataErrorWithoutThrowing(String kind) {
    var states =
        kind.equals("main")
            ? session.actor.getGameplay().getMainMissions()
            : session.actor.getGameplay().getSubMissions();
    states.put(null, new QuestState(SUB + 1));
    assertDoesNotThrow(() -> rejectSnapshot());
  }

  @Test
  void unsupportedNegativeGoalKeepsDoingHistoryWithoutCompletingIt() throws Exception {
    GameplayDefinitions.subs.put(
        SUB,
        gson.fromJson(
            "{\"ID\":" + SUB + ",\"MainMissionID\":" + MAIN + ",\"Progress\":-1}",
            MissionInfo.SubMissionInfo.class));
    session.request("GetMissionDataCsReq");
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    var row =
        (DynamicMessage) ((List<?>) Gameplay46.value(session.response, "mission_list")).getFirst();
    assertEquals(0, Gameplay46.number(row, "progress"));
    assertEquals(1, ((EnumValueDescriptor) Gameplay46.value(row, "status")).getNumber());
  }

  @Test
  void readOnlyCancelledHistoryDoesNotRestoreGameplayEligibility() throws Exception {
    var state = damage(MAIN, 1, "valid");
    state.reserveReward();
    state.closeReward();
    session.actor.getGameplay().getMainMissions().put(MAIN, state);
    session.actor.getGameplay().getCancelledMissions().add(MAIN);
    session.request("GetMissionDataCsReq");
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    assertEquals(
        List.of(MAIN), Gameplay46.value(session.response, "finished_main_mission_id_list"));
    assertFalse(session.actor.getMissionManager().finished(MAIN));
    assertTrue(session.actor.getGameplay().getCancelledMissions().contains(MAIN));
  }

  @Test
  void actualPlayerMorphiaReloadKeepsBadHistoryRejectedAndGoodHistoryReadable() throws Exception {
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "mission_wire_records");
      store.getMapper().map(Player.class, QuestState.class);
      session.actor.getGameplay().getSubMissions().put(SUB, damage(SUB, 3, "zero-time"));
      reload(store);
      rejectSnapshot();
      var state = damage(SUB, 3, "valid");
      state.reserveReward();
      state.closeReward();
      session.actor.getGameplay().getSubMissions().put(SUB, state);
      reload(store);
      session.request("GetMissionDataCsReq");
      assertEquals(0, Gameplay46.number(session.response, "retcode"));
      assertEquals(
          QuestState.Phase.CLOSED,
          session.actor.getGameplay().getSubMissions().get(SUB).getPhase());
      assertEquals(
          3,
          Gameplay46.number(
              (DynamicMessage)
                  ((List<?>) Gameplay46.value(session.response, "mission_list")).getFirst(),
              "progress"));
      assertTrue(session.actor.getGameplay().getRewards().isEmpty());
    } finally {
      mongo.shutdownNow();
    }
  }

  void reload(dev.morphia.Datastore store) throws Exception {
    var saved = new Player();
    var uid = Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(saved, 198840012);
    var gameplay = Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(saved, session.actor.getGameplay());
    store.save(saved);
    var restored =
        store
            .find(Player.class)
            .filter(dev.morphia.query.filters.Filters.eq("_id", 198840012))
            .first();
    assertNotNull(restored);
    gameplay.set(session.actor, restored.getGameplay());
  }

  @Test
  void concurrentSnapshotsKeepProgressAndPhaseFromTheSamePlayerLock() throws Exception {
    var workers = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var calls = new ArrayList<java.util.concurrent.Callable<DynamicMessage>>();
      for (int i = 0; i < 24; i++) calls.add(() -> Gameplay46.missionSnapshot(session, false));
      calls.add(
          () -> {
            synchronized (session.actor) {
              session.actor.getGameplay().getSubMissions().get(SUB).advance(3, 3, 1);
              session.actor.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 1);
            }
            return Gameplay46.missionSnapshot(session, false);
          });
      for (var result : workers.invokeAll(calls)) {
        var snapshot = result.get();
        assertEquals(0, Gameplay46.number(snapshot, "retcode"));
        var row =
            (DynamicMessage) ((List<?>) Gameplay46.value(snapshot, "mission_list")).getFirst();
        int progress = Gameplay46.number(row, "progress"),
            phase = ((EnumValueDescriptor) Gameplay46.value(row, "status")).getNumber();
        assertTrue(progress == 0 && phase == 1 || progress == 3 && phase == 2);
      }
    } finally {
      workers.shutdownNow();
    }
  }
}
