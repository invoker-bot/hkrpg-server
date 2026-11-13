package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RobotHuntCompletion46Test {
  @TempDir Path root;
  final WorldMechanism46Test harness = new WorldMechanism46Test();

  @BeforeEach
  void load() throws Exception {
    harness.root = root;
    harness.map.merge("robot-hunt-4.6.json");
    var source = JsonParser.parseReader(new InputStreamReader(
        getClass().getResourceAsStream("/fixtures/robot-hunt-4.6.json"),
        StandardCharsets.UTF_8)).getAsJsonObject();
    source.getAsJsonObject("headers").entrySet().forEach(e ->
        harness.map.fixture.source.getAsJsonObject("headers").add(e.getKey(), e.getValue()));
    harness.load();
  }

  @AfterEach
  void restore() throws Exception {
    harness.restore();
  }

  EntityProp actor(int group, int instance) {
    return harness.map.fixture.player.scene.getEntitiesByGroup(EntityProp.class, group).stream()
        .filter(p -> p.getInstId() == instance).findFirst().orElseThrow();
  }

  Retcode interact(int group, int instance) {
    return ScenePropTransitions.interact(harness.map.fixture.player, 1010,
        actor(group, instance).getEntityId()).retcode();
  }

  @ParameterizedTest
  @CsvSource({"20111654,47", "20121655,96", "20122655,96", "20131657,67",
      "20131658,69", "20132653,57", "20132654,58"})
  void recordedRobotFinishRevealsOnlyItsChestAndClaimRemainsSeparate(int chest, int group) {
    var map = harness.map;
    map.enter(chest);
    assertEquals(PropState.Hidden, map.chest(chest).getState());
    assertEquals(Retcode.SUCC, interact(group, 300002));
    assertEquals(PropState.Open, actor(group, 300002).getState());
    assertEquals(PropState.Hidden, map.chest(chest).getState());
    assertEquals(0, map.fixture.player.grants);
    assertEquals(Retcode.SUCC, interact(group, 300007));
    assertEquals(PropState.ChestClosed, map.chest(chest).getState());
    assertEquals(0, map.fixture.player.grants);
    if (chest == 20131657 || chest == 20131658)
      assertEquals(PropState.Hidden, map.chest(chest == 20131657 ? 20131658 : 20131657).getState());
    harness.reload();
    assertEquals(PropState.Open, actor(group, 300002).getState());
    assertEquals(PropState.ChestClosed, map.chest(chest).getState());
    assertEquals(Retcode.SUCC,
        SceneInteractions.validateNative(map.fixture.player, 120120, map.chest(chest).getEntityId()));
    assertEquals(Retcode.SUCC,
        WorldChests.open(map.fixture.player, 120120, map.chest(chest).getEntityId()).retcode());
    assertEquals(0, map.fixture.player.grants);
    assertEquals(Retcode.SUCC, map.open(chest));
    assertEquals(1, map.fixture.player.grants);
    harness.reload();
    assertEquals(PropState.ChestUsed, map.chest(chest).getState());
    assertNotEquals(Retcode.SUCC, map.open(chest));
    assertNotEquals(Retcode.SUCC,
        WorldChests.open(map.fixture.player, 120120, map.chest(chest).getEntityId()).retcode());
    assertEquals(1, map.fixture.player.grants);
  }

  @Test
  void endpointCannotBeRecordedBeforeTheRobotIsRepaired() {
    var map = harness.map;
    map.enter(20122655);
    assertNotEquals(Retcode.SUCC, interact(96, 300007));
    assertEquals(PropState.Closed, actor(96, 300007).getState());
    assertEquals(PropState.Hidden, map.chest(20122655).getState());
    assertEquals(Retcode.SUCC, interact(96, 300002));
    assertEquals(Retcode.SUCC, interact(96, 300007));
    assertEquals(PropState.ChestClosed, map.chest(20122655).getState());
  }

  @Test
  void failedFinishSaveLeavesTheEndpointAndChestRetryable() {
    var map = harness.map;
    map.enter(20122655);
    assertEquals(Retcode.SUCC, interact(96, 300002));
    map.fixture.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, interact(96, 300007));
    assertEquals(PropState.Closed, actor(96, 300007).getState());
    assertEquals(PropState.Hidden, map.chest(20122655).getState());
    assertFalse(map.fixture.player.getGameplay().getWorldMechanisms().completed(20122655));
    map.fixture.player.failSave = false;
    assertEquals(Retcode.SUCC, interact(96, 300007));
    harness.reload();
    assertEquals(PropState.ChestClosed, map.chest(20122655).getState());
  }

  @Test
  void oldSavedRepairAndEndpointCanRecoverWithoutRepeatingTheChallenge() {
    var map = harness.map;
    map.enter(20122655);
    assertEquals(Retcode.SUCC, interact(96, 300002));
    assertEquals(Retcode.SUCC, interact(96, 300007));
    // Model a document saved by the old server before its late 120120 request arrived.
    map.fixture.player.getGameplay().worldMechanisms(new WorldMechanismState());
    map.fixture.player.save();
    harness.reload();
    assertEquals(PropState.Hidden, map.chest(20122655).getState());
    map.fixture.player.failSave = true;
    RobotHunts.recover(map.fixture.player);
    assertEquals(PropState.Hidden, map.chest(20122655).getState());
    assertFalse(map.fixture.player.getGameplay().getWorldMechanisms().completed(20122655));
    map.fixture.player.failSave = false;
    RobotHunts.recover(map.fixture.player);
    assertEquals(PropState.ChestClosed, map.chest(20122655).getState());
    assertEquals(0, map.fixture.player.grants);
    harness.reload();
    assertEquals(PropState.ChestClosed, map.chest(20122655).getState());
    assertEquals(Retcode.SUCC, map.open(20122655));
    assertEquals(1, map.fixture.player.grants);
  }

  @Test
  void recoveryRejectsAnEndpointWithoutTheSavedRepair() {
    var map = harness.map;
    map.enter(20122655);
    var p = map.fixture.player;
    var scene = p.scene;
    var marker = actor(96, 300007);
    var record = new ScenePropState.Snapshot(scene.getPlaneId(), scene.getFloorId(),
        scene.getDimensionId(), scene.getStoryLineId(), 96, 300007, 100,
        PropState.Open.getVal(), PropTimelines.source(marker.getPropInfo()), "");
    p.getGameplay().scenePropStates(p.getGameplay().getScenePropStates().with(List.of(record)));
    p.save();
    harness.reload();
    assertEquals(PropState.Open, actor(96, 300007).getState());
    RobotHunts.recover(p);
    assertEquals(PropState.Hidden, map.chest(20122655).getState());
    assertFalse(p.getGameplay().getWorldMechanisms().completed(20122655));
  }

  @Test
  void changingThePinnedOriginalGroupDoesNotUnlockTheChest() throws Exception {
    var map = harness.map;
    map.enter(20122655);
    assertEquals(Retcode.SUCC, interact(96, 300002));
    var group = root.resolve(MapChestSources.LOCATIONS.get(20122655).path());
    var before = Files.readString(group);
    var time = Files.getLastModifiedTime(group);
    var changed = before.replace("96,300004", "96,300005");
    assertNotEquals(before, changed);
    assertEquals(before.length(), changed.length());
    Files.writeString(group, changed);
    Files.setLastModifiedTime(group, time);
    assertNotEquals(Retcode.SUCC, interact(96, 300007));
    assertEquals(PropState.Hidden, map.chest(20122655).getState());
    assertFalse(map.fixture.player.getGameplay().getWorldMechanisms().completed(20122655));
    Files.writeString(group, before);
    assertEquals(Retcode.SUCC, interact(96, 300007));
    assertEquals(PropState.ChestClosed, map.chest(20122655).getState());
  }
}
