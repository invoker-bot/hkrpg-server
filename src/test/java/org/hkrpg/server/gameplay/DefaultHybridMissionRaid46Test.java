package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.trial.TrialAvatar;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Full original 40431; acceptance, scene arrival and the first battle result are explicit fixtures.
 */
class DefaultHybridMissionRaid46Test {
  @TempDir Path root;
  final MissionTrialOnlyRaid46Test source = new MissionTrialOnlyRaid46Test();
  DefaultEntryMissionRaid46Test.WorldActor player;

  @BeforeEach
  void prepare() throws Exception {
    source.root = root;
    source.prepare();
    source.installRecords("default-hybrid-mission-raid-4.6.json");
    var helper = source.helper;
    player = new DefaultEntryMissionRaid46Test.WorldActor(helper);
    var formal = helper.player.getAvatarById(199986);
    formal.setOwner(player);
    player.getAvatars().getAvatars().put(199986, formal);
    player.getCurrentLineup().getAvatars().add(199986);
    helper.player = player;
    helper.fixture.player = player;
    source.player = player;
    var records = helper.original.getAsJsonObject("records");
    Files.writeString(
        root.resolve("ExcelOutput/RaidConfig.json"),
        records.get("ExcelOutput/RaidConfig.json").toString());
    String directory = "Config/Level/Mission/1040408/";
    var header =
        records
            .getAsJsonArray(directory + "MissionInfo_1040408.json")
            .get(0)
            .getAsJsonObject()
            .deepCopy();
    header.add(
        "SubMissionList",
        records
            .getAsJsonArray(directory + "MissionInfo_1040408.partial.json")
            .get(0)
            .getAsJsonObject()
            .get("SubMissionList"));
    var info = ResourceLoader.getGson().fromJson(header, MissionInfo.class);
    for (var sub : info.getSubMissionList()) {
      if (records.has(sub.getMissionJsonPath())) {
        var raw = records.getAsJsonArray(sub.getMissionJsonPath()).get(0);
        var task = ResourceLoader.getGson().fromJson(raw, MissionTaskInfo.class);
        task.retainSourceDefinition(raw);
        sub.setTask(task);
      }
      GameplayDefinitions.subs.put(sub.getId(), sub);
    }
    GameData.getMissionInfos().put(1040408, info);
    player.getGameplay().getMainMissions().put(1040407, new QuestState(1040407));
    helper.position(player, 2000101);
    player.getCurrentLineup().setMp(2);
  }

  @AfterEach
  void restore() throws Exception {
    source.restore();
  }

  private Retcode trial() {
    return new MissionRaid(player)
        .enterSelected(40431, 0, 0, 0, List.of(new RaidTeams.Selection(1021409, true)));
  }

