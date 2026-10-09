package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.server.packet.*;
import java.util.*;
import org.junit.jupiter.api.*;

class TrialRewardWireTest {
  final TrialActivityLifecycleTest fixture = new TrialActivityLifecycleTest();
  final Gson gson = new Gson();
  ItemExcel oldItem;
  RewardExcel oldReward;

  static class Recipient extends TrialActivityLifecycleTest.Director {
    public String getName() {
      return "trial-reward-fixture";
    }

    int grants;
    boolean partial;
    final Inventory inventory =
        new Inventory(this) {
          public GrantResult grantItems(ItemParamMap map, boolean hint) {
            grants++;
            if (!partial) return super.grantItems(map, hint);
            var half = new ItemParamMap();
            half.add(1, 3);
            return new GrantResult(super.grantItems(half, hint).items(), false);
          }
        };

    public Inventory getInventory() {
      return inventory;
    }
  }

  @BeforeEach
  void install() {
    fixture.install();
    fixture.roster(TrialActivityLifecycleTest.SPECIAL);
    oldItem = GameData.getItemExcelMap().get(1);
    oldReward = GameData.getRewardExcelMap().get(TrialActivityLifecycleTest.STAGE);
    GameData.getItemExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":1,\"ItemMainType\":\"Virtual\",\"PileLimit\":1000000}", ItemExcel.class));
    var reward = gson.fromJson("{\"RewardID\":199951,\"Hcoin\":7}", RewardExcel.class);
    reward.onLoad();
    GameData.getRewardExcelMap().add(reward);
  }

  @AfterEach
  void restore() {
    fixture.restore();
    GameData.getItemExcelMap().keySet().remove(1);
    GameData.getRewardExcelMap().keySet().remove(TrialActivityLifecycleTest.STAGE);
    if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
    if (oldReward != null) GameData.getRewardExcelMap().add(oldReward);
  }

  TrialActivityLifecycleTest.Session session(Recipient player) {
    var session = new TrialActivityLifecycleTest.Session(player);
    player.setSession(session);
    return session;
  }

  void win(Recipient player) {
    var trial = player.getTrialManager().startTrial(TrialActivityLifecycleTest.STAGE);
    assertNotNull(trial);
    trial.onBattleFinish(
        fixture.trialBattle(player, true, 42),
        emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
        emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance());
  }

  void claim(TrialActivityLifecycleTest.Session session, int stage) throws Exception {
    fixture.handle(
        session,
        "TakeTrialActivityRewardCsReq",
        fixture.request(session, "TakeTrialActivityRewardCsReq", stage));
  }

  void query(TrialActivityLifecycleTest.Session session) throws Exception {
    fixture.handle(session, "GetTrialActivityDataCsReq", new byte[0]);
  }

  Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  @Test
  void unplayedTrialsCannotClaimOrAppearAsCompletedInTheNativeQuery() throws Exception {
    var p = new Recipient();
    var s = session(p);
    claim(s, TrialActivityLifecycleTest.STAGE);
    assertEquals(Retcode.QUEST_NOT_FINISH.getVal(), fixture.code(s));
    assertEquals(0, p.getHcoin());
    assertEquals(0, p.grants);
    query(s);
    assertTrue(((List<?>) field(s.response, "trial_activity_info_list")).isEmpty());
    assertEquals(0, field(s.response, "activity_stage_id"));
  }

  @Test
  void confirmedRewardGrantsTheFrozenQuantityOnceAndPersistsItsTakenState() throws Exception {
    var p = new Recipient();
    var s = session(p);
    win(p);
    GameData.getRewardExcelMap().get(TrialActivityLifecycleTest.STAGE).getRewards().put(1, 999);
    query(s);
    var info =
        (DynamicMessage) ((List<?>) field(s.response, "trial_activity_info_list")).getFirst();
    assertEquals(TrialActivityLifecycleTest.STAGE, field(info, "stage_id"));
    assertEquals(false, field(info, "taken_reward"));
    claim(s, TrialActivityLifecycleTest.STAGE);
    assertEquals(0, fixture.code(s));
    assertEquals(7, p.getHcoin());
    var item =
        (DynamicMessage)
            ((List<?>) field((DynamicMessage) field(s.response, "reward"), "item_list")).getFirst();
    assertEquals(7, field(item, "num"));
    claim(s, TrialActivityLifecycleTest.STAGE);
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(), fixture.code(s));
    assertEquals(1, p.grants);
    query(s);
    info = (DynamicMessage) ((List<?>) field(s.response, "trial_activity_info_list")).getFirst();
    assertEquals(true, field(info, "taken_reward"));
    var reloaded = gson.fromJson(gson.toJson(p.getGameplay()), GameplayState.class);
    var state = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    state.setAccessible(true);
    state.set(p, reloaded);
    claim(s, TrialActivityLifecycleTest.STAGE);
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(), fixture.code(s));
    assertEquals(7, p.getHcoin());
  }

  @Test
  void anInterruptedRewardNeverReplaysItsPartialInventoryGrant() throws Exception {
    var p = new Recipient();
    var s = session(p);
    win(p);
    p.partial = true;
    claim(s, TrialActivityLifecycleTest.STAGE);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), fixture.code(s));
    assertEquals(3, p.getHcoin());
    claim(s, TrialActivityLifecycleTest.STAGE);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture.code(s));
    assertEquals(1, p.grants);
    assertEquals(
        Map.of(1, 3),
        p.getGameplay().getTrialCompletions().get(TrialActivityLifecycleTest.STAGE).getReceived());
    query(s);
    var info =
        (DynamicMessage) ((List<?>) field(s.response, "trial_activity_info_list")).getFirst();
    assertEquals(false, field(info, "taken_reward"));
  }

  @Test
  void aFullRewardBalanceDoesNotReserveTheReceiptAndCanBeRetriedAfterSpaceIsAvailable()
      throws Exception {
    var p = new Recipient();
    var s = session(p);
    win(p);
    var balance = emu.lunarcore.game.player.Player.class.getDeclaredField("hcoin");
    balance.setAccessible(true);
    balance.setInt(p, 999997);
    claim(s, TrialActivityLifecycleTest.STAGE);
    assertEquals(Retcode.ITEM_REWARD_EXCEED_LIMIT.getVal(), fixture.code(s));
    assertEquals(0, p.grants);
    assertFalse(
        p.getGameplay()
            .getRewards()
            .containsKey(TrialRewards.key(TrialActivityLifecycleTest.STAGE)));
    balance.setInt(p, 0);
    claim(s, TrialActivityLifecycleTest.STAGE);
    assertEquals(0, fixture.code(s));
    assertEquals(7, p.getHcoin());
  }

  @Test
  void concurrentNativeClaimsCannotGrantTheSameTrialTwice() throws Exception {
    var p = new Recipient();
    var first = session(p);
    win(p);
    var second = new TrialActivityLifecycleTest.Session(p);
    try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var a =
          threads.submit(
              () -> {
                claim(first, TrialActivityLifecycleTest.STAGE);
                return fixture.code(first);
              });
      var b =
          threads.submit(
              () -> {
                claim(second, TrialActivityLifecycleTest.STAGE);
                return fixture.code(second);
              });
      assertEquals(
          Set.of(0, Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal()),
          Set.of(
              a.get(5, java.util.concurrent.TimeUnit.SECONDS),
              b.get(5, java.util.concurrent.TimeUnit.SECONDS)));
    }
    assertEquals(1, p.grants);
    assertEquals(7, p.getHcoin());
  }

  @Test
  void aTrialWithAnInvalidStartTimeRewardCannotInferAPayoutAfterResourceRepair() throws Exception {
    var p = new Recipient();
    var s = session(p);
    var reward = GameData.getRewardExcelMap().get(TrialActivityLifecycleTest.STAGE);
    GameData.getRewardExcelMap().keySet().remove(TrialActivityLifecycleTest.STAGE);
    win(p);
    GameData.getRewardExcelMap().add(reward);
    claim(s, TrialActivityLifecycleTest.STAGE);
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), fixture.code(s));
    assertEquals(0, p.grants);
    assertFalse(
        p.getGameplay()
            .getRewards()
            .containsKey(TrialRewards.key(TrialActivityLifecycleTest.STAGE)));
  }

  @Test
  void unauthenticatedTrialQueriesAndClaimsCannotPublishOrGrant() throws Exception {
    var p = new Recipient();
    var s = session(p);
    win(p);
    s.response = null;
    s.setState(SessionState.WAITING_FOR_TOKEN);
    query(s);
    claim(s, TrialActivityLifecycleTest.STAGE);
    assertNull(s.response);
    assertEquals(0, p.grants);
  }

  @Test
  void duplicateAndOverflowRewardStageFieldsCannotClaimAnotherTrial() throws Exception {
    var p = new Recipient();
    var s = session(p);
    win(p);
    byte[] valid =
        fixture.request(s, "TakeTrialActivityRewardCsReq", TrialActivityLifecycleTest.STAGE);
    byte[] doubled = new byte[valid.length * 2];
    System.arraycopy(valid, 0, doubled, 0, valid.length);
    System.arraycopy(valid, 0, doubled, valid.length, valid.length);
    var out = new java.io.ByteArrayOutputStream();
    var coded = com.google.protobuf.CodedOutputStream.newInstance(out);
    coded.writeUInt64(8, (1L << 32) + TrialActivityLifecycleTest.STAGE);
    coded.flush();
    for (byte[] bytes : List.of(doubled, out.toByteArray(), new byte[0], new byte[] {8, 1})) {
      fixture.handle(s, "TakeTrialActivityRewardCsReq", bytes);
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), fixture.code(s));
    }
    assertEquals(0, p.grants);
    claim(s, TrialActivityLifecycleTest.STAGE + 1);
    assertEquals(Retcode.QUEST_NOT_FINISH.getVal(), fixture.code(s));
  }
}
