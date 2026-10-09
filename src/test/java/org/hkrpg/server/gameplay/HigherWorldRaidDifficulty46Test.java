package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original world tiers and presets; parent/early outcomes and NPC placement are fixtures. */
class HigherWorldRaidDifficulty46Test {
  @TempDir Path root;
  final RaidDifficultyAdjustment46Test source = new RaidDifficultyAdjustment46Test();
  static final int[] STAGE_LEVELS = {24, 32, 40, 48, 56, 66, 82};
  static final int[] TRIAL_LEVELS = {40, 40, 50, 58, 68, 78, 80};

  static Stream<Arguments> scenarios() {
    return IntStream.rangeClosed(2, 6)
        .boxed()
        .flatMap(world -> IntStream.of(-1, 0, 1).mapToObj(pref -> Arguments.of(world, pref)));
  }

  static Stream<Arguments> restores() {
    return IntStream.of(2, 6)
        .boxed()
        .flatMap(
            world ->
                IntStream.of(-1, 0, 1)
                    .boxed()
                    .flatMap(
                        pref ->
                            IntStream.of(0, 1).mapToObj(mode -> Arguments.of(world, pref, mode))));
  }

  static Stream<Arguments> npcs() {
    return IntStream.rangeClosed(2, 6)
        .boxed()
        .flatMap(world -> IntStream.of(0, 1).mapToObj(pref -> Arguments.of(world, pref)));
  }

