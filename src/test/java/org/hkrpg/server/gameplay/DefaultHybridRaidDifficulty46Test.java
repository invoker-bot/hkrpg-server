package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
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

/** Original 40431 worlds; parent, scene construction and drops are explicitly isolated. */
class DefaultHybridRaidDifficulty46Test {
  @TempDir Path root;
  final RaidStageWinAcceptance46Test source = new RaidStageWinAcceptance46Test();
  final List<PlaneEventExcel> oldEvents = new ArrayList<>();
  JsonObject records;
  static final int[] TRIAL_LEVELS = {40, 40, 50, 58, 68, 78, 80};

  static Stream<Arguments> scenarios() {
    return IntStream.rangeClosed(1, 6)
        .boxed()
        .flatMap(
            world ->
                IntStream.of(-1, 0, 1)
                    .boxed()
                    .flatMap(
                        preference ->
                            Stream.of(false, true)
                                .map(trial -> Arguments.of(world, preference, trial))));
  }

  static Stream<Arguments> pauses() {
    return IntStream.rangeClosed(1, 6)
        .boxed()
        .flatMap(
            world ->
                IntStream.of(-1, 0, 1).mapToObj(preference -> Arguments.of(world, preference)));
  }

  static Stream<Arguments> restores() {
    return IntStream.of(1, 6)
        .boxed()
        .flatMap(
            world ->
                IntStream.of(0, 1)
                    .boxed()
                    .flatMap(
                        preference ->
                            Stream.of(false, true)
                                .map(paused -> Arguments.of(world, preference, paused))));
  }

  @BeforeEach
  void prepare() throws Exception {
    source.root = root;
    source.prepare();
    records =
        source
            .hybrid
            .source
            .helper
            .fixture
            .fixture("default-hybrid-raid-worlds-4.6.json")
            .getAsJsonObject("records");
    oldEvents.addAll(GameData.getPlaneEventExcelMap().values());
    GameData.getPlaneEventExcelMap().values().clear();
    for (var raw : records.getAsJsonArray("ExcelOutput/PlaneEvent.json"))
      GameData.getPlaneEventExcelMap()
          .add(ResourceLoader.getGson().fromJson(raw, PlaneEventExcel.class));
    for (var entry : records.entrySet())
      if (entry.getKey().startsWith("Config/BattleLineupPage/SkillTreePointPreset/")) {
        var path = root.resolve(entry.getKey());
        Files.createDirectories(path.getParent());
        Files.writeString(path, entry.getValue().getAsJsonArray().get(0).toString());
      }
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getPlaneEventExcelMap().values().clear();
    oldEvents.forEach(GameData.getPlaneEventExcelMap()::add);
    source.restore();
  }

  DefaultEntryMissionRaid46Test.WorldActor player() {
    return source.hybrid.player;
  }

  MissionRaid raid() {
    return new MissionRaid(player());
  }

  void enter(int world, int preference, boolean trial) {
    player().world = world;
    if (preference >= 0)
      assertEquals(
          Retcode.SUCC,
          player()
              .getGameplay()
              .getRaidDifficultySettings()
              .update(player(), 40431, 1, preference));
    if (!trial) {
      var role = new GameAvatar(GameData.getAvatarExcelMap().get(1409));
      role.setOwner(player());
      player().getAvatars().getAvatars().put(1409, role);
      player().getCurrentLineup().getAvatars().clear();
      player().getCurrentLineup().getAvatars().add(1409);
    }
    assertEquals(
        Retcode.SUCC,
        raid()
            .enterSelected(
                40431,
                0,
                0,
                0,
                trial ? List.of(new RaidTeams.Selection(1021409, true)) : List.of()));
  }

