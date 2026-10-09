package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original 20213 source; battle/talk outcomes and scene arrival are explicit isolated fixtures. */
class RecoverMpMissionRaid46Test {
  @TempDir Path root;
  final MissionTrialOnlyRaid46Test source = new MissionTrialOnlyRaid46Test();
  MultiStageMissionRaid46Test.SceneActor player;

  @BeforeEach
  void prepare() throws Exception {
    source.root = root;
    source.prepare();
    source.installRecords("recover-mp-mission-raid-4.6.json");
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
    for (int main : List.of(1020400, 1020401)) {
      String path = "Config/Level/Mission/" + main + "/";
      var raw =
          records
              .getAsJsonArray(path + "MissionInfo_" + main + ".json")
              .get(0)
              .getAsJsonObject()
              .deepCopy();
      raw.add(
          "SubMissionList",
          records
              .getAsJsonArray(path + "MissionInfo_" + main + ".partial.json")
              .get(0)
              .getAsJsonObject()
              .get("SubMissionList"));
      var info = helper.gson.fromJson(raw, MissionInfo.class);
      for (var sub : info.getSubMissionList()) {
        if (records.has(sub.getMissionJsonPath())) {
          var script = records.getAsJsonArray(sub.getMissionJsonPath()).get(0);
          var task = helper.gson.fromJson(script, MissionTaskInfo.class);
          task.retainSourceDefinition(script);
          sub.setTask(task);
        }
        GameplayDefinitions.subs.put(sub.getId(), sub);
      }
      GameData.getMissionInfos().put(main, info);
    }
    player.getGameplay().getMainMissions().put(1020301, new QuestState(1020301));
    helper.position(player, 2000101);
    player.getCurrentLineup().setMp(2);
  }

  @AfterEach
  void restore() throws Exception {
    source.restore();
  }

  private void enter() {
    assertNotNull(
        player.getTrialManager().buildTrialAvatar(1021002), "Original trial source available");
    assertEquals(Retcode.SUCC, new MissionRaid(player).enter(20213, 0, 0, 0, List.of()));
  }

  private void outcome(int id) {
    var sub = Objects.requireNonNull(GameplayDefinitions.subs.get(id));
    assertTrue(
        Set.of("Talk", "StageWin").contains(sub.getFinishType()),
        "Explicit isolated outcome " + id);
    var state = new QuestState(id);
    state.advance(sub.getProgress(), sub.getProgress(), 123);
    player.getGameplay().getSubMissions().put(id, state);
  }

  private void secondStage() {
    outcome(102040101);
    outcome(102040102);
    player.getMissionManager().refresh();
    var run = new MissionRaid(player).current();
    assertEquals(1, run.getSequence().getStage());
    assertEquals(1020401, run.getMainMission());
    assertEquals(2021301, player.getEntryId());
    assertTrue(MissionStateRecords.subCompleted(player, 102040103));
    assertEquals(2021301, run.getTransferredSubs().get(102040103));
  }

