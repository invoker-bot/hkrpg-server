package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;

class Message46Test {
  static final int MAIN = 199929, NORMAL = 1999291, PERF = 1999292, CONTACT = 199929;
  Gameplay46HandlerTest.Session session;

  @BeforeEach
  void setup() {
    session = new Gameplay46HandlerTest.Session();
    for (int id : List.of(NORMAL, PERF)) {
      GameplayDefinitions.messageSections.put(
          id, new GameplayDefinitions.MessageSection(id, id == PERF, 0, List.of(id * 10)));
      GameplayDefinitions.messageGroups.put(
          id, new GameplayDefinitions.MessageGroup(id, CONTACT, 0, List.of(id)));
      GameplayDefinitions.messageItems.put(
          id * 10,
          new GameplayDefinitions.MessageItem(
              id * 10, id, "NPC", "Text", List.of(id * 10 + 1, id * 10 + 2)));
      for (int choice : List.of(1, 2))
        GameplayDefinitions.messageItems.put(
            id * 10 + choice,
            new GameplayDefinitions.MessageItem(
                id * 10 + choice, id, "Player", "Text", List.of(id * 10 + 3)));
      GameplayDefinitions.messageItems.put(
          id * 10 + 3,
          new GameplayDefinitions.MessageItem(id * 10 + 3, id, "NPC", "Text", List.of()));
      var c =
          new com.google.gson.Gson()
              .fromJson(
                  "{\"ID\":"
                      + id
                      + ",\"MainMissionID\":"
                      + MAIN
                      + ",\"TakeType\":\"Auto\",\"ParamType\":\"Equal\",\"ParamInt1\":"
                      + id
                      + ",\"FinishType\":\""
                      + (id == PERF ? "MessagePerformSectionFinish" : "MessageSectionFinish")
                      + "\"}",
                  MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(id, c);
    }
  }

  @AfterEach
  void cleanup() {
    for (int id : List.of(NORMAL, PERF)) {
      GameplayDefinitions.messageSections.remove(id);
      GameplayDefinitions.messageGroups.remove(id);
      GameplayDefinitions.subs.remove(id);
      for (int i = 0; i <= 3; i++) GameplayDefinitions.messageItems.remove(id * 10 + i);
    }
  }

  void open() {
    session.player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    session.player.getMissionManager().refresh();
  }

  DynamicMessage request(String name, Map<String, Object> values) throws Exception {
    var b = Gameplay46.builder(session, name);
    values.forEach((k, v) -> Gameplay46.set(b, k, v));
    assertTrue(
        VersionedPacketHandler.handle(
            session, session.getProtocol().clientCommand(name), b.build().toByteArray()));
    return session.response;
  }

  int finish(int id) throws Exception {
    return Gameplay46.number(request("FinishSectionIdCsReq", Map.of("section_id", id)), "retcode");
  }

  int choose(int id, int text) throws Exception {
    return Gameplay46.number(
        request("FinishItemIdCsReq", Map.of("item_id", id, "text_id", text)), "retcode");
  }

  int perform(List<Integer> ids) throws Exception {
    var data = Gameplay46.builder(session, "FPDBFGBJOFD");
    Gameplay46.set(data, "MGPGDLIJKLM", PERF);
    for (int id : ids) {
      var item = Gameplay46.builder(session, "MessageItem");
      Gameplay46.set(item, "item_id", id);
      Gameplay46.add(data, "DPPAFBAHEFH", item.build());
    }
    return Gameplay46.number(
        request("FinishPerformSectionIdCsReq", Map.of("HBMLBLAJKKL", data.build())), "retcode");
  }

  @Test
  void aMultiSectionMissionActivatesItsListedPerformancesAndCountsOnlyFinishedSections()
      throws Exception {
    int multi = 1999299;
    var normal = GameplayDefinitions.messageSections.get(NORMAL);
    GameplayDefinitions.messageSections.put(
        NORMAL,
        new GameplayDefinitions.MessageSection(NORMAL, true, 0, normal.StartMessageItemIDList()));
    GameplayDefinitions.subs.remove(NORMAL);
    GameplayDefinitions.subs.remove(PERF);
    var row =
        new com.google.gson.Gson()
            .fromJson(
                "{\"ID\":"
                    + multi
                    + ",\"MainMissionID\":"
                    + MAIN
                    + ",\"TakeType\":\"Auto\",\"FinishType\":\"MultiMessagePerformSectionFinish\",\"ParamType\":\"ListContain\",\"ParamIntList\":["
                    + NORMAL
                    + ","
                    + PERF
                    + "],\"Progress\":2}",
                MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(multi, row);
    try {
      open();
      assertTrue(session.player.getGameplay().getPhoneSections().containsKey(NORMAL));
      assertTrue(session.player.getGameplay().getPhoneSections().containsKey(PERF));
      assertEquals(0, multi(Map.of(NORMAL, List.of(NORMAL * 10 + 1))));
      assertEquals(1, session.player.getMissionManager().sub(multi).getProgress());
      session.player.getMissionManager().refresh();
      assertEquals(1, session.player.getMissionManager().sub(multi).getProgress());
      assertNotEquals(0, multi(Map.of(PERF, List.of(123))));
      assertEquals(1, session.player.getMissionManager().sub(multi).getProgress());
      assertEquals(0, multi(Map.of(PERF, List.of(PERF * 10 + 2))));
      assertEquals(
          QuestState.Phase.FINISHED, session.player.getMissionManager().sub(multi).getPhase());
    } finally {
      GameplayDefinitions.subs.remove(multi);
    }
  }

  @Test
  void emptyPhoneQueryDoesNotInventFinishedContacts() throws Exception {
    session.request("GetNpcStatusCsReq");
    assertEquals("GetNpcStatusScRsp", session.responseName);
    assertEquals(0, Gameplay46.number(session.response, "retcode"));
    assertTrue(
        ((java.util.List<?>) Gameplay46.value(session.response, "npc_status_list")).isEmpty());
    assertTrue(session.player.getGameplay().getMainMissions().isEmpty());
  }

  @Test
  void unopenedAndIncompleteSectionsCannotSkipTheReplyBranch() throws Exception {
    assertNotEquals(0, finish(NORMAL));
    assertTrue(session.player.getGameplay().getPhoneSections().isEmpty());
    open();
    var state = session.player.getGameplay().getPhoneSections().get(NORMAL);
    assertEquals(List.of(NORMAL * 10), state.getHistory());
    assertEquals(List.of(NORMAL * 10 + 1, NORMAL * 10 + 2), state.getOffered());
    assertNotEquals(0, finish(NORMAL));
    assertNotEquals(0, choose(NORMAL * 10 + 3, 0));
    assertNotEquals(0, choose(NORMAL * 10 + 2, 1));
    assertEquals(0, choose(NORMAL * 10 + 2, 0));
    assertEquals(0, finish(NORMAL));
    assertEquals(
        QuestState.Phase.FINISHED, session.player.getMissionManager().sub(NORMAL).getPhase());
    assertEquals(0, session.player.getMissionManager().sub(PERF).getProgress());
    assertNotEquals(0, finish(NORMAL));
    var req = Gameplay46.builder(session, "GetNpcMessageGroupCsReq");
    Gameplay46.add(req, "BJIPBBFIBPD", CONTACT);
    VersionedPacketHandler.handle(
        session,
        session.getProtocol().clientCommand("GetNpcMessageGroupCsReq"),
        req.build().toByteArray());
    var group = InitialData46Test.first(session.response, "message_group_list");
    var section = InitialData46Test.first(group, "message_section_list");
    var history = (List<?>) Gameplay46.value(section, "item_list");
    assertEquals(
        List.of(NORMAL * 10, NORMAL * 10 + 2, NORMAL * 10 + 3),
        history.stream().map(v -> Gameplay46.number((DynamicMessage) v, "item_id")).toList());
    assertEquals(
        "MessageSectionStatus_MessageSectionFinish",
        Gameplay46.value(section, "status").toString());
    assertTrue(session.player.getGameplay().getRewards().isEmpty());
  }

  @Test
  void performanceChoicesAreValidatedBeforeAnyHistoryOrMissionIsChanged() throws Exception {
    open();
    var initial = session.player.getGameplay().getPhoneSections().get(PERF).copy();
    assertNotEquals(0, perform(List.of()));
    assertNotEquals(0, perform(List.of(NORMAL * 10 + 1)));
    assertNotEquals(0, perform(List.of(PERF * 10 + 3, PERF * 10 + 1)));
    assertNotEquals(0, perform(List.of(PERF * 10 + 1, PERF * 10 + 1)));
    assertEquals(
        initial.getHistory(),
        session.player.getGameplay().getPhoneSections().get(PERF).getHistory());
    assertEquals(
        initial.getOffered(),
        session.player.getGameplay().getPhoneSections().get(PERF).getOffered());
    assertEquals(0, session.player.getMissionManager().sub(PERF).getProgress());
    assertEquals(0, perform(List.of(PERF * 10 + 1)));
    assertEquals(
        List.of(PERF * 10, PERF * 10 + 1, PERF * 10 + 3),
        session.player.getGameplay().getPhoneSections().get(PERF).getHistory());
    assertEquals(
        QuestState.Phase.FINISHED, session.player.getMissionManager().sub(PERF).getPhase());
    session.request("GetMissionMessageCsReq");
    assertEquals(List.of(PERF), Gameplay46.value(session.response, "HNIPIODEBNN"));
    assertNotEquals(0, perform(List.of(PERF * 10 + 1)));
    assertTrue(session.player.getGameplay().getRewards().isEmpty());
  }

  @Test
  void invalidGraphsAndCancelledObjectivesNeverBecomeCompletableMessages() throws Exception {
    var original = GameplayDefinitions.messageItems.get(NORMAL * 10 + 3);
    GameplayDefinitions.messageItems.put(
        original.ID(),
        new GameplayDefinitions.MessageItem(
            original.ID(), NORMAL, "NPC", "Text", List.of(NORMAL * 10)));
    open();
    assertFalse(session.player.getGameplay().getPhoneSections().containsKey(NORMAL));
    GameplayDefinitions.messageItems.put(original.ID(), original);
    session.player.getMissionManager().refresh();
    assertTrue(session.player.getGameplay().getPhoneSections().containsKey(NORMAL));
    session.player.getGameplay().getCancelledSubs().add(NORMAL);
    assertNotEquals(0, choose(NORMAL * 10 + 1, 0));
    session.player.getGameplay().getCancelledMissions().add(MAIN);
    assertNotEquals(0, perform(List.of(PERF * 10 + 1)));
    assertEquals(0, session.player.getMissionManager().sub(PERF).getProgress());
  }

  int multi(Map<Integer, List<Integer>> submissions) throws Exception {
    var req = Gameplay46.builder(session, "FinishMultiPerformSectionIdCsReq");
    for (var entry : submissions.entrySet()) {
      var data = Gameplay46.builder(session, "FPDBFGBJOFD");
      Gameplay46.set(data, "MGPGDLIJKLM", entry.getKey());
      for (int id : entry.getValue()) {
        var item = Gameplay46.builder(session, "MessageItem");
        Gameplay46.set(item, "item_id", id);
        Gameplay46.add(data, "DPPAFBAHEFH", item.build());
      }
      Gameplay46.add(req, "CGKBNIMCFKH", data.build());
    }
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("FinishMultiPerformSectionIdCsReq"),
            req.build().toByteArray()));
    return Gameplay46.number(session.response, "PLDKENJLKME");
  }

