package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual original source talks, with explicit tutorial, position and clock fixtures. */
class DailyMissionExpiry46Test {
  static final int MAIN = DailyMissionAcceptance46Test.FIRST;
  static final Instant RESET = Instant.parse("2026-10-05T20:00:00Z");
  final DailyMissionAssignment46Test assigned = new DailyMissionAssignment46Test();
  @TempDir Path temp;

  @BeforeEach
  void setup() throws Exception {
    assigned.temp = temp;
    assigned.setup();
  }

  @AfterEach
  void cleanup() {
    assigned.cleanup();
  }

  DailyMissionAcceptance46Test.Actor actor() {
    return assigned.repeated.fixture.actor;
  }

  void clock(Instant time) {
    assigned.repeated.clock(time);
  }

  String saved() {
    return assigned.repeated.fixture.gson.toJson(actor().getGameplay());
  }

  long firstDay() {
    return DailyManager.day(DailyMissionRepeat46Test.FIRST_DAY);
  }

  String expiredKey() {
    return MAIN + ":" + firstDay();
  }

  void begin() {
    actor().getMissionManager().refresh();
    assigned.assigned();
  }

  void fourTalks() {
    begin();
    for (int suffix : List.of(101, 103, 104, 105)) {
      int id = 300020000 + suffix;
      assertEquals(Retcode.SUCC, actor().getMissionManager().finishTalk(id, "Mission_" + id));
    }
  }

  void renewed() {
    var g = actor().getGameplay();
    var old = g.getDailyMissionPeriods().getExpired().get(expiredKey());
    assertNotNull(old);
    assertEquals(QuestState.Phase.DOING, old.getMain().getPhase());
    assertEquals(0, old.getMain().getProgress());
    assertEquals(0, old.getMain().getFinishTime());
    assertEquals(RESET.getEpochSecond(), old.getExpiredAt());
    assertNotSame(old.getMain(), g.getMainMissions().get(MAIN));
    assertEquals(QuestState.Phase.DOING, g.getMainMissions().get(MAIN).getPhase());
    assertEquals(0, g.getSubMissions().get(300020101).getProgress());
    assertFalse(g.getSubMissions().containsKey(300020106));
    assertEquals(DailyManager.day(RESET), g.getDailyMissionPeriods().getRunDays().get(MAIN));
    assertEquals(
        MAIN,
        g.getDailyMissionSelections()
            .getDays()
            .get(Long.toString(DailyManager.day(RESET)))
            .getFirst()
            .getId());
    assertEquals(0, actor().getScoin());
  }

  @Test
  void untouchedCommissionGetsANewViewExactlyAtChineseReset() {
    begin();
    var old = actor().getGameplay().getMainMissions().get(MAIN);
    clock(RESET);
    actor().getMissionManager().refresh();
    renewed();
    assertSame(
        old,
        actor().getGameplay().getDailyMissionPeriods().getExpired().get(expiredKey()).getMain());
  }

  @Test
  void partialFiveTalkGraphArchivesItsOriginalObjectsAndResetsOnlyItsOwnView() {
    fourTalks();
    var g = actor().getGameplay();
    var oldMain = g.getMainMissions().get(MAIN);
    var oldTalk = g.getSubMissions().get(300020101);
    var oldAggregate = g.getSubMissions().get(300020102);
    var tutorial = g.getMainMissions().get(1010403);
    clock(RESET);
    actor().getMissionManager().refresh();
    renewed();
    var archive = g.getDailyMissionPeriods().getExpired().get(expiredKey());
    assertSame(oldMain, archive.getMain());
    assertSame(oldTalk, archive.getSubs().get(300020101));
    assertSame(oldAggregate, archive.getSubs().get(300020102));
    assertEquals(3, archive.getSubs().get(300020102).getProgress());
    assertEquals(6, archive.getGoals().size());
    assertEquals(3, archive.getGoals().get(300020102));
    assertSame(tutorial, g.getMainMissions().get(1010403));
    assertTrue(g.getRewards().isEmpty());
    assertTrue(g.getMissionRewardPlans().isEmpty());
  }

