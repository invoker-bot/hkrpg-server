package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.RewardExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original paid TrialOrPlayer raid; prior acceptance/scene arrivals are explicit fixtures. */
class PaidMissionRaid46Test {
  @TempDir Path root;
  final MissionTrialOnlyRaid46Test source = new MissionTrialOnlyRaid46Test();
  final List<RewardExcel> oldRewards = new ArrayList<>();
  RewardActor player;

  /** Inventory delivery is isolated here; the native H2 probe verifies real item documents. */
  static class RewardActor extends MultiStageMissionRaid46Test.SceneActor {
    int attempts;
    boolean complete = true;
    Runnable onGrant;
    final Map<Integer, Integer> materials = new TreeMap<>();
    final Inventory inventory =
        new Inventory(this) {
          @Override
          public GrantResult grantItems(ItemParamMap values, boolean hint) {
            if (values.isEmpty()) return new GrantResult(List.of(), true);
            attempts++;
            addSCoin(values.get(2));
            for (var item : values)
              if (item.getIntKey() != 2)
                materials.merge(item.getIntKey(), item.getIntValue(), Math::addExact);
            var callback = onGrant;
            onGrant = null;
            if (callback != null) callback.run();
            return new GrantResult(values.toItemList(), complete);
          }
        };

    RewardActor(MissionRaidTrialLifecycle46Test fixture) {
      super(fixture);
      MissionTrialProvisioning46Test.set(this, "name", "paid-raid-fixture");
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    oldRewards.addAll(GameData.getRewardExcelMap().values());
    source.root = root;
    source.prepare();
    source.installRecords("paid-mission-raid-4.6.json");
    var helper = source.helper;
    player = new RewardActor(helper);
    var formal = helper.player.getAvatarById(199986);
    formal.setOwner(player);
    player.getAvatars().getAvatars().put(199986, formal);
    player.getCurrentLineup().getAvatars().add(199986);
    helper.player = player;
    helper.fixture.player = player;
    source.player = player;
    int main = 2000703;
    String directory = "Config/Level/Mission/" + main + "/";
    var records = helper.original.getAsJsonObject("records");
    var info =
        helper.gson.fromJson(
            records.getAsJsonArray(directory + "MissionInfo_" + main + ".json").get(0),
            MissionInfo.class);
    var subs = new ArrayList<MissionInfo.SubMissionInfo>();
    for (var raw :
        records
            .getAsJsonArray(directory + "MissionInfo_" + main + ".partial.json")
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("SubMissionList")) {
      var sub = helper.gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
      if (records.has(sub.getMissionJsonPath())) {
        var script = records.getAsJsonArray(sub.getMissionJsonPath()).get(0);
        var task = helper.gson.fromJson(script, MissionTaskInfo.class);
        task.retainSourceDefinition(script);
        sub.setTask(task);
      }
      GameplayDefinitions.subs.put(sub.getId(), sub);
      subs.add(sub);
    }
    MissionTrialProvisioning46Test.setField(
        info, MissionInfo.class, "subMissionList", subs.toArray(MissionInfo.SubMissionInfo[]::new));
    GameData.getMissionInfos().put(main, info);
    player.getGameplay().getMainMissions().put(2000702, new QuestState(2000702));
    helper.position(player, 2000101);
  }

  @AfterEach
  void restore() throws Exception {
    source.restore();
    GameData.getRewardExcelMap().values().clear();
    oldRewards.forEach(GameData.getRewardExcelMap()::add);
  }

  @Test
  void originalPaidRaidIsAcceptedWithItsUnmodifiedInclusionAndRewardRows() {
    var row = GameplayDefinitions.raids.get(400030100L);
    var plan = RaidTeams.plan(row);
    assertNotNull(plan);
    assertEquals("TrialOrPlayer", plan.type());
    assertEquals(Map.of(17, 1006), plan.requirements());
    assertFalse(row.SkipRewardOnFinish());
    assertEquals(List.of(130007), row.RewardList());
    var reward = GameData.getRewardExcelMap().get(130007);
    assertNotNull(reward);
    var quantities = new TreeMap<Integer, Integer>();
    for (var item : reward.getRewards()) quantities.put(item.getIntKey(), item.getIntValue());
    assertEquals(Map.of(2, 30000, 213, 3, 223, 3), quantities);
    var trial =
        player.getRaidTrialManager().prepare(plan, List.of(new RaidTeams.Selection(1101006, true)));
    assertEquals(List.of(1101006), trial.party().getRoster());
    assertNotNull(MissionRaid.resource(4000301, 0));
  }

