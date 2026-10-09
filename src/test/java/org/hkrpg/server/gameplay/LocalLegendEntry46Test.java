package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.battle.*;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original event/stage/actor, accepted-story and window fixtures; no official client input. */
class LocalLegendEntry46Test {
  @TempDir Path root;
  final LocalLegendAccessTest fixture = new LocalLegendAccessTest();
  final List<PlaneEventExcel> oldEvents = new ArrayList<>();
  final List<StageExcel> oldStages = new ArrayList<>();
  final List<AvatarExcel> oldActors = new ArrayList<>();
  Session session;
  Formal formal;

  static class Actor extends LocalLegendTargetQuestsTest.Actor {
    final PlayerLineup ordinary = new PlayerLineup(this, 0);
    int world = -1;

    @Override
    public int getWorldLevel() {
      return world < 0 ? super.getWorldLevel() : world;
    }

    @Override
    public PlayerLineup getCurrentLineup() {
      return ordinary;
    }
  }

  static class Formal extends GameAvatar {
    int saves;

    Formal(AvatarExcel source) {
      super(source);
    }

    @Override
    public void save() {
      saves++;
    }
  }

  static class Session extends GameSession {
    final Actor actor = new Actor();
    DynamicMessage response;

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
      actor.resetPosition();
      actor.setSession(this);
    }

    @Override
    public Actor getPlayer() {
      return actor;
    }

    @Override
    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    @Override
    public void send(BasePacket packet) {}

