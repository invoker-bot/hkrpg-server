package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.SessionState;
import java.nio.file.Path;
import java.util.*;
import org.hkrpg.server.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Original 43306 entrance; gameplay acceptance and scene arrival use the existing explicit fixture.
 */
class RaidDifficultyAdjustment46Test {
  @TempDir Path root;
  final DefaultEntryMissionRaid46Test fixture = new DefaultEntryMissionRaid46Test();
  Session session;
  final List<emu.lunarcore.data.excel.PlaneEventExcel> oldEvents = new ArrayList<>();
  final List<emu.lunarcore.data.excel.StageExcel> oldStages = new ArrayList<>();

  class Session extends GameSession {
    DynamicMessage response;
    String responseName;

    Session() {
      super(null);
      setState(SessionState.ACTIVE);
    }

    @Override
    public DefaultEntryMissionRaid46Test.WorldActor getPlayer() {
      return fixture.player;
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

  static Object field(DynamicMessage message, String name) {
    return message.getField(
        Objects.requireNonNull(message.getDescriptorForType().findFieldByName(name)));
  }

  static int number(DynamicMessage message, String name) {
    return (Integer) field(message, name);
  }

  @BeforeEach
  void prepare() throws Exception {
    fixture.root = root;
    fixture.prepare();
    fixture.player.world = 1;
    session = new Session();
    oldEvents.addAll(emu.lunarcore.data.GameData.getPlaneEventExcelMap().values());
    oldStages.addAll(emu.lunarcore.data.GameData.getStageExcelMap().values());
    var records =
        com.google.gson.JsonParser.parseString(
                new String(
                    Objects.requireNonNull(
                            getClass()
                                .getResourceAsStream(
                                    "/fixtures/raid-difficulty-adjustment-4.6.json"))
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject()
            .getAsJsonObject("records");
    for (var raw : records.getAsJsonArray("ExcelOutput/PlaneEvent.json"))
      emu.lunarcore.data.GameData.getPlaneEventExcelMap()
          .add(
              fixture.source.helper.gson.fromJson(
                  raw, emu.lunarcore.data.excel.PlaneEventExcel.class));
    for (var raw : records.getAsJsonArray("ExcelOutput/StageConfig.json")) {
      var row =
          emu.lunarcore.data.ResourceLoader.getGson()
              .fromJson(raw, emu.lunarcore.data.excel.StageExcel.class);
      row.onLoad();
      emu.lunarcore.data.GameData.getStageExcelMap().add(row);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/SpecialAvatar.json"))
      emu.lunarcore.data.GameData.getSpecialAvatarExcelMap().add(
          fixture.source.helper.gson.fromJson(raw, emu.lunarcore.data.excel.SpecialAvatarExcel.class));
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
    emu.lunarcore.data.GameData.getPlaneEventExcelMap().values().clear();
    oldEvents.forEach(emu.lunarcore.data.GameData.getPlaneEventExcelMap()::add);
    emu.lunarcore.data.GameData.getStageExcelMap().values().clear();
    oldStages.forEach(emu.lunarcore.data.GameData.getStageExcelMap()::add);
  }

  DynamicMessage choice(int id, int source, int difficulty) {
    var schema = session.getProtocol().clientSchema();
    var b = DynamicMessage.newBuilder(schema.message("AMKBFMCOIHK"));
    b.setField(b.getDescriptorForType().findFieldByName("id"), id);
    for (var item : Map.of("source_type", source, "ANJMFPKJHOH", difficulty).entrySet()) {
      var f = b.getDescriptorForType().findFieldByName(item.getKey());
      b.setField(f, f.getEnumType().findValueByNumberCreatingIfUnknown(item.getValue()));
    }
    return b.build();
  }

  byte[] request(int id, int source, int difficulty) {
    var b =
        DynamicMessage.newBuilder(
            session.getProtocol().clientSchema().message("DifficultyAdjustmentUpdateDataCsReq"));
    b.setField(b.getDescriptorForType().findFieldByName("data"), choice(id, source, difficulty));
    return b.build().toByteArray();
  }

  DynamicMessage call(String name, byte[] bytes) throws Exception {
    session.response = null;
    assertTrue(
        VersionedPacketHandler.handle(session, session.getProtocol().clientCommand(name), bytes),
        "Native difficulty command must be routed");
    assertEquals(name.replace("CsReq", "ScRsp"), session.responseName);
    assertNotNull(session.response);
    return session.response;
  }

  DynamicMessage update(int id, int source, int difficulty) throws Exception {
    return call("DifficultyAdjustmentUpdateDataCsReq", request(id, source, difficulty));
  }

  @Test
  void queryReportsOnlySavedChoicesWithoutManufacturingDefaults() throws Exception {
    int saves = fixture.player.saves;
    var out = call("DifficultyAdjustmentGetDataCsReq", new byte[0]);
    assertEquals(0, number(out, "retcode"));
    assertEquals(List.of(), field(out, "content_package_list"));
    assertEquals(saves, fixture.player.saves);
  }

  @Test
  void easyChoiceIsSavedAndReturnedThroughExactNativeFields() throws Exception {
    int saves = fixture.player.saves;
    var out = update(43306, 1, 1);
    assertEquals(0, number(out, "retcode"));
    assertEquals(choice(43306, 1, 1), field(out, "data"));
    assertEquals(List.of(choice(43306, 1, 1)), field(out, "content_package_list"));
    assertEquals(saves + 1, fixture.player.saves);
    var queried = call("DifficultyAdjustmentGetDataCsReq", new byte[0]);
    assertEquals(List.of(choice(43306, 1, 1)), field(queried, "content_package_list"));
  }

  @Test
  void repeatedEasyChoiceDoesNotSaveAgain() throws Exception {
    assertEquals(0, number(update(43306, 1, 1), "retcode"));
    int saves = fixture.player.saves;
    assertEquals(0, number(update(43306, 1, 1), "retcode"));
    assertEquals(saves, fixture.player.saves);
  }

  @Test
  void explicitDefaultReplacesTheSavedEasyChoice() throws Exception {
    assertEquals(0, number(update(43306, 1, 1), "retcode"));
    assertEquals(0, number(update(43306, 1, 0), "retcode"));
    assertEquals(
        List.of(choice(43306, 1, 0)),
        field(call("DifficultyAdjustmentGetDataCsReq", new byte[0]), "content_package_list"));
  }

  @ParameterizedTest
  @CsvSource({"43306,0,1", "43306,2,1", "43306,1,2", "43315,1,1", "0,1,1", "-1,1,1"})
  void unsupportedOwnerIdOrDifficultyIsRejectedWithoutSaving(int id, int source, int difficulty)
      throws Exception {
    var before = fixture.source.helper.gson.toJson(fixture.player.getGameplay());
    int saves = fixture.player.saves;
    assertNotEquals(0, number(update(id, source, difficulty), "retcode"));
    assertEquals(before, fixture.source.helper.gson.toJson(fixture.player.getGameplay()));
    assertEquals(saves, fixture.player.saves);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 7})
  void unsupportedPlayerWorldCannotSetAnUnusableChoice(int world) throws Exception {
    fixture.player.world = world;
    int saves = fixture.player.saves;
    assertNotEquals(0, number(update(43306, 1, 1), "retcode"));
    assertEquals(saves, fixture.player.saves);
  }

  @Test
  void malformedOrAbsentChildIsRejectedWithoutSaving() throws Exception {
    int saves = fixture.player.saves;
    for (var bytes :
        List.of(new byte[0], new byte[] {16, 1}, new byte[] {18, 1, 64}, new byte[] {18, 0, 18, 0}))
      assertNotEquals(0, number(call("DifficultyAdjustmentUpdateDataCsReq", bytes), "retcode"));
    assertEquals(saves, fixture.player.saves);
  }

  @Test
  void malformedEmptyQueryIsRejected() throws Exception {
    assertNotEquals(
        0, number(call("DifficultyAdjustmentGetDataCsReq", new byte[] {8, 1}), "retcode"));
  }

  void easyEntry() throws Exception {
    assertEquals(0, number(update(43306, 1, 1), "retcode"));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        new MissionRaid(fixture.player).enter(43306, 0, 0, 0, List.of()));
    assertEquals(1, fixture.player.getWorldLevel());
    assertEquals(43306001, fixture.player.getEntryId());
  }

  @Test
  void easySelectionAllowsTheOriginalWorldZeroResourceAtActualWorldOne() throws Exception {
    easyEntry();
    assertTrue(new MissionRaid(fixture.player).active());
    assertTrue(new MissionRaid(fixture.player).owns(1032201));
    assertEquals(2, fixture.player.getCurrentLineup().getMp());
  }

  @Test
  void activeRaidCannotChangeItsDifficulty() throws Exception {
    easyEntry();
    var before = fixture.source.helper.gson.toJson(fixture.player.getGameplay());
    int saves = fixture.player.saves;
    assertNotEquals(0, number(update(43306, 1, 0), "retcode"));
    assertEquals(before, fixture.source.helper.gson.toJson(fixture.player.getGameplay()));
    assertEquals(saves, fixture.player.saves);
  }

  @Test
  void pausedRunKeepsItsFrozenDifficultyWhenThePreferenceChanges() throws Exception {
    easyEntry();
    var run = new MissionRaid(fixture.player).current();
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        new MissionRaid(fixture.player).leave(43306, false));
    assertEquals(2000101, fixture.player.getEntryId());
    assertEquals(0, number(update(43306, 1, 0), "retcode"));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        new MissionRaid(fixture.player).enter(43306, 0, 0, 0, List.of()));
    assertSame(run, new MissionRaid(fixture.player).current());
    assertTrue(new MissionRaid(fixture.player).active());
    assertEquals(1, fixture.player.getWorldLevel());
  }

