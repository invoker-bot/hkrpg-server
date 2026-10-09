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
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Same original 43306 world-1 records; explicit acceptance and scene arrival are fixtures. */
class DefaultWorldOneRaid46Test {
  @TempDir Path root;
  final RaidDifficultyAdjustment46Test source = new RaidDifficultyAdjustment46Test();

  @BeforeEach
  void prepare() throws Exception {
    source.root = root;
    source.prepare();
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

  void enter() throws Exception {
    assertEquals(0, RaidDifficultyAdjustment46Test.number(source.update(43306, 1, 0), "retcode"));
    assertEquals(Retcode.SUCC, raid().enter(43306, 0, 0, 0, List.of()));
    assertEquals(1, player().getWorldLevel());
  }

  emu.lunarcore.game.battle.Battle battle() {
    return Objects.requireNonNull(
        new MissionFarmingBattleTest.Service().startEventBattle(player(), 20321050));
  }

  @Test
  void selectedDefaultFreezesActualAndEffectiveWorldOne() throws Exception {
    enter();
    var snapshot = raid().current().getDifficultySnapshot();
    assertNotNull(snapshot);
    assertEquals(1, snapshot.getActualWorld());
    assertEquals(1, snapshot.getEffectiveWorld());
    assertEquals(0, snapshot.getDifficulty());
    assertTrue(raid().active());
    assertEquals(2, player().getCurrentLineup().getMp());
  }

  @Test
  void actualDefaultEventUsesOriginalWorldOneStageAndWireValue() throws Exception {
    enter();
    var battle = battle();
    assertEquals(203210501, battle.getStage().getId());
    assertEquals(32, ((StageExcel) battle.getStage()).getLevel());
    assertEquals(1, battle.toProto().getWorldLevel());
    assertEquals(1, player().getWorldLevel());
  }

  @Test
  void pausedDefaultIsNotReinterpretedByLaterEasySelection() throws Exception {
    enter();
    var run = raid().current();
    assertEquals(Retcode.SUCC, raid().leave(43306, false));
    assertEquals(0, RaidDifficultyAdjustment46Test.number(source.update(43306, 1, 1), "retcode"));
    assertEquals(Retcode.SUCC, raid().enter(43306, 0, 0, 0, List.of()));
    assertSame(run, raid().current());
    assertEquals(203210501, battle().getStage().getId());
    assertEquals(0, run.getDifficultySnapshot().getDifficulty());
  }

  @Test
  void actualWorldChangeStillRejectsDefaultBattleSettlement() throws Exception {
    enter();
    var battle = battle();
    player().world = 2;
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

  @Test
  void damagedDefaultSnapshotCannotFallBackToAnEqualFormalWorld() throws Exception {
    enter();
    var field = GameplayState.MissionRaidRun.class.getDeclaredField("difficultySnapshot");
    field.setAccessible(true);
    field.set(raid().current(), null);
    assertFalse(raid().active());
    assertNull(new MissionFarmingBattleTest.Service().startEventBattle(player(), 20321050));
  }

  @Test
  void originalWorldOneTrialRetainsDefaultDifficultyAcrossConsumedPointResume() throws Exception {
    enter();
    var complete = DefaultEntryMissionRaid46Test.class.getDeclaredMethod("completeStage");
    complete.setAccessible(true);
    complete.invoke(source.fixture);
    var talk = new QuestState(103190102);
    talk.advance(1, 1, 123);
    player().getGameplay().getSubMissions().put(103190102, talk);
    player().getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(player(), 103190112));
    assertEquals(1, player().getTrialManager().specialRow(1021304).getWorldLevel());
    player().getCurrentLineup().setMp(1);
    assertEquals(Retcode.SUCC, raid().leave(43306, false));
    assertEquals(0, RaidDifficultyAdjustment46Test.number(source.update(43306, 1, 1), "retcode"));
    assertEquals(Retcode.SUCC, raid().enter(43306, 0, 0, 0, List.of()));
    assertEquals(List.of(1021304), player().getCurrentLineup().getAvatars());
    assertEquals(1, player().getCurrentLineup().getMp());
    assertEquals(2, player().getLineupManager().getLineups()[0].getMp());
    assertEquals(1, battle().toProto().getWorldLevel());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void actualPlayerMorphiaRestoresActiveOrPausedDefaultTwice(int mode) throws Exception {
    enter();
    if (mode == 1) {
      assertEquals(Retcode.SUCC, raid().leave(43306, false));
      assertEquals(0, RaidDifficultyAdjustment46Test.number(source.update(43306, 1, 1), "retcode"));
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
      MissionTrialProvisioning46Test.set(document, "worldLevel", 1);
      MissionTrialProvisioning46Test.set(document, "gameplay", player().getGameplay());
      store.save(document);
      for (int attempt = 0; attempt < 2; attempt++) {
        var saved = Objects.requireNonNull(store.find(Player.class).first());
        assertEquals(expected, source.fixture.source.helper.gson.toJson(saved.getGameplay()));
        var copy = new DefaultEntryMissionRaid46Test.WorldActor(source.fixture.source.helper);
        copy.world = 1;
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
        assertEquals(0, new MissionRaid(copy).current().getDifficultySnapshot().getDifficulty());
        var battle =
            Objects.requireNonNull(
                new MissionFarmingBattleTest.Service().startEventBattle(copy, 20321050));
        assertEquals(203210501, battle.getStage().getId());
        assertEquals(1, battle.toProto().getWorldLevel());
        store.save(document);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