  emu.lunarcore.game.battle.Battle battle() {
    return Objects.requireNonNull(
        new RaidStageWinAcceptance46Test.Service().startEventBattle(player(), 20431070));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  void eachWorldPreferenceAndPartyUseTheOriginalStageAndAdvanceTheRaidGoal(
      int world, int preference, boolean trial) {
    enter(world, preference, trial);
    var frozen = raid().current().getDifficultySnapshot();
    int selected = Math.max(0, preference);
    assertEquals(world, frozen.getActualWorld());
    assertEquals(world - selected, frozen.getEffectiveWorld());
    assertEquals(selected, frozen.getDifficulty());
    assertEquals(world, player().getWorldLevel());
    if (trial) assertEquals(TRIAL_LEVELS[world], player().getAvatarById(1021409).getLevel());
    var battle = battle();
    assertEquals(204310700 + world - selected, battle.getStage().getId());
    assertEquals(world - selected, battle.toProto().getWorldLevel());
    assertNotNull(
        new RaidStageWinAcceptance46Test.Service()
            .finishBattle(
                player(),
                battle.getId(),
                battle.getStage().getId(),
                BattleEndStatus.BATTLE_END_WIN,
                BattleStatistics.newInstance()));
    assertTrue(MissionStateRecords.subCompleted(player(), 104040830));
    assertEquals(9, player().getGameplay().getStoryValues().get("floor:40431001:FSV_SunState"));
    assertEquals(
        Retcode.SUCC, player().getMissionManager().finishTalk(104040834, "Mission_104040834"));
    assertEquals(40431002, player().getEntryId());
    assertEquals(world - selected, RaidDifficultySettings.world(player()));
  }

  @ParameterizedTest
  @MethodSource("pauses")
  void pausedRaidRetainsItsFrozenWorldAndTrialTierAcrossOppositeMenuChoice(
      int world, int preference) {
    enter(world, preference, true);
    var run = raid().current();
    var party = run.getTrialParty();
    player().getCurrentLineup().setMp(1);
    assertEquals(Retcode.SUCC, raid().leave(40431, false));
    assertEquals(
        Retcode.SUCC,
        player()
            .getGameplay()
            .getRaidDifficultySettings()
            .update(player(), 40431, 1, 1 - Math.max(0, preference)));
    assertEquals(
        Retcode.SUCC,
        raid().enterSelected(40431, 0, 0, 0, List.of(new RaidTeams.Selection(1021409, true))));
    assertSame(run, raid().current());
    assertSame(party, run.getTrialParty());
    assertEquals(Math.max(0, preference), run.getDifficultySnapshot().getDifficulty());
    assertEquals(1, player().getCurrentLineup().getMp());
    assertEquals(TRIAL_LEVELS[world], player().getAvatarById(1021409).getLevel());
    assertEquals(204310700 + world - Math.max(0, preference), battle().getStage().getId());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 7, 99})
  void missingWorldTierCannotCreateAPreferenceOrTemporaryParty(int world) {
    player().world = world;
    assertEquals(
        Retcode.REQ_PARA_INVALID,
        player().getGameplay().getRaidDifficultySettings().update(player(), 40431, 1, 1));
    assertEquals(
        Retcode.REQ_PARA_INVALID,
        raid().enterSelected(40431, 0, 0, 0, List.of(new RaidTeams.Selection(1021409, true))));
    assertEquals(0, player().saves);
    assertTrue(player().getGameplay().getRaidDifficultySettings().snapshot().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 6})
  void changedActualWorldCannotSettleTheOriginalRaidBattle(int world) {
    enter(world, 1, true);
    var battle = battle();
    player().world = world + 1;
    assertNull(
        new RaidStageWinAcceptance46Test.Service()
            .finishBattle(
                player(),
                battle.getId(),
                battle.getStage().getId(),
                BattleEndStatus.BATTLE_END_WIN,
                BattleStatistics.newInstance()));
    assertSame(battle, player().getBattle());
    assertFalse(MissionStateRecords.subCompleted(player(), 104040830));
  }

