package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.SessionState;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.junit.jupiter.api.*;

/** A login snapshot is history, whereas mission_sync invokes client completion callbacks. */
class MissionNotification46Test {
  static final int OLD_MAIN = 19883001, MAIN = 19883002, SUB = 198830021;
  MissionInfo.SubMissionInfo old;
  Session session;

  static class Session extends Gameplay46HandlerTest.Session {
    final List<DynamicMessage> notifications = new ArrayList<>();
    boolean failSend;
    final Player actor = new Player() {
      public void save() {}
      public String getName() { return "notification-fixture"; }
      public boolean isOnline() { return true; }
      public GameSession getSession() { return Session.this; }
    };
    public Player getPlayer() { return actor; }
    public void sendClientPacket(String name, byte[] bytes) {
      if (failSend) throw new IllegalStateException("injected transport failure");
      super.sendClientPacket(name, bytes);
      if (name.equals("PlayerSyncScNotify")) notifications.add(response);
    }
  }

  @BeforeEach void setup() {
    old = GameplayDefinitions.subs.put(SUB, new Gson().fromJson(
        "{\"ID\":" + SUB + ",\"MainMissionID\":" + MAIN + ",\"Progress\":3}",
        MissionInfo.SubMissionInfo.class));
    session = new Session();
    session.actor.getGameplay().getMainMissions().put(OLD_MAIN, finished(OLD_MAIN));
    session.actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    session.actor.getGameplay().getSubMissions().put(SUB, new QuestState(SUB));
  }

  @AfterEach void cleanup() {
    if (old == null) GameplayDefinitions.subs.remove(SUB);
    else GameplayDefinitions.subs.put(SUB, old);
  }

  static QuestState finished(int id) {
    var state = new QuestState(id);
    state.advance(1, 1, 123);
    return state;
  }

  void query() throws Exception { session.request("GetMissionDataCsReq"); }
  void sync() { Gameplay46.syncMissions(session.actor); }
  List<?> field(DynamicMessage message, String name) {
    return (List<?>) Gameplay46.value(message, name);
  }
  DynamicMessage delta() {
    return (DynamicMessage) Gameplay46.value(session.notifications.getLast(), "mission_sync");
  }

  @Test void fullQueryRetainsHistoryButDoesNotTurnItIntoNewCompletionNotifications() throws Exception {
    query();
    assertEquals(List.of(OLD_MAIN), field(session.response, "finished_main_mission_id_list"));
    sync(); sync();
    assertTrue(session.notifications.isEmpty(), "An unchanged history must not replay unlock hints");
  }

  @Test void advancingOneObjectiveDoesNotReplayOldMainMissionRewardsOrOtherObjectives() throws Exception {
    query();
    session.actor.getGameplay().getSubMissions().get(SUB).advance(1, 3, 124);
    sync();
    assertEquals(1, session.notifications.size());
    assertTrue(field(delta(), "finished_main_mission_id_list").isEmpty());
    assertEquals(1, field(delta(), "mission_list").size());
    assertEquals(1, Gameplay46.number((DynamicMessage) field(delta(), "mission_list").getFirst(), "progress"));
    sync();
    assertEquals(1, session.notifications.size());
  }

