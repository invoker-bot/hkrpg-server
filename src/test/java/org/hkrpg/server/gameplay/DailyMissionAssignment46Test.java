package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Authored Manual/Auto source commissions receive a persisted supported daily assignment. */
class DailyMissionAssignment46Test {
  static final int MAIN = DailyMissionAcceptance46Test.FIRST;
  final DailyMissionRepeat46Test repeated = new DailyMissionRepeat46Test();
  com.google.gson.JsonArray originalDailyRows;
  @TempDir Path temp;

  @BeforeEach
  void setup() throws Exception {
    repeated.temp = temp;
    repeated.setup();
    originalDailyRows = repeated.fixture.dailyRows.deepCopy();
    singleSource();
  }

  @AfterEach
  void cleanup() {
    repeated.cleanup();
  }

  void singleSource() throws Exception {
    var rows = new com.google.gson.JsonArray();
    rows.add(repeated.fixture.dailyRows.get(0));
    repeated.fixture.dailyRows = rows;
    repeated.fixture.loadCatalog();
  }

  void assigned() {
    var actor = repeated.fixture.actor;
    var main = actor.getGameplay().getMainMissions().get(MAIN);
    assertNotNull(main);
    assertEquals(QuestState.Phase.DOING, main.getPhase());
    assertEquals(0, main.getProgress());
    assertNotNull(actor.getGameplay().getSubMissions().get(300020101));
    var day = DailyManager.day(DailyMissionRepeat46Test.FIRST_DAY);
    var choices = actor.getGameplay().getDailyMissionSelections().getDays().get(Long.toString(day));
    assertNotNull(choices);
    assertEquals(1, choices.size());
    assertEquals(MAIN, choices.getFirst().getId());
  }

  @Test
  void supportedSourceCommissionIsAssignedWithoutAClientManualAccept() {
    assertFalse(repeated.fixture.actor.getGameplay().getMainMissions().containsKey(MAIN));
    repeated.fixture.actor.getMissionManager().refresh();
    assigned();
    assertEquals(
        "Manual", GameData.getMainMissionExcelMap().get(MAIN).getTakeParam().getFirst().Type());
  }