  @Test
  void finalOldTalkAtNewDayIsRejectedBeforeAnyMutationOrReward() {
    fourTalks();
    clock(RESET);
    int saves = actor().saves;
    String before = saved();
    assertEquals(
        Retcode.MISSION_NOT_DOING,
        actor().getMissionManager().finishTalk(300020106, "Mission_300020106"));
    assertEquals(before, saved());
    assertEquals(saves, actor().saves);
    assertEquals(0, actor().getScoin());
  }

  @Test
  void staleAcceptCannotMoveTheOldViewIntoANewQuotaPeriod() {
    begin();
    clock(RESET);
    int saves = actor().saves;
    String before = saved();
    assertEquals(Retcode.MISSION_NOT_DOING, actor().getMissionManager().accept(MAIN));
    assertEquals(before, saved());
    assertEquals(saves, actor().saves);
  }

  @Test
  void archivedUnpaidProgressCannotFinishOrPayTheNewRound() {
    fourTalks();
    clock(RESET);
    actor().getMissionManager().refresh();
    renewed();
    assertEquals(
        Retcode.MISSION_NOT_DOING,
        actor().getMissionManager().finishTalk(300020106, "Mission_300020106"));
    assigned.repeated.complete();
    assertEquals(5000, actor().getScoin());
    assertEquals(Retcode.SUCC, actor().getDailyManager().claimQuest(2100003).retcode());
    assertEquals(200, actor().getGameplay().getDailyPoints());
    assertFalse(actor().getGameplay().getRewards().containsKey("mission:" + MAIN));
    assertTrue(
        actor()
            .getGameplay()
            .getRewards()
            .get("daily-mission:" + DailyManager.day(RESET) + ":mission:" + MAIN)
            .isGranted());
    actor().getMissionManager().refresh();
    assertEquals(5000, actor().getScoin());
  }

  @Test
  void aPreviouslyPaidRoundAndAnExpiredRoundKeepSeparateProofs() {
    assigned.repeated.complete();
    assertEquals(5000, actor().getScoin());
    var receipt = actor().getGameplay().getRewards().get("mission:" + MAIN);
    clock(RESET);
    actor().getMissionManager().refresh();
    assertEquals(
        Retcode.SUCC, actor().getMissionManager().finishTalk(300020101, "Mission_300020101"));
    clock(RESET.plusSeconds(86400));
    actor().getMissionManager().refresh();
    assertEquals(1, actor().getGameplay().getDailyMissionPeriods().getArchives().size());
    assertEquals(1, actor().getGameplay().getDailyMissionPeriods().getExpired().size());
    assertEquals(0, actor().getGameplay().getSubMissions().get(300020101).getProgress());
    assertSame(receipt, actor().getGameplay().getRewards().get("mission:" + MAIN));
    assigned.repeated.complete();
    assertEquals(10000, actor().getScoin());
  }

