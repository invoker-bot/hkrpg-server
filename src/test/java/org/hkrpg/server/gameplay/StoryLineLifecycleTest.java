package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.trial.TrialAvatar;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class StoryLineLifecycleTest {
  static final int STORY = 199931, START = 19993101, END = 19993102;
  static final int PLANE = 199931, FLOOR = 199931001, ENTRY = 199931001;
  static final int ACTOR = 199931, SPECIAL = 1199931;
  final Gson gson = new Gson();
  @TempDir Path root;
  Map<String, FloorInfo> oldFloors;
  Map<Integer, MissionInfo.SubMissionInfo> oldSubs;
  List<MapEntranceExcel> oldEntries;
  List<MazePlaneExcel> oldPlanes;
  List<SpecialAvatarExcel> oldSpecials;
  AvatarExcel oldActor;
  Field catalog;
  Object oldCatalog;

  static class Director extends Player {
    boolean rejectScene;
    int saves;

    Director() {
      try {
        for (String name : List.of("pos", "rot")) {
          var field = Player.class.getDeclaredField(name);
          field.setAccessible(true);
          field.set(this, new Position());
        }
        var field = Player.class.getDeclaredField("lineupManager");
        field.setAccessible(true);
        field.set(this, new emu.lunarcore.game.player.lineup.LineupManager(this));
        getLineupManager().getLineups()[0] = new NormalTeam(this);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    public void save() {
      saves++;
    }

    public void sendPacket(emu.lunarcore.server.packet.BasePacket packet) {}

    // Deliberately fail after the persistent transition is reserved.
    public boolean loadScene(
        int plane, int floor, int entry, Position pos, Position rot, boolean send, int dimension) {
      return !rejectScene && super.loadScene(plane, floor, entry, pos, rot, false, dimension);
    }
  }

  static class NormalTeam extends emu.lunarcore.game.player.lineup.PlayerLineup {
    NormalTeam(Player player) {
      super(player, 0);
    }

    public void save() {}
  }

  static class ExtraTeam extends emu.lunarcore.game.player.lineup.PlayerExtraLineup {
    ExtraTeam(Player player) {
      super(
          player,
          emu.lunarcore.proto.ExtraLineupTypeOuterClass.ExtraLineupType.LINEUP_STAGE_TRIAL_VALUE);
    }

    public void save() {}
  }

  @BeforeEach
  void prepare() throws Exception {
    oldFloors = new HashMap<>(GameData.getFloorInfos());
    oldSubs = new HashMap<>(GameplayDefinitions.subs);
    oldEntries = new ArrayList<>(GameData.getMapEntranceExcelMap().values());
    oldPlanes = new ArrayList<>(GameData.getMazePlaneExcelMap().values());
    oldSpecials = new ArrayList<>(GameData.getSpecialAvatarExcelMap().values());
    oldActor = GameData.getAvatarExcelMap().get(ACTOR);
    catalog = StoryLineDefinitions.class.getDeclaredField("catalog");
    catalog.setAccessible(true);
    oldCatalog = catalog.get(null);
    var floor =
        gson.fromJson(
            "{\"FloorID\":199931001,\"DimensionList\":["
                + "{\"ID\":0,\"GroupIndexList\":[0]},{\"ID\":4,\"GroupIndexList\":[1]}],"
                + "\"GroupInstanceList\":[{\"ID\":8},{\"ID\":9}]}",
            FloorInfo.class);
    for (int id : List.of(8, 9)) {
      var group =
          gson.fromJson(
              "{\"GroupName\":\"Story lifecycle\",\"LoadSide\":\"Server\","
                  + "\"PropList\":[],\"AnchorList\":[{\"ID\":1,\"PosX\":"
                  + id
                  + ",\"PosY\":2,\"PosZ\":3,\"RotY\":70}]}",
              GroupInfo.class);
      group.setId(id);
      floor.getGroups().put(id, group);
    }
    GameData.getFloorInfos().put("P" + PLANE + "_F" + FLOOR, floor);
    GameData.getMapEntranceExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":199931001,\"PlaneID\":199931,"
                    + "\"FloorID\":199931001,\"StartGroupID\":8,\"StartAnchorID\":1}",
                MapEntranceExcel.class));
    GameData.getMazePlaneExcelMap()
        .add(
            gson.fromJson(
                "{\"PlaneID\":199931,\"PlaneType\":\"Town\"," + "\"WorldID\":100}",
                MazePlaneExcel.class));
    GameData.getAvatarExcelMap().add(gson.fromJson("{\"AvatarID\":199931}", AvatarExcel.class));
    GameData.getSpecialAvatarExcelMap()
        .add(
            gson.fromJson(
                "{\"SpecialAvatarID\":1199931,"
                    + "\"AvatarID\":199931,\"Level\":40,\"Promotion\":2}",
                SpecialAvatarExcel.class));
    Files.createDirectories(root.resolve("ExcelOutput"));
    writeStory(false);
    Files.writeString(
        root.resolve("ExcelOutput/StoryLineFloorData.json"),
        "[{\"StoryLineID\":199931,\"FloorID\":199931001,\"DimensionID\":4,"
            + "\"ConditionExpression\":\"[BetweenSubMission:19993101,19993102]\"}]");
    Files.writeString(
        root.resolve("ExcelOutput/StoryLine.json"),
        "[{\"StoryLineID\":199931,\"BeginCondition\":{\"Type\":\"SubMissionTaken\",\"Param\":\"19993101\"},"
            + "\"EndCondition\":{\"Type\":\"FinishSubMission\",\"Param\":\"19993102\"},"
            + "\"ShowCondition\":\"[BetweenSubMission:19993101,19993102]\","
            + "\"InitEntranceID\":199931001,\"InitGroupID\":9,\"InitAnchorID\":1}]");
    StoryLineDefinitions.load(root);
    for (int id : List.of(START, END))
      GameplayDefinitions.subs.put(
          id,
          gson.fromJson(
              "{\"ID\":" + id + ",\"MainMissionID\":199931}", MissionInfo.SubMissionInfo.class));
  }

  void writeStory(boolean skip) throws Exception {
    Files.writeString(
        root.resolve("ExcelOutput/StroyLineTrialAvatarData.json"),
        "[{\"StoryLineID\":199931,\"TrialAvatarList\":[1199931],\"InitTrialAvatarList\":[1199931],"
            + "\"CaptainAvatarID\":1199931,\"SkipJoinLineup\":"
            + skip
            + "}]");
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getFloorInfos().clear();
    GameData.getFloorInfos().putAll(oldFloors);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameData.getMapEntranceExcelMap().keySet().clear();
    oldEntries.forEach(GameData.getMapEntranceExcelMap()::add);
    GameData.getMazePlaneExcelMap().keySet().clear();
    oldPlanes.forEach(GameData.getMazePlaneExcelMap()::add);
    GameData.getSpecialAvatarExcelMap().values().clear();
    oldSpecials.forEach(GameData.getSpecialAvatarExcelMap()::add);
    GameData.getAvatarExcelMap().keySet().remove(ACTOR);
    if (oldActor != null) GameData.getAvatarExcelMap().add(oldActor);
    catalog.set(null, oldCatalog);
  }

  Director player(boolean taken) {
    var player = new Director();
    var formal = new GameAvatar(GameData.getAvatarExcelMap().get(ACTOR));
    formal.setOwner(player);
    formal.setExcel(GameData.getAvatarExcelMap().get(ACTOR));
    player.getAvatars().getAvatars().put(ACTOR, formal);
    var team = player.getCurrentLineup();
    team.getAvatars().clear();
    team.getAvatars().add(ACTOR);
    formal.setCurrentHp(team, 4321);
    formal.setCurrentSp(team, 2345);
    player.getGameplay().getMainMissions().put(STORY, new QuestState(STORY));
    if (taken) player.getGameplay().getSubMissions().put(START, new QuestState(START));
    assertTrue(
        player.loadScene(
            PLANE, FLOOR, ENTRY, new Position(123, 456, 789), new Position(1, 2, 3), false));
    return player;
  }

  Retcode enter(Director player) {
    return player.getStoryLineManager().change(STORY, 0, 0, 0, false);
  }

  @Test
  void trialSecretPointBonusUsesTheActorIdAndSurvivesSavedBranchRestore() throws Exception {
    var old = GameData.getAvatarExcelMap().get(1408);
    try {
      GameData.getAvatarExcelMap().add(gson.fromJson("{\"AvatarID\":1408}", AvatarExcel.class));
      GameData.getSpecialAvatarExcelMap()
          .add(
              gson.fromJson(
                  "{\"SpecialAvatarID\":1199931,\"AvatarID\":1408,\"Level\":80,\"Promotion\":6}",
                  SpecialAvatarExcel.class));
      var player = player(true);
      assertEquals(Retcode.SUCC, enter(player));
      var party = player.getCurrentLineup();
      assertEquals(8, party.getMaxMp());
      party.setMp(8);
      assertEquals(8, party.getMp());
      var restored = player(true);
      var field = Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(restored, gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
      assertTrue(restored.getStoryLineManager().restoreOnLogin());
      assertEquals(8, restored.getCurrentLineup().getMp());
      assertEquals(8, restored.getCurrentLineup().getMaxMp());
      restored.getCurrentLineup().setMp(99);
      assertEquals(8, restored.getCurrentLineup().getMp());
    } finally {
      GameData.getAvatarExcelMap().keySet().remove(1408);
      if (old != null) GameData.getAvatarExcelMap().add(old);
    }
  }

  @Test
  void reenteringTheCurrentBranchKeepsItsLivePositionEvenBeforeAPeriodicSave() {
    var player = player(true);
    assertEquals(Retcode.SUCC, enter(player));
    player.getPos().set(411, 522, 633);
    player.getRot().set(4, 5, 6);
    assertTrue(player.getStoryLineManager().canChange(STORY, 0, 0, 0));
    assertEquals(Retcode.SUCC, enter(player));
    assertEquals(411, player.getPos().getX());
    assertEquals(522, player.getPos().getY());
    assertEquals(6, player.getRot().getZ());
    assertEquals(411, player.getStoryLineManager().current().getLocation().getPos().getX());
  }

  @Test
  void completedSavedBranchReturnsHomeOnLoginWithoutRecreatingItsExpiredDimension()
      throws Exception {
    var player = player(true);
    assertEquals(Retcode.SUCC, enter(player));
    var end = new QuestState(END);
    assertTrue(end.advance(1, 1, 1000));
    player.getGameplay().getSubMissions().put(END, end);
    var restored = player(true);
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(restored, gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    assertTrue(restored.getStoryLineManager().restoreOnLogin());
    assertEquals(0, restored.getGameplay().getStoryLines().getCurrentId());
    assertEquals(0, restored.getScene().getDimensionId());
    assertEquals(123, restored.getPos().getX());
    assertEquals(List.of(ACTOR), restored.getCurrentLineup().getAvatars());
    assertFalse(restored.getGameplay().getStoryLines().getRuns().containsKey(STORY));
    assertNull(restored.getGameplay().getStoryLines().getHome());
  }

  @Test
  void aResourceBranchChangesActualDimensionAndTrialPartyWithoutEditingTheNormalRoster() {
    var player = player(true);
    var normal = player.getCurrentLineup();
    assertEquals(Retcode.SUCC, enter(player));
    assertEquals(4, player.getScene().getDimensionId());
    assertEquals(Set.of(9), player.getScene().getLoadedGroups());
    assertEquals(List.of(SPECIAL), player.getCurrentLineup().getAvatars());
    assertTrue(player.getCurrentLineup().isExtraLineup());
    assertInstanceOf(TrialAvatar.class, player.getAvatarById(SPECIAL));
    assertEquals(List.of(ACTOR), normal.getAvatars());
    assertEquals(4321, player.getAvatarById(ACTOR).getCurrentHp(normal));
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    var wireParty = player.getCurrentLineup().toProto();
    assertEquals(1, wireParty.getAvatarList().length());
    assertEquals(SPECIAL, wireParty.getAvatarList().get(0).getId());
    assertEquals(10000, wireParty.getAvatarList().get(0).getHp());
  }

  @Test
  void leavingOnTheSameFloorRecreatesTheMainDimensionAndRestoresAnIndependentPositionCopy() {
    var player = player(true);
    var main = player.getScene();
    var team = player.getCurrentLineup();
    assertEquals(Retcode.SUCC, enter(player));
    player.getPos().set(900, 800, 700);
    player.getRot().set(8, 7, 6);
    assertEquals(Retcode.SUCC, player.getStoryLineManager().change(0, 0, 0, 0, false));
    assertEquals(0, player.getScene().getDimensionId());
    assertNotSame(main, player.getScene());
    assertEquals(Set.of(8), player.getScene().getLoadedGroups());
    assertSame(team, player.getCurrentLineup());
    assertEquals(123, player.getPos().getX());
    assertEquals(2, player.getRot().getY());
    assertNull(player.getAvatarById(SPECIAL));
    assertNull(player.getGameplay().getStoryLines().getHome());
    assertEquals(0, player.getGameplay().getStoryLines().getCurrentId());
  }

  @Test
  void aLockedBranchOrAnAnchorFromTheWrongDimensionHasNoPartialSideEffects() {
    var player = player(false);
    var scene = player.getScene();
    var party = player.getCurrentLineup();
    assertNotEquals(Retcode.SUCC, enter(player));
    player.getGameplay().getSubMissions().put(START, new QuestState(START));
    assertNotEquals(Retcode.SUCC, player.getStoryLineManager().change(STORY, ENTRY, 8, 1, false));
    assertSame(scene, player.getScene());
    assertSame(party, player.getCurrentLineup());
    assertTrue(player.getGameplay().getStoryLines().getRuns().isEmpty());
    assertNull(player.getAvatarById(SPECIAL));
  }

  @Test
  void missingTrialsAndUnverifiedSkipJoinBranchesRejectTheWholeEntry() throws Exception {
    var player = player(true);
    var valid = GameData.getSpecialAvatarExcelMap().get(SPECIAL, 0);
    GameData.getSpecialAvatarExcelMap().values().clear();
    assertNotEquals(Retcode.SUCC, enter(player));
    assertEquals(0, player.getScene().getDimensionId());
    oldSpecials.forEach(GameData.getSpecialAvatarExcelMap()::add);
    GameData.getSpecialAvatarExcelMap().add(valid);
    assertTrue(player.getStoryLineManager().canChange(STORY, 0, 0, 0));
    writeStory(true);
    StoryLineDefinitions.load(root);
    assertNotEquals(Retcode.SUCC, enter(player));
    assertTrue(player.getGameplay().getStoryLines().getRuns().isEmpty());
  }

  @Test
  void pendingEntryIsRecoverableFromItsFrozenPlayerDocumentWithoutBorrowingNewTrialStats()
      throws Exception {
    var player = player(true);
    player.rejectScene = true;
    assertEquals(Retcode.PLAYER_DATA_ERROR, enter(player));
    assertTrue(player.getGameplay().getStoryLines().isTransitionPending());
    var saved = gson.toJson(player.getGameplay());
    GameData.getSpecialAvatarExcelMap().values().clear();
    var restored = player(true);
    var state = Player.class.getDeclaredField("gameplay");
    state.setAccessible(true);
    state.set(restored, gson.fromJson(saved, GameplayState.class));
    assertTrue(restored.getStoryLineManager().restoreOnLogin());
    assertEquals(4, restored.getScene().getDimensionId());
    assertEquals(40, restored.getAvatarById(SPECIAL).getLevel());
    assertFalse(restored.getGameplay().getStoryLines().isTransitionPending());
  }

  @Test
  void trialLifeEnergyLeaderAndTechniquePointsSurviveSuspendAndResume() {
    var player = player(true);
    assertEquals(Retcode.SUCC, enter(player));
    var lineup = player.getCurrentLineup();
    var trial = player.getAvatarById(SPECIAL);
    trial.setCurrentHp(lineup, 3210);
    trial.setCurrentSp(lineup, 6543);
    lineup.setMp(2);
    assertEquals(Retcode.SUCC, player.getStoryLineManager().change(0, 0, 0, 0, false));
    assertEquals(Retcode.SUCC, enter(player));
    var resumed = player.getCurrentLineup();
    assertEquals(3210, player.getAvatarById(SPECIAL).getCurrentHp(resumed));
    assertEquals(6543, player.getAvatarById(SPECIAL).getCurrentSp(resumed));
    assertEquals(2, resumed.getMp());
  }

  @Test
  void anInterruptedExplicitReturnKeepsItsAuthoredDestinationInsteadOfTheOriginalHome()
      throws Exception {
    var player = player(true);
    assertEquals(Retcode.SUCC, enter(player));
    player.rejectScene = true;
    assertEquals(
        Retcode.PLAYER_DATA_ERROR, player.getStoryLineManager().change(0, ENTRY, 8, 1, false));
    var saved = gson.toJson(player.getGameplay());
    var restored = player(true);
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(restored, gson.fromJson(saved, GameplayState.class));
    assertTrue(restored.getStoryLineManager().restoreOnLogin());
    // Authored anchor coordinates are metres converted to the wire's millimetres.
    assertEquals(8000, restored.getPos().getX());
    assertEquals(0, restored.getScene().getDimensionId());
  }

  @Test
  void disconnectRecordsActualMovementAndAllPartyVitalsBeforeAPlayerDocumentRestore()
      throws Exception {
    var player = player(true);
    assertEquals(Retcode.SUCC, enter(player));
    player.getPos().set(900, 800, 700);
    player.getRot().set(8, 7, 6);
    var lineup = player.getCurrentLineup();
    var trial = player.getAvatarById(SPECIAL);
    trial.setCurrentHp(lineup, 3210);
    trial.setCurrentSp(lineup, 6543);
    lineup.setMp(2);
    player.onLogout();
    var restored = player(true);
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(restored, gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    assertTrue(restored.getStoryLineManager().restoreOnLogin());
    assertEquals(900, restored.getPos().getX());
    assertEquals(7, restored.getRot().getY());
    var resumed = restored.getCurrentLineup();
    assertEquals(3210, restored.getAvatarById(SPECIAL).getCurrentHp(resumed));
    assertEquals(6543, restored.getAvatarById(SPECIAL).getCurrentSp(resumed));
    assertEquals(2, resumed.getMp());
  }

  @Test
  void aStoryTrialCannotBeWrittenIntoAnyFormalTeamThroughTheGenericEditor() {
    var player = player(true);
    var formal = player.getCurrentLineup();
    assertEquals(Retcode.SUCC, enter(player));
    assertFalse(player.getLineupManager().joinLineup(0, 1, SPECIAL));
    assertFalse(player.getLineupManager().replaceLineup(0, 0, List.of(SPECIAL)));
    assertEquals(List.of(ACTOR), formal.getAvatars());
  }

  @Test
  void theActualFinishActionEntersItsResourceBranchAndACompletedCursorDoesNotSwitchAgain() {
    var player = player(true);
    var sub =
        gson.fromJson(
            "{\"ID\":19993199,\"FinishActionList\":[{\"FinishActionType\":\"ChangeStoryLine\","
                + "\"FinishActionPara\":[199931,0,0,0]}]}",
            MissionInfo.SubMissionInfo.class);
    assertEquals(Retcode.SUCC, new StoryActions(player).execute(sub));
    player.getPos().set(555, 666, 777);
    assertEquals(Retcode.SUCC, new StoryActions(player).execute(sub));
    assertEquals(555, player.getPos().getX());
    assertEquals(1, player.getGameplay().getStoryActions().get(sub.getId()).getNext());
  }

  @Test
  void anotherExtraPartyCannotBecomeTheHomeOfAStoryBranch() {
    var player = player(true);
    player.getLineupManager().setCurrentExtraLineup(13, false);
    assertNotEquals(Retcode.SUCC, enter(player));
    assertTrue(player.getGameplay().getStoryLines().getRuns().isEmpty());
  }

  @Test
  void anActiveBranchBlocksTrialActivityBeforeItCanPublishTemporaryActorsOrAnInstance() {
    var player = player(true);
    assertEquals(Retcode.SUCC, enter(player));
    var old = GameData.getAvatarDemoExcelMap().get(STORY);
    player
        .getLineupManager()
        .getExtraLineups()
        .put(
            emu.lunarcore.proto.ExtraLineupTypeOuterClass.ExtraLineupType.LINEUP_STAGE_TRIAL_VALUE,
            new ExtraTeam(player));
    GameData.getAvatarDemoExcelMap()
        .add(
            gson.fromJson(
                "{\"StageID\":199931,\"MapEntranceID\":199931001,"
                    + "\"TrialAvatarList\":[1199931]}",
                AvatarDemoExcel.class));
    try {
      var result = assertDoesNotThrow(() -> player.getTrialManager().startTrial(STORY));
      assertNull(result);
      assertNull(player.getInstance());
      assertTrue(player.getAvatars().getTempAvatars().isEmpty());
      assertEquals(List.of(SPECIAL), player.getCurrentLineup().getAvatars());
    } finally {
      GameData.getAvatarDemoExcelMap().keySet().remove(STORY);
      if (old != null) GameData.getAvatarDemoExcelMap().add(old);
    }
  }

  @Test
  void aSavedRunWhoseIdentityDoesNotMatchItsMapKeyCannotActivateItsActors() throws Exception {
    var player = player(true);
    assertEquals(Retcode.SUCC, enter(player));
    var saved =
        gson.toJson(player.getGameplay())
            .replace("\"id\":199931,\"leader\"", "\"id\":199932,\"leader\"");
    assertNotEquals(gson.toJson(player.getGameplay()), saved);
    var restored = player(true);
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(restored, gson.fromJson(saved, GameplayState.class));
    assertThrows(IllegalStateException.class, () -> restored.getStoryLineManager().lineup());
    assertThrows(IllegalStateException.class, () -> restored.getAvatarById(SPECIAL));
  }

  void checkPendingBattleEntry(boolean cocoon) throws Exception {
    int stageId = 199938;
    var oldStage = GameData.getStageExcelMap().get(stageId);
    var oldCocoon = GameData.getCocoonExcelMap().get(stageId, 0);
    var stage =
        gson.fromJson(
            "{\"StageID\":199938,\"StageType\":\""
                + (cocoon ? "Cocoon" : "Mainline")
                + "\",\"Level\":30,\"MonsterList\":[{\"Monster0\":199938}]}",
            StageExcel.class);
    stage.onLoad();
    var resource =
        gson.fromJson(
            "{\"ID\":199938,\"MappingInfoID\":199938,\"CocoonType\":\"TYPE_NORMAL\",\"WorldLevel\":0,\"StaminaCost\":10,"
                + "\"MaxChallengeCnt\":6,\"StageIDList\":[199938],\"DropList\":[]}",
            CocoonExcel.class);
    GameData.getStageExcelMap().add(stage);
    GameData.getCocoonExcelMap().add(resource);
    try {
      var player = player(true);
      assertEquals(Retcode.SUCC, enter(player));
      var stamina = Player.class.getDeclaredField("stamina");
      stamina.setAccessible(true);
      stamina.setInt(player, 240);
      var service = new emu.lunarcore.game.battle.BattleService(null);
      var control =
          cocoon
              ? service.startCocoon(player, stageId, 0, 1)
              : service.startBattle(player, stageId, 0);
      assertNotNull(control, "The configured entry must work before the transition");
      assertEquals(1, control.getWaves().size());
      player.setBattle(null);
      player
          .getGameplay()
          .getStoryLines()
          .begin(STORY, new StoryLineState.Place(player), new StoryLineState.Place(player));
      var blocked =
          cocoon
              ? service.startCocoon(player, stageId, 0, 1)
              : service.startBattle(player, stageId, 0);
      assertNull(blocked);
      assertNull(player.getBattle());
      assertEquals(240, player.getStamina());
      assertTrue(player.getGameplay().getStoryLines().isTransitionPending());
    } finally {
      GameData.getStageExcelMap().keySet().remove(stageId);
      if (oldStage != null) GameData.getStageExcelMap().add(oldStage);
      GameData.getCocoonExcelMap().values().remove(resource);
      if (oldCocoon != null) GameData.getCocoonExcelMap().add(oldCocoon);
    }
  }

  @Test
  void pendingStoryTransitionCannotStartAConfiguredBattleStage() throws Exception {
    checkPendingBattleEntry(false);
  }

  @Test
  void pendingStoryTransitionCannotStartAConfiguredCocoonBattle() throws Exception {
    checkPendingBattleEntry(true);
  }

  @Test
  void pendingStoryTransitionCannotStartAnOtherwiseWorkingMonsterAmbush() {
    var player = player(true);
    assertEquals(Retcode.SUCC, enter(player));
    var stage =
        gson.fromJson(
            "{\"StageID\":199939,\"Level\":30,\"MonsterList\":[{\"Monster0\":199939}]}",
            StageExcel.class);
    stage.onLoad();
    var group = new GroupInfo();
    group.setId(9);
    var info = gson.fromJson("{\"ID\":42,\"Pos\":{},\"Rot\":{}}", MonsterInfo.class);
    var npc = gson.fromJson("{\"ID\":199939}", NpcMonsterExcel.class);
    var monster =
        new emu.lunarcore.game.scene.entity.EntityMonster(player.getScene(), npc, group, info);
    monster.setEntityId(199939);
    monster.setCustomStage(stage);
    player.getScene().getEntities().put(199939, monster);
    var hits = new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet();
    hits.add(player.getScene().getAvatarEntityIds().iterator().nextInt());
    var assists = new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet();
    var service = new emu.lunarcore.game.battle.BattleService(null);
    service.attack(player, 199939, 199939, null, hits, assists);
    assertNotNull(player.getBattle(), "The ambush must work before the transition");
    assertEquals(List.of(monster), player.getBattle().getNpcMonsters());
    player.setBattle(null);
    player
        .getGameplay()
        .getStoryLines()
        .begin(STORY, new StoryLineState.Place(player), new StoryLineState.Place(player));
    service.attack(player, 199939, 199939, null, hits, assists);
    assertNull(player.getBattle());
    assertEquals(5, player.getCurrentLineup().getMp());
    assertTrue(monster.isAlive());
    assertTrue(player.getGameplay().getStoryLines().isTransitionPending());
  }
}
