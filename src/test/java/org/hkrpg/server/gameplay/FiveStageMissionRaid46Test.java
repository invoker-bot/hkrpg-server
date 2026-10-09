package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original five-main chain; prior acceptance and non-transfer outcomes are explicit fixtures. */
class FiveStageMissionRaid46Test {
  @TempDir Path root;
  final MissionTrialOnlyRaid46Test source = new MissionTrialOnlyRaid46Test();
  MultiStageMissionRaid46Test.SceneActor player;

  @BeforeEach
  void prepare() throws Exception {
    source.root = root;
    source.prepare();
    source.installRecords("five-stage-mission-raid-4.6.json");
    var helper = source.helper;
    player = new MultiStageMissionRaid46Test.SceneActor(helper);
    var formal = helper.player.getAvatarById(199986);
    formal.setOwner(player);
    player.getAvatars().getAvatars().put(199986, formal);
    player.getCurrentLineup().getAvatars().add(199986);
    helper.player = player;
    helper.fixture.player = player;
    source.player = player;
    var records = helper.original.getAsJsonObject("records");
    for (int main : List.of(2020502, 2020503, 2020504, 2020505, 2020506)) {
      String path = "Config/Level/Mission/" + main + "/";
      var info =
          helper.gson.fromJson(
              records.getAsJsonArray(path + "MissionInfo_" + main + ".json").get(0),
              MissionInfo.class);
      var subs = new ArrayList<MissionInfo.SubMissionInfo>();
      for (var raw :
          records
              .getAsJsonArray(path + "MissionInfo_" + main + ".partial.json")
              .get(0)
              .getAsJsonObject()
              .getAsJsonArray("SubMissionList")) {
        var sub = helper.gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
        if (records.has(sub.getMissionJsonPath())) {
          var script = records.getAsJsonArray(sub.getMissionJsonPath()).get(0);
          var task = helper.gson.fromJson(script, MissionTaskInfo.class);
          task.retainSourceDefinition(script);
          sub.setTask(task);
        }
        GameplayDefinitions.subs.put(sub.getId(), sub);
        subs.add(sub);
      }
      MissionTrialProvisioning46Test.setField(
          info,
          MissionInfo.class,
          "subMissionList",
          subs.toArray(MissionInfo.SubMissionInfo[]::new));
      GameData.getMissionInfos().put(main, info);
    }
    player.getGameplay().getMainMissions().put(2020501, new QuestState(2020501));
    helper.position(player, 2000101);
  }

  @AfterEach
  void restore() throws Exception {
    source.restore();
  }

  private void enterTrial(boolean mixed) {
    assertNotNull(player.getTrialManager().buildTrialAvatar(1101208), "Original trial resources");
    var choices = new ArrayList<RaidTeams.Selection>();
    if (mixed) choices.add(new RaidTeams.Selection(199986, false));
    choices.add(new RaidTeams.Selection(1101208, true));
    assertDoesNotThrow(
        () ->
            player
                .getRaidTrialManager()
                .prepare(RaidTeams.plan(GameplayDefinitions.raids.get(4320100L)), choices));
    assertEquals(Retcode.SUCC, new MissionRaid(player).enterSelected(43201, 0, 0, 0, choices));
  }

  private void formalChoice() {
    var actor = new GameAvatar(GameData.getAvatarExcelMap().get(1208));
    actor.setOwner(player);
    player.getAvatars().getAvatars().put(1208, actor);
    player.getCurrentLineup().getAvatars().clear();
    player.getCurrentLineup().getAvatars().add(1208);
    assertEquals(Retcode.SUCC, new MissionRaid(player).enter(43201, 0, 0, 0, List.of()));
  }

  private void goals(int id, Set<Integer> found) {
    if (!found.add(id)) return;
    var sub = Objects.requireNonNull(GameplayDefinitions.subs.get(id));
    if (sub.getTakeParamIntList() != null && !"Auto".equals(sub.getTakeType()))
      for (int gate : sub.getTakeParamIntList()) goals(gate, found);
  }

  private void completeStage() {
    var run = new MissionRaid(player).current();
    var found = new HashSet<Integer>();
    for (int id : GameData.getMissionInfos().get(run.getMainMission()).getFinishSubMissionList())
      goals(id, found);
    for (int id : found) {
      var sub = GameplayDefinitions.subs.get(id);
      if ("EnterRaidScene".equals(sub.getFinishType())) continue;
      assertTrue(
          Set.of("Talk", "StageWin", "SubMissionFinishCnt").contains(sub.getFinishType()),
          "Unknown isolated outcome " + id);
      var state = new QuestState(id);
      state.advance(sub.getProgress(), sub.getProgress(), 123);
      player.getGameplay().getSubMissions().put(id, state);
    }
    player.getMissionManager().refresh();
  }

