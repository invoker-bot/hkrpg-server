package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.challenge.*;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.*;
import org.junit.jupiter.api.*;

class MissionChallengeWinTest {
  static final int MAIN = 19996924, SUB = 199969241, CHALLENGE = 19996924;
  static final int EVENT = 19996924, STAGE = 199969240, NPC = 19996924, ENTRY = 19996924;
  final Gson gson = new Gson();
  final GameplayProgressTest missions = new GameplayProgressTest();
  final MissionFarmingBattleTest.Service service = new MissionFarmingBattleTest.Service();
  ChallengeExcel oldChallenge;
  StageExcel oldStage;
  NpcMonsterExcel oldNpc;
  MapEntranceExcel oldEntry;
  List<PlaneEventExcel> oldEvents;

  static class Challenger extends MissionFarmingBattleTest.Farmer {
    static class SilentSession extends emu.lunarcore.server.game.GameSession {
      SilentSession() {
        super(null);
      }

      public void send(int command) {}

      public void send(emu.lunarcore.server.packet.BasePacket packet) {}
    }

    final SilentSession session = new SilentSession();
    final emu.lunarcore.util.Position position = new emu.lunarcore.util.Position();
    final emu.lunarcore.util.Position rotation = new emu.lunarcore.util.Position();

    public emu.lunarcore.server.game.GameSession getSession() {
      return session;
    }

    public emu.lunarcore.util.Position getPos() {
      return position;
    }

    public emu.lunarcore.util.Position getRot() {
      return rotation;
    }

    int entry = ENTRY;
    Scene scene;
    boolean rejectEnter;

    public boolean enterScene(int id, int anchor, boolean send) {
      return !rejectEnter;
    }

    GameplayState saved;
    final emu.lunarcore.game.avatar.GameAvatar avatar =
        new emu.lunarcore.game.avatar.GameAvatar() {
          public int getMaxSp() {
            return 100;
          }

          public int getCurrentSp(emu.lunarcore.game.player.lineup.PlayerLineup lineup) {
            return 0;
          }

          public int getAvatarId() {
            return 199961;
          }

          public int getCurrentHp(emu.lunarcore.game.player.lineup.PlayerLineup lineup) {
            return 10000;
          }

          public void save() {}

          public emu.lunarcore.proto.BattleAvatarOuterClass.BattleAvatar toBattleProto(
              emu.lunarcore.game.player.lineup.PlayerLineup lineup, int index) {
            return emu.lunarcore.proto.BattleAvatarOuterClass.BattleAvatar.newInstance()
                .setId(199961)
                .setHp(10000);
          }
        };

    public emu.lunarcore.game.avatar.GameAvatar getAvatarById(int id) {
      return id == 199961 ? avatar : null;
    }

    public int getPlaneId() {
      return 10000;
    }

    public int getFloorId() {
      return 10000000;
    }

    public int getEntryId() {
      return entry;
    }

    public Scene getScene() {
      return scene;
    }

    public void save() {
      saved = new Gson().fromJson(new Gson().toJson(getGameplay()), GameplayState.class);
    }

    final emu.lunarcore.game.challenge.ChallengeManager challenges =
        new emu.lunarcore.game.challenge.ChallengeManager(this) {
          public synchronized void addHistory(int id, int stars, int score, boolean extra) {}
        };

    public emu.lunarcore.game.challenge.ChallengeManager getChallengeManager() {
      return challenges;
    }
  }

