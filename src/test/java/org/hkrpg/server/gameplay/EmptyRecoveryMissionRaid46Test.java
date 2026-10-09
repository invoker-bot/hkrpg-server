package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original empty recovery and complete composite script; outcomes/scene arrival are explicit
 * fixtures.
 */
class EmptyRecoveryMissionRaid46Test {
  @TempDir Path root;
  final MissionTrialOnlyRaid46Test source = new MissionTrialOnlyRaid46Test();
  WorldActor player;

  static class WorldActor extends MultiStageMissionRaid46Test.SceneActor {
    int world;
    int moves;
    boolean failMove;
    boolean failSave;

    WorldActor(MissionRaidTrialLifecycle46Test helper) {
      super(helper);
    }

    @Override
    public int getWorldLevel() {
      return world;
    }

    @Override
    public void save() {
      if (failSave) throw new IllegalStateException("Owned fixture save failure");
      super.save();
    }

    @Override
    public boolean hasSceneAnchor(int entry, int group, int anchor) {
      return entry == 43315001
          && group == 28
          && anchor == 1
          && fixture
                  .original
                  .getAsJsonObject("records")
                  .getAsJsonArray(
                      "Config/LevelOutput/SharedRuntimeGroup/Groups_P43315_F43315001/LevelGroup_P43315_F43315001_G28.json")
                  .get(0)
                  .getAsJsonObject()
                  .getAsJsonArray("AnchorList")
                  .get(0)
                  .getAsJsonObject()
                  .get("ID")
                  .getAsInt()
              == anchor;
    }

