package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DicePve46Test {
  @TempDir Path root;
  final DiceCombat46Test fixture = new DiceCombat46Test();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  RewardExcel oldReward;
  JsonObject source;
  static final int STAGE = 1001, REWARD = 8014100, MAIN = 1054003, SUB = 105400302, DUST = 264998;

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.setup();
    try (var input = getClass().getResourceAsStream("/gameplay/activity-dice-pve-4.6.json")) {
      assertNotNull(input);
      source =
          JsonParser.parseString(
                  new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    fixture.table("ActivityDiceStageConfig", "[" + source.get("stage") + "]");
    oldReward = GameData.getRewardExcelMap().get(REWARD);
    var reward = new Gson().fromJson(source.get("reward"), RewardExcel.class);
    reward.onLoad();
    GameData.getRewardExcelMap().add(reward);
    fixture.oldItems.put(DUST, GameData.getItemExcelMap().get(DUST));
    GameData.getItemExcelMap()
        .add(
            new Gson()
                .fromJson(
                    "{\"ID\":264998,\"ItemMainType\":\"Material\",\"PileLimit\":99999}",
                    ItemExcel.class));
    oldSubs.putAll(GameplayDefinitions.subs);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.put(
        SUB, new Gson().fromJson(source.get("mission"), MissionInfo.SubMissionInfo.class));
  }

  @AfterEach
  void restore() throws Exception {
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    if (oldReward == null) GameData.getRewardExcelMap().keySet().remove(REWARD);
    else GameData.getRewardExcelMap().add(oldReward);
    fixture.restore();
  }

  DiceCombat46Test.Session accepted() throws Exception {
    var s = new DiceCombat46Test.Session();
    for (var entry : Map.of("planeId", 10501, "floorId", 10501001).entrySet()) {
      var field = Player.class.getDeclaredField(entry.getKey());
      field.setAccessible(true);
      field.set(s.player, entry.getValue());
    }
    s.player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    s.player.getGameplay().getSubMissions().put(SUB, new QuestState(SUB));
    fixture.stock(s, DiceCombat46Test.COIN, 1);
    fixture.stock(s, DUST, 1);
    return s;
  }

  DynamicMessage finish(
      DiceCombat46Test.Session s, int stage, boolean win, boolean flag, int scalar)
      throws Exception {
    var req = Gameplay46.builder(s, "DiceCombatFinishPveStageCsReq");
    Gameplay46.set(req, "MPDHNFNCIEA", stage);
    Gameplay46.set(req, "is_win", win);
    Gameplay46.set(req, "AINKFEINNAK", flag);
    Gameplay46.set(req, "IMKHIDEBGEB", scalar);
    assertTrue(
        VersionedPacketHandler.handle(s, 9293, req.build().toByteArray()),
        "The native 4.6 PVE result request must be handled");
    assertEquals("FinishPveStageScRsp", s.responseName);
    assertEquals(9271, s.getProtocol().clientCommand(s.responseName));
    return s.response;
  }

  @Test
  void acceptedSourceVictoryGrantsItsActualFirstRewardAndCompletesOnlyTheAcceptedObjective()
      throws Exception {
    var s = accepted();
    var result = finish(s, STAGE, true, false, 0);
    assertEquals(0, Gameplay46.number(result, "retcode"));
    assertEquals(STAGE, Gameplay46.number(result, "MPDHNFNCIEA"));
    assertEquals(true, Gameplay46.value(result, "is_win"));
    assertEquals(2, s.player.getInventory().getMaterialByItemId(DiceCombat46Test.COIN).getCount());
    assertEquals(101, s.player.getInventory().getMaterialByItemId(DUST).getCount());
    assertEquals(1, s.player.getMissionManager().sub(SUB).getProgress());
    assertNotEquals(QuestState.Phase.DOING, s.player.getMissionManager().sub(SUB).getPhase());
    var query = fixture.invoke(s, "GetDiceCombatSystemDataCsReq", Map.of());
    assertEquals(List.of(STAGE), Gameplay46.value(query, "OHFIIBEIPJE"));
  }

  int count(DiceCombat46Test.Session s, int id) {
    var item = s.player.getInventory().getMaterialByItemId(id);
    return item == null ? 0 : item.getCount();
  }

  int finishCode(DiceCombat46Test.Session s, int stage, boolean win, boolean flag, int scalar)
      throws Exception {
    return Gameplay46.number(finish(s, stage, win, flag, scalar), "retcode");
  }

  void unchanged(DiceCombat46Test.Session s) {
    assertEquals(1, count(s, DiceCombat46Test.COIN));
    assertEquals(1, count(s, DUST));
    assertTrue(s.player.getGameplay().getDiceCombat().getPveWins().isEmpty());
    assertFalse(s.player.getGameplay().getRewards().containsKey("dice-pve:" + STAGE));
    assertEquals(0, s.saves);
  }

  @Test
  void repeatedVictoryReturnsNoSecondRewardOrMissionEvent() throws Exception {
    var s = accepted();
    assertEquals(0, finishCode(s, STAGE, true, false, 12));
    int saves = s.saves;
    var again = finish(s, STAGE, true, false, 12);
    assertEquals(0, Gameplay46.number(again, "retcode"));
    var reward = (DynamicMessage) Gameplay46.value(again, "reward");
    assertTrue(((List<?>) Gameplay46.value(reward, "item_list")).isEmpty());
    assertEquals(2, count(s, DiceCombat46Test.COIN));
    assertEquals(101, count(s, DUST));
    assertEquals(1, s.player.getMissionManager().sub(SUB).getProgress());
    assertEquals(saves, s.saves);
  }

  @Test
  void noAcceptedObjectiveCannotCreateAWinOrReward() throws Exception {
    var s = accepted();
    s.player.getGameplay().getSubMissions().clear();
    assertEquals(Retcode.MISSION_NOT_DOING.getVal(), finishCode(s, STAGE, true, false, 0));
    unchanged(s);
  }

  @Test
  void cancelledOrWrongMainMissionCannotAuthorizeItsObjective() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var s = accepted();
      if (mode == 0) s.player.getGameplay().getCancelledSubs().add(SUB);
      if (mode == 1) s.player.getGameplay().getCancelledMissions().add(MAIN);
      if (mode == 2) s.player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN + 1));
      assertEquals(Retcode.MISSION_NOT_DOING.getVal(), finishCode(s, STAGE, true, false, 0));
      unchanged(s);
    }
  }

  @Test
  void anotherFloorCannotFinishTheSourceStoryObjective() throws Exception {
    var s = accepted();
    var field = Player.class.getDeclaredField("floorId");
    field.setAccessible(true);
    field.set(s.player, 20001001);
    assertEquals(Retcode.MISSION_NOT_DOING.getVal(), finishCode(s, STAGE, true, false, 0));
    unchanged(s);
  }

  @Test
  void lossesAndUnmatchedNativeFlagStayUnsupportedWithoutFakeVictory() throws Exception {
    var s = accepted();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), finishCode(s, STAGE, false, false, 0));
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), finishCode(s, STAGE, true, true, 0));
    assertEquals(false, Gameplay46.value(s.response, "is_win"));
    assertEquals(0, Gameplay46.number(s.response, "MPDHNFNCIEA"));
    unchanged(s);
  }

  @Test
  void unsupportedConditionShapesAreNotCompletedByAGenericWin() throws Exception {
    var original = GameplayDefinitions.subs.get(SUB);
    for (int mode = 0; mode < 5; mode++) {
      var c = new Gson().fromJson(source.get("mission"), MissionInfo.SubMissionInfo.class);
      if (mode == 0) c.setParamType("Equal");
      if (mode == 1) {
        c.setParamInt1(0);
        c.setParamInt2(1);
      }
      if (mode == 2) c.setProgress(2);
      if (mode == 3) c.setParamIntList(new Integer[] {STAGE, STAGE});
      if (mode == 4) c.setStoryLineIDList(List.of(2022304));
      GameplayDefinitions.subs.put(SUB, c);
      var s = accepted();
      assertEquals(Retcode.MISSION_NOT_DOING.getVal(), finishCode(s, STAGE, true, false, 0));
      unchanged(s);
    }
    GameplayDefinitions.subs.put(SUB, original);
  }

  @Test
  void unknownClassicStageCannotAwardAConfiguredMissionReward() throws Exception {
    var s = accepted();
    assertEquals(Retcode.REQ_PARA_INVALID.getVal(), finishCode(s, 99999, true, false, 0));
    unchanged(s);
  }

  @Test
  void missingRewardCannotReserveAFirstWin() throws Exception {
    GameData.getRewardExcelMap().keySet().remove(REWARD);
    var s = accepted();
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), finishCode(s, STAGE, true, false, 0));
    unchanged(s);
  }

  @Test
  void unknownRewardItemCannotProducePartialGrants() throws Exception {
    var row =
        new Gson()
            .fromJson(
                "{\"RewardID\":8014100,\"ItemID_1\":1999999999,\"Count_1\":1}", RewardExcel.class);
    row.onLoad();
    GameData.getRewardExcelMap().add(row);
    var s = accepted();
    assertEquals(Retcode.REWARD_CONFIG_NOT_EXIST.getVal(), finishCode(s, STAGE, true, false, 0));
    unchanged(s);
  }

  @Test
  void malformedDuplicateWideOrNonBooleanFieldsAreRejectedBeforeAnyMutation() throws Exception {
    var good = Gameplay46.builder(new DiceCombat46Test.Session(), "DiceCombatFinishPveStageCsReq");
    Gameplay46.set(good, "MPDHNFNCIEA", STAGE);
    Gameplay46.set(good, "is_win", true);
    var valid = good.build().toByteArray();
    var cases = new ArrayList<byte[]>();
    cases.add(new byte[] {8, 2});
    cases.add(new byte[] {16, 2});
    cases.add(new byte[] {82, 0});
    cases.add(new byte[] {8, (byte) 128});
    for (int tag : List.of(4, 10)) {
      var buffer = new java.io.ByteArrayOutputStream();
      var output = com.google.protobuf.CodedOutputStream.newInstance(buffer);
      output.writeUInt64(tag, 0x100000000L);
      output.flush();
      cases.add(buffer.toByteArray());
    }
    for (byte[] extra : List.of(new byte[] {8, 1}, new byte[] {24, 1})) {
      var joined = Arrays.copyOf(valid, valid.length + extra.length);
      System.arraycopy(extra, 0, joined, valid.length, extra.length);
      cases.add(joined);
    }
    for (var bytes : cases) {
      var s = accepted();
      assertTrue(VersionedPacketHandler.handle(s, 9293, bytes));
      assertEquals(Retcode.REQ_PARA_INVALID.getVal(), Gameplay46.number(s.response, "retcode"));
      unchanged(s);
    }
  }

  @Test
  void opaqueNativeScalarIsPreservedAsUInt32WithoutInventingAvatarOrDamageMeaning()
      throws Exception {
    var s = accepted();
    assertEquals(0, finishCode(s, STAGE, true, false, -1));
    assertEquals(
        0xffffffffL,
        s.player.getGameplay().getDiceCombat().getPveWins().get(STAGE).getNativeScalar());
    assertTrue(s.player.getGameplay().getDiceCombat().getAvatars().isEmpty());
  }

  @Test
  void pendingRealUpgradeBlocksANewPveGrant() throws Exception {
    var s = accepted();
    var state = s.player.getGameplay().getDiceCombat();
    var field = GameplayState.DiceCombat.class.getDeclaredField("upgrades");
    field.setAccessible(true);
    field.set(
        state,
        new ArrayList<>(
            List.of(new GameplayState.DiceUpgrade(264001, 1, 1, 1790000000L, DUST, 60))));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), finishCode(s, STAGE, true, false, 0));
    unchanged(s);
  }

  @Test
  void interruptedReservationNeverAutomaticallyGrantsOnRetry() throws Exception {
    var s = accepted();
    s.interruptPveReservation = true;
    assertThrows(IllegalStateException.class, () -> finish(s, STAGE, true, false, 0));
    assertEquals(1, count(s, DUST));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), finishCode(s, STAGE, true, false, 0));
    assertFalse(s.player.getGameplay().getDiceCombat().getPveWins().get(STAGE).isApplied());
    assertEquals(
        List.of(),
        Gameplay46.value(
            fixture.invoke(s, "GetDiceCombatSystemDataCsReq", Map.of()), "OHFIIBEIPJE"));
  }

  @Test
  void interruptedRewardReservationNeverAutomaticallyGrantsOnRetry() throws Exception {
    var s = accepted();
    s.interruptPveRewardReservation = true;
    assertThrows(IllegalStateException.class, () -> finish(s, STAGE, true, false, 0));
    assertEquals(1, count(s, DUST));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), finishCode(s, STAGE, true, false, 0));
    assertFalse(s.player.getRewardLedger().granted("dice-pve:" + STAGE));
  }

  @Test
  void interruptedAppliedSaveDoesNotGrantAgainOrPublishUnsavedHistory() throws Exception {
    var s = accepted();
    s.interruptPveApplied = true;
    assertThrows(IllegalStateException.class, () -> finish(s, STAGE, true, false, 0));
    assertEquals(101, count(s, DUST));
    assertTrue(s.player.getRewardLedger().granted("dice-pve:" + STAGE));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), finishCode(s, STAGE, true, false, 0));
    assertEquals(101, count(s, DUST));
    assertEquals(
        List.of(),
        Gameplay46.value(
            fixture.invoke(s, "GetDiceCombatSystemDataCsReq", Map.of()), "OHFIIBEIPJE"));
    assertEquals(0, s.player.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void aLaterAcceptedObjectiveCannotConsumeThePreviousVictoryAgain() throws Exception {
    var s = accepted();
    assertEquals(0, finishCode(s, STAGE, true, false, 0));
    var later = new Gson().fromJson(source.get("mission"), MissionInfo.SubMissionInfo.class);
    later.setId(SUB + 10);
    GameplayDefinitions.subs.put(later.getId(), later);
    s.player.getGameplay().getSubMissions().put(later.getId(), new QuestState(later.getId()));
    assertEquals(0, finishCode(s, STAGE, true, false, 0));
    assertEquals(0, s.player.getMissionManager().sub(later.getId()).getProgress());
    assertEquals(101, count(s, DUST));
  }

  @Test
  void anAppliedWinWithoutItsRewardLedgerIsRejectedAsCorruptPlayerData() throws Exception {
    var s = accepted();
    assertEquals(0, finishCode(s, STAGE, true, false, 0));
    s.player.getGameplay().getRewards().remove("dice-pve:" + STAGE);
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), finishCode(s, STAGE, true, false, 0));
    var query = fixture.invoke(s, "GetDiceCombatSystemDataCsReq", Map.of());
    assertEquals(Retcode.PLAYER_DATA_ERROR.getVal(), Gameplay46.number(query, "retcode"));
    assertTrue(((List<?>) Gameplay46.value(query, "OHFIIBEIPJE")).isEmpty());
    assertEquals(101, count(s, DUST));
  }

  @Test
  void unsupportedStoryActionsRemainPendingAfterAReportedVictory() throws Exception {
    var c = GameplayDefinitions.subs.get(SUB);
    c.setFinishActionList(
        new Gson()
            .fromJson(
                "[{\"FinishActionType\":\"UnmatchedDiceAction\",\"FinishActionPara\":[]}]",
                MissionInfo.FinishAction[].class));
    var s = accepted();
    assertEquals(0, finishCode(s, STAGE, true, false, 0));
    assertEquals(QuestState.Phase.DOING, s.player.getMissionManager().sub(SUB).getPhase());
    assertFalse(
        s.player.getGameplay().getDiceCombat().getPveWins().get(STAGE).isMissionEventsDone());
    assertEquals(0, finishCode(s, STAGE, true, false, 0));
    assertEquals(0, s.player.getMissionManager().sub(SUB).getProgress());
    assertEquals(101, count(s, DUST));
  }

  @Test
  void interruptedPveGrantBlocksFurtherShopPayments() throws Exception {
    var s = accepted();
    s.interruptPveReservation = true;
    assertThrows(IllegalStateException.class, () -> finish(s, STAGE, true, false, 0));
    fixture.stock(s, DiceCombat46Test.COIN, 10);
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture.buy(s, 1, 1101));
    assertTrue(s.player.getGameplay().getDiceCombat().getPurchases().isEmpty());
    assertEquals(11, count(s, DiceCombat46Test.COIN));
  }

  @Test
  void interruptedPveGrantBlocksFurtherAvatarUpgradePayments() throws Exception {
    try (var input = getClass().getResourceAsStream("/gameplay/activity-dice-const-4.6.json")) {
      assertNotNull(input);
      Files.write(
          root.resolve("ExcelOutput/ActivityDiceConstValueCommo.json"), input.readAllBytes());
    }
    var s = accepted();
    s.interruptPveReservation = true;
    assertThrows(IllegalStateException.class, () -> finish(s, STAGE, true, false, 0));
    fixture.stock(s, DiceCombat46Test.AVATAR, 1);
    s.player
        .getGameplay()
        .getDiceCombat()
        .getAvatars()
        .put(
            DiceCombat46Test.AVATAR,
            new GameplayState.DiceAvatar(
                DiceCombat46Test.AVATAR, 1790000000L, List.of(264101, 264101)));
    var req = Gameplay46.builder(s, "DiceCombatUpgradeAvatarCsReq");
    Gameplay46.set(req, "KOEOEHIMOEI", DiceCombat46Test.AVATAR);
    assertTrue(VersionedPacketHandler.handle(s, 9292, req.build().toByteArray()));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), Gameplay46.number(s.response, "retcode"));
    assertEquals(1, count(s, DUST));
    assertTrue(s.player.getGameplay().getDiceCombat().getUpgrades().isEmpty());
  }

  @Test
  void aSavedGameplayDocumentKeepsTheFrozenRewardTargetsAndUnsignedScalar() throws Exception {
    var s = accepted();
    assertEquals(0, finishCode(s, STAGE, true, false, -1));
    var saved = new Gson().fromJson(new Gson().toJson(s.player.getGameplay()), GameplayState.class);
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(s.player, saved);
    var win = saved.getDiceCombat().getPveWins().get(STAGE);
    assertEquals(0xffffffffL, win.getNativeScalar());
    assertEquals(Map.of(DiceCombat46Test.COIN, 1, DUST, 100), win.getItems());
    assertEquals(Set.of(SUB), win.getTargets());
    assertTrue(win.isApplied() && win.isMissionEventsDone());
    var query = fixture.invoke(s, "GetDiceCombatSystemDataCsReq", Map.of());
    assertEquals(List.of(STAGE), Gameplay46.value(query, "OHFIIBEIPJE"));
    assertEquals(0, finishCode(s, STAGE, true, false, -1));
    assertEquals(101, count(s, DUST));
  }
}
