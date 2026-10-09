package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.data.excel.RewardExcel;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.server.packet.Retcode;
import java.util.List;
import org.junit.jupiter.api.*;

class SubMissionRewardTest {
  static final int MAIN = 19997001, SUB = 199970011, ITEM = 19997001, REWARD = 19997001;
  final Gson gson = new Gson();

  static class Recipient extends GameplayProgressTest.TestPlayer {
    int attempts, delivered;
    boolean complete = true;
    Runnable onGrant;
    final Inventory inventory =
        new Inventory(this) {
          public GrantResult grantItems(ItemParamMap map, boolean hint) {
            attempts++;
            delivered += map.get(ITEM);
            var callback = onGrant;
            onGrant = null;
            if (callback != null) callback.run();
            return new GrantResult(List.of(), complete);
          }
        };

    public Inventory getInventory() {
      return inventory;
    }
  }

  @BeforeEach
  void fixture() {
    GameData.getItemExcelMap()
        .add(gson.fromJson("{\"ID\":" + ITEM + ",\"ItemMainType\":\"Material\"}", ItemExcel.class));
    reward(REWARD, 7);
    new GameplayProgressTest()
        .mission(
            MAIN,
            "Manual",
            Integer.toString(SUB + 1),
            """
[{"ID":199970011,"MainMissionID":19997001,"TakeType":"Auto","FinishType":"Talk",
  "ParamStr1":"earned","SubRewardID":19997001},
 {"ID":199970012,"MainMissionID":19997001,"TakeType":"Auto","FinishType":"UnsupportedCondition"}]
""");
  }

  void reward(int id, int count) {
    var r =
        gson.fromJson(
            "{\"RewardID\":" + id + ",\"ItemID_1\":" + ITEM + ",\"Count_1\":" + count + "}",
            RewardExcel.class);
    r.onLoad();
    GameData.getRewardExcelMap().add(r);
  }

  @AfterEach
  void clean() {
    GameplayDefinitions.subs.clear();
    GameData.getMainMissionExcelMap().keySet().remove(MAIN);
    GameData.getMissionInfos().remove(MAIN);
    GameData.getItemExcelMap().keySet().remove(ITEM);
    GameData.getRewardExcelMap().keySet().remove(REWARD);
  }

  @Test
  void earnedSubRewardIsGrantedBeforeParentCompletionAndNeverReplayed() {
    var p = new Recipient();
    var manager = p.getMissionManager();
    assertEquals(Retcode.SUCC, manager.accept(MAIN));
    assertEquals(0, p.attempts);
    assertEquals(Retcode.SUCC, manager.finishTalk(SUB, "earned"));
    assertFalse(manager.finished(MAIN));
    assertEquals(7, p.delivered);
    var plan = p.getGameplay().getMissionRewardPlans().get("submission:" + SUB);
    assertEquals(List.of(REWARD), plan.getRewardIds());
    assertEquals(java.util.Map.of(ITEM, 7), plan.getItems());
    reward(REWARD, 1000);
    manager.refresh();
    assertEquals(Retcode.MISSION_NOT_DOING, manager.finishTalk(SUB, "earned"));
    assertEquals(1, p.attempts);
  }

  @Test
  void interruptedRewardGrantCannotBeAutomaticallyRepeated() {
    var p = new Recipient();
    p.complete = false;
    var manager = p.getMissionManager();
    manager.accept(MAIN);
    manager.finishTalk(SUB, "earned");
    assertEquals(1, p.attempts);
    p.complete = true;
    manager.refresh();
    assertEquals(1, p.attempts);
    assertFalse(p.getRewardLedger().granted("submission:" + SUB));
  }

  @Test
  void cancelledSubNeverReceivesAReward() {
    var p = new Recipient();
    var manager = p.getMissionManager();
    manager.accept(MAIN);
    p.getGameplay().getCancelledSubs().add(SUB);
    assertEquals(Retcode.MISSION_NOT_DOING, manager.finishTalk(SUB, "earned"));
    manager.refresh();
    assertEquals(0, p.attempts);
  }

