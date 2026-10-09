package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.*;
import emu.lunarcore.proto.ExtraLineupTypeOuterClass.ExtraLineupType;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.util.Position;
import java.util.*;
import org.junit.jupiter.api.*;

class TrialActivityLifecycleTest {
  static final int STAGE = 199951, ACTOR = 199951, SPECIAL = 1199951, MISSING = 1199953;
  static final int TYPE = ExtraLineupType.LINEUP_STAGE_TRIAL_VALUE;
  final Gson gson = new Gson();
  List<SpecialAvatarExcel> specials;
  AvatarExcel actor;
  AvatarDemoExcel demo;

  static class Director extends Player {
    final LineupManager teams;
    final PlayerExtraLineup trial;
    boolean allowEntry = true;
    boolean allowReturn = true;
    int entryAttempts, returnAttempts, saves;
    final List<BasePacket> packets = new ArrayList<>();
    emu.lunarcore.game.scene.Scene scene;

    Director() {
      teams = new LineupManager(this);
      trial =
          new PlayerExtraLineup(this, TYPE) {
            public void save() {}
          };
      teams.getExtraLineups().put(TYPE, trial);
    }

    public LineupManager getLineupManager() {
      return teams;
    }

    public Position getPos() {
      return new Position(1, 2, 3);
    }

    public Position getRot() {
      return new Position(0, 70, 0);
    }

    public void sendPacket(BasePacket packet) {
      packets.add(packet);
    }

    public emu.lunarcore.game.scene.Scene getScene() {
      return scene;
    }

    public void save() {
      saves++;
    }

    public boolean enterScene(int entry, int anchor, boolean send) {
      entryAttempts++;
      return allowEntry;
    }

    public boolean loadScene(emu.lunarcore.util.Location place, boolean send) {
      returnAttempts++;
      return allowReturn;
    }
  }

  static class Session extends emu.lunarcore.server.game.GameSession {
    final Player player;
    com.google.protobuf.DynamicMessage response;
    String responseName;
    int replies;

    Session(Player player) {
      super(null);
      this.player = player;
      setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    }

    public Player getPlayer() {
      return player;
    }

    public org.hkrpg.server.protocol.ProtocolBridge getProtocol() {
      return org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    }

    public void sendClientPacket(String name, byte[] bytes) {
      replies++;
      responseName = name;
      try {
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (java.io.IOException error) {
        throw new IllegalStateException(error);
      }
    }
  }

  byte[] request(Session session, String name, int stage) {
    var type = session.getProtocol().clientSchema().message(name);
    return com.google.protobuf.DynamicMessage.newBuilder(type)
        .setField(type.findFieldByName("stage_id"), stage)
        .build()
        .toByteArray();
  }

  void handle(Session session, String name, byte[] body) throws Exception {
    assertTrue(
        org.hkrpg.server.protocol.VersionedPacketHandler.handle(
            session, session.getProtocol().clientCommand(name), body));
  }

  int code(Session session) {
    return (Integer)
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode"));
  }

  @BeforeEach
  void install() {
    specials = new ArrayList<>(GameData.getSpecialAvatarExcelMap().values());
    actor = GameData.getAvatarExcelMap().get(ACTOR);
    demo = GameData.getAvatarDemoExcelMap().get(STAGE);
    GameData.getSpecialAvatarExcelMap()
        .add(
            gson.fromJson(
                "{\"SpecialAvatarID\":1199951,\"AvatarID\":199951,\"Level\":40,\"Promotion\":2}",
                SpecialAvatarExcel.class));
    GameData.getAvatarExcelMap().add(gson.fromJson("{\"AvatarID\":199951}", AvatarExcel.class));
  }

  @AfterEach
  void restore() {
    GameData.getSpecialAvatarExcelMap().values().clear();
    specials.forEach(GameData.getSpecialAvatarExcelMap()::add);
    GameData.getAvatarExcelMap().keySet().remove(ACTOR);
    if (actor != null) GameData.getAvatarExcelMap().add(actor);
    GameData.getAvatarDemoExcelMap().keySet().remove(STAGE);
    if (demo != null) GameData.getAvatarDemoExcelMap().add(demo);
  }

