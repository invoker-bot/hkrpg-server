package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real daily graphs; unlock histories, position, clock and saves are explicit fixtures. */
class DailyMissionAcceptance46Test {
  static final int FIRST = 3000201, SAME_GROUP = 3000202, OTHER_GROUP = 3000522;
  static final Instant TODAY = Instant.parse("2026-10-05T05:00:00Z");
  final Gson gson = new Gson();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final List<QuestExcel> oldQuests = new ArrayList<>();
  final List<FinishWayExcel> oldRules = new ArrayList<>();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  final List<ItemExcel> oldItems = new ArrayList<>();
  final Map<Integer, MissionInfo> oldDefinitions = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  @TempDir Path temp;
  Actor actor;
  JsonArray dailyRows, countRows;

  static class Actor extends Player {
    int saves;

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
    value.setNickname("Daily commission fixture");
    value.getMissionManager().setClock(Clock.fixed(TODAY, ZoneOffset.UTC));
    value.getDailyManager().setClock(Clock.fixed(TODAY, ZoneOffset.UTC));
    for (int id : List.of(2000116, 2000120, 1010403))
      value.getGameplay().getMainMissions().put(id, closed(id));
    return value;
  }

  static QuestState closed(int id) {
    var state = new QuestState(id);
    state.advance(1, 1, TODAY.minusSeconds(86400).getEpochSecond());
    state.reserveReward();
    state.closeReward();
    return state;
  }

  @BeforeEach
  void setup() throws Exception {
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldQuests.addAll(GameData.getQuestExcelMap().values());
    oldRules.addAll(GameData.getFinishWayExcelMap().values());
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    oldItems.addAll(GameData.getItemExcelMap().values());
    oldDefinitions.putAll(GameData.getMissionInfos());
    oldSubs.putAll(GameplayDefinitions.subs);
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getQuestExcelMap().values().clear();
    GameData.getFinishWayExcelMap().values().clear();
    GameData.getRewardExcelMap().values().clear();
    GameData.getItemExcelMap().values().clear();
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    dailyRows = new JsonArray();
    countRows = new JsonArray();
    var definitions = new HashMap<Integer, JsonObject>();
    var children = new HashMap<Integer, JsonArray>();
    try (var reader =
        new java.io.InputStreamReader(
            getClass().getResourceAsStream("/fixtures/daily-assignment-4.6.json"),
            java.nio.charset.StandardCharsets.UTF_8)) {
      var entries = JsonParser.parseReader(reader).getAsJsonArray();
      assertEquals(33, entries.size());
      for (var raw : entries) {
        var e = raw.getAsJsonObject();
        var row = e.getAsJsonObject("row");
        switch (e.get("kind").getAsString()) {
          case "definition" -> definitions.put(row.get("MainMissionID").getAsInt(), row);
          case "sub" ->
              children
                  .computeIfAbsent(row.get("MainMissionID").getAsInt(), ignored -> new JsonArray())
                  .add(row);
          case "main" ->
              GameData.getMainMissionExcelMap().add(gson.fromJson(row, MainMissionExcel.class));
          case "quest" -> GameData.getQuestExcelMap().add(gson.fromJson(row, QuestExcel.class));
          case "rule" ->
              GameData.getFinishWayExcelMap().add(gson.fromJson(row, FinishWayExcel.class));
          case "daily" -> dailyRows.add(row);
          case "count" -> countRows.add(row);
          case "reward" -> {
            var value = gson.fromJson(row, RewardExcel.class);
            value.onLoad();
            GameData.getRewardExcelMap().add(value);
          }
          case "item" ->
              GameData.getItemExcelMap()
                  .add(emu.lunarcore.data.ResourceLoader.getGson().fromJson(row, ItemExcel.class));
          default -> fail("Unknown fixture");
        }
      }
    }
    for (var e : definitions.entrySet()) {
      e.getValue().add("SubMissionList", children.get(e.getKey()));
      var value = gson.fromJson(e.getValue(), MissionInfo.class);
      GameData.getMissionInfos().put(e.getKey(), value);
      for (var sub : value.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    loadCatalog();
    actor = fresh();
  }

  void loadCatalog() throws Exception {
    Files.createDirectories(temp.resolve("ExcelOutput"));
    Files.writeString(temp.resolve("ExcelOutput/DailyMissionData.json"), dailyRows.toString());
    Files.writeString(temp.resolve("ExcelOutput/DailyMissionCount.json"), countRows.toString());
    DailyMissionCatalog.load(temp);
  }

  @AfterEach
  void cleanup() {
    DailyMissionCatalog.load(temp.resolve("absent"));
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getQuestExcelMap().values().clear();
    oldQuests.forEach(GameData.getQuestExcelMap()::add);
    GameData.getFinishWayExcelMap().values().clear();
    oldRules.forEach(GameData.getFinishWayExcelMap()::add);
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
    GameData.getItemExcelMap().values().clear();
    oldItems.forEach(GameData.getItemExcelMap()::add);
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldDefinitions);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
  }

  void rejected(int id, Retcode code) {
    int saves = actor.saves;
    var before = gson.toJson(actor.getGameplay());
    assertEquals(code, actor.getMissionManager().accept(id));
    assertEquals(saves, actor.saves);
    assertEquals(before, gson.toJson(actor.getGameplay()));
    assertFalse(actor.getGameplay().getMainMissions().containsKey(id));
  }

  @ParameterizedTest
  @ValueSource(ints = {FIRST, SAME_GROUP, OTHER_GROUP})
  void aManualGateCannotBypassTheAuthoredDailyUnlock(int id) {
    actor.getGameplay().getMainMissions().remove(id == OTHER_GROUP ? 2000120 : 2000116);
    rejected(id, Retcode.MISSION_NOT_DOING);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrongId",
        "zeroTime",
        "wrongGoal",
        "cancelled",
        "doing",
        "missingDefinition",
        "wrongDefinition",
        "futureTime"
      })
  void damagedUnlockHistoryCannotSelectACommission(String kind) throws Exception {
    var state = actor.getGameplay().getMainMissions().get(2000116);
    switch (kind) {
      case "wrongId" -> set(state, "questId", 2000120);
      case "zeroTime" -> set(state, "finishTime", 0L);
      case "futureTime" -> set(state, "finishTime", TODAY.getEpochSecond() + 1);
      case "wrongGoal" -> set(state, "progress", 2);
      case "cancelled" -> actor.getGameplay().getCancelledMissions().add(2000116);
      case "doing" -> actor.getGameplay().getMainMissions().put(2000116, new QuestState(2000116));
      case "missingDefinition" ->
          GameData.getMainMissionExcelMap().values().removeIf(x -> x.getId() == 2000116);
      case "wrongDefinition" ->
          set(GameData.getMainMissionExcelMap().get(2000116), "MainMissionID", 2000120);
      default -> fail(kind);
    }
    rejected(FIRST, Retcode.MISSION_NOT_DOING);
  }