  @Test
  void aBadSectionInTheBatchCannotPartlyCompleteOtherPerformances() throws Exception {
    var normal = GameplayDefinitions.messageSections.get(NORMAL);
    GameplayDefinitions.messageSections.put(
        NORMAL,
        new GameplayDefinitions.MessageSection(NORMAL, true, 0, normal.StartMessageItemIDList()));
    GameplayDefinitions.subs.get(NORMAL).setFinishType("MessagePerformSectionFinish");
    open();
    assertNotEquals(0, multi(Map.of(NORMAL, List.of(NORMAL * 10 + 1), PERF, List.of(12345))));
    assertFalse(session.player.getGameplay().getPhoneSections().get(NORMAL).isFinished());
    assertEquals(0, session.player.getMissionManager().sub(NORMAL).getProgress());
    assertEquals(0, multi(Map.of(NORMAL, List.of(NORMAL * 10 + 1), PERF, List.of(PERF * 10 + 2))));
    assertEquals(2, ((List<?>) Gameplay46.value(session.response, "JCALNIODLLN")).size());
    assertEquals(
        QuestState.Phase.FINISHED, session.player.getMissionManager().sub(NORMAL).getPhase());
    assertEquals(
        QuestState.Phase.FINISHED, session.player.getMissionManager().sub(PERF).getPhase());
  }
}