  @Test
  void repeatedAndConcurrentResetQueriesArchiveAndInstallOnlyOneRound() throws Exception {
    fourTalks();
    clock(RESET);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 24; i++)
        futures.add(pool.submit(() -> actor().getMissionManager().refresh()));
      for (var task : futures) task.get();
    }
    renewed();
    int saves = actor().saves;
    String before = saved();
    for (int i = 0; i < 12; i++) actor().getMissionManager().refresh();
    assertEquals(before, saved());
    assertEquals(saves, actor().saves);
    assertEquals(1, actor().getGameplay().getDailyMissionPeriods().getExpired().size());
  }

  @Test
  void actualPlayerReloadsKeepExpiredAndCurrentSourceViewsSeparate() throws Exception {
    fourTalks();
    clock(RESET);
    actor().getMissionManager().refresh();
    renewed();
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "daily_expiry");
      store.getMapper().map(Player.class, QuestState.class);
      for (int i = 0; i < 2; i++) {
        var base = new Player();
        DailyMissionAcceptance46Test.set(base, "uid", 123456800);
        DailyMissionAcceptance46Test.set(base, "gameplay", actor().getGameplay());
        store.save(base);
        var restored =
            store
                .find(Player.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", 123456800))
                .first();
        assertNotNull(restored);
        assigned.repeated.fixture.actor = assigned.repeated.fixture.fresh();
        DailyMissionAcceptance46Test.set(actor(), "gameplay", restored.getGameplay());
        clock(RESET);
        actor().getMissionManager().refresh();
        renewed();
        assertEquals(
            3,
            actor()
                .getGameplay()
                .getDailyMissionPeriods()
                .getExpired()
                .get(expiredKey())
                .getSubs()
                .get(300020102)
                .getProgress());
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void nativeMissionQueryAtResetReportsOnlyTheFreshDoingView() throws Exception {
    fourTalks();
    clock(RESET);
    var responses = new ArrayList<Map.Entry<String, com.google.protobuf.DynamicMessage>>();
    var session =
        new emu.lunarcore.server.game.GameSession(null) {
          @Override
          public Player getPlayer() {
            return actor();
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
    renewed();
    var response = responses.getLast();
    assertEquals("GetMissionDataScRsp", response.getKey());
    var value = response.getValue();
    assertTrue(value.getUnknownFields().asMap().isEmpty());
    var rows =
        (List<?>) value.getField(value.getDescriptorForType().findFieldByName("main_mission_list"));
    var daily =
        rows.stream()
            .map(com.google.protobuf.DynamicMessage.class::cast)
            .filter(
                row -> row.getField(row.getDescriptorForType().findFieldByName("id")).equals(MAIN))
            .toList();
    assertEquals(1, daily.size());
    var main = daily.getFirst();
    assertEquals(
        1,
        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                main.getField(main.getDescriptorForType().findFieldByName("status")))
            .getNumber());
    var finished =
        (List<?>)
            value.getField(
                value.getDescriptorForType().findFieldByName("finished_main_mission_id_list"));
    assertFalse(finished.contains(MAIN));
    assertTrue(responses.stream().noneMatch(p -> p.getKey().equals("MissionRewardScNotify")));
  }

  @Test
  void fourOClockAndClockRollbackLeaveViewsUnchangedBeforeTheirPeriod() {
    fourTalks();
    clock(RESET.minusSeconds(1));
    int saves = actor().saves;
    String before = saved();
    actor().getMissionManager().refresh();
    assertEquals(before, saved());
    assertEquals(saves, actor().saves);
    clock(RESET);
    actor().getMissionManager().refresh();
    renewed();
    clock(RESET.minusSeconds(1));
    before = saved();
    saves = actor().saves;
    actor().getMissionManager().refresh();
    assertEquals(before, saved());
    assertEquals(saves, actor().saves);
  }

  @Test
  void unsupportedCustomValuesKeepTheOldViewInsteadOfBeingCleared() throws Exception {
    begin();
    DailyMissionAcceptance46Test.set(
        GameData.getMissionInfos().get(MAIN),
        "missionCustomValueList",
        new emu.lunarcore.data.config.MissionInfo.MissionCustomValueInfo[] {null});
    clock(RESET);
    int saves = actor().saves;
    String before = saved();
    actor().getMissionManager().refresh();
    assertEquals(before, saved());
    assertEquals(saves, actor().saves);
    assertTrue(actor().getGameplay().getDailyMissionPeriods().getExpired().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "nullExpired",
        "nullMain",
        "wrongDay",
        "futureExpiry",
        "wrongGoal",
        "unknownSub",
        "pendingCursor",
        "overlappingArchive"
      })
  void damagedExpiredEvidenceHoldsTheCurrentTalkBeforeAnyMutation(String fault) throws Exception {
    fourTalks();
    clock(RESET);
    actor().getMissionManager().refresh();
    renewed();
    var periods = actor().getGameplay().getDailyMissionPeriods();
    var archive = periods.getExpired().get(expiredKey());
    switch (fault) {
      case "nullExpired" -> DailyMissionAcceptance46Test.set(periods, "expired", null);
      case "nullMain" -> DailyMissionAcceptance46Test.set(archive, "main", null);
      case "wrongDay" -> DailyMissionAcceptance46Test.set(archive, "day", firstDay() - 1);
      case "futureExpiry" ->
          DailyMissionAcceptance46Test.set(
              archive, "expiredAt", RESET.plusSeconds(10).getEpochSecond());
      case "wrongGoal" -> archive.getGoals().put(300020101, 2);
      case "unknownSub" -> archive.getSubs().put(99, new QuestState(99));
      case "pendingCursor" -> {
        var cursor = new GameplayState.ActionCursor();
        cursor.reserve();
        archive.getActions().put(300020101, cursor);
      }
      case "overlappingArchive" ->
          periods
              .getArchives()
              .put(
                  expiredKey(),
                  new DailyMissionPeriodState.Snapshot(
                      firstDay(),
                      MAIN,
                      archive.getMain(),
                      archive.getSubs(),
                      archive.getGoals(),
                      archive.getActions(),
                      List.of()));
    }
    int saves = actor().saves;
    String before = saved();
    var result =
        assertDoesNotThrow(
            () -> actor().getMissionManager().finishTalk(300020101, "Mission_300020101"));
    assertEquals(Retcode.MISSION_NOT_DOING, result);
    assertEquals(before, saved());
    assertEquals(saves, actor().saves);
    assertEquals(0, actor().getScoin());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missingPeriods", "missingExpired", "missingArchives", "missingRunDays"})
  void manualAcceptanceWithMissingPeriodEvidenceFailsWithoutAnExceptionOrSave(String fault)
      throws Exception {
    if (fault.equals("missingPeriods"))
      DailyMissionAcceptance46Test.set(actor().getGameplay(), "dailyMissionPeriods", null);
    else if (fault.equals("missingExpired"))
      DailyMissionAcceptance46Test.set(
          actor().getGameplay().getDailyMissionPeriods(), "expired", null);
    else
      DailyMissionAcceptance46Test.set(
          actor().getGameplay().getDailyMissionPeriods(),
          fault.equals("missingArchives") ? "archives" : "runDays",
          null);
    int saves = actor().saves;
    String before = saved();
    var result = assertDoesNotThrow(() -> actor().getMissionManager().accept(MAIN));
    assertEquals(Retcode.PLAYER_DATA_ERROR, result);
    assertEquals(before, saved());
    assertEquals(saves, actor().saves);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missingDay",
        "pendingAction",
        "wrongSubID",
        "futureSubTime",
        "subClaiming",
        "storedMainPlan",
        "badGroup"
      })
  void unsafeUnfinishedViewsStayUntouchedAtReset(String fault) throws Exception {
    fourTalks();
    var g = actor().getGameplay();
    switch (fault) {
      case "missingDay" -> {
        g.getDailyMissionSelections().getDays().clear();
        DailyMissionAcceptance46Test.set(
            g.getDailyMissionSelections(), "latestDay", Long.MIN_VALUE);
      }
      case "pendingAction" -> {
        var cursor = new GameplayState.ActionCursor();
        cursor.reserve();
        g.getStoryActions().put(300020101, cursor);
      }
      case "wrongSubID" ->
          DailyMissionAcceptance46Test.set(g.getSubMissions().get(300020101), "questId", 300020103);
      case "futureSubTime" ->
          DailyMissionAcceptance46Test.set(
              g.getSubMissions().get(300020101),
              "finishTime",
              RESET.plusSeconds(10).getEpochSecond());
      case "subClaiming" -> g.getSubMissions().get(300020101).reserveReward();
      case "storedMainPlan" ->
          g.getMissionRewardPlans().put("mission:" + MAIN, new GameplayState.MissionRewardPlan());
      case "badGroup" ->
          DailyMissionAcceptance46Test.set(
              g.getDailyMissionSelections().getDays().get(Long.toString(firstDay())).getFirst(),
              "group",
              999999);
    }
    clock(RESET);
    int saves = actor().saves;
    String before = saved();
    actor().getMissionManager().refresh();
    assertEquals(before, saved());
    assertEquals(saves, actor().saves);
    assertEquals(0, actor().getScoin());
    assertTrue(g.getDailyMissionPeriods().getExpired().isEmpty());
  }
}
