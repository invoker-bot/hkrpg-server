package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class GameplayProgressTest {
  final Gson gson = new Gson();

  static class TestPlayer extends Player {
    int grants, entrance;
    final Inventory inventory =
        new Inventory(this) {
          public GrantResult grantItems(ItemParamMap map, boolean hint) {
            return new GrantResult(addItems(map, hint), true);
          }

          public List<GameItem> addItems(ItemParamMap map, boolean hint) {
            grants++;
            return List.of();
          }
        };

    public Inventory getInventory() {
      return inventory;
    }

    public void save() {}

    public void sendPacket(BasePacket packet) {}

    public boolean enterScene(int entry, int anchor, boolean send) {
      entrance = entry;
      return true;
    }

    public boolean enterSceneAtAnchor(int entry, int group, int anchor, boolean send) {
      entrance = entry;
      return true;
    }

    public boolean hasSceneAnchor(int entry, int group, int anchor) {
      return true;
    }
  }

  static class ScenePlayer extends TestPlayer {
    int scenePlane, sceneFloor, sceneEntry;

    public int getPlaneId() {
      return scenePlane;
    }

    public int getFloorId() {
      return sceneFloor;
    }

    public int getEntryId() {
      return sceneEntry;
    }
  }

  @AfterEach
  void reset() {
    GameplayDefinitions.dailyLevels.clear();
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.activities.clear();
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.areas.clear();
    GameplayDefinitions.maps.clear();
    GameplayDefinitions.rooms.clear();
    GameplayDefinitions.roomPool.clear();
    GameplayDefinitions.subs.clear();
  }

  void mission(int id, String take, String finish, String subs) {
    var main =
        gson.fromJson(
            "{\"MainMissionID\":"
                + id
                + ",\"TakeParam\":[{\"Type\":\""
                + take
                + "\"}],\"BeginParam\":[{\"Type\":\"Auto\"}]}",
            MainMissionExcel.class);
    GameData.getMainMissionExcelMap().add(main);
    var info =
        gson.fromJson(
            "{\"MainMissionID\":"
                + id
                + ",\"FinishSubMissionList\":["
                + finish
                + "],\"SubMissionList\":"
                + subs
                + "}",
            MissionInfo.class);
    GameData.getMissionInfos().put(id, info);
    for (var sub : info.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
  }

  @Test
  void negativeLocationConditionsUseActualPlaneFloorPairAndKeepUnknownComparisonsPending() {
    mission(
        199954,
        "Manual",
        "1999541,1999542",
        """
[{"ID":1999541,"MainMissionID":199954,"TakeType":"Auto","FinishType":"NotInPlane","ParamType":"Equal","ParamInt1":10000},
 {"ID":1999542,"MainMissionID":199954,"TakeType":"Auto","FinishType":"NotInFloor","ParamType":"Equal","ParamInt1":10000,"ParamInt2":10000001},
 {"ID":1999543,"MainMissionID":199954,"TakeType":"Auto","FinishType":"NotInPlane","ParamType":"NotEqual","ParamInt1":10000}]
""");
    var p = new ScenePlayer();
    p.scenePlane = 10000;
    p.sceneFloor = 10000001;
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(199954));
    assertEquals(0, p.getMissionManager().sub(1999541).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999542).getProgress());
    p.sceneFloor = 10000002;
    p.getMissionManager().refresh();
    assertEquals(1, p.getMissionManager().sub(1999542).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999541).getProgress());
    p.scenePlane = 10001;
    p.getMissionManager().refresh();
    assertEquals(1, p.getMissionManager().sub(1999541).getProgress());
    assertEquals(0, p.getMissionManager().sub(1999543).getProgress());
  }

  @Test
  void sceneEntryUsesActualEntranceAndNewObjectiveWaitsForAnotherTransition() {
    mission(
        19996505,
        "Manual",
        "199965051,199965052",
        """
[{"ID":199965051,"MainMissionID":19996505,"TakeType":"Auto","FinishType":"EnterMapByEntrance","ParamType":"Equal","ParamInt1":199965051},
 {"ID":199965052,"MainMissionID":19996505,"TakeType":"MultiSequence","TakeParamIntList":[199965051],"FinishType":"EnterMapByEntrance","ParamType":"Equal","ParamInt1":199965051},
 {"ID":199965053,"MainMissionID":19996505,"TakeType":"Auto","FinishType":"EnterMapByEntrance","ParamType":"NotEqual","ParamInt1":199965051}]
""");
    var p = new ScenePlayer();
    p.scenePlane = 19996505;
    p.sceneFloor = 199965050;
    p.sceneEntry = 199965050;
    var manager = p.getMissionManager();
    assertEquals(Retcode.SUCC, manager.accept(19996505));
    manager.onEnterFloor(p.scenePlane, p.sceneFloor);
    assertEquals(0, manager.sub(199965051).getProgress());
    p.sceneEntry = 199965051;
    manager.refresh();
    assertEquals(0, manager.sub(199965051).getProgress());
    manager.onEnterFloor(p.scenePlane, p.sceneFloor);
    assertEquals(QuestState.Phase.FINISHED, manager.sub(199965051).getPhase());
    assertEquals(QuestState.Phase.DOING, manager.sub(199965052).getPhase());
    manager.refresh();
    assertEquals(QuestState.Phase.DOING, manager.sub(199965052).getPhase());
    manager.onEnterFloor(p.scenePlane, p.sceneFloor);
    assertTrue(manager.finished(19996505));
    assertEquals(0, manager.sub(199965053).getProgress());
  }

  @Test
  void equalPlaneConditionReadsSavedLocationWithoutManufacturingEntranceEvents() {
    mission(
        19996506,
        "Manual",
        "199965061",
        """
[{"ID":199965061,"MainMissionID":19996506,"TakeType":"Auto","FinishType":"EnterPlane","ParamType":"Equal","ParamInt1":19996506},
 {"ID":199965062,"MainMissionID":19996506,"TakeType":"Auto","FinishType":"EnterPlane","ParamType":"NotEqual","ParamInt1":19996506}]
""");
    var p = new ScenePlayer();
    p.scenePlane = 19996507;
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(19996506));
    assertFalse(p.getMissionManager().finished(19996506));
    p.scenePlane = 19996506;
    p.getMissionManager().refresh();
    assertTrue(p.getMissionManager().finished(19996506));
    assertEquals(0, p.getMissionManager().sub(199965062).getProgress());
  }

  @Test
  void circularFinishActionsCompleteOnceWithoutReservingTheCycleForever() {
    mission(
        19996501,
        "Manual",
        "199965011,199965012",
        """
[{"ID":199965011,"MainMissionID":19996501,"TakeType":"Auto","FinishType":"Talk","ParamStr1":"branch",
  "FinishActionList":[{"FinishActionType":"AddCustomValue","FinishActionParaString":["root","1"]},
                      {"FinishActionType":"delSubMission","FinishActionPara":[199965012]}]},
 {"ID":199965012,"MainMissionID":19996501,"TakeType":"Auto","FinishType":"UnsupportedCondition",
  "FinishActionList":[{"FinishActionType":"AddCustomValue","FinishActionParaString":["child","1"]},
                      {"FinishActionType":"delSubMission","FinishActionPara":[199965011]}]}]
""");
    var p = new TestPlayer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(19996501));
    assertEquals(Retcode.SUCC, p.getMissionManager().finishTalk(199965011, "branch"));
    assertTrue(p.getMissionManager().finished(19996501));
    assertEquals(1, p.getGameplay().getStoryValues().get("mission:19996501:root"));
    assertEquals(1, p.getGameplay().getStoryValues().get("mission:19996501:child"));
    assertFalse(p.getGameplay().getStoryActions().get(199965011).isPending());
    assertFalse(p.getGameplay().getStoryActions().get(199965012).isPending());
    assertEquals(Retcode.MISSION_NOT_DOING, p.getMissionManager().finishTalk(199965011, "branch"));
    assertEquals(1, p.getGameplay().getStoryValues().get("mission:19996501:root"));
  }

  @Test
  void nestedUnsupportedActionOrInactiveParentRejectsBeforeEarlierEffects() {
    mission(
        19996502,
        "Manual",
        "199965021",
        """
[{"ID":199965021,"MainMissionID":19996502,"TakeType":"Auto","FinishType":"Talk","ParamStr1":"nested",
  "FinishActionList":[{"FinishActionType":"SetCustomValue","FinishActionParaString":["early","1"]},
                      {"FinishActionType":"delSubMission","FinishActionPara":[199965022]}]},
 {"ID":199965022,"MainMissionID":19996502,"TakeType":"Auto","FinishType":"Unknown",
  "FinishActionList":[{"FinishActionType":"UnknownStoryAction"}]}]
""");
    var p = new TestPlayer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(19996502));
    assertEquals(
        Retcode.MISSION_FINISH_WAY_NOT_MATCH,
        p.getMissionManager().finishTalk(199965021, "nested"));
    assertTrue(p.getGameplay().getStoryValues().isEmpty());
    assertTrue(p.getGameplay().getStoryActions().isEmpty());
    mission(
        19996503,
        "Manual",
        "199965031",
        """
        [{"ID":199965031,"MainMissionID":19996503,"TakeType":"Auto","FinishType":"Unknown"}]
        """);
    var crossParent =
        gson.fromJson(
            """
            {"ID":199965024,"MainMissionID":19996502,"FinishActionList":[
              {"FinishActionType":"SetCustomValue","FinishActionParaString":["early","1"]},
              {"FinishActionType":"delSubMission","FinishActionPara":[199965031]}]}
            """,
            MissionInfo.SubMissionInfo.class);
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH, new StoryActions(p).execute(crossParent));
    assertTrue(p.getGameplay().getStoryValues().isEmpty());
    assertTrue(p.getGameplay().getStoryActions().isEmpty());
  }

  @Test
  void savedPendingActionIsNotMistakenForAnActiveRecursion() {
    mission(
        19996504,
        "Manual",
        "199965041",
        """
[{"ID":199965041,"MainMissionID":19996504,"TakeType":"Auto","FinishType":"Talk","ParamStr1":"retry",
  "FinishActionList":[{"FinishActionType":"AddCustomValue","FinishActionParaString":["count","1"]}]}]
""");
    var p = new TestPlayer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(19996504));
    var pending = new GameplayState.ActionCursor();
    pending.reserve();
    p.getGameplay().getStoryActions().put(199965041, pending);
    assertEquals(Retcode.SYSTEM_BUSY, p.getMissionManager().finishTalk(199965041, "retry"));
    assertEquals(Retcode.SYSTEM_BUSY, new MissionManager(p).finishTalk(199965041, "retry"));
    assertTrue(p.getGameplay().getStoryValues().isEmpty());
    assertEquals(QuestState.Phase.DOING, p.getMissionManager().sub(199965041).getPhase());
    mission(
        19996507,
        "Manual",
        "199965071",
        """
[{"ID":199965071,"MainMissionID":19996507,"TakeType":"Auto","FinishType":"Talk","ParamStr1":"child-pending",
  "FinishActionList":[{"FinishActionType":"SetCustomValue","FinishActionParaString":["early","1"]},
                      {"FinishActionType":"delSubMission","FinishActionPara":[199965072]}]},
 {"ID":199965072,"MainMissionID":19996507,"TakeType":"Auto","FinishType":"Unknown"}]
""");
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(19996507));
    var childPending = new GameplayState.ActionCursor();
    childPending.reserve();
    p.getGameplay().getStoryActions().put(199965072, childPending);
    assertEquals(Retcode.SYSTEM_BUSY, p.getMissionManager().finishTalk(199965071, "child-pending"));
    assertTrue(p.getGameplay().getStoryValues().isEmpty());
    assertFalse(p.getGameplay().getStoryActions().containsKey(199965071));
  }

  @Test
  void storyRequiresAcceptedMissionExactTalkAndRealStageWin() {
    mission(
        19998001,
        "Manual",
        "199980012",
        "[{\"ID\":199980011,\"MainMissionID\":19998001,\"TakeType\":\"Auto\",\"FinishType\":\"Talk\",\"ParamStr1\":\"test-talk\"},{\"ID\":199980012,\"MainMissionID\":19998001,\"TakeType\":\"MultiSequence\",\"TakeParamIntList\":[199980011],\"FinishType\":\"StageWin\",\"ParamInt1\":123}]");
    var p = new TestPlayer();
    p.getMissionManager().refresh();
    assertFalse(p.getMissionManager().finished(19998001));
    assertNull(p.getMissionManager().sub(199980011));
    assertEquals(
        Retcode.MISSION_NOT_DOING, p.getMissionManager().finishTalk(199980011, "test-talk"));
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(19998001));
    assertNull(p.getMissionManager().sub(199980012));
    assertEquals(
        Retcode.MISSION_FINISH_WAY_NOT_MATCH,
        p.getMissionManager().finishTalk(199980011, "different"));
    assertEquals(Retcode.SUCC, p.getMissionManager().finishTalk(199980011, "test-talk"));
    assertNotNull(p.getMissionManager().sub(199980012));
    p.getMissionManager().onStageWin(124);
    assertFalse(p.getMissionManager().finished(19998001));
    p.getMissionManager().onStageWin(123);
    assertTrue(p.getMissionManager().finished(19998001));
    int grants = p.grants;
    p.getMissionManager().onStageWin(123);
    p.getMissionManager().refresh();
    assertEquals(grants, p.grants);
  }

  @Test
  void unknownFinishRuleNeverAutoCompletesAndMainGatesCannotBeBypassed() {
    mission(
        19998002,
        "Unknown",
        "199980021",
        "[{\"ID\":199980021,\"MainMissionID\":19998002,\"TakeType\":\"Auto\",\"FinishType\":\"Unimplemented\"}]");
    var p = new TestPlayer();
    assertEquals(Retcode.MISSION_NOT_DOING, p.getMissionManager().accept(19998002));
    assertFalse(p.getMissionManager().finished(19998002));
  }

  @Test
  void talkCannotSkipAnUnsupportedStoryFinishAction() {
    mission(
        19997901,
        "Manual",
        "199979011",
        "[{\"ID\":199979011,\"MainMissionID\":19997901,\"TakeType\":\"Auto\",\"FinishType\":\"Talk\",\"ParamStr1\":\"story-action\",\"FinishActionList\":[{\"FinishActionType\":\"UnknownStoryAction\",\"FinishActionPara\":[]}]}]");
    var p = new TestPlayer();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(19997901));
    assertEquals(
        Retcode.MISSION_FINISH_WAY_NOT_MATCH,
        p.getMissionManager().finishTalk(199979011, "story-action"));
    assertEquals(QuestState.Phase.DOING, p.getMissionManager().sub(199979011).getPhase());
    assertFalse(p.getMissionManager().finished(19997901));
  }

  @Test
  void absentStoryAnchorRejectsTheWholeSequenceBeforeEarlierEffects() {
    var p =
        new Player() {
          public void save() {}
        };
    var sub =
        gson.fromJson(
            """
            {"ID":199979091,"MainMissionID":19997909,"FinishActionList":[
              {"FinishActionType":"SetCustomValue","FinishActionParaString":["EarlierValue","1"]},
              {"FinishActionType":"MoveToAnchor","FinishActionPara":[19997909,1,1]}
            ]}
            """,
            MissionInfo.SubMissionInfo.class);
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH, new StoryActions(p).execute(sub));
    assertTrue(p.getGameplay().getStoryValues().isEmpty());
    assertFalse(p.getGameplay().getStoryActions().containsKey(sub.getId()));
  }

  @Test
  void alreadyEnteredStoryDestinationDoesNotRequireAnUnusedAnchor() {
    var p =
        new Player() {
          public void save() {}

          public int getEntryId() {
            return 19997909;
          }

          public boolean enterSceneAtAnchor(int entry, int group, int anchor, boolean send) {
            fail("Already entered destination must not teleport again");
            return false;
          }
        };
    var sub =
        gson.fromJson(
            """
            {"ID":199979092,"FinishActionList":[
              {"FinishActionType":"EnterEntryIfNotThere","FinishActionPara":[19997909,1,1]}
            ]}
            """,
            MissionInfo.SubMissionInfo.class);
    assertEquals(Retcode.SUCC, new StoryActions(p).execute(sub));
    assertFalse(p.getGameplay().getStoryActions().get(sub.getId()).isPending());
  }

  @Test
  void storyMoveActionRunsBeforeUnlockingTheNextObjectiveAndOnlyOnce() {
    mission(
        19997902,
        "Manual",
        "199979021",
        "[{\"ID\":199979021,\"MainMissionID\":19997902,\"TakeType\":\"Auto\",\"FinishType\":\"Talk\",\"ParamStr1\":\"story-move\",\"FinishActionList\":[{\"FinishActionType\":\"MoveToAnchor\",\"FinishActionPara\":[19997000,12,34]}]}]");
    var p = new TestPlayer();
    p.getMissionManager().accept(19997902);
    assertEquals(Retcode.SUCC, p.getMissionManager().finishTalk(199979021, "story-move"));
    assertEquals(19997000, p.entrance);
    p.entrance = 0;
    assertEquals(
        Retcode.MISSION_NOT_DOING, p.getMissionManager().finishTalk(199979021, "story-move"));
    assertEquals(0, p.entrance);
  }

  @Test
  void exactTalkStringResolvesClientOmittedSubIdWithoutFinishingUnrelatedObjectives() {
    mission(
        19997903,
        "Manual",
        "199979031",
        "[{\"ID\":199979031,\"MainMissionID\":19997903,\"TakeType\":\"Auto\",\"FinishType\":\"Talk\",\"ParamStr1\":\"named-performance\"}]");
    var p = new TestPlayer();
    p.getMissionManager().accept(19997903);
    assertEquals(Retcode.MISSION_NOT_EXIST, p.getMissionManager().finishTalk(0, "0"));
    assertEquals(Retcode.SUCC, p.getMissionManager().finishTalk(0, "named-performance"));
    assertTrue(p.getMissionManager().finished(19997903));
  }

  @Test
  void floorSavedActionUnlocksOnlyTheMatchingFloorCondition() {
    mission(
        19997904,
        "Manual",
        "199979042",
        "[{\"ID\":199979041,\"MainMissionID\":19997904,\"TakeType\":\"Auto\",\"FinishType\":\"Talk\",\"ParamStr1\":\"floor-write\",\"FinishActionList\":[{\"FinishActionType\":\"SetFloorSavedValue\",\"FinishActionParaString\":[\"20001\",\"20001001\",\"StoryDoor\",\"1\"]}]},{\"ID\":199979042,\"MainMissionID\":19997904,\"TakeType\":\"MultiSequence\",\"TakeParamIntList\":[199979041],\"FinishType\":\"FloorSavedValue\",\"LevelFloorID\":20001001,\"ParamStr1\":\"StoryDoor\",\"ParamInt1\":1}]");
    var p = new TestPlayer();
    p.getMissionManager().accept(19997904);
    assertFalse(p.getMissionManager().finished(19997904));
    assertEquals(Retcode.SUCC, p.getMissionManager().finishTalk(199979041, "floor-write"));
    assertTrue(p.getMissionManager().finished(19997904));
    assertEquals(1, p.getGameplay().getStoryValues().get("floor:20001001:StoryDoor"));
  }

  void daily(int id) {
    var q =
        gson.fromJson(
            "{\"QuestID\":"
                + id
                + ",\"QuestType\":5,\"FinishWayID\":"
                + id
                + ",\"RewardID\":19998100,\"UnlockType\":\"AutoUnlock\"}",
            QuestExcel.class);
    var way =
        gson.fromJson(
            "{\"ID\":" + id + ",\"FinishType\":\"ConsumeStamina\",\"Progress\":10}",
            FinishWayExcel.class);
    var reward =
        gson.fromJson("{\"RewardID\":19998100,\"ItemID_1\":23,\"Count_1\":100}", RewardExcel.class);
    reward.onLoad();
    GameData.getQuestExcelMap().add(q);
    GameData.getFinishWayExcelMap().add(way);
    GameData.getRewardExcelMap().add(reward);
    GameplayDefinitions.dailyPool.add(new GameplayDefinitions.DailyPool(id, 1));
    GameplayDefinitions.dailyLevels.add(new GameplayDefinitions.DailyLevel(1, 0, 100, 0));
  }

  @Test
  void dailyEarnedPointsClaimOnceAndResetAtFourAm() {
    daily(19998101);
    var p = new TestPlayer();
    var manager = p.getDailyManager();
    var before = Instant.parse("2026-09-30T19:59:59Z");
    manager.setClock(Clock.fixed(before, ZoneOffset.UTC));
    manager.ensureDay();
    assertEquals(0, p.getGameplay().getDailyPoints());
    assertEquals(Retcode.DAILY_ACTIVE_LEVEL_AP_NOT_ENOUGH, manager.claimLevel(1).retcode());
    assertEquals(Retcode.QUEST_STATUS_ERROR, p.getQuestManager().finishByClient(19998101));
    manager.onStaminaConsumed(10);
    assertEquals(Retcode.SUCC, p.getQuestManager().claim(19998101).retcode());
    assertEquals(100, p.getGameplay().getDailyPoints());
    assertEquals(Retcode.QUEST_REWARD_ALREADY_TAKEN, p.getQuestManager().claim(19998101).retcode());
    assertEquals(Retcode.SUCC, manager.claimLevel(1).retcode());
    assertEquals(Retcode.DAILY_ACTIVE_LEVEL_REWARD_ALREADY_TAKEN, manager.claimLevel(1).retcode());
    long prior = p.getGameplay().getDailyDay();
    manager.setClock(Clock.fixed(before.plusSeconds(1), ZoneOffset.UTC));
    manager.ensureDay();
    assertEquals(prior + 1, p.getGameplay().getDailyDay());
    assertEquals(0, p.getGameplay().getDailyPoints());
    assertFalse(manager.levels().getFirst().getIsHasTaken());
    assertEquals(Retcode.QUEST_NOT_FINISH, p.getQuestManager().claim(19998101).retcode());
    manager.setClock(Clock.fixed(before, ZoneOffset.UTC));
    manager.ensureDay();
    assertEquals(prior + 1, p.getGameplay().getDailyDay());
  }

  @Test
  void interruptedDailyQuestReservationNeverRecreditsPoints() {
    daily(19998102);
    var p = new TestPlayer();
    p.getDailyManager().ensureDay();
    p.getDailyManager().onStaminaConsumed(10);
    assertTrue(p.getGameplay().getDailyQuests().get(19998102).reserveReward());
    assertEquals(Retcode.SYSTEM_BUSY, p.getDailyManager().claimQuest(19998102).retcode());
    assertEquals(0, p.getGameplay().getDailyPoints());
  }

  @Test
  void configuredAutoObjectiveAndPowerConsumptionAreAvailableInRealDailyPool() {
    daily(19998103);
    var auto =
        gson.fromJson(
            "{\"ID\":19998103,\"FinishType\":\"AutoFinish\",\"Progress\":1}", FinishWayExcel.class);
    GameData.getFinishWayExcelMap().add(auto);
    var p = new TestPlayer();
    p.getDailyManager().ensureDay();
    assertNotNull(p.getGameplay().getDailyQuests().get(19998103));
    assertEquals(
        QuestState.Phase.FINISHED, p.getGameplay().getDailyQuests().get(19998103).getPhase());
    GameplayDefinitions.dailyPool.clear();
    daily(19998104);
    var power =
        gson.fromJson(
            "{\"ID\":19998104,\"FinishType\":\"UseItem\",\"ParamInt1\":11,\"Progress\":120}",
            FinishWayExcel.class);
    GameData.getFinishWayExcelMap().add(power);
    var other = new TestPlayer();
    other.getDailyManager().onStaminaConsumed(60);
    assertEquals(60, other.getGameplay().getDailyQuests().get(19998104).getProgress());
    other.getDailyManager().onStaminaConsumed(60);
    assertEquals(
        QuestState.Phase.FINISHED, other.getGameplay().getDailyQuests().get(19998104).getPhase());
  }

  @Test
  void activityCountsDistinctEligibleLoginDaysAndRejectsEarlyExpiredAndDuplicateClaims()
      throws Exception {
    var panel = gson.fromJson("{\"PanelID\":19998200}", ActivityPanelExcel.class);
    GameData.getActivityPanelExcelMap().add(panel);
    GameplayDefinitions.activities.put(
        19998200, new GameplayDefinitions.LoginActivity(19998200, List.of(0, 0), 19998201));
    var now = Instant.parse("2026-10-01T00:00:00Z");
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(
            19998200,
            19998201,
            now.minusSeconds(1).getEpochSecond(),
            now.plusSeconds(86400).getEpochSecond()));
    var p = new TestPlayer();
    var m = p.getLoginActivityManager();
    m.setClock(Clock.fixed(now, ZoneOffset.UTC));
    m.recordLogin();
    m.recordLogin();
    assertEquals(1, m.days(19998200));
    assertEquals(Retcode.LOGIN_ACTIVITY_DAYS_LACK, m.claim(19998200, 2).retcode());
    try (var pool = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Callable<Retcode>>();
      for (int i = 0; i < 10; i++) jobs.add(() -> m.claim(19998200, 1).retcode());
      int success = 0;
      for (var job : pool.invokeAll(jobs)) if (job.get() == Retcode.SUCC) success++;
      assertEquals(1, success);
    }
    assertEquals(1, p.grants);
    m.setClock(Clock.fixed(now.plusSeconds(86400), ZoneOffset.UTC));
    m.recordLogin();
    assertEquals(1, m.days(19998200));
    assertEquals(Retcode.REQ_PARA_INVALID, m.claim(19998200, 1).retcode());
  }

  @Test
  void universeCannotSkipRoomsOrRestartActiveRunAndResumeKeepsAssignments() {
    GameplayDefinitions.areas.put(19998300, new GameplayDefinitions.Area(19998300, 0, 1, 0, 0));
    GameplayDefinitions.maps.put(
        1,
        List.of(
            new GameplayDefinitions.Site(1, 1, true, List.of(2), 0),
            new GameplayDefinitions.Site(1, 2, false, List.of(), 0)));
    GameplayDefinitions.rooms.put(19998301, new GameplayDefinitions.Room(19998301, 1, 8000101, 10));
    GameplayDefinitions.roomPool.put(1, List.of(19998301));
    GameplayDefinitions.roomPool.put(2, List.of(19998301));
    var p = new UniverseLifecycleTest.Explorer();
    assertEquals(Retcode.SUCC, p.getUniverseManager().start(19998300));
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, p.getUniverseManager().start(19998300));
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, p.getUniverseManager().enter(2));
    var run = p.getGameplay().getRogue();
    String id = run.getRunId();
    assertTrue(p.getUniverseManager().resume());
    assertEquals(id, run.getRunId());
    assertTrue(p.getGameplay().getRewards().isEmpty());
    run.clear();
    assertEquals(Retcode.ROGUE_AREA_INVALID, p.getUniverseManager().enter(99));
    assertEquals(Retcode.SUCC, p.getUniverseManager().enter(2));
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, p.getUniverseManager().enter(1));
    assertEquals(Retcode.SUCC, p.getUniverseManager().abandon());
    assertEquals(GameplayState.RogueRun.Phase.ABANDONED, run.getPhase());
    assertTrue(p.getGameplay().getRewards().isEmpty());
  }
}
