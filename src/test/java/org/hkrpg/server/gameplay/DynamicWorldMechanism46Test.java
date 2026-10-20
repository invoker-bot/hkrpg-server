package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real 4.6 boards, pieces and targets; rehydrate the last successfully saved document. */
class DynamicWorldMechanism46Test {
  @TempDir Path root;
  final WorldMechanism46Test fixture = new WorldMechanism46Test();
  Map<Integer, MissionInfo.SubMissionInfo> previousSubs;

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
    previousSubs = new HashMap<>(GameplayDefinitions.subs);
    var raw =
        JsonParser.parseReader(
                new InputStreamReader(
                    getClass().getResourceAsStream("/fixtures/world-mechanisms-4.6.json"),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var entry : raw.getAsJsonObject("missions").entrySet())
      for (var sub : entry.getValue().getAsJsonObject().getAsJsonArray("SubMissionList")) {
        var def = fixture.map.fixture.gson.fromJson(sub, MissionInfo.SubMissionInfo.class);
        GameplayDefinitions.subs.put(def.getId(), def);
      }
  }

  @AfterEach
  void restore() throws Exception {
    if (previousSubs != null) {
      GameplayDefinitions.subs.clear();
      GameplayDefinitions.subs.putAll(previousSubs);
    }
    fixture.restore();
  }

  private MapChest46Test map() {
    return fixture.map;
  }

  private EntityProp actor(int group, int instance) {
    return map().fixture.player.scene.getEntitiesByGroup(EntityProp.class, group).stream()
        .filter(p -> p.getInstId() == instance)
        .findFirst()
        .orElseThrow();
  }

  private Retcode interact(int group, int instance, int config) {
    return ScenePropTransitions.interact(
            map().fixture.player, config, actor(group, instance).getEntityId())
        .retcode();
  }

  private void reload() {
    fixture.reload();
  }

  private void claimAndReload(int id) {
    assertEquals(PropState.ChestClosed, map().chest(id).getState());
    assertEquals(0, map().fixture.player.grants);
    reload();
    assertEquals(Retcode.SUCC, map().open(id));
    reload();
    assertEquals(PropState.ChestUsed, map().chest(id).getState());
    assertNotEquals(Retcode.SUCC, map().open(id));
    assertEquals(1, map().fixture.player.grants);
  }

  @Test
  void boardActivationPartialCollectionAndCompletionSurviveSeparateRestarts() {
    map().enter(10301634);
    assertEquals(Retcode.SUCC, interact(223, 300001, 1210));
    assertEquals(PropState.TriggerEnable, actor(223, 300002).getState());
    assertEquals(PropState.TriggerEnable, actor(223, 300003).getState());
    assertEquals(PropState.Closed, actor(223, 300004).getState());
    reload();
    assertNotEquals(Retcode.SUCC, interact(223, 300001, 11000));
    assertEquals(Retcode.SUCC, interact(223, 300002, 11000));
    reload();
    assertEquals(PropState.Closed, actor(223, 300002).getState());
    assertEquals(PropState.TriggerEnable, actor(223, 300003).getState());
    assertEquals(PropState.Open, actor(223, 300001).getState());
    assertNotEquals(Retcode.SUCC, map().open(10301634));
    assertEquals(Retcode.SUCC, interact(223, 300003, 11000));
    assertEquals(PropState.TriggerEnable, actor(223, 300001).getState());
    reload();
    assertEquals(Retcode.SUCC, interact(223, 300001, 11000));
    claimAndReload(10301634);
  }

  @ParameterizedTest
  @CsvSource({
    "20311667,434",
    "20312659,257",
    "20332664,326",
    "20313661,228",
    "20321662,184",
    "20322663,241"
  })
  void alreadyCollectedBoardsUseTheAuthoredFinishCallback(int chest, int group) {
    map().enter(chest);
    assertEquals(Retcode.SUCC, interact(group, 300001, 11000));
    claimAndReload(chest);
  }

  @Test
  void oneBoardRevealsAllItsExplicitTargetsButNoUnrelatedChest() {
    map().enter(20331663);
    assertEquals(Retcode.SUCC, interact(201, 300001, 11000));
    for (int id : new int[] {20331663, 20331666, 20331667})
      assertEquals(PropState.ChestClosed, map().chest(id).getState());
    reload();
    for (int id : new int[] {20331663, 20331666, 20331667})
      assertEquals(Retcode.SUCC, map().open(id));
    reload();
    for (int id : new int[] {20331663, 20331666, 20331667}) {
      assertEquals(PropState.ChestUsed, map().chest(id).getState());
      assertNotEquals(Retcode.SUCC, map().open(id));
    }
    assertEquals(3, map().fixture.player.grants);
  }