  private void thirdStage() {
    completeStage();
    completeStage();
    var run = new MissionRaid(player).current();
    assertEquals(2, run.getSequence().getStage());
    assertEquals(2020504, run.getMainMission());
    assertEquals(43201003, player.getEntryId());
    assertNull(player.getMissionManager().sub(202050410));
    assertNull(player.getMissionManager().sub(202050412));
  }

  private void acceptRole(int id) {
    player.getGameplay().getSubMissions().put(id, new QuestState(id));
    player.getMissionManager().refresh();
  }

  @Test
  void originalFiveStageTrialAlternativeEntersAndTransfersThroughTwoStages() {
    enterTrial(false);
    thirdStage();
    assertEquals(List.of(1101208), player.getCurrentLineup().getAvatars());
    assertEquals(
        Map.of(202050211, 43201002, 202050310, 43201003),
        new MissionRaid(player).current().getTransferredSubs());
  }

  @Test
  void acceptedThirdStageReplacementCanSelectAnAuthoredRoleOutsideTheEntryInclusion() {
    enterTrial(false);
    thirdStage();
    acceptRole(202050412);
    assertEquals(List.of(1101001), player.getCurrentLineup().getAvatars());
    assertTrue(MissionStateRecords.subCompleted(player, 202050412));
    assertTrue(new MissionRaid(player).activeFor(2020504));
  }

  @Test
  void formalAlternativeReplacementKeepsTheOriginalRaidOwnedAndAllowsItsNextTransfer() {
    formalChoice();
    thirdStage();
    acceptRole(202050410);
    assertEquals(List.of(1101208), player.getCurrentLineup().getAvatars());
    acceptRole(202050412);
    assertEquals(List.of(1101001), player.getCurrentLineup().getAvatars());
    assertTrue(new MissionRaid(player).activeFor(2020504));
    completeStage();
    assertEquals(3, new MissionRaid(player).current().getSequence().getStage());
    assertEquals(43201004, player.getEntryId());
  }

  @Test
  void originalReplacementReusesTheOwnedEntryTrialWhenTheEntryTeamAlsoHasAFormalMember() {
    enterTrial(true);
    thirdStage();
    acceptRole(202050410);
    assertEquals(List.of(1101208), player.getCurrentLineup().getAvatars());
    assertTrue(MissionStateRecords.subCompleted(player, 202050410));
    assertEquals(Set.of(1101208), player.getAvatars().getTempAvatars().keySet());
  }

