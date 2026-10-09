package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.trial.TrialAvatar;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original 40412 ForbidReplace rule and source forced virtual replacement, not client selection.
 */
class MissionFrozenRaidTrial46Test {
  @TempDir Path root;
  final MissionRaidTrialLifecycle46Test helper = new MissionRaidTrialLifecycle46Test();
  MissionTrialProvisioning46Test.Actor player;

  @BeforeEach
  void prepare() throws Exception {
    helper.root = root;
    helper.prepare();
    player = helper.player;
    var source = helper.fixture.fixture("raid-forbidden-scripted-trial-4.6.json");
    var records = source.getAsJsonObject("records");
    for (var raw : records.getAsJsonArray("ExcelOutput/RaidConfig.json")) {
      var row = helper.gson.fromJson(raw, GameplayDefinitions.Raid.class);
      GameplayDefinitions.raids.put((long) row.RaidID() * 100 + row.WorldLevel(), row);
    }
    for (var raw : records.getAsJsonArray("ExcelOutput/MainMission.json"))
      GameData.getMainMissionExcelMap()
          .add(ResourceLoader.getGson().fromJson(raw, MainMissionExcel.class));
    for (var raw : records.getAsJsonArray("ExcelOutput/MazePlane.json"))
      GameData.getMazePlaneExcelMap()
          .add(ResourceLoader.getGson().fromJson(raw, MazePlaneExcel.class));
    for (var raw : records.getAsJsonArray("ExcelOutput/MapEntrance.json"))
      GameData.getMapEntranceExcelMap()
          .add(ResourceLoader.getGson().fromJson(raw, MapEntranceExcel.class));
    var limits = root.resolve("ExcelOutput/TeamLimitCondition.json");
    Files.createDirectories(limits.getParent());
    Files.writeString(
        limits, records.getAsJsonArray("ExcelOutput/TeamLimitCondition.json").toString());
    for (var entry : records.entrySet())
      helper.original.getAsJsonObject("records").add(entry.getKey(), entry.getValue());
  }

  @AfterEach
  void restore() throws Exception {
    helper.restore();
  }