  @Test
  void mainMissionDoesNotGrantTheSubRewardPreviewList() {
    GameData.getMainMissionExcelMap()
        .add(
            gson.fromJson(
                """
                {"MainMissionID":19997001,"RewardID":19997001,"SubRewardList":[19997001],
                 "TakeParam":[{"Type":"Manual"}],"BeginParam":[{"Type":"Auto"}]}
                """,
                MainMissionExcel.class));
    var info =
        gson.fromJson(
            """
            {"MainMissionID":19997001,"FinishSubMissionList":[199970011],
             "SubMissionList":[{"ID":199970011,"MainMissionID":19997001,"TakeType":"Auto",
               "FinishType":"Talk","ParamStr1":"earned"}]}
            """,
            MissionInfo.class);
    GameData.getMissionInfos().put(MAIN, info);
    GameplayDefinitions.subs.put(SUB, info.getSubMissionList()[0]);
    var p = new Recipient();
    p.getMissionManager().accept(MAIN);
    p.getMissionManager().finishTalk(SUB, "earned");
    assertEquals(7, p.delivered);
    p.getMissionManager().refresh();
    assertEquals(1, p.attempts);
  }

  void conditionalFixture() {
    var info =
        gson.fromJson(
            """
{"MainMissionID":19997001,"FinishSubMissionList":[199970012],
 "MissionCustomValueList":[{"Name":"OldValue_0","Type":"Former","ValidValueParamList":[0,1,2]}],
 "SubMissionList":[{"ID":199970011,"MainMissionID":19997001,"TakeType":"Auto",
  "FinishType":"Talk","ParamStr1":"earned","CustomValueList":["OldValue_0"],
  "SubRewardID":19997001,"CustomValueReward":[{"Name":"OldValue_0","Value":1,"RewardID":19997001}]},
  {"ID":199970012,"MainMissionID":19997001,"TakeType":"Auto","FinishType":"UnsupportedCondition"}]}
""",
            MissionInfo.class);
    GameData.getMissionInfos().put(MAIN, info);
    for (var sub : info.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
  }

  @Test
  void conditionalRewardUsesValidatedChoiceAtCompletionAndCannotChangeLater() {
    conditionalFixture();
    var p = new Recipient();
    p.getMissionManager().accept(MAIN);
    assertEquals(
        Retcode.SUCC, p.getMissionManager().finishTalk(SUB, "earned", java.util.Map.of(0, 1)));
    assertEquals(14, p.delivered);
    assertEquals(
        java.util.Map.of("OldValue_0", 1),
        p.getGameplay().getMissionRewardPlans().get("submission:" + SUB).getChoices());
    p.getGameplay().getStoryValues().put("mission:" + MAIN + ":OldValue_0", 2);
    p.getMissionManager().refresh();
    assertEquals(1, p.attempts);
  }

  @Test
  void missingChoiceEvidenceCannotBeInventedAfterCompletion() {
    conditionalFixture();
    var p = new Recipient();
    p.getMissionManager().accept(MAIN);
    p.getMissionManager().finishTalk(SUB, "earned");
    assertEquals(0, p.attempts);
    p.getGameplay().getStoryValues().put("mission:" + MAIN + ":OldValue_0", 1);
    p.getMissionManager().refresh();
    assertEquals(0, p.attempts);
  }

  @Test
  void endedParentCannotCompleteOptionalTalkOrEarnItsSubReward() {
    var p = new Recipient();
    p.getMissionManager().accept(MAIN);
    p.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 1);
    assertEquals(Retcode.MISSION_NOT_DOING, p.getMissionManager().finishTalk(SUB, "earned"));
    assertEquals(0, p.getMissionManager().sub(SUB).getProgress());
    assertFalse(p.getGameplay().getMissionRewardPlans().containsKey("submission:" + SUB));
  }