  @Test
  void laterEasyChoiceCannotRewriteAnOldWorldZeroRun() throws Exception {
    fixture.player.world = 0;
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        new MissionRaid(fixture.player).enter(43306, 0, 0, 0, List.of()));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        new MissionRaid(fixture.player).leave(43306, false));
    fixture.player.world = 1;
    assertEquals(0, number(update(43306, 1, 1), "retcode"));
    int saves = fixture.player.saves;
    assertNotEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        new MissionRaid(fixture.player).enter(43306, 0, 0, 0, List.of()));
    assertEquals(saves, fixture.player.saves);
    assertEquals(2000101, fixture.player.getEntryId());
  }

  @Test
  void changedActualWorldInvalidatesTheOwnedRunWithoutChangingItsSnapshot() throws Exception {
    easyEntry();
    var before = fixture.source.helper.gson.toJson(fixture.player.getGameplay());
    fixture.player.world = 2;
    assertFalse(new MissionRaid(fixture.player).active());
    assertFalse(new MissionRaid(fixture.player).owns(1032201));
    assertEquals(before, fixture.source.helper.gson.toJson(fixture.player.getGameplay()));
  }

  @Test
  void changedOriginalUnmappedFieldInvalidatesTheSavedChoice() throws Exception {
    assertEquals(0, number(update(43306, 1, 1), "retcode"));
    var file = root.resolve("ExcelOutput/RaidConfig.json");
    var rows =
        com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file))
            .getAsJsonArray();
    rows.get(0).getAsJsonObject().addProperty("AutoObtainDamageType", false);
    java.nio.file.Files.writeString(file, rows.toString());
    int saves = fixture.player.saves;
    assertNotEquals(0, number(call("DifficultyAdjustmentGetDataCsReq", new byte[0]), "retcode"));
    assertNotEquals(0, number(update(43306, 1, 0), "retcode"));
    assertEquals(saves, fixture.player.saves);
  }

  emu.lunarcore.game.battle.Battle battle() {
    return new MissionFarmingBattleTest.Service().startEventBattle(fixture.player, 20321050);
  }

  @Test
  void actualEventBattleUsesTheEasyStageAndWireWorldRatherThanTheFormalWorld() throws Exception {
    easyEntry();
    var battle = Objects.requireNonNull(battle());
    assertEquals(203210500, battle.getStage().getId());
    assertEquals(24, ((emu.lunarcore.data.excel.StageExcel) battle.getStage()).getLevel());
    assertEquals(0, battle.toProto().getWorldLevel());
    assertEquals(1, fixture.player.getWorldLevel());
  }

  @Test
  void changingFormalWorldDuringBattleRejectsSettlement() throws Exception {
    easyEntry();
    var battle = Objects.requireNonNull(battle());
    fixture.player.world = 2;
    assertNull(
        new MissionFarmingBattleTest.Service()
            .finishBattle(
                fixture.player,
                battle.getId(),
                battle.getStage().getId(),
                emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
                emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance()));
    assertSame(battle, fixture.player.getBattle());
    assertFalse(battle.isSettling());
  }

  @Test
  void changingOriginalDifficultySourceDuringBattleRejectsSettlement() throws Exception {
    easyEntry();
    var battle = Objects.requireNonNull(battle());
    var file = root.resolve("ExcelOutput/RaidConfig.json");
    var rows =
        com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file))
            .getAsJsonArray();
    rows.get(0).getAsJsonObject().addProperty("AutoObtainDamageType", false);
    java.nio.file.Files.writeString(file, rows.toString());
    assertNull(
        new MissionFarmingBattleTest.Service()
            .finishBattle(
                fixture.player,
                battle.getId(),
                battle.getStage().getId(),
                emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
                emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance()));
    assertSame(battle, fixture.player.getBattle());
    assertFalse(battle.isSettling());
  }

  @Test
  void sourceNpcConstructionUsesTheSameEasyWorld() throws Exception {
    easyEntry();
    // Placement and NPC are explicit unit fixtures; the event is the original 43306 event.
    var gson = fixture.source.helper.gson;
    var excel =
        gson.fromJson(
            "{\"ID\":1999988,\"Rank\":\"Minion\"}", emu.lunarcore.data.excel.NpcMonsterExcel.class);
    var map = emu.lunarcore.data.GameData.getNpcMonsterExcelMap();
    var old = map.get(1999988);
    map.add(excel);
    try {
      var group =
          gson.fromJson(
              "{\"GroupName\":\"DifficultyUnit\"}", emu.lunarcore.data.config.GroupInfo.class);
      group.setId(9988);
      var info =
          gson.fromJson(
              "{\"ID\":9988,\"NPCMonsterID\":1999988,\"EventID\":20321050}",
              emu.lunarcore.data.config.MonsterInfo.class);
      var monster =
          new emu.lunarcore.game.scene.SceneEntityLoader()
              .loadMonster(fixture.player.getScene(), group, info);
      assertNotNull(monster);
      assertEquals(0, monster.getWorldLevel());
      assertEquals(203210500, monster.getStageId());
    } finally {
      map.values().removeIf(row -> row.getId() == 1999988);
      if (old != null) map.add(old);
    }
  }

  @Test
  void damagedFrozenSelectionCannotFallBackToTheFormalWorldBattle() throws Exception {
    easyEntry();
    var field = GameplayState.MissionRaidRun.class.getDeclaredField("difficultySnapshot");
    field.setAccessible(true);
    field.set(new MissionRaid(fixture.player).current(), null);
    assertFalse(new MissionRaid(fixture.player).active());
    assertNull(battle());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
  void actualPlayerMorphiaRestoresTheFrozenWorldAndPausedPreferenceTwice(int mode)
      throws Exception {
    easyEntry();
    if (mode > 0)
      assertEquals(
          emu.lunarcore.server.packet.Retcode.SUCC,
          new MissionRaid(fixture.player).leave(43306, false));
    if (mode == 2) assertEquals(0, number(update(43306, 1, 0), "retcode"));
    var expected = fixture.source.helper.gson.toJson(fixture.player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "raid-difficulty-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      MissionTrialProvisioning46Test.set(document, "uid", fixture.player.getUid());
      MissionTrialProvisioning46Test.set(document, "gameplay", fixture.player.getGameplay());
      MissionTrialProvisioning46Test.set(document, "worldLevel", 1);
      store.save(document);
      for (int attempt = 0; attempt < 2; attempt++) {
        var saved =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(1, saved.getWorldLevel());
        assertEquals(expected, fixture.source.helper.gson.toJson(saved.getGameplay()));
        var copy = new DefaultEntryMissionRaid46Test.WorldActor(fixture.source.helper);
        copy.world = 1;
        MissionTrialProvisioning46Test.set(copy, "gameplay", saved.getGameplay());
        var formal =
            new emu.lunarcore.game.avatar.GameAvatar(
                emu.lunarcore.data.GameData.getAvatarExcelMap().get(199986));
        formal.setOwner(copy);
        copy.getAvatars().getAvatars().put(199986, formal);
        copy.getCurrentLineup().getAvatars().add(199986);
        copy.getCurrentLineup().setMp(2);
        fixture.source.helper.position(copy, fixture.player.getEntryId());
        assertEquals(mode == 0, new MissionRaid(copy).restoreOnLogin());
        assertEquals(expected, fixture.source.helper.gson.toJson(copy.getGameplay()));
        if (mode > 0)
          assertEquals(
              emu.lunarcore.server.packet.Retcode.SUCC,
              new MissionRaid(copy).enter(43306, 0, 0, 0, List.of()));
        assertTrue(new MissionRaid(copy).active());
        assertEquals(
            0,
            Objects.requireNonNull(
                    new MissionFarmingBattleTest.Service().startEventBattle(copy, 20321050))
                .toProto()
                .getWorldLevel());
        store.save(document);
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void failedPreferenceSaveRestoresThePreviousChoiceAndAllowsAnExplicitRetry() throws Exception {
    class FailedActor extends DefaultEntryMissionRaid46Test.WorldActor {
      boolean failing = true;

      FailedActor() {
          super(RaidDifficultyAdjustment46Test.this.fixture.source.helper);
        world = 1;
      }

      @Override
      public void save() {
        if (failing) throw new IllegalStateException("Isolated save rejection");
        super.save();
      }
    }
    var actor = new FailedActor();
    fixture.player = actor;
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(),
        number(update(43306, 1, 1), "retcode"));
    assertEquals(
        List.of(),
        field(call("DifficultyAdjustmentGetDataCsReq", new byte[0]), "content_package_list"));
    actor.failing = false;
    assertEquals(0, number(update(43306, 1, 1), "retcode"));
    actor.failing = true;
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SYSTEM_BUSY.getVal(),
        number(update(43306, 1, 0), "retcode"));
    assertEquals(
        List.of(choice(43306, 1, 1)),
        field(call("DifficultyAdjustmentGetDataCsReq", new byte[0]), "content_package_list"));
  }

  @Test
  void concurrentIdenticalPreferencesSaveOnlyOnce() throws Exception {
    int saves = fixture.player.saves;
    var state = fixture.player.getGameplay().getRaidDifficultySettings();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> state.update(fixture.player, 43306, 1, 1));
      var second = executor.submit(() -> state.update(fixture.player, 43306, 1, 1));
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, first.get());
      assertEquals(emu.lunarcore.server.packet.Retcode.SUCC, second.get());
    }
    assertEquals(saves + 1, fixture.player.saves);
  }

  @Test
  void originalSecondStageWorldOneTrialAndPauseResumeRetainTheFrozenEasyWorld() throws Exception {
    easyEntry();
    assertEquals(1, fixture.player.getTrialManager().specialRow(1021304).getWorldLevel());
    var complete = DefaultEntryMissionRaid46Test.class.getDeclaredMethod("completeStage");
    complete.setAccessible(true);
    complete.invoke(fixture);
    assertEquals(43306002, fixture.player.getEntryId());
    var talk = new emu.lunarcore.game.quest.QuestState(103190102);
    talk.advance(1, 1, 123);
    fixture.player.getGameplay().getSubMissions().put(103190102, talk);
    fixture.player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(fixture.player, 103190112));
    assertEquals(List.of(1021304), fixture.player.getCurrentLineup().getAvatars());
    fixture.player.getCurrentLineup().setMp(1);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        new MissionRaid(fixture.player).leave(43306, false));
    assertEquals(0, number(update(43306, 1, 0), "retcode"));
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        new MissionRaid(fixture.player).enter(43306, 0, 0, 0, List.of()));
    assertEquals(1, fixture.player.getCurrentLineup().getMp());
    assertEquals(2, fixture.player.getLineupManager().getLineups()[0].getMp());
    assertEquals(0, Objects.requireNonNull(battle()).toProto().getWorldLevel());
  }
}
