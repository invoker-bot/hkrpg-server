package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.GetQuestDataScRspOuterClass.GetQuestDataScRsp;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketGetQuestDataScRsp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/**
 * A native fresh login selected an empty pool before 1010403; completing that original gate
 * in the same reset day must append 2100003 without resetting any saved daily acceptance.
 * Source objects, existing manager/query APIs and actual Phone rewards are used here. Tutorial
 * histories, retained scene resources, time and the Actor save boundary remain explicit fixtures.
 */
class DailyActiveLateUnlock46Test {
  static final int ACTIVE = 2100003, GATE = 1010403;
  final DailyMissionMessage46Test daily = new DailyMissionMessage46Test();
  final Map<Integer, GameplayDefinitions.DailyPool> pools = new HashMap<>();
  @TempDir Path temp;

  @BeforeEach
  void setup() throws Exception {
    daily.temp = temp;
    daily.setup();
    GameplayDefinitions.dailyPool.forEach(row -> pools.put(row.QuestID(), row));
    loadSource("daily-compose-use-4.6.json");
    loadSource("daily-prop-points-4.6.json");
    daily.actor = daily.fresh();
    daily.actor.getGameplay().getMainMissions().remove(GATE);
    daily.clock(DailyMissionMessage46Test.TODAY);
    pool(ACTIVE);
  }

  @AfterEach
  void cleanup() { daily.cleanup(); }

  void loadSource(String name) throws Exception {
    try (var input = getClass().getResourceAsStream("/fixtures/" + name)) {
      var rows = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonArray();
      for (var entry : rows) {
        var source = entry.getAsJsonObject();
        assertEquals("724b139d8c9c32d12552eb95745a4fee72bfe48b", source.get("sourceCommit").getAsString());
        var row = source.getAsJsonObject("row");
        switch (source.get("kind").getAsString()) {
          case "pool" -> {
            var value = daily.gson.fromJson(row, GameplayDefinitions.DailyPool.class);
            pools.put(value.QuestID(), value);
          }
          case "quest" -> GameData.getQuestExcelMap().add(daily.gson.fromJson(row, QuestExcel.class));
          case "rule" -> GameData.getFinishWayExcelMap().add(daily.gson.fromJson(row, FinishWayExcel.class));
          case "reward" -> {
            var value = daily.gson.fromJson(row, RewardExcel.class);
            value.onLoad(); GameData.getRewardExcelMap().add(value);
          }
          default -> {} // Other complete source objects are already covered by their own fixtures.
        }
      }
    }
  }

  void pool(int... ids) {
    GameplayDefinitions.dailyPool.clear();
    for (int id : ids) GameplayDefinitions.dailyPool.add(Objects.requireNonNull(pools.get(id)));
  }

  void completeGate(int id) {
    var state = new QuestState(id);
    assertTrue(state.advance(1, 1, daily.now.getEpochSecond()));
    assertTrue(state.reserveReward());
    state.closeReward();
    daily.actor.getGameplay().getMainMissions().put(id, state);
    assertTrue(daily.actor.getMissionManager().finished(id));
  }

  QuestState target(int id) {
    var state = daily.actor.getGameplay().getDailyQuests().get(id);
    assertNotNull(state, "An original newly unlocked pool type must be appended on the same saved day: " + id);
    assertEquals(id, state.getQuestId());
    return state;
  }

  void initialLockedLoginDay() {
    assertFalse(daily.actor.getMissionManager().finished(GATE));
    assertEquals(DailyManager.day(daily.now), daily.actor.getDailyManager().ensureDay());
    assertTrue(daily.actor.getGameplay().getDailyQuests().isEmpty());
    assertTrue(daily.actor.saves > 0);
    assertEquals(0, daily.actor.getGameplay().getDailyPoints());
  }

  @ParameterizedTest @ValueSource(strings = {"ensureDay", "GetQuestDataScRsp"})
  void aLoginBeforeUnlockGetsTheOriginalTargetAfterSameDayGateCompletion(String entry) {
    initialLockedLoginDay();
    long savedDay = daily.actor.getGameplay().getDailyDay();
    completeGate(GATE);
    if (entry.equals("ensureDay")) daily.actor.getDailyManager().ensureDay();
    else {
      var response = (GetQuestDataScRsp) new PacketGetQuestDataScRsp(daily.actor).getData();
      long count = 0;
      for (var row : response.getQuestList()) if (row.getId() == ACTIVE) count++;
      assertEquals(1, count, "The actual quest response must expose the late unlocked target");
    }
    assertEquals(savedDay, daily.actor.getGameplay().getDailyDay());
    assertTrue(MissionStateRecords.doing(target(ACTIVE), ACTIVE, 1));
    assertEquals(Set.of(ACTIVE), daily.actor.getGameplay().getDailyQuests().keySet());
    assertEquals(0, daily.actor.getScoin());
    assertEquals(0, daily.actor.getGameplay().getDailyPoints());
  }