  @ParameterizedTest
  @CsvSource({"20242671,183", "20242678,190", "20242685,197"})
  void commandRobotFinishUnlocksOnlyItsBoundTreasure(int chest, int group) {
    map().enter(chest);
    assertEquals(Retcode.SUCC, interact(group, 300001, 1010));
    int other = chest == 20242671 ? 20242685 : 20242671;
    assertEquals(PropState.Hidden, map().chest(other).getState());
    claimAndReload(chest);
    assertNotEquals(Retcode.SUCC, interact(group, 300001, 1100));
  }

  @ParameterizedTest
  @CsvSource({
    "20121671,51,300004,2010401",
    "20121673,63,300004,2010401",
    "20121674,64,300005,2010402",
    "20121676,65,300005,2010402",
    "20121677,66,300005,2010405"
  })
  void loadedReplacementTramcarControllerFinishesItsMissionChest(
      int chest, int group, int controller, int mission) {
    map().fixture.player.getGameplay().getMainMissions().put(mission, new QuestState(mission));
    if (group == 63) {
      var q = new QuestState(201040101);
      q.advance(1, 1, 1);
      map().fixture.player.getGameplay().getSubMissions().put(201040101, q);
    }
    map().enter(chest);
    assertEquals(Retcode.SUCC, interact(group, controller, 1100));
    claimAndReload(chest);
  }