  void roster(int... ids) {
    var row =
        gson.fromJson(
            "{\"StageID\":199951,\"RewardID\":199951,\"MapEntranceID\":30501001,\"MazeGroupID1\":9,"
                + "\"ConfigList1\":[42],\"NpcMonsterIDList1\":[199951],\"EventIDList1\":[199951],\"SPList\":"
                + Arrays.toString(new int[ids.length])
                + ",\"TrialAvatarList\":"
                + Arrays.toString(ids)
                + "}",
            AvatarDemoExcel.class);
    row.onLoad();
    GameData.getAvatarDemoExcelMap().add(row);
  }

  void energy(int... amounts) {
    var existing = GameData.getAvatarDemoExcelMap().get(STAGE);
    var json = gson.toJsonTree(existing).getAsJsonObject();
    json.add("SPList", gson.toJsonTree(amounts));
    json.add("ConfigList1", gson.toJsonTree(new int[] {42}));
    json.add("NpcMonsterIDList1", gson.toJsonTree(new int[] {ACTOR}));
    json.add("EventIDList1", gson.toJsonTree(new int[] {STAGE}));
    var changed = gson.fromJson(json, AvatarDemoExcel.class);
    changed.onLoad();
    GameData.getAvatarDemoExcelMap().add(changed);
  }

  @Test
  void aTrialsResourceInitialEnergyReplacesTheInheritedFixedValueInLineupAndBattle()
      throws Exception {
    roster(SPECIAL);
    energy(0);
    var player = new Director();
    assertNotNull(player.getTrialManager().startTrial(STAGE));
    var avatar = player.getAvatarById(SPECIAL);
    assertEquals(0, avatar.getCurrentSp(player.trial));
    var battle = avatar.toBattleProto(player.trial, 0);
    assertEquals(0, battle.getSpBar().getCurSp());
    var bridge = org.hkrpg.server.protocol.ProtocolBridge.forVersion("4.6.0");
    var nativeAvatar =
        bridge
            .clientSchema()
            .parse("BattleAvatar", bridge.translate("BattleAvatar", battle.toByteArray(), true));
    var bar =
        (com.google.protobuf.DynamicMessage)
            nativeAvatar.getField(nativeAvatar.getDescriptorForType().findFieldByName("sp_bar"));
    assertEquals(0, bar.getField(bar.getDescriptorForType().findFieldByName("cur_sp")));
  }

  @Test
  void enteringATrialPersistsItsFrozenPartyAndHomeBeforeTheSceneCanChange() {
    roster(SPECIAL);
    var player =
        new Director() {
          public int getEntryId() {
            return 2000101;
          }

          public int getPlaneId() {
            return 20001;
          }

          public int getFloorId() {
            return 20001001;
          }

          public boolean enterScene(int entry, int anchor, boolean send) {
            var saved =
                gson.toJsonTree(getGameplay()).getAsJsonObject().getAsJsonObject("trialRun");
            assertNotNull(saved, "Scene entry has no saved trial run or home to restore");
            assertEquals(STAGE, saved.get("stageId").getAsInt());
            assertEquals("ENTERING", saved.get("phase").getAsString());
            assertEquals(2000101, saved.getAsJsonObject("home").get("entry").getAsInt());
            assertTrue(saved.getAsJsonObject("trialConfigs").has(Integer.toString(SPECIAL)));
            assertEquals(
                0,
                saved
                    .getAsJsonObject("vitals")
                    .getAsJsonObject(Integer.toString(SPECIAL))
                    .get("sp")
                    .getAsInt());
            return super.enterScene(entry, anchor, send);
          }
        };
    assertNotNull(player.getTrialManager().startTrial(STAGE));
    var saved = gson.toJsonTree(player.getGameplay()).getAsJsonObject().getAsJsonObject("trialRun");
    assertEquals("ACTIVE", saved.get("phase").getAsString());
    assertTrue(player.saves > 0);
  }

  @Test
  void initialEnergyKeepsTheDeclaredIndexWhenAnOppositeGenderCandidateIsExcluded() {
    roster(MISSING, SPECIAL);
    GameData.getSpecialAvatarExcelMap()
        .add(
            gson.fromJson(
                "{\"SpecialAvatarID\":1199953,\"AvatarID\":8002,\"Level\":40,\"Promotion\":2}",
                SpecialAvatarExcel.class));
    energy(2500, 6300);
    var player = new Director();
    assertNotNull(player.getTrialManager().startTrial(STAGE));
    assertEquals(List.of(SPECIAL), player.trial.getAvatars());
    assertEquals(6300, player.getAvatarById(SPECIAL).getCurrentSp(player.trial));
  }