    @Override
    public boolean enterSceneAtAnchor(int entry, int group, int anchor, boolean notify) {
      assertEquals(43315001, entry);
      assertEquals(28, group);
      assertEquals(1, anchor);
      moves++;
      if (failMove) return false;
      var raw =
          fixture
              .original
              .getAsJsonObject("records")
              .getAsJsonArray(
                  "Config/LevelOutput/SharedRuntimeGroup/Groups_P43315_F43315001/LevelGroup_P43315_F43315001_G28.json")
              .get(0)
              .getAsJsonObject()
              .getAsJsonArray("AnchorList")
              .get(0)
              .getAsJsonObject();
      fixture.position(this, entry);
      MissionTrialProvisioning46Test.set(
          this, "pos", fixture.gson.fromJson(raw, AnchorInfo.class).getPos().clone());
      return true;
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    source.root = root;
    source.prepare();
    source.installRecords("empty-recovery-mission-raid-4.6.json");
    var helper = source.helper;
    player = new WorldActor(helper);
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
    for (int main : List.of(1034208)) {
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
    player.getGameplay().getMainMissions().put(1034207, new QuestState(1034207));
    helper.position(player, 2000101);
    player.getCurrentLineup().setMp(2);
  }

  @AfterEach
  void restore() throws Exception {
    source.restore();
  }

  private void enter() {
    assertEquals(Retcode.SUCC, new MissionRaid(player).enter(43315, 0, 0, 0, List.of()));
  }

  private void goal(int id, Set<Integer> found) {
    if (!found.add(id)) return;
    var c = Objects.requireNonNull(GameplayDefinitions.subs.get(id));
    if (!"Auto".equals(c.getTakeType())) for (int gate : c.getTakeParamIntList()) goal(gate, found);
  }

  void beforeVirtual() {
    var ids = new HashSet<Integer>();
    goal(103420814, ids);
    for (int id : ids) {
      var c = GameplayDefinitions.subs.get(id);
      assertTrue(Set.of("Talk", "StageWin").contains(c.getFinishType()));
      var state = new QuestState(id);
      state.advance(1, 1, 123);
      player.getGameplay().getSubMissions().put(id, state);
    }
    player.getMissionManager().refresh();
  }

  @Test
  void originalEmptyRecoveryIsAvailableWithoutRewritingItToUnknown() {
    var row = GameplayDefinitions.raids.get(4331500L);
    assertEquals(List.of(), row.RecoverType());
    assertNotNull(MissionRaid.resource(43315, 0));
    assertEquals(List.of(), GameplayDefinitions.raids.get(4331500L).RecoverType());
  }

  @Test
  void originalEmptyRecoveryPreservesFormalVitalsAndFreezesTheWholeSingleMain() {
    var formal = player.getAvatarById(199986);
    var normal = player.getCurrentLineup();
    int hp = formal.getCurrentHp(normal), sp = formal.getCurrentSp(normal);
    enter();
    assertEquals(43315001, player.getEntryId());
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    assertEquals(2, player.getCurrentLineup().getMp());
    assertEquals(hp, formal.getCurrentHp(normal));
    assertEquals(sp, formal.getCurrentSp(normal));
    var run = new MissionRaid(player).current();
    assertNotNull(run.getSequence());
    assertEquals(List.of(1034208), run.getSequence().getMains());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void wholeOriginalCompositeScriptIsRecognized() {
    var c = GameplayDefinitions.subs.get(103420827);
    var src = TrialAvatarMissionConditions.source(c);
    assertNotNull(src);
    assertEquals("RPG.GameCore.ReplaceVirtualTeam", src.task());
    assertEquals(List.of(1021317), src.targets());
    assertTrue(src.signature().contains("RPG.GameCore.ClientFinishMission"));
  }

  @Test
  void originalCompositeCreatesTheActualCustomTrialAndAllOriginalGoalsReturnHome() {
    enter();
    beforeVirtual();
    assertTrue(MissionStateRecords.subCompleted(player, 103420827));
    assertTrue(MissionStateRecords.subCompleted(player, 103420807));
    assertEquals(List.of(1021317), player.getCurrentLineup().getAvatars());
    assertTrue(player.getCurrentLineup().toProto().getIsVirtual());
    assertEquals(2, player.getCurrentLineup().getMp());
    var actor = (emu.lunarcore.game.trial.TrialAvatar) player.getAvatarById(1021317);
    assertEquals(1317, actor.getActorId());
    assertEquals(40, actor.getLevel());
    assertTrue(actor.getSkillPlan().custom());
    assertEquals(23033, actor.getEquips().get(100).getItemId());
    var last = new QuestState(103420815);
    last.advance(1, 1, 123);
    player.getGameplay().getSubMissions().put(103420815, last);
    player.getMissionManager().refresh();
    var run = new MissionRaid(player).current();
    assertTrue(MissionRaidSequence.finished(player, run, 1034208));
    assertEquals(Retcode.SUCC, new MissionRaid(player).leave(43315, false));
    assertTrue(run.isCompleted());
    assertEquals(2000101, player.getEntryId());
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
    assertEquals(2, player.getCurrentLineup().getMp());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void truncatingTheOriginalCompositeCannotSilentlyRunOnlyTheActorAction() {
    var c = GameplayDefinitions.subs.get(103420827);
    var raw = JsonParser.parseString(c.getTask().getSourceDefinition()).getAsJsonObject();
    raw.getAsJsonArray("OnStartSequece")
        .get(0)
        .getAsJsonObject()
        .getAsJsonArray("TaskList")
        .remove(1);
    var task = source.helper.gson.fromJson(raw, MissionTaskInfo.class);
    task.retainSourceDefinition(raw);
    c.setTask(task);
    assertNull(TrialAvatarMissionConditions.source(c));
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @Test
  void higherPlayerWorldCannotEnterThisFiniteRecoveryScope() {
    player.world = 1;
    int saves = player.saves;
    var before = source.helper.gson.toJson(player.getGameplay());
    assertNotEquals(Retcode.SUCC, new MissionRaid(player).enter(43315, 0, 0, 0, List.of()));
    assertEquals(saves, player.saves);
    assertEquals(before, source.helper.gson.toJson(player.getGameplay()));
  }

  private void pendingCompanion() {
    var ids = new HashSet<Integer>();
    goal(103420814, ids);
    for (int id : ids) {
      var state = new QuestState(id);
      if (id != 103420807) state.advance(1, 1, 123);
      player.getGameplay().getSubMissions().put(id, state);
    }
    player.getMissionManager().refresh();
  }

  @Test
  void secondInstructionActuallyFinishesTheOwnedTargetAndRunsItsOriginalAnchorActionOnce() {
    enter();
    pendingCompanion();
    assertTrue(MissionStateRecords.subCompleted(player, 103420807));
    assertTrue(MissionStateRecords.subCompleted(player, 103420827));
    assertEquals(1, player.moves);
    var action = player.getGameplay().getStoryActions().get(103420807);
    assertNotNull(action);
    assertFalse(action.isPending());
    assertEquals(1, action.getNext());
    assertEquals(List.of(1021317), player.getCurrentLineup().getAvatars());
    var state = source.helper.gson.toJson(player.getGameplay());
    for (int i = 0; i < 3; i++) player.getMissionManager().refresh();
    assertEquals(1, player.moves);
    assertEquals(state, source.helper.gson.toJson(player.getGameplay()));
  }

  @Test
  void failedSecondInstructionRetainsOwnedActorsAndCannotPretendTheWholeScriptFinished() {
    enter();
    player.failMove = true;
    pendingCompanion();
    assertInstanceOf(emu.lunarcore.game.trial.TrialAvatar.class, player.getAvatarById(1021317));
    assertFalse(MissionStateRecords.subCompleted(player, 103420807));
    assertFalse(MissionStateRecords.subCompleted(player, 103420827));
    assertEquals(1, player.moves);
    assertTrue(player.getGameplay().getStoryActions().get(103420807).isPending());
    var before = source.helper.gson.toJson(player.getGameplay());
    for (int i = 0; i < 3; i++) player.getMissionManager().refresh();
    assertEquals(before, source.helper.gson.toJson(player.getGameplay()));
    assertEquals(1, player.moves);
    assertEquals(List.of(1021317), player.getCurrentLineup().getAvatars());
    assertEquals(2, player.getCurrentLineup().getMp());
  }

  @Test
  void failedActorReservationRestoresThePreviousStateBeforeAnythingIsPublished() {
    enter();
    var ids = new HashSet<Integer>();
    goal(103420814, ids);
    for (int id : ids) {
      var state = new QuestState(id);
      state.advance(1, 1, 123);
      player.getGameplay().getSubMissions().put(id, state);
    }
    player.getGameplay().getSubMissions().put(103420827, new QuestState(103420827));
    var before = source.helper.gson.toJson(player.getGameplay());
    int packets = player.packets.size();
    player.failSave = true;
    assertThrows(
        IllegalStateException.class,
        () -> player.getMissionTrialManager().execute(GameplayDefinitions.subs.get(103420827)));
    assertEquals(before, source.helper.gson.toJson(player.getGameplay()));
    assertNull(new MissionRaid(player).current().getMissionTrials().getParty());
    assertTrue(new MissionRaid(player).current().getMissionTrials().getExecuted().isEmpty());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertEquals(packets, player.packets.size());
    assertFalse(MissionStateRecords.subCompleted(player, 103420827));
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "cancelled", "wrongId", "progress", "pendingAction"})
  void anUnownedOrUncertainCompanionCannotAuthorizeActorPublication(String kind) {
    enter();
    var ids = new HashSet<Integer>();
    goal(103420814, ids);
    for (int id : ids) {
      var q = new QuestState(id);
      q.advance(1, 1, 123);
      player.getGameplay().getSubMissions().put(id, q);
    }
    player.getGameplay().getSubMissions().put(103420827, new QuestState(103420827));
    switch (kind) {
      case "missing" -> player.getGameplay().getSubMissions().remove(103420807);
      case "cancelled" -> player.getGameplay().getCancelledSubs().add(103420807);
      case "wrongId" ->
          player.getGameplay().getSubMissions().put(103420807, new QuestState(103420808));
      case "progress" -> {
        var q = new QuestState(103420807);
        q.advance(2, 2, 123);
        player.getGameplay().getSubMissions().put(103420807, q);
      }
      case "pendingAction" -> {
        player.getGameplay().getSubMissions().put(103420807, new QuestState(103420807));
        var cursor = new GameplayState.ActionCursor();
        cursor.reserve();
        player.getGameplay().getStoryActions().put(103420807, cursor);
      }
      default -> fail("Unknown companion fixture");
    }
    var before = source.helper.gson.toJson(player.getGameplay());
    int saves = player.saves;
    assertFalse(player.getMissionTrialManager().execute(GameplayDefinitions.subs.get(103420827)));
    assertEquals(before, source.helper.gson.toJson(player.getGameplay()));
    assertEquals(saves, player.saves);
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"target", "extraTask", "type"})
  void changedWholeCompositeGraphsDoNotPartiallyCreateAnActor(String kind) {
    var c = GameplayDefinitions.subs.get(103420827);
    var raw = JsonParser.parseString(c.getTask().getSourceDefinition()).getAsJsonObject();
    var tasks =
        raw.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList");
    if (kind.equals("target"))
      tasks.get(1).getAsJsonObject().addProperty("SubmissionID", 103420804);
    else if (kind.equals("type"))
      tasks.get(1).getAsJsonObject().addProperty("$type", "RPG.GameCore.FinishPerformanceMission");
    else {
      var extra = new JsonObject();
      extra.addProperty("$type", "RPG.GameCore.UnknownTask");
      tasks.add(extra);
    }
    var task = source.helper.gson.fromJson(raw, MissionTaskInfo.class);
    task.retainSourceDefinition(raw);
    c.setTask(task);
    assertNull(TrialAvatarMissionConditions.source(c));
    enter();
    beforeVirtual();
    assertFalse(MissionStateRecords.subCompleted(player, 103420827));
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertEquals(List.of(199986), player.getCurrentLineup().getAvatars());
  }

  @Test
  void changingTheOriginalRawEntranceClosesAnExistingSingleMainProof() throws Exception {
    enter();
    beforeVirtual();
    var before = source.helper.gson.toJson(player.getGameplay());
    var p = root.resolve("ExcelOutput/RaidConfig.json");
    var rows = JsonParser.parseString(Files.readString(p)).getAsJsonArray();
    rows.get(0).getAsJsonObject().addProperty("EntrancePageBGImagePath", "changed-resource");
    Files.writeString(p, rows.toString());
    assertNotNull(MissionRaid.resource(43315, 0));
    assertFalse(new MissionRaid(player).active());
    assertEquals(before, source.helper.gson.toJson(player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void actualPlayerMorphiaRestoresFormalVirtualPausedPendingAndCompletedStates(int mode)
      throws Exception {
    enter();
    if (mode == 3) {
      player.failMove = true;
      pendingCompanion();
    } else if (mode > 0) beforeVirtual();
    if (mode > 0) player.getCurrentLineup().setMp(1);
    if (mode == 2) assertEquals(Retcode.SUCC, new MissionRaid(player).leave(43315, false));
    if (mode == 4) {
      var last = new QuestState(103420815);
      last.advance(1, 1, 123);
      player.getGameplay().getSubMissions().put(103420815, last);
      player.getMissionManager().refresh();
      assertEquals(Retcode.SUCC, new MissionRaid(player).leave(43315, false));
    }
    var expected = source.helper.gson.toJson(player.getGameplay());
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "empty-recovery-fixture");
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
        var copy = new WorldActor(source.helper);
        MissionTrialProvisioning46Test.set(copy, "gameplay", saved.getGameplay());
        var formal =
            new emu.lunarcore.game.avatar.GameAvatar(GameData.getAvatarExcelMap().get(199986));
        formal.setOwner(copy);
        copy.getAvatars().getAvatars().put(199986, formal);
        copy.getLineupManager().getLineups()[0].getAvatars().add(199986);
        copy.getLineupManager().getLineups()[0].setMp(2);
        source.helper.position(copy, player.getEntryId());
        copy.getMissionTrialManager().restoreOnLogin();
        assertEquals(mode < 2 || mode == 3, new MissionRaid(copy).restoreOnLogin());
        assertEquals(expected, source.helper.gson.toJson(copy.getGameplay()));
        assertEquals(0, copy.saves);
        if (mode == 2)
          assertEquals(Retcode.SUCC, new MissionRaid(copy).enter(43315, 0, 0, 0, List.of()));
        assertEquals(
            mode == 0 || mode == 4 ? List.of(199986) : List.of(1021317),
            copy.getCurrentLineup().getAvatars());
        assertEquals(mode == 0 || mode == 4 ? 2 : 1, copy.getCurrentLineup().getMp());
        assertEquals(2, copy.getLineupManager().getLineups()[0].getMp());
        assertEquals(mode > 0 && mode < 4, copy.getCurrentLineup().toProto().getIsVirtual());
        if (mode == 3) {
          copy.getMissionManager().refresh();
          assertEquals(expected, source.helper.gson.toJson(copy.getGameplay()));
          assertEquals(0, copy.moves);
          assertFalse(MissionStateRecords.subCompleted(copy, 103420827));
          assertTrue(copy.getGameplay().getStoryActions().get(103420807).isPending());
        }
        store.save(document);
      }
    } finally {
      server.shutdownNow();
    }
  }
}
