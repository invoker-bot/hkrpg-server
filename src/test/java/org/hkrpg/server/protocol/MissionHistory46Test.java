package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CN4.6 field 15 loads finished children without invoking the 0x928 finish callback. */
class MissionHistory46Test {
  static final int MAIN = 1000112, SUB = 100010191;
  static final int NEXT_MAIN = 19883003, NEXT_SUB = 198830031;
  final Map<Integer, MissionInfo.SubMissionInfo> old = new HashMap<>();
  MissionNotification46Test.Session session;

  @BeforeEach void setup() throws Exception {
    var gson = new Gson();
    var source = gson.fromJson(Files.readString(Path.of(
        "resources/4.6/Config/Level/Mission/1000112/MissionInfo_1000112.partial.json")),
        com.google.gson.JsonObject.class).getAsJsonArray("SubMissionList").get(0);
    old.put(SUB, GameplayDefinitions.subs.put(SUB,
        gson.fromJson(source, MissionInfo.SubMissionInfo.class)));
    old.put(NEXT_SUB, GameplayDefinitions.subs.put(NEXT_SUB, gson.fromJson(
        "{\"ID\":" + NEXT_SUB + ",\"MainMissionID\":" + NEXT_MAIN + ",\"Progress\":1}",
        MissionInfo.SubMissionInfo.class)));
    session = new MissionNotification46Test.Session();
    session.actor.getGameplay().getMainMissions().put(MAIN, completed(MAIN));
    session.actor.getGameplay().getSubMissions().put(SUB, completed(SUB));
    session.actor.getGameplay().getMainMissions().put(NEXT_MAIN, new QuestState(NEXT_MAIN));
    session.actor.getGameplay().getSubMissions().put(NEXT_SUB, new QuestState(NEXT_SUB));
  }

  @AfterEach void cleanup() {
    old.forEach((id, config) -> {
      if (config == null) GameplayDefinitions.subs.remove(id);
      else GameplayDefinitions.subs.put(id, config);
    });
  }

  static QuestState completed(int id) { return MissionNotification46Test.finished(id); }
  DynamicMessage query() throws Exception {
    session.request("GetMissionDataCsReq");
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    return session.response;
  }
  List<?> list(DynamicMessage message, String field) {
    return (List<?>) Gameplay46.value(message, field);
  }
  List<Integer> ids(DynamicMessage message, String field) {
    return list(message, field).stream().map(row -> Gameplay46.number((DynamicMessage) row, "id")).toList();
  }
  void assertQuietHistory(DynamicMessage snapshot) {
    assertEquals(List.of(MAIN), list(snapshot, "finished_main_mission_id_list"));
    assertEquals(List.of(SUB), snapshot.getField(snapshot.getDescriptorForType().findFieldByNumber(15)),
        "Finished child history belongs in native field 15, not the callback-producing mission_list");
    assertEquals(List.of(NEXT_SUB), ids(snapshot, "mission_list"));
    assertEquals(List.of(NEXT_MAIN), ids(snapshot, "main_mission_list"));
  }

  @ParameterizedTest @ValueSource(strings = {"FINISHED", "CLAIMING", "CLOSED"})
  void tutorialUnlockHistoryDoesNotReenterTheActiveMissionPipeline(String phase) throws Exception {
    for (var state : List.of(session.actor.getGameplay().getMainMissions().get(MAIN),
        session.actor.getGameplay().getSubMissions().get(SUB))) {
      if (!phase.equals("FINISHED")) state.reserveReward();
      if (phase.equals("CLOSED")) state.closeReward();
    }
    var before = new Gson().toJson(session.actor.getGameplay());
    assertQuietHistory(query());
    assertEquals(before, new Gson().toJson(session.actor.getGameplay()));
  }

  @Test void completedChildOfAnOngoingMissionAlsoLoadsWithoutAFinishCallback() throws Exception {
    session.actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    var snapshot = query();
    assertEquals(List.of(SUB), snapshot.getField(snapshot.getDescriptorForType().findFieldByNumber(15)));
    assertEquals(List.of(NEXT_SUB), ids(snapshot, "mission_list"));
    assertEquals(List.of(MAIN, NEXT_MAIN), ids(snapshot, "main_mission_list"));
    assertTrue(list(snapshot, "finished_main_mission_id_list").isEmpty());
  }

  @Test void historyQueriesCannotEraseNotificationBaselineAndReplayOldUnlocks() throws Exception {
    for (int i = 0; i < 3; i++) {
      assertQuietHistory(query());
      Gameplay46.syncMissions(session.actor);
    }
    assertTrue(session.notifications.isEmpty());
    session.actor.getGameplay().getSubMissions().get(NEXT_SUB).advance(1, 1, 456);
    session.actor.getGameplay().getMainMissions().get(NEXT_MAIN).advance(1, 1, 456);
    Gameplay46.syncMissions(session.actor);
    assertEquals(1, session.notifications.size());
    var delta = (DynamicMessage) Gameplay46.value(session.notifications.getFirst(), "mission_sync");
    assertEquals(List.of(NEXT_SUB), ids(delta, "mission_list"));
    assertEquals(List.of(NEXT_MAIN), list(delta, "finished_main_mission_id_list"));
    query();
    Gameplay46.syncMissions(session.actor);
    assertEquals(1, session.notifications.size(), "Reading history must not repeat the real new completion");
  }

  @Test void repeatedLoginQueriesAndPlayerReloadKeepTheTutorialHistoryQuiet() throws Exception {
    var saved = new Gson().toJson(session.actor.getGameplay());
    var login = new emu.lunarcore.server.packet.recv.HandlerPlayerLoginCsReq();
    for (int i = 0; i < 2; i++) {
      login.handle(session, new byte[0]);
      assertQuietHistory(query());
      Gameplay46.syncMissions(session.actor);
      assertTrue(session.notifications.isEmpty());
      session = new MissionNotification46Test.Session();
      var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(session.actor, new Gson().fromJson(saved, org.hkrpg.server.gameplay.GameplayState.class));
    }
  }

  @Test void failedHistorySendDoesNotAcknowledgePendingCompletion() throws Exception {
    assertQuietHistory(query());
    session.actor.getGameplay().getSubMissions().get(NEXT_SUB).advance(1, 1, 456);
    session.failSend = true;
    assertThrows(IllegalStateException.class, this::query);
    session.failSend = false;
    Gameplay46.syncMissions(session.actor);
    assertEquals(1, session.notifications.size());
    var delta = (DynamicMessage) Gameplay46.value(session.notifications.getFirst(), "mission_sync");
    assertEquals(List.of(NEXT_SUB), ids(delta, "mission_list"));
  }
}