  @Test void actualNewCompletionIsDeliveredOnceAndRemainsVisibleInSubsequentQueries() throws Exception {
    query();
    session.actor.getGameplay().getSubMissions().get(SUB).advance(3, 3, 124);
    session.actor.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 124);
    sync();
    assertEquals(List.of(MAIN), field(delta(), "finished_main_mission_id_list"));
    sync();
    assertEquals(1, session.notifications.size());
    query();
    assertEquals(List.of(OLD_MAIN, MAIN), field(session.response, "finished_main_mission_id_list"));
    sync();
    assertEquals(1, session.notifications.size());
  }

  @Test void acknowledgedSnapshotSurvivesPlayerReloadWithoutPersistingSessionNotificationFlags() throws Exception {
    query();
    session.actor.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 124);
    sync();
    var saved = new Gson().toJson(session.actor.getGameplay());
    session = new Session();
    var gameplay = Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(session.actor, new Gson().fromJson(saved, org.hkrpg.server.gameplay.GameplayState.class));
    query(); sync();
    assertTrue(session.notifications.isEmpty(), "Reconnection must load history without grant popups");
  }

  @Test void failedNotificationCanBeRetriedWithoutLosingNewCompletion() throws Exception {
    query();
    session.actor.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 124);
    session.failSend = true;
    assertThrows(IllegalStateException.class, this::sync);
    session.failSend = false;
    sync();
    assertEquals(List.of(MAIN), field(delta(), "finished_main_mission_id_list"));
    sync();
    assertEquals(1, session.notifications.size());
  }

  @Test void inactiveSessionDoesNotConsumePendingNotification() throws Exception {
    query();
    session.actor.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 124);
    session.setState(SessionState.INACTIVE);
    sync();
    assertTrue(session.notifications.isEmpty());
    session.setState(SessionState.ACTIVE);
    sync();
    assertEquals(List.of(MAIN), field(delta(), "finished_main_mission_id_list"));
  }

  @Test void realLoginHandlerSeedsHistoryBeforeAnyMissionQuery() throws Exception {
    new emu.lunarcore.server.packet.recv.HandlerPlayerLoginCsReq().handle(session, new byte[0]);
    sync();
    assertTrue(session.notifications.isEmpty());
    session.actor.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 124);
    sync();
    assertEquals(List.of(MAIN), field(delta(), "finished_main_mission_id_list"));
  }

  @Test void repeatedLoginRequestCannotSwallowPendingNewCompletion() throws Exception {
    var login = new emu.lunarcore.server.packet.recv.HandlerPlayerLoginCsReq();
    login.handle(session, new byte[0]);
    session.actor.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 124);
    login.handle(session, new byte[0]);
    sync();
    assertEquals(List.of(MAIN), field(delta(), "finished_main_mission_id_list"));
  }

  @Test void rewardPhaseChangesDoNotReplayCompletion() throws Exception {
    query();
    var completed = session.actor.getGameplay().getMainMissions().get(OLD_MAIN);
    completed.reserveReward();
    sync();
    completed.closeReward();
    sync();
    assertTrue(session.notifications.isEmpty());
  }

  @Test void repeatableMissionCanNotifyAgainAfterActuallyReopening() throws Exception {
    query();
    session.actor.getGameplay().getMainMissions().put(OLD_MAIN, new QuestState(OLD_MAIN));
    sync();
    assertTrue(session.notifications.isEmpty());
    session.actor.getGameplay().getMainMissions().get(OLD_MAIN).advance(1, 1, 125);
    sync();
    assertEquals(List.of(OLD_MAIN), field(delta(), "finished_main_mission_id_list"));
  }

  @Test void badSnapshotDoesNotErasePreviousBaseline() throws Exception {
    query();
    var state = session.actor.getGameplay().getMainMissions().put(OLD_MAIN, null);
    query(); sync();
    assertTrue(session.notifications.isEmpty());
    session.actor.getGameplay().getMainMissions().put(OLD_MAIN, state);
    session.actor.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 124);
    sync();
    assertEquals(List.of(MAIN), field(delta(), "finished_main_mission_id_list"));
  }

  @Test void failedFullQueryDoesNotAcknowledgeNewState() throws Exception {
    query();
    session.actor.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 124);
    session.failSend = true;
    assertThrows(IllegalStateException.class, this::query);
    session.failSend = false;
    sync();
    assertEquals(List.of(MAIN), field(delta(), "finished_main_mission_id_list"));
  }

  @Test void readOnlySnapshotInspectionDoesNotAcknowledgeNewState() throws Exception {
    query();
    session.actor.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 124);
    Gameplay46.missionSnapshot(session, false);
    sync();
    assertEquals(List.of(MAIN), field(delta(), "finished_main_mission_id_list"));
  }

  @Test void concurrentRefreshesDeliverOneConsistentDelta() throws Exception {
    query();
    session.actor.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 124);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var calls = new ArrayList<java.util.concurrent.Callable<Void>>();
      for (int i = 0; i < 24; i++) calls.add(() -> { sync(); return null; });
      for (var task : pool.invokeAll(calls)) task.get();
    }
    assertEquals(1, session.notifications.size());
    assertEquals(List.of(MAIN), field(delta(), "finished_main_mission_id_list"));
  }

  @Test void actualPlayerDatabaseReloadDoesNotReplayHistoryOnFreshConnections() throws Exception {
    var mongo = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client = com.mongodb.client.MongoClients.create(
        "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "mission_notification_history");
      store.getMapper().map(Player.class, QuestState.class);
      var uid = Player.class.getDeclaredField("uid"); uid.setAccessible(true);
      var gameplay = Player.class.getDeclaredField("gameplay"); gameplay.setAccessible(true);
      query();
      session.actor.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 124);
      sync();
      for (int i = 0; i < 2; i++) {
        var saved = new Player(); uid.setInt(saved, 198830012);
        gameplay.set(saved, session.actor.getGameplay()); store.save(saved);
        var restored = store.find(Player.class)
            .filter(dev.morphia.query.filters.Filters.eq("_id", 198830012)).first();
        assertNotNull(restored);
        session = new Session(); gameplay.set(session.actor, restored.getGameplay());
        new emu.lunarcore.server.packet.recv.HandlerPlayerLoginCsReq().handle(session, new byte[0]);
        query(); sync();
        assertTrue(session.notifications.isEmpty());
        assertEquals(List.of(OLD_MAIN, MAIN), field(session.response, "finished_main_mission_id_list"));
      }
    } finally { mongo.shutdownNow(); }
  }
}