  @BeforeEach
  void resources() {
    oldChallenge = GameData.getChallengeExcelMap().get(CHALLENGE);
    oldStage = GameData.getStageExcelMap().get(STAGE);
    oldNpc = GameData.getNpcMonsterExcelMap().get(NPC);
    oldEntry = GameData.getMapEntranceExcelMap().get(ENTRY);
    oldEvents =
        GameData.getPlaneEventExcelMap().values().stream()
            .filter(e -> e.getEventId() == EVENT)
            .toList();
    GameData.getPlaneEventExcelMap().values().removeIf(e -> e.getEventId() == EVENT);
    GameData.getPlaneEventExcelMap()
        .add(
            gson.fromJson(
                "{\"EventID\":" + EVENT + ",\"StageID\":" + STAGE + "}", PlaneEventExcel.class));
    GameData.getNpcMonsterExcelMap()
        .add(gson.fromJson("{\"ID\":" + NPC + "}", NpcMonsterExcel.class));
    GameData.getMapEntranceExcelMap()
        .add(
            gson.fromJson(
                "{\"ID\":" + ENTRY + ",\"PlaneID\":10000,\"FloorID\":10000000}",
                MapEntranceExcel.class));
    var stage =
        gson.fromJson(
            "{\"StageID\":"
                + STAGE
                + ",\"StageType\":\"Challenge\","
                + "\"MonsterList\":[{\"Monster0\":1011010}]}",
            StageExcel.class);
    stage.onLoad();
    GameData.getStageExcelMap().add(stage);
    challenge(1);
    mission("ChallengeFinishCnt", ",\"ParamType\":\"NoPara\"");
  }

  void challenge(int stages) {
    var row =
        gson.fromJson(
            "{\"ID\":"
                + CHALLENGE
                + ",\"MapEntranceID\":"
                + ENTRY
                + ",\"StageNum\":"
                + stages
                + ",\"ChallengeCountDown\":20,\"ChallengeTargetID\":[],"
                + "\"MazeGroupID1\":2,\"ConfigList1\":[200001],\"NpcMonsterIDList1\":["
                + NPC
                + "],\"EventIDList1\":["
                + EVENT
                + "],\"MazeGroupID2\":3,\"ConfigList2\":[200001],"
                + "\"NpcMonsterIDList2\":["
                + NPC
                + "],\"EventIDList2\":["
                + EVENT
                + "]}",
            ChallengeExcel.class);
    row.onLoad();
    GameData.getChallengeExcelMap().add(row);
  }

  void mission(String type, String params) {
    missions.mission(
        MAIN,
        "Manual",
        Integer.toString(SUB),
        "[{\"ID\":"
            + SUB
            + ",\"MainMissionID\":"
            + MAIN
            + ",\"TakeType\":\"Auto\",\"FinishType\":\""
            + type
            + "\",\"LevelPlaneID\":10000,\"LevelFloorID\":10000000"
            + params
            + "}]");
  }

  @AfterEach
  void cleanup() {
    missions.reset();
    GameData.getMainMissionExcelMap().keySet().remove(MAIN);
    GameData.getMissionInfos().remove(MAIN);
    GameData.getChallengeExcelMap().keySet().remove(CHALLENGE);
    GameData.getStageExcelMap().keySet().remove(STAGE);
    GameData.getNpcMonsterExcelMap().keySet().remove(NPC);
    GameData.getMapEntranceExcelMap().keySet().remove(ENTRY);
    if (oldChallenge != null) GameData.getChallengeExcelMap().add(oldChallenge);
    if (oldStage != null) GameData.getStageExcelMap().add(oldStage);
    if (oldNpc != null) GameData.getNpcMonsterExcelMap().add(oldNpc);
    if (oldEntry != null) GameData.getMapEntranceExcelMap().add(oldEntry);
    GameData.getPlaneEventExcelMap().values().removeIf(e -> e.getEventId() == EVENT);
    oldEvents.forEach(GameData.getPlaneEventExcelMap()::add);
  }

  Challenger player(boolean accept) {
    var p = new Challenger();
    p.setInstance(new ChallengeInstance(p, GameData.getChallengeExcelMap().get(CHALLENGE)));
    p.getLineupManager().setCurrentExtraLineup(1, false);
    p.getCurrentLineup().getAvatars().add(199961);
    p.getLineupManager().getExtraLineupByType(3).getAvatars().add(199961);
    p.scene =
        new Scene(
            p,
            gson.fromJson("{\"PlaneID\":10000,\"PlaneType\":\"Challenge\"}", MazePlaneExcel.class),
            10000000) {
          public void loadGroup(int id) {}

          public void refreshMissionGroups() {}

          public emu.lunarcore.proto.SceneInfoOuterClass.SceneInfo toProto() {
            return emu.lunarcore.proto.SceneInfoOuterClass.SceneInfo.newInstance();
          }
        };
    if (accept) assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    return p;
  }

