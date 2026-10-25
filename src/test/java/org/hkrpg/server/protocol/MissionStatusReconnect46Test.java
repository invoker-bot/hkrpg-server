package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.recv.HandlerGetMissionStatusCsReq;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Native MissionModule resolves each queried main ID, including negative answers. */
class MissionStatusReconnect46Test {
  static final int TEAM = 1000201, SHOP = 1000301, NEXT = 1000400, FUTURE = 1000500;
  static final Gson GSON = new Gson();

  static class Session extends Gameplay46HandlerTest.Session {
    final List<String> sent = new ArrayList<>();

    @Override public void send(BasePacket packet) {
      var wire = getProtocol().outbound(packet.getCmdId(), packet.getData().toByteArray());
      sendClientPacket("GetMissionStatusScRsp", wire.body());
      sent.add("GetMissionStatusScRsp");
    }
  }

  static QuestState completed(int id, QuestState.Phase phase) {
    var state = new QuestState(id);
    state.advance(1, 1, 123);
    if (phase != QuestState.Phase.FINISHED) state.reserveReward();
    if (phase == QuestState.Phase.CLOSED) state.closeReward();
    return state;
  }

  static DynamicMessage query(Session session, List<Integer> ids) throws Exception {
    var request = Gameplay46.builder(session, "GetMissionStatusCsReq");
    ids.forEach(id -> Gameplay46.add(request, "main_mission_id_list", id));
    Gameplay46.add(request, "sub_mission_id_list", 100040001);
    var converted = session.getProtocol().inbound(
        session.getProtocol().clientCommand("GetMissionStatusCsReq"), request.build().toByteArray());
    new HandlerGetMissionStatusCsReq().handle(session, converted.body());
    return session.response;
  }

  @SuppressWarnings("unchecked")
  static List<Integer> ids(DynamicMessage response, String name) {
    return (List<Integer>) Gameplay46.value(response, name);
  }

  @ParameterizedTest
  @EnumSource(value = QuestState.Phase.class, names = {"FINISHED", "CLAIMING", "CLOSED"})
  void reconnectAnswersEveryDependencyWithoutReplayingUnlockEvents(QuestState.Phase phase)
      throws Exception {
    var session = new Session();
    session.player.getGameplay().getMainMissions().put(TEAM, completed(TEAM, phase));
    session.player.getGameplay().getMainMissions().put(SHOP, completed(SHOP, phase));
    session.player.getGameplay().getMainMissions().put(NEXT, new QuestState(NEXT));
    var saved = GSON.toJson(session.player.getGameplay());
    for (int reconnect = 0; reconnect < 3; reconnect++) {
      var response = query(session, List.of(TEAM, NEXT, SHOP, FUTURE));
      assertEquals(1238, session.getProtocol().clientCommand("GetMissionStatusScRsp"));
      assertEquals(List.of(TEAM, SHOP), response.getField(
          response.getDescriptorForType().findFieldByNumber(14)));
      assertEquals(List.of(NEXT, FUTURE), response.getField(
          response.getDescriptorForType().findFieldByNumber(3)),
          "Omitted unfinished IDs leave the client's preparation promises unresolved");
      var answered = new HashSet<>(ids(response, "finished_main_mission_id_list"));
      answered.addAll(ids(response, "unfinished_main_mission_id_list"));
      assertEquals(Set.of(TEAM, SHOP, NEXT, FUTURE), answered);
      var child = (DynamicMessage) ((List<?>) Gameplay46.value(response, "sub_mission_status_list")).getFirst();
      assertEquals("MissionStatus_MissionNone", Gameplay46.value(child, "status").toString());
      assertEquals(List.of("GetMissionStatusScRsp"), session.sent);
      assertEquals(saved, GSON.toJson(session.player.getGameplay()));
      session = new Session();
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(session.player, GSON.fromJson(saved, GameplayState.class));
    }
  }

  @Test void freshPlayerReceivesNegativeAnswersForEveryLockedMenuDependency() throws Exception {
    var session = new Session();
    var response = query(session, List.of(TEAM, SHOP, FUTURE));
    assertTrue(ids(response, "finished_main_mission_id_list").isEmpty());
    assertEquals(List.of(TEAM, SHOP, FUTURE), ids(response, "unfinished_main_mission_id_list"));
    assertTrue(session.player.getGameplay().getMainMissions().isEmpty());
  }

  @Test void largeFrameBatchCannotSilentlyOmitFutureDependencies() throws Exception {
    var session = new Session();
    var requested = new ArrayList<Integer>();
    requested.add(SHOP);
    for (int i = 0; i < 600; i++) requested.add(19900000 + i);
    session.player.getGameplay().getMainMissions().put(SHOP, completed(SHOP, QuestState.Phase.CLOSED));
    var response = query(session, requested);
    assertEquals(List.of(SHOP), ids(response, "finished_main_mission_id_list"));
    assertEquals(requested.subList(1, requested.size()), ids(response, "unfinished_main_mission_id_list"));
  }

  @Test void readingStatusDoesNotAcceptAnEligibleAutoMission() throws Exception {
    int id = 19882001;
    var config = GSON.fromJson("""
        {"MainMissionID":19882001,"TakeParam":[{"Type":"Auto","Value":0}],
         "BeginParam":[{"Type":"Auto","Value":0}]}
        """, MainMissionExcel.class);
    var oldConfig = GameData.getMainMissionExcelMap().get(id);
    GameData.getMainMissionExcelMap().add(config);
    var oldInfo = GameData.getMissionInfos().put(id, new MissionInfo());
    try {
      var session = new Session();
      var before = GSON.toJson(session.player.getGameplay());
      query(session, List.of(id));
      assertEquals(before, GSON.toJson(session.player.getGameplay()),
          "A reconnect dependency read must not start story actions or grant a new party member");
    } finally {
      GameData.getMainMissionExcelMap().keySet().remove(id);
      if (oldConfig != null) GameData.getMainMissionExcelMap().add(oldConfig);
      if (oldInfo == null) GameData.getMissionInfos().remove(id);
      else GameData.getMissionInfos().put(id, oldInfo);
    }
  }
}
