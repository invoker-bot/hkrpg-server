package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OraclePuzzleReward46Test {
  @TempDir Path root;
  final WorldMechanism46Test harness = new WorldMechanism46Test();

  @BeforeEach
  void load() throws Exception {
    harness.root = root;
    harness.map.merge("oracle-puzzles-4.6.json");
    var raw =
        JsonParser.parseReader(
                new InputStreamReader(
                    getClass().getResourceAsStream("/fixtures/oracle-puzzles-4.6.json"),
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

  WorldGroupProperties.Result finish(int group, String name, int value) {
    var p = harness.map.fixture.player;
    return WorldGroupProperties.change(p, p.floor, 0, group, name, value);
  }

  @ParameterizedTest
  @CsvSource({
    "10403660,137,FSV_OracleMachine_Chest_1",
    "10403661,139,FSV_OracleMachine_Chest_2",
    "20432661,183,FSV_OracleMachine_FanAppear",
    "20432662,181,FSV_OracleMachine_Chest_2",
    "20482660,102,FSV_OracleMachine_Chest_1"
  })
  void authoredFinishSavesTheMachineAndUnlocksOnlyItsOwnTreasure(
      int chest, int group, String floorValue) {
    var map = harness.map;
    var p = map.fixture.player;
    map.enter(chest);
    assertEquals(PropState.Hidden, map.chest(chest).getState());
    assertNotEquals(Retcode.SUCC, map.open(chest));
    assertEquals(0, p.grants);
    var result = finish(group, "SGP_PuzzleState", 1);
    assertEquals(Retcode.SUCC, result.retcode());
    assertEquals(0, result.oldValue());
    assertEquals(1, result.newValue());
    assertEquals(PropState.ChestClosed, map.chest(chest).getState());
    assertEquals(1, p.getGameplay().getStoryValues().get("floor:" + p.floor + ":" + floorValue));
    assertEquals(0, p.grants, "Finishing the puzzle must not claim the chest");
    int other = chest == 10403660 ? 10403661 : chest == 20432661 ? 20432662 : 0;
    if (other != 0) assertEquals(PropState.Hidden, map.chest(other).getState());
    harness.reload();
    assertEquals(PropState.ChestClosed, map.chest(chest).getState());
    assertEquals(1, p.scene.getGroups().get(group).getProperties().getInt("SGP_PuzzleState"));
    assertEquals(Retcode.SUCC, finish(group, "SGP_PuzzleState", 1).retcode());
    assertEquals(
        Retcode.SUCC, WorldChests.open(p, 120120, map.chest(chest).getEntityId()).retcode());
    assertEquals(0, p.grants);
    assertEquals(Retcode.SUCC, map.open(chest));
    harness.reload();
    assertEquals(PropState.ChestUsed, map.chest(chest).getState());
    assertNotEquals(Retcode.SUCC, map.open(chest));
    assertEquals(1, p.grants);
  }

  @Test
  void aFailedSaveDoesNotPublishOrConsumeTheCompletion() {
    var map = harness.map;
    var p = map.fixture.player;
    map.enter(10403660);
    p.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, finish(137, "SGP_PuzzleState", 1).retcode());
    assertEquals(PropState.Hidden, map.chest(10403660).getState());
    assertEquals(0, p.scene.getGroups().get(137).getProperties().getInt("SGP_PuzzleState"));
    assertNull(p.getGameplay().getStoryValues().get("floor:10403001:FSV_OracleMachine_Chest_1"));
    assertFalse(p.getGameplay().getWorldMechanisms().completed(10403660));
    p.failSave = false;
    assertEquals(Retcode.SUCC, finish(137, "SGP_PuzzleState", 1).retcode());
    harness.reload();
    assertEquals(PropState.ChestClosed, map.chest(10403660).getState());
  }

  @Test
  void aLateChestReportAlsoPersistsTheMachineState() {
    var map = harness.map;
    var p = map.fixture.player;
    map.enter(10403660);
    assertEquals(
        Retcode.SUCC, WorldChests.open(p, 120120, map.chest(10403660).getEntityId()).retcode());
    assertEquals(1, p.scene.getGroups().get(137).getProperties().getInt("SGP_PuzzleState"));
    assertEquals(
        1, p.getGameplay().getStoryValues().get("floor:10403001:FSV_OracleMachine_Chest_1"));
    harness.reload();
    assertEquals(PropState.ChestClosed, map.chest(10403660).getState());
    assertEquals(1, p.scene.getGroups().get(137).getProperties().getInt("SGP_PuzzleState"));
    assertEquals(0, p.grants);
  }

  @Test
  void changedControllerSourceCannotUnlockTheOriginalChest() throws Exception {
    var map = harness.map;
    var p = map.fixture.player;
    map.enter(10403660);
    var path =
        root.resolve(
            "Config/LevelOutput/SharedRuntimeGroup/Groups_P10403_F10403001/LevelGroup_P10403_F10403001_G137.json");
    var before = Files.readString(path);
    var timestamp = Files.getLastModifiedTime(path);
    var changed = before.replace("FSV_OracleMachine_Chest_1", "FSV_OracleMachine_Chest_2");
    assertNotEquals(before, changed);
    assertEquals(before.length(), changed.length());
    Files.writeString(path, changed);
    Files.setLastModifiedTime(path, timestamp);
    assertNotEquals(Retcode.SUCC, finish(137, "SGP_PuzzleState", 1).retcode());
    assertFalse(p.getGameplay().getWorldMechanisms().completed(10403660));
    assertEquals(PropState.Hidden, map.chest(10403660).getState());
    Files.writeString(path, before);
    assertEquals(Retcode.SUCC, finish(137, "SGP_PuzzleState", 1).retcode());
  }

  @Test
  void invalidPropertyDoesNotFinishOrAlterThePuzzle() {
    var map = harness.map;
    var p = map.fixture.player;
    map.enter(10403660);
    assertNotEquals(Retcode.SUCC, finish(137, "SGP_PuzzleState", 2).retcode());
    assertNotEquals(Retcode.SUCC, finish(137, "Unrelated", 1).retcode());
    assertNotEquals(Retcode.SUCC, finish(138, "SGP_PuzzleState", 1).retcode());
    assertEquals(PropState.Hidden, map.chest(10403660).getState());
    assertFalse(p.getGameplay().getWorldMechanisms().completed(10403660));
    assertEquals(0, p.grants);
  }

  @Test
  void legacyChestCompletionRestoresTheMatchingMachineWithoutASecondReward() {
    var map = harness.map;
    var p = map.fixture.player;
    map.enter(10403660);
    assertEquals(Retcode.SUCC, WorldMechanisms.unlock(p, 10403660, map.chest(10403660)).retcode());
    harness.reload();
    assertEquals(0, p.scene.getGroups().get(137).getProperties().getInt("SGP_PuzzleState"));
    p.failSave = true;
    OraclePuzzleRewards.recover(p);
    assertEquals(0, p.scene.getGroups().get(137).getProperties().getInt("SGP_PuzzleState"));
    assertNull(p.getGameplay().getStoryValues().get("floor:10403001:FSV_OracleMachine_Chest_1"));
    p.failSave = false;
    OraclePuzzleRewards.recover(p);
    assertEquals(1, p.scene.getGroups().get(137).getProperties().getInt("SGP_PuzzleState"));
    assertEquals(
        1, p.getGameplay().getStoryValues().get("floor:10403001:FSV_OracleMachine_Chest_1"));
    assertEquals(0, p.grants);
    harness.reload();
    assertEquals(PropState.ChestClosed, map.chest(10403660).getState());
    assertEquals(1, p.scene.getGroups().get(137).getProperties().getInt("SGP_PuzzleState"));
    assertEquals(Retcode.SUCC, map.open(10403660));
    OraclePuzzleRewards.recover(p);
    assertEquals(1, p.grants);
  }
}