  @Test
  void theOriginalSameDayNpcTalkPhoneAndClaimCloseTheAppendedActiveTargetExactlyOnce() {
    initialLockedLoginDay(); completeGate(GATE);
    daily.actor.getDailyManager().ensureDay();
    assertTrue(MissionStateRecords.doing(target(ACTIVE), ACTIVE, 1));
    daily.open(); daily.pay(121140202, 121140205);
    assertEquals(5000, daily.actor.getScoin());
    assertEquals(200, daily.actor.getGameplay().getDailyPoints());
    assertEquals(DailyManager.day(DailyMissionMessage46Test.TODAY), daily.actor.getGameplay().getDailyDay());
    assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, new MessageManager(daily.actor).finishSection(DailyMissionMessage46Test.SECTION));
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, daily.actor.getDailyManager().claimQuest(ACTIVE).retcode());
    assertEquals(5000, daily.actor.getScoin()); assertEquals(200, daily.actor.getGameplay().getDailyPoints());
  }

  @ParameterizedTest @EnumSource(QuestState.Phase.class)
  void addingANewTypePreservesTheExistingAcceptanceInEverySavedPhase(QuestState.Phase phase) {
    pool(ACTIVE, 2100111, 2100114);
    daily.actor.getDailyManager().ensureDay();
    var selected = target(2100114); selected.captureObservationKey();
    if (phase != QuestState.Phase.DOING) selected.advance(1, 1, daily.now.getEpochSecond());
    if (phase == QuestState.Phase.CLAIMING || phase == QuestState.Phase.CLOSED) selected.reserveReward();
    if (phase == QuestState.Phase.CLOSED) selected.closeReward();
    daily.actor.getGameplay().addPoints(73); daily.actor.save();
    String old = daily.gson.toJson(selected);
    completeGate(GATE); daily.actor.getDailyManager().ensureDay();
    assertTrue(MissionStateRecords.doing(target(ACTIVE), ACTIVE, 1));
    assertSame(selected, target(2100114)); assertEquals(old, daily.gson.toJson(selected));
    assertEquals(phase, selected.getPhase()); assertEquals(73, daily.actor.getGameplay().getDailyPoints());
    assertFalse(daily.actor.getGameplay().getDailyQuests().containsKey(2100111), "An occupied type must never be rerolled");
    assertEquals(Set.of(ACTIVE, 2100114), daily.actor.getGameplay().getDailyQuests().keySet());
  }

  @Test
  void unlockingAnAlternativeOfAnOccupiedTypeDoesNotRerollOrWriteTheSavedObject() {
    pool(2100111, 2100114); daily.actor.getDailyManager().ensureDay();
    var selected = target(2100114); String old = daily.gson.toJson(selected); int saves = daily.actor.saves;
    completeGate(GATE); daily.actor.getDailyManager().ensureDay();
    assertSame(selected, target(2100114)); assertEquals(old, daily.gson.toJson(selected));
    assertEquals(Set.of(2100114), daily.actor.getGameplay().getDailyQuests().keySet());
    assertEquals(saves, daily.actor.saves);
  }

  @Test
  void anAlreadyClaimedOriginalAutoFinishKeepsItsPointsAndCannotBeClaimedAgainAfterAppend() {
    pool(ACTIVE, 2100132); daily.actor.getDailyManager().ensureDay();
    var auto = target(2100132); assertEquals(QuestState.Phase.FINISHED, auto.getPhase());
    assertEquals(Retcode.SUCC, daily.actor.getDailyManager().claimQuest(2100132).retcode());
    assertEquals(100, daily.actor.getGameplay().getDailyPoints()); String old = daily.gson.toJson(auto);
    completeGate(GATE); daily.actor.getDailyManager().ensureDay(); target(ACTIVE);
    assertSame(auto, target(2100132)); assertEquals(old, daily.gson.toJson(auto));
    assertEquals(100, daily.actor.getGameplay().getDailyPoints());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, daily.actor.getDailyManager().claimQuest(2100132).retcode());
  }

  @Test
  void aRealPaidPhoneRunAndClosedSummaryKeepTheirReceiptsHistoryAndCreditsWhenAnotherTypeUnlocks() {
    pool(ACTIVE, 2100112); completeGate(GATE); daily.actor.getDailyManager().ensureDay();
    daily.open(); daily.pay(121140203, 121140207);
    var closed = target(ACTIVE); String old = daily.gson.toJson(closed);
    var gameplay = daily.actor.getGameplay();
    String receipts = daily.gson.toJson(gameplay.getRewards());
    String plans = daily.gson.toJson(gameplay.getMissionRewardPlans());
    String phone = daily.gson.toJson(gameplay.getDailyMissionMessages());
    String selections = daily.gson.toJson(gameplay.getDailyMissionSelections());
    completeGate(4020102); daily.actor.getDailyManager().ensureDay();
    assertTrue(MissionStateRecords.doing(target(2100112), 2100112, 1));
    assertSame(closed, target(ACTIVE)); assertEquals(old, daily.gson.toJson(closed));
    assertEquals(receipts, daily.gson.toJson(gameplay.getRewards()));
    assertEquals(plans, daily.gson.toJson(gameplay.getMissionRewardPlans()));
    assertEquals(phone, daily.gson.toJson(gameplay.getDailyMissionMessages()));
    assertEquals(selections, daily.gson.toJson(gameplay.getDailyMissionSelections()));
    assertEquals(5000, daily.actor.getScoin()); assertEquals(200, gameplay.getDailyPoints());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, daily.actor.getDailyManager().claimQuest(ACTIVE).retcode());
    assertEquals(Retcode.MESSAGE_SECTION_CAN_NOT_FINISH, new MessageManager(daily.actor).finishSection(DailyMissionMessage46Test.SECTION));
    assertEquals(5000, daily.actor.getScoin()); assertEquals(200, gameplay.getDailyPoints());
  }

  @ParameterizedTest @ValueSource(strings = {"missing", "doing", "cancelled"})
  void sameDayRefreshCannotAppendWhileTheOriginalGateRemainsUnfinishedOrCancelled(String gate) {
    initialLockedLoginDay();
    if (gate.equals("doing")) daily.actor.getGameplay().getMainMissions().put(GATE, new QuestState(GATE));
    if (gate.equals("cancelled")) {
      completeGate(GATE); daily.actor.getGameplay().getCancelledMissions().add(GATE);
    }
    daily.actor.getDailyManager().ensureDay();
    assertTrue(daily.actor.getGameplay().getDailyQuests().isEmpty());
    assertEquals(Retcode.QUEST_NOT_ACCEPT, daily.actor.getDailyManager().claimQuest(ACTIVE).retcode());
  }

  @Test
  void anUnlockedQuestStillRequiresAnActuallySupportedFinishRule() {
    initialLockedLoginDay(); completeGate(GATE);
    var source = daily.gson.toJsonTree(GameData.getFinishWayExcelMap().get(ACTIVE)).getAsJsonObject();
    source.addProperty("FinishType", "UnsupportedLateUnlockFixture");
    GameData.getFinishWayExcelMap().add(daily.gson.fromJson(source, FinishWayExcel.class));
    daily.actor.getDailyManager().ensureDay(); assertTrue(daily.actor.getGameplay().getDailyQuests().isEmpty());
  }

  @Test
  void repeatedActualQueriesAfterAppendKeepTheAcceptanceIdentityAndDoNotSaveAgain() {
    initialLockedLoginDay(); completeGate(GATE); daily.actor.getDailyManager().ensureDay();
    var selected = target(ACTIVE); String key = selected.captureObservationKey();
    int saves = daily.actor.saves;
    for (int iteration = 0; iteration < 3; iteration++) {
      assertSame(selected, daily.actor.getQuestManager().states().stream().filter(q -> q.getQuestId() == ACTIVE).findFirst().orElseThrow());
      assertEquals(key, selected.getObservationKey());
    }
    assertEquals(saves, daily.actor.saves);
  }

  @Test
  void theNextRealResetCreatesANewAcceptanceAndClearsOnlyThatDaysPoints() {
    completeGate(GATE); daily.actor.getDailyManager().ensureDay();
    var previous = target(ACTIVE); daily.actor.getGameplay().addPoints(100);
    daily.clock(DailyMissionMessage46Test.NEXT); daily.actor.getDailyManager().ensureDay();
    assertNotSame(previous, target(ACTIVE)); assertEquals(0, daily.actor.getGameplay().getDailyPoints());
    assertEquals(DailyManager.day(DailyMissionMessage46Test.NEXT), daily.actor.getGameplay().getDailyDay());
    assertTrue(daily.actor.getMissionManager().finished(GATE));
  }

  @Test
  void aBackwardClockCannotAppendOrResetAFutureSavedDailyPool() {
    daily.clock(DailyMissionMessage46Test.NEXT); initialLockedLoginDay(); completeGate(GATE);
    daily.actor.getGameplay().addPoints(100);
    daily.clock(DailyMissionMessage46Test.TODAY); daily.actor.getDailyManager().ensureDay();
    assertTrue(daily.actor.getGameplay().getDailyQuests().isEmpty());
    assertEquals(100, daily.actor.getGameplay().getDailyPoints());
    assertEquals(DailyManager.day(DailyMissionMessage46Test.NEXT), daily.actor.getGameplay().getDailyDay());
  }

  @ParameterizedTest @ValueSource(strings = {"empty", "claimedAuto"})
  void aRejectedSameDayAppendWriteRestoresAllSavedStateAndCanRetryWithoutReset(String existing) {
    if (existing.equals("claimedAuto")) {
      pool(ACTIVE, 2100132); daily.actor.getDailyManager().ensureDay();
      assertEquals(Retcode.SUCC, daily.actor.getDailyManager().claimQuest(2100132).retcode());
    } else initialLockedLoginDay();
    completeGate(GATE);
    var states = daily.actor.getGameplay().getDailyQuests();
    var prior = states.get(2100132);
    String before = daily.gson.toJson(daily.actor.getGameplay());
    String durable = daily.actor.durableGameplay;
    int saves = daily.actor.saves;
    daily.actor.failSave = true;
    try {
      assertThrows(IllegalStateException.class, () -> daily.actor.getDailyManager().ensureDay());
      assertEquals(before, daily.gson.toJson(daily.actor.getGameplay()));
      assertSame(states, daily.actor.getGameplay().getDailyQuests());
      assertEquals(durable, daily.actor.durableGameplay); assertEquals(saves, daily.actor.saves);
      assertFalse(states.containsKey(ACTIVE));
      if (prior != null) assertSame(prior, states.get(2100132));
    } finally { daily.actor.failSave = false; }
    daily.actor.getDailyManager().ensureDay();
    assertTrue(MissionStateRecords.doing(target(ACTIVE), ACTIVE, 1));
    assertEquals(existing.equals("claimedAuto") ? 100 : 0, daily.actor.getGameplay().getDailyPoints());
    if (prior != null) {
      assertSame(prior, states.get(2100132));
      assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, daily.actor.getDailyManager().claimQuest(2100132).retcode());
    }
  }

  @Test
  void newlyEligibleAlternativesUseTheExistingSortedDailyPolicyOnlyForAnUnoccupiedType() {
    pool(2100111, 2100112); initialLockedLoginDay(); completeGate(GATE); completeGate(4020102);
    daily.actor.getDailyManager().ensureDay();
    var ids = List.of(2100111, 2100112);
    int expected = ids.get(Math.floorMod(DailyManager.day(daily.now), ids.size()));
    target(expected); assertEquals(Set.of(expected), daily.actor.getGameplay().getDailyQuests().keySet());
  }

  @Test
  void actualMorphiaReadsPreserveTheEmptyLoginDayThenTheLateAcceptanceWithoutReselectingIt() throws Exception {
    initialLockedLoginDay();
    var server = new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client = com.mongodb.client.MongoClients.create("mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "daily_active_late_unlock");
      store.getMapper().map(Player.class, QuestState.class);
      for (int read = 0; read < 2; read++) {
        var base = new Player();
        DailyMissionAcceptance46Test.set(base, "uid", daily.actor.getUid());
        DailyMissionAcceptance46Test.set(base, "gameplay", daily.actor.getGameplay());
        store.save(base);
        var restored = store.find(Player.class).filter(dev.morphia.query.filters.Filters.eq("_id", daily.actor.getUid())).first();
        assertNotNull(restored);
        daily.actor = daily.fresh(); DailyMissionAcceptance46Test.set(daily.actor, "gameplay", restored.getGameplay());
        daily.clock(DailyMissionMessage46Test.TODAY);
        if (read == 0) {
          assertTrue(daily.actor.getGameplay().getDailyQuests().isEmpty()); completeGate(GATE);
          daily.actor.getDailyManager().ensureDay(); target(ACTIVE).captureObservationKey();
        } else {
          var saved = target(ACTIVE); String old = daily.gson.toJson(saved);
          daily.actor.getDailyManager().ensureDay(); assertSame(saved, target(ACTIVE));
          assertEquals(old, daily.gson.toJson(saved)); assertNotNull(saved.getObservationKey());
        }
        assertEquals(DailyManager.day(DailyMissionMessage46Test.TODAY), daily.actor.getGameplay().getDailyDay());
      }
    } finally { server.shutdownNow(); }
  }
}