  @Test
  void originalFiveStageCompletionNeedsAllFourActualTransfersAndRestoresTheEntryTeam() {
    enterTrial(false);
    thirdStage();
    acceptRole(202050412);
    completeStage();
    assertEquals(List.of(1101208), player.getCurrentLineup().getAvatars());
    assertNull(new MissionRaid(player).current().getMissionTrials().getParty());
    completeStage();
    assertFalse(new MissionRaid(player).current().isCompleted());
    completeStage();
    var run = new MissionRaid(player).current();
    assertEquals(4, run.getSequence().getStage());
    assertTrue(run.isCompleted());
    assertEquals(
        Map.of(202050211, 43201002, 202050310, 43201003, 202050409, 43201004, 202050505, 43201005),
        run.getTransferredSubs());
    assertEquals(Retcode.SUCC, new MissionRaid(player).leave(43201, false));
    assertEquals(2000101, player.getEntryId());
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "empty", "wrong", "duplicate"})
  void aReplacementWithADamagedOriginalEntryRosterCannotPublishOnRestore(String damage)
      throws Exception {
    enterTrial(false);
    thirdStage();
    acceptRole(202050412);
    var party = new MissionRaid(player).current().getMissionTrials().getParty();
    assertEquals(List.of(1101208), party.getEntryRoster());
    switch (damage) {
      case "missing" ->
          MissionTrialProvisioning46Test.setField(
              party, MissionTrialState.Party.class, "entryRoster", null);
      case "empty" -> party.getEntryRoster().clear();
      case "wrong" -> {
        party.getEntryRoster().clear();
        party.getEntryRoster().add(199986);
      }
      case "duplicate" -> party.getEntryRoster().add(1101208);
    }
    player.getMissionTrialManager().detach();
    assertThrows(
        IllegalStateException.class, () -> player.getMissionTrialManager().restoreOnLogin());
    assertNull(player.getAvatarById(1101001));
    assertEquals(Set.of(1101208), player.getAvatars().getTempAvatars().keySet());
  }

  @Test
  void reusingTheEntryTrialStillHasIndependentMissionHpEnergyAndTechniquePoints() {
    enterTrial(true);
    thirdStage();
    var base = player.getCurrentLineup();
    base.setMp(4);
    player.getAvatarById(1101208).setCurrentHp(base, 4200);
    player.getAvatarById(1101208).setCurrentSp(base, 3200);
    acceptRole(202050410);
    var current = player.getCurrentLineup();
    assertEquals(4200, player.getAvatarById(1101208).getCurrentHp(current));
    assertEquals(3200, player.getAvatarById(1101208).getCurrentSp(current));
    current.setMp(1);
    player.getAvatarById(1101208).setCurrentHp(current, 3000);
    player.getAvatarById(1101208).setCurrentSp(current, 1000);
    assertEquals(4, base.getMp());
    assertEquals(4200, player.getAvatarById(1101208).getCurrentHp(base));
    assertEquals(3200, player.getAvatarById(1101208).getCurrentSp(base));
    assertEquals(Retcode.SUCC, new MissionRaid(player).leave(43201, false));
    assertEquals(5, player.getCurrentLineup().getMp());
    assertEquals(
        Retcode.SUCC,
        new MissionRaid(player)
            .enterSelected(
                43201,
                0,
                0,
                0,
                List.of(
                    new RaidTeams.Selection(199986, false),
                    new RaidTeams.Selection(1101208, true))));
    current = player.getCurrentLineup();
    assertEquals(1, current.getMp());
    assertEquals(3000, player.getAvatarById(1101208).getCurrentHp(current));
    assertEquals(1000, player.getAvatarById(1101208).getCurrentSp(current));
    assertTrue(
        new MissionRaid(player).current().getMissionTrials().getParty().getActors().isEmpty());
    assertEquals(
        Set.of(202050410),
        new MissionRaid(player).current().getMissionTrials().getExecuted().keySet());
  }

  @Test
  void theAuthoredLaterRoleDoesNotBecomeASelectableEntryTrial() {
    assertEquals(
        Retcode.RAID_AVATAR_NOT_MATCH,
        new MissionRaid(player)
            .enterSelected(43201, 0, 0, 0, List.of(new RaidTeams.Selection(1101001, true))));
    assertNull(new MissionRaid(player).current());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void actualPlayerMorphiaRestoresAnAliasOrReplacementWithItsOriginalEntryTeam(int mode)
      throws Exception {
    if (mode == 2) formalChoice();
    else enterTrial(mode == 0);
    thirdStage();
    acceptRole(mode == 0 ? 202050410 : 202050412);
    var expected = source.helper.gson.toJson(player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "five-stage-entry-proof-fixture");
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
      for (int attempt = 0; attempt < 2; attempt++) {
        var saved =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(expected, source.helper.gson.toJson(saved.getGameplay()));
        var copy = new MultiStageMissionRaid46Test.SceneActor(source.helper);
        MissionTrialProvisioning46Test.set(copy, "gameplay", saved.getGameplay());
        for (var member : player.getAvatars().getAvatars().values()) {
          var formal = new GameAvatar(member.getExcel());
          formal.setOwner(copy);
          copy.getAvatars().getAvatars().put(formal.getAvatarId(), formal);
        }
        copy.getLineupManager().getLineups()[0].getAvatars().add(mode == 2 ? 1208 : 199986);
        var run = new MissionRaid(copy).current();
        if (run.getTrialParty() != null)
          copy.getRaidTrialManager()
              .install(
                  run,
                  copy.getRaidTrialManager()
                      .rebuild(
                          run.getTrialParty(),
                          RaidTeams.plan(GameplayDefinitions.raids.get(4320100L))));
        source.helper.position(copy, player.getEntryId());
        copy.getMissionTrialManager().restoreOnLogin();
        assertTrue(new MissionRaid(copy).restoreOnLogin());
        assertEquals(
            mode == 0 ? List.of(1101208) : List.of(1101001), copy.getCurrentLineup().getAvatars());
        assertEquals(expected, source.helper.gson.toJson(copy.getGameplay()));
        assertEquals(0, copy.saves);
        store.save(saved);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
