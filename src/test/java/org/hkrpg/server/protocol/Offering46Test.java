package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class Offering46Test {
  @TempDir Path root;
  final DiceCombat46Test fixture = new DiceCombat46Test();
  static final int POINT = 264999, REWARD = 199998900;
  final Map<Integer, RewardExcel> oldRewards = new HashMap<>();

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    fixture.oldItems.put(POINT, GameData.getItemExcelMap().get(POINT));
    GameData.getItemExcelMap()
        .add(
            new Gson()
                .fromJson(
                    "{\"ID\":264999,\"ItemMainType\":\"Material\",\"PileLimit\":99999}",
                    ItemExcel.class));
    fixture.table("OfferingTypeConfig", "[{\"ID\":12,\"ItemID\":264999,\"MaxLevel\":5}]");
    var rows = new ArrayList<Map<String, Integer>>();
    for (int level = 1; level <= 5; level++) {
      rows.add(Map.of("TypeID", 12, "Level", level, "ItemCost", 50, "RewardID", REWARD + level));
      oldRewards.put(REWARD + level, GameData.getRewardExcelMap().get(REWARD + level));
      var reward =
          new Gson()
              .fromJson(
                  "{\"RewardID\":" + (REWARD + level) + ",\"ItemID_1\":264996,\"Count_1\":1}",
                  RewardExcel.class);
      reward.onLoad();
      GameData.getRewardExcelMap().add(reward);
    }
    fixture.table("OfferingLevelConfig", new Gson().toJson(rows));
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.restore();
    oldRewards.forEach(
        (id, value) -> {
          if (value == null) GameData.getRewardExcelMap().keySet().remove((int) id);
          else GameData.getRewardExcelMap().add(value);
        });
  }

  DynamicMessage request(DiceCombat46Test.Session s, String name, List<Integer> levels, int entity)
      throws Exception {
    var b = Gameplay46.builder(s, name);
    if (name.equals("GetOfferingInfoCsReq")) Gameplay46.add(b, "offering_id_list", 12);
    else {
      Gameplay46.set(b, "offering_id", 12);
      Gameplay46.set(b, "interacted_prop_entity_id", entity);
      if (name.equals("TakeOfferingRewardCsReq"))
        levels.forEach(level -> Gameplay46.add(b, "take_reward_level_list", level));
    }
    assertTrue(
        VersionedPacketHandler.handle(
            s, s.getProtocol().clientCommand(name), b.build().toByteArray()),
        "The current 4.6 Offering request must be dispatched");
    return s.response;
  }

  DynamicMessage query(DiceCombat46Test.Session s) throws Exception {
    var m = request(s, "GetOfferingInfoCsReq", List.of(), 0);
    assertEquals(0, Gameplay46.number(m, "retcode"));
    var rows = (List<?>) Gameplay46.value(m, "offering_info_list");
    assertEquals(1, rows.size());
    return (DynamicMessage) rows.getFirst();
  }

  DynamicMessage submit(DiceCombat46Test.Session s) throws Exception {
    return request(s, "SubmitOfferingItemCsReq", List.of(), 0);
  }

  void progress(DynamicMessage info, int level, int current, int total) {
    assertEquals(12, Gameplay46.number(info, "offering_id"));
    assertEquals(level, Gameplay46.number(info, "offering_level"));
    assertEquals(current, Gameplay46.number(info, "level_exp"));
    assertEquals(total, Gameplay46.number(info, "total_exp"));
  }

  @Test
  void aQueryStartsAtZeroAndNeverManufacturesMaximumOfferingLevel() throws Exception {
    var s = new DiceCombat46Test.Session();
    progress(query(s), 0, 0, 0);
    assertEquals(0, s.saves);
  }

  @Test
  void submissionConsumesRealPointsAndCarriesPartialProgressBetweenLevels() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 25);
    assertEquals(0, Gameplay46.number(submit(s), "retcode"));
    progress(query(s), 0, 25, 25);
    fixture.stock(s, POINT, 40);
    assertEquals(0, Gameplay46.number(submit(s), "retcode"));
    progress(query(s), 1, 15, 65);
    assertFalse(s.player.getInventory().hasItem(POINT, 1));
    assertTrue(((List<?>) Gameplay46.value(query(s), "has_taken_reward_id_list")).isEmpty());
  }

  @Test
  void reachingTheCapKeepsUnusedPointsAndRepeatedSubmissionCannotConsumeMore() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 350);
    assertEquals(0, Gameplay46.number(submit(s), "retcode"));
    progress(query(s), 5, 0, 250);
    assertEquals(100, s.player.getInventory().getMaterialByItemId(POINT).getCount());
    assertNotEquals(0, Gameplay46.number(submit(s), "retcode"));
    assertEquals(100, s.player.getInventory().getMaterialByItemId(POINT).getCount());
  }

  @Test
  void aRealOfferingLevelUnlocksTheSourceDiceGoodAndLevelFourDoesNot() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, DiceCombat46Test.COIN, 10);
    fixture.stock(s, POINT, 200);
    assertEquals(0, Gameplay46.number(submit(s), "retcode"));
    assertNotEquals(0, fixture.buy(s, 1, 1202));
    fixture.stock(s, POINT, 50);
    assertEquals(0, Gameplay46.number(submit(s), "retcode"));
    assertEquals(0, fixture.buy(s, 1, 1202));
    assertTrue(s.player.getInventory().hasItem(DiceCombat46Test.DICE, 1));
    assertEquals(8, s.player.getInventory().getMaterialByItemId(DiceCombat46Test.COIN).getCount());
  }

  @Test
  void unearnedOrDuplicateBatchRewardsNeverPartiallyGrant() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 50);
    assertEquals(0, Gameplay46.number(submit(s), "retcode"));
    assertNotEquals(
        0, Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1, 2), 0), "retcode"));
    assertNotEquals(
        0, Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1, 1), 0), "retcode"));
    assertFalse(s.player.getInventory().hasItem(DiceCombat46Test.COIN, 1));
    assertTrue(((List<?>) Gameplay46.value(query(s), "has_taken_reward_id_list")).isEmpty());
  }

  @Test
  void eachEarnedLevelRewardIsDeliveredAndReportedOnlyOnce() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 250);
    assertEquals(0, Gameplay46.number(submit(s), "retcode"));
    assertEquals(
        0, Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1, 5), 0), "retcode"));
    assertEquals(2, s.player.getInventory().getMaterialByItemId(DiceCombat46Test.COIN).getCount());
    assertEquals(List.of(1, 5), Gameplay46.value(query(s), "has_taken_reward_id_list"));
    assertNotEquals(
        0, Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1, 5), 0), "retcode"));
    assertEquals(2, s.player.getInventory().getMaterialByItemId(DiceCombat46Test.COIN).getCount());
  }

  @Test
  void missingPointsDoNotCreateProgressOrASave() throws Exception {
    var s = new DiceCombat46Test.Session();
    assertEquals(Retcode.ITEM_NOT_ENOUGH.getVal(), Gameplay46.number(submit(s), "retcode"));
    progress(query(s), 0, 0, 0);
    assertEquals(0, s.saves);
  }

  @Test
  void aClosedActivityCannotDonateOrClaimHistoricalDiceOfferingRewards() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 250);
    GameplayDefinitions.windows.clear();
    assertNotEquals(0, Gameplay46.number(submit(s), "retcode"));
    assertNotEquals(
        0, Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1), 0), "retcode"));
    assertEquals(250, s.player.getInventory().getMaterialByItemId(POINT).getCount());
    assertEquals(0, s.saves);
  }

  @Test
  void anUnboundMapPropCannotBeSubstitutedForTheDiceMenuSubmission() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 250);
    assertNotEquals(
        0, Gameplay46.number(request(s, "SubmitOfferingItemCsReq", List.of(), 99999), "retcode"));
    assertEquals(250, s.player.getInventory().getMaterialByItemId(POINT).getCount());
    assertEquals(0, s.saves);
  }

  @Test
  void missingOrDuplicateLevelCostsRejectSubmissionWithoutConsumingPoints() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 250);
    fixture.table(
        "OfferingLevelConfig",
        "[{\"TypeID\":12,\"Level\":1,\"ItemCost\":50,\"RewardID\":199998901}]");
    assertNotEquals(0, Gameplay46.number(submit(s), "retcode"));
    assertEquals(250, s.player.getInventory().getMaterialByItemId(POINT).getCount());
  }

  @Test
  void aFullRewardStackDoesNotReserveAClaimThatCouldNeverFit() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 50);
    submit(s);
    fixture.stock(s, DiceCombat46Test.COIN, 99);
    int before = s.saves;
    assertEquals(
        Retcode.ITEM_REWARD_EXCEED_LIMIT.getVal(),
        Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1), 0), "retcode"));
    assertEquals(before, s.saves);
    assertTrue(s.player.getGameplay().getOfferings().get(12).getClaims().isEmpty());
  }

  @Test
  void anOfferingAvatarRewardCreatesItsRealBaseRosterAndOneNativeAcquisition() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 50);
    submit(s);
    var reward =
        new Gson()
            .fromJson(
                "{\"RewardID\":" + (REWARD + 1) + ",\"ItemID_1\":264001,\"Count_1\":1}",
                RewardExcel.class);
    reward.onLoad();
    GameData.getRewardExcelMap().add(reward);
    assertEquals(
        0, Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1), 0), "retcode"));
    var owned = s.player.getGameplay().getDiceCombat().getAvatars().get(DiceCombat46Test.AVATAR);
    assertNotNull(owned, "A real offering reward token must also unlock the Dice avatar");
    assertEquals(1, owned.getLevel());
    assertEquals(List.of(DiceCombat46Test.DICE, DiceCombat46Test.DICE), owned.getDice());
    assertEquals(1, Collections.frequency(s.messages, "AddItemRsp"));
    request(s, "TakeOfferingRewardCsReq", List.of(1), 0);
    assertEquals(1, Collections.frequency(s.messages, "AddItemRsp"));
  }

  @Test
  void emptyQueriesMatchTheSourceWithoutListingOrCompletingUnknownOfferings() throws Exception {
    var s = new DiceCombat46Test.Session();
    assertTrue(VersionedPacketHandler.handle(s, 6930, new byte[0]));
    assertEquals(0, Gameplay46.number(s.response, "retcode"));
    assertTrue(((List<?>) Gameplay46.value(s.response, "offering_info_list")).isEmpty());
    assertEquals(0, s.saves);
  }

  @Test
  void packedAndUnpackedQueryListsUseTheVerifiedCommandAndResponse() throws Exception {
    var s = new DiceCombat46Test.Session();
    for (var bytes : List.of(new byte[] {40, 12}, new byte[] {42, 1, 12})) {
      assertTrue(VersionedPacketHandler.handle(s, 6930, bytes));
      assertEquals(0, Gameplay46.number(s.response, "retcode"));
      assertEquals(6936, s.getProtocol().clientCommand(s.responseName));
      progress(
          (DynamicMessage)
              ((List<?>) Gameplay46.value(s.response, "offering_info_list")).getFirst(),
          0,
          0,
          0);
    }
  }

  @Test
  void malformedUnsignedPackedUnknownAndDuplicateScalarsCannotDebit() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 250);
    for (var bytes :
        List.of(
            new byte[] {80, 12, 80, 12},
            new byte[] {80, -116, -128, -128, -128, 16},
            new byte[] {80, 12, 8, 1},
            new byte[] {82, 1, 12},
            new byte[] {80, 12, 104, -128})) {
      assertTrue(VersionedPacketHandler.handle(s, 6937, bytes));
      assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
    }
    for (var bytes :
        List.of(
            new byte[] {42, 1, -128},
            new byte[] {42, 5, -116, -128, -128, -128, 16},
            new byte[] {40, 12, 40, 12},
            new byte[] {40, 12, 40, 11},
            new byte[] {42, 1, 0})) {
      assertTrue(VersionedPacketHandler.handle(s, 6930, bytes));
      assertNotEquals(0, Gameplay46.number(s.response, "retcode"));
    }
    assertEquals(250, s.player.getInventory().getMaterialByItemId(POINT).getCount());
    assertEquals(0, s.saves);
  }

  @Test
  void invalidClaimBatchesDoNotTurnSignedWrappedLevelsIntoEarnedRewards() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 250);
    submit(s);
    int before = s.saves;
    for (var levels : List.of(List.of(0), List.of(-1), List.of(6), List.<Integer>of()))
      assertNotEquals(
          0, Gameplay46.number(request(s, "TakeOfferingRewardCsReq", levels, 0), "retcode"));
    assertEquals(before, s.saves);
    assertFalse(s.player.getInventory().hasItem(DiceCombat46Test.COIN, 1));
  }

  @Test
  void aMissingLaterRewardRejectsTheWholeBatchBeforeAnySaveOrGrant() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 250);
    submit(s);
    int before = s.saves;
    GameData.getRewardExcelMap().keySet().remove(REWARD + 5);
    assertEquals(
        Retcode.REWARD_CONFIG_NOT_EXIST.getVal(),
        Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1, 5), 0), "retcode"));
    assertEquals(before, s.saves);
    assertFalse(s.player.getInventory().hasItem(DiceCombat46Test.COIN, 1));
  }

  @Test
  void anOverflowingCombinedRewardCannotReserveOrPartiallyDeliver() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 250);
    submit(s);
    int before = s.saves;
    GameData.getRewardExcelMap()
        .get(REWARD + 1)
        .getRewards()
        .put(DiceCombat46Test.COIN, Integer.MAX_VALUE);
    assertNotEquals(
        0, Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1, 5), 0), "retcode"));
    assertEquals(before, s.saves);
    assertTrue(s.player.getGameplay().getOfferings().get(12).getClaims().isEmpty());
  }

  @Test
  void concurrentSubmissionsConsumeOnceAndConcurrentClaimsDeliverOnce() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 250);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<Integer>>();
      for (int i = 0; i < 8; i++)
        tasks.add(() -> Offerings.submit(s.player, OfferingCatalog.read(), 12, 0).code().getVal());
      var results = pool.invokeAll(tasks);
      int successes = 0;
      for (var r : results) if (r.get() == 0) successes++;
      assertEquals(1, successes);
      tasks.clear();
      for (int i = 0; i < 8; i++)
        tasks.add(
            () ->
                Offerings.claim(s.player, OfferingCatalog.read(), 12, 0, List.of(1, 5))
                    .code()
                    .getVal());
      results = pool.invokeAll(tasks);
      successes = 0;
      for (var r : results) if (r.get() == 0) successes++;
      assertEquals(1, successes);
    }
    progress(query(s), 5, 0, 250);
    assertEquals(2, s.player.getInventory().getMaterialByItemId(DiceCombat46Test.COIN).getCount());
  }

  @Test
  void changedLevelCostsCannotRewritePreviouslyAcceptedProgress() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 65);
    submit(s);
    var rows = new ArrayList<Map<String, Integer>>();
    for (int l = 1; l <= 5; l++)
      rows.add(Map.of("TypeID", 12, "Level", l, "ItemCost", 999, "RewardID", REWARD + l));
    fixture.table("OfferingLevelConfig", new Gson().toJson(rows));
    progress(query(s), 1, 15, 65);
    fixture.stock(s, POINT, 35);
    assertEquals(0, Gameplay46.number(submit(s), "retcode"));
    progress(query(s), 2, 0, 100);
  }

  @Test
  void duplicatedSourceLevelsCannotBeAcceptedAsAMaximum() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 50);
    fixture.table(
        "OfferingLevelConfig",
        "[{\"TypeID\":12,\"Level\":1,\"ItemCost\":50,\"RewardID\":199998901},{\"TypeID\":12,\"Level\":1,\"ItemCost\":50,\"RewardID\":199998901}]");
    assertNotEquals(0, Gameplay46.number(submit(s), "retcode"));
    assertEquals(0, s.saves);
  }

  @Test
  void aMaximumValueWithNoDebitsCannotUnlockDiceOrIssueRewards() throws Exception {
    var s = new DiceCombat46Test.Session();
    var state = new GameplayState.Offering(OfferingCatalog.read());
    var f = GameplayState.Offering.class.getDeclaredField("level");
    f.setAccessible(true);
    f.setInt(state, 5);
    s.player.getGameplay().getOfferings().put(12, state);
    assertFalse(Offerings.unlocked(s.player, 12, 5));
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        Gameplay46.number(request(s, "GetOfferingInfoCsReq", List.of(), 0), "retcode"));
    assertEquals(0, s.saves);
  }

  @Test
  void aPendingDebitAndAPendingRewardNeverAutomaticallyRepeat() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 250);
    var state = new GameplayState.Offering(OfferingCatalog.read());
    state.getSubmissions().add(new GameplayState.OfferingSubmission(0, 50));
    s.player.getGameplay().getOfferings().put(12, state);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(submit(s), "retcode"));
    progress(query(s), 0, 0, 0);
    state.apply(state.getSubmissions().getFirst());
    state
        .getClaims()
        .add(new GameplayState.OfferingClaim(List.of(1), Map.of(DiceCombat46Test.COIN, 1)));
    assertEquals(
        Retcode.SYSTEM_BUSY.getVal(),
        Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1), 0), "retcode"));
    assertTrue(((List<?>) Gameplay46.value(query(s), "has_taken_reward_id_list")).isEmpty());
    assertEquals(0, s.saves);
  }

  @Test
  void rejectedInventoryWriteKeepsTheReservationAndOriginalStockWithoutRedebiting()
      throws Exception {
    var s = new DiceCombat46Test.Session();
    var interrupted = new boolean[] {false};
    var item =
        new emu.lunarcore.game.inventory.GameItem(GameData.getItemExcelMap().get(POINT), 250) {
          public void save() {
            if (interrupted[0])
              throw new IllegalStateException("simulated debit-write interruption");
          }
        };
    assertTrue(s.player.getInventory().addItem(item));
    interrupted[0] = true;
    assertThrows(IllegalStateException.class, () -> submit(s));
    interrupted[0] = false;
    assertTrue(s.player.getInventory().hasItem(POINT, 250));
    progress(query(s), 0, 0, 0);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(submit(s), "retcode"));
    assertEquals(250, item.getCount());
    assertTrue(
        s.player.getGameplay().getOfferings().get(12).getSubmissions().stream()
            .noneMatch(GameplayState.OfferingSubmission::isPaid));
  }

  @Test
  void actualMorphiaRestoresEarnedLevelsFrozenPlansClaimsAndPendingReceipts() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 65);
    submit(s);
    request(s, "TakeOfferingRewardCsReq", List.of(1), 0);
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "offering12");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456787);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      gameplay.set(document, s.player.getGameplay());
      store.save(document);
      gameplay.set(
          s.player, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      progress(query(s), 1, 15, 65);
      assertEquals(List.of(1), Gameplay46.value(query(s), "has_taken_reward_id_list"));
      assertNotEquals(
          0, Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1), 0), "retcode"));
      assertEquals(
          1, s.player.getInventory().getMaterialByItemId(DiceCombat46Test.COIN).getCount());
      var state = s.player.getGameplay().getOfferings().get(12);
      assertEquals(List.of(50, 50, 50, 50, 50), state.getCosts());
      assertEquals(Map.of(DiceCombat46Test.COIN, 1), state.getClaims().getFirst().getItems());
      state.getSubmissions().add(new GameplayState.OfferingSubmission(65, 35));
      gameplay.set(document, s.player.getGameplay());
      store.save(document);
      gameplay.set(
          s.player, store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
      assertEquals(Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(submit(s), "retcode"));
      progress(query(s), 1, 15, 65);
    } finally {
      mongo.shutdownNow();
    }
  }

  @Test
  void theAuthoredOutfitRewardRemainsAnInventoryItemAndDoesNotAutoEquip() throws Exception {
    var s = new DiceCombat46Test.Session();
    var unlocks = emu.lunarcore.game.player.Player.class.getDeclaredField("unlocks");
    unlocks.setAccessible(true);
    unlocks.set(s.player, new emu.lunarcore.game.player.PlayerUnlockData());
    fixture.stock(s, POINT, 50);
    submit(s);
    fixture.oldItems.put(227005, GameData.getItemExcelMap().get(227005));
    var item =
        new Gson()
            .fromJson(
                "{\"ID\":227005,\"ItemMainType\":\"Usable\",\"ItemSubType\":\"PlayerOutfit\",\"PileLimit\":1}",
                ItemExcel.class);
    GameData.getItemExcelMap().add(item);
    var reward =
        new Gson()
            .fromJson(
                "{\"RewardID\":" + (REWARD + 1) + ",\"ItemID_1\":227005,\"Count_1\":1}",
                RewardExcel.class);
    reward.onLoad();
    GameData.getRewardExcelMap().add(reward);
    assertEquals(
        0, Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1), 0), "retcode"));
    assertTrue(s.player.getInventory().hasItem(227005, 1));
    assertEquals(1, s.player.getInventory().getMaterialByItemId(227005).getCount());
    assertFalse(
        s.player.getUnlocks().getPlayerOutfits().contains(1005),
        "Receiving an outfit item is not an equip request");
  }

  @Test
  void differentSourceLevelCostsCarryProgressAndCapAtTheirSum() throws Exception {
    var rows = new ArrayList<Map<String, Integer>>();
    var costs = List.of(50, 100, 150, 100, 100);
    for (int l = 1; l <= 5; l++)
      rows.add(
          Map.of("TypeID", 12, "Level", l, "ItemCost", costs.get(l - 1), "RewardID", REWARD + l));
    fixture.table("OfferingLevelConfig", new Gson().toJson(rows));
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 65);
    submit(s);
    progress(query(s), 1, 15, 65);
    fixture.stock(s, POINT, 100);
    submit(s);
    progress(query(s), 2, 15, 165);
    fixture.stock(s, POINT, 400);
    submit(s);
    progress(query(s), 5, 0, 500);
    assertEquals(65, s.player.getInventory().getMaterialByItemId(POINT).getCount());
  }

  void avatarReward() {
    var reward =
        new Gson()
            .fromJson(
                "{\"RewardID\":" + (REWARD + 1) + ",\"ItemID_1\":264001,\"Count_1\":1}",
                RewardExcel.class);
    reward.onLoad();
    GameData.getRewardExcelMap().add(reward);
  }

  @Test
  void laterDeckChangesCannotRewriteTheFrozenRewardAcquisitionPlan() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 50);
    submit(s);
    avatarReward();
    request(s, "TakeOfferingRewardCsReq", List.of(1), 0);
    var owned = s.player.getGameplay().getDiceCombat().getAvatars().get(DiceCombat46Test.AVATAR);
    owned.getDice().clear();
    owned.getDice().add(DiceCombat46Test.DICE);
    assertEquals(
        List.of(DiceCombat46Test.DICE, DiceCombat46Test.DICE),
        s.player
            .getGameplay()
            .getOfferings()
            .get(12)
            .getClaims()
            .getFirst()
            .getAvatars()
            .get(DiceCombat46Test.AVATAR)
            .getDice(),
        "A saved acquisition plan must remain the original deck when the owned deck changes");
  }

  @Test
  void aCompletedAcquisitionWithMissingOwnershipCannotBeReportedAsClaimed() throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 50);
    submit(s);
    avatarReward();
    request(s, "TakeOfferingRewardCsReq", List.of(1), 0);
    s.player.getGameplay().getDiceCombat().getAvatars().clear();
    int before = s.saves;
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        Gameplay46.number(request(s, "GetOfferingInfoCsReq", List.of(), 0), "retcode"));
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(),
        Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1), 0), "retcode"));
    assertEquals(before, s.saves);
  }

  @Test
  void rejectedRewardInventoryWriteKeepsTheBatchUnclaimedAndOriginalStockWithoutRepeating()
      throws Exception {
    var s = new DiceCombat46Test.Session();
    fixture.stock(s, POINT, 50);
    submit(s);
    var fail = new boolean[] {false};
    var coin =
        new emu.lunarcore.game.inventory.GameItem(
            GameData.getItemExcelMap().get(DiceCombat46Test.COIN), 1) {
          public void save() {
            if (fail[0]) throw new IllegalStateException("simulated reward-write interruption");
          }
        };
    assertTrue(s.player.getInventory().addItem(coin));
    fail[0] = true;
    assertThrows(
        IllegalStateException.class, () -> request(s, "TakeOfferingRewardCsReq", List.of(1), 0));
    fail[0] = false;
    assertEquals(1, coin.getCount());
    assertTrue(((List<?>) Gameplay46.value(query(s), "has_taken_reward_id_list")).isEmpty());
    assertEquals(
        Retcode.SYSTEM_BUSY.getVal(),
        Gameplay46.number(request(s, "TakeOfferingRewardCsReq", List.of(1), 0), "retcode"));
    assertEquals(1, coin.getCount());
  }

  @Test
  void aLegacyPlayerWithNoOfferingMapQueriesZeroWithoutASave() throws Exception {
    var s = new DiceCombat46Test.Session();
    var field = GameplayState.class.getDeclaredField("offerings");
    field.setAccessible(true);
    field.set(s.player.getGameplay(), null);
    progress(query(s), 0, 0, 0);
    assertEquals(0, s.saves);
  }
}
