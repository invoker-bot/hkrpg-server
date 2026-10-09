package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.server.game.*;
import emu.lunarcore.server.packet.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original source-2 event/stages, using an explicit ordinary scene and formal-actor fixture. */
class StageEventDifficulty46Test {
  @org.junit.jupiter.api.io.TempDir Path root;
  final RaidDifficultyAdjustment46Test baseline = new RaidDifficultyAdjustment46Test();
  final MissionFarmingBattleTest.Service service = new MissionFarmingBattleTest.Service();
  final Gson gson = new Gson();
  GameServer shell;
  Session session;
  JsonObject original;
  static final int EVENT = 20003011;
  static final int[] LEVELS = {7, 30, 40, 48, 56, 66, 75};

  class Session extends GameSession {
    DynamicMessage response;
    String responseName;

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    @Override
    public DefaultEntryMissionRaid46Test.WorldActor getPlayer() {
      return baseline.fixture.player;
    }

    @Override
    public GameServer getServer() {
      return shell;
    }

    @Override
    public ProtocolBridge getProtocol() {
      return ProtocolBridge.forVersion("4.6.0");
    }

    @Override
    public void sendClientPacket(String name, byte[] bytes) {
      try {
        responseName = name;
        response = getProtocol().clientSchema().parse(name, bytes);
      } catch (java.io.IOException failure) {
        throw new AssertionError(failure);
      }
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    baseline.root = root;
    baseline.prepare();
    emu.lunarcore.LunarCore.getConfig().protocol.wireVersion = "4.6.0";
    original = baseline.fixture.source.helper.fixture.fixture("stage-event-difficulty-4.6.json");
    var records = original.getAsJsonObject("records");
    Files.writeString(
        root.resolve("ExcelOutput/DifficultyAdjustmentStage.json"),
        records.get("ExcelOutput/DifficultyAdjustmentStage.json").toString());
    for (var raw : records.getAsJsonArray("ExcelOutput/PlaneEvent.json"))
      GameData.getPlaneEventExcelMap().add(gson.fromJson(raw, PlaneEventExcel.class));
    for (var raw : records.getAsJsonArray("ExcelOutput/StageConfig.json")) {
      var row = emu.lunarcore.data.ResourceLoader.getGson().fromJson(raw, StageExcel.class);
      row.onLoad();
      GameData.getStageExcelMap().add(row);
    }
    var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    singleton.setAccessible(true);
    shell = (GameServer) ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(GameServer.class);
    var field = GameServer.class.getDeclaredField("battleService");
    field.setAccessible(true);
    field.set(shell, service);
    session = new Session();
  }

  @AfterEach
  void restore() throws Exception {
    baseline.restore();
  }

