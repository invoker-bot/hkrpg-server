package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.recv.HandlerGetMissionStatusCsReq;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** A finished parent's optional goodbye must not displace Asta's current commission. */
class RetiredMissionDialogue46Test {
  static final int OLD_MAIN = 1000501, GOODBYE = 100050103;
  static final int MAIN = 2000301, MESSAGE = 200030100, TALK = 200030101;
  static final Gson GSON = new Gson();
  final Map<Integer, MissionInfo> oldMissions = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  Session session;

  static class Session extends Gameplay46HandlerTest.Session {
    int saves;
    final List<DynamicMessage> notifications = new ArrayList<>();
    final Player actor = new Player() {
      public void save() { saves++; }
      public boolean isOnline() { return true; }
      public GameSession getSession() { return Session.this; }
      public int getPlaneId() { return 10001; }
      public int getFloorId() { return 10001001; }
      public String getName() { return "Asta mission fixture"; }
    };
    public Player getPlayer() { return actor; }
    public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      if (name.equals("PlayerSyncScNotify")) notifications.add(response);
    }
    public void send(BasePacket packet) {
      var wire = getProtocol().outbound(packet.getCmdId(), packet.getData().toByteArray());
      assertEquals(getProtocol().clientCommand("GetMissionStatusScRsp"), wire.cmdId());
      sendClientPacket("GetMissionStatusScRsp", wire.body());
    }
  }

  @BeforeEach void setup() throws Exception {
    oldMissions.putAll(GameData.getMissionInfos());
    oldSubs.putAll(GameplayDefinitions.subs);
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    for (int id : List.of(OLD_MAIN, MAIN)) {
      Path root = Path.of("resources/4.6/Config/Level/Mission/" + id);
      var row = JsonParser.parseString(Files.readString(root.resolve("MissionInfo_" + id + ".json")))
          .getAsJsonObject();
      var partial = JsonParser.parseString(Files.readString(root.resolve("MissionInfo_" + id + ".partial.json")))
          .getAsJsonObject();
      row.add("SubMissionList", partial.get("SubMissionList"));
      var mission = GSON.fromJson(row, MissionInfo.class);
      GameData.getMissionInfos().put(id, mission);
      for (var sub : mission.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    session = new Session();
    session.actor.getGameplay().getMainMissions().put(OLD_MAIN, completed(OLD_MAIN, QuestState.Phase.CLOSED));
    session.actor.getGameplay().getSubMissions().put(GOODBYE, new QuestState(GOODBYE));
    session.actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    session.actor.getGameplay().getSubMissions().put(MESSAGE, completed(MESSAGE, QuestState.Phase.FINISHED));
    session.actor.getGameplay().getSubMissions().put(TALK, new QuestState(TALK));
  }

  @AfterEach void cleanup() {
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldMissions);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
  }

  static QuestState completed(int id, QuestState.Phase phase) {
    var state = new QuestState(id);
    state.advance(1, 1, 123);
    if (phase != QuestState.Phase.FINISHED) state.reserveReward();
    if (phase == QuestState.Phase.CLOSED) state.closeReward();
    return state;
  }

  List<DynamicMessage> rows(DynamicMessage message, String name) {
    return ((List<?>) Gameplay46.value(message, name)).stream().map(DynamicMessage.class::cast).toList();
  }
  DynamicMessage row(DynamicMessage message, String name, int id) {
    return rows(message, name).stream().filter(r -> Gameplay46.number(r, "id") == id).findFirst().orElseThrow();
  }
  void status(DynamicMessage row, String expected) {
    assertEquals("MissionStatus_Mission" + expected, Gameplay46.value(row, "status").toString());
  }
  DynamicMessage snapshot() throws Exception {
    session.request("GetMissionDataCsReq");
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    return session.response;
  }
  DynamicMessage dependency() throws Exception {
    var request = Gameplay46.builder(session, "GetMissionStatusCsReq");
    for (int id : List.of(GOODBYE, MESSAGE, TALK)) Gameplay46.add(request, "sub_mission_id_list", id);
    var converted = session.getProtocol().inbound(session.getProtocol().clientCommand("GetMissionStatusCsReq"),
        request.build().toByteArray());
    new HandlerGetMissionStatusCsReq().handle(session, converted.body());
    return session.response;
  }

  @ParameterizedTest
  @EnumSource(value = QuestState.Phase.class, names = {"FINISHED", "CLAIMING", "CLOSED"})
  void completedMainDoesNotAdvertiseItsUnfinishedGoodbyeAsCurrentDialogue(QuestState.Phase phase) throws Exception {
    session.actor.getGameplay().getMainMissions().put(OLD_MAIN, completed(OLD_MAIN, phase));
    String before = GSON.toJson(session.actor.getGameplay());
    int saves = session.saves;
    var snapshot = snapshot();
    status(row(snapshot, "mission_list", GOODBYE), "None");
    status(row(snapshot, "mission_list", TALK), "Doing");
    assertEquals(List.of(MESSAGE), Gameplay46.value(snapshot, "OLKALABEDOA"));
    assertEquals(List.of(OLD_MAIN), Gameplay46.value(snapshot, "finished_main_mission_id_list"));
    assertEquals(before, GSON.toJson(session.actor.getGameplay()));
    assertEquals(saves, session.saves);
  }

  @ParameterizedTest
  @EnumSource(value = QuestState.Phase.class, names = {"FINISHED", "CLAIMING", "CLOSED"})
  void npcDependencyQueryUsesTheSameInactiveGoodbyeWithoutInventingCompletion(QuestState.Phase phase) throws Exception {
    session.actor.getGameplay().getMainMissions().put(OLD_MAIN, completed(OLD_MAIN, phase));
    String before = GSON.toJson(session.actor.getGameplay());
    var query = dependency();
    status(row(query, "sub_mission_status_list", GOODBYE), "None");
    assertEquals(0, Gameplay46.number(row(query, "sub_mission_status_list", GOODBYE), "progress"));
    status(row(query, "sub_mission_status_list", MESSAGE), "Finish");
    status(row(query, "sub_mission_status_list", TALK), "Doing");
    assertEquals(before, GSON.toJson(session.actor.getGameplay()));
  }

  @Test void unfinishedPrologueStillAllowsItsOriginalGoodbye() throws Exception {
    session.actor.getGameplay().getMainMissions().put(OLD_MAIN, new QuestState(OLD_MAIN));
    status(row(snapshot(), "mission_list", GOODBYE), "Doing");
    status(row(dependency(), "sub_mission_status_list", GOODBYE), "Doing");
  }

  @Test void anActuallyCompletedGoodbyeKeepsItsRealHistory() throws Exception {
    session.actor.getGameplay().getSubMissions().put(GOODBYE, completed(GOODBYE, QuestState.Phase.FINISHED));
    var query = snapshot();
    assertTrue(((List<?>) Gameplay46.value(query, "OLKALABEDOA")).contains(GOODBYE));
    status(row(dependency(), "sub_mission_status_list", GOODBYE), "Finish");
  }

  @Test void parentCompletionClearsThePreviousDoingStateOnceInsteadOfFakingAFinish() throws Exception {
    session.actor.getGameplay().getMainMissions().put(OLD_MAIN, new QuestState(OLD_MAIN));
    snapshot();
    session.notifications.clear();
    session.actor.getGameplay().getMainMissions().put(OLD_MAIN, completed(OLD_MAIN, QuestState.Phase.FINISHED));
    Gameplay46.syncMissions(session.actor);
    assertEquals(1, session.notifications.size());
    var delta = (DynamicMessage) Gameplay46.value(session.notifications.getFirst(), "mission_sync");
    status(row(delta, "mission_list", GOODBYE), "None");
    assertTrue(rows(delta, "mission_list").stream().noneMatch(r -> Gameplay46.number(r, "id") == TALK));
    for (var retired : rows(delta, "mission_list")) status(retired, "None");
    assertEquals(List.of(OLD_MAIN), Gameplay46.value(delta, "finished_main_mission_id_list"));
    Gameplay46.syncMissions(session.actor);
    assertEquals(1, session.notifications.size());
    assertEquals(QuestState.Phase.DOING, session.actor.getMissionManager().sub(GOODBYE).getPhase());
    assertTrue(session.actor.getGameplay().getRewards().isEmpty());
  }

  @Test void onlyTheActualCommissionDialogueUnlocksTheThreeInvestigations() throws Exception {
    snapshot();
    assertNotEquals(0, session.actor.getMissionManager().finishTalk(GOODBYE, "Mission_" + GOODBYE).getVal());
    assertNull(session.actor.getMissionManager().sub(200030102));
    assertEquals(0, session.actor.getMissionManager().finishTalk(TALK, "Mission_" + TALK).getVal());
    for (int id : List.of(200030102, 200030104, 200030106))
      assertEquals(QuestState.Phase.DOING, session.actor.getMissionManager().sub(id).getPhase());
    assertFalse(session.actor.getMissionManager().finished(MAIN));
    status(row(dependency(), "sub_mission_status_list", GOODBYE), "None");
  }

  @Test void reconnectRebuildsInactiveDialogueFromTheUnmodifiedSavedRecords() throws Exception {
    String saved = GSON.toJson(session.actor.getGameplay());
    for (int i = 0; i < 2; i++) {
      session = new Session();
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(session.actor, GSON.fromJson(saved, GameplayState.class));
      status(row(snapshot(), "mission_list", GOODBYE), "None");
      status(row(dependency(), "sub_mission_status_list", TALK), "Doing");
      Gameplay46.syncMissions(session.actor);
      assertTrue(session.notifications.isEmpty());
      assertEquals(saved, GSON.toJson(session.actor.getGameplay()));
    }
  }
}