    @Override
    public void sendClientPacket(String name, byte[] body) {
      try {
        response = getProtocol().clientSchema().parse(name, body);
      } catch (java.io.IOException e) {
        throw new AssertionError(e);
      }
    }
  }

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
    fixture.window(1);
    session = new Session();
    session.actor.setLevel(21);
    session
        .actor
        .getGameplay()
        .getMainMissions()
        .putAll(fixture.fixture.actor.getGameplay().getMainMissions());
    var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(session.actor, 7310246);
    oldEvents.addAll(GameData.getPlaneEventExcelMap().values());
    oldStages.addAll(GameData.getStageExcelMap().values());
    oldActors.addAll(GameData.getAvatarExcelMap().values());
    GameData.getPlaneEventExcelMap().values().clear();
    GameData.getStageExcelMap().values().clear();
    GameData.getAvatarExcelMap().values().clear();
    for (var value : rows("local-legend-all-stages-4.6.json")) {
      var row = value.getAsJsonObject();
      if (row.get("kind").getAsString().equals("event"))
        GameData.getPlaneEventExcelMap()
            .add(ResourceLoader.getGson().fromJson(row.get("row"), PlaneEventExcel.class));
      else {
        var stage = ResourceLoader.getGson().fromJson(row.get("row"), StageExcel.class);
        stage.onLoad();
        GameData.getStageExcelMap().add(stage);
      }
    }
    var source =
        ResourceLoader.getGson()
            .fromJson(
                rows("local-legend-formal-actor-4.6.json").get(0).getAsJsonObject().get("row"),
                AvatarExcel.class);
    GameData.getAvatarExcelMap().add(source);
    formal = new Formal(source);
    formal.setOwner(session.actor);
    var id = GameAvatar.class.getDeclaredField("id");
    id.setAccessible(true);
    id.set(formal, new org.bson.types.ObjectId());
    session.actor.getAvatars().getAvatars().put(8001, formal);
    session.actor.getAvatars().getAvatarObjectIdMap().put(formal.getId(), formal);
    session.actor.ordinary.getAvatars().add(8001);
    formal.setCurrentHp(session.actor.ordinary, 1200);
    formal.setCurrentSp(session.actor.ordinary, 3000);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(50070, 5007003, 1, Long.MAX_VALUE));
  }

  JsonArray rows(String name) throws Exception {
    return JsonParser.parseString(
            new String(
                Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/" + name))
                    .readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8))
        .getAsJsonArray();
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getPlaneEventExcelMap().values().clear();
    oldEvents.forEach(GameData.getPlaneEventExcelMap()::add);
    GameData.getStageExcelMap().values().clear();
    oldStages.forEach(GameData.getStageExcelMap()::add);
    GameData.getAvatarExcelMap().values().clear();
    oldActors.forEach(GameData.getAvatarExcelMap()::add);
    fixture.restore();
  }

  byte[] request(int difficulty, int... roster) {
    var schema = session.getProtocol().clientSchema();
    var type = schema.message("EnterLocalLegendLevelCsReq");
    var builder =
        DynamicMessage.newBuilder(type)
            .setField(type.findFieldByName("group_id"), 1)
            .setField(type.findFieldByName("DBKHHCGGKNN"), difficulty);
    var member = type.findFieldByName("avatar_list").getMessageType();
    for (int id : roster)
      builder.addRepeatedField(
          type.findFieldByName("avatar_list"),
          DynamicMessage.newBuilder(member)
              .setField(member.findFieldByName("avatar_id"), id)
              .setField(
                  member.findFieldByName("avatar_type"),
                  member
                      .findFieldByName("avatar_type")
                      .getEnumType()
                      .findValueByName("AvatarType_AvatarFormalType"))
              .build());
    return builder.build().toByteArray();
  }

  LocalLegendBattle enter() throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 6591, request(1, 8001)));
    assertEquals(0, retcode());
    return assertInstanceOf(LocalLegendBattle.class, session.actor.getBattle());
  }

  Battle finish(LocalLegendBattle battle, BattleEndStatus result, BattleStatistics stats) {
    return new BattleService(null)
        .finishBattle(session.actor, battle.getId(), battle.getStage().getId(), result, stats);
  }

  int retcode() {
    return (Integer)
        session.response.getField(
            session.response.getDescriptorForType().findFieldByName("retcode"));
  }

  @Test
  void dedicatedEntryIncludesNativeCategoryFiveWithEveryCurrentLevelTarget() throws Exception {
    var battle = enter();
    var map = battle.toProto().getBattleTargetInfo();
    assertEquals(1, map.length());
    assertEquals(5, map.get(0).getKey());
    var goals = map.get(0).getValue().getBattleTargetList();
    assertEquals(1, goals.length());
    assertEquals(5001804, goals.get(0).getId());
    assertEquals(0, goals.get(0).getProgress());
    assertEquals(1, goals.get(0).getTotalProgress());
    var wire =
        (DynamicMessage)
            session.response.getField(
                session.response.getDescriptorForType().findFieldByName("battle_info"));
    var field = wire.getDescriptorForType().findFieldByName("battle_target_info");
    assertEquals(2004, field.getNumber());
    assertEquals(1, wire.getRepeatedFieldCount(field));
    var category = (DynamicMessage) wire.getRepeatedField(field, 0);
    assertEquals(5, category.getField(category.getDescriptorForType().findFieldByName("key")));
  }

  @Test
  void newBattleTargetsStartAtZeroAndCannotBeChangedThroughReturnedSnapshots() throws Exception {
    var easy = LocalLegendCatalog.level(1, 1);
    var hard = LocalLegendCatalog.level(1, 2);
    session.actor.getGameplay().getLocalLegendProgress().merge(easy, 1, 100);
    session.actor.getGameplay().getLocalLegendProgress().merge(hard, hard.allowedMask(), 100);
    session.actor.getQuestManager().refreshUnlocks();
    int savedBeforeEntry = session.actor.saves;
    assertTrue(VersionedPacketHandler.handle(session, 6591, request(2, 8001)));
    assertEquals(0, retcode());
    var battle = assertInstanceOf(LocalLegendBattle.class, session.actor.getBattle());
    var snapshot = battle.toProto();
    assertEquals(1, snapshot.getBattleTargetInfo().length());
    var goals = snapshot.getBattleTargetInfo().get(0).getValue().getBattleTargetList();
    assertEquals(3, goals.length());
    for (int i = 0; i < goals.length(); i++) {
      assertEquals(hard.goals().get(i).id(), goals.get(i).getId());
      assertEquals(
          hard.goals().get(i).threshold(), Integer.toUnsignedLong(goals.get(i).getTotalProgress()));
      assertEquals(0, goals.get(i).getProgress());
    }
    var frozen = battle.toProto();
    goals.get(0).setProgress(1).setId(5001804).setTotalProgress(999);
    snapshot.getBattleTargetInfo().get(0).setKey(1);
    assertEquals(frozen, battle.toProto());
    assertEquals(
        hard.allowedMask(), session.actor.getGameplay().getLocalLegendProgress().mask(hard));
    assertEquals(savedBeforeEntry, session.actor.saves);
    assertEquals(0, formal.saves);
  }

  @Test
  void nativeTrialTypeCannotBorrowAnOwnedFormalActor() throws Exception {
    var request =
        session
            .getProtocol()
            .clientSchema()
            .parse("EnterLocalLegendLevelCsReq", request(1, 8001))
            .toBuilder();
    var list = request.getDescriptorForType().findFieldByName("avatar_list");
    var member = ((DynamicMessage) request.getRepeatedField(list, 0)).toBuilder();
    var type = member.getDescriptorForType().findFieldByName("avatar_type");
    member.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarTrialType"));
    request.setRepeatedField(list, 0, member.build());
    assertTrue(VersionedPacketHandler.handle(session, 6591, request.build().toByteArray()));
    assertNotEquals(0, retcode());
    assertFalse(session.actor.isInBattle());
    assertEquals(0, session.actor.saves);
    assertEquals(0, formal.saves);
  }

  @Test
  void originalEasyEntryCreatesAnActualIsolatedBattleWithoutChangingTheFormalParty()
      throws Exception {
    var level = LocalLegendCatalog.level(1, 1);
    assertTrue(
        LocalLegendAccess.available(session.actor, level, java.time.Instant.now()),
        "Original access fixture");
    assertNotNull(level.stage(session.actor.getWorldLevel()), "Original world/stage fixture");
    assertNotNull(
        LocalLegendStageBinding.identity(level, level.stage(session.actor.getWorldLevel())),
        "Original stage settings fixture");
    assertNotNull(
        LocalLegendParty.prepare(
            session.actor, level, List.of(new LocalLegendParty.Member(8001, 1))),
        "Owned formal actor fixture");
    assertTrue(VersionedPacketHandler.handle(session, 6591, request(1, 8001)));
    assertEquals(0, retcode());
    assertTrue(session.actor.isInBattle());
    var type = session.response.getDescriptorForType();
    var battle = (DynamicMessage) session.response.getField(type.findFieldByName("battle_info"));
    assertEquals(
        4260041, battle.getField(battle.getDescriptorForType().findFieldByName("stage_id")));
    assertEquals(1200, formal.getCurrentHp(session.actor.ordinary));
    assertEquals(3000, formal.getCurrentSp(session.actor.ordinary));
    assertEquals(0, formal.saves);
    assertEquals(List.of(8001), session.actor.ordinary.getAvatars());
    assertEquals(0, session.actor.saves);
    assertTrue(session.actor.getQuestStates().isEmpty());
  }

  @Test
  void closedWindowCannotCreateAnyBattleOrProgress() throws Exception {
    GameplayDefinitions.windows.clear();
    assertTrue(VersionedPacketHandler.handle(session, 6591, request(1, 8001)));
    assertNotEquals(0, retcode());
    assertFalse(session.actor.isInBattle());
    assertTrue(session.actor.getGameplay().getLocalLegendProgress().entries().isEmpty());
  }

  @Test
  void lockedHardDifficultyCannotBeReachedThroughTheDedicatedEntry() throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 6591, request(2, 8001)));
    assertNotEquals(0, retcode());
    assertFalse(session.actor.isInBattle());
  }

  @Test
  void duplicateFormalActorsCannotCreateOrReplaceABattle() throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 6591, request(1, 8001, 8001)));
    assertNotEquals(0, retcode());
    assertFalse(session.actor.isInBattle());
    assertEquals(0, formal.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"70017002", "188180808010", "700118012a03608000", "0000", "80"})
  void duplicateOverflowUnknownAndTruncatedEntryFieldsAreRejected(String hex) throws Exception {
    assertTrue(VersionedPacketHandler.handle(session, 6591, HexFormat.of().parseHex(hex)));
    assertNotEquals(0, retcode());
    assertFalse(session.actor.isInBattle());
    assertEquals(0, session.actor.saves);
  }

  @Test
  void unauthenticatedEntryCannotCreateABattleOrRespond() throws Exception {
    session.setState(SessionState.WAITING_FOR_TOKEN);
    assertTrue(VersionedPacketHandler.handle(session, 6591, request(1, 8001)));
    assertNull(session.response);
    assertFalse(session.actor.isInBattle());
  }

  @ParameterizedTest
  @CsvSource({
    "1,1", "1,2", "1,3", "2,1", "2,2", "2,3", "3,1", "3,2", "3,3", "4,1", "4,2", "4,3", "5,1",
    "5,2", "5,3"
  })
  void everyOriginalGroupAndDifficultyUsesItsOwnActualStagesAcrossAllSevenWorldLevels(
      int group, int difficulty) {
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(
            50070, LocalLegendCatalog.group(group).module(), 1, Long.MAX_VALUE));
    if (difficulty > 1) {
      session
          .actor
          .getGameplay()
          .getLocalLegendProgress()
          .merge(LocalLegendCatalog.level(group, difficulty - 1), 1, 100);
      session.actor.getQuestManager().refreshUnlocks();
    }
    var level = LocalLegendCatalog.level(group, difficulty);
    for (int world = 0; world <= 6; world++) {
      session.actor.world = world;
      var source = level.stage(world);
      assertNotNull(source);
      var entry =
          LocalLegendBattles.start(
              session.actor,
              group,
              difficulty,
              List.of(new LocalLegendParty.Member(8001, 1)),
              java.time.Instant.now());
      assertEquals(Retcode.SUCC, entry.retcode());
      assertSame(source, entry.battle().getStage());
      var wire = entry.battle().toProto();
      assertEquals(world, wire.getWorldLevel());
      assertEquals(source.getId(), wire.getStageId());
      assertEquals(source.getMonsterWaves().size(), wire.getMonsterWaveList().length());
      assertEquals(10000, wire.getBattleAvatarList().get(0).getHp());
      assertEquals(5000, wire.getBattleAvatarList().get(0).getSpBar().getCurSp());
      assertEquals(1, wire.getBattleTargetInfo().length());
      assertEquals(5, wire.getBattleTargetInfo().get(0).getKey());
      var targets = wire.getBattleTargetInfo().get(0).getValue().getBattleTargetList();
      assertEquals(level.goals().size(), targets.length());
      for (int i = 0; i < targets.length(); i++) {
        assertEquals(level.goals().get(i).id(), targets.get(i).getId());
        assertEquals(
            level.goals().get(i).threshold(),
            Integer.toUnsignedLong(targets.get(i).getTotalProgress()));
        assertEquals(0, targets.get(i).getProgress());
      }
      assertSame(
          entry.battle(),
          finish(entry.battle(), BattleEndStatus.BATTLE_END_QUIT, BattleStatistics.newInstance()));
      assertFalse(session.actor.isInBattle());
      assertEquals(1200, formal.getCurrentHp(session.actor.ordinary));
      assertEquals(3000, formal.getCurrentSp(session.actor.ordinary));
      assertEquals(0, formal.saves);
    }
  }

  @Test
  void acceptedBoundWinSavesOnlyItsActualTargetAndPublishesTheOriginalQuestRewardOnce()
      throws Exception {
    var battle = enter();
    var stats = LocalLegendCatalogTest.report(5, 5001804, 1, 0);
    assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN, stats));
    assertFalse(session.actor.isInBattle());
    var history = session.actor.getGameplay().getLocalLegendProgress();
    assertEquals(1, history.mask(LocalLegendCatalog.level(1, 1)));
    assertEquals(0, history.mask(LocalLegendCatalog.level(1, 2)));
    assertTrue(QuestCompletionRecords.completed(session.actor, 6072101));
    assertEquals("LocalLegendDataChangeNotify", session.response.getDescriptorForType().getName());
    var row =
        (DynamicMessage)
            session.response.getField(
                session.response.getDescriptorForType().findFieldByName("level_data"));
    assertEquals(1, row.getField(row.getDescriptorForType().findFieldByName("FFGFINDLMCF")));
    assertEquals(0, session.actor.grants);
    assertEquals(Retcode.SUCC, session.actor.getQuestManager().claim(6072113).retcode());
    assertEquals(Map.of(1, 40, 2, 40000, 213, 4), session.actor.amounts);
    assertEquals(1, session.actor.grants);
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN,
        session.actor.getQuestManager().claim(6072113).retcode());
    assertNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, stats));
    assertEquals(1, session.actor.grants);
    assertEquals(1200, formal.getCurrentHp(session.actor.ordinary));
    assertEquals(3000, formal.getCurrentSp(session.actor.ordinary));
    assertEquals(0, formal.saves);
  }

  @Test
  void easyWinnerExecutesTheOriginalMainTargetWithoutExtraStatistics() throws Exception {
    var battle = enter();
    assertSame(
        battle, finish(battle, BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance()));
    assertEquals(1, session.actor.getGameplay().getLocalLegendProgress().mask(battle.level()));
    assertTrue(QuestCompletionRecords.completed(session.actor, 6072101));
    assertEquals(0, session.actor.grants);
  }

  @Test
  void validNativeClientAchievementsCannotPreventAnAcceptedBoundWin() throws Exception {
    var battle = enter();
    var statistics = LocalLegendCatalogTest.report(6, 30213, 1, 1);
    statistics.addBattleTargetInfo(
        LocalLegendCatalogTest.report(5, 5001804, 1, 1).getBattleTargetInfo().get(0));
    assertSame(battle, finish(battle, BattleEndStatus.BATTLE_END_WIN, statistics));
    assertFalse(session.actor.isInBattle());
    assertEquals(1, session.actor.getGameplay().getLocalLegendProgress().mask(battle.level()));
    assertTrue(QuestCompletionRecords.completed(session.actor, 6072101));
    assertEquals(0, session.actor.grants);
    assertEquals(0, formal.saves);
  }

  @Test
  void sourceEasyWinnerTargetIsIndependentOfTheOtherClientAchievementCategory() throws Exception {
    var battle = enter();
    assertSame(
        battle,
        finish(
            battle, BattleEndStatus.BATTLE_END_WIN, LocalLegendCatalogTest.report(6, 30213, 1, 1)));
    assertFalse(session.actor.isInBattle());
    assertEquals(1, session.actor.getGameplay().getLocalLegendProgress().mask(battle.level()));
    assertTrue(QuestCompletionRecords.completed(session.actor, 6072101));
    assertEquals(0, session.actor.grants);
    assertEquals(0, formal.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void foreignDuplicateAndUnknownTargetReportsCannotConsumeTheActiveBattle(int variant)
      throws Exception {
    var battle = enter();
    var stats =
        switch (variant) {
          case 0 -> LocalLegendCatalogTest.report(5, 5001801, 1, 0);
          case 1 -> LocalLegendCatalogTest.report(5, 5001804, 1, 0, 5001804, 1, 0);
          case 2 -> LocalLegendCatalogTest.report(7, 5001804, 1, 0);
          case 3 -> LocalLegendCatalogTest.report(0, 5001804, 1, 0);
          default ->
              LocalLegendCatalogTest.report(5, 5001804, 1, 0)
                  .addBattleTargetInfo(
                      LocalLegendCatalogTest.report(5, 5001804, 1, 0).getBattleTargetInfo().get(0));
        };
    assertNull(finish(battle, BattleEndStatus.BATTLE_END_WIN, stats));
    assertSame(battle, session.actor.getBattle());
    assertTrue(session.actor.getGameplay().getLocalLegendProgress().entries().isEmpty());
    assertEquals(0, session.actor.saves);
    assertSame(
        battle,
        finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(5, 5001804, 1, 0)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"BATTLE_END_LOSE", "BATTLE_END_QUIT"})
  void nonWinningReportsDoNotPublishTargetsUnderTheExplicitServerEntryPolicy(String result)
      throws Exception {
    var battle = enter();
    assertSame(
        battle,
        finish(
            battle,
            BattleEndStatus.valueOf(result),
            LocalLegendCatalogTest.report(5, 5001804, 1, 0)));
    assertTrue(session.actor.getGameplay().getLocalLegendProgress().entries().isEmpty());
    assertTrue(session.actor.getQuestStates().isEmpty());
    assertEquals(0, session.actor.saves);
    assertEquals(1200, formal.getCurrentHp(session.actor.ordinary));
    assertEquals(3000, formal.getCurrentSp(session.actor.ordinary));
    assertEquals(0, formal.saves);
  }

  @Test
  void anotherEntryCannotReplaceAnActiveBattleOrChangeItsFrozenParty() throws Exception {
    var battle = enter();
    var snapshot = battle.toProto().toByteArray();
    assertTrue(VersionedPacketHandler.handle(session, 6591, request(1, 8001)));
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), retcode());
    assertSame(battle, session.actor.getBattle());
    assertArrayEquals(snapshot, battle.toProto().toByteArray());
    assertThrows(
        UnsupportedOperationException.class, () -> battle.getLineup().getAvatars().clear());
    assertEquals(0, session.actor.saves);
  }

  @Test
  void sourceStageMutationCannotSettleOrRewriteTheFrozenEntry() throws Exception {
    var battle = enter();
    var stage = (StageExcel) battle.getStage();
    var wave = stage.getMonsterWaves().get(0);
    int old = wave.getInt(0);
    var snapshot = battle.toProto().toByteArray();
    wave.set(0, 1002030);
    assertNull(
        finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(5, 5001804, 1, 0)));
    assertSame(battle, session.actor.getBattle());
    assertArrayEquals(snapshot, battle.toProto().toByteArray());
    wave.set(0, old);
    assertSame(
        battle,
        finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(5, 5001804, 1, 0)));
  }

  @Test
  void lostOwnershipAndChangedWorldCannotWriteProgressButDoNotDestroyTheBattle() throws Exception {
    var battle = enter();
    session.actor.getAvatars().getAvatars().remove(8001);
    assertNull(
        finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(5, 5001804, 1, 0)));
    assertSame(battle, session.actor.getBattle());
    session.actor.getAvatars().getAvatars().put(8001, formal);
    session.actor.world = 2;
    assertNull(
        finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(5, 5001804, 1, 0)));
    assertSame(battle, session.actor.getBattle());
    session.actor.world = 1;
    assertSame(
        battle,
        finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(5, 5001804, 1, 0)));
  }

  @Test
  void concurrentSettlementAcceptsOneReportAndProducesOneTargetRecord() throws Exception {
    var battle = enter();
    var start = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var a =
          threads.submit(
              () -> {
                start.await();
                return finish(
                    battle,
                    BattleEndStatus.BATTLE_END_WIN,
                    LocalLegendCatalogTest.report(5, 5001804, 1, 0));
              });
      var b =
          threads.submit(
              () -> {
                start.await();
                return finish(
                    battle,
                    BattleEndStatus.BATTLE_END_WIN,
                    LocalLegendCatalogTest.report(5, 5001804, 1, 0));
              });
      start.countDown();
      assertEquals(
          1,
          (a.get(10, TimeUnit.SECONDS) != null ? 1 : 0)
              + (b.get(10, TimeUnit.SECONDS) != null ? 1 : 0));
    }
    assertEquals(1, session.actor.getGameplay().getLocalLegendProgress().entries().size());
    assertEquals(0, session.actor.grants);
    assertEquals(0, formal.saves);
  }

  @Test
  void progressSaveFailureKeepsOldHistoryAndClosesAutomaticSettlementRetry() throws Exception {
    var battle = enter();
    var before = session.actor.getGameplay().getLocalLegendProgress();
    session.actor.failSave = true;
    assertThrows(
        IllegalStateException.class,
        () -> finish(battle, BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance()));
    assertSame(before, session.actor.getGameplay().getLocalLegendProgress());
    assertTrue(before.entries().isEmpty());
    assertSame(battle, session.actor.getBattle());
    assertTrue(session.actor.getQuestStates().isEmpty());
    assertEquals(0, session.actor.grants);
    session.actor.failSave = false;
    assertNull(
        finish(
            battle,
            BattleEndStatus.BATTLE_END_WIN,
            LocalLegendCatalogTest.report(5, 5001804, 1, 0)));
  }
}