  @Test
  void activationFailureRollsBackEveryPieceAndAllowsRetry() {
    map().enter(10301634);
    map().fixture.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, interact(223, 300001, 1210));
    assertEquals(PropState.Locked, actor(223, 300001).getState());
    assertEquals(PropState.TriggerDisable, actor(223, 300002).getState());
    assertTrue(map().fixture.player.getGameplay().getScenePropStates().getRecords().isEmpty());
    map().fixture.player.failSave = false;
    assertEquals(Retcode.SUCC, interact(223, 300001, 1210));
    reload();
    assertEquals(PropState.TriggerEnable, actor(223, 300002).getState());
  }

  @Test
  void finalPieceFailureDoesNotLoseThePieceOrActivateTheBoard() {
    map().enter(10301634);
    assertEquals(Retcode.SUCC, interact(223, 300001, 1210));
    assertEquals(Retcode.SUCC, interact(223, 300002, 11000));
    map().fixture.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, interact(223, 300003, 11000));
    map().fixture.player.failSave = false;
    reload();
    assertEquals(PropState.Open, actor(223, 300001).getState());
    assertEquals(PropState.TriggerEnable, actor(223, 300003).getState());
    assertEquals(Retcode.SUCC, interact(223, 300003, 11000));
    assertEquals(PropState.TriggerEnable, actor(223, 300001).getState());
  }

  @Test
  void forgedReadyStateOrMissingPieceCannotSkipCollection() {
    map().enter(10301634);
    actor(223, 300001).setState(PropState.TriggerEnable, false);
    assertNotEquals(Retcode.SUCC, interact(223, 300001, 11000));
    map().fixture.scene();
    assertEquals(Retcode.SUCC, interact(223, 300001, 1210));
    map().fixture.player.scene.removeEntity(actor(223, 300003));
    assertNotEquals(Retcode.SUCC, interact(223, 300002, 11000));
    assertTrue(map().fixture.player.getGameplay().getWorldMechanisms().getRecords().isEmpty());
  }

  @Test
  void pieceOnlyAllowsItsOwnCollectRequest() {
    map().enter(10301634);
    assertNotEquals(Retcode.SUCC, interact(223, 300002, 19100));
    assertNotEquals(Retcode.SUCC, interact(223, 300001, 1200));
    assertEquals(PropState.Hidden, map().chest(10301634).getState());
  }

  @ParameterizedTest
  @CsvSource({"20413666,277,7", "20423666,99,6", "20412666,142,5", "20413667,278,4"})
  void eachAuthoredSpearSequenceAndShieldDestructionSurviveRestart(
      int chest, int group, int steps) {
    map().enter(chest);
    var row =
        JsonParser.parseReader(
                new InputStreamReader(
                    getClass().getResourceAsStream("/fixtures/world-mechanisms-4.6.json"),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    var graph = actor(group, 300002).getPropInfo().getInitLevelGraph();
    // Configs below follow the four different original trajectories, not a generic finish ID.
    int[] chain = {1010, 101101, 1010102, 1020103, 1030104, 1040105, 1050002};
    int last =
        switch (steps) {
          case 4 -> 1020002;
          case 5 -> 1030002;
          case 6 -> 1040002;
          default -> 1050002;
        };
    assertTrue(row.getAsJsonObject("headers").has(graph));
    assertFalse(map().fixture.player.scene.destroyProp(actor(group, 300001)));
    for (int i = 0; i < steps; i++) {
      int config = i == steps - 1 ? last : chain[i];
      assertEquals(Retcode.SUCC, interact(group, 300002, config));
      reload();
      assertEquals(PropState.Hidden, map().chest(chest).getState());
    }
    assertTrue(map().fixture.player.scene.destroyProp(actor(group, 300001)));
    reload();
    assertTrue(
        map().fixture.player.scene.getEntitiesByGroup(EntityProp.class, group).stream()
            .noneMatch(p -> p.getInstId() == 300001));
    claimAndReload(chest);
  }

  @Test
  void aFailedShieldSaveKeepsTheObjectAndAllowsTheSameHitToRetry() {
    map().enter(20413667);
    for (int config : new int[] {1010, 101101, 1010102, 1020002})
      assertEquals(Retcode.SUCC, interact(278, 300002, config));
    var shield = actor(278, 300001);
    map().fixture.player.failSave = true;
    assertFalse(map().fixture.player.scene.destroyProp(shield));
    assertSame(shield, actor(278, 300001));
    map().fixture.player.failSave = false;
    reload();
    assertEquals(PropState.Locked, actor(278, 300002).getState());
    assertTrue(map().fixture.player.scene.destroyProp(actor(278, 300001)));
    claimAndReload(20413667);
  }

  @Test
  void sourceFinishAndAllThreeTreasureUnlocksRollBackTogether() {
    map().enter(20331663);
    map().fixture.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, interact(201, 300001, 11000));
    assertEquals(PropState.TriggerEnable, actor(201, 300001).getState());
    for (int id : new int[] {20331663, 20331666, 20331667})
      assertEquals(PropState.Hidden, map().chest(id).getState());
    assertTrue(map().fixture.player.getGameplay().getWorldMechanisms().getRecords().isEmpty());
    map().fixture.player.failSave = false;
    assertEquals(Retcode.SUCC, interact(201, 300001, 11000));
    reload();
    for (int id : new int[] {20331663, 20331666, 20331667})
      assertEquals(PropState.ChestClosed, map().chest(id).getState());
  }

  @Test
  void replacementControllersDoNotBypassTheirMissionGates() {
    map().enter(20121671);
    assertTrue(map().fixture.player.scene.getEntitiesByGroup(EntityProp.class, 51).isEmpty());
    assertTrue(map().fixture.player.scene.getEntitiesByGroup(EntityProp.class, 65).isEmpty());
  }

  @Test
  void spearRejectsSkippedStepAndForgedFinalState() {
    map().enter(20413667);
    assertNotEquals(Retcode.SUCC, interact(278, 300002, 1020002));
    actor(278, 300002).setState(PropState.Locked, false);
    assertFalse(map().fixture.player.scene.destroyProp(actor(278, 300001)));
    assertEquals(PropState.Hidden, map().chest(20413667).getState());
    assertTrue(map().fixture.player.getGameplay().getWorldMechanisms().getRecords().isEmpty());
  }

  @Test
  void changedSpearSourceCannotAdvanceOrEraseDurableProgress() throws Exception {
    map().enter(20413667);
    assertEquals(Retcode.SUCC, interact(278, 300002, 1010));
    var path = root.resolve(actor(278, 300002).getPropInfo().getInitLevelGraph());
    var before = Files.readString(path);
    var time = Files.getLastModifiedTime(path);
    var after = before.replace("101101", "101102");
    assertNotEquals(before, after);
    Files.writeString(path, after);
    Files.setLastModifiedTime(path, time);
    assertNotEquals(Retcode.SUCC, interact(278, 300002, 101101));
    Files.writeString(path, before);
    reload();
    assertEquals(PropState.Open, actor(278, 300002).getState());
    assertEquals(Retcode.SUCC, interact(278, 300002, 101101));
  }

  @Test
  void everyNewBindingMatchesTheOriginalLoadedFilesAndRows() {
    int checked = 0;
    for (var rule : WorldMechanismSources.RULES.values()) {
      if (!(Set.of("JIGSAW", "SPEAR").contains(rule.kind().name())
          || rule.kind().name().equals("CONTROLLER")
              && rule.files().stream().anyMatch(p -> p.contains("CommandRobotPuzzle")))) continue;
      map().enter(rule.chest());
      assertNotNull(
          WorldMechanismSources.group(rule, map().fixture.player.scene),
          "Invalid source binding for " + rule.chest());
      checked++;
    }
    assertEquals(67, checked);
  }
}