  EntityMonster monster(Challenger p, int group, int instance) {
    var groupInfo = new GroupInfo();
    groupInfo.setId(group);
    var monster =
        new EntityMonster(
            p.getScene(),
            GameData.getNpcMonsterExcelMap().get(NPC),
            groupInfo,
            gson.fromJson("{\"ID\":" + instance + "}", MonsterInfo.class));
    monster.setEventId(EVENT);
    monster.setEntityId(99);
    p.getScene().getEntities().put(99, monster);
    return monster;
  }

  Battle start(Challenger p) {
    var instance = (ChallengeInstance) p.getInstance();
    monster(p, instance.getCurrentStage() == 1 ? 2 : 3, 200001);
    service.attack(
        p,
        p.getScene().getAvatarEntityIds().iterator().nextInt(),
        0,
        null,
        new IntOpenHashSet(new int[] {99}),
        new IntOpenHashSet());
    return Objects.requireNonNull(p.getBattle());
  }

  Battle finish(Challenger p, Battle b, BattleEndStatus status) {
    return service.finishBattle(
        p, b.getId(), b.getStage().getId(), status, BattleStatistics.newInstance());
  }

  int progress(Challenger p) {
    return p.getMissionManager().sub(SUB).getProgress();
  }

  @Test
  void confirmedZeroStarMemoryVictoryCompletesAndPersistsTheAcceptedCountObjectiveOnce() {
    var p = player(true);
    var battle = start(p);
    assertNotNull(finish(p, battle, BattleEndStatus.BATTLE_END_WIN));
    assertTrue(((ChallengeInstance) p.getInstance()).isWin());
    assertEquals(0, ((ChallengeInstance) p.getInstance()).getStars());
    assertEquals(1, progress(p));
    assertEquals(1, p.saved.getSubMissions().get(SUB).getProgress());
    assertNull(finish(p, battle, BattleEndStatus.BATTLE_END_WIN));
  }