  @Test
  void outOfRangeTrialInitialEnergyCannotPublishOrStartTheParty() {
    roster(SPECIAL);
    for (int invalid : List.of(-1, 10001)) {
      energy(invalid);
      rejectedWithoutPublishing(new Director());
    }
  }

  @Test
  void anInitialEnergyListWithTheWrongCandidateCountCannotStartATrial() {
    roster(SPECIAL);
    energy(0, 5000);
    rejectedWithoutPublishing(new Director());
  }

  emu.lunarcore.game.battle.Battle trialBattle(Director player, boolean npcBattle, int instance) {
    player.scene =
        new emu.lunarcore.game.scene.Scene(
            player,
            gson.fromJson(
                "{\"PlaneID\":199951,\"PlaneType\":\"TrialActivity\"}", MazePlaneExcel.class),
            199951001);
    var stage =
        gson.fromJson(
            "{\"StageID\":199951,\"MonsterList\":[{\"Monster0\":199951}]}", StageExcel.class);
    stage.onLoad();
    if (!npcBattle) return new emu.lunarcore.game.battle.Battle(player, player.trial, stage);
    var group = new emu.lunarcore.data.config.GroupInfo();
    group.setId(9);
    var monster =
        new emu.lunarcore.game.scene.entity.EntityMonster(
            player.scene,
            gson.fromJson("{\"ID\":199951}", NpcMonsterExcel.class),
            group,
            gson.fromJson(
                "{\"ID\":" + instance + ",\"PosX\":1,\"PosY\":2,\"PosZ\":3,\"RotY\":70}",
                emu.lunarcore.data.config.MonsterInfo.class));
    monster.setEntityId(12345);
    monster.setEventId(STAGE);
    monster.setCustomStage(stage);
    player.scene.getEntities().put(monster.getEntityId(), monster);
    var battle = new emu.lunarcore.game.battle.Battle(player, player.trial, List.of(monster));
    player.scene.removeEntity(monster);
    return battle;
  }

  @Test
  void aConfirmedTrialWinPersistsItsStartTimeRewardBeforeReturningAndIgnoresResourceReload() {
    roster(SPECIAL);
    var oldReward = GameData.getRewardExcelMap().get(STAGE);
    var oldItem = GameData.getItemExcelMap().get(ACTOR);
    try {
      GameData.getItemExcelMap()
          .add(
              gson.fromJson(
                  "{\"ID\":199951,\"ItemMainType\":\"Material\",\"PileLimit\":999}",
                  ItemExcel.class));
      var reward =
          gson.fromJson(
              "{\"RewardID\":199951,\"ItemID_1\":199951,\"Count_1\":7}", RewardExcel.class);
      reward.onLoad();
      GameData.getRewardExcelMap().add(reward);
      var player = new Director();
      var trial = player.getTrialManager().startTrial(STAGE);
      reward.getRewards().put(ACTOR, 999);
      var battle = trialBattle(player, true, 42);
      player.allowReturn = false;
      trial.onBattleFinish(
          battle,
          emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
          emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance());
      var completions =
          gson.toJsonTree(player.getGameplay())
              .getAsJsonObject()
              .getAsJsonObject("trialCompletions");
      assertNotNull(completions, "Confirmed trial victory was only transient");
      var plan = completions.getAsJsonObject(Integer.toString(STAGE));
      assertNotNull(plan);
      assertEquals(7, plan.getAsJsonObject("items").get(Integer.toString(ACTOR)).getAsInt());
      assertTrue(plan.get("ready").getAsBoolean());
      assertTrue(trial.isFinished());
      assertSame(trial, player.getInstance());
      assertTrue(player.saves > 0);
      var restored = gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class);
      assertEquals(
          completions,
          gson.toJsonTree(restored).getAsJsonObject().getAsJsonObject("trialCompletions"));
      int saves = player.saves;
      trial.onBattleFinish(
          battle,
          emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
          emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance());
      assertEquals(saves, player.saves);
    } finally {
      GameData.getRewardExcelMap().keySet().remove(STAGE);
      GameData.getItemExcelMap().keySet().remove(ACTOR);
      if (oldReward != null) GameData.getRewardExcelMap().add(oldReward);
      if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
    }
  }

