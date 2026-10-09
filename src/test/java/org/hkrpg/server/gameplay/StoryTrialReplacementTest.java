package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class StoryTrialReplacementTest {
  static final int MAIN = 19996925, SUB = 199969251, FUTURE = 1199932;
  static final String SCRIPT =
      "{\"Type\":\"Mission\",\"OnInitSequece\":[],\"OnStartSequece\":[{\"TaskList\":[{\"$type\":\"RPG.GameCore.StoryLineReplaceTrialPlayer\"}]}]}";
  final StoryTrialSkillFreezeTest trials = new StoryTrialSkillFreezeTest();
  final GameplayProgressTest missions = new GameplayProgressTest();
  @TempDir Path root;
  Map<Integer, MissionInfo> oldMissionInfos;
  MainMissionExcel oldMain;
  MissionInfo.SubMissionInfo start, end;

  @BeforeEach
  void resources() throws Exception {
    oldMissionInfos = new HashMap<>(GameData.getMissionInfos());
    oldMain = GameData.getMainMissionExcelMap().get(MAIN);
    trials.root = root;
    trials.install();
    trials.skill(1);
    start = GameplayDefinitions.subs.get(StoryLineLifecycleTest.START);
    end = GameplayDefinitions.subs.get(StoryLineLifecycleTest.END);
    GameData.getSpecialAvatarExcelMap()
        .add(
            trials.fixture.gson.fromJson(
                "{\"SpecialAvatarID\":"
                    + FUTURE
                    + ",\"AvatarID\":199931,\"Level\":70,\"Promotion\":6}",
                SpecialAvatarExcel.class));
    Files.writeString(
        root.resolve("ExcelOutput/StroyLineTrialAvatarData.json"),
        "[{\"StoryLineID\":199931,\"TrialAvatarList\":[1199931,1199932],\"InitTrialAvatarList\":[1199931],\"CaptainAvatarID\":1199931}]");
    StoryLineDefinitions.load(root);
    script(SCRIPT);
  }

  @AfterEach
  void restore() throws Exception {
    trials.restore();
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldMissionInfos);
    GameData.getMainMissionExcelMap().keySet().remove(MAIN);
    if (oldMain != null) GameData.getMainMissionExcelMap().add(oldMain);
  }

  void script(String text) throws Exception {
    var directory = root.resolve("Config/Level/Mission/" + MAIN);
    Files.createDirectories(directory);
    var script = directory.resolve("Mission_" + SUB + ".json");
    if (text == null) Files.deleteIfExists(script);
    else Files.writeString(script, text);
    String sub =
        "{\"ID\":"
            + SUB
            + ",\"MainMissionID\":"
            + MAIN
            + ",\"TakeType\":\"Auto\",\"FinishType\":\"StoryLineAddTrialAvatar\",\"ParamType\":\"Equal\",\"ParamInt1\":"
            + FUTURE
            + ",\"Progress\":1,\"LevelPlaneID\":199931,\"LevelFloorID\":199931001,\"StoryLineIDList\":[199931],\"MissionJsonPath\":\"Config/Level/Mission/"
            + MAIN
            + "/Mission_"
            + SUB
            + ".json\"}";
    missions.mission(MAIN, "Manual", "199969259", "[" + sub + "]");
    Files.writeString(
        directory.resolve("MissionInfo_" + MAIN + ".json"),
        trials.fixture.gson.toJson(GameData.getMissionInfos().get(MAIN)));
    GameplayDefinitions.loadMissions(root.resolve("Config/Level/Mission"));
    GameplayDefinitions.subs.put(start.getId(), start);
    GameplayDefinitions.subs.put(end.getId(), end);
  }

  StoryLineLifecycleTest.Director started() {
    return trials.started();
  }

  void accept(StoryLineLifecycleTest.Director player) {
    assertEquals(Retcode.SUCC, player.getMissionManager().accept(MAIN));
  }

  void pending(StoryLineLifecycleTest.Director player) {
    assertEquals(0, player.getMissionManager().sub(SUB).getProgress());
    assertEquals(List.of(StoryLineLifecycleTest.SPECIAL), player.getCurrentLineup().getAvatars());
    assertFalse(player.getStoryLineManager().current().getAvailableTrials().contains(FUTURE));
  }

  @Test
  void loadedLiteralTaskReplacesTheActualTrialPartyBeforeCompleting() {
    var player = started();
    assertNull(player.getStoryLineManager().avatar(FUTURE));
    accept(player);
    assertEquals(1, player.getMissionManager().sub(SUB).getProgress());
    assertEquals(List.of(FUTURE), player.getCurrentLineup().getAvatars());
    assertEquals(0, player.getCurrentLineup().getLeader());
    assertEquals(
        Set.of(StoryLineLifecycleTest.SPECIAL, FUTURE),
        player.getStoryLineManager().current().getAvailableTrials());
    assertNotNull(player.getStoryLineManager().avatar(FUTURE));
    assertEquals(70, player.getStoryLineManager().avatar(FUTURE).getLevel());
    assertEquals(Set.of(StoryLineLifecycleTest.ACTOR), player.getAvatars().getAvatars().keySet());
    assertEquals(
        4321,
        player
            .getAvatars()
            .getAvatars()
            .get(StoryLineLifecycleTest.ACTOR)
            .getCurrentHp(player.getLineupManager().getLineups()[0]));
  }

  @Test
  void aClientPartyReplacementCannotUnlockTheFutureActorBeforeItsTask() {
    var player = started();
    assertFalse(((StoryLineup) player.getCurrentLineup()).tryReplace(List.of(FUTURE), 0));
    accept(player);
    assertTrue(
        ((StoryLineup) player.getCurrentLineup())
            .tryReplace(List.of(StoryLineLifecycleTest.SPECIAL, FUTURE), 1));
    assertEquals(
        List.of(StoryLineLifecycleTest.SPECIAL, FUTURE), player.getCurrentLineup().getAvatars());
  }

  @Test
  void missingOrUnsupportedScriptNeverUnlocksAnActor() throws Exception {
    for (String text :
        Arrays.asList(
            null,
            "{}",
            SCRIPT.replace("StoryLineReplaceTrialPlayer", "ReplaceTrialPlayer"),
            SCRIPT.replace("\"$type\":", "\"Unknown\":1,\"$type\":"),
            SCRIPT.replace("\"OnInitSequece\":[]", "\"OnInitSequece\":[{}]"),
            SCRIPT.replace(
                "\"TaskList\":[{", "\"TaskList\":[{\"$type\":\"RPG.GameCore.FinishMission\"},{"))) {
      script(text);
      var player = started();
      accept(player);
      pending(player);
    }
  }

  @Test
  void changedTargetParametersCannotBorrowTheLoadedTask() {
    var config = GameplayDefinitions.subs.get(SUB);
    config.setParamInt1(StoryLineLifecycleTest.SPECIAL);
    var player = started();
    accept(player);
    pending(player);
  }

  @Test
  void unknownCustomVariablesAndRewardsDoNotAuthorizeReplacement() throws Exception {
    for (int variant = 0; variant < 2; variant++) {
      script(SCRIPT);
      var config = GameplayDefinitions.subs.get(SUB);
      if (variant == 0) config.setCustomValueList(new String[] {"unknown"});
      else
        config.setCustomValueReward(
            new MissionInfo.CustomValueReward[] {
              new MissionInfo.CustomValueReward(0, "unknown", 1, 0)
            });
      var player = started();
      accept(player);
      pending(player);
    }
  }

  @Test
  void sourceNpcGroupsAndConflictingStoryIdentityKeepTheTaskPending() throws Exception {
    for (String name : List.of("GroupIDList", "RequiredNPCSeriesIDList", "StoryLineID")) {
      script(SCRIPT);
      var path = root.resolve("Config/Level/Mission/" + MAIN + "/MissionInfo_" + MAIN + ".json");
      var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
      var config = json.getAsJsonArray("SubMissionList").get(0).getAsJsonObject();
      if (name.equals("StoryLineID")) config.addProperty(name, StoryLineLifecycleTest.STORY + 1);
      else {
        var ids = new JsonArray();
        ids.add(7);
        config.add(name, ids);
      }
      Files.writeString(path, json.toString());
      GameplayDefinitions.loadMissions(root.resolve("Config/Level/Mission"));
      GameplayDefinitions.subs.put(start.getId(), start);
      GameplayDefinitions.subs.put(end.getId(), end);
      var player = started();
      accept(player);
      pending(player);
    }
  }

  @Test
  void unacceptedOrCancelledTasksNeverUnlockFutureActors() {
    for (int situation = 0; situation < 3; situation++) {
      var player = started();
      if (situation == 0) player.getMissionManager().refresh();
      else {
        if (situation == 1) player.getGameplay().getCancelledSubs().add(SUB);
        else player.getGameplay().getCancelledMissions().add(MAIN);
        player
            .getGameplay()
            .getMainMissions()
            .put(MAIN, new emu.lunarcore.game.quest.QuestState(MAIN));
        player.getMissionManager().refresh();
      }
      assertFalse(player.getStoryLineManager().current().getAvailableTrials().contains(FUTURE));
      assertEquals(List.of(StoryLineLifecycleTest.SPECIAL), player.getCurrentLineup().getAvatars());
    }
  }

  @Test
  void transitionPendingKeepsTheAcceptedReplacementPending() {
    var player = started();
    var place = new StoryLineState.Place(player);
    player.getGameplay().getStoryLines().begin(StoryLineLifecycleTest.STORY, place, place);
    accept(player);
    pending(player);
  }

  @Test
  void anActiveBattleKeepsTheOldPartyUntilTheNextRefreshAfterBattle() {
    var player = started();
    var team = player.getCurrentLineup();
    var battle =
        new emu.lunarcore.game.battle.Battle(
            player, team, (emu.lunarcore.game.battle.BattleStage) null, false);
    player.setBattle(battle);
    accept(player);
    pending(player);
    assertSame(team, battle.getLineup());
    player.setBattle(null);
    player.getMissionManager().refresh();
    assertEquals(1, player.getMissionManager().sub(SUB).getProgress());
    assertEquals(List.of(FUTURE), player.getCurrentLineup().getAvatars());
  }

  @Test
  void missingFrozenFutureActorNeverCompletesTheTask() {
    var player = started();
    player.getStoryLineManager().current().getTrialConfigs().remove(FUTURE);
    accept(player);
    pending(player);
  }

  @Test
  void liveResourceChangesCannotReplaceTheFrozenFutureActor() {
    var player = started();
    GameData.getSpecialAvatarExcelMap()
        .add(
            trials.fixture.gson.fromJson(
                "{\"SpecialAvatarID\":"
                    + FUTURE
                    + ",\"AvatarID\":199931,\"Level\":5,\"Promotion\":0}",
                SpecialAvatarExcel.class));
    accept(player);
    assertEquals(1, player.getMissionManager().sub(SUB).getProgress());
    assertEquals(70, player.getStoryLineManager().avatar(FUTURE).getLevel());
    assertEquals(
        Map.of(1, 1), new HashMap<>(player.getStoryLineManager().avatar(FUTURE).getSkillTree()));
  }

  @Test
  void savedReplacementRestoresAvailabilityPartyAndFrozenSkills() throws Exception {
    var player = started();
    accept(player);
    var restored = trials.reload(player);
    assertTrue(restored.getStoryLineManager().restoreOnLogin());
    restored.getMissionManager().refresh();
    assertEquals(List.of(FUTURE), restored.getCurrentLineup().getAvatars());
    assertEquals(1, restored.getMissionManager().sub(SUB).getProgress());
    assertNotNull(restored.getStoryLineManager().avatar(FUTURE));
    assertEquals(70, restored.getStoryLineManager().avatar(FUTURE).getLevel());
    assertEquals(
        Map.of(1, 1), new HashMap<>(restored.getStoryLineManager().avatar(FUTURE).getSkillTree()));
  }

  @Test
  void repeatedAndConcurrentRefreshesDoNotResetACompletedPartyChange() throws Exception {
    var player = started();
    accept(player);
    assertEquals(1, player.getMissionManager().sub(SUB).getProgress());
    var team = (StoryLineup) player.getCurrentLineup();
    team.hp(FUTURE, 2468);
    assertTrue(team.tryReplace(List.of(StoryLineLifecycleTest.SPECIAL), 0));
    try (var pool = Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<Future<?>>();
      for (int i = 0; i < 20; i++) calls.add(pool.submit(player.getMissionManager()::refresh));
      for (var call : calls) call.get(5, TimeUnit.SECONDS);
    }
    assertEquals(List.of(StoryLineLifecycleTest.SPECIAL), player.getCurrentLineup().getAvatars());
    assertEquals(2468, team.hp(FUTURE));
    assertEquals(1, player.getMissionManager().sub(SUB).getProgress());
  }

  @Test
  void savedPartyChangeBeforeMissionAdvanceResumesWithoutResettingVitals() throws Exception {
    var player = started();
    player.getGameplay().getMainMissions().put(MAIN, new emu.lunarcore.game.quest.QuestState(MAIN));
    player.getGameplay().getSubMissions().put(SUB, new emu.lunarcore.game.quest.QuestState(SUB));
    assertTrue(StoryTrialReplacement.apply(player, GameplayDefinitions.subs.get(SUB)));
    assertEquals(0, player.getMissionManager().sub(SUB).getProgress());
    ((StoryLineup) player.getCurrentLineup()).hp(FUTURE, 2468);
    var restored = trials.reload(player);
    assertTrue(restored.getStoryLineManager().restoreOnLogin());
    restored.getMissionManager().refresh();
    assertEquals(1, restored.getMissionManager().sub(SUB).getProgress());
    assertEquals(List.of(FUTURE), restored.getCurrentLineup().getAvatars());
    assertEquals(2468, ((StoryLineup) restored.getCurrentLineup()).hp(FUTURE));
  }
}
