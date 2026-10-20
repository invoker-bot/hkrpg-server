package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real source-linked completion paths, with reload from the last successful player save. */
class FinalWorldMechanism46Test {
  @TempDir Path root;
  final RemainingWorldMechanism46Test setup = new RemainingWorldMechanism46Test();

  @BeforeEach
  void load() throws Exception {
    setup.root = root;
    setup.load();
    var prior = map().fixture.player;
    var actor =
        new MapChest46Test.MapPlayer() {
          final java.util.Map<Integer, emu.lunarcore.game.inventory.GameItem> items =
              new java.util.HashMap<>();
          final emu.lunarcore.game.inventory.Inventory inventory =
              new emu.lunarcore.game.inventory.Inventory(this) {
                public GrantResult grantItems(
                    emu.lunarcore.game.inventory.ItemParamMap rewards, boolean hint) {
                  grants++;
                  for (var item : rewards)
                    if (item.getIntKey() == 140189)
                      items.put(
                          140189,
                          new emu.lunarcore.game.inventory.GameItem(140189, item.getIntValue()));
                  return new GrantResult(rewards.toItemList(), true);
                }

                public emu.lunarcore.game.inventory.GameItem getMaterialByItemId(int id) {
                  return items.get(id);
                }

                public boolean removeItem(emu.lunarcore.game.inventory.GameItem item, int count) {
                  if (item == null || item.getCount() < count) return false;
                  item.setCount(item.getCount() - count);
                  return true;
                }
              };

          public emu.lunarcore.game.inventory.Inventory getInventory() {
            return inventory;
          }
        };
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(actor, prior.getGameplay());
    actor.setLevel(prior.getLevel());
    map().fixture.player = actor;
    map().fixture.scene();
  }

  @AfterEach
  void restore() throws Exception {
    setup.restore();
  }

  private MapChest46Test map() {
    return setup.fixture.map;
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

  private void begin(int chest, int main, int sub) {
    var p = map().fixture.player;
    p.getGameplay().getMainMissions().put(main, new QuestState(main));
    p.getGameplay().getSubMissions().put(sub, new QuestState(sub));
    map().enter(chest);
  }

  private void claim(int chest) {
    int before = map().fixture.player.grants;
    assertEquals(PropState.ChestClosed, map().chest(chest).getState());
    setup.fixture.reload();
    assertEquals(Retcode.SUCC, map().open(chest));
    setup.fixture.reload();
    assertEquals(PropState.ChestUsed, map().chest(chest).getState());
    assertNotEquals(Retcode.SUCC, map().open(chest));
    assertEquals(before + 1, map().fixture.player.grants);
  }

  @ParameterizedTest
  @CsvSource({"20211674,4015101,401510104,130", "20211681,4015102,401510203,171"})
  void compassMustFinishBeforeItsGuardSpawnsAndVictoryUnlocksOnce(
      int chest, int main, int sub, int group) {
    begin(chest, main, sub);
    assertTrue(
        map().fixture.player.scene.getEntitiesByGroup(EntityMonster.class, group).isEmpty(),
        "Guard must wait for the ship");
    assertNotEquals(Retcode.SUCC, map().open(chest));
    assertEquals(Retcode.SUCC, interact(group, 300001, 1010));
    assertEquals(PropState.ChestLocked, map().chest(chest).getState());
    setup.fixture.reload();
    var guards = map().fixture.player.scene.getEntitiesByGroup(EntityMonster.class, group);
    assertEquals(1, guards.size());
    assertEquals(200002, guards.getFirst().getInstId());
    assertTrue(map().fixture.player.scene.defeatMonster(guards.getFirst()));
    claim(chest);
    assertTrue(map().fixture.player.scene.getEntitiesByGroup(EntityMonster.class, group).isEmpty());
  }

  @Test
  void compassSaveFailureLeavesShipAndGuardUnstarted() {
    begin(20211674, 4015101, 401510104);
    map().fixture.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, interact(130, 300001, 1010));
    assertEquals(PropState.Closed, actor(130, 300001).getState());
    assertTrue(map().fixture.player.scene.getEntitiesByGroup(EntityMonster.class, 130).isEmpty());
    map().fixture.player.failSave = false;
    assertEquals(Retcode.SUCC, interact(130, 300001, 1010));
    assertEquals(1, map().fixture.player.scene.getEntitiesByGroup(EntityMonster.class, 130).size());
  }

