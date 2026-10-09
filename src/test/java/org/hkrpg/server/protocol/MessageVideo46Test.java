package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Three original video message graphs; acceptance objectives and player persistence are fixtures.
 */
class MessageVideo46Test {
  static final int MAIN = 199936;
  static final List<Integer> SECTIONS = List.of(1219000, 1219100, 1504201);
  final Map<Integer, GameplayDefinitions.MessageItem> previousItems = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MessageGroup> previousGroups = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MessageSection> previousSections = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> previousSubs = new HashMap<>();
  final Gson gson = new Gson();
  Session session;

  static class Actor extends Player {
    int saves;

    @Override
    public void save() {
      saves++;
    }
  }

  static class Session extends Gameplay46HandlerTest.Session {
    Actor actor = new Actor();

    @Override
    public Player getPlayer() {
      return actor;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    previousItems.putAll(GameplayDefinitions.messageItems);
    previousGroups.putAll(GameplayDefinitions.messageGroups);
    previousSections.putAll(GameplayDefinitions.messageSections);
    previousSubs.putAll(GameplayDefinitions.subs);
    GameplayDefinitions.messageItems.clear();
    GameplayDefinitions.messageGroups.clear();
    GameplayDefinitions.messageSections.clear();
    GameplayDefinitions.subs.clear();
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/message-video-4.6.json"),
            StandardCharsets.UTF_8)) {
      for (var raw : JsonParser.parseReader(reader).getAsJsonArray()) {
        var entry = raw.getAsJsonObject();
        var row = entry.get("row");
        switch (entry.get("kind").getAsString()) {
          case "MessageItemConfig" -> {
            var value = gson.fromJson(row, GameplayDefinitions.MessageItem.class);
            GameplayDefinitions.messageItems.put(value.ID(), value);
          }
          case "MessageSectionConfig" -> {
            var value = gson.fromJson(row, GameplayDefinitions.MessageSection.class);
            GameplayDefinitions.messageSections.put(value.ID(), value);
          }
          case "MessageGroupConfig" -> {
            var value = gson.fromJson(row, GameplayDefinitions.MessageGroup.class);
            GameplayDefinitions.messageGroups.put(value.ID(), value);
          }
          default -> {}
        }
      }
    }
    session = new Session();
    session.actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    for (int section : SECTIONS) {
      var config =
          gson.fromJson(
              "{\"ID\":"
                  + section
                  + ",\"MainMissionID\":"
                  + MAIN
                  + ",\"TakeType\":\"Auto\",\"FinishType\":\"MessagePerformSectionFinish\",\"ParamType\":\"Equal\",\"ParamInt1\":"
                  + section
                  + ",\"Progress\":1}",
              MissionInfo.SubMissionInfo.class);
      GameplayDefinitions.subs.put(section, config);
    }
    session.actor.getMissionManager().refresh();
    assertEquals(new HashSet<>(SECTIONS), session.actor.getGameplay().getPhoneSections().keySet());
  }

  @AfterEach
  void cleanup() {
    GameplayDefinitions.messageItems.clear();
    GameplayDefinitions.messageItems.putAll(previousItems);
    GameplayDefinitions.messageGroups.clear();
    GameplayDefinitions.messageGroups.putAll(previousGroups);
    GameplayDefinitions.messageSections.clear();
    GameplayDefinitions.messageSections.putAll(previousSections);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(previousSubs);
  }

  List<Integer> path(int section) {
    var result = new ArrayList<Integer>();
    var next = GameplayDefinitions.messageSections.get(section).StartMessageItemIDList();
    var seen = new HashSet<Integer>();
    while (!next.isEmpty()) {
      int id = next.getFirst();
      assertTrue(seen.add(id));
      result.add(id);
      next = GameplayDefinitions.messageItems.get(id).NextItemIDList();
    }
    return result;
  }

  int video(int section) {
    return path(section).stream()
        .filter(id -> "Video".equals(GameplayDefinitions.messageItems.get(id).ItemType()))
        .findFirst()
        .orElseThrow();
  }

  List<Integer> report(int section, boolean includeVideo) {
    return path(section).stream()
        .filter(
            id -> {
              var row = GameplayDefinitions.messageItems.get(id);
              return "Player".equals(row.Sender())
                  || includeVideo && "Video".equals(row.ItemType());
            })
        .toList();
  }

  String saved() {
    return gson.toJson(session.actor.getGameplay());
  }

  void unchanged(String before, int saves) {
    assertEquals(before, saved());
    assertEquals(saves, session.actor.saves);
    for (int section : SECTIONS)
      assertFalse(session.actor.getGameplay().getPhoneSections().get(section).isFinished());
  }

  @ParameterizedTest
  @ValueSource(ints = {1219000, 1219100, 1504201})
  void omittedOriginalVideoCannotCompleteHistoryOrAcceptedObjective(int section) {
    String before = saved();
    int saves = session.actor.saves;
    assertEquals(
        Retcode.MESSAGE_SECTION_CAN_NOT_FINISH,
        new MessageManager(session.actor).finishPerformance(section, report(section, false)));
    unchanged(before, saves);
    assertEquals(0, session.actor.getMissionManager().sub(section).getProgress());
  }

  @Test
  void terminalNpcVideoWaitsForItsActualCompletionReport() {
    var state = session.actor.getGameplay().getPhoneSections().get(1219100);
    assertEquals(List.of(121910005), state.getOffered());
    assertFalse(state.getHistory().contains(121910005));
    assertEquals(path(1219100).subList(0, 5), state.getHistory());
  }

  @ParameterizedTest
  @ValueSource(ints = {1219000, 1219100, 1504201})
  void sourceOrderedRepliesAndVideoCompleteTheActualGraphOnce(int section) {
    var manager = new MessageManager(session.actor);
    assertEquals(Retcode.SUCC, manager.finishPerformance(section, report(section, true)));
    var state = session.actor.getGameplay().getPhoneSections().get(section);
    assertEquals(path(section), state.getHistory());
    assertTrue(state.getOffered().isEmpty());
    assertTrue(state.isFinished());
    assertEquals(
        QuestState.Phase.FINISHED, session.actor.getMissionManager().sub(section).getPhase());
    int saves = session.actor.saves;
    String before = saved();
    assertEquals(
        Retcode.MESSAGE_SECTION_CAN_NOT_FINISH,
        manager.finishPerformance(section, report(section, true)));
    assertEquals(before, saved());
    assertEquals(saves, session.actor.saves);
    assertTrue(session.actor.getGameplay().getRewards().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {1219000, 1219100, 1504201})
  void anotherSectionsVideoCannotReplaceTheAuthoredVideo(int section) {
    int other = SECTIONS.stream().filter(id -> id != section).findFirst().orElseThrow();
    var values = new ArrayList<>(report(section, true));
    values.set(values.indexOf(video(section)), video(other));
    String before = saved();
    int saves = session.actor.saves;
    assertEquals(
        Retcode.MESSAGE_SECTION_CAN_NOT_FINISH,
        new MessageManager(session.actor).finishPerformance(section, values));
    unchanged(before, saves);
  }

  @Test
  void missingVideoInSecondSectionRejectsTheWholeBatchBeforeSaving() {
    var submissions = new LinkedHashMap<Integer, List<Integer>>();
    submissions.put(1219000, report(1219000, true));
    submissions.put(1219100, report(1219100, false));
    String before = saved();
    int saves = session.actor.saves;
    assertEquals(
        Retcode.MESSAGE_SECTION_CAN_NOT_FINISH,
        new MessageManager(session.actor).finishPerformances(submissions));
    unchanged(before, saves);
  }

  @Test
  void jsonRestoredTerminalVideoStillRequiresItsRecordAndDoesNotInventCompletion()
      throws Exception {
    var restored = gson.fromJson(saved(), GameplayState.class);
    var actor = new Actor();
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(actor, restored);
    session.actor = actor;
    assertEquals(
        Retcode.MESSAGE_SECTION_CAN_NOT_FINISH,
        new MessageManager(actor).finishPerformance(1219100, List.of()));
    assertEquals(
        Retcode.SUCC, new MessageManager(actor).finishPerformance(1219100, List.of(121910005)));
    var copy = gson.fromJson(saved(), GameplayState.class);
    assertTrue(copy.getPhoneSections().get(1219100).isFinished());
    assertEquals(path(1219100), copy.getPhoneSections().get(1219100).getHistory());
  }

  @ParameterizedTest
  @ValueSource(ints = {1219000, 1219100, 1504201})
  void everyLastAuthoredReplyBranchAlsoKeepsItsOriginalVideo(int section) {
    var path = new ArrayList<Integer>();
    var next = GameplayDefinitions.messageSections.get(section).StartMessageItemIDList();
    while (!next.isEmpty()) {
      int id = next.getLast();
      assertFalse(path.contains(id));
      path.add(id);
      next = GameplayDefinitions.messageItems.get(id).NextItemIDList();
    }
    var records =
        path.stream()
            .filter(
                id -> {
                  var row = GameplayDefinitions.messageItems.get(id);
                  return "Player".equals(row.Sender()) || "Video".equals(row.ItemType());
                })
            .toList();
    assertEquals(
        Retcode.SUCC, new MessageManager(session.actor).finishPerformance(section, records));
    assertEquals(path, session.actor.getGameplay().getPhoneSections().get(section).getHistory());
    assertEquals(1, session.actor.getMissionManager().sub(section).getProgress());
  }

  @Test
  void oneValidBatchCompletesAllThreeOriginalGraphsAndPersistsTheirExactPaths() {
    var submissions = new LinkedHashMap<Integer, List<Integer>>();
    for (int id : SECTIONS) submissions.put(id, report(id, true));
    assertEquals(Retcode.SUCC, new MessageManager(session.actor).finishPerformances(submissions));
    var restored = gson.fromJson(saved(), GameplayState.class);
    for (int id : SECTIONS) {
      assertEquals(path(id), restored.getPhoneSections().get(id).getHistory());
      assertTrue(restored.getPhoneSections().get(id).isFinished());
      assertEquals(1, restored.getSubMissions().get(id).getProgress());
    }
  }

  int nativeReport(int section, boolean includeVideo) throws Exception {
    var req = Gameplay46.builder(session, "FinishPerformSectionIdCsReq");
    var data = Gameplay46.builder(session, "FPDBFGBJOFD");
    Gameplay46.set(data, "MGPGDLIJKLM", section);
    for (int id : report(section, includeVideo)) {
      var entry = Gameplay46.builder(session, "MessageItem");
      Gameplay46.set(entry, "item_id", id);
      Gameplay46.add(data, "DPPAFBAHEFH", entry.build());
    }
    Gameplay46.set(req, "HBMLBLAJKKL", data.build());
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("FinishPerformSectionIdCsReq"),
            req.build().toByteArray()));
    assertEquals("FinishPerformSectionIdScRsp", session.responseName);
    return Gameplay46.number(session.response, "retcode");
  }

  @ParameterizedTest
  @ValueSource(ints = {1219000, 1219100, 1504201})
  void nativePerformanceRepliesRejectOmissionThenAcceptTheExactVideoPath(int section)
      throws Exception {
    String before = saved();
    int saves = session.actor.saves;
    assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(), nativeReport(section, false));
    unchanged(before, saves);
    assertEquals(0, nativeReport(section, true));
    assertTrue(session.actor.getGameplay().getPhoneSections().get(section).isFinished());
    assertEquals(
        path(section), session.actor.getGameplay().getPhoneSections().get(section).getHistory());
    assertNotEquals(0, nativeReport(section, true));
  }
}
