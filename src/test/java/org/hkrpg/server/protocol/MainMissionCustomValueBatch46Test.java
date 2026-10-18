package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MainMissionCustomValueBatch46Test {
  static List<Integer> ids(int count) {
    var values = new ArrayList<Integer>();
    // Synthetic IDs with the same canonical packed size as the observed 550-byte query.
    for (int i = 0; i < count; i++) values.add(i == 0 ? 1000000 : 3000000 + i);
    return values;
  }

  static byte[] query(Gameplay46HandlerTest.Session session, List<Integer> ids) {
    var request = Gameplay46.builder(session, "GetMainMissionCustomValueCsReq");
    for (int id : ids) Gameplay46.add(request, "main_mission_id_list", id);
    return request.build().toByteArray();
  }

  static void send(Gameplay46HandlerTest.Session session, List<Integer> ids) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session,
        session.getProtocol().clientCommand("GetMainMissionCustomValueCsReq"), query(session, ids)));
  }

  @ParameterizedTest @ValueSource(ints = {101, 137, 2184, 4096})
  void nativeFrameEndBatchIsNotRejectedAsAnInvalidValue(int count) throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    var ids = ids(count);
    var before = new Gson().toJson(session.player.getGameplay());
    if (count == 137) assertEquals(550, query(session, ids).length);
    send(session, ids);
    assertEquals(0, Gameplay46.number(session.response, "retcode"),
        "The client sends one deduplicated frame-end query, not pages of 100");
    var rows = (List<?>) Gameplay46.value(session.response, "main_mission_list");
    assertEquals(count, rows.size());
    for (int i = 0; i < count; i++) {
      var row = (DynamicMessage) rows.get(i);
      assertEquals(ids.get(i), Gameplay46.number(row, "id"));
      assertEquals("MissionStatus_MissionNone", Gameplay46.value(row, "status").toString());
      assertTrue(((List<?>) Gameplay46.value(row, "custom_value_list")).isEmpty());
    }
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
  }

  @Test void oversizedBatchRemainsBoundedAndReturnsNoPartialSnapshot() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    send(session, ids(4097));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(session.response, "retcode"));
    assertTrue(((List<?>) Gameplay46.value(session.response, "main_mission_list")).isEmpty());
    assertTrue(session.player.getGameplay().getMainMissions().isEmpty());
  }

  @Test void largerBatchPreservesExistingStatusAndDeduplicatesWithoutAdvancingAnything() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    var ids = ids(137);
    int id = ids.getLast();
    var state = new QuestState(id);
    session.player.getGameplay().getMainMissions().put(id, state);
    ids.add(id);
    var before = new Gson().toJson(session.player.getGameplay());
    send(session, ids);
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    var rows = (List<?>) Gameplay46.value(session.response, "main_mission_list");
    assertEquals(137, rows.size());
    assertEquals("MissionStatus_MissionDoing",
        Gameplay46.value((DynamicMessage) rows.getLast(), "status").toString());
    assertSame(state, session.player.getGameplay().getMainMissions().get(id));
    assertEquals(before, new Gson().toJson(session.player.getGameplay()));
  }

  @Test void expandedBatchCannotBypassCorruptSavedStateValidation() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    var ids = ids(137);
    session.player.getGameplay().getMainMissions().put(ids.getLast(), new QuestState(99));
    send(session, ids);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), Gameplay46.number(session.response, "retcode"));
    assertTrue(((List<?>) Gameplay46.value(session.response, "main_mission_list")).isEmpty());
  }

  @Test void unauthenticatedBatchStillReturnsNoPlayerState() throws Exception {
    var session = new Gameplay46HandlerTest.Session();
    session.setState(SessionState.WAITING_FOR_LOGIN);
    send(session, ids(137));
    assertNull(session.response);
  }
}