  @Test
  void oldMainCompletionClaimsOnlyItsOwnRewardDespiteSubRewardPreview() {
    GameData.getMainMissionExcelMap()
        .add(
            gson.fromJson(
                """
                {"MainMissionID":19997001,"RewardID":19997001,"SubRewardList":[19997001],
                 "TakeParam":[{"Type":"Manual"}],"BeginParam":[{"Type":"Auto"}]}
                """,
                MainMissionExcel.class));
    var p = new Recipient();
    p.getMissionManager().accept(MAIN);
    p.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 1);
    p.getMissionManager().refresh();
    assertEquals(1, p.attempts);
    assertEquals(7, p.delivered);
    assertEquals(
        emu.lunarcore.game.quest.QuestState.Phase.CLOSED,
        p.getGameplay().getMainMissions().get(MAIN).getPhase());
  }

  @Test
  void completedParentCannotDuplicateEarnedSubRewardsOrGrantUnselectedPreviewBranches() {
    conditionalFixture();
    reward(REWARD + 1, 99);
    GameData.getMainMissionExcelMap()
        .add(
            gson.fromJson(
                """
                {"MainMissionID":19997001,"SubRewardList":[19997001,19997002],
                 "TakeParam":[{"Type":"Manual"}],"BeginParam":[{"Type":"Auto"}]}
                """,
                MainMissionExcel.class));
    var info =
        gson.fromJson(
            """
{"MainMissionID":19997001,"FinishSubMissionList":[199970011],
 "MissionCustomValueList":[{"Name":"OldValue_0","Type":"Former","ValidValueParamList":[1,2]}],
 "SubMissionList":[{"ID":199970011,"MainMissionID":19997001,"TakeType":"Auto",
  "FinishType":"Talk","ParamStr1":"earned","CustomValueList":["OldValue_0"],
  "CustomValueReward":[{"Name":"OldValue_0","Value":1,"RewardID":19997001},
                       {"Name":"OldValue_0","Value":2,"RewardID":19997002}]}]}
""",
            MissionInfo.class);
    GameData.getMissionInfos().put(MAIN, info);
    GameplayDefinitions.subs.put(SUB, info.getSubMissionList()[0]);
    var p = new Recipient();
    try {
      p.getMissionManager().accept(MAIN);
      p.getMissionManager().finishTalk(SUB, "earned", java.util.Map.of(0, 1));
      assertEquals(7, p.delivered);
      assertTrue(p.getMissionManager().finished(MAIN));
      p.getMissionManager().refresh();
      assertEquals(7, p.delivered);
    } finally {
      GameData.getRewardExcelMap().keySet().remove(REWARD + 1);
    }
  }

  @Test
  void rewardInventoryCallbacksMayActivateAnotherMissionWithoutBreakingSettlementIteration() {
    GameData.getMainMissionExcelMap()
        .add(
            gson.fromJson(
                """
                {"MainMissionID":19997001,"RewardID":19997001,
                 "TakeParam":[{"Type":"Manual"}],"BeginParam":[{"Type":"Auto"}]}
                """,
                MainMissionExcel.class));
    var p = new Recipient();
    p.getMissionManager().accept(MAIN);
    p.getGameplay().getMainMissions().get(MAIN).advance(1, 1, 1);
    p.getGameplay()
        .getMainMissions()
        .put(MAIN + 16, new emu.lunarcore.game.quest.QuestState(MAIN + 16));
    p.onGrant =
        () ->
            p.getGameplay()
                .getMainMissions()
                .put(MAIN + 32, new emu.lunarcore.game.quest.QuestState(MAIN + 32));
    assertDoesNotThrow(() -> p.getMissionManager().refresh());
    assertEquals(7, p.delivered);
    p.getMissionManager().refresh();
    assertEquals(1, p.attempts);
  }
}