  @Test
  void originalTrialChoiceEntersWithoutPrematureCurrencyRewardsOrFormalStorageChanges() {
    assertEquals(
        Retcode.SUCC,
        new MissionRaid(player)
            .enterSelected(4000301, 0, 0, 0, List.of(new RaidTeams.Selection(1101006, true))));
    assertEquals(4000301, player.getEntryId());
    assertEquals(2000703, new MissionRaid(player).current().getMainMission());
    assertEquals(List.of(1101006), player.getCurrentLineup().getAvatars());
    assertFalse(new MissionRaid(player).current().isCompleted());
    assertEquals(0, player.getScoin());
    assertEquals(Set.of(199986), player.getAvatars().getAvatars().keySet());
    assertNull(player.getMissionManager().sub(200070318));
  }

  @Test
  void anOwnedFormalSilverWolfCanSatisfyTheOriginalAlternativeRosterGate() {
    formalChoice();
    assertEquals(Retcode.SUCC, new MissionRaid(player).enter(4000301, 0, 0, 0, List.of()));
    assertEquals(List.of(1006), player.getCurrentLineup().getAvatars());
    assertNull(new MissionRaid(player).current().getTrialParty());
    assertEquals(0, player.getScoin());
  }

  private void formalChoice() {
    var silverWolf = new GameAvatar(GameData.getAvatarExcelMap().get(1006));
    silverWolf.setOwner(player);
    player.getAvatars().getAvatars().put(1006, silverWolf);
    player.getCurrentLineup().getAvatars().clear();
    player.getCurrentLineup().getAvatars().add(1006);
  }

  private GameplayState.MissionRaidRun enterTrial() {
    assertEquals(
        Retcode.SUCC,
        new MissionRaid(player)
            .enterSelected(4000301, 0, 0, 0, List.of(new RaidTeams.Selection(1101006, true))));
    return new MissionRaid(player).current();
  }

  // Dialogue, prop and combat outcomes are explicit fixtures. Original gates/finals stay intact.
  private void isolatedGoals() {
    for (int id : List.of(301, 303, 304, 305, 306, 307, 310, 311, 313, 314, 315, 316, 317)) {
      var state = new QuestState(200070000 + id);
      state.advance(1, 1, 123);
      player.getGameplay().getSubMissions().put(state.getQuestId(), state);
    }
  }

  @Test
  void acceptedOriginalCreateTaskAppendsTrialToTheFormalAlternativeParty() {
    formalChoice();
    assertEquals(Retcode.SUCC, new MissionRaid(player).enter(4000301, 0, 0, 0, List.of()));
    player.getGameplay().getSubMissions().put(200070318, new QuestState(200070318));
    player.getMissionManager().refresh();
    assertEquals(List.of(1006, 1101006), player.getCurrentLineup().getAvatars());
    assertTrue(MissionStateRecords.subCompleted(player, 200070318));
    assertInstanceOf(emu.lunarcore.game.trial.TrialAvatar.class, player.getAvatarById(1101006));
    assertTrue(new MissionRaid(player).activeFor(2000703));
    assertEquals(0, player.attempts);
    assertEquals(Set.of(199986, 1006), player.getAvatars().getAvatars().keySet());
  }

  @Test
  void aCreditOverflowDoesNotReserveOrDeliverAnyOfTheAuthoredRewards() {
    var run = enterTrial();
    MissionTrialProvisioning46Test.set(player, "scoin", Integer.MAX_VALUE - 100);
    isolatedGoals();
    player.getMissionManager().refresh();
    assertEquals(0, player.attempts);
    assertFalse(run.getPayout().isReserved());
    assertFalse(run.isCompleted());
    assertFalse(player.getGameplay().getRewards().containsKey("raid:4000301:0"));
    assertEquals(Integer.MAX_VALUE - 100, player.getScoin());
  }

  @Test
  void originalCompletionPaysAllThreeItemsExactlyOnceAndARealReturnReceipt() {
    var run = enterTrial();
    isolatedGoals();
    player.onGrant = () -> player.getMissionManager().refresh();
    player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainCompleted(player, 2000703));
    assertTrue(run.isCompleted());
    assertTrue(run.getPayout().isReserved());
    assertTrue(run.getPayout().isPaid());
    assertEquals(1, player.attempts);
    assertEquals(30000, player.getScoin());
    assertEquals(Map.of(213, 3, 223, 3), player.materials);
    for (int i = 0; i < 4; i++) {
      player.getMissionManager().refresh();
      new MissionRaid(player).settle();
    }
    assertEquals(Retcode.SUCC, new MissionRaid(player).leave(4000301, false));
    assertEquals(2000101, player.getEntryId());
    assertEquals(2000101, run.getFinishedReturn().getEntry());
    assertEquals(1, player.attempts);
    assertEquals(30000, player.getScoin());
  }