  @Test
  void guardUnloadOrFailedSaveCannotUnlockCompassReward() {
    begin(20211674, 4015101, 401510104);
    assertEquals(Retcode.SUCC, interact(130, 300001, 1010));
    var p = map().fixture.player;
    var guard = p.scene.getEntitiesByGroup(EntityMonster.class, 130).getFirst();
    p.scene.removeEntity(guard);
    assertEquals(PropState.ChestLocked, map().chest(20211674).getState());
    setup.fixture.reload();
    guard = p.scene.getEntitiesByGroup(EntityMonster.class, 130).getFirst();
    p.failSave = true;
    assertFalse(p.scene.defeatMonster(guard));
    assertSame(guard, p.scene.getEntityById(guard.getEntityId()));
    assertEquals(PropState.ChestLocked, map().chest(20211674).getState());
    p.failSave = false;
    assertTrue(p.scene.defeatMonster(guard));
    claim(20211674);
  }

  @Test
  void cargoNpcWaitsForItsThreeTalksAndAcceptsOriginalCleanup() {
    begin(20211682, 4015102, 401510207);
    assertEquals(Retcode.SUCC, interact(173, 300008, 1010));
    assertEquals(PropState.ChestLocked, map().chest(20211682).getState());
    setup.fixture.reload();
    var p = map().fixture.player;
    for (int talk : new int[] {401510208, 401510210, 401510211}) {
      assertEquals(PropState.ChestLocked, map().chest(20211682).getState());
      if (talk == 401510210) map().enter(20211678);
      assertEquals(
          Retcode.SUCC, p.getMissionManager().finishTalk(talk, "Mission_" + talk), "Talk " + talk);
      if (talk == 401510210) map().enter(20211682);
    }
    assertEquals(PropState.ChestClosed, map().chest(20211682).getState());
    assertEquals(Retcode.SUCC, interact(173, 300008, 1120));
    claim(20211682);
  }

  @Test
  void cargoNpcCannotSkipDialogueWithItsCleanupReport() {
    begin(20211682, 4015102, 401510207);
    assertEquals(Retcode.SUCC, interact(173, 300008, 1010));
    assertNotEquals(Retcode.SUCC, interact(173, 300008, 1120));
    assertEquals(PropState.ChestLocked, map().chest(20211682).getState());
    assertNotEquals(Retcode.SUCC, map().open(20211682));
  }

  @Test
  void completedCompassCannotStartOutsideItsOwningObjective() {
    begin(20211674, 4015101, 401510105);
    assertNotEquals(Retcode.SUCC, interact(130, 300001, 1010));
    assertTrue(map().fixture.player.scene.getEntitiesByGroup(EntityMonster.class, 130).isEmpty());
  }

  @Test
  void memoryOnlyCompassStateCannotSpawnAGuardOrUnlockTheChest() {
    begin(20211674, 4015101, 401510104);
    actor(130, 300001).setState(PropState.Open, false);
    WorldMechanisms.afterControl(actor(130, 300001));
    assertTrue(map().fixture.player.scene.getEntitiesByGroup(EntityMonster.class, 130).isEmpty());
    assertEquals(PropState.ChestLocked, map().chest(20211674).getState());
  }

  @Test
  void savedCompassRestoresGuardsDuringFirstLoginSceneConstruction() {
    begin(20211674, 4015101, 401510104);
    assertEquals(Retcode.SUCC, interact(130, 300001, 1010));
    map().fixture.player.scene = null;
    map().fixture.scene();
    assertEquals(1, map().fixture.player.scene.getEntitiesByGroup(EntityMonster.class, 130).size());
  }
}
