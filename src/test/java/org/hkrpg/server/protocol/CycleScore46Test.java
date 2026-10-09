package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.RewardExcel;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CycleScore46Test {
  @TempDir Path root;
  final UniverseFinish46Test universe = new UniverseFinish46Test();
  static final int SECOND_REWARD = UniverseFinish46Test.REWARD + 1;

  @BeforeEach
  void resources() throws Exception {
    var excel = Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(
        excel.resolve("ConstValueCommon.json"),
        """
        [{"ConstValueName":"CycleScore_Refresh","Value":{"IntValue":7}},
         {"ConstValueName":"CycleScore_StartDate","Value":{"StringValue":"2026-02-09 04:00:00"}}]
        """);
    Files.writeString(
        excel.resolve("CycleScoreReward.json"),
        "[{\"ScoreRank\":1,\"Score\":265,\"Reward\":"
            + UniverseFinish46Test.REWARD
            + "},{\"ScoreRank\":2,\"Score\":530,\"Reward\":"
            + SECOND_REWARD
            + "}]");
    Files.writeString(
        excel.resolve("ConstValueRogue.json"),
        """
        [{"ConstRogueName":"Rogue_Explore_Score_To_Talent_Coin_Rate","ConstValue":"0.1"},
         {"ConstRogueName":"Rogue_Score_Reward_Unlock_Progress","ConstValue":"1"}]
        """);
    GameplayDefinitions.load(root);
    universe.fixture();
    // Disable the separate first-clear award: this test observes only actual cycle grants.
    GameplayDefinitions.areas.put(
        UniverseFinish46Test.AREA,
        new GameplayDefinitions.Area(UniverseFinish46Test.AREA, 19996, 1, 0, 0, Map.of(1, 265)));
    reward(SECOND_REWARD, 11);
  }

  void reward(int id, int count) {
    var value =
        new Gson()
            .fromJson(
                "{\"RewardID\":"
                    + id
                    + ",\"ItemID_1\":"
                    + UniverseFinish46Test.ITEM
                    + ",\"Count_1\":"
                    + count
                    + "}",
                RewardExcel.class);
    value.onLoad();
    GameData.getRewardExcelMap().add(value);
  }

  @AfterEach
  void clean() {
    universe.cleanup();
    GameData.getRewardExcelMap().keySet().remove(SECOND_REWARD);
  }

  DynamicMessage data(UniverseFinish46Test.Session s) {
    return (DynamicMessage) Gameplay46.value(s.response, "NFEDANFBIBE");
  }

  int claim(UniverseFinish46Test.Session s, int... ranks) throws Exception {
    var request = Gameplay46.builder(s, "CycleScoreRewardTakeRewardCsReq");
    for (int rank : ranks) Gameplay46.add(request, "LLJHJADNDKB", rank);
    assertTrue(
        VersionedPacketHandler.handle(
            s,
            s.getProtocol().clientCommand("CycleScoreRewardTakeRewardCsReq"),
            request.build().toByteArray()));
    assertEquals("CycleScoreRewardTakeRewardScRsp", s.responseName);
    return Gameplay46.number(s.response, "retcode");
  }

  void win(UniverseFinish46Test.Session s) {
    var battle = universe.battle(s, BattleEndStatus.BATTLE_END_WIN);
    s.explorer.getUniverseManager().onBattleFinish(battle);
    s.explorer.getUniverseManager().onBattleFinish(battle);
  }

  void time(UniverseFinish46Test.Session s, Instant now) {
    s.explorer.getCycleScoreManager().setClock(Clock.fixed(now, ZoneOffset.UTC));
  }

  static final Instant EPOCH = Instant.parse("2026-02-08T20:00:00Z");

  @Test
  void nativeCycleQuerySettlesASavedCompletionThatWasInterruptedBeforeItsScoreCredit()
      throws Exception {
    var s = new UniverseFinish46Test.Session();
    time(s, EPOCH);
    s.afterResultSaved =
        () -> {
          throw new IllegalStateException("simulated interruption after terminal save");
        };
    assertThrows(IllegalStateException.class, () -> win(s));
    var result = s.explorer.getGameplay().getRogueResults().values().iterator().next();
    assertTrue(result.isCycleReady());
    assertFalse(result.isCycleSettled());
    assertTrue(s.explorer.getGameplay().getCycleWeeks().isEmpty());
    s.request("CycleScoreRewardGetDataCsReq");
    assertEquals(265, Gameplay46.number(data(s), "PMJGAJGIFOA"));
    assertTrue(result.isCycleSettled());
    s.request("CycleScoreRewardGetDataCsReq");
    assertEquals(265, Gameplay46.number(data(s), "PMJGAJGIFOA"));
    assertEquals(1, Collections.frequency(s.names, "CycleScoreRewardUpdateInfoScNotify"));
  }

  @Test
  void exactRefreshBoundarySeparatesWeeksAndLateSettlementKeepsTheCompletionWeek()
      throws Exception {
    var s = new UniverseFinish46Test.Session();
    time(s, EPOCH.minusSeconds(1));
    s.request("CycleScoreRewardGetDataCsReq");
    assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
    assertEquals(0L, Gameplay46.value(data(s), "start_time"));
    assertTrue(s.explorer.getGameplay().getCycleWeeks().isEmpty());
    time(s, EPOCH.plusSeconds(7 * 86400L - 1));
    s.afterResultSaved = () -> time(s, EPOCH.plusSeconds(7 * 86400L));
    win(s);
    var result = s.explorer.getGameplay().getRogueResults().values().iterator().next();
    assertTrue(result.isCycleSettled());
    assertEquals(EPOCH.getEpochSecond(), result.getCycleStart());
    s.request("CycleScoreRewardGetDataCsReq");
    assertEquals(
        EPOCH.plusSeconds(7 * 86400L).getEpochSecond(), Gameplay46.value(data(s), "start_time"));
    assertEquals(0, Gameplay46.number(data(s), "PMJGAJGIFOA"));
    assertNotEquals(0, claim(s, 1));
    assertEquals(0, s.attempts);
    assertEquals(1, s.explorer.getGameplay().getCycleWeeks().size());
    // The old record is retained; no clock change or query recaptures its score into the new week.
    time(s, EPOCH);
    s.request("CycleScoreRewardGetDataCsReq");
    assertEquals(265, Gameplay46.number(data(s), "PMJGAJGIFOA"));
    assertEquals(0, claim(s, 1));
    time(s, EPOCH.plusSeconds(7 * 86400L));
    s.request("CycleScoreRewardGetDataCsReq");
    assertTrue(((List<?>) Gameplay46.value(data(s), "OPLNKCOLCKJ")).isEmpty());
    assertEquals(0, Gameplay46.number(data(s), "PMJGAJGIFOA"));
  }

  @Test
  void allRewardQuantitiesFreezeBeforeAnyPersistenceOrInventoryCallback() throws Exception {
    var s = new UniverseFinish46Test.Session();
    win(s);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC, s.explorer.getUniverseManager().leave(false));
    win(s);
    s.afterResultSaved =
        () -> {
          reward(UniverseFinish46Test.REWARD, 1000);
          reward(SECOND_REWARD, 2000);
        };
    assertEquals(0, claim(s, 1, 2));
    assertTrue(((List<?>) Gameplay46.value(s.response, "NGKOFMBBGNL")).isEmpty());
    assertEquals(List.of(1, 2), Gameplay46.value(data(s), "OPLNKCOLCKJ"));
    assertEquals(18, s.granted);
    assertEquals(2, s.attempts);
  }

  @Test
  void concurrentClaimsGrantOneCopyAndSavedPlayerRestoresScoreAndTakenRanks() throws Exception {
    var s = new UniverseFinish46Test.Session();
    time(s, EPOCH);
    win(s);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var results =
          pool.<org.hkrpg.server.gameplay.CycleScoreManager.Claim>invokeAll(
              List.of(
                  () -> s.explorer.getCycleScoreManager().claim(List.of(1)),
                  () -> s.explorer.getCycleScoreManager().claim(List.of(1))));
      assertEquals(
          1,
          (results.get(0).get().retcode() == emu.lunarcore.server.packet.Retcode.SUCC ? 1 : 0)
              + (results.get(1).get().retcode() == emu.lunarcore.server.packet.Retcode.SUCC
                  ? 1
                  : 0));
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, s.attempts);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "cycle-score-restore");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456784);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, s.explorer.getGameplay());
      store.save(document);
      var loaded = store.find(emu.lunarcore.game.player.Player.class).first();
      loaded.getCycleScoreManager().setClock(Clock.fixed(EPOCH, ZoneOffset.UTC));
      var resumed =
          new UniverseFinish46Test.Session() {
            public emu.lunarcore.game.player.Player getPlayer() {
              return loaded;
            }
          };
      resumed.request("CycleScoreRewardGetDataCsReq");
      assertEquals(265, Gameplay46.number(data(resumed), "PMJGAJGIFOA"));
      assertEquals(List.of(1), Gameplay46.value(data(resumed), "OPLNKCOLCKJ"));
      assertNotEquals(0, claim(resumed, 1));
      assertTrue(
          loaded.getGameplay().getRogueResults().values().iterator().next().isCycleSettled());
      assertEquals(0, resumed.attempts);
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void unknownFieldsAndUnverifiedDiscardCannotMutateTheEarnedSnapshot() throws Exception {
    var s = new UniverseFinish46Test.Session();
    win(s);
    assertTrue(
        VersionedPacketHandler.handle(
            s, s.getProtocol().clientCommand("CycleScoreRewardGetDataCsReq"), new byte[] {8, 1}));
    assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
    assertEquals(265, Gameplay46.number(data(s), "PMJGAJGIFOA"));
    var discard = Gameplay46.builder(s, "CycleScoreRewardDestroyExceedRewardCsReq");
    Gameplay46.add(discard, "BHNEOLKFNAM", 1);
    assertTrue(
        VersionedPacketHandler.handle(
            s,
            s.getProtocol().clientCommand("CycleScoreRewardDestroyExceedRewardCsReq"),
            discard.build().toByteArray()));
    assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
    assertEquals(265, Gameplay46.number(data(s), "PMJGAJGIFOA"));
    assertTrue(((List<?>) Gameplay46.value(data(s), "OPLNKCOLCKJ")).isEmpty());
    assertEquals(0, s.attempts);
  }

  @Test
  void missingOrMalformedCycleResourcesNeverRetroactivelyCreditATerminalRun() throws Exception {
    var s = new UniverseFinish46Test.Session();
    GameplayDefinitions.cycleConstants.clear();
    win(s);
    assertFalse(
        s.explorer.getGameplay().getRogueResults().values().iterator().next().isCycleReady());
    resources();
    s.explorer.getUniverseManager().settleResults();
    s.request("CycleScoreRewardGetDataCsReq");
    assertEquals(0, Gameplay46.number(data(s), "PMJGAJGIFOA"));
    assertTrue(s.explorer.getGameplay().getCycleWeeks().isEmpty());
    GameplayDefinitions.cycleConstants.put(
        "CycleScore_Refresh",
        com.google.gson.JsonParser.parseString("{\"IntValue\":0.5}").getAsJsonObject());
    s.request("CycleScoreRewardGetDataCsReq");
    assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
    assertEquals(0, s.attempts);
  }

  @Test
  void scoreOverflowLeavesTheFrozenContributionPendingWithoutAWrappedOrFakeCredit()
      throws Exception {
    var s = new UniverseFinish46Test.Session();
    time(s, EPOCH);
    var week =
        new org.hkrpg.server.gameplay.GameplayState.CycleWeek(
            EPOCH.getEpochSecond(), EPOCH.plusSeconds(7 * 86400L).getEpochSecond());
    assertTrue(week.credit(Integer.MAX_VALUE - 100));
    var window = s.explorer.getCycleScoreManager().window().orElseThrow();
    s.explorer.getGameplay().getCycleWeeks().put(window.key(), week);
    win(s);
    var result = s.explorer.getGameplay().getRogueResults().values().iterator().next();
    assertTrue(result.isCycleReady());
    assertFalse(result.isCycleSettled());
    assertEquals(265, result.getCyclePoints());
    s.request("CycleScoreRewardGetDataCsReq");
    assertEquals(Integer.MAX_VALUE - 100, Gameplay46.number(data(s), "PMJGAJGIFOA"));
    assertFalse(s.names.contains("CycleScoreRewardUpdateInfoScNotify"));
    assertFalse(week.credit(-1));
    assertEquals(Integer.MAX_VALUE - 100, week.getScore());
  }

  @Test
  void queryIsARealSevenDayWindowAndCannotInventScoreOrRewards() throws Exception {
    var s = new UniverseFinish46Test.Session();
    s.request("CycleScoreRewardGetDataCsReq");
    assertEquals(0, Gameplay46.number(data(s), "PMJGAJGIFOA"));
    assertEquals(
        7 * 86400L,
        (Long) Gameplay46.value(data(s), "end_time")
            - (Long) Gameplay46.value(data(s), "start_time"));
    assertTrue(((List<?>) Gameplay46.value(data(s), "OPLNKCOLCKJ")).isEmpty());
    assertNotEquals(0, claim(s, 1));
    assertNotEquals(0, claim(s, Integer.MAX_VALUE));
    assertEquals(0, s.attempts);
    assertTrue(s.explorer.getGameplay().getRewards().isEmpty());
  }

  @Test
  void realClearedRoomsCreditOnceAndNativeClaimReturnsOnlyActualAwardedRankAndItems()
      throws Exception {
    var s = new UniverseFinish46Test.Session();
    win(s);
    s.request("CycleScoreRewardGetDataCsReq");
    assertEquals(265, Gameplay46.number(data(s), "PMJGAJGIFOA"));
    assertEquals(1, Collections.frequency(s.names, "CycleScoreRewardUpdateInfoScNotify"));
    assertEquals(0, claim(s, 1));
    assertTrue(((List<?>) Gameplay46.value(s.response, "NGKOFMBBGNL")).isEmpty());
    assertEquals(List.of(1), Gameplay46.value(data(s), "OPLNKCOLCKJ"));
    var items = (DynamicMessage) Gameplay46.value(s.response, "ENGHMIAMPLG");
    assertEquals(7, Gameplay46.number(InitialData46Test.first(items, "item_list"), "num"));
    assertNotEquals(0, claim(s, 1));
    assertNotEquals(0, claim(s, 2));
    assertEquals(1, s.attempts);
    assertEquals(7, s.granted);
  }

  @Test
  void malformedBatchIsRejectedBeforeAnyGrantAndPartialGrantNeverBecomesTaken() throws Exception {
    var s = new UniverseFinish46Test.Session();
    win(s);
    assertNotEquals(0, claim(s, 1, 1));
    assertNotEquals(0, claim(s, 1, Integer.MAX_VALUE));
    assertEquals(0, s.attempts);
    s.complete = false;
    assertNotEquals(0, claim(s, 1));
    assertTrue(((List<?>) Gameplay46.value(data(s), "OPLNKCOLCKJ")).isEmpty());
    assertTrue(((List<?>) Gameplay46.value(s.response, "NGKOFMBBGNL")).isEmpty());
    s.complete = true;
    reward(UniverseFinish46Test.REWARD, 1000);
    assertNotEquals(0, claim(s, 1));
    s.request("CycleScoreRewardGetDataCsReq");
    assertTrue(((List<?>) Gameplay46.value(data(s), "OPLNKCOLCKJ")).isEmpty());
    assertEquals(1, s.attempts);
    assertEquals(7, s.granted);
  }
}