  @Test
  void finalFlagsWithoutTheOriginalDependencyClosureDoNotAwardRewards() {
    var run = enterTrial();
    for (int id : List.of(2000703, 200070317)) {
      var state = new QuestState(id);
      state.advance(1, 1, 123);
      (id == 2000703
              ? player.getGameplay().getMainMissions()
              : player.getGameplay().getSubMissions())
          .put(id, state);
    }
    new MissionRaid(player).settle();
    assertFalse(run.isCompleted());
    assertEquals(0, player.attempts);
    assertEquals(0, player.getScoin());
  }

  @Test
  void anInterruptedPartialDeliveryRemainsPendingThroughPauseAndFinishedGoalReentry() {
    var run = enterTrial();
    player.complete = false;
    isolatedGoals();
    player.getMissionManager().refresh();
    assertEquals(1, player.attempts);
    assertFalse(run.isCompleted());
    assertFalse(run.getPayout().isPaid());
    assertFalse(player.getRewardLedger().granted("raid:4000301:0"));
    assertEquals(Retcode.SUCC, new MissionRaid(player).leave(4000301, false));
    assertNull(run.getFinishedReturn());
    player.complete = true;
    assertEquals(
        Retcode.SUCC,
        new MissionRaid(player)
            .enterSelected(4000301, 0, 0, 0, List.of(new RaidTeams.Selection(1101006, true))));
    player.getMissionManager().refresh();
    assertFalse(run.isCompleted());
    assertFalse(run.getPayout().isPaid());
    assertEquals(1, player.attempts);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"missing", "source", "rewards", "items", "rewardSources", "itemSources", "paid"})
  void damagedFrozenPayoutsCannotAuthorizeTheRaidOrGrantItems(String damage) throws Exception {
    var run = enterTrial();
    var payout = run.getPayout();
    switch (damage) {
      case "missing" ->
          MissionTrialProvisioning46Test.setField(
              run, GameplayState.MissionRaidRun.class, "payout", null);
      case "source" ->
          MissionTrialProvisioning46Test.setField(
              payout, MissionRaidPayout.class, "source", "changed");
      case "rewards" -> payout.getRewards().clear();
      case "items" -> payout.getItems().put(2, 30001);
      case "rewardSources" -> payout.getRewardSources().put(130007, "changed");
      case "itemSources" -> payout.getItemSources().clear();
      case "paid" ->
          MissionTrialProvisioning46Test.setField(payout, MissionRaidPayout.class, "paid", true);
    }
    isolatedGoals();
    assertFalse(new MissionRaid(player).owns(2000703));
    new MissionRaid(player).settle();
    assertEquals(0, player.attempts);
    assertFalse(run.isCompleted());
  }

  @Test
  void theAlreadySelectedOriginalTrialSatisfiesAnAcceptedCreateConditionWithoutAnotherActor() {
    var run = enterTrial();
    player.getGameplay().getSubMissions().put(200070318, new QuestState(200070318));
    player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(player, 200070318));
    assertEquals(List.of(1101006), player.getCurrentLineup().getAvatars());
    assertNull(run.getMissionTrials().getParty());
    assertTrue(run.getMissionTrials().getExecuted().isEmpty());
    assertEquals(Set.of(1101006), player.getAvatars().getTempAvatars().keySet());
  }

  @Test
  void aFormalPartyWithoutSilverWolfCannotStartTheOriginalRaid() {
    assertEquals(
        Retcode.RAID_AVATAR_NOT_MATCH, new MissionRaid(player).enter(4000301, 0, 0, 0, List.of()));
    assertNull(new MissionRaid(player).current());
    assertEquals(0, player.attempts);
  }

  @Test
  void aPaidRaidCannotFreezeAnIncompleteMainMissionSource() throws Exception {
    MissionTrialProvisioning46Test.setField(
        GameData.getMissionInfos().get(2000703), MissionInfo.class, "subMissionList", null);
    assertNull(MissionRaid.resource(4000301, 0));
    assertNull(new MissionRaid(player).current());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"receipt", "cancelMain", "cancelSub", "reward", "item", "mission", "script"})
  void changedResourcesOrUnownedCompletionReceiptsCannotPay(String damage) throws Exception {
    var run = enterTrial();
    switch (damage) {
      case "receipt" -> {
        var receipt = new GameplayState.Receipt(0);
        receipt.complete();
        player.getGameplay().getRewards().put("raid:4000301:0", receipt);
      }
      case "cancelMain" -> player.getGameplay().getCancelledMissions().add(2000703);
      case "cancelSub" -> player.getGameplay().getCancelledSubs().add(200070303);
      case "reward" -> GameData.getRewardExcelMap().get(130007).getRewards().add(2, 1);
      case "item" -> GameData.getItemExcelMap().keySet().remove(223);
      case "mission" ->
          MissionTrialProvisioning46Test.setField(
              GameData.getMissionInfos().get(2000703),
              MissionInfo.class,
              "finishSubMissionList",
              new int[] {200070316});
      case "script" ->
          GameplayDefinitions.subs
              .get(200070318)
              .getTask()
              .retainSourceDefinition(com.google.gson.JsonParser.parseString("{}"));
    }
    isolatedGoals();
    if (!damage.equals("cancelMain")) {
      var main = new QuestState(2000703);
      main.advance(1, 1, 123);
      player.getGameplay().getMainMissions().put(2000703, main);
    }
    new MissionRaid(player).settle();
    assertEquals(0, player.attempts);
    assertFalse(run.isCompleted());
    assertFalse(run.getPayout().isPaid());
  }

  @Test
  void savedGrantedReceiptFinishesTheReservedPlanWithoutDeliveringAgain() throws Exception {
    var run = enterTrial();
    isolatedGoals();
    var main = new QuestState(2000703);
    main.advance(1, 1, 123);
    player.getGameplay().getMainMissions().put(2000703, main);
    MissionTrialProvisioning46Test.setField(
        run.getPayout(), MissionRaidPayout.class, "reserved", true);
    var receipt = new GameplayState.Receipt(0);
    receipt.complete();
    player.getGameplay().getRewards().put("raid:4000301:0", receipt);
    new MissionRaid(player).settle();
    assertTrue(run.isCompleted());
    assertTrue(run.getPayout().isPaid());
    assertEquals(0, player.attempts);
  }

  @Test
  void simultaneousRefreshesDeliverTheRaidRewardOnlyOnce() throws Exception {
    var run = enterTrial();
    isolatedGoals();
    var latch = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 6; i++)
        futures.add(
            pool.submit(
                () -> {
                  try {
                    latch.await();
                    player.getMissionManager().refresh();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                  }
                }));
      latch.countDown();
      for (var future : futures) future.get(20, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertTrue(run.isCompleted());
    assertEquals(1, player.attempts);
    assertEquals(Map.of(213, 3, 223, 3), player.materials);
    assertEquals(30000, player.getScoin());
  }

  @Test
  void materialStackCapacityIsCheckedBeforeAnyRewardReservation() {
    var run = enterTrial();
    var item = new GameItem(GameData.getItemExcelMap().get(213), 99998);
    item.setOwner(player);
    player
        .getInventory()
        .getTab(emu.lunarcore.game.inventory.tabs.InventoryTabType.MATERIAL)
        .onAddItem(item);
    isolatedGoals();
    player.getMissionManager().refresh();
    assertEquals(0, player.attempts);
    assertFalse(run.getPayout().isReserved());
    assertFalse(player.getGameplay().getRewards().containsKey("raid:4000301:0"));
    item.setCount(0);
    player.getMissionManager().refresh();
    assertEquals(1, player.attempts);
    assertTrue(run.isCompleted());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void actualPlayerMorphiaDocumentsPreserveEnteredPendingAndPaidPlans(int phase) throws Exception {
    var run = enterTrial();
    if (phase > 0) {
      isolatedGoals();
      player.complete = phase == 2;
      player.getMissionManager().refresh();
    }
    var expected = source.helper.gson.toJson(player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "paid-raid-document-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      MissionTrialProvisioning46Test.set(document, "uid", player.getUid());
      MissionTrialProvisioning46Test.set(document, "gameplay", player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var saved =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(expected, source.helper.gson.toJson(saved.getGameplay()));
        var restored = saved.getGameplay().getMissionRaids().get(4000301);
        assertTrue(restored.getPayout().matches(GameplayDefinitions.raids.get(400030100L)));
        assertEquals(run.getPayout().isReserved(), restored.getPayout().isReserved());
        assertEquals(run.getPayout().isPaid(), restored.getPayout().isPaid());
        store.save(saved);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