  @Test
  void originalRecoveryRowAndTwoStageSourceAreAvailable() {
    var row = GameplayDefinitions.raids.get(2021300L);
    assertEquals(List.of("RecoverMp"), row.RecoverType());
    assertEquals(List.of(1020400, 1020401), row.MainMissionIDList());
    assertTrue(MissionRaidSequence.supported(row));
    assertNotNull(MissionRaid.resource(20213, 0));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2, 5})
  void firstEntryFillsOnlyTheDeclaredTrialParty(int formalMp) {
    var ordinary = player.getCurrentLineup();
    ordinary.setMp(formalMp);
    var actor = player.getAvatarById(199986);
    int hp = actor.getCurrentHp(ordinary), sp = actor.getCurrentSp(ordinary);
    enter();
    assertEquals(List.of(1021002), player.getCurrentLineup().getAvatars());
    assertEquals(player.getCurrentLineup().getMaxMp(), player.getCurrentLineup().getMp());
    assertEquals(formalMp, ordinary.getMp());
    assertEquals(hp, actor.getCurrentHp(ordinary));
    assertEquals(sp, actor.getCurrentSp(ordinary));
    assertEquals(Set.of(199986), player.getAvatars().getAvatars().keySet());
  }

  @Test
  void pauseAndReentryPreserveConsumedPointsWithoutRefilling() {
    enter();
    player.getCurrentLineup().setMp(1);
    var raid = new MissionRaid(player);
    assertEquals(Retcode.SUCC, raid.leave(20213, false));
    assertEquals(2, player.getCurrentLineup().getMp());
    enter();
    assertEquals(1, player.getCurrentLineup().getMp());
    assertEquals(1, raid.current().getTrialParty().getMp());
  }

  @Test
  void originalTransferAndBothCreateTasksRetainConsumedRaidPoints() {
    enter();
    player.getCurrentLineup().setMp(1);
    secondStage();
    // The original 214 has no TakeType; its script acceptance is an explicit fixture.
    assertNull(GameplayDefinitions.subs.get(102040214).getTakeType());
    player.getGameplay().getSubMissions().put(102040214, new QuestState(102040214));
    player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(player, 102040214));
    assertEquals(List.of(1021002, 1021206), player.getCurrentLineup().getAvatars());
    assertEquals(1, player.getCurrentLineup().getMp());
    outcome(102040212);
    player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.subCompleted(player, 102040213));
    assertEquals(List.of(1021002, 1021206, 1021203), player.getCurrentLineup().getAvatars());
    assertEquals(1, player.getCurrentLineup().getMp());
    assertEquals(1, new MissionRaid(player).current().getTrialParty().getMp());
  }

  @Test
  void originalFinalGoalsFinishBothStagesAndRestoreTheFormalParty() {
    enter();
    player.getCurrentLineup().setMp(1);
    secondStage();
    outcome(102040206);
    outcome(102040201);
    outcome(102040202);
    player.getMissionManager().refresh();
    var run = new MissionRaid(player).current();
    assertTrue(MissionRaidSequence.finished(player, run, 1020400));
    assertTrue(MissionRaidSequence.finished(player, run, 1020401));
    assertEquals(Retcode.SUCC, new MissionRaid(player).leave(20213, false));
    assertTrue(run.isCompleted());
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    assertEquals(2, player.getCurrentLineup().getMp());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"RecoverHp", "ResetMp", "RecoverSp", "RecoverHalfSp"})
  void unimplementedRecoveryKindsRejectBeforePublishingActors(String kind) {
    var row =
        source.helper.gson.toJsonTree(GameplayDefinitions.raids.get(2021300L)).getAsJsonObject();
    var list = new com.google.gson.JsonArray();
    list.add(kind);
    row.add("RecoverType", list);
    GameplayDefinitions.raids.put(
        2021300L, source.helper.gson.fromJson(row, GameplayDefinitions.Raid.class));
    var before = source.helper.gson.toJson(player.getGameplay());
    int saves = player.saves;
    assertNotEquals(Retcode.SUCC, new MissionRaid(player).enter(20213, 0, 0, 0, List.of()));
    assertEquals(before, source.helper.gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertEquals(2, player.getCurrentLineup().getMp());
  }

  @ParameterizedTest
  @ValueSource(strings = {"mixed", "duplicate", "null"})
  void ambiguousRecoveryListsCannotEnterOrChangeTheFormalParty(String kind) {
    var row =
        source.helper.gson.toJsonTree(GameplayDefinitions.raids.get(2021300L)).getAsJsonObject();
    var values = new com.google.gson.JsonArray();
    values.add("RecoverMp");
    values.add(kind.equals("mixed") ? "Unknown" : "RecoverMp");
    row.add("RecoverType", kind.equals("null") ? com.google.gson.JsonNull.INSTANCE : values);
    GameplayDefinitions.raids.put(
        2021300L, source.helper.gson.fromJson(row, GameplayDefinitions.Raid.class));
    var before = source.helper.gson.toJson(player.getGameplay());
    int saves = player.saves;
    assertNotEquals(Retcode.SUCC, new MissionRaid(player).enter(20213, 0, 0, 0, List.of()));
    assertEquals(before, source.helper.gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertEquals(2, player.getCurrentLineup().getMp());
  }

  @Test
  void changedRecoveryResourceClosesTheSavedRaidWithoutRefillingOrChangingFormalPoints() {
    enter();
    player.getCurrentLineup().setMp(1);
    var run = new MissionRaid(player).current();
    var row =
        source.helper.gson.toJsonTree(GameplayDefinitions.raids.get(2021300L)).getAsJsonObject();
    var values = new com.google.gson.JsonArray();
    values.add("Unknown");
    row.add("RecoverType", values);
    GameplayDefinitions.raids.put(
        2021300L, source.helper.gson.fromJson(row, GameplayDefinitions.Raid.class));
    assertFalse(new MissionRaid(player).restoreOnLogin());
    assertEquals(0, player.getGameplay().getCurrentMissionRaid());
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    assertEquals(2, player.getCurrentLineup().getMp());
    assertEquals(1, run.getTrialParty().getMp());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 6})
  void corruptSavedPointCountsAreRejectedInsteadOfBeingRepairedByEntryRecovery(int points) {
    enter();
    var party = new MissionRaid(player).current().getTrialParty();
    party.mp(points);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            player
                .getRaidTrialManager()
                .rebuild(party, RaidTeams.plan(GameplayDefinitions.raids.get(2021300L))));
    assertEquals(points, party.getMp());
    assertEquals(2, player.getLineupManager().getLineups()[0].getMp());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void actualPlayerMorphiaRestoresConsumedBaseScriptedOrPausedPointsWithoutRecoveringAgain(int mode)
      throws Exception {
    enter();
    player.getCurrentLineup().setMp(1);
    if (mode == 1) {
      secondStage();
      player.getGameplay().getSubMissions().put(102040214, new QuestState(102040214));
      player.getMissionManager().refresh();
      assertTrue(MissionStateRecords.subCompleted(player, 102040214));
    }
    if (mode == 2) assertEquals(Retcode.SUCC, new MissionRaid(player).leave(20213, false));
    var expected = source.helper.gson.toJson(player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "recover-mp-entry-fixture");
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
        var formal =
            new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(199986));
        formal.setOwner(copy);
        copy.getAvatars().getAvatars().put(199986, formal);
        copy.getLineupManager().getLineups()[0].getAvatars().add(199986);
        copy.getLineupManager().getLineups()[0].setMp(2);
        source.helper.position(copy, player.getEntryId());
        if (mode == 2) {
          assertFalse(new MissionRaid(copy).restoreOnLogin());
          assertEquals(expected, source.helper.gson.toJson(copy.getGameplay()));
          assertEquals(Retcode.SUCC, new MissionRaid(copy).enter(20213, 0, 0, 0, List.of()));
        } else {
          var run = new MissionRaid(copy).current();
          copy.getRaidTrialManager()
              .install(
                  run,
                  copy.getRaidTrialManager()
                      .rebuild(
                          run.getTrialParty(),
                          RaidTeams.plan(GameplayDefinitions.raids.get(2021300L))));
          copy.getMissionTrialManager().restoreOnLogin();
          assertTrue(new MissionRaid(copy).restoreOnLogin());
          assertEquals(expected, source.helper.gson.toJson(copy.getGameplay()));
          assertEquals(0, copy.saves);
        }
        assertEquals(
            mode == 1 ? List.of(1021002, 1021206) : List.of(1021002),
            copy.getCurrentLineup().getAvatars());
        assertEquals(1, copy.getCurrentLineup().getMp());
        assertEquals(2, copy.getLineupManager().getLineups()[0].getMp());
        // Reentering a paused copy mutates its loaded document; repeat from the original snapshot.
        store.save(document);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