  @Test
  void originalForcedVirtualTaskCanReplaceTheOwnedForbiddenFormalParty() throws Exception {
    helper.open(40412, 104020708, 1021403);
    var run = new MissionRaid(player).current();
    assertTrue(player.getRaidTrialManager().inside());
    assertTrue(
        player
            .getRaidTrialManager()
            .allowed(RaidTeams.plan(GameplayDefinitions.raids.get(4041200L))));
    var base = helper.gson.toJson(run.getTrialParty());
    player.getMissionManager().refresh();
    assertInstanceOf(TrialAvatar.class, player.getAvatarById(1021403));
    assertEquals(List.of(1021403), player.getCurrentLineup().getAvatars());
    assertTrue(player.getCurrentLineup().toProto().getIsVirtual());
    assertEquals(QuestState.Phase.FINISHED, player.getMissionManager().sub(104020708).getPhase());
    assertTrue(new MissionRaid(player).activeFor(1040207));
    assertEquals(base, helper.gson.toJson(run.getTrialParty()));
    assertEquals(List.of(199986), player.getLineupManager().getLineups()[0].getAvatars());
    assertFalse(player.getLineupManager().replaceLineup(0, 13, List.of(199986)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"mp", "hp", "sp"})
  void hiddenOriginalRaidViewCannotMutateItsStoredVitals(String field) throws Exception {
    helper.open(40412, 104020708, 1021403);
    var view = player.getRaidTrialManager().lineup();
    player.getMissionManager().refresh();
    assertNotNull(player.getAvatarById(1021403));
    var before = helper.gson.toJson(player.getGameplay());
    int saves = player.saves;
    switch (field) {
      case "mp" -> view.setMp(1);
      case "hp" -> player.getAvatarById(199986).setCurrentHp(view, 1234);
      case "sp" -> player.getAvatarById(199986).setCurrentSp(view, 1234);
    }
    assertEquals(before, helper.gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
    assertEquals(List.of(1021403), player.getCurrentLineup().getAvatars());
  }

  @ParameterizedTest
  @ValueSource(strings = {"addMp", "refresh"})
  void hiddenOriginalRaidViewCannotPublishAConflictingLineup(String operation) throws Exception {
    helper.open(40412, 104020708, 1021403);
    var view = player.getRaidTrialManager().lineup();
    player.getMissionManager().refresh();
    int packets = player.packets.size();
    var before = helper.gson.toJson(player.getGameplay());
    if (operation.equals("addMp")) view.addMp(1);
    else view.refreshLineup();
    assertEquals(packets, player.packets.size());
    assertEquals(before, helper.gson.toJson(player.getGameplay()));
  }

  @Test
  void theScriptPartyPreservesTheOriginalFormalTeamIndex() throws Exception {
    var team = new emu.lunarcore.game.player.lineup.PlayerLineup(player, 1);
    team.getAvatars().add(199986);
    player.getLineupManager().getLineups()[1] = team;
    MissionTrialProvisioning46Test.setField(
        player.getLineupManager(),
        emu.lunarcore.game.player.lineup.LineupManager.class,
        "currentIndex",
        1);
    helper.open(40412, 104020708, 1021403);
    player.getMissionManager().refresh();
    assertEquals(1, player.getCurrentLineup().getIndex());
    assertSame(player.getCurrentLineup(), player.getLineupManager().getLineupByIndex(1));
    assertEquals(List.of(199986), team.getAvatars());
    assertTrue(new MissionRaid(player).activeFor(1040207));
  }

  @Test
  void pauseReturnAndJsonRecoveryKeepBothPartySnapshotsAndIndependentVitals() throws Exception {
    helper.open(40412, 104020708, 1021403);
    player.getMissionManager().refresh();
    var run = new MissionRaid(player).current();
    var base = helper.gson.toJson(run.getTrialParty());
    player.getAvatarById(1021403).setCurrentHp(player.getCurrentLineup(), 2468);
    player.getAvatarById(1021403).setCurrentSp(player.getCurrentLineup(), 3456);
    player.getCurrentLineup().setMp(2);
    var frozen = helper.gson.toJson(run.getMissionTrials());
    int entry = player.getEntryId();
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC, new MissionRaid(player).leave(40412, false));
    assertNull(player.getAvatarById(1021403));
    assertEquals(base, helper.gson.toJson(run.getTrialParty()));
    assertEquals(frozen, helper.gson.toJson(run.getMissionTrials()));
    var restored = helper.fixture.reload(player.getGameplay());
    var copy = restored.getGameplay().getMissionRaids().get(40412);
    restored.getGameplay().beginMissionRaid(copy);
    helper.position(restored, entry);
    restored.getMissionTrialManager().restoreOnLogin();
    assertTrue(new MissionRaid(restored).restoreOnLogin());
    assertEquals(2468, restored.getAvatarById(1021403).getCurrentHp(restored.getCurrentLineup()));
    assertEquals(3456, restored.getAvatarById(1021403).getCurrentSp(restored.getCurrentLineup()));
    assertEquals(2, restored.getCurrentLineup().getMp());
    assertEquals(base, helper.gson.toJson(copy.getTrialParty()));
    assertEquals(frozen, helper.gson.toJson(copy.getMissionTrials()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"binding", "roster", "hp", "mp", "world", "originalTrialInjection"})
  void damagedOriginalPartyCannotPublishTheFrozenScriptActor(String damage) throws Exception {
    helper.open(40412, 104020708, 1021403);
    player.getMissionManager().refresh();
    var restored = helper.fixture.reload(player.getGameplay());
    var run = new MissionRaid(restored).current();
    var base = run.getTrialParty();
    switch (damage) {
      case "binding" ->
          MissionTrialProvisioning46Test.setField(
              run.getMissionTrials().getParty(), MissionTrialState.Party.class, "raidBase", "{}");
      case "roster" ->
          MissionTrialProvisioning46Test.setField(
              base, RaidTrialParty.class, "roster", List.of(199987));
      case "hp" ->
          MissionTrialProvisioning46Test.setField(
              base.getVitals().get(199986), StoryLineState.Vital.class, "hp", 10001);
      case "mp" -> base.mp(100);
      case "world" ->
          MissionTrialProvisioning46Test.setField(base, RaidTrialParty.class, "playerWorld", 1);
      case "originalTrialInjection" -> base.getConfigs().put(1021403, "{}");
    }
    var before = helper.gson.toJson(restored.getGameplay());
    assertThrows(
        IllegalStateException.class, () -> restored.getMissionTrialManager().restoreOnLogin());
    assertNull(restored.getAvatarById(1021403));
    assertTrue(restored.getAvatars().getTempAvatars().isEmpty());
    assertEquals(before, helper.gson.toJson(restored.getGameplay()));
    assertEquals(0, restored.saves);
  }

  @Test
  void completedScriptPartyIsStoredInActualPlayerDocumentsWithItsOriginalBase() throws Exception {
    helper.open(40412, 104020708, 1021403);
    player.getMissionManager().refresh();
    var expected = helper.gson.toJson(player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store =
          dev.morphia.Morphia.createDatastore(client, "forbidden-raid-script-document-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      MissionTrialProvisioning46Test.set(document, "uid", player.getUid());
      MissionTrialProvisioning46Test.set(document, "gameplay", player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var saved =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(expected, helper.gson.toJson(saved.getGameplay()));
        var restored = helper.fixture.reload(saved.getGameplay());
        helper.position(restored, player.getEntryId());
        restored.getMissionTrialManager().restoreOnLogin();
        assertTrue(new MissionRaid(restored).restoreOnLogin());
        assertInstanceOf(TrialAvatar.class, restored.getAvatarById(1021403));
        assertEquals(expected, helper.gson.toJson(restored.getGameplay()));
        assertEquals(0, restored.saves);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