  DynamicMessage call(String name, DynamicMessage message) throws Exception {
    session.response = null;
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand(name),
            message == null ? new byte[0] : message.toByteArray()));
    assertEquals(name.replace("CsReq", "ScRsp"), session.responseName);
    return Objects.requireNonNull(session.response);
  }

  DynamicMessage update(int event, int source, int difficulty) throws Exception {
    var type = session.getProtocol().clientSchema().message("DifficultyAdjustmentUpdateDataCsReq");
    return call(
        "DifficultyAdjustmentUpdateDataCsReq",
        DynamicMessage.newBuilder(type)
            .setField(type.findFieldByName("data"), baseline.choice(event, source, difficulty))
            .build());
  }

  Battle enter() throws Exception {
    var type = session.getProtocol().clientSchema().message("SceneEnterStageCsReq");
    var out =
        call(
            "SceneEnterStageCsReq",
            DynamicMessage.newBuilder(type)
                .setField(type.findFieldByName("event_id"), EVENT)
                .build());
    assertEquals(0, RaidDifficultyAdjustment46Test.number(out, "retcode"));
    return Objects.requireNonNull(baseline.fixture.player.getBattle());
  }

  static Stream<Arguments> worlds() {
    return java.util.stream.IntStream.rangeClosed(1, 6)
        .boxed()
        .flatMap(world -> Stream.of(Arguments.of(world, 0), Arguments.of(world, 1)));
  }

  @ParameterizedTest
  @MethodSource("worlds")
  void nativeSelectionUsesTheOriginalEffectiveStageAndWorld(int world, int difficulty)
      throws Exception {
    var player = baseline.fixture.player;
    player.world = world;
    int saves = player.saves;
    assertEquals(0, RaidDifficultyAdjustment46Test.number(update(EVENT, 2, difficulty), "retcode"));
    assertEquals(saves + 1, player.saves);
    var query = call("DifficultyAdjustmentGetDataCsReq", null);
    assertEquals(
        List.of(baseline.choice(EVENT, 2, difficulty)),
        RaidDifficultyAdjustment46Test.field(query, "content_package_list"));
    var battle = enter();
    assertEquals(EVENT * 10 + world - difficulty, battle.getStage().getId());
    assertEquals(LEVELS[world - difficulty], ((StageExcel) battle.getStage()).getLevel());
    assertEquals(world - difficulty, battle.toProto().getWorldLevel());
    var wire =
        (DynamicMessage) RaidDifficultyAdjustment46Test.field(session.response, "battle_info");
    assertEquals(world - difficulty, RaidDifficultyAdjustment46Test.number(wire, "world_level"));
    assertEquals(world, player.getWorldLevel());
    assertEquals(2, player.getCurrentLineup().getMp());
    assertEquals(List.of(8015011), battle.getWaves().getFirst().getMonsters());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void anUnselectedEventKeepsDefaultAndDoesNotManufactureAChoice(int world) throws Exception {
    var player = baseline.fixture.player;
    player.world = world;
    int saves = player.saves;
    var query = call("DifficultyAdjustmentGetDataCsReq", null);
    assertEquals(List.of(), RaidDifficultyAdjustment46Test.field(query, "content_package_list"));
    var battle = enter();
    assertEquals(EVENT * 10 + world, battle.getStage().getId());
    assertEquals(world, battle.toProto().getWorldLevel());
    assertEquals(saves, player.saves);
  }

  @Test
  void identicalSelectionsSaveOnce() throws Exception {
    assertEquals(0, RaidDifficultyAdjustment46Test.number(update(EVENT, 2, 1), "retcode"));
    int saves = baseline.fixture.player.saves;
    assertEquals(0, RaidDifficultyAdjustment46Test.number(update(EVENT, 2, 1), "retcode"));
    assertEquals(saves, baseline.fixture.player.saves);
  }

  @Test
  void sourceOneAndSourceTwoPreferencesCoexistWithoutChangingTheirOwners() throws Exception {
    assertEquals(0, RaidDifficultyAdjustment46Test.number(update(43306, 1, 1), "retcode"));
    assertEquals(0, RaidDifficultyAdjustment46Test.number(update(EVENT, 2, 0), "retcode"));
    assertEquals(
        List.of(baseline.choice(43306, 1, 1), baseline.choice(EVENT, 2, 0)),
        RaidDifficultyAdjustment46Test.field(
            call("DifficultyAdjustmentGetDataCsReq", null), "content_package_list"));
  }

  @ParameterizedTest
  @CsvSource({
    "20003011,1,1",
    "20003011,0,1",
    "20003011,2,2",
    "20101011,2,1",
    "20101012,2,1",
    "0,2,1",
    "-1,2,1"
  })
  void unsupportedOwnersAliasesAndDifficultyCannotCreatePreferences(
      int event, int source, int difficulty) throws Exception {
    int saves = baseline.fixture.player.saves;
    var before = gson.toJson(baseline.fixture.player.getGameplay());
    assertNotEquals(
        0, RaidDifficultyAdjustment46Test.number(update(event, source, difficulty), "retcode"));
    assertEquals(saves, baseline.fixture.player.saves);
    assertEquals(before, gson.toJson(baseline.fixture.player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 7})
  void unverifiedWorldsCannotUpdatePreferences(int world) throws Exception {
    baseline.fixture.player.world = world;
    int saves = baseline.fixture.player.saves;
    assertNotEquals(0, RaidDifficultyAdjustment46Test.number(update(EVENT, 2, 1), "retcode"));
    assertEquals(saves, baseline.fixture.player.saves);
  }

  @Test
  void anExistingEasyPreferenceDoesNotUnderflowWorldZero() throws Exception {
    assertEquals(0, RaidDifficultyAdjustment46Test.number(update(EVENT, 2, 1), "retcode"));
    baseline.fixture.player.world = 0;
    var battle = enter();
    assertEquals(EVENT * 10, battle.getStage().getId());
    assertEquals(0, battle.toProto().getWorldLevel());
    assertTrue(((StageEventDifficultyBattle) battle).matches(baseline.fixture.player));
  }

  void easy() throws Exception {
    assertEquals(0, RaidDifficultyAdjustment46Test.number(update(EVENT, 2, 1), "retcode"));
  }

  emu.lunarcore.game.battle.Battle finish(
      Battle battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus end) {
    return service.finishBattle(
        baseline.fixture.player,
        battle.getId(),
        battle.getStage().getId(),
        end,
        emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance());
  }

  @Test
  void anActiveBattleRejectsMenuChangesAndCanQuitWithItsOriginalScope() throws Exception {
    easy();
    var battle = enter();
    int saves = baseline.fixture.player.saves;
    assertNotEquals(0, RaidDifficultyAdjustment46Test.number(update(EVENT, 2, 0), "retcode"));
    assertSame(battle, baseline.fixture.player.getBattle());
    assertEquals(saves, baseline.fixture.player.saves);
    assertSame(
        battle,
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_QUIT));
    assertNull(baseline.fixture.player.getBattle());
  }

  void settlementRejected(Battle battle) {
    int saves = baseline.fixture.player.saves;
    assertNull(
        finish(
            battle, emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN));
    assertSame(battle, baseline.fixture.player.getBattle());
    assertFalse(battle.isSettling());
    assertEquals(saves, baseline.fixture.player.saves);
  }

  @Test
  void actualWorldDriftRejectsSettlementBeforeAnyEffect() throws Exception {
    easy();
    var battle = enter();
    baseline.fixture.player.world = 2;
    settlementRejected(battle);
  }

  @ParameterizedTest
  @ValueSource(strings = {"entryId", "planeId", "floorId"})
  void changingPlayerLocationFieldsWithoutReplacingTheSceneRejectsSettlement(String name)
      throws Exception {
    easy();
    var battle = enter();
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField(name);
    field.setAccessible(true);
    field.setInt(baseline.fixture.player, field.getInt(baseline.fixture.player) + 1);
    settlementRejected(battle);
  }

  @Test
  void anotherSceneDimensionCannotCreateAnOrdinaryPreference() throws Exception {
    var player = baseline.fixture.player;
    MissionTrialProvisioning46Test.set(
        player,
        "scene",
        new emu.lunarcore.game.scene.Scene(
            player, GameData.getMazePlaneExcelMap().get(player.getPlaneId()), player.getFloorId()) {
          @Override
          public int getDimensionId() {
            return 1;
          }
        });
    int saves = player.saves;
    assertNotEquals(0, RaidDifficultyAdjustment46Test.number(update(EVENT, 2, 1), "retcode"));
    assertEquals(saves, player.saves);
  }

  @Test
  void aChangedCompleteDifficultyRowRejectsSettlement() throws Exception {
    easy();
    var battle = enter();
    var rows =
        original
            .getAsJsonObject("records")
            .getAsJsonArray("ExcelOutput/DifficultyAdjustmentStage.json")
            .deepCopy();
    rows.get(0).getAsJsonObject().addProperty("unverifiedFutureRule", 1);
    Files.writeString(root.resolve("ExcelOutput/DifficultyAdjustmentStage.json"), rows.toString());
    settlementRejected(battle);
  }

  @Test
  void changingBothConsumedStageWavesAndBattleWavesStillRejectsSettlement() throws Exception {
    easy();
    var battle = enter();
    battle.getStage().getMonsterWaves().getFirst().set(0, 8015012);
    battle.getWaves().getFirst().getMonsters().set(0, 8015012);
    settlementRejected(battle);
  }

  @Test
  void theInternalScriptedEventEntryUsesTheSamePlan() throws Exception {
    easy();
    var battle = Objects.requireNonNull(service.startBattle(baseline.fixture.player, EVENT, 1));
    assertEquals(EVENT * 10, battle.getStage().getId());
    assertEquals(0, battle.toProto().getWorldLevel());
  }

  @Test
  void inheritedProtocolKeepsItsOriginalWorldWithoutApplyingExperimentalPreferences()
      throws Exception {
    easy();
    emu.lunarcore.LunarCore.getConfig().protocol.wireVersion = "4.2.0";
    var battle = Objects.requireNonNull(service.startEventBattle(baseline.fixture.player, EVENT));
    assertEquals(EVENT * 10 + 1, battle.getStage().getId());
    assertEquals(1, battle.toProto().getWorldLevel());
    assertFalse(battle instanceof StageEventDifficultyBattle);
  }

  @Test
  void aNullSavedCollectionIsReportedAsDataErrorWithoutManufacturingDefaults() throws Exception {
    var settings = baseline.fixture.player.getGameplay().getStageEventDifficultySettings();
    var field = StageEventDifficultySettings.class.getDeclaredField("choices");
    field.setAccessible(true);
    field.set(settings, null);
    int saves = baseline.fixture.player.saves;
    var out = call("DifficultyAdjustmentGetDataCsReq", null);
    assertEquals(
        Retcode.PLAYER_DATA_ERROR.getVal(), RaidDifficultyAdjustment46Test.number(out, "retcode"));
    assertEquals(List.of(), RaidDifficultyAdjustment46Test.field(out, "content_package_list"));
    assertEquals(saves, baseline.fixture.player.saves);
    assertNull(service.startEventBattle(baseline.fixture.player, EVENT));
  }

  static Stream<Arguments> restores() {
    return Stream.of(1, 6)
        .flatMap(
            world ->
                Stream.of(Arguments.of(world, -1), Arguments.of(world, 0), Arguments.of(world, 1)));
  }

  @ParameterizedTest
  @MethodSource("restores")
  void actualPlayerMorphiaRestoresEachPreferenceTwice(int world, int preference) throws Exception {
    var player = baseline.fixture.player;
    player.world = world;
    if (preference >= 0)
      assertEquals(
          0, RaidDifficultyAdjustment46Test.number(update(EVENT, 2, preference), "retcode"));
    var expected = gson.toJson(player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "stage-event-difficulty-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      MissionTrialProvisioning46Test.set(document, "uid", player.getUid());
      MissionTrialProvisioning46Test.set(document, "worldLevel", world);
      MissionTrialProvisioning46Test.set(document, "gameplay", player.getGameplay());
      store.save(document);
      for (int attempt = 0; attempt < 2; attempt++) {
        var saved =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(expected, gson.toJson(saved.getGameplay()));
        var copy = new DefaultEntryMissionRaid46Test.WorldActor(baseline.fixture.source.helper);
        copy.world = world;
        MissionTrialProvisioning46Test.set(copy, "gameplay", saved.getGameplay());
        var formal =
            new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(199986));
        formal.setOwner(copy);
        copy.getAvatars().getAvatars().put(199986, formal);
        copy.getCurrentLineup().getAvatars().add(199986);
        copy.getCurrentLineup().setMp(2);
        baseline.fixture.source.helper.position(copy, player.getEntryId());
        var battle = Objects.requireNonNull(service.startEventBattle(copy, EVENT));
        assertEquals(EVENT * 10 + world - Math.max(0, preference), battle.getStage().getId());
        assertEquals(world - Math.max(0, preference), battle.toProto().getWorldLevel());
        assertEquals(expected, gson.toJson(copy.getGameplay()));
        assertEquals(0, copy.saves);
        store.save(document);
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void aFailedPlayerSaveRestoresThePreviousSelection() throws Exception {
    easy();
    var previous = baseline.fixture.player;
    var failed =
        new DefaultEntryMissionRaid46Test.WorldActor(baseline.fixture.source.helper) {
          @Override
          public void save() {
            throw new IllegalStateException("fixture save failed");
          }
        };
    failed.world = 1;
    MissionTrialProvisioning46Test.set(failed, "gameplay", previous.getGameplay());
    baseline.fixture.source.helper.position(failed, previous.getEntryId());
    baseline.fixture.player = failed;
    var before = gson.toJson(failed.getGameplay());
    assertEquals(
        Retcode.SYSTEM_BUSY.getVal(),
        RaidDifficultyAdjustment46Test.number(update(EVENT, 2, 0), "retcode"));
    assertEquals(before, gson.toJson(failed.getGameplay()));
    assertEquals(
        1,
        failed
            .getGameplay()
            .getStageEventDifficultySettings()
            .snapshot()
            .getFirst()
            .getDifficulty());
  }
}
