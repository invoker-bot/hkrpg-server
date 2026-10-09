package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.Position;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original two-main raid; source acceptance/scene arrival are explicitly isolated fixtures. */
class MultiStageMissionRaid46Test {
  @TempDir Path root;
  final MissionTrialOnlyRaid46Test source = new MissionTrialOnlyRaid46Test();
  SceneActor player;

  static class SceneActor extends MissionTrialProvisioning46Test.Actor {
    final MissionRaidTrialLifecycle46Test fixture;
    final List<Integer> transitions = new ArrayList<>();

    SceneActor(MissionRaidTrialLifecycle46Test fixture) {
      this.fixture = fixture;
    }

    @Override
    public boolean enterScene(int entry, int anchor, boolean notify) {
      fixture.position(this, entry);
      transitions.add(entry);
      return true;
    }

    @Override
    public boolean loadScene(
        int plane, int floor, int entry, Position pos, Position rot, boolean notify) {
      var row = GameData.getMapEntranceExcelMap().get(entry);
      if (row == null || row.getPlaneID() != plane || row.getFloorID() != floor) return false;
      fixture.position(this, entry);
      MissionTrialProvisioning46Test.set(this, "pos", pos.clone());
      MissionTrialProvisioning46Test.set(this, "rot", rot.clone());
      transitions.add(entry);
      return true;
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    source.root = root;
    source.prepare();
    source.installRecords("multi-stage-mission-raid-4.6.json");
    var helper = source.helper;
    player = new SceneActor(helper);
    var formal = helper.player.getAvatarById(199986);
    formal.setOwner(player);
    player.getAvatars().getAvatars().put(199986, formal);
    player.getCurrentLineup().getAvatars().add(199986);
    helper.player = player;
    helper.fixture.player = player;
    source.player = player;
    var records = helper.original.getAsJsonObject("records");
    for (int main : List.of(1021301, 1021401)) {
      String directory = "Config/Level/Mission/" + main + "/";
      var info =
          helper.gson.fromJson(
              records.getAsJsonArray(directory + "MissionInfo_" + main + ".json").get(0),
              MissionInfo.class);
      var subs = new ArrayList<MissionInfo.SubMissionInfo>();
      for (var raw :
          records
              .getAsJsonArray(directory + "MissionInfo_" + main + ".partial.json")
              .get(0)
              .getAsJsonObject()
              .getAsJsonArray("SubMissionList")) {
        var sub = helper.gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
        var caseData = helper.fixture.cases.get(sub.getId());
        var script = caseData == null ? null : caseData.get("script");
        if (records.has(sub.getMissionJsonPath()))
          script = records.getAsJsonArray(sub.getMissionJsonPath()).get(0);
        if (script != null) {
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
    player.getGameplay().getMainMissions().put(1021201, new QuestState(1021201));
    helper.position(player, 2000101);
  }

  @AfterEach
  void restore() throws Exception {
    source.restore();
  }

  @Test
  void exactMultiStageRowIsAcceptedWithItsOriginalTrialAndDistinctAfterMission() {
    var row = GameplayDefinitions.raids.get(4023100100L);
    var plan = RaidTeams.plan(row);
    assertNotNull(plan);
    var trials =
        player.getRaidTrialManager().prepare(plan, player.getRaidTrialManager().defaults(plan));
    assertEquals(List.of(1021002), trials.party().getRoster());
    assertEquals(List.of(1021301, 1021401), row.MainMissionIDList());
    assertEquals(1021201, row.MainMissionIDBefore());
    assertEquals(1021501, row.MainMissionIDAfter());
    assertNotNull(MissionRaid.resource(40231001, 0));
  }

  @Test
  void enteringOnlyAcceptsTheFirstStageAndDoesNotFinishOrOpenTheSecond() {
    assertEquals(Retcode.SUCC, new MissionRaid(player).enter(40231001, 0, 0, 0, List.of()));
    var raid = new MissionRaid(player);
    assertEquals(1021301, raid.current().getMainMission());
    assertTrue(raid.activeFor(1021301));
    assertFalse(raid.owns(1021401));
    assertNull(player.getGameplay().getMainMissions().get(1021401));
    assertNull(player.getGameplay().getMainMissions().get(1021501));
    assertFalse(raid.completed(raid.current()));
    assertFalse(raid.current().isCompleted());
    assertEquals(List.of(1021002), player.getCurrentLineup().getAvatars());
  }

  void isolatedCompletion(int sub) {
    var row = Objects.requireNonNull(GameplayDefinitions.subs.get(sub));
    var state = new QuestState(sub);
    state.advance(Math.max(row.getProgress(), 1), Math.max(row.getProgress(), 1), 1);
    player.getGameplay().getSubMissions().put(sub, state);
  }

  void firstTransfer() {
    for (int sub = 102130101; sub <= 102130110; sub++) isolatedCompletion(sub);
    assertEquals(Retcode.SUCC, new MissionRaid(player).enter(40231001, 0, 0, 0, List.of()));
    assertEquals(40231004, player.getEntryId());
    assertEquals(
        Map.of(102130113, 40231004), new MissionRaid(player).current().getTransferredSubs());
    assertTrue(MissionStateRecords.subCompleted(player, 102130113));
    assertEquals(List.of(40231001, 40231004), player.transitions);
  }

  void secondStage() {
    firstTransfer();
    isolatedCompletion(102130111); // Battle outcome is explicitly isolated in this test.
    isolatedCompletion(
        102130112); // Client performance is explicitly isolated, source row unchanged.
    player.getMissionManager().refresh();
    assertEquals(1021401, new MissionRaid(player).current().getMainMission());
  }

  @Test
  void originalTransfersOpenTheSecondMainOnlyAfterTheFirstFinishSubAndCreateItsSourceRole() {
    secondStage();
    var raid = new MissionRaid(player);
    var run = raid.current();
    assertEquals(1, run.getSequence().getStage());
    assertEquals(List.of(1021301, 1021401), run.getSequence().getMains());
    assertEquals(List.of(40231001, 40231004, 40231003), player.transitions);
    assertEquals(Map.of(102130113, 40231004, 102140141, 40231003), run.getTransferredSubs());
    assertTrue(MissionStateRecords.mainCompleted(player, 1021301));
    assertTrue(raid.activeFor(1021401));
    assertFalse(raid.owns(1021301));
    assertTrue(MissionStateRecords.subCompleted(player, 102140142));
    assertEquals(List.of(1021213), player.getCurrentLineup().getAvatars());
    assertInstanceOf(emu.lunarcore.game.trial.TrialAvatar.class, player.getAvatarById(1021213));
    assertNull(player.getMissionManager().sub(102140143));
    assertNull(player.getGameplay().getMainMissions().get(1021501));
    assertFalse(raid.completed(run));
    assertFalse(run.isCompleted());
  }

  @Test
  void missingFirstFinishEvidenceCannotAdvanceOrCompleteTheEntireRaid() {
    firstTransfer();
    player.getGameplay().getMainMissions().get(1021301).advance(1, 1, 1);
    player.getMissionManager().refresh();
    var raid = new MissionRaid(player);
    assertEquals(0, raid.current().getSequence().getStage());
    assertEquals(1021301, raid.current().getMainMission());
    assertNull(player.getGameplay().getMainMissions().get(1021401));
    assertFalse(raid.completed(raid.current()));
    assertFalse(raid.current().isCompleted());
    assertNull(player.getAvatarById(1021213));
  }

  @Test
  void finishedFirstMainAtTheWrongFloorCannotAdvanceTheStage() {
    assertEquals(Retcode.SUCC, new MissionRaid(player).enter(40231001, 0, 0, 0, List.of()));
    isolatedCompletion(102130112);
    player.getMissionManager().refresh();
    assertTrue(MissionStateRecords.mainCompleted(player, 1021301));
    assertEquals(40231001, player.getEntryId());
    assertEquals(0, new MissionRaid(player).current().getSequence().getStage());
    assertNull(player.getGameplay().getMainMissions().get(1021401));
  }

  @Test
  void queriesAndRefreshesDoNotRepeatTransfersOrTrialCreation() {
    secondStage();
    var run = new MissionRaid(player).current();
    String before = source.helper.gson.toJson(run);
    var role = player.getAvatarById(1021213);
    for (int i = 0; i < 4; i++) {
      player.getCurrentLineup().toProto();
      player.getMissionManager().refresh();
    }
    assertEquals(List.of(40231001, 40231004, 40231003), player.transitions);
    assertEquals(before, source.helper.gson.toJson(run));
    assertSame(role, player.getAvatarById(1021213));
  }

  @Test
  void pauseAndReentryRestoreTheSecondStageAndItsTrialRatherThanRestartingTheFirst() {
    secondStage();
    var raid = new MissionRaid(player);
    var run = raid.current();
    var sourceBefore = source.helper.gson.toJson(run.getMissionTrials());
    assertEquals(Retcode.SUCC, raid.leave(40231001, false));
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertEquals(Retcode.SUCC, raid.enter(40231001, 0, 0, 0, List.of()));
    assertEquals(1021401, raid.current().getMainMission());
    assertEquals(1, raid.current().getSequence().getStage());
    assertEquals(40231003, player.getEntryId());
    assertEquals(List.of(1021213), player.getCurrentLineup().getAvatars());
    assertEquals(sourceBefore, source.helper.gson.toJson(run.getMissionTrials()));
    assertEquals(2, run.getTransferredSubs().size());
  }

  @Test
  void completionRequiresBothOriginalMainMissionsAndTheirFinishSubsBeforeTheFinalReturn() {
    secondStage();
    var raid = new MissionRaid(player);
    var run = raid.current();
    for (int sub = 102140101; sub <= 102140105; sub++) isolatedCompletion(sub);
    player.getMissionManager().refresh();
    assertTrue(raid.completed(run));
    assertTrue(run.isCompleted());
    assertNull(run.getFinishedReturn());
    assertFalse(player.getMissionTrialManager().inside());
    assertEquals(Retcode.SUCC, raid.leave(40231001, false));
    assertEquals(2023202, player.getEntryId());
    assertEquals(2023202, run.getFinishedReturn().getEntry());
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    assertNull(player.getGameplay().getMainMissions().get(1021501));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ordinal",
        "source",
        "mainOrder",
        "currentMain",
        "missingPrior",
        "priorProgress",
        "priorCancelled",
        "priorSubCancelled",
        "missingTransferReceipt",
        "missingPriorGate"
      })
  void savedSequenceCannotAuthorizeAStageWithBrokenSourceOrPriorCompletion(String damage)
      throws Exception {
    secondStage();
    var run = new MissionRaid(player).current();
    var sequence = run.getSequence();
    switch (damage) {
      case "ordinal" ->
          MissionTrialProvisioning46Test.setField(sequence, MissionRaidSequence.class, "stage", 0);
      case "source" ->
          MissionTrialProvisioning46Test.setField(
              sequence, MissionRaidSequence.class, "source", "");
      case "mainOrder" -> Collections.reverse(sequence.getMains());
      case "currentMain" ->
          MissionTrialProvisioning46Test.setField(
              run, GameplayState.MissionRaidRun.class, "mainMission", 1021301);
      case "missingPrior" -> player.getGameplay().getMainMissions().remove(1021301);
      case "priorProgress" ->
          MissionTrialProvisioning46Test.setField(
              player.getGameplay().getSubMissions().get(102130112),
              QuestState.class,
              "progress",
              0);
      case "priorCancelled" -> player.getGameplay().getCancelledMissions().add(1021301);
      case "priorSubCancelled" -> player.getGameplay().getCancelledSubs().add(102130112);
      case "missingTransferReceipt" -> run.getTransferredSubs().remove(102130113);
      case "missingPriorGate" -> player.getGameplay().getSubMissions().remove(102130107);
    }
    var raid = new MissionRaid(player);
    assertFalse(raid.owns(1021401));
    assertFalse(raid.active());
    assertFalse(raid.completed(run));
  }

  @ParameterizedTest
  @ValueSource(strings = {"futureMain", "futureSub", "cancelledFuture"})
  void futureStageStateCannotBeSmuggledIntoTheCurrentStage(String damage) {
    firstTransfer();
    switch (damage) {
      case "futureMain" ->
          player.getGameplay().getMainMissions().put(1021401, new QuestState(1021401));
      case "futureSub" -> isolatedCompletion(102140105);
      case "cancelledFuture" -> player.getGameplay().getCancelledMissions().add(1021401);
    }
    assertFalse(new MissionRaid(player).owns(1021301));
    assertFalse(new MissionRaid(player).active());
    assertFalse(new MissionRaid(player).advance());
  }

  @ParameterizedTest
  @ValueSource(strings = {"raid", "takeGate", "finishSub", "transferScript", "trialSub"})
  void changingAuthoredResourcesInvalidatesTheFrozenSequence(String damage) throws Exception {
    secondStage();
    var run = new MissionRaid(player).current();
    switch (damage) {
      case "raid" -> {
        var row =
            source
                .helper
                .gson
                .toJsonTree(GameplayDefinitions.raids.get(4023100100L))
                .getAsJsonObject();
        row.addProperty("FinishEntranceID", 2000101);
        GameplayDefinitions.raids.put(
            4023100100L, source.helper.gson.fromJson(row, GameplayDefinitions.Raid.class));
      }
      case "takeGate" ->
          MissionTrialProvisioning46Test.setField(
              GameData.getMainMissionExcelMap().get(1021401),
              emu.lunarcore.data.excel.MainMissionExcel.class,
              "TakeParam",
              List.of(new emu.lunarcore.data.excel.MainMissionExcel.Gate("Auto", 0)));
      case "finishSub" ->
          MissionTrialProvisioning46Test.setField(
              GameData.getMissionInfos().get(1021301),
              MissionInfo.class,
              "finishSubMissionList",
              new int[] {102130111});
      case "transferScript" ->
          GameplayDefinitions.subs
              .get(102140141)
              .getTask()
              .retainSourceDefinition(
                  com.google.gson.JsonParser.parseString("{\"Type\":\"Mission\"}"));
      case "trialSub" -> GameplayDefinitions.subs.get(102140142).setParamInt1(1021204);
    }
    assertFalse(new MissionRaid(player).owns(1021401));
    assertFalse(new MissionRaid(player).completed(run));
  }

  @Test
  void mixedTransferGraphDoesNotExecuteAsABareRaidTransfer() {
    var sub = GameplayDefinitions.subs.get(102130113);
    var graph =
        com.google.gson.JsonParser.parseString(sub.getTask().getSourceDefinition())
            .getAsJsonObject();
    graph
        .getAsJsonArray("OnStartSequece")
        .get(0)
        .getAsJsonObject()
        .getAsJsonArray("TaskList")
        .add(
            com.google.gson.JsonParser.parseString(
                "{\"$type\":\"RPG.GameCore.ClientFinishMission\"}"));
    sub.getTask().retainSourceDefinition(graph);
    for (int id = 102130101; id <= 102130110; id++) isolatedCompletion(id);
    assertEquals(Retcode.SUCC, new MissionRaid(player).enter(40231001, 0, 0, 0, List.of()));
    assertEquals(40231001, player.getEntryId());
    assertTrue(new MissionRaid(player).current().getTransferredSubs().isEmpty());
    assertFalse(MissionStateRecords.subCompleted(player, 102130113));
    assertNull(player.getGameplay().getMainMissions().get(1021401));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void realPlayerMorphiaDocumentsRestoreBothStageOrdinalsWithoutReexecuting(int stage)
      throws Exception {
    if (stage == 0) firstTransfer();
    else secondStage();
    var expected = source.helper.gson.toJson(player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store =
          dev.morphia.Morphia.createDatastore(client, "multi-stage-source-document-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, QuestState.class, Position.class);
      var document = new emu.lunarcore.game.player.Player();
      MissionTrialProvisioning46Test.set(document, "uid", player.getUid());
      MissionTrialProvisioning46Test.set(document, "gameplay", player.getGameplay());
      store.save(document);
      for (int i = 0; i < 2; i++) {
        var saved =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(expected, source.helper.gson.toJson(saved.getGameplay()));
        var copy = source.helper.fixture.reload(saved.getGameplay());
        source.helper.position(copy, player.getEntryId());
        copy.getMissionTrialManager().restoreOnLogin();
        assertTrue(new MissionRaid(copy).restoreOnLogin());
        assertEquals(stage, new MissionRaid(copy).current().getSequence().getStage());
        assertEquals(
            stage == 0 ? List.of(1021002) : List.of(1021213), copy.getCurrentLineup().getAvatars());
        assertEquals(expected, source.helper.gson.toJson(copy.getGameplay()));
        assertEquals(0, copy.saves);
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void concurrentRefreshesAdvanceOnlyOnceAndRetainOneReceiptForEachTransfer() throws Exception {
    firstTransfer();
    isolatedCompletion(102130111);
    isolatedCompletion(102130112);
    var latch = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 6; i++)
        futures.add(
            pool.submit(
                () -> {
                  try {
                    latch.await();
                  } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                  }
                  player.getMissionManager().refresh();
                }));
      latch.countDown();
      for (var future : futures) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
    var run = new MissionRaid(player).current();
    assertEquals(1, run.getSequence().getStage());
    assertEquals(List.of(40231001, 40231004, 40231003), player.transitions);
    assertEquals(Map.of(102130113, 40231004, 102140141, 40231003), run.getTransferredSubs());
    assertEquals(Set.of(102140142), run.getMissionTrials().getExecuted().keySet());
    assertEquals(List.of(1021213), player.getCurrentLineup().getAvatars());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void allPartyQueryReturnsTheOwnedBaseTrialBeforeCreationAndAfterRetirement(boolean completed) {
    if (completed) {
      secondStage();
      for (int sub = 102140101; sub <= 102140105; sub++) isolatedCompletion(sub);
      player.getMissionManager().refresh();
    } else {
      assertEquals(Retcode.SUCC, new MissionRaid(player).enter(40231001, 0, 0, 0, List.of()));
    }
    assertFalse(player.getMissionTrialManager().inside());
    var expected = player.getCurrentLineup().toProto();
    assertEquals(1021002, expected.getAvatarList().get(0).getId());
    var session = new MissionTrialProvisioning46Test.PartySession(player);
    var packet = new emu.lunarcore.server.packet.send.PacketGetAllLineupDataScRsp(session);
    var all =
        (emu.lunarcore.proto.GetAllLineupDataScRspOuterClass.GetAllLineupDataScRsp)
            packet.getData();
    assertEquals(expected, all.getLineupList().get(0));
    assertEquals(expected.getIndex(), all.getCurIndex());
    assertEquals(List.of(199986), player.getLineupManager().getLineups()[0].getAvatars());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void baseTrialQueriesRetainASelectedOrdinaryIndexOfOne(boolean completed) throws Exception {
    var second = new emu.lunarcore.game.player.lineup.PlayerLineup(player, 1);
    second.getAvatars().add(199986);
    player.getLineupManager().getLineups()[1] = second;
    MissionTrialProvisioning46Test.setField(
        player.getLineupManager(),
        emu.lunarcore.game.player.lineup.LineupManager.class,
        "currentIndex",
        1);
    if (completed) {
      secondStage();
      for (int sub = 102140101; sub <= 102140105; sub++) isolatedCompletion(sub);
      player.getMissionManager().refresh();
    } else assertEquals(Retcode.SUCC, new MissionRaid(player).enter(40231001, 0, 0, 0, List.of()));
    var current = player.getCurrentLineup().toProto();
    assertEquals(1, current.getIndex());
    var packet =
        new emu.lunarcore.server.packet.send.PacketGetAllLineupDataScRsp(
            new MissionTrialProvisioning46Test.PartySession(player));
    var all =
        (emu.lunarcore.proto.GetAllLineupDataScRspOuterClass.GetAllLineupDataScRsp)
            packet.getData();
    assertEquals(1, all.getCurIndex());
    assertEquals(current, all.getLineupList().get(1));
    assertEquals(0, all.getLineupList().get(0).getIndex());
    assertEquals(List.of(199986), second.getAvatars());
  }
}