  @ParameterizedTest
  @MethodSource("restores")
  void actualPlayerMorphiaRestoresTrialTierAndFrozenDifficultyInActiveAndPausedRuns(
      int world, int preference, boolean paused) throws Exception {
    enter(world, preference, true);
    if (paused) {
      assertEquals(Retcode.SUCC, raid().leave(40431, false));
      assertEquals(
          Retcode.SUCC,
          player()
              .getGameplay()
              .getRaidDifficultySettings()
              .update(player(), 40431, 1, 1 - preference));
    }
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "default-hybrid-worlds");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      var document = new Player();
      MissionTrialProvisioning46Test.set(document, "uid", player().getUid());
      MissionTrialProvisioning46Test.set(document, "worldLevel", world);
      MissionTrialProvisioning46Test.set(document, "gameplay", player().getGameplay());
      store.save(document);
      var saved = Objects.requireNonNull(store.find(Player.class).first());
      assertEquals(
          source.hybrid.source.helper.gson.toJson(player().getGameplay()),
          source.hybrid.source.helper.gson.toJson(saved.getGameplay()));
      var copy = new DefaultEntryMissionRaid46Test.WorldActor(source.hybrid.source.helper);
      copy.world = world;
      MissionTrialProvisioning46Test.set(copy, "gameplay", saved.getGameplay());
      var formal = new GameAvatar(GameData.getAvatarExcelMap().get(199986));
      formal.setOwner(copy);
      copy.getAvatars().getAvatars().put(199986, formal);
      copy.getLineupManager().getLineups()[0].getAvatars().add(199986);
      source.hybrid.source.helper.position(copy, player().getEntryId());
      assertEquals(!paused, new MissionRaid(copy).restoreOnLogin());
      if (paused)
        assertEquals(
            Retcode.SUCC,
            new MissionRaid(copy)
                .enterSelected(40431, 0, 0, 0, List.of(new RaidTeams.Selection(1021409, true))));
      assertEquals(TRIAL_LEVELS[world], copy.getAvatarById(1021409).getLevel());
      var battle =
          Objects.requireNonNull(
              new RaidStageWinAcceptance46Test.Service().startEventBattle(copy, 20431070));
      assertEquals(204310700 + world - preference, battle.getStage().getId());
    } finally {
      server.shutdownNow();
    }
  }

  void companionSource() throws Exception {
    var originalRaid = records.getAsJsonArray("ExcelOutput/RaidConfig.json").get(0).deepCopy();
    var originalRule =
        records.getAsJsonArray("ExcelOutput/TeamLimitCondition.json").get(0).deepCopy();
    source.hybrid.source.installRecords("default-entry-mission-raid-4.6.json");
    var companion = source.hybrid.source.helper.original.getAsJsonObject("records");
    for (int main : List.of(1032201, 1031901)) {
      String path = "Config/Level/Mission/" + main + "/";
      var raw =
          companion
              .getAsJsonArray(path + "MissionInfo_" + main + ".json")
              .get(0)
              .getAsJsonObject()
              .deepCopy();
      raw.add(
          "SubMissionList",
          companion
              .getAsJsonArray(path + "MissionInfo_" + main + ".partial.json")
              .get(0)
              .getAsJsonObject()
              .get("SubMissionList"));
      var info = ResourceLoader.getGson().fromJson(raw, MissionInfo.class);
      for (var sub : info.getSubMissionList()) {
        if (companion.has(sub.getMissionJsonPath())) {
          var script = companion.getAsJsonArray(sub.getMissionJsonPath()).get(0);
          var task = ResourceLoader.getGson().fromJson(script, MissionTaskInfo.class);
          task.retainSourceDefinition(script);
          sub.setTask(task);
        }
        GameplayDefinitions.subs.put(sub.getId(), sub);
      }
      GameData.getMissionInfos().put(main, info);
    }
    var raids = companion.getAsJsonArray("ExcelOutput/RaidConfig.json").deepCopy();
    raids.add(originalRaid);
    Files.writeString(root.resolve("ExcelOutput/RaidConfig.json"), raids.toString());
    var rules = companion.getAsJsonArray("ExcelOutput/TeamLimitCondition.json").deepCopy();
    rules.add(originalRule);
    Files.writeString(root.resolve("ExcelOutput/TeamLimitCondition.json"), rules.toString());
    assertNotNull(MissionRaid.resource(43306, 0));
    assertNotNull(MissionRaid.resource(40431, 0));
  }

  @Test
  void unrelatedRaidPreferenceDoesNotReplaceTheDefaultForANewHybridRun() throws Exception {
    companionSource();
    player().world = 2;
    assertEquals(
        Retcode.SUCC,
        player().getGameplay().getRaidDifficultySettings().update(player(), 43306, 1, 1));
    enter(2, -1, true);
    assertEquals(0, raid().current().getDifficultySnapshot().getDifficulty());
    assertEquals(204310702, battle().getStage().getId());
  }

  @Test
  void twoOriginalRaidChoicesRemainIndependentAndHaveDeterministicWireOrder() throws Exception {
    companionSource();
    player().world = 2;
    var choices = player().getGameplay().getRaidDifficultySettings();
    assertEquals(Retcode.SUCC, choices.update(player(), 40431, 1, 1));
    assertEquals(Retcode.SUCC, choices.update(player(), 43306, 1, 0));
    assertEquals(
        List.of(40431, 43306),
        choices.snapshot().stream().map(RaidDifficultySettings.Choice::getRaid).toList());
    assertEquals(
        List.of(1, 0),
        choices.snapshot().stream().map(RaidDifficultySettings.Choice::getDifficulty).toList());
    assertEquals(Retcode.SUCC, choices.update(player(), 43306, 1, 1));
    assertEquals(
        List.of(1, 1),
        choices.snapshot().stream().map(RaidDifficultySettings.Choice::getDifficulty).toList());
    enter(2, -1, true);
    assertEquals(1, raid().current().getDifficultySnapshot().getDifficulty());
    assertEquals(204310701, battle().getStage().getId());
  }

  @Test
  void actualPlayerMorphiaRestoresBothOriginalRaidChoices() throws Exception {
    companionSource();
    player().world = 2;
    var choices = player().getGameplay().getRaidDifficultySettings();
    assertEquals(Retcode.SUCC, choices.update(player(), 40431, 1, 1));
    assertEquals(Retcode.SUCC, choices.update(player(), 43306, 1, 0));
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "default-hybrid-menu-choices");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store.getMapper().map(Player.class, QuestState.class, Position.class);
      var document = new Player();
      MissionTrialProvisioning46Test.set(document, "uid", player().getUid());
      MissionTrialProvisioning46Test.set(document, "gameplay", player().getGameplay());
      store.save(document);
      var saved = Objects.requireNonNull(store.find(Player.class).first());
      assertEquals(
          List.of(40431, 43306),
          saved.getGameplay().getRaidDifficultySettings().snapshot().stream()
              .map(RaidDifficultySettings.Choice::getRaid)
              .toList());
      assertEquals(
          List.of(1, 0),
          saved.getGameplay().getRaidDifficultySettings().snapshot().stream()
              .map(RaidDifficultySettings.Choice::getDifficulty)
              .toList());
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void failedSecondChoiceSavePreservesTheFirstAndCanBeRetried() throws Exception {
    companionSource();
    class Actor extends DefaultEntryMissionRaid46Test.WorldActor {
      boolean failing;

      Actor() {
        super(source.hybrid.source.helper);
        world = 2;
      }

      @Override
      public void save() {
        if (failing) throw new IllegalStateException("Isolated menu save rejection");
        super.save();
      }
    }
    var actor = new Actor();
    var choices = actor.getGameplay().getRaidDifficultySettings();
    assertEquals(Retcode.SUCC, choices.update(actor, 43306, 1, 0));
    actor.failing = true;
    assertEquals(Retcode.SYSTEM_BUSY, choices.update(actor, 40431, 1, 1));
    assertEquals(
        List.of(43306),
        choices.snapshot().stream().map(RaidDifficultySettings.Choice::getRaid).toList());
    actor.failing = false;
    assertEquals(Retcode.SUCC, choices.update(actor, 40431, 1, 1));
    assertEquals(
        List.of(40431, 43306),
        choices.snapshot().stream().map(RaidDifficultySettings.Choice::getRaid).toList());
  }
}