  @BeforeEach
  void prepare() throws Exception {
    source.root = root;
    source.prepare();
    var records =
        com.google.gson.JsonParser.parseString(
                new String(
                    Objects.requireNonNull(
                            getClass()
                                .getResourceAsStream("/fixtures/raid-difficulty-worlds-4.6.json"))
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject()
            .getAsJsonObject("records");
    for (var raw : records.getAsJsonArray("ExcelOutput/PlaneEvent.json"))
      GameData.getPlaneEventExcelMap()
          .add(
              source.fixture.source.helper.gson.fromJson(
                  raw, emu.lunarcore.data.excel.PlaneEventExcel.class));
    for (var raw : records.getAsJsonArray("ExcelOutput/StageConfig.json")) {
      var row = emu.lunarcore.data.ResourceLoader.getGson().fromJson(raw, StageExcel.class);
      row.onLoad();
      GameData.getStageExcelMap().add(row);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/SpecialAvatar.json"))
      GameData.getSpecialAvatarExcelMap()
          .add(
              source.fixture.source.helper.gson.fromJson(
                  raw, emu.lunarcore.data.excel.SpecialAvatarExcel.class));
    for (var entry : records.entrySet())
      if (entry.getKey().startsWith("Config/BattleLineupPage/SkillTreePointPreset/")) {
        var path = root.resolve(entry.getKey());
        Files.createDirectories(path.getParent());
        Files.writeString(path, entry.getValue().getAsJsonArray().get(0).toString());
      }
  }

  @AfterEach
  void restore() throws Exception {
    source.restore();
  }

  DefaultEntryMissionRaid46Test.WorldActor player() {
    return source.fixture.player;
  }

  MissionRaid raid() {
    return new MissionRaid(player());
  }

  void enter(int world, int preference) throws Exception {
    player().world = world;
    if (preference >= 0)
      assertEquals(
          0, RaidDifficultyAdjustment46Test.number(source.update(43306, 1, preference), "retcode"));
    else assertTrue(player().getGameplay().getRaidDifficultySettings().snapshot().isEmpty());
    assertEquals(Retcode.SUCC, raid().enter(43306, 0, 0, 0, List.of()));
    assertEquals(world, player().getWorldLevel());
  }

  emu.lunarcore.game.battle.Battle battle() {
    return Objects.requireNonNull(
        new MissionFarmingBattleTest.Service().startEventBattle(player(), 20321050));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  void eachTierFreezesAndUsesItsOriginalEventAndWireWorld(int world, int preference)
      throws Exception {
    enter(world, preference);
    int selected = Math.max(0, preference), effective = world - selected;
    var frozen = raid().current().getDifficultySnapshot();
    assertEquals(world, frozen.getActualWorld());
    assertEquals(effective, frozen.getEffectiveWorld());
    assertEquals(selected, frozen.getDifficulty());
    assertEquals(
        preference >= 0 ? 1 : 0,
        player().getGameplay().getRaidDifficultySettings().snapshot().size());
    var battle = battle();
    assertEquals(203210500 + effective, battle.getStage().getId());
    assertEquals(STAGE_LEVELS[effective], ((StageExcel) battle.getStage()).getLevel());
    assertEquals(effective, battle.toProto().getWorldLevel());
    assertSame(
        battle,
        new MissionFarmingBattleTest.Service()
            .finishBattle(
                player(),
                battle.getId(),
                battle.getStage().getId(),
                BattleEndStatus.BATTLE_END_QUIT,
                BattleStatistics.newInstance()));
    assertNull(player().getBattle());
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  void actualTrialTierAndConsumedPointSurvivePauseAndOppositePreference(int world, int preference)
      throws Exception {
    enter(world, preference);
    var run = raid().current();
    var complete = DefaultEntryMissionRaid46Test.class.getDeclaredMethod("completeStage");
    complete.setAccessible(true);
    complete.invoke(source.fixture);
    var talk = new QuestState(103190102);
    talk.advance(1, 1, 123);
    player().getGameplay().getSubMissions().put(103190102, talk);
    player().getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(player(), 103190112));
    assertEquals(world, player().getTrialManager().specialRow(1021304).getWorldLevel());
    assertEquals(TRIAL_LEVELS[world], player().getAvatarById(1021304).getLevel());
    player().getCurrentLineup().setMp(1);
    assertEquals(Retcode.SUCC, raid().leave(43306, false));
    assertEquals(
        0,
        RaidDifficultyAdjustment46Test.number(
            source.update(43306, 1, 1 - Math.max(0, preference)), "retcode"));
    assertEquals(Retcode.SUCC, raid().enter(43306, 0, 0, 0, List.of()));
    assertSame(run, raid().current());
    assertEquals(Math.max(0, preference), run.getDifficultySnapshot().getDifficulty());
    assertEquals(List.of(1021304), player().getCurrentLineup().getAvatars());
    assertEquals(1, player().getCurrentLineup().getMp());
    assertEquals(2, player().getLineupManager().getLineups()[0].getMp());
    assertEquals(world - Math.max(0, preference), battle().toProto().getWorldLevel());
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  void changedActualWorldStillRejectsHigherTierSettlement(int world, int preference)
      throws Exception {
    enter(world, preference);
    var battle = battle();
    player().world = world + 1;
    assertNull(
        new MissionFarmingBattleTest.Service()
            .finishBattle(
                player(),
                battle.getId(),
                battle.getStage().getId(),
                BattleEndStatus.BATTLE_END_WIN,
                BattleStatistics.newInstance()));
    assertSame(battle, player().getBattle());
    assertFalse(battle.isSettling());
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4, 5, 6})
  void higherTierMenuCannotRewriteAnOldWorldZeroRun(int world) throws Exception {
    player().world = 0;
    assertEquals(Retcode.SUCC, raid().enter(43306, 0, 0, 0, List.of()));
    var run = raid().current();
    assertNull(run.getDifficultySnapshot());
    assertEquals(Retcode.SUCC, raid().leave(43306, false));
    player().world = world;
    assertEquals(0, RaidDifficultyAdjustment46Test.number(source.update(43306, 1, 1), "retcode"));
    assertNotEquals(Retcode.SUCC, raid().enter(43306, 0, 0, 0, List.of()));
    assertNull(run.getDifficultySnapshot());
    assertSame(run, player().getGameplay().getMissionRaids().get(43306));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 7, 99})
  void absentWorldTierCannotCreatePreferenceOrRaid(int world) throws Exception {
    player().world = world;
    int saves = player().saves;
    assertNotEquals(
        0, RaidDifficultyAdjustment46Test.number(source.update(43306, 1, 1), "retcode"));
    assertNotEquals(Retcode.SUCC, raid().enter(43306, 0, 0, 0, List.of()));
    assertEquals(saves, player().saves);
    assertTrue(player().getGameplay().getRaidDifficultySettings().snapshot().isEmpty());
  }

  @ParameterizedTest
  @MethodSource("npcs")
  void higherNpcConstructionUsesFrozenWorldAndOriginalStage(int world, int preference)
      throws Exception {
    enter(world, preference);
    var gson = source.fixture.source.helper.gson;
    var excel =
        gson.fromJson(
            "{\"ID\":1999988,\"Rank\":\"Minion\"}", emu.lunarcore.data.excel.NpcMonsterExcel.class);
    var map = GameData.getNpcMonsterExcelMap();
    var old = map.get(1999988);
    map.add(excel);
    try {
      var group =
          gson.fromJson(
              "{\"GroupName\":\"HigherDifficultyUnit\"}",
              emu.lunarcore.data.config.GroupInfo.class);
      group.setId(9988);
      var info =
          gson.fromJson(
              "{\"ID\":9988,\"NPCMonsterID\":1999988,\"EventID\":20321050}",
              emu.lunarcore.data.config.MonsterInfo.class);
      var monster =
          new emu.lunarcore.game.scene.SceneEntityLoader()
              .loadMonster(player().getScene(), group, info);
      assertNotNull(monster);
      assertEquals(world - preference, monster.getWorldLevel());
      assertEquals(203210500 + world - preference, monster.getStageId());
    } finally {
      map.values().removeIf(row -> row.getId() == 1999988);
      if (old != null) map.add(old);
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("restores")
  void actualPlayerMorphiaRestoresEachTierAndPreferenceTwice(int world, int preference, int mode)
      throws Exception {
    enter(world, preference);
    if (mode == 1) {
      assertEquals(Retcode.SUCC, raid().leave(43306, false));
      assertEquals(
          0,
          RaidDifficultyAdjustment46Test.number(
              source.update(43306, 1, 1 - Math.max(0, preference)), "retcode"));
    }
    var expected = source.fixture.source.helper.gson.toJson(player().getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "default-world-one-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      var document = new Player();
      MissionTrialProvisioning46Test.set(document, "uid", player().getUid());
      MissionTrialProvisioning46Test.set(document, "worldLevel", world);
      MissionTrialProvisioning46Test.set(document, "gameplay", player().getGameplay());
      store.save(document);
      for (int attempt = 0; attempt < 2; attempt++) {
        var saved = Objects.requireNonNull(store.find(Player.class).first());
        assertEquals(expected, source.fixture.source.helper.gson.toJson(saved.getGameplay()));
        var copy = new DefaultEntryMissionRaid46Test.WorldActor(source.fixture.source.helper);
        copy.world = world;
        MissionTrialProvisioning46Test.set(copy, "gameplay", saved.getGameplay());
        var formal = new GameAvatar(GameData.getAvatarExcelMap().get(199986));
        formal.setOwner(copy);
        copy.getAvatars().getAvatars().put(199986, formal);
        copy.getCurrentLineup().getAvatars().add(199986);
        copy.getCurrentLineup().setMp(2);
        source.fixture.source.helper.position(copy, player().getEntryId());
        assertEquals(mode == 0, new MissionRaid(copy).restoreOnLogin());
        assertEquals(expected, source.fixture.source.helper.gson.toJson(copy.getGameplay()));
        if (mode == 1)
          assertEquals(Retcode.SUCC, new MissionRaid(copy).enter(43306, 0, 0, 0, List.of()));
        assertEquals(
            Math.max(0, preference),
            new MissionRaid(copy).current().getDifficultySnapshot().getDifficulty());
        var battle =
            Objects.requireNonNull(
                new MissionFarmingBattleTest.Service().startEventBattle(copy, 20321050));
        assertEquals(203210500 + world - Math.max(0, preference), battle.getStage().getId());
        assertEquals(world - Math.max(0, preference), battle.toProto().getWorldLevel());
        store.save(document);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
