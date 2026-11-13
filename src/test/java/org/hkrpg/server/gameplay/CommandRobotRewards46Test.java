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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CommandRobotRewards46Test {
  @TempDir Path root;
  final WorldMechanism46Test harness = new WorldMechanism46Test();

  @BeforeEach
  void load() throws Exception {
    harness.root = root;
    harness.map.merge("controller-finish-rewards-4.6.json");
    var raw =
        JsonParser.parseReader(
                new InputStreamReader(
                    getClass().getResourceAsStream("/fixtures/controller-finish-rewards-4.6.json"),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    raw.getAsJsonObject("headers")
        .entrySet()
        .forEach(
            e ->
                harness
                    .map
                    .fixture
                    .source
                    .getAsJsonObject("headers")
                    .add(e.getKey(), e.getValue()));
    harness.load();
  }

  @AfterEach
  void restore() throws Exception {
    harness.restore();
  }

  EntityProp controller(int group) {
    return harness.map.fixture.player.scene.getEntitiesByGroup(EntityProp.class, group).stream()
        .filter(p -> p.getInstId() == 300001)
        .findFirst()
        .orElseThrow();
  }

  Retcode finish(int group) {
    var p = harness.map.fixture.player;
    return ScenePropTransitions.interact(p, 1010, controller(group).getEntityId()).retcode();
  }

  @ParameterizedTest
  @CsvSource({
    "20223671,209",
    "20223672,226",
    "20223673,227",
    "20223674,228",
    "20223675,229",
    "20223676,230",
    "20223677,231",
    "20223678,232",
    "20223679,233"
  })
  void originalControllerFinishRevealsTheBoundChest(int chest, int group) {
    var map = harness.map;
    var p = map.fixture.player;
    map.enter(chest);
    assertEquals(PropState.Hidden, map.chest(chest).getState());
    assertNotEquals(Retcode.SUCC, map.open(chest));
    assertEquals(Retcode.SUCC, finish(group));
    assertEquals(PropState.Open, controller(group).getState());
    assertEquals(PropState.ChestClosed, map.chest(chest).getState());
    assertEquals(0, p.grants);
    int other = chest == 20223671 ? 20223672 : 20223671;
    assertEquals(PropState.Hidden, map.chest(other).getState());
    harness.reload();
    assertEquals(PropState.Open, controller(group).getState());
    assertEquals(PropState.ChestClosed, map.chest(chest).getState());
    assertEquals(
        Retcode.SUCC, SceneInteractions.validateNative(p, 120120, map.chest(chest).getEntityId()));
    assertEquals(
        Retcode.SUCC, WorldChests.open(p, 120120, map.chest(chest).getEntityId()).retcode());
    assertEquals(0, p.grants);
    assertEquals(Retcode.SUCC, map.open(chest));
    harness.reload();
    assertEquals(PropState.ChestUsed, map.chest(chest).getState());
    assertNotEquals(Retcode.SUCC, map.open(chest));
    assertNotEquals(
        Retcode.SUCC, WorldChests.open(p, 120120, map.chest(chest).getEntityId()).retcode());
    assertEquals(1, p.grants);
  }

  @Test
  void failedSaveRollsBackControllerAndRewardTogether() {
    var map = harness.map;
    var p = map.fixture.player;
    map.enter(20223671);
    p.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, finish(209));
    assertEquals(PropState.Closed, controller(209).getState());
    assertEquals(PropState.Hidden, map.chest(20223671).getState());
    assertFalse(p.getGameplay().getWorldMechanisms().completed(20223671));
    p.failSave = false;
    assertEquals(Retcode.SUCC, finish(209));
    harness.reload();
    assertEquals(PropState.ChestClosed, map.chest(20223671).getState());
  }

  @Test
  void aChangedFinishScriptCannotAdvanceTheControllerOrTheChest() throws Exception {
    var map = harness.map;
    map.enter(20223671);
    var path =
        root.resolve(
            "Config/Level/Maze/MazeExtra/CommandRobotPuzzle/CommandRobotPuzzle_Controller.json");
    var before = Files.readString(path);
    var timestamp = Files.getLastModifiedTime(path);
    var changed = before.replace("1010", "1011");
    assertNotEquals(before, changed);
    assertEquals(before.length(), changed.length());
    Files.writeString(path, changed);
    Files.setLastModifiedTime(path, timestamp);
    assertNotEquals(Retcode.SUCC, finish(209));
    assertEquals(PropState.Closed, controller(209).getState());
    assertEquals(PropState.Hidden, map.chest(20223671).getState());
    Files.writeString(path, before);
    assertEquals(Retcode.SUCC, finish(209));
    assertEquals(PropState.ChestClosed, map.chest(20223671).getState());
  }

  @Test
  void aChangedFinishInteractionCannotAdvanceTheControllerOrTheChest() throws Exception {
    var map = harness.map;
    map.enter(20223671);
    var path = root.resolve("ExcelOutput/InteractConfig.json");
    var before = Files.readString(path);
    var timestamp = Files.getLastModifiedTime(path);
    var changed = before.replace("1010", "1011");
    assertNotEquals(before, changed);
    assertEquals(before.length(), changed.length());
    Files.writeString(path, changed);
    Files.setLastModifiedTime(path, timestamp);
    assertNotEquals(Retcode.SUCC, finish(209));
    assertEquals(PropState.Closed, controller(209).getState());
    assertEquals(PropState.Hidden, map.chest(20223671).getState());
    Files.writeString(path, before);
    assertEquals(Retcode.SUCC, finish(209));
  }

  @Test
  void legacySavedControllerRecoversItsOwnUnclaimedChest() {
    var map = harness.map;
    var p = map.fixture.player;
    map.enter(20223671);
    assertEquals(Retcode.SUCC, finish(209));
    p.getGameplay().worldMechanisms(new WorldMechanismState());
    p.save();
    harness.reload();
    assertEquals(PropState.Hidden, map.chest(20223671).getState());
    p.failSave = true;
    ControllerFinishRewards.recover(p);
    assertEquals(PropState.Hidden, map.chest(20223671).getState());
    assertFalse(p.getGameplay().getWorldMechanisms().completed(20223671));
    p.failSave = false;
    ControllerFinishRewards.recover(p);
    assertEquals(PropState.ChestClosed, map.chest(20223671).getState());
    assertEquals(PropState.Hidden, map.chest(20223672).getState());
    assertEquals(0, p.grants);
    harness.reload();
    assertEquals(Retcode.SUCC, map.open(20223671));
    ControllerFinishRewards.recover(p);
    assertEquals(1, p.grants);
  }
}
