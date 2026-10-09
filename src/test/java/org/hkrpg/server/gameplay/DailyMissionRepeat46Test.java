package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Source commission repeats; closed tutorial histories and the day clock are explicit fixtures. */
class DailyMissionRepeat46Test {
  static final int MAIN = DailyMissionAcceptance46Test.FIRST;
  static final Instant FIRST_DAY = DailyMissionAcceptance46Test.TODAY,
      NEXT_DAY = FIRST_DAY.plusSeconds(86400);
  final DailyMissionAcceptance46Test fixture = new DailyMissionAcceptance46Test();
  final List<GameplayDefinitions.DailyPool> oldPool = new ArrayList<>();
  @TempDir Path temp;

  @BeforeEach
  void setup() throws Exception {
    fixture.temp = temp;
    fixture.setup();
    oldPool.addAll(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    try (var reader =
        new java.io.InputStreamReader(
            getClass().getResourceAsStream("/fixtures/daily-today-missions-4.6.json"),
            java.nio.charset.StandardCharsets.UTF_8)) {
      for (var raw : JsonParser.parseReader(reader).getAsJsonArray()) {
        var e = raw.getAsJsonObject();
        if (e.get("kind").getAsString().equals("pool")
            && e.getAsJsonObject("row").get("QuestID").getAsInt() == 2100003)
          GameplayDefinitions.dailyPool.add(
              fixture.gson.fromJson(e.get("row"), GameplayDefinitions.DailyPool.class));
      }
    }
    assertEquals(1, GameplayDefinitions.dailyPool.size());
    fixture.actor.getDailyManager().ensureDay();
  }

  @AfterEach
  void cleanup() {
    fixture.cleanup();
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
  }

  void clock(Instant now) {
    fixture.actor.getMissionManager().setClock(Clock.fixed(now, ZoneOffset.UTC));
    fixture.actor.getDailyManager().setClock(Clock.fixed(now, ZoneOffset.UTC));
  }

  void complete() {
    assertEquals(Retcode.SUCC, fixture.actor.getMissionManager().accept(MAIN));
    for (int suffix : List.of(101, 103, 104, 105, 106)) {
      int id = 300020000 + suffix;
      assertEquals(Retcode.SUCC, fixture.actor.getMissionManager().finishTalk(id, "Mission_" + id));
    }
    assertEquals(
        QuestState.Phase.CLOSED,
        fixture.actor.getGameplay().getMainMissions().get(MAIN).getPhase());
  }

  void rejected(Retcode expected) {
    int saves = fixture.actor.saves;
    var before = fixture.gson.toJson(fixture.actor.getGameplay());
    int credits = fixture.actor.getScoin();
    assertEquals(expected, fixture.actor.getMissionManager().accept(MAIN));
    assertEquals(saves, fixture.actor.saves);
    assertEquals(before, fixture.gson.toJson(fixture.actor.getGameplay()));
    assertEquals(credits, fixture.actor.getScoin());
  }

  @Test
  void theSameSourceCommissionCanStartAgainAfterTheChineseDailyReset() {
    complete();
    clock(NEXT_DAY);
    assertEquals(Retcode.SUCC, fixture.actor.getMissionManager().accept(MAIN));
    var main = fixture.actor.getGameplay().getMainMissions().get(MAIN);
    assertEquals(QuestState.Phase.DOING, main.getPhase());
    assertEquals(0, main.getProgress());
    assertEquals(0, main.getFinishTime());
    var start = fixture.actor.getGameplay().getSubMissions().get(300020101);
    assertNotNull(start);
    assertEquals(QuestState.Phase.DOING, start.getPhase());
    assertEquals(0, start.getProgress());
    assertFalse(fixture.actor.getGameplay().getSubMissions().containsKey(300020106));
    assertEquals(5000, fixture.actor.getScoin());
  }

  @Test
  void actualFiveTalksAndAggregationPayEachPeriodOnceAndRetainEarlierEvidence() {
    complete();
    assertEquals(Retcode.SUCC, fixture.actor.getDailyManager().claimQuest(2100003).retcode());
    assertEquals(200, fixture.actor.getGameplay().getDailyPoints());
    var oldMain = fixture.actor.getGameplay().getMainMissions().get(MAIN);
    var oldLast = fixture.actor.getGameplay().getSubMissions().get(300020106);
    var oldReceipt = fixture.actor.getGameplay().getRewards().get("mission:" + MAIN);
    for (int repeat = 1; repeat <= 2; repeat++) {
      clock(FIRST_DAY.plusSeconds(86400L * repeat));
      fixture.actor.getDailyManager().ensureDay();
      assertEquals(0, fixture.actor.getGameplay().getDailyPoints());
      assertEquals(
          QuestState.Phase.DOING,
          fixture.actor.getGameplay().getDailyQuests().get(2100003).getPhase());
      assertEquals(Retcode.SUCC, fixture.actor.getMissionManager().accept(MAIN));
      assertEquals(
          Retcode.MISSION_NOT_DOING,
          fixture.actor.getMissionManager().accept(DailyMissionAcceptance46Test.OTHER_GROUP));
      complete();
      assertEquals(5000 * (repeat + 1), fixture.actor.getScoin());
      assertEquals(Retcode.SUCC, fixture.actor.getDailyManager().claimQuest(2100003).retcode());
      assertEquals(200, fixture.actor.getGameplay().getDailyPoints());
      assertEquals(
          Retcode.QUEST_REWARD_ALREADY_TAKEN,
          fixture.actor.getDailyManager().claimQuest(2100003).retcode());
      rejected(Retcode.MISSION_ALREADY_DONE);
      fixture.actor.getMissionManager().refresh();
      assertEquals(5000 * (repeat + 1), fixture.actor.getScoin());
    }
    var periods = fixture.actor.getGameplay().getDailyMissionPeriods();
    assertEquals(2, periods.getArchives().size());
    var first = periods.getArchives().get(MAIN + ":" + DailyManager.day(FIRST_DAY));
    assertNotNull(first);
    assertSame(oldMain, first.getMain());
    assertSame(oldLast, first.getSubs().get(300020106));
    assertSame(oldReceipt, fixture.actor.getGameplay().getRewards().get("mission:" + MAIN));
    assertTrue(oldReceipt.isGranted());
    assertEquals(6, first.getSubs().size());
    assertEquals(List.of("mission:" + MAIN), first.getRewardKeys());
    assertEquals(DailyManager.day(FIRST_DAY.plusSeconds(172800)), periods.getRunDays().get(MAIN));
    assertEquals(
        3,
        fixture.actor.getGameplay().getRewards().keySet().stream()
            .filter(key -> key.contains("mission:" + MAIN))
            .count());
  }

  @Test
  void repetitionCannotBeginBeforeFourOClockOrAfterClockRollback() {
    complete();
    clock(Instant.parse("2026-10-05T19:59:59Z"));
    rejected(Retcode.MISSION_ALREADY_DONE);
    clock(Instant.parse("2026-10-05T20:00:00Z"));
    assertEquals(Retcode.SUCC, fixture.actor.getMissionManager().accept(MAIN));
    clock(FIRST_DAY);
    rejected(Retcode.MISSION_NOT_DOING);
  }

  @Test
  void nativeRepeatQueryAndPayoutUseTheActualMainMissionFields() throws Exception {
    var messages = new ArrayList<Map.Entry<String, com.google.protobuf.DynamicMessage>>();
    var session =
        new emu.lunarcore.server.game.GameSession(null) {
          @Override
          public Player getPlayer() {
            return fixture.actor;
          }

          @Override
          public org.hkrpg.server.protocol.ProtocolBridge getProtocol() {
            return org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
          }

          @Override
          public void sendClientPacket(String name, byte[] bytes) {
            try {
              messages.add(Map.entry(name, getProtocol().clientSchema().parse(name, bytes)));
            } catch (Exception error) {
              throw new AssertionError(error);
            }
          }
        };
    session.setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    var networkActor =
        new DailyMissionAcceptance46Test.Actor() {
          @Override
          public boolean isOnline() {
            return true;
          }

          @Override
          public emu.lunarcore.server.game.GameSession getSession() {
            return session;
          }

          @Override
          public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {}
        };
    DailyMissionAcceptance46Test.set(networkActor, "gameplay", fixture.actor.getGameplay());
    DailyMissionAcceptance46Test.set(networkActor, "name", "Daily repeat wire fixture");
    fixture.actor = networkActor;
    clock(FIRST_DAY);
    complete();
    clock(NEXT_DAY);
    assertEquals(Retcode.SUCC, fixture.actor.getMissionManager().accept(MAIN));
    messages.clear();
    assertTrue(
        org.hkrpg.server.protocol.VersionedPacketHandler.handle(
            session, session.getProtocol().clientCommand("GetMissionDataCsReq"), new byte[0]));
    var response = messages.getLast();
    assertEquals("GetMissionDataScRsp", response.getKey());
    var fields = response.getValue().getDescriptorForType();
    var finished =
        (List<?>)
            response.getValue().getField(fields.findFieldByName("finished_main_mission_id_list"));
    assertFalse(finished.contains(MAIN));
    var rows = (List<?>) response.getValue().getField(fields.findFieldByName("main_mission_list"));
    var main =
        rows.stream()
            .map(com.google.protobuf.DynamicMessage.class::cast)
            .filter(
                row -> row.getField(row.getDescriptorForType().findFieldByName("id")).equals(MAIN))
            .findFirst()
            .orElseThrow();
    assertEquals(
        "MissionStatus_MissionDoing",
        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                main.getField(main.getDescriptorForType().findFieldByName("status")))
            .getName());
    messages.clear();
    complete();
    assertEquals(10000, fixture.actor.getScoin());
    var payouts =
        messages.stream()
            .filter(message -> message.getKey().equals("MissionRewardScNotify"))
            .toList();
    assertEquals(1, payouts.size());
    var payout = payouts.getFirst().getValue();
    assertTrue(payout.getUnknownFields().asMap().isEmpty());
    assertEquals(
        MAIN, payout.getField(payout.getDescriptorForType().findFieldByName("main_mission_id")));
    assertTrue(
        messages.stream()
            .noneMatch(message -> message.getKey().equals("SubMissionRewardScNotify")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unboundLegacyCompletion",
        "incompleteClaim",
        "missingReceipt",
        "unfinishedReceipt",
        "missingPlan",
        "unreadyPlan",
        "futureCompletion",
        "differentCompletionDay",
        "cancelledSub",
        "damagedSub",
        "pendingAction",
        "foreignPayment",
        "foreignAcquiredItems",
        "customValueGraph",
        "outsideAggregate",
        "duplicateArchive",
        "wrongRunDay"
      })
  void unsafePriorPeriodIsPreservedRatherThanReset(String kind) throws Exception {
    complete();
    var gameplay = fixture.actor.getGameplay();
    var periods = gameplay.getDailyMissionPeriods();
    switch (kind) {
      case "unboundLegacyCompletion" -> {
        gameplay.getDailyMissionSelections().getDays().clear();
        DailyMissionAcceptance46Test.set(
            gameplay.getDailyMissionSelections(), "latestDay", Long.MIN_VALUE);
      }
      case "incompleteClaim" ->
          DailyMissionAcceptance46Test.set(
              gameplay.getMainMissions().get(MAIN), "phase", QuestState.Phase.CLAIMING);
      case "missingReceipt" -> gameplay.getRewards().remove("mission:" + MAIN);
      case "unfinishedReceipt" ->
          DailyMissionAcceptance46Test.set(
              gameplay.getRewards().get("mission:" + MAIN), "granted", false);
      case "missingPlan" -> gameplay.getMissionRewardPlans().remove("mission:" + MAIN);
      case "unreadyPlan" ->
          DailyMissionAcceptance46Test.set(
              gameplay.getMissionRewardPlans().get("mission:" + MAIN), "ready", false);
      case "futureCompletion" ->
          DailyMissionAcceptance46Test.set(
              gameplay.getMainMissions().get(MAIN),
              "finishTime",
              NEXT_DAY.plusSeconds(1).getEpochSecond());
      case "differentCompletionDay" ->
          DailyMissionAcceptance46Test.set(
              gameplay.getMainMissions().get(MAIN),
              "finishTime",
              FIRST_DAY.minusSeconds(86400).getEpochSecond());
      case "cancelledSub" -> gameplay.getCancelledSubs().add(300020106);
      case "damagedSub" ->
          DailyMissionAcceptance46Test.set(gameplay.getSubMissions().get(300020106), "progress", 2);
      case "pendingAction" -> {
        var action = new GameplayState.ActionCursor();
        action.reserve();
        gameplay.getStoryActions().put(300020101, action);
      }
      case "foreignPayment" ->
          gameplay.getMissionPayments().put(300020101, new GameplayState.ActionCursor());
      case "foreignAcquiredItems" -> gameplay.getMissionAcquiredItems().put(300020101, Set.of(2));
      case "customValueGraph" ->
          DailyMissionAcceptance46Test.set(
              GameData.getMissionInfos().get(MAIN),
              "missionCustomValueList",
              new emu.lunarcore.data.config.MissionInfo.MissionCustomValueInfo[] {null});
      case "outsideAggregate" ->
          GameplayDefinitions.subs
              .get(300020102)
              .setParamIntList(new Integer[] {300020103, 300020104, 300020201});
      case "duplicateArchive" ->
          periods.getArchives().put(MAIN + ":" + DailyManager.day(FIRST_DAY), null);
      case "wrongRunDay" -> periods.getRunDays().put(MAIN, DailyManager.day(FIRST_DAY) - 1);
      default -> fail(kind);
    }
    clock(NEXT_DAY);
    rejected(Retcode.MISSION_ALREADY_DONE);
    assertEquals(5000, fixture.actor.getScoin());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "nullSub",
        "wrongSubId",
        "wrongSubProgress",
        "wrongSubPhase",
        "missingMainRewardKey",
        "nullAction",
        "pendingAction",
        "wrongDay"
      })
  void damagedEarlierArchiveCannotBeDiscardedByAnotherSuccessfulReset(String kind)
      throws Exception {
    complete();
    clock(NEXT_DAY);
    complete();
    var periods = fixture.actor.getGameplay().getDailyMissionPeriods();
    var first = periods.getArchives().get(MAIN + ":" + DailyManager.day(FIRST_DAY));
    assertNotNull(first);
    switch (kind) {
      case "nullSub" -> first.getSubs().put(300020106, null);
      case "wrongSubId" ->
          DailyMissionAcceptance46Test.set(first.getSubs().get(300020106), "questId", 300020105);
      case "wrongSubProgress" ->
          DailyMissionAcceptance46Test.set(first.getSubs().get(300020106), "progress", 2);
      case "wrongSubPhase" ->
          DailyMissionAcceptance46Test.set(first.getSubs().get(300020106), "phase", null);
      case "missingMainRewardKey" -> {
        first.getRewardKeys().clear();
        first.getRewardKeys().add("");
      }
      case "nullAction" -> first.getActions().put(300020101, null);
      case "pendingAction" -> first.getActions().get(300020101).reserve();
      case "wrongDay" -> {
        long old = DailyManager.day(FIRST_DAY);
        periods.getArchives().remove(MAIN + ":" + old);
        DailyMissionAcceptance46Test.set(first, "day", old - 1);
        periods.getArchives().put(MAIN + ":" + (old - 1), first);
      }
      default -> fail(kind);
    }
    clock(FIRST_DAY.plusSeconds(172800));
    rejected(Retcode.MISSION_ALREADY_DONE);
    assertEquals(10000, fixture.actor.getScoin());
  }

