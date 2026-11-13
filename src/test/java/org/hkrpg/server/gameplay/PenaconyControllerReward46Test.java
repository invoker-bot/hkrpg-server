package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PenaconyControllerReward46Test {
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

  EntityProp actor(int group, int instance) {
    return harness.map.fixture.player.scene.getEntitiesByGroup(EntityProp.class, group).stream()
        .filter(p -> p.getInstId() == instance)
        .findFirst()
        .orElseThrow();
  }

  Retcode interact(int id, int group, int instance) {
    return ScenePropTransitions.interact(
            harness.map.fixture.player, id, actor(group, instance).getEntityId())
        .retcode();
  }

  @Test
  void tenHitsFinishRevealsTheBoundTreasure() {
    var map = harness.map;
    var p = map.fixture.player;
    map.enter(20331671);
    assertEquals(PropState.Hidden, map.chest(20331671).getState());
    assertEquals(Retcode.SUCC, interact(1010, 350, 300001));
    assertEquals(PropState.ChestClosed, map.chest(20331671).getState());
    harness.reload();
    assertEquals(PropState.ChestClosed, map.chest(20331671).getState());
    assertEquals(
        Retcode.SUCC, WorldChests.open(p, 120120, map.chest(20331671).getEntityId()).retcode());
    assertEquals(Retcode.SUCC, map.open(20331671));
    assertEquals(1, p.grants);
  }

  @Test
  void cinemaExitRequiresARecordedSuccessfulGame() {
    var map = harness.map;
    var p = map.fixture.player;
    map.enter(20331672);
    assertNotEquals(Retcode.SUCC, interact(1010, 351, 300002));
    assertEquals(PropState.Hidden, map.chest(20331672).getState());
    actor(351, 300001).setState(PropState.Closed, false);
    assertNotEquals(Retcode.SUCC, interact(1010, 351, 300002));
    actor(351, 300001).setState(PropState.Locked, false);
    assertEquals(Retcode.SUCC, interact(1200, 351, 300001));
    harness.reload();
    assertEquals(PropState.Closed, actor(351, 300001).getState());
    assertEquals(Retcode.SUCC, interact(1010, 351, 300002));
    assertEquals(PropState.ChestClosed, map.chest(20331672).getState());
    harness.reload();
    assertEquals(PropState.ChestClosed, map.chest(20331672).getState());
    assertEquals(Retcode.SUCC, map.open(20331672));
    assertEquals(1, p.grants);
  }
}