  @Test
  void partialFirstHalfDoesNotCompleteTheWholeChallenge() {
    challenge(2);
    var p = player(true);
    assertNotNull(finish(p, start(p), BattleEndStatus.BATTLE_END_WIN));
    assertFalse(((ChallengeInstance) p.getInstance()).isWin());
    assertEquals(0, progress(p));
    assertNotNull(finish(p, start(p), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, progress(p));
  }

  @Test
  void anObjectiveAcceptedAfterPreparationWaitsForAnotherActualChallenge() {
    var p = player(false);
    var battle = start(p);
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    assertNotNull(finish(p, battle, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(0, progress(p));
    p.setInstance(new ChallengeInstance(p, GameData.getChallengeExcelMap().get(CHALLENGE)));
    assertNotNull(finish(p, start(p), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, progress(p));
  }

  @Test
  void wrongIdentityRejectedReportThenValidRetryCanComplete() {
    var p = player(true);
    var b = start(p);
    assertNull(
        service.finishBattle(
            p,
            b.getId() + 1,
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance()));
    assertEquals(0, progress(p));
    assertSame(b, p.getBattle());
    assertNotNull(finish(p, b, BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, progress(p));
  }

  @Test
  void lossQuitAndAWinWithRemainingSceneMonstersDoNotComplete() {
    for (var result :
        List.of(
            BattleEndStatus.BATTLE_END_LOSE,
            BattleEndStatus.BATTLE_END_QUIT,
            BattleEndStatus.BATTLE_END_WIN)) {
      var p = player(true);
      var b = start(p);
      if (result == BattleEndStatus.BATTLE_END_WIN) {
        var extra = monster(p, 2, 200002);
        extra.setEntityId(100);
        p.getScene().getEntities().put(100, extra);
      }
      assertNotNull(finish(p, b, result));
      assertEquals(0, progress(p));
    }
  }

  @Test
  void wrongNpcAndStageOnlyBattleCannotAuthorizeChallengeProgress() {
    for (int situation = 0; situation < 2; situation++) {
      var p = player(true);
      Battle b;
      if (situation == 0) {
        monster(p, 7, 200001);
        service.attack(
            p,
            p.getScene().getAvatarEntityIds().iterator().nextInt(),
            0,
            null,
            new IntOpenHashSet(new int[] {99}),
            new IntOpenHashSet());
        b = p.getBattle();
      } else b = service.startBattle(p, EVENT, 0);
      assertNotNull(finish(p, b, BattleEndStatus.BATTLE_END_WIN));
      assertEquals(0, progress(p));
    }
  }

  @Test
  void cancelChangedLocationAndExtraParametersRemainPending() {
    for (int situation = 0; situation < 5; situation++) {
      mission("ChallengeFinishCnt", ",\"ParamType\":\"NoPara\"");
      var p = player(true);
      var b = start(p);
      if (situation == 0) p.getGameplay().getCancelledSubs().add(SUB);
      if (situation == 1) p.entry++;
      if (situation == 2) GameplayDefinitions.subs.get(SUB).setParamInt1(1);
      if (situation == 3) GameplayDefinitions.subs.get(SUB).setParamType("Equal");
      if (situation == 4) GameplayDefinitions.subs.get(SUB).setProgress(2);
      assertNotNull(finish(p, b, BattleEndStatus.BATTLE_END_WIN));
      assertEquals(0, progress(p));
    }
  }

  @Test
  void explicitCompletedChallengeCanBeBacktrackedAfterGsonRestore() throws Exception {
    mission(
        "ChallengeFinish",
        ",\"ParamType\":\"Equal\",\"ParamInt1\":" + CHALLENGE + ",\"IsBackTrack\":true");
    var p = player(false);
    assertNotNull(finish(p, start(p), BattleEndStatus.BATTLE_END_WIN));
    var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(p, gson.fromJson(gson.toJson(p.getGameplay()), GameplayState.class));
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    assertEquals(1, progress(p));
  }

  @Test
  void unknownHistoryWrongChallengeAndNonBacktrackDoNotBorrowOldCompletions() {
    for (int situation = 0; situation < 3; situation++) {
      mission(
          "ChallengeFinish",
          ",\"ParamType\":\"Equal\",\"ParamInt1\":"
              + (situation == 1 ? CHALLENGE + 1 : CHALLENGE)
              + ",\"IsBackTrack\":"
              + (situation != 2));
      var p = player(false);
      if (situation != 0) assertNotNull(finish(p, start(p), BattleEndStatus.BATTLE_END_WIN));
      assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
      assertEquals(0, progress(p));
    }
  }

  @Test
  void specifiedChallengeVictoryCompletesOnlyItsExactAcceptedId() {
    mission("ChallengeFinish", ",\"ParamType\":\"Equal\",\"ParamInt1\":" + CHALLENGE);
    var p = player(true);
    assertNotNull(finish(p, start(p), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, progress(p));
  }

  @Test
  void sourceAuthoredCustomChallengeStageIsAcceptedButUnmatchedOverridesAreRejected() {
    for (boolean valid : List.of(true, false)) {
      var p = player(true);
      var monster = monster(p, 2, 200001);
      var override = gson.fromJson("{\"StageID\":17,\"MonsterList\":[]}", StageExcel.class);
      override.onLoad();
      monster.setCustomStage(valid ? GameData.getStageExcelMap().get(STAGE) : override);
      service.attack(
          p,
          p.getScene().getAvatarEntityIds().iterator().nextInt(),
          0,
          null,
          new IntOpenHashSet(new int[] {99}),
          new IntOpenHashSet());
      var b = p.getBattle();
      assertNotNull(finish(p, b, BattleEndStatus.BATTLE_END_WIN));
      assertEquals(valid ? 1 : 0, progress(p));
      assertEquals(valid, p.getGameplay().getCompletedMemoryChallenges().contains(CHALLENGE));
    }
  }

  @Test
  void olderDocumentsHaveNoFabricatedCompletionAndReturnedSetIsReadOnly() {
    var empty = gson.fromJson("{\"completedMemoryChallenges\":null}", GameplayState.class);
    assertTrue(empty.getCompletedMemoryChallenges().isEmpty());
    assertThrows(
        UnsupportedOperationException.class,
        () -> empty.getCompletedMemoryChallenges().add(CHALLENGE));
    assertTrue(empty.recordMemoryChallenge(CHALLENGE));
    assertFalse(empty.recordMemoryChallenge(CHALLENGE));
    assertFalse(empty.recordMemoryChallenge(0));
    assertEquals(Set.of(CHALLENGE), empty.getCompletedMemoryChallenges());
  }

  emu.lunarcore.server.game.GameSession session(Challenger p) {
    return new Challenger.SilentSession() {
      public emu.lunarcore.game.player.Player getPlayer() {
        return p;
      }
    };
  }

  @Test
  void successfulChallengeLeaveClearsTheInstanceForTheNextStart() throws Exception {
    var p = player(false);
    new emu.lunarcore.server.packet.recv.HandlerLeaveChallengeCsReq()
        .handle(session(p), new byte[0]);
    assertNull(p.getInstance());
    assertEquals(0, p.getCurrentLineup().getExtraLineupType());
  }

  @Test
  void failedChallengeReturnKeepsTheActiveInstanceAndItsLineup() throws Exception {
    var p = player(false);
    p.rejectEnter = true;
    var instance = p.getInstance();
    new emu.lunarcore.server.packet.recv.HandlerLeaveChallengeCsReq()
        .handle(session(p), new byte[0]);
    assertSame(instance, p.getInstance());
    assertEquals(1, p.getCurrentLineup().getExtraLineupType());
  }

  @Test
  void challengeObjectiveNavigationFloorIsSeparateFromTheActualChallengeBattleFloor() {
    var config = GameplayDefinitions.subs.get(SUB);
    config.setLevelPlaneID(10001);
    config.setLevelFloorID(10001000);
    var p = player(true);
    assertNotNull(finish(p, start(p), BattleEndStatus.BATTLE_END_WIN));
    assertEquals(1, progress(p));
  }

  void roundsTarget(int left) {
    var row = GameData.getChallengeExcelMap().get(CHALLENGE);
    var target =
        gson.fromJson(
            "{\"ID\":"
                + CHALLENGE
                + ",\"ChallengeTargetType\":\"ROUNDS_LEFT\",\"ChallengeTargetParam1\":"
                + left
                + "}",
            ChallengeTargetExcel.class);
    GameData.getChallengeTargetExcelMap().add(target);
    try {
      var ids = ChallengeExcel.class.getDeclaredField("ChallengeTargetID");
      ids.setAccessible(true);
      ids.set(row, new int[] {CHALLENGE});
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError(exception);
    }
  }

  @Test
  void finalBattleRoundsAreConsumedBeforeStarsAreCalculated() {
    roundsTarget(16);
    try {
      var p = player(false);
      var instance = (ChallengeInstance) p.getInstance();
      var b = start(p);
      assertNotNull(
          service.finishBattle(
              p,
              b.getId(),
              STAGE,
              BattleEndStatus.BATTLE_END_WIN,
              BattleStatistics.newInstance().setRoundCnt(5)));
      assertEquals(15, instance.getRoundsLeft());
      assertEquals(0, instance.getStars());
    } finally {
      GameData.getChallengeTargetExcelMap().keySet().remove(CHALLENGE);
    }
  }

  @Test
  void finalWinAtTheExactRemainingRoundThresholdEarnsItsStar() {
    roundsTarget(15);
    try {
      var p = player(false);
      var instance = (ChallengeInstance) p.getInstance();
      var b = start(p);
      assertNotNull(
          service.finishBattle(
              p,
              b.getId(),
              STAGE,
              BattleEndStatus.BATTLE_END_WIN,
              BattleStatistics.newInstance().setRoundCnt(5)));
      assertEquals(15, instance.getRoundsLeft());
      assertEquals(1, instance.getStars());
    } finally {
      GameData.getChallengeTargetExcelMap().keySet().remove(CHALLENGE);
    }
  }

  @Test
  void bothHalvesContributeToTheFinalRoundThreshold() {
    challenge(2);
    roundsTarget(13);
    try {
      var p = player(false);
      var instance = (ChallengeInstance) p.getInstance();
      var first = start(p);
      assertNotNull(
          service.finishBattle(
              p,
              first.getId(),
              STAGE,
              BattleEndStatus.BATTLE_END_WIN,
              BattleStatistics.newInstance().setRoundCnt(4)));
      assertEquals(16, instance.getRoundsLeft());
      assertFalse(instance.isWin());
      monster(p, 3, 200001);
      service.attack(
          p,
          p.getScene().getAvatarEntityIds().iterator().nextInt(),
          0,
          null,
          new IntOpenHashSet(new int[] {99}),
          new IntOpenHashSet());
      var second = p.getBattle();
      assertNotNull(
          service.finishBattle(
              p,
              second.getId(),
              STAGE,
              BattleEndStatus.BATTLE_END_WIN,
              BattleStatistics.newInstance().setRoundCnt(4)));
      assertEquals(12, instance.getRoundsLeft());
      assertEquals(0, instance.getStars());
    } finally {
      GameData.getChallengeTargetExcelMap().keySet().remove(CHALLENGE);
    }
  }

  @Test
  void invalidNegativeRoundsKeepTheBattleForAValidRetry() {
    for (int invalid : new int[] {-1, Integer.MIN_VALUE}) {
      var p = player(false);
      var instance = (ChallengeInstance) p.getInstance();
      var b = start(p);
      assertNull(
          service.finishBattle(
              p,
              b.getId(),
              STAGE,
              BattleEndStatus.BATTLE_END_WIN,
              BattleStatistics.newInstance().setRoundCnt(invalid)));
      assertSame(b, p.getBattle());
      assertEquals(20, instance.getRoundsLeft());
      assertFalse(instance.isWin());
      assertNotNull(
          service.finishBattle(
              p,
              b.getId(),
              STAGE,
              BattleEndStatus.BATTLE_END_WIN,
              BattleStatistics.newInstance().setRoundCnt(8)));
      assertTrue(instance.isWin());
      assertEquals(12, instance.getRoundsLeft());
    }
  }

  @Test
  void actualFinalSettlementContainsTheFinishedChallengeAndConsumedRounds() throws Exception {
    var p = player(false);
    var instance = (ChallengeInstance) p.getInstance();
    var b = start(p);
    assertNotNull(
        service.finishBattle(
            p,
            b.getId(),
            STAGE,
            BattleEndStatus.BATTLE_END_WIN,
            BattleStatistics.newInstance().setRoundCnt(5)));
    var packet = new emu.lunarcore.server.packet.send.PacketChallengeSettleNotify(instance);
    var protocol = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    var reply =
        protocol
            .clientSchema()
            .parse(
                "ChallengeSettleNotify",
                protocol.translate("ChallengeSettleNotify", packet.getData().toByteArray(), true));
    var current =
        (com.google.protobuf.DynamicMessage)
            reply.getField(reply.getDescriptorForType().findFieldByName("cur_challenge"));
    assertEquals(
        CHALLENGE,
        current.getField(current.getDescriptorForType().findFieldByName("challenge_id")));
    assertEquals(
        5, current.getField(current.getDescriptorForType().findFieldByName("round_count")));
    assertEquals(
        instance.getStatus(),
        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                current.getField(current.getDescriptorForType().findFieldByName("status")))
            .getNumber());
    assertTrue(reply.getUnknownFields().asMap().isEmpty());
  }
}