  @Test
  void twoPlayerDocumentReloadsPreserveArchivesAndPeriodSpecificRewardReceipts() throws Exception {
    complete();
    clock(NEXT_DAY);
    complete();
    assertEquals(10000, fixture.actor.getScoin());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "daily_mission_repeat");
      store.getMapper().map(Player.class, QuestState.class);
      for (int iteration = 0; iteration < 2; iteration++) {
        var base = new Player();
        DailyMissionAcceptance46Test.set(base, "uid", 123456798);
        DailyMissionAcceptance46Test.set(base, "gameplay", fixture.actor.getGameplay());
        DailyMissionAcceptance46Test.set(base, "scoin", fixture.actor.getScoin());
        store.save(base);
        var restored =
            store
                .find(Player.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", 123456798))
                .first();
        assertNotNull(restored);
        fixture.actor = fixture.fresh();
        DailyMissionAcceptance46Test.set(fixture.actor, "gameplay", restored.getGameplay());
        DailyMissionAcceptance46Test.set(fixture.actor, "scoin", restored.getScoin());
        clock(NEXT_DAY);
        rejected(Retcode.MISSION_ALREADY_DONE);
        fixture.actor.getMissionManager().refresh();
        assertEquals(10000, fixture.actor.getScoin());
        assertEquals(1, fixture.actor.getGameplay().getDailyMissionPeriods().getArchives().size());
      }
    } finally {
      server.shutdownNow();
    }
  }
}