  @Test
  void exactDefaultHybridRowIsAcceptedWithItsWholeOriginalMissionAndTeamGate() {
    var row = GameplayDefinitions.raids.get(4043100L);
    assertNull(row.EnterType());
    assertEquals("TrialOrPlayer", row.TeamType());
    assertEquals(List.of(1040408), row.MainMissionIDList());
    assertEquals(Map.of(43, 1409), RaidTeams.plan(row).requirements());
    assertEquals(34, GameData.getMissionInfos().get(1040408).getSubMissionList().length);
    assertNotNull(MissionRaid.resource(40431, 0));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void declaredTrialAndTheOwnedFormalRoleCanEachEnterWithoutGrantingFormalActors(boolean useTrial) {
    var ordinary = player.getCurrentLineup();
    if (!useTrial) {
      var formal = new GameAvatar(GameData.getAvatarExcelMap().get(1409));
      formal.setOwner(player);
      player.getAvatars().getAvatars().put(1409, formal);
      ordinary.getAvatars().clear();
      ordinary.getAvatars().add(1409);
    }
    var ids = Set.copyOf(player.getAvatars().getAvatars().keySet());
    assertEquals(
        Retcode.SUCC,
        useTrial ? trial() : new MissionRaid(player).enter(40431, 0, 0, 0, List.of()));
    assertEquals(40431001, player.getEntryId());
    assertTrue(new MissionRaid(player).activeFor(1040408));
    assertEquals(ids, player.getAvatars().getAvatars().keySet());
    assertEquals(List.of(useTrial ? 1021409 : 1409), player.getCurrentLineup().getAvatars());
    assertEquals(
        useTrial,
        player.getAvatarById(player.getCurrentLineup().getAvatars().getFirst())
            instanceof TrialAvatar);
    assertEquals(2, ordinary.getMp());
    assertEquals(QuestState.Phase.DOING, player.getMissionManager().sub(104040830).getPhase());
    assertFalse(MissionStateRecords.subCompleted(player, 104040816));
  }

  @Test
  void missingRequiredRoleRejectsBeforeChangingPartyOrSaving() {
    var before = source.helper.gson.toJson(player.getGameplay());
    assertEquals(
        Retcode.RAID_AVATAR_NOT_MATCH, new MissionRaid(player).enter(40431, 0, 0, 0, List.of()));
    assertEquals(before, source.helper.gson.toJson(player.getGameplay()));
    assertEquals(0, player.saves);
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
  }

  @Test
  void aTrialIdCannotBeReinterpretedAsAnOwnedFormalActor() {
    assertEquals(
        Retcode.RAID_AVATAR_NOT_MATCH,
        new MissionRaid(player)
            .enterSelected(40431, 0, 0, 0, List.of(new RaidTeams.Selection(1021409, false))));
    assertEquals(0, player.getGameplay().getCurrentMissionRaid());
    assertEquals(0, player.saves);
  }

  @Test
  void theOriginalStageAndTalkGateTransferTheSameTrialPartyToTheSecondFloor() {
    assertEquals(Retcode.SUCC, trial());
    var run = new MissionRaid(player).current();
    var frozen = source.helper.gson.toJson(run.getTrialParty());
    // Isolate the preceding battle outcome; this is not a combat-input or client acceptance test.
    player.getMissionManager().onStageWin(20431070);
    assertEquals(9, player.getGameplay().getStoryValues().get("floor:40431001:FSV_SunState"));
    assertEquals(
        Retcode.SUCC, player.getMissionManager().finishTalk(104040834, "Mission_104040834"));
    assertEquals(40431002, player.getEntryId());
    assertEquals(Map.of(104040829, 40431002), run.getTransferredSubs());
    assertTrue(new MissionRaid(player).activeFor(1040408));
    assertEquals(frozen, source.helper.gson.toJson(run.getTrialParty()));
    assertEquals(List.of(1021409), player.getCurrentLineup().getAvatars());
    assertNull(player.getMissionManager().sub(104040816));
  }

  @Test
  void duplicateStartDoesNotReplaceTheOwnedRun() {
    assertEquals(Retcode.SUCC, trial());
    var run = new MissionRaid(player).current();
    var frozen = source.helper.gson.toJson(player.getGameplay());
    int saves = player.saves;
    assertEquals(Retcode.RAID_DOING, trial());
    assertSame(run, new MissionRaid(player).current());
    assertEquals(frozen, source.helper.gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 7, 99})
  void absentHigherWorldDifficultyCannotBorrowTheWorldZeroEntry(int world) {
    player.world = world;
    assertEquals(Retcode.REQ_PARA_INVALID, trial());
    assertEquals(0, player.saves);
    assertEquals(0, player.getGameplay().getCurrentMissionRaid());
  }

  @Test
  void missingParentDoesNotCreateAnAcceptedRaidOrTemporaryParty() {
    player.getGameplay().getMainMissions().remove(1040407);
    assertEquals(Retcode.MISSION_NOT_DOING, trial());
    assertFalse(player.getRaidTrialManager().inside());
    assertEquals(0, player.saves);
  }
}