  @Test
  void repeatedMissionQueriesNeverRerollOrSaveTheSameAssignment() {
    repeated.fixture.actor.getMissionManager().refresh();
    assigned();
    var actor = repeated.fixture.actor;
    int saves = actor.saves;
    String before = repeated.fixture.gson.toJson(actor.getGameplay());
    for (int i = 0; i < 12; i++) actor.getMissionManager().refresh();
    assertEquals(saves, actor.saves);
    assertEquals(before, repeated.fixture.gson.toJson(actor.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {2000116, 1010403})
  void bothTheCommissionAndActivityTutorialUnlocksRemainRequired(int locked) {
    repeated.fixture.actor.getGameplay().getMainMissions().remove(locked);
    int saves = repeated.fixture.actor.saves;
    repeated.fixture.actor.getMissionManager().refresh();
    assertFalse(repeated.fixture.actor.getGameplay().getMainMissions().containsKey(MAIN));
    assertEquals(saves, repeated.fixture.actor.saves);
  }

  @Test
  void settledOriginalCommissionIsAutomaticallyAssignedAgainOnTheNextDay() {
    repeated.complete();
    repeated.clock(DailyMissionRepeat46Test.NEXT_DAY);
    repeated.fixture.actor.getMissionManager().refresh();
    var actor = repeated.fixture.actor;
    assertEquals(
        QuestState.Phase.DOING, actor.getGameplay().getMainMissions().get(MAIN).getPhase());
    assertNotNull(actor.getGameplay().getSubMissions().get(300020101));
    assertEquals(1, actor.getGameplay().getDailyMissionPeriods().getArchives().size());
    assertEquals(5000, actor.getScoin());
    repeated.complete();
    assertEquals(10000, actor.getScoin());
  }

  @Test
  void oldUnfinishedSupportedCommissionIsArchivedBeforeTheNextAssignment() {
    assertEquals(Retcode.SUCC, repeated.fixture.actor.getMissionManager().accept(MAIN));
    repeated.clock(DailyMissionRepeat46Test.NEXT_DAY);
    var actor = repeated.fixture.actor;
    var old = actor.getGameplay().getMainMissions().get(MAIN);
    actor.getMissionManager().refresh();
    assertNotSame(old, actor.getGameplay().getMainMissions().get(MAIN));
    assertSame(old, actor.getGameplay().getDailyMissionPeriods().getExpired().get(
        MAIN + ":" + DailyManager.day(DailyMissionRepeat46Test.FIRST_DAY)).getMain());
    assertTrue(
        actor
            .getGameplay()
            .getDailyMissionSelections()
            .getDays()
            .containsKey(Long.toString(DailyManager.day(DailyMissionRepeat46Test.NEXT_DAY))));
  }

  @Test
  void unsupportedCustomValueGraphIsNotTurnedIntoAnAutomaticAssignment() throws Exception {
    DailyMissionAcceptance46Test.set(
        GameData.getMissionInfos().get(MAIN),
        "missionCustomValueList",
        new emu.lunarcore.data.config.MissionInfo.MissionCustomValueInfo[] {null});
    repeated.fixture.actor.getMissionManager().refresh();
    assertFalse(repeated.fixture.actor.getGameplay().getMainMissions().containsKey(MAIN));
  }

  @Test
  void concurrentQueriesPersistOnlyOneOriginalAssignment() throws Exception {
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var calls = new ArrayList<java.util.concurrent.Callable<Boolean>>();
      for (int i = 0; i < 24; i++)
        calls.add(
            () -> {
              repeated.fixture.actor.getMissionManager().refresh();
              return true;
            });
      for (var result : executor.invokeAll(calls)) assertTrue(result.get());
    }
    assigned();
  }

  @Test
  void sourceGroupsAndPlayerDaySeedProduceStableSelectionsAcrossAllOriginalCandidates()
      throws Exception {
    repeated.fixture.dailyRows = originalDailyRows.deepCopy();
    repeated.fixture.loadCatalog();
    var ids = new HashSet<Integer>();
    for (int uid = 1; uid <= 32; uid++) {
      var first = repeated.fixture.fresh();
      DailyMissionAcceptance46Test.set(first, "uid", 198850000 + uid);
      first.getMissionManager().refresh();
      var day = Long.toString(DailyManager.day(DailyMissionRepeat46Test.FIRST_DAY));
      var choices = first.getGameplay().getDailyMissionSelections().getDays().get(day);
      assertNotNull(choices);
      assertEquals(1, choices.size());
      var selection = choices.getFirst();
      ids.add(selection.getId());
      var original = DailyMissionCatalog.mission(selection.getId());
      assertNotNull(original);
      assertEquals(original.GroupID(), selection.getGroup());
      assertEquals(original.DailyMissionType(), selection.getType());
      var second = repeated.fixture.fresh();
      DailyMissionAcceptance46Test.set(second, "uid", 198850000 + uid);
      second.getMissionManager().refresh();
      assertEquals(
          selection.getId(),
          second.getGameplay().getDailyMissionSelections().getDays().get(day).getFirst().getId());
    }
    assertEquals(
        Set.of(
            MAIN,
            DailyMissionAcceptance46Test.SAME_GROUP,
            DailyMissionAcceptance46Test.OTHER_GROUP),
        ids);
  }

  @Test
  void actualPlayerDocumentReloadKeepsTheAssignmentWithoutAnotherManualAcceptance()
      throws Exception {
    repeated.fixture.actor.getMissionManager().refresh();
    assigned();
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "daily_assignment");
      store.getMapper().map(Player.class, QuestState.class);
      for (int i = 0; i < 2; i++) {
        var base = new Player();
        DailyMissionAcceptance46Test.set(base, "uid", 123456799);
        DailyMissionAcceptance46Test.set(base, "gameplay", repeated.fixture.actor.getGameplay());
        store.save(base);
        var restored =
            store
                .find(Player.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", 123456799))
                .first();
        assertNotNull(restored);
        repeated.fixture.actor = repeated.fixture.fresh();
        DailyMissionAcceptance46Test.set(
            repeated.fixture.actor, "gameplay", restored.getGameplay());
        repeated.fixture.actor.getMissionManager().refresh();
        assigned();
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void nativeMissionEntryQueryCarriesTheNewAssignmentBeforeAnyAcceptRequest() throws Exception {
    var responses = new ArrayList<Map.Entry<String, com.google.protobuf.DynamicMessage>>();
    var session =
        new emu.lunarcore.server.game.GameSession(null) {
          @Override
          public Player getPlayer() {
            return repeated.fixture.actor;
          }

          @Override
          public org.hkrpg.server.protocol.ProtocolBridge getProtocol() {
            return org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
          }

          @Override
          public void sendClientPacket(String name, byte[] bytes) {
            try {
              responses.add(Map.entry(name, getProtocol().clientSchema().parse(name, bytes)));
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          }
        };
    session.setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    assertTrue(
        org.hkrpg.server.protocol.VersionedPacketHandler.handle(
            session, session.getProtocol().clientCommand("GetMissionDataCsReq"), new byte[0]));
    assigned();
    var response = responses.getLast();
    assertEquals("GetMissionDataScRsp", response.getKey());
    assertTrue(response.getValue().getUnknownFields().asMap().isEmpty());
    var field = response.getValue().getDescriptorForType().findFieldByName("main_mission_list");
    var rows = (List<?>) response.getValue().getField(field);
    assertEquals(
        1,
        rows.stream()
            .map(com.google.protobuf.DynamicMessage.class::cast)
            .filter(
                row -> row.getField(row.getDescriptorForType().findFieldByName("id")).equals(MAIN))
            .count());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"tomorrow", "earlierDay", "missingDay", "missingSelection", "damagedArchive"})
  void malformedPeriodBindingCannotRegrantAnAlreadyDeliveredMainReward(String kind)
      throws Exception {
    repeated.complete();
    repeated.clock(DailyMissionRepeat46Test.NEXT_DAY);
    repeated.complete();
    var actor = repeated.fixture.actor;
    var gameplay = actor.getGameplay();
    var periods = gameplay.getDailyMissionPeriods();
    DailyMissionAcceptance46Test.set(
        gameplay.getMainMissions().get(MAIN), "phase", QuestState.Phase.FINISHED);
    long day = DailyManager.day(DailyMissionRepeat46Test.NEXT_DAY);
    switch (kind) {
      case "tomorrow" -> periods.getRunDays().put(MAIN, day + 1);
      case "earlierDay" -> periods.getRunDays().put(MAIN, day - 1);
      case "missingDay" -> periods.getRunDays().put(MAIN, null);
      case "missingSelection" -> {
        gameplay.getDailyMissionSelections().getDays().remove(Long.toString(day));
        DailyMissionAcceptance46Test.set(
            gameplay.getDailyMissionSelections(), "latestDay", day - 1);
      }
      case "damagedArchive" ->
          periods.getArchives().get(MAIN + ":" + (day - 1)).getSubs().put(300020106, null);
      default -> fail(kind);
    }
    int saves = actor.saves;
    String before = repeated.fixture.gson.toJson(gameplay);
    actor.getMissionManager().refresh();
    assertEquals(10000, actor.getScoin());
    assertEquals(saves, actor.saves);
    assertEquals(before, repeated.fixture.gson.toJson(gameplay));
    assertEquals(QuestState.Phase.FINISHED, gameplay.getMainMissions().get(MAIN).getPhase());
  }

  @Test
  void intactPeriodBindingClosesAnAlreadyDeliveredMainRewardWithoutRegranting() throws Exception {
    repeated.complete();
    repeated.clock(DailyMissionRepeat46Test.NEXT_DAY);
    repeated.complete();
    var actor = repeated.fixture.actor;
    DailyMissionAcceptance46Test.set(
        actor.getGameplay().getMainMissions().get(MAIN), "phase", QuestState.Phase.FINISHED);
    actor.getMissionManager().refresh();
    assertEquals(10000, actor.getScoin());
    assertEquals(
        QuestState.Phase.CLOSED, actor.getGameplay().getMainMissions().get(MAIN).getPhase());
  }
}
