package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original six-objective daily graph; tutorial unlock and day selection are explicit fixtures. */
class DailyTodayMissionCountsTest {
  static final int MAIN = 3000201, QUEST = 2100003;
  static final Instant TODAY = Instant.parse("2026-10-05T05:00:00Z");
  final Gson gson = new Gson();
  final Map<Integer, MissionInfo> oldMissions = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldRules = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<GameplayDefinitions.DailyPool> oldPool = new ArrayList<>();
  Actor actor;

  static class Actor extends Player {
    int saves;
    boolean online;
    final List<emu.lunarcore.server.packet.BasePacket> packets = new ArrayList<>();

    @Override
    public boolean isOnline() {
      return online;
    }

    @Override
    public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {
      packets.add(packet);
    }

    @Override
    public int getPlaneId() {
      return 20003;
    }

    @Override
    public int getFloorId() {
      return 20003001;
    }

    @Override
    public void save() {
      saves++;
    }
  }

  Actor fresh() {
    var value = new Actor();
    value.getDailyManager().setClock(Clock.fixed(TODAY, ZoneOffset.UTC));
    value.getMissionManager().setClock(Clock.fixed(TODAY, ZoneOffset.UTC));
    value
        .getGameplay()
        .getMainMissions()
        .put(1010403, completed(1010403, TODAY.minusSeconds(86400), "CLOSED"));
    return value;
  }

  @BeforeEach
  void setup() throws Exception {
    oldMissions.putAll(GameData.getMissionInfos());
    oldSubs.putAll(GameplayDefinitions.subs);
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldRules.addAll(GameData.getFinishWayExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldPool.addAll(GameplayDefinitions.dailyPool);
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameplayDefinitions.dailyPool.clear();
    JsonObject definition = null;
    var children = new JsonArray();
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/daily-today-missions-4.6.json"),
            StandardCharsets.UTF_8)) {
      var entries = JsonParser.parseReader(reader).getAsJsonArray();
      assertEquals(16, entries.size());
      for (var raw : entries) {
        var entry = raw.getAsJsonObject();
        var row = entry.getAsJsonObject("row");
        switch (entry.get("kind").getAsString()) {
          case "definition" -> definition = row;
          case "sub" -> children.add(row);
          case "main" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "quest" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "rule" ->
              GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
          case "pool" ->
              GameplayDefinitions.dailyPool.add(
                  gson.fromJson(row, GameplayDefinitions.DailyPool.class));
          case "reward" -> {
            var reward = gson.fromJson(row, RewardExcel.class);
            reward.onLoad();
            GameData.getRewardExcelMap().add(reward);
          }
          default -> fail("Unknown fixture kind");
        }
      }
    }
    assertNotNull(definition);
    definition.add("SubMissionList", children);
    var main = gson.fromJson(definition, MissionInfo.class);
    GameData.getMissionInfos().put(MAIN, main);
    for (var sub : main.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
    actor = fresh();
    actor.getDailyManager().ensureDay();
    // Isolate observation behavior even on the original unsupported selection implementation.
    for (int id : List.of(2100003, 2100134))
      actor.getGameplay().getDailyQuests().putIfAbsent(id, new QuestState(id));
  }

  @AfterEach
  void cleanup() {
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldMissions);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getQuestExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    oldRules.forEach(GameData.getFinishWayExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
  }

  static QuestState completed(int id, Instant time, String phase) {
    var state = new QuestState(id);
    assertTrue(state.advance(1, 1, time.getEpochSecond()));
    if (!phase.equals("FINISHED")) assertTrue(state.reserveReward());
    if (phase.equals("CLOSED")) state.closeReward();
    return state;
  }

  void pending() {
    actor.getDailyManager().ensureDay();
    var state = actor.getGameplay().getDailyQuests().get(QUEST);
    assertNotNull(state);
    assertEquals(QuestState.Phase.DOING, state.getPhase());
    assertEquals(0, state.getProgress());
    assertEquals(0, state.getFinishTime());
  }

  void finished(int id) {
    var state = actor.getGameplay().getDailyQuests().get(id);
    assertNotNull(state);
    assertEquals(QuestState.Phase.FINISHED, state.getPhase());
    assertEquals(1, state.getProgress());
    assertEquals(TODAY.getEpochSecond(), state.getFinishTime());
  }