  @Test
  void aStageOnlyVictoryCannotCompleteAConfiguredTrialMonster() {
    roster(SPECIAL);
    var player = new Director();
    var trial = player.getTrialManager().startTrial(STAGE);
    var battle = trialBattle(player, false, 0);
    trial.onBattleFinish(
        battle,
        emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
        emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance());
    assertFalse(trial.isFinished());
    assertSame(trial, player.getInstance());
    assertEquals(0, player.returnAttempts);
  }

  @Test
  void aVictoryAgainstAnUnconfiguredNpcCannotCompleteTheTrial() {
    roster(SPECIAL);
    var player = new Director();
    var trial = player.getTrialManager().startTrial(STAGE);
    var battle = trialBattle(player, true, 99);
    trial.onBattleFinish(
        battle,
        emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
        emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance());
    assertFalse(trial.isFinished());
    assertSame(trial, player.getInstance());
    assertEquals(0, player.returnAttempts);
  }

  @Test
  void theConfiguredNpcVictoryReturnsAndSendsOneNativeFinishWithTheActualStage() {
    roster(SPECIAL);
    var player = new Director();
    var session = new Session(player);
    player.setSession(session);
    var trial = player.getTrialManager().startTrial(STAGE);
    var battle = trialBattle(player, true, 42);
    trial.onBattleFinish(
        battle,
        emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
        emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance());
    assertTrue(trial.isFinished());
    assertNull(player.getInstance());
    assertEquals(1, player.returnAttempts);
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertEquals("CurTrialActivityScNotify", session.responseName);
    assertEquals(1, session.replies);
    assertEquals(
        STAGE,
        session.response.getField(session.response.getDescriptorForType().findFieldByNumber(1)));
    assertEquals(
        1,
        ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                session.response.getField(
                    session.response.getDescriptorForType().findFieldByNumber(10)))
            .getNumber());
    trial.onBattleFinish(
        battle,
        emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
        emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance());
    assertEquals(1, session.replies);
    assertEquals(1, player.returnAttempts);
  }

  void rejectedWithoutPublishing(Director player) {
    assertNull(player.getTrialManager().startTrial(STAGE));
    assertNull(player.getInstance());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertTrue(player.trial.getAvatars().isEmpty());
    assertEquals(0, player.teams.getCurrentExtraIndex());
    assertEquals(0, player.entryAttempts);
    assertEquals(0, player.saves);
  }

  @Test
  void aMissingPartyMemberCannotStartAnIncompleteTrialOrPublishEarlierMembers() {
    roster(SPECIAL);
    var valid = new Director();
    assertNotNull(
        valid.getTrialManager().startTrial(STAGE), "the configured member can start a trial");
    roster(SPECIAL, MISSING);
    rejectedWithoutPublishing(new Director());
  }

  @Test
  void aDuplicateConfiguredPartyCannotPublishOrStartATrial() {
    roster(SPECIAL, SPECIAL);
    rejectedWithoutPublishing(new Director());
  }

  @Test
  void aPreexistingTemporaryActorCannotBeOverwrittenByANewTrial() {
    roster(SPECIAL);
    var player = new Director();
    var previous = player.getTrialManager().createTrialAvatar(SPECIAL);
    assertNotNull(previous);
    assertNull(player.getTrialManager().startTrial(STAGE));
    assertSame(previous, player.getAvatarById(SPECIAL));
    assertEquals(Map.of(SPECIAL, previous), player.getAvatars().getTempAvatars());
    assertTrue(player.trial.getAvatars().isEmpty());
    assertNull(player.getInstance());
    assertEquals(0, player.entryAttempts);
    assertEquals(0, player.saves);
  }

  @Test
  void anOrdinaryActorAbove8000DoesNotUseTheTrailblazerGenderHeuristic() {
    roster(SPECIAL);
    var player =
        new Director() {
          public emu.lunarcore.game.player.PlayerGender getGender() {
            return emu.lunarcore.game.player.PlayerGender.GENDER_WOMAN;
          }
        };
    assertNotNull(player.getTrialManager().startTrial(STAGE));
    assertEquals(List.of(SPECIAL), player.trial.getAvatars());
    assertEquals(ACTOR, player.getAvatarById(SPECIAL).getActorId());
    player.setInstance(null);
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void cn46CanStartAndLeaveTheActualTrialPartyUsingItsStageIdentity(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
    var configField = emu.lunarcore.LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    var oldConfig = configField.get(null);
    var config = new emu.lunarcore.Config();
    config.resourceDir = root.toString();
    var oldWindows = new ArrayList<>(GameplayDefinitions.windows);
    var oldPanel = GameData.getActivityPanelExcelMap().get(STAGE);
    configField.set(null, config);
    TrialActivityWindowTest.catalog(root, STAGE, STAGE * 100 + 1, STAGE);
    GameData.getActivityPanelExcelMap()
        .add(
            gson.fromJson(
                "{\"PanelID\":"
                    + STAGE
                    + ",\"UIPrefab\":\"UI/Quest/Widget/RoleTrialPanel.prefab\"}",
                ActivityPanelExcel.class));
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(STAGE, STAGE * 100 + 1, 0, Long.MAX_VALUE));
    try {
      roster(SPECIAL);
      var player = new Director();
      var session = new Session(player);
      handle(
          session, "StartTrialActivityCsReq", request(session, "StartTrialActivityCsReq", STAGE));
      assertEquals("StartTrialActivityScRsp", session.responseName);
      assertEquals(0, code(session));
      assertEquals(
          STAGE,
          session.response.getField(session.response.getDescriptorForType().findFieldByNumber(5)));
      var trial = player.getInstance(emu.lunarcore.game.trial.TrialInstance.class);
      assertNotNull(trial);
      assertEquals(List.of(SPECIAL), player.trial.getAvatars());
      handle(
          session, "LeaveTrialActivityCsReq", request(session, "LeaveTrialActivityCsReq", STAGE));
      assertEquals("LeaveTrialActivityScRsp", session.responseName);
      assertEquals(0, code(session));
      assertEquals(
          STAGE,
          session.response.getField(session.response.getDescriptorForType().findFieldByNumber(5)));
      assertNull(player.getInstance());
      assertFalse(trial.isFinished());
      assertTrue(player.getAvatars().getTempAvatars().isEmpty());
      assertEquals(1, player.returnAttempts);
    } finally {
      configField.set(null, oldConfig);
      GameplayDefinitions.windows.clear();
      GameplayDefinitions.windows.addAll(oldWindows);
      GameData.getActivityPanelExcelMap().keySet().remove(STAGE);
      if (oldPanel != null) GameData.getActivityPanelExcelMap().add(oldPanel);
    }
  }

  @Test
  void staleDuplicateAndLargeIntegerLeaveRequestsCannotEndTheCurrentTrial() throws Exception {
    roster(SPECIAL);
    var player = new Director();
    var trial = player.getTrialManager().startTrial(STAGE);
    var session = new Session(player);
    byte[] valid = request(session, "LeaveTrialActivityCsReq", STAGE);
    byte[] duplicated = new byte[valid.length * 2];
    System.arraycopy(valid, 0, duplicated, 0, valid.length);
    System.arraycopy(valid, 0, duplicated, valid.length, valid.length);
    var output = new java.io.ByteArrayOutputStream();
    var coded = com.google.protobuf.CodedOutputStream.newInstance(output);
    coded.writeUInt64(12, (1L << 32) + STAGE);
    coded.flush();
    for (var body :
        List.of(
            request(session, "LeaveTrialActivityCsReq", STAGE + 1),
            duplicated,
            output.toByteArray(),
            new byte[0],
            new byte[] {8, 1})) {
      handle(session, "LeaveTrialActivityCsReq", body);
      assertEquals(emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID.getVal(), code(session));
      assertSame(trial, player.getInstance());
      assertNotNull(player.getAvatarById(SPECIAL));
      assertEquals(TYPE, player.teams.getCurrentExtraIndex());
      assertEquals(0, player.returnAttempts);
    }
  }

  @Test
  void aFailedReturnKeepsTheTrialPartyForAnExplicitRetry() throws Exception {
    roster(SPECIAL);
    var player = new Director();
    var trial = player.getTrialManager().startTrial(STAGE);
    var session = new Session(player);
    player.allowReturn = false;
    handle(session, "LeaveTrialActivityCsReq", request(session, "LeaveTrialActivityCsReq", STAGE));
    assertEquals(emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(), code(session));
    assertSame(trial, player.getInstance());
    assertNotNull(player.getAvatarById(SPECIAL));
    assertEquals(TYPE, player.teams.getCurrentExtraIndex());
    assertFalse(trial.isFinished());
    player.allowReturn = true;
    handle(session, "LeaveTrialActivityCsReq", request(session, "LeaveTrialActivityCsReq", STAGE));
    assertEquals(0, code(session));
    assertNull(player.getInstance());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertEquals(2, player.returnAttempts);
  }

  @Test
  void unauthenticatedCn46TrialRequestsCannotCreateOrLeaveAStage() throws Exception {
    roster(SPECIAL);
    var player = new Director();
    var session = new Session(player);
    session.setState(emu.lunarcore.server.packet.SessionState.WAITING_FOR_TOKEN);
    for (String name : List.of("StartTrialActivityCsReq", "LeaveTrialActivityCsReq"))
      handle(session, name, request(session, name, STAGE));
    assertNull(session.response);
    assertNull(player.getInstance());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertEquals(0, player.entryAttempts);
    assertEquals(0, player.returnAttempts);
  }

  @Test
  void abandoningATrialDoesNotPublishAFabricatedFinishNotification() {
    roster(SPECIAL);
    var player = new Director();
    var trial = player.getTrialManager().startTrial(STAGE);
    assertNotNull(trial);
    player.packets.clear();
    player.setInstance(null);
    assertFalse(trial.isFinished());
    assertTrue(
        player.packets.stream()
            .noneMatch(
                packet ->
                    packet.getCmdId()
                        == emu.lunarcore.server.packet.CmdId.CurTrialActivityScNotify));
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void losingABattleInAnEmptyTrialSceneCannotCompleteTheTrial() {
    roster(SPECIAL);
    var player = new Director();
    var trial = player.getTrialManager().startTrial(STAGE);
    assertNotNull(trial);
    player.scene =
        new emu.lunarcore.game.scene.Scene(
            player,
            gson.fromJson(
                "{\"PlaneID\":199951,\"PlaneType\":\"TrialActivity\"}", MazePlaneExcel.class),
            199951001);
    var stage =
        gson.fromJson(
            "{\"StageID\":199951,\"MonsterList\":[{\"Monster0\":199951}]}", StageExcel.class);
    stage.onLoad();
    var battle = new emu.lunarcore.game.battle.Battle(player, player.trial, stage);
    assertEquals(1, battle.getWaves().size());
    trial.onBattleFinish(
        battle,
        emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_LOSE,
        emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance());
    assertFalse(trial.isFinished());
    assertSame(trial, player.getInstance());
    assertNotNull(player.getAvatarById(SPECIAL));
  }

  @Test
  void failedSceneEntryRemovesOwnedTrialActorsAndRestoresThePreviousPartyState() {
    roster(SPECIAL);
    var player = new Director();
    player.allowEntry = false;
    player.trial.getAvatars().add(98765);
    player.trial.setLeader(0);
    player.trial.setMp(2);
    assertNull(player.getTrialManager().startTrial(STAGE));
    assertEquals(1, player.entryAttempts);
    assertNull(player.getInstance());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertNull(player.getAvatarById(SPECIAL));
    assertEquals(List.of(98765), player.trial.getAvatars());
    assertEquals(2, player.trial.getMp());
    assertEquals(0, player.teams.getCurrentExtraIndex());
  }

  @Test
  void endingATrialClearsItsOwnActorsAndPartyWithoutRemovingUnrelatedTemporaryActors() {
    roster(SPECIAL);
    var player = new Director();
    var unrelated = new GameAvatar(GameData.getAvatarExcelMap().get(ACTOR));
    player.getAvatars().getTempAvatars().put(88888, unrelated);
    var trial = player.getTrialManager().startTrial(STAGE);
    assertNotNull(trial);
    assertNotNull(player.getAvatarById(SPECIAL));
    assertEquals(List.of(SPECIAL), player.trial.getAvatars());
    player.setInstance(null);
    assertNull(player.getAvatarById(SPECIAL));
    assertEquals(Map.of(88888, unrelated), player.getAvatars().getTempAvatars());
    assertTrue(player.trial.getAvatars().isEmpty());
    assertEquals(0, player.trial.getMp());
    assertEquals(0, player.teams.getCurrentExtraIndex());
  }
}
