package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExpeditionHarvestTest {
  final Gson gson = ResourceLoader.getGson();
  final List<Runnable> restore = new ArrayList<>();
  List<GameplayDefinitions.DailyPool> oldPool;
  JsonArray fixture;
  int deliveryLimit = Integer.MAX_VALUE;
  GameplayState restoredState;
  final Map<Integer, ExpeditionCatalog.Harvest> oldHarvests = new HashMap<>();
  final Map<Integer, ExpeditionCatalog.Team> oldTeams = new HashMap<>();
  final Map<String, GameplayDefinitions.ConstantValue> oldConstants = new HashMap<>();
  final Instant beginning = Instant.parse("2026-10-05T12:00:00Z");
  final Player player =
      new Player() {
        final Inventory actual =
            new Inventory(this) {
              public List<GameItem> addItems(List<GameItem> items, boolean hint) {
                return super.addItems(
                    items.subList(0, Math.min(deliveryLimit, items.size())).stream()
                        .map(
                            i ->
                                new GameItem(i.getExcel(), i.getCount()) {
                                  public void save() {}
                                })
                        .map(i -> (GameItem) i)
                        .toList(),
                    hint);
              }
            };

        public Inventory getInventory() {
          return actual;
        }

        public GameplayState getGameplay() {
          return restoredState == null ? super.getGameplay() : restoredState;
        }

        public void save() {}

        public void sendPacket(BasePacket packet) {}
      };
  final ExpeditionManager manager = new ExpeditionManager(player);

  class NativeSession extends GameSession {
    DynamicMessage response;

    NativeSession() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    public void send(BasePacket packet) {}

    public void sendClientPacket(String name, byte[] body) {
      try {
        response = getProtocol().clientSchema().parse(name, body);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
  }

  <T extends GameResource> void install(ExcelMap<T> map, T row) {
    var old = map.get(row.getId());
    restore.add(
        () -> {
          map.keySet().remove(row.getId());
          if (old != null) map.add(old);
        });
    map.add(row);
  }

  @BeforeEach
  void setup() throws Exception {
    player.setNickname("Expedition regression");
    try (var in = getClass().getResourceAsStream("/fixtures/expedition-harvest-4.6.json")) {
      fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    oldPool = new ArrayList<>(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    oldHarvests.putAll(ExpeditionCatalog.harvests);
    ExpeditionCatalog.harvests.clear();
    oldTeams.putAll(ExpeditionCatalog.teams);
    ExpeditionCatalog.teams.clear();
    oldConstants.putAll(ExpeditionCatalog.constants);
    ExpeditionCatalog.constants.clear();
    for (var value : fixture) {
      var r = value.getAsJsonObject();
      var row = r.getAsJsonObject("row");
      switch (r.get("kind").getAsString()) {
        case "pool" ->
            GameplayDefinitions.dailyPool.add(
                gson.fromJson(row, GameplayDefinitions.DailyPool.class));
        case "quest" -> install(GameData.getQuestExcelMap(), gson.fromJson(row, QuestExcel.class));
        case "rule" ->
            install(GameData.getFinishWayExcelMap(), gson.fromJson(row, FinishWayExcel.class));
        case "reward" -> {
          var reward = gson.fromJson(row, RewardExcel.class);
          reward.onLoad();
          install(GameData.getRewardExcelMap(), reward);
        }
        case "item" -> install(GameData.getItemExcelMap(), gson.fromJson(row, ItemExcel.class));
        case "harvest" -> {
          var h = gson.fromJson(row, ExpeditionCatalog.Harvest.class);
          ExpeditionCatalog.harvests.put(h.ExpeditionID(), h);
        }
        case "team" -> {
          var t = gson.fromJson(row, ExpeditionCatalog.Team.class);
          ExpeditionCatalog.teams.put(t.TeamID(), t);
        }
        case "constant" ->
            ExpeditionCatalog.constants.put(
                row.get("ConstValueName").getAsString(),
                gson.fromJson(row.get("Value"), GameplayDefinitions.ConstantValue.class));
      }
    }
    player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));
    player.getDailyManager().ensureDay();
    manager.setClock(Clock.fixed(beginning, ZoneOffset.UTC));
  }

  @AfterEach
  void cleanup() {
    Collections.reverse(restore);
    restore.forEach(Runnable::run);
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(oldPool);
    ExpeditionCatalog.harvests.clear();
    ExpeditionCatalog.harvests.putAll(oldHarvests);
    ExpeditionCatalog.teams.clear();
    ExpeditionCatalog.teams.putAll(oldTeams);
    ExpeditionCatalog.constants.clear();
    ExpeditionCatalog.constants.putAll(oldConstants);
  }

  @Test
  void originalExpeditionDailyObjectivesAreSelectableWithoutFreeCompletion() {
    for (int id : List.of(2100002, 2100133)) {
      var q = player.getGameplay().getDailyQuests().get(id);
      assertNotNull(q, "Original expedition objective is still excluded: " + id);
      assertEquals(0, q.getProgress());
    }
    assertEquals(0, player.getGameplay().getDailyPoints());
  }

  @Test
  void nativeExpeditionQueryHasAnAuthenticatedServerHandler() throws Exception {
    var session = new NativeSession();
    assertTrue(
        VersionedPacketHandler.handle(session, 2534, new byte[0]),
        "4.6 expedition query is unhandled");
    assertNotNull(session.response);
    assertEquals(
        0,
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode")));
  }

  @Test
  void nativeExpeditionTakeHasAnAuthenticatedServerHandler() throws Exception {
    var session = new NativeSession();
    assertTrue(
        VersionedPacketHandler.handle(session, 2502, new byte[0]),
        "4.6 harvest request is unhandled");
    assertNotNull(session.response);
  }

  void finishMission(int id) {
    var q = new QuestState(id);
    q.advance(1, 1, 1);
    player.getGameplay().getMainMissions().put(id, q);
  }

  void unlock() {
    finishMission(1010403);
  }

  void time(long seconds) {
    manager.setClock(Clock.fixed(beginning.plusSeconds(seconds), ZoneOffset.UTC));
  }

  int stock(int id) {
    var i = player.getInventory().getMaterialByItemId(id);
    return i == null ? 0 : i.getCount();
  }

  void resetDailies() {
    player.getGameplay().getDailyQuests().replaceAll((id, q) -> new QuestState(id));
  }

  void daily(int progress) {
    for (int id : List.of(2100002, 2100133))
      assertEquals(progress, player.getGameplay().getDailyQuests().get(id).getProgress());
  }

  void starts(Integer... ids) {
    assertEquals(Retcode.SUCC, manager.set(List.of(ids)).retcode());
  }

  @Test
  void queryDoesNotCreateAssignmentsTimestampRewardsOrDailyCredit() {
    unlock();
    var before = gson.toJson(player.getGameplay());
    var view = manager.snapshot();
    assertEquals(List.of(1001, 1002, 2001, 3001, 4001), view.unlocked());
    assertEquals(2, view.teams());
    assertEquals(0, view.rewardTime());
    assertTrue(view.active().isEmpty());
    assertEquals(before, gson.toJson(player.getGameplay()));
    daily(0);
  }

  @Test
  void assignmentsRequireOriginalMissionAndRespectTwoThreeFourTeamGates() {
    assertEquals(Retcode.EXPEDITION_NOT_UNLOCKED, manager.set(List.of(1001)).retcode());
    daily(0);
    unlock();
    assertEquals(
        Retcode.EXPEDITION_TEAM_COUNT_LIMIT, manager.set(List.of(1001, 1002, 2001)).retcode());
    finishMission(1011102);
    assertEquals(3, manager.snapshot().teams());
    starts(1001, 1002, 2001);
    finishMission(1020301);
    assertEquals(4, manager.snapshot().teams());
    starts(1001, 1002, 2001, 3001);
    daily(1);
  }

  @Test
  void oneHourYieldsActualOriginalTwoMaterialUnitsWithoutImmediateFreeReward() {
    unlock();
    starts(1001);
    assertEquals(0, stock(111001));
    daily(1);
    resetDailies();
    time(3599);
    assertEquals(Retcode.EXPEDITION_NOT_FINISH, manager.take().retcode());
    daily(0);
    time(3600);
    var claim = manager.take();
    assertEquals(Retcode.SUCC, claim.retcode());
    assertEquals(List.of(1001), claim.taken());
    assertEquals(2, stock(111001));
    daily(1);
    assertEquals(2, claim.rewards().getFirst().get(111001));
    assertEquals(Retcode.EXPEDITION_NOT_FINISH, manager.take().retcode());
    assertEquals(2, stock(111001));
  }

  @ParameterizedTest
  @ValueSource(longs = {108000, 108001, 360000, 31536000})
  void storedRewardsCapAtOriginalThirtyHoursAndDoNotLeaveOverflowToReclaim(long elapsed) {
    unlock();
    starts(1001);
    time(elapsed);
    assertEquals(Retcode.SUCC, manager.take().retcode());
    assertEquals(60, stock(111001));
    assertEquals(Retcode.EXPEDITION_NOT_FINISH, manager.take().retcode());
    time(elapsed + 3600);
    assertEquals(Retcode.SUCC, manager.take().retcode());
    assertEquals(62, stock(111001));
  }

  @Test
  void hourlyAnchorPreservesPartialHourAndUsesClientVisibleStartOfHour() {
    unlock();
    time(1800);
    starts(1001);
    assertEquals(beginning.getEpochSecond(), manager.snapshot().rewardTime());
    time(7200 + 1800);
    assertEquals(Retcode.SUCC, manager.take().retcode());
    assertEquals(4, stock(111001));
    assertEquals(beginning.plusSeconds(7200).getEpochSecond(), manager.snapshot().rewardTime());
    time(10799);
    assertEquals(Retcode.EXPEDITION_NOT_FINISH, manager.take().retcode());
    time(10800);
    assertEquals(Retcode.SUCC, manager.take().retcode());
    assertEquals(6, stock(111001));
  }

  @Test
  void replacingAssignmentsSettlesOldRatesThenStartsNewRates() {
    unlock();
    starts(1001);
    time(7200);
    var changed = manager.set(List.of(1002));
    assertEquals(Retcode.SUCC, changed.retcode());
    assertEquals(List.of(1001), changed.taken());
    assertEquals(List.of(1002), changed.active());
    assertEquals(4, stock(111001));
    assertEquals(0, stock(111011));
    time(10800);
    manager.take();
    assertEquals(2, stock(111011));
  }

  @Test
  void unchangedAssignmentsAndReorderingDoNotResetAccrualOrCreditDaily() {
    unlock();
    starts(1001, 1002);
    resetDailies();
    time(3600);
    starts(1002, 1001);
    assertEquals(beginning.getEpochSecond(), manager.snapshot().rewardTime());
    daily(0);
    assertEquals(Retcode.SUCC, manager.take().retcode());
    assertEquals(2, stock(111001));
    assertEquals(2, stock(111011));
  }

  @Test
  void cancellationSettlesActualOldRewardsAndStopsFurtherAccrual() {
    unlock();
    starts(1001);
    time(3600);
    var canceled = manager.set(List.of());
    assertEquals(Retcode.SUCC, canceled.retcode());
    assertTrue(canceled.active().isEmpty());
    assertEquals(2, stock(111001));
    time(7200);
    assertEquals(Retcode.EXPEDITION_NOT_ACCEPTED, manager.take().retcode());
    assertEquals(2, stock(111001));
  }

  @Test
  void emptyCancellationWithoutEarnedRewardDoesNotCompleteDaily() {
    unlock();
    starts(1001);
    resetDailies();
    manager.set(List.of());
    daily(0);
  }

  @Test
  void coinHarvestCreditsActualWalletAndTwoItemHarvestDeliversBothOriginalItems() {
    unlock();
    finishMission(1010701);
    starts(4001, 5001);
    time(3600);
    assertEquals(Retcode.SUCC, manager.take().retcode());
    assertEquals(1000, player.getScoin());
    assertEquals(2, stock(180004));
    assertEquals(2, stock(180005));
  }

  @Test
  void resourceRateChangeCannotRetroactivelyRewriteAlreadyDispatchedRewards() {
    unlock();
    starts(1001);
    var reward = ExpeditionCatalog.harvests.get(1001).RewardID();
    var replacement =
        gson.fromJson(
            "{\"RewardID\":" + reward + ",\"ItemID_1\":111001,\"Count_1\":99}", RewardExcel.class);
    replacement.onLoad();
    install(GameData.getRewardExcelMap(), replacement);
    time(3600);
    assertEquals(Retcode.SUCC, manager.take().retcode());
    assertEquals(2, stock(111001));
  }

  @Test
  void clockRollbackDoesNotReverseAnchorOrGrantRewards() {
    unlock();
    starts(1001);
    time(-3600);
    assertEquals(Retcode.PLAYER_DATA_ERROR, manager.take().retcode());
    assertEquals(Retcode.PLAYER_DATA_ERROR, manager.set(List.of(1002)).retcode());
    assertEquals(0, stock(111001));
    assertEquals(List.of(1001), manager.snapshot().active());
  }

  @Test
  void invalidTargetSetCannotSettleExistingRewardsOrChangeSavedState() {
    unlock();
    starts(1001);
    time(3600);
    var before = gson.toJson(player.getGameplay());
    assertEquals(Retcode.EXPEDITION_CONFIG_NOT_EXIST, manager.set(List.of(999999)).retcode());
    assertEquals(Retcode.EXPEDITION_NOT_UNLOCKED, manager.set(List.of(1003)).retcode());
    assertEquals(Retcode.REQ_PARA_INVALID, manager.set(List.of(1001, 1001)).retcode());
    assertEquals(before, gson.toJson(player.getGameplay()));
    assertEquals(0, stock(111001));
  }

  @Test
  void missingRewardConfigRefusesNewAssignmentWithoutReplacingExistingOne() {
    unlock();
    starts(1001);
    var row = ExpeditionCatalog.harvests.get(1002);
    GameData.getRewardExcelMap().keySet().remove(row.RewardID());
    assertEquals(Retcode.EXPEDITION_REWARD_CONFIG_NOT_EXIST, manager.set(List.of(1002)).retcode());
    assertEquals(List.of(1001), manager.snapshot().active());
  }

  @Test
  void fullMaterialPileRejectsWholeGrantBeforeWalletTimestampAndDailyChange() {
    unlock();
    starts(1001, 4001);
    resetDailies();
    var value =
        new GameItem(
            GameData.getItemExcelMap().get(111001),
            GameData.getItemExcelMap().get(111001).getPileLimit()) {
          public void save() {}
        };
    value.setOwner(player);
    player.getInventory().getItems().put(value.getInternalUid(), value);
    player.getInventory().getTabByItemType(value.getItemMainType()).onAddItem(value);
    time(3600);
    assertEquals(Retcode.PLAYER_DATA_ERROR, manager.take().retcode());
    assertEquals(0, player.getScoin());
    daily(0);
    assertFalse(player.getGameplay().getExpeditions().pending());
    assertEquals(beginning.getEpochSecond(), manager.snapshot().rewardTime());
  }

  @Test
  void partialDeliveryReservesReceiptAndBlocksReplaysReplacementsAndAutomaticQueries() {
    unlock();
    starts(1001, 1002);
    resetDailies();
    time(3600);
    deliveryLimit = 1;
    assertEquals(Retcode.PLAYER_DATA_ERROR, manager.take().retcode());
    assertEquals(2, stock(111001) + stock(111011));
    daily(0);
    assertTrue(player.getGameplay().getExpeditions().pending());
    deliveryLimit = 2;
    assertEquals(Retcode.SYSTEM_BUSY, manager.take().retcode());
    assertEquals(Retcode.SYSTEM_BUSY, manager.set(List.of(2001)).retcode());
    var before = gson.toJson(player.getGameplay());
    manager.snapshot();
    assertEquals(before, gson.toJson(player.getGameplay()));
    restoredState = gson.fromJson(before, GameplayState.class);
    assertEquals(Retcode.SYSTEM_BUSY, manager.take().retcode());
    assertEquals(2, stock(111001) + stock(111011));
  }

  @Test
  void jsonReloadKeepsFrozenRatesReceiptsAnchorAndPreventsRepeatedHarvest() {
    unlock();
    starts(1001);
    time(3600);
    manager.take();
    restoredState = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
    assertEquals(List.of(1001), manager.snapshot().active());
    assertEquals(Retcode.EXPEDITION_NOT_FINISH, manager.take().retcode());
    assertEquals(2, stock(111001));
    time(7200);
    manager.take();
    assertEquals(4, stock(111001));
    assertEquals(2, player.getGameplay().getExpeditions().getSequence());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
  void malformedNativeAssignmentWireDoesNotChangeStateOrDailies(int mode) throws Exception {
    unlock();
    var buffer = new ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(buffer);
    switch (mode) {
      case 0 -> out.writeUInt32(10, 1001);
      case 1 -> out.writeByteArray(10, new byte[0]);
      case 2 -> out.writeByteArray(10, new byte[] {120, 0});
      case 3 -> out.writeByteArray(10, new byte[] {120, (byte) 233, 7, 8, 1});
      case 4 -> out.writeByteArray(10, new byte[] {120, (byte) 233, 7, 120, (byte) 233, 7});
      case 5 -> out.writeByteArray(10, new byte[] {120, (byte) 233});
      case 6 -> out.writeUInt32(11, 1001);
      case 7 -> {
        var row = new ByteArrayOutputStream();
        var writer = CodedOutputStream.newInstance(row);
        writer.writeUInt64(15, (1L << 32) + 1001);
        writer.flush();
        out.writeByteArray(10, row.toByteArray());
      }
    }
    out.flush();
    var before = gson.toJson(player.getGameplay());
    var session = new NativeSession();
    assertTrue(VersionedPacketHandler.handle(session, 2525, buffer.toByteArray()));
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(before, gson.toJson(player.getGameplay()));
    daily(0);
  }

  @Test
  void nativeSetUsesOriginalFieldTenAndNestedFifteenAndReturnsActualAssignment() throws Exception {
    unlock();
    var session = new NativeSession();
    assertTrue(
        VersionedPacketHandler.handle(session, 2525, new byte[] {82, 3, 120, (byte) 233, 7}));
    assertEquals(
        0,
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode")));
    var rows =
        (List<?>)
            session.response.getField(
                session.response.getDescriptorForType().findFieldByName("accept_multi_expedition"));
    assertEquals(1, rows.size());
    var row = (DynamicMessage) rows.getFirst();
    assertEquals(1001, row.getField(row.getDescriptorForType().findFieldByNumber(15)));
    daily(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {2534, 2502})
  void emptyNativeRequestsRejectUnknownPayloadWithoutMutatingState(int command) throws Exception {
    unlock();
    var before = gson.toJson(player.getGameplay());
    var session = new NativeSession();
    VersionedPacketHandler.handle(session, command, new byte[] {8, 1});
    assertEquals(
        Retcode.REQ_PARA_INVALID.getVal(),
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode")));
    assertEquals(before, gson.toJson(player.getGameplay()));
    daily(0);
  }

  @Test
  void nativeUnauthenticatedRequestsAreIgnored() throws Exception {
    var session = new NativeSession();
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(
        VersionedPacketHandler.handle(session, 2525, new byte[] {82, 3, 120, (byte) 233, 7}));
    assertNull(session.response);
    daily(0);
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010, 1011, 2001, 3001, 4001, 5001,
        5002, 5003, 5004, 5005, 5006, 5007, 5008, 5009
      })
  void eachOriginalHarvestPaysItsOwnFrozenHourlyRewardAfterItsActualMissionGate(int id) {
    var row = ExpeditionCatalog.harvests.get(id);
    for (var gate : row.UnlockCondition()) finishMission(Integer.parseInt(gate.Param()));
    var rates = ExpeditionCatalog.rates(row);
    assertNotNull(rates);
    starts(id);
    time(3600);
    var result = manager.take();
    assertEquals(Retcode.SUCC, result.retcode());
    assertEquals(List.of(id), result.taken());
    for (var rate : rates.entrySet())
      assertEquals(
          rate.getValue().intValue(),
          rate.getKey() == 2 ? player.getScoin() : stock(rate.getKey()));
  }

  @Test
  void dispatchedOriginalObjectivesEachClaimOneHundredActualActivityPointsOnlyOnce() {
    unlock();
    starts(1001);
    assertEquals(0, player.getGameplay().getDailyPoints());
    for (int id : List.of(2100002, 2100133))
      assertEquals(Retcode.SUCC, player.getQuestManager().claim(id).retcode());
    assertEquals(200, player.getGameplay().getDailyPoints());
    for (int id : List.of(2100002, 2100133))
      assertEquals(
          Retcode.QUEST_REWARD_ALREADY_TAKEN, player.getQuestManager().claim(id).retcode());
    assertEquals(200, player.getGameplay().getDailyPoints());
    assertEquals(0, stock(23));
  }

  @Test
  void concurrentHarvestRequestsGrantOneActualHourOnlyOnce() throws Exception {
    unlock();
    starts(1001);
    time(3600);
    try (var executor = Executors.newFixedThreadPool(8)) {
      var attempts = new ArrayList<Future<ExpeditionManager.Result>>();
      for (int i = 0; i < 24; i++) attempts.add(executor.submit(manager::take));
      int success = 0;
      for (var result : attempts) if (result.get().retcode() == Retcode.SUCC) success++;
      assertEquals(1, success);
      assertEquals(2, stock(111001));
      assertEquals(1, player.getGameplay().getExpeditions().getSequence());
    }
  }

  @ParameterizedTest
  @CsvSource({"false", "true"})
  void morphiaReloadPreservesFrozenAssignmentsAndCompleteOrPendingReceipts(boolean pending)
      throws Exception {
    unlock();
    starts(1001, 1002);
    time(3600);
    deliveryLimit = pending ? 1 : 2;
    manager.take();
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "expedition-persistence");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class);
      var persisted = new Player();
      var uid = Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(persisted, 123460046);
      var gameplay = Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(persisted, player.getGameplay());
      store.save(persisted);
      var loaded = store.find(Player.class).first();
      var state = loaded.getGameplay().getExpeditions();
      assertEquals(List.of(1001, 1002), state.ids());
      assertEquals(Map.of(111001, 2), state.getAssignments().getFirst().getRates());
      assertEquals(pending, state.pending());
      assertEquals(Map.of(111001, 2, 111011, 2), state.getLastAttempt().getRewards());
      assertEquals(1, state.getLastAttempt().getHours());
      assertEquals(!pending, loaded.getRewardLedger().granted(state.getLastAttempt().key()));
      var restored = new ExpeditionManager(loaded);
      restored.setClock(Clock.fixed(beginning.plusSeconds(3600), ZoneOffset.UTC));
      assertEquals(
          pending ? Retcode.SYSTEM_BUSY : Retcode.EXPEDITION_NOT_FINISH, restored.take().retcode());
      store.save(loaded);
      assertEquals(
          1, store.find(Player.class).first().getGameplay().getExpeditions().getSequence());
    } finally {
      server.shutdownNow();
    }
  }
}