  @ParameterizedTest
  @ValueSource(ints = {SAME_GROUP, OTHER_GROUP})
  void authoredOnePerDayQuotaCoversEveryGroup(int second) {
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(FIRST));
    rejected(second, Retcode.MISSION_NOT_DOING);
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(FIRST));
  }

  @Test
  void finishingAndClaimingTheCommissionDoesNotFreeTheDailyQuota() {
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(FIRST));
    for (int suffix : List.of(101, 103, 104, 105, 106))
      assertEquals(
          Retcode.SUCC,
          actor
              .getMissionManager()
              .finishTalk(300020000 + suffix, "Mission_" + (300020000 + suffix)));
    assertTrue(actor.getMissionManager().finished(FIRST));
    assertEquals(5000, actor.getScoin());
    rejected(OTHER_GROUP, Retcode.MISSION_NOT_DOING);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missingQuest",
        "wrongQuestType",
        "missingMainGraph",
        "wrongMainType",
        "removedCatalogRow",
        "wrongRowQuest",
        "missingFinishRule",
        "wrongFinishRule",
        "missingEnding",
        "wrongParent"
      })
  void commissionSourceLinksMustResolveBeforeAnyReservation(String kind) throws Exception {
    switch (kind) {
      case "missingQuest" -> GameData.getQuestExcelMap().values().clear();
      case "wrongQuestType" -> set(GameData.getQuestExcelMap().get(2100003), "QuestType", 1);
      case "missingMainGraph" -> GameData.getMissionInfos().remove(FIRST);
      case "wrongMainType" -> set(GameData.getMainMissionExcelMap().get(FIRST), "Type", "Branch");
      case "removedCatalogRow" -> {
        dailyRows.remove(0);
        loadCatalog();
      }
      case "wrongRowQuest" -> {
        dailyRows.get(0).getAsJsonObject().addProperty("QuestID", 2100134);
        loadCatalog();
      }
      case "missingFinishRule" -> GameData.getFinishWayExcelMap().values().clear();
      case "wrongFinishRule" ->
          set(GameData.getFinishWayExcelMap().get(2100003), "FinishType", "AutoFinish");
      case "missingEnding" -> GameplayDefinitions.subs.remove(300020106);
      case "wrongParent" ->
          set(GameplayDefinitions.subs.get(300020106), "mainMissionID", SAME_GROUP);
      default -> fail(kind);
    }
    rejected(FIRST, Retcode.MISSION_NOT_DOING);
  }

  @Test
  void clockRollbackCannotAcquireAnotherCommission() {
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(FIRST));
    actor.getMissionManager().setClock(Clock.fixed(TODAY.minusSeconds(86400), ZoneOffset.UTC));
    rejected(OTHER_GROUP, Retcode.MISSION_NOT_DOING);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "nullState",
        "nullDays",
        "nullList",
        "nullSelection",
        "duplicate",
        "wrongDayKey",
        "wrongWatermark",
        "wrongId",
        "wrongType",
        "wrongGroup",
        "zeroGroup"
      })
  void damagedPersistedSelectionCannotCreateASecondCommission(String kind) throws Exception {
    var state = actor.getGameplay().getDailyMissionSelections();
    long day = DailyManager.day(TODAY);
    assertTrue(state.record(day, DailyMissionCatalog.mission(FIRST)));
    var list = state.getDays().get(Long.toString(day));
    switch (kind) {
      case "nullState" -> set(actor.getGameplay(), "dailyMissionSelections", null);
      case "nullDays" -> set(state, "days", null);
      case "nullList" -> state.getDays().put(Long.toString(day), null);
      case "nullSelection" -> list.add(null);
      case "duplicate" -> list.add(list.getFirst());
      case "wrongDayKey" ->
          state.getDays().put("0" + day, state.getDays().remove(Long.toString(day)));
      case "wrongWatermark" -> set(state, "latestDay", day - 1);
      case "wrongId" -> set(list.getFirst(), "id", 3000211);
      case "wrongType" -> set(list.getFirst(), "type", 2);
      case "wrongGroup" -> set(list.getFirst(), "group", 30004);
      case "zeroGroup" -> set(list.getFirst(), "group", 0);
      default -> fail(kind);
    }
    rejected(OTHER_GROUP, Retcode.PLAYER_DATA_ERROR);
  }

  @Test
  void exactChineseResetOpensTheNextQuotaAndPreservesPriorMissionRecords() {
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(FIRST));
    var earlier = actor.getGameplay().getMainMissions().get(FIRST);
    actor
        .getMissionManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T19:59:59Z"), ZoneOffset.UTC));
    rejected(OTHER_GROUP, Retcode.MISSION_NOT_DOING);
    actor
        .getMissionManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T20:00:00Z"), ZoneOffset.UTC));
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(OTHER_GROUP));
    assertSame(earlier, actor.getGameplay().getMainMissions().get(FIRST));
    assertEquals(QuestState.Phase.DOING, earlier.getPhase());
  }

  @Test
  void separatePlayersHaveSeparateQuotaSelections() {
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(FIRST));
    assertEquals(Retcode.SUCC, fresh().getMissionManager().accept(OTHER_GROUP));
    rejected(OTHER_GROUP, Retcode.MISSION_NOT_DOING);
  }

  @Test
  void nativeAcceptAndMissionQueryExposeOnlyTheSelectedOriginalCommission() throws Exception {
    var session =
        new emu.lunarcore.server.game.GameSession(null) {
          com.google.protobuf.DynamicMessage response;
          String responseName;

          @Override
          public Player getPlayer() {
            return actor;
          }

          @Override
          public org.hkrpg.server.protocol.ProtocolBridge getProtocol() {
            return org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
          }

          @Override
          public void sendClientPacket(String name, byte[] bytes) {
            try {
              response = getProtocol().clientSchema().parse(name, bytes);
              responseName = name;
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
          }
        };
    session.setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    for (int id : List.of(FIRST, OTHER_GROUP)) {
      var req =
          com.google.protobuf.DynamicMessage.newBuilder(
              session.getProtocol().clientSchema().message("AcceptMainMissionCsReq"));
      req.setField(req.getDescriptorForType().findFieldByName("main_mission_id"), id);
      assertTrue(
          org.hkrpg.server.protocol.VersionedPacketHandler.handle(
              session,
              session.getProtocol().clientCommand("AcceptMainMissionCsReq"),
              req.build().toByteArray()));
      assertEquals("AcceptMainMissionScRsp", session.responseName);
      assertTrue(session.response.getUnknownFields().asMap().isEmpty());
      assertEquals(
          id,
          session.response.getField(
              session.response.getDescriptorForType().findFieldByName("main_mission_id")));
      assertEquals(
          (id == FIRST ? Retcode.SUCC : Retcode.MISSION_NOT_DOING).getVal(),
          session.response.getField(
              session.response.getDescriptorForType().findFieldByName("retcode")));
    }
    assertTrue(
        org.hkrpg.server.protocol.VersionedPacketHandler.handle(
            session, session.getProtocol().clientCommand("GetMissionDataCsReq"), new byte[0]));
    assertEquals("GetMissionDataScRsp", session.responseName);
    assertTrue(session.response.getUnknownFields().asMap().isEmpty());
    assertEquals(
        0,
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode")));
    var rows =
        (List<?>)
            session.response.getField(
                session.response.getDescriptorForType().findFieldByName("main_mission_list"));
    var daily =
        rows.stream()
            .map(com.google.protobuf.DynamicMessage.class::cast)
            .filter(
                row ->
                    DailyMissionCatalog.mission(
                            (Integer)
                                row.getField(row.getDescriptorForType().findFieldByName("id")))
                        != null)
            .toList();
    assertEquals(1, daily.size());
    var main = daily.getFirst();
    assertEquals(FIRST, main.getField(main.getDescriptorForType().findFieldByName("id")));
    assertEquals(
        "MissionStatus_MissionDoing",
        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                main.getField(main.getDescriptorForType().findFieldByName("status")))
            .getName());
    assertNotNull(actor.getGameplay().getSubMissions().get(300020101));
    assertEquals(
        QuestState.Phase.DOING, actor.getGameplay().getSubMissions().get(300020101).getPhase());
  }

  @Test
  void concurrentRequestsCanConsumeOnlyOneAuthoredQuota() throws Exception {
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(12)) {
      var calls = new ArrayList<java.util.concurrent.Callable<Map.Entry<Integer, Retcode>>>();
      for (int index = 0; index < 24; index++) {
        int id = index % 2 == 0 ? FIRST : OTHER_GROUP;
        calls.add(() -> Map.entry(id, actor.getMissionManager().accept(id)));
      }
      var results = executor.invokeAll(calls);
      var winners = new HashSet<Integer>();
      for (var result : results) {
        var value = result.get();
        if (value.getValue() == Retcode.SUCC) winners.add(value.getKey());
        else assertEquals(Retcode.MISSION_NOT_DOING, value.getValue());
      }
      assertEquals(1, winners.size());
      assertEquals(
          1,
          actor.getGameplay().getMainMissions().keySet().stream()
              .filter(id -> id == FIRST || id == OTHER_GROUP)
              .count());
    }
  }

  @Test
  void actualPlayerDocumentReloadKeepsTheConsumedQuota() throws Exception {
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "daily_mission_acceptance");
      store.getMapper().map(Player.class, QuestState.class);
      assertEquals(Retcode.SUCC, actor.getMissionManager().accept(FIRST));
      for (int iteration = 0; iteration < 2; iteration++) {
        var base = new Player();
        set(base, "uid", 123456797);
        set(base, "gameplay", actor.getGameplay());
        store.save(base);
        var restored =
            store
                .find(Player.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", 123456797))
                .first();
        assertNotNull(restored);
        actor = fresh();
        set(actor, "gameplay", restored.getGameplay());
        rejected(OTHER_GROUP, Retcode.MISSION_NOT_DOING);
        assertEquals(Retcode.SUCC, actor.getMissionManager().accept(FIRST));
      }
    } finally {
      server.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "duplicateMission",
        "duplicateCount",
        "zeroCount",
        "negativeGroup",
        "missingCount",
        "unknownType"
      })
  void malformedCatalogReloadIsRejectedWithoutReplacingTheLastValidTables(String kind)
      throws Exception {
    switch (kind) {
      case "duplicateMission" -> dailyRows.add(dailyRows.get(0).deepCopy());
      case "duplicateCount" -> countRows.add(countRows.get(0).deepCopy());
      case "zeroCount" -> countRows.get(0).getAsJsonObject().addProperty("DailyCount", 0);
      case "negativeGroup" -> dailyRows.get(0).getAsJsonObject().addProperty("GroupID", -1);
      case "missingCount" -> countRows = new JsonArray();
      case "unknownType" -> dailyRows.get(0).getAsJsonObject().addProperty("DailyMissionType", 2);
      default -> fail(kind);
    }
    var before = DailyMissionCatalog.mission(FIRST);
    assertNotNull(before);
    assertThrows(IllegalStateException.class, this::loadCatalog);
    assertSame(before, DailyMissionCatalog.mission(FIRST));
  }

  static void set(Object value, String name, Object replacement) throws Exception {
    var type = value instanceof Player ? Player.class : value.getClass();
    var field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(value, replacement);
  }
}