  @ParameterizedTest
  @ValueSource(ints = {2100003, 2100134})
  void originalFinishTodayGoalIsSelectedOnlyAfterItsTutorialUnlock(int id) {
    var selected = fresh();
    selected.getDailyManager().ensureDay();
    assertNotNull(selected.getGameplay().getDailyQuests().get(id));
    var locked = fresh();
    locked.getGameplay().getMainMissions().remove(1010403);
    locked.getDailyManager().ensureDay();
    assertFalse(locked.getGameplay().getDailyQuests().containsKey(id));
  }

  @Test
  void observedCompletionPublishesTheActualQuestStatusImmediately() throws Exception {
    actor.online = true;
    actor
        .getGameplay()
        .getMainMissions()
        .put(MAIN, completed(MAIN, TODAY.minusSeconds(1), "CLOSED"));
    actor.getDailyManager().ensureDay();
    finished(QUEST);
    assertTrue(
        actor.packets.stream()
            .anyMatch(
                packet ->
                    packet.getCmdId() == emu.lunarcore.server.packet.CmdId.GetQuestDataScRsp));
    var packet =
        actor.packets.stream()
            .filter(
                value -> value.getCmdId() == emu.lunarcore.server.packet.CmdId.GetQuestDataScRsp)
            .findFirst()
            .orElseThrow();
    var bridge = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    var data =
        bridge
            .clientSchema()
            .parse(
                "GetQuestDataScRsp",
                bridge.outbound(packet.getCmdId(), packet.getData().toByteArray()).body());
    assertTrue(data.getUnknownFields().asMap().isEmpty());
    var rows = (List<?>) data.getField(data.getDescriptorForType().findFieldByName("quest_list"));
    for (int id : List.of(2100003, 2100134)) {
      var row =
          rows.stream()
              .map(com.google.protobuf.DynamicMessage.class::cast)
              .filter(
                  value ->
                      (Integer) value.getField(value.getDescriptorForType().findFieldByName("id"))
                          == id)
              .findFirst()
              .orElseThrow();
      assertEquals(1, row.getField(row.getDescriptorForType().findFieldByName("progress")));
      assertEquals(
          2,
          ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                  row.getField(row.getDescriptorForType().findFieldByName("status")))
              .getNumber());
      assertEquals(
          TODAY.getEpochSecond(),
          row.getField(row.getDescriptorForType().findFieldByName("finish_time")));
    }
  }

  @Test
  void allOriginalTalksAndAggregateCompleteBothGoalsBeforeAnyDailyQueryOrClaim() {
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(MAIN));
    pending();
    for (int id : List.of(300020101, 300020103, 300020104, 300020105)) {
      assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(id, "Mission_" + id));
      pending();
    }
    assertEquals(3, actor.getMissionManager().sub(300020102).getProgress());
    assertEquals(
        Retcode.SUCC, actor.getMissionManager().finishTalk(300020106, "Mission_300020106"));
    assertTrue(actor.getMissionManager().finished(MAIN));
    finished(2100003);
    finished(2100134);
    assertEquals(
        Retcode.MISSION_NOT_DOING,
        actor.getMissionManager().finishTalk(300020106, "Mission_300020106"));
    assertEquals(0, actor.getGameplay().getDailyPoints());
    assertEquals(Retcode.SUCC, actor.getDailyManager().claimQuest(QUEST).retcode());
    assertEquals(200, actor.getGameplay().getDailyPoints());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getDailyManager().claimQuest(QUEST).retcode());
    assertEquals(200, actor.getGameplay().getDailyPoints());
  }

  @ParameterizedTest
  @ValueSource(strings = {"FINISHED", "CLAIMING", "CLOSED"})
  void currentDayCompletionAtEveryRewardStageIsObservedOnce(String phase) {
    actor.getGameplay().getMainMissions().put(MAIN, completed(MAIN, TODAY.minusSeconds(1), phase));
    actor.getDailyManager().ensureDay();
    finished(QUEST);
    int saves = actor.saves;
    actor.getDailyManager().ensureDay();
    assertEquals(saves, actor.saves);
    finished(QUEST);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "over-goal",
        "wrong-id",
        "future",
        "overflow-time",
        "cancelled",
        "not-daily",
        "missing-definition"
      })
  void invalidOrUnclassifiedCompletionDoesNotCount(String mode) {
    var row = gson.toJsonTree(completed(MAIN, TODAY.minusSeconds(1), "CLOSED")).getAsJsonObject();
    switch (mode) {
      case "null-phase" -> row.add("phase", JsonNull.INSTANCE);
      case "zero-time" -> row.addProperty("finishTime", 0);
      case "negative-time" -> row.addProperty("finishTime", -1);
      case "zero-progress" -> row.addProperty("progress", 0);
      case "negative-progress" -> row.addProperty("progress", -1);
      case "over-goal" -> row.addProperty("progress", 2);
      case "wrong-id" -> row.addProperty("questId", MAIN + 1);
      case "future" -> row.addProperty("finishTime", TODAY.plusSeconds(1).getEpochSecond());
      case "overflow-time" -> row.addProperty("finishTime", Long.MAX_VALUE);
      case "cancelled" -> actor.getGameplay().getCancelledMissions().add(MAIN);
      case "not-daily" -> {
        var config = gson.toJsonTree(GameData.getMainMissionExcelMap().get(MAIN)).getAsJsonObject();
        config.addProperty("Type", "Branch");
        GameData.getMainMissionExcelMap().add(gson.fromJson(config, MainMissionExcel.class));
      }
      case "missing-definition" ->
          GameData.getMainMissionExcelMap()
              .values()
              .remove(GameData.getMainMissionExcelMap().get(MAIN));
    }
    actor.getGameplay().getMainMissions().put(MAIN, gson.fromJson(row, QuestState.class));
    pending();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "extra-int",
        "extra-list",
        "extra-item",
        "extra-string",
        "wrong-param",
        "zero-goal",
        "backtrack"
      })
  void unsupportedFinishTodayVariantsStayPending(String mode) {
    actor
        .getGameplay()
        .getMainMissions()
        .put(MAIN, completed(MAIN, TODAY.minusSeconds(1), "CLOSED"));
    var row = gson.toJsonTree(GameData.getFinishWayExcelMap().get(QUEST)).getAsJsonObject();
    switch (mode) {
      case "extra-int" -> row.addProperty("ParamInt1", 1);
      case "extra-list" -> row.add("ParamIntList", JsonParser.parseString("[3000201]"));
      case "extra-item" ->
          row.add("ParamItemList", JsonParser.parseString("[{\"ItemID\":235,\"ItemNum\":1}]"));
      case "extra-string" -> row.addProperty("ParamStr1", "unknown");
      case "wrong-param" -> row.addProperty("ParamType", "Equal");
      case "zero-goal" -> row.addProperty("Progress", 0);
      case "backtrack" -> row.addProperty("IsBackTrack", true);
    }
    GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
    pending();
  }

  @Test
  void previousDayHistoryDoesNotLeakAcrossTheChineseFourOClockBoundary() {
    Instant boundary = Instant.parse("2026-10-04T20:00:00Z");
    actor
        .getGameplay()
        .getMainMissions()
        .put(MAIN, completed(MAIN, boundary.minusSeconds(1), "CLOSED"));
    pending();
    actor.getGameplay().getMainMissions().put(MAIN, completed(MAIN, boundary, "CLOSED"));
    actor.getDailyManager().ensureDay();
    finished(QUEST);
    actor
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T20:00:00Z"), ZoneOffset.UTC));
    actor.getDailyManager().ensureDay();
    var next = actor.getGameplay().getDailyQuests().get(QUEST);
    assertNotNull(next);
    assertEquals(QuestState.Phase.DOING, next.getPhase());
    assertEquals(0, next.getProgress());
    assertEquals(0, actor.getGameplay().getDailyPoints());
  }

  @Test
  void actualPlayerDocumentRestoresCompletedDailyHistoryWithoutAnotherGrant() throws Exception {
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "daily_today_counts");
      store.getMapper().map(Player.class, QuestState.class);
      actor
          .getGameplay()
          .getMainMissions()
          .put(MAIN, completed(MAIN, TODAY.minusSeconds(1), "CLOSED"));
      actor.getDailyManager().ensureDay();
      finished(QUEST);
      reload(store);
      finished(QUEST);
      assertEquals(Retcode.SUCC, actor.getDailyManager().claimQuest(QUEST).retcode());
      reload(store);
      actor.getDailyManager().ensureDay();
      assertEquals(200, actor.getGameplay().getDailyPoints());
      assertEquals(
          Retcode.QUEST_REWARD_ALREADY_TAKEN, actor.getDailyManager().claimQuest(QUEST).retcode());
    } finally {
      server.shutdownNow();
    }
  }

  void reload(dev.morphia.Datastore store) throws Exception {
    var base = new Player();
    var uid = Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(base, 123456796);
    var gameplay = Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(base, actor.getGameplay());
    store.save(base);
    var restored =
        store
            .find(Player.class)
            .filter(dev.morphia.query.filters.Filters.eq("_id", 123456796))
            .first();
    assertNotNull(restored);
    actor = fresh();
    gameplay.set(actor, restored.getGameplay());
  }
}
