package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.entity.EntityMonster;
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

class WorldMechanism46Test {
  @TempDir Path root;
  final MapChest46Test map = new MapChest46Test();

  @BeforeEach
  void load() throws Exception {
    map.root = root;
    map.merge("time-relay-flame-4.6.json");
    map.merge("golden-spear-4.6.json");
    map.merge("world-mechanisms-4.6.json");
    var extra =
        JsonParser.parseReader(
                new InputStreamReader(
                    getClass().getResourceAsStream("/fixtures/world-mechanisms-4.6.json"),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    extra
        .getAsJsonObject("headers")
        .entrySet()
        .forEach(e -> map.fixture.source.getAsJsonObject("headers").add(e.getKey(), e.getValue()));
    map.load();
  }

  @AfterEach
  void restore() throws Exception {
    map.restore();
  }

  EntityMonster monster(int instance) {
    return map.fixture.player.scene.getEntitiesByGroup(EntityMonster.class, 11).stream()
        .filter(m -> m.getInstId() == instance)
        .findFirst()
        .orElseThrow();
  }

  void reload() {
    var player = map.fixture.player;
    assertNotNull(player.saved);
    // Read the last durable document, not the current in-memory object.
    try {
      var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      field.setAccessible(true);
      field.set(
          player,
          map.fixture.gson.fromJson(map.fixture.gson.toJson(player.saved), GameplayState.class));
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
    map.fixture.scene();
  }

  @Test
  void allGuardsAreRequiredAndEachDefeatSurvivesReload() {
    assertEquals(2, map.fixture.player.scene.getEntitiesByGroup(EntityMonster.class, 11).size());
    assertTrue(map.fixture.player.scene.defeatMonster(monster(200001)));
    assertEquals(PropState.ChestLocked, map.chest(20001632).getState());
    assertNotEquals(Retcode.SUCC, map.open(20001632));
    reload();
    assertEquals(1, map.fixture.player.scene.getEntitiesByGroup(EntityMonster.class, 11).size());
    assertEquals(200002, monster(200002).getInstId());
    assertTrue(map.fixture.player.scene.defeatMonster(monster(200002)));
    assertEquals(PropState.ChestClosed, map.chest(20001632).getState());
    reload();
    assertTrue(map.fixture.player.scene.getEntitiesByGroup(EntityMonster.class, 11).isEmpty());
    assertEquals(PropState.ChestClosed, map.chest(20001632).getState());
    assertEquals(Retcode.SUCC, map.open(20001632));
    reload();
    assertEquals(PropState.ChestUsed, map.chest(20001632).getState());
    assertNotEquals(Retcode.SUCC, map.open(20001632));
    assertEquals(1, map.fixture.player.grants);
  }

  @Test
  void unloadingGuardIsNotAChallengeVictory() {
    map.fixture.player.scene.removeEntity(monster(200001));
    assertTrue(map.fixture.player.scene.defeatMonster(monster(200002)));
    assertEquals(PropState.ChestLocked, map.chest(20001632).getState());
    reload();
    assertEquals(200001, monster(200001).getInstId());
    assertEquals(PropState.ChestLocked, map.chest(20001632).getState());
  }

  @Test
  void saveFailureDoesNotRemoveGuardOrUnlockChest() {
    var guard = monster(200001);
    map.fixture.player.failSave = true;
    assertFalse(map.fixture.player.scene.defeatMonster(guard));
    assertSame(guard, monster(200001));
    assertEquals(PropState.ChestLocked, map.chest(20001632).getState());
    map.fixture.player.failSave = false;
    assertTrue(map.fixture.player.scene.defeatMonster(guard));
    reload();
    assertEquals(1, map.fixture.player.scene.getEntitiesByGroup(EntityMonster.class, 11).size());
  }

  @ParameterizedTest
  @CsvSource({"20111656,300020", "20211656,300001", "20241651,300001"})
  void sourcePuzzleFinishUnlocksOnlyItsChestAndPersistsBeforeReward(int id, int instance) {
    map.enter(id);
    assertNotEquals(Retcode.SUCC, map.open(id));
    int group = map.chest(id).getGroupId();
    var controller =
        map.fixture.player.scene.getEntitiesByGroup(EntityProp.class, group).stream()
            .filter(p -> p.getInstId() == instance)
            .findFirst()
            .orElseThrow();
    assertEquals(
        Retcode.SUCC,
        SceneInteractions.validateNative(map.fixture.player, 1010, controller.getEntityId()));
    assertNotNull(map.fixture.player.interactWithProp(1010, controller.getEntityId()));
    assertEquals(PropState.ChestClosed, map.chest(id).getState());
    assertEquals(0, map.fixture.player.grants);
    reload();
    assertEquals(PropState.ChestClosed, map.chest(id).getState());
    assertEquals(Retcode.SUCC, map.open(id));
    reload();
    assertEquals(PropState.ChestUsed, map.chest(id).getState());
    assertEquals(1, map.fixture.player.grants);
  }

  @ParameterizedTest
  @CsvSource({"10301656,111120", "10401651,111120", "10403660,120120"})
  void exactClientFinishReportIsDurableAndDoesNotGrantLoot(int id, int interaction) {
    map.enter(id);
    assertNotEquals(Retcode.SUCC, map.open(id));
    assertEquals(
        Retcode.SUCC,
        WorldChests.open(map.fixture.player, interaction, map.chest(id).getEntityId()).retcode());
    assertEquals(0, map.fixture.player.grants);
    reload();
    assertEquals(PropState.ChestClosed, map.chest(id).getState());
    assertEquals(Retcode.SUCC, map.open(id));
    reload();
    assertEquals(PropState.ChestUsed, map.chest(id).getState());
    assertNotEquals(Retcode.SUCC, map.open(id));
    assertEquals(1, map.fixture.player.grants);
  }

  @Test
  void aGuardChestCannotUseAPuzzleReportOrAManuallyChangedPropState() {
    assertNotEquals(
        Retcode.SUCC,
        WorldChests.open(map.fixture.player, 111120, map.chest(20001632).getEntityId()).retcode());
    map.chest(20001632).setState(PropState.ChestClosed, false);
    assertNotEquals(Retcode.SUCC, map.open(20001632));
    assertTrue(map.fixture.player.getGameplay().getWorldMechanisms().getRecords().isEmpty());
    assertEquals(0, map.fixture.player.grants);
  }

  @Test
  void clientUnlockSaveFailurePreservesThePuzzleForRetry() {
    map.enter(10301656);
    map.fixture.player.failSave = true;
    assertEquals(
        Retcode.SYSTEM_BUSY,
        WorldChests.open(map.fixture.player, 111120, map.chest(10301656).getEntityId()).retcode());
    assertEquals(PropState.ChestLocked, map.chest(10301656).getState());
    assertTrue(map.fixture.player.getGameplay().getWorldMechanisms().getRecords().isEmpty());
    map.fixture.player.failSave = false;
    assertEquals(
        Retcode.SUCC,
        WorldChests.open(map.fixture.player, 111120, map.chest(10301656).getEntityId()).retcode());
    reload();
    assertEquals(PropState.ChestClosed, map.chest(10301656).getState());
    assertEquals(0, map.fixture.player.grants);
  }

  @Test
  void controllerAndUnlockRollBackTogetherWhenTheSaveFails() {
    map.enter(20111656);
    var controller =
        map.fixture.player.scene.getEntitiesByGroup(EntityProp.class, 53).stream()
            .filter(p -> p.getInstId() == 300020)
            .findFirst()
            .orElseThrow();
    map.fixture.player.failSave = true;
    assertNull(map.fixture.player.interactWithProp(1010, controller.getEntityId()));
    assertEquals(PropState.Closed, controller.getState());
    assertEquals(PropState.Hidden, map.chest(20111656).getState());
    assertTrue(map.fixture.player.getGameplay().getWorldMechanisms().getRecords().isEmpty());
    assertTrue(map.fixture.player.getGameplay().getScenePropStates().getRecords().isEmpty());
    map.fixture.player.failSave = false;
    assertNotNull(map.fixture.player.interactWithProp(1010, controller.getEntityId()));
    reload();
    assertEquals(PropState.ChestClosed, map.chest(20111656).getState());
  }

  @Test
  void finalGuardSaveFailureKeepsThePreviousPartialProgress() {
    assertTrue(map.fixture.player.scene.defeatMonster(monster(200001)));
    map.fixture.player.failSave = true;
    assertFalse(map.fixture.player.scene.defeatMonster(monster(200002)));
    map.fixture.player.failSave = false;
    reload();
    assertEquals(1, map.fixture.player.scene.getEntitiesByGroup(EntityMonster.class, 11).size());
    assertEquals(PropState.ChestLocked, map.chest(20001632).getState());
    assertTrue(map.fixture.player.scene.defeatMonster(monster(200002)));
    assertEquals(Retcode.SUCC, map.open(20001632));
  }

  @Test
  void completedPuzzleCannotRelockOrReopenAClaimedChest() {
    map.enter(10301656);
    int entity = map.chest(10301656).getEntityId();
    assertEquals(Retcode.SUCC, WorldChests.open(map.fixture.player, 111120, entity).retcode());
    assertNotEquals(Retcode.SUCC, WorldChests.open(map.fixture.player, 111120, entity).retcode());
    assertEquals(Retcode.SUCC, map.open(10301656));
    for (int config : new int[] {111120, 120120, 112110, 112131})
      assertNotEquals(Retcode.SUCC, WorldChests.open(map.fixture.player, config, entity).retcode());
    assertEquals(PropState.ChestUsed, map.chest(10301656).getState());
    assertEquals(1, map.fixture.player.grants);
  }

  @Test
  void changedGraphWithEqualLengthAndTimestampInvalidatesCachedCompletion() throws Exception {
    map.enter(10301656);
    assertEquals(
        Retcode.SUCC,
        WorldChests.open(map.fixture.player, 111120, map.chest(10301656).getEntityId()).retcode());
    var path =
        root.resolve("Config/Level/Props/Chap03/InitLevelGraph_Prop_Chap03_ClockToyBox_01.json");
    var old = Files.readString(path);
    var time = Files.getLastModifiedTime(path);
    var changed = old.replace("111120", "111121");
    assertNotEquals(old, changed);
    assertEquals(old.length(), changed.length());
    Files.writeString(path, changed);
    Files.setLastModifiedTime(path, time);
    assertNotEquals(Retcode.SUCC, map.open(10301656));
    map.fixture.scene();
    assertEquals(PropState.ChestLocked, map.chest(10301656).getState());
    Files.writeString(path, old);
    map.fixture.scene();
    assertEquals(PropState.ChestClosed, map.chest(10301656).getState());
    assertEquals(Retcode.SUCC, map.open(10301656));
  }

  @Test
  void invalidJournalCannotDisappearGuardsOrUnlockTheChest() {
    assertTrue(map.fixture.player.scene.defeatMonster(monster(200001)));
    var json =
        map.fixture
            .gson
            .toJsonTree(map.fixture.player.getGameplay().getWorldMechanisms())
            .getAsJsonObject();
    json.getAsJsonObject("records").getAsJsonObject("20001632").addProperty("complete", true);
    map.fixture
        .player
        .getGameplay()
        .worldMechanisms(map.fixture.gson.fromJson(json, WorldMechanismState.class));
    map.fixture.scene();
    assertEquals(2, map.fixture.player.scene.getEntitiesByGroup(EntityMonster.class, 11).size());
    assertEquals(PropState.ChestLocked, map.chest(20001632).getState());
    assertFalse(map.fixture.player.scene.defeatMonster(monster(200002)));
    assertNotEquals(Retcode.SUCC, map.open(20001632));
  }

  @Test
  void completedPuzzleFromThePreviousMapDoesNotAuthorizeAnotherEntity() {
    map.enter(10301656);
    var old = map.chest(10301656);
    assertEquals(
        Retcode.SUCC, WorldChests.open(map.fixture.player, 111120, old.getEntityId()).retcode());
    map.enter(10401651);
    assertNotEquals(Retcode.SUCC, map.open(10401651));
    assertEquals(PropState.ChestLocked, map.chest(10401651).getState());
    assertEquals(0, map.fixture.player.grants);
  }
}
