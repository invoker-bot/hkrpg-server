package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original messages and mission gates; prerequisite completions and player level are fixtures. */
class BoxingClubMessages46Test {
  final Gson gson = ResourceLoader.getGson();
  final Map<Integer, GameplayDefinitions.MessageSection> sections = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MessageGroup> groups = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MessageItem> items = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> subs = new HashMap<>();
  final Map<Integer, MissionInfo> headers = new HashMap<>();
  final List<MainMissionExcel> mains = new ArrayList<>();
  final List<RewardExcel> rewards = new ArrayList<>();
  final List<GameplayDefinitions.Window> windows = new ArrayList<>();
  JsonObject source;
  Session session;

  static class Actor extends Player {
    @Override
    public void save() {}
  }

  static class Session extends Gameplay46HandlerTest.Session {
    final Actor actor = new Actor();

    @Override
    public Player getPlayer() {
      return actor;
    }
  }

  @BeforeEach
  void load() throws Exception {
    sections.putAll(GameplayDefinitions.messageSections);
    groups.putAll(GameplayDefinitions.messageGroups);
    items.putAll(GameplayDefinitions.messageItems);
    subs.putAll(GameplayDefinitions.subs);
    headers.putAll(GameData.getMissionInfos());
    mains.addAll(GameData.getMainMissionExcelMap().values());
    rewards.addAll(GameData.getRewardExcelMap().values());
    windows.addAll(GameplayDefinitions.windows);
    GameplayDefinitions.messageSections.clear();
    GameplayDefinitions.messageGroups.clear();
    GameplayDefinitions.messageItems.clear();
    GameplayDefinitions.subs.clear();
    GameData.getMissionInfos().clear();
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameplayDefinitions.windows.clear();
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/boxing-club-messages-4.6.json"),
            StandardCharsets.UTF_8)) {
      source = JsonParser.parseReader(reader).getAsJsonObject();
    }
    for (var raw : source.getAsJsonArray("sections")) {
      var value = gson.fromJson(raw, GameplayDefinitions.MessageSection.class);
      GameplayDefinitions.messageSections.put(value.ID(), value);
    }
    for (var raw : source.getAsJsonArray("groups")) {
      var value = gson.fromJson(raw, GameplayDefinitions.MessageGroup.class);
      GameplayDefinitions.messageGroups.put(value.ID(), value);
    }
    for (var raw : source.getAsJsonArray("items")) {
      var value = gson.fromJson(raw, GameplayDefinitions.MessageItem.class);
      GameplayDefinitions.messageItems.put(value.ID(), value);
    }
    for (var raw : source.getAsJsonArray("subs")) {
      var value = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      if (value.getId() == 201070201) {
        var task = gson.fromJson(source.get("talkScript"), MissionTaskInfo.class);
        task.retainSourceDefinition(source.get("talkScript"));
        value.setTask(task);
      }
      GameplayDefinitions.subs.put(value.getId(), value);
    }
    for (var raw : source.getAsJsonArray("headers")) {
      var value = gson.fromJson(raw, MissionInfo.class);
      GameData.getMissionInfos().put(value.getMainMissionID(), value);
    }
    for (var raw : source.getAsJsonArray("mainRows"))
      GameData.getMainMissionExcelMap().add(gson.fromJson(raw, MainMissionExcel.class));
    GameData.getRewardExcelMap().add(gson.fromJson(source.get("rewardRow"), RewardExcel.class));
    GameplayDefinitions.windows.add(new GameplayDefinitions.Window(40003, 4000301, 1, 4102444800L));
    session = new Session();
    for (var entry : Map.of("level", 21, "planeId", 10102, "floorId", 10102001).entrySet()) {
      var field = Player.class.getDeclaredField(entry.getKey());
      field.setAccessible(true);
      field.setInt(session.actor, entry.getValue());
    }
    for (int id : List.of(1011001, 2010720, 1011503, 1010302)) {
      var state = new QuestState(id);
      state.advance(1, 1, 1);
      state.reserveReward();
      state.closeReward();
      session.actor.getGameplay().getMainMissions().put(id, state);
    }
  }

  @AfterEach
  void restore() {
    GameplayDefinitions.messageSections.clear();
    GameplayDefinitions.messageSections.putAll(sections);
    GameplayDefinitions.messageGroups.clear();
    GameplayDefinitions.messageGroups.putAll(groups);
    GameplayDefinitions.messageItems.clear();
    GameplayDefinitions.messageItems.putAll(items);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(subs);
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(headers);
    GameData.getMainMissionExcelMap().values().clear();
    mains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    rewards.forEach(GameData.getRewardExcelMap()::add);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.addAll(windows);
  }

  int request(String name, Map<String, Object> values) throws Exception {
    var builder = Gameplay46.builder(session, name);
    values.forEach((key, value) -> Gameplay46.set(builder, key, value));
    assertTrue(
        VersionedPacketHandler.handle(
            session, session.getProtocol().clientCommand(name), builder.build().toByteArray()));
    assertEquals(name.replace("CsReq", "ScRsp"), session.responseName);
    return Gameplay46.number(session.response, "retcode");
  }

  void open() throws Exception {
    session.actor.getMissionManager().refresh();
    assertTrue(session.actor.getGameplay().getPhoneSections().containsKey(1110000));
    assertTrue(session.actor.getGameplay().getPhoneSections().containsKey(1301100));
    assertFalse(session.actor.getGameplay().getPhoneSections().containsKey(1110700));
    assertEquals(
        0,
        request(
            "FinishTalkMissionCsReq",
            Map.of("sub_mission_id", 201070201, "talk_str", "Mission_201070201")));
    assertEquals(
        QuestState.Phase.FINISHED, session.actor.getMissionManager().sub(201070201).getPhase());
    assertEquals(
        QuestState.Phase.DOING, session.actor.getMissionManager().sub(201070202).getPhase());
    assertTrue(session.actor.getGameplay().getPhoneSections().containsKey(1110700));
    // The resource points the section at 2010703; its actual accepted goal belongs to 2010702.
    assertNull(session.actor.getGameplay().getMainMissions().get(2010703));
  }

  @ParameterizedTest
  @CsvSource({
    "1110000,1,0",
    "1110000,2,0",
    "1110000,3,0",
    "1110700,1,6",
    "1110700,1,7",
    "1110700,2,6",
    "1110700,2,7",
    "1301100,2,0",
    "1301100,3,0"
  })
  void everyAuthoredBranchCompletesOnlyItsAcceptedMessageGoal(int section, int first, int second)
      throws Exception {
    open();
    var state = session.actor.getGameplay().getPhoneSections().get(section);
    assertEquals(
        Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(),
        request("FinishSectionIdCsReq", Map.of("section_id", section)));
    assertEquals(0, request("FinishItemIdCsReq", Map.of("item_id", section * 100 + first)));
    if (second != 0) {
      assertEquals(
          Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(),
          request("FinishSectionIdCsReq", Map.of("section_id", section)));
      assertEquals(0, request("FinishItemIdCsReq", Map.of("item_id", section * 100 + second)));
    }
    var expected = new ArrayList<Integer>();
    var next = GameplayDefinitions.messageSections.get(section).StartMessageItemIDList();
    while (!next.isEmpty()) {
      int id =
          next.size() == 1
              ? next.getFirst()
              : expected.contains(section * 100 + first)
                  ? section * 100 + second
                  : section * 100 + first;
      assertTrue(next.contains(id));
      assertFalse(expected.contains(id));
      expected.add(id);
      next = GameplayDefinitions.messageItems.get(id).NextItemIDList();
    }
    assertEquals(0, request("FinishSectionIdCsReq", Map.of("section_id", section)));
    var finished = session.actor.getGameplay().getPhoneSections().get(section);
    assertEquals(expected, finished.getHistory());
    assertTrue(finished.isFinished());
    assertTrue(finished.getOffered().isEmpty());
    assertFalse(state.isFinished(), "A completed copy must not mutate an earlier snapshot");
    int sub = Map.of(1110000, 201070100, 1110700, 201070202, 1301100, 801520201).get(section);
    assertEquals(1, session.actor.getMissionManager().sub(sub).getProgress());
    assertEquals(QuestState.Phase.FINISHED, session.actor.getMissionManager().sub(sub).getPhase());
    for (int other : List.of(201070100, 201070202, 801520201))
      if (other != sub) assertEquals(0, session.actor.getMissionManager().sub(other).getProgress());
    assertEquals(
        Retcode.MESSAGE_SECTION_CAN_NOT_FINISH.getVal(),
        request("FinishSectionIdCsReq", Map.of("section_id", section)));
    assertEquals(0, session.actor.getScoin());
  }

  @Test
  void closedActivityWindowDoesNotPublishItsSeasonTwoPhoneGraph() {
    GameplayDefinitions.windows.clear();
    session.actor.getMissionManager().refresh();
    assertTrue(session.actor.getGameplay().getPhoneSections().containsKey(1110000));
    assertFalse(session.actor.getGameplay().getPhoneSections().containsKey(1301100));
    assertEquals(
        Retcode.MESSAGE_SECTION_NOT_TAKE, new MessageManager(session.actor).choose(130110002, 0));
  }

  @Test
  void missingPrerequisitesLeaveAllOriginalMessagesUnaccepted() {
    session.actor.getGameplay().getMainMissions().clear();
    session.actor.getMissionManager().refresh();
    assertTrue(session.actor.getGameplay().getPhoneSections().isEmpty());
    assertTrue(session.actor.getGameplay().getSubMissions().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {1110000, 1110700, 1301100})
  void anotherSectionsReplyAndAnUnreachableSystemItemCannotAdvanceTheAcceptedGraph(int section)
      throws Exception {
    open();
    int other = section == 1110000 ? 1110700 : 1110000;
    String before = gson.toJson(session.actor.getGameplay().getPhoneSections().get(section));
    assertEquals(
        Retcode.MESSAGE_ITEM_CAN_NOT_FINISH,
        new MessageManager(session.actor)
            .choose(
                Map.of(1110000, 111000007, 1110700, 111070009, 1301100, 130110008).get(section),
                0));
    assertNotEquals(Retcode.SUCC, new MessageManager(session.actor).choose(other * 100 + 8, 0));
    assertEquals(
        Retcode.MESSAGE_ITEM_CAN_NOT_FINISH,
        new MessageManager(session.actor).choose(section == 1110000 ? 999990041 : 999990048, 0));
    assertEquals(before, gson.toJson(session.actor.getGameplay().getPhoneSections().get(section)));
  }
}
