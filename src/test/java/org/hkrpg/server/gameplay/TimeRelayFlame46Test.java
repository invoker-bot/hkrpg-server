package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The actual client finishes the local puzzle, then reports 111120 before opening 112131. */
class TimeRelayFlame46Test {
  @TempDir Path root;
  final WorldChestActivity46Test fixture = new WorldChestActivity46Test();

  @BeforeEach
  void setup() throws Exception {
    var extra =
        JsonParser.parseReader(
                new java.io.InputStreamReader(
                    getClass().getResourceAsStream("/fixtures/time-relay-flame-4.6.json"),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var e : extra.getAsJsonObject("records").entrySet()) {
      var rows = fixture.source.getAsJsonObject("records").getAsJsonArray(e.getKey());
      if (rows == null) {
        fixture.source.getAsJsonObject("records").add(e.getKey(), e.getValue().deepCopy());
      } else {
        for (var r : e.getValue().getAsJsonArray()) rows.add(r.deepCopy());
      }
    }
    for (String section : new String[] {"groups", "locations"}) {
      for (var e : extra.getAsJsonObject(section).entrySet())
        fixture.source.getAsJsonObject(section).add(e.getKey(), e.getValue().deepCopy());
    }
    fixture.root = root;
    fixture.load();
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.restore();
  }

  EntityProp flame(int id) {
    return fixture.chest(id);
  }

  void unlock(int id) {
    var prop = flame(id);
    assertEquals(PropState.ChestLocked, prop.getState());
    assertEquals(
        Retcode.SUCC,
        SceneInteractions.validateNative(fixture.player, 111120, prop.getEntityId()),
        "Original client puzzle-completion request must unlock its real source prop");
    assertSame(prop, fixture.player.interactWithProp(111120, prop.getEntityId()));
    assertEquals(PropState.ChestClosed, prop.getState());
  }

  @ParameterizedTest
  @ValueSource(ints = {20411654, 20411655, 20411656, 20411657})
  void eachOriginalPuzzleReportsUnlockAndOpensOnce(int id) {
    fixture.player.getQuestManager().refreshUnlocks();
    unlock(id);
    var prop = flame(id);
    assertSame(prop, fixture.player.interactWithProp(112131, prop.getEntityId()));
    assertEquals(PropState.ChestUsed, prop.getState());
    assertEquals(1, fixture.progress(6029856));
    assertEquals(1, fixture.progress(6029850));
    assertNull(fixture.player.interactWithProp(112131, prop.getEntityId()));
    assertEquals(1, fixture.progress(6029856));
  }

  @Test
  void theSourceType224GoalIsAcceptedAtZero() {
    var goal = fixture.state(6029856);
    assertNotNull(goal, "The original time-flame activity goal is missing");
    assertEquals(0, goal.getProgress());
    assertEquals(emu.lunarcore.game.quest.QuestState.Phase.DOING, goal.getPhase());
  }

  @Test
  void unlockingDoesNotGrantLootOrAdvanceAnyChestGoal() {
    fixture.player.getQuestManager().refreshUnlocks();
    int grants = fixture.player.grants;
    unlock(20411654);
    assertEquals(grants, fixture.player.grants);
    for (int id : new int[] {6029850, 6029851, 6029852, 6029856})
      assertEquals(0, fixture.progress(id));
  }

  @Test
  void normalChestsDoNotSatisfyTheTimeFlameTypeGoal() {
    fixture.player.getQuestManager().refreshUnlocks();
    var ordinary = fixture.chest(20411601);
    assertSame(ordinary, fixture.player.interactWithProp(112131, ordinary.getEntityId()));
    assertNotNull(fixture.state(6029856));
    assertEquals(0, fixture.progress(6029856));
    unlock(20411654);
    var prop = flame(20411654);
    assertSame(prop, fixture.player.interactWithProp(112131, prop.getEntityId()));
    assertEquals(1, fixture.progress(6029856));
    assertEquals(2, fixture.progress(6029850));
  }

  @Test
  void laterPuzzlesCannotIncreaseTheAlreadyCompletedSingleGoal() {
    fixture.player.getQuestManager().refreshUnlocks();
    for (int id : new int[] {20411654, 20411655, 20411656, 20411657}) {
      unlock(id);
      var prop = flame(id);
      assertSame(prop, fixture.player.interactWithProp(112131, prop.getEntityId()));
    }
    assertEquals(1, fixture.progress(6029856));
    assertEquals(4, fixture.progress(6029850));
    assertEquals(4, fixture.player.grants);
  }

  @Test
  void anUnclaimedUnlockRestoresAsClosedInANewScene() {
    unlock(20411654);
    fixture.player.getGameplay().worldChests(fixture.player.saved.getWorldChests());
    fixture.scene();
    assertEquals(PropState.ChestClosed, flame(20411654).getState());
    var prop = flame(20411654);
    assertSame(prop, fixture.player.interactWithProp(112131, prop.getEntityId()));
  }

  @Test
  void claimedPuzzleAndItsTypeCreditRestoreTogether() {
    fixture.player.getQuestManager().refreshUnlocks();
    unlock(20411654);
    var prop = flame(20411654);
    assertSame(prop, fixture.player.interactWithProp(112131, prop.getEntityId()));
    fixture.player.getGameplay().worldChests(fixture.player.saved.getWorldChests());
    fixture.scene();
    assertEquals(PropState.ChestUsed, flame(20411654).getState());
    assertEquals(1, fixture.progress(6029856));
    assertNull(fixture.player.interactWithProp(112131, flame(20411654).getEntityId()));
    assertEquals(1, fixture.player.grants);
  }

  @Test
  void aFailedUnlockSaveKeepsTheOriginalLockAndAllowsARealRetry() {
    fixture.player.failSave = true;
    var prop = flame(20411654);
    assertEquals(
        Retcode.SYSTEM_BUSY,
        WorldChests.open(fixture.player, 111120, prop.getEntityId()).retcode());
    assertEquals(PropState.ChestLocked, prop.getState());
    fixture.player.failSave = false;
    unlock(20411654);
    assertEquals(0, fixture.player.grants);
  }

  @Test
  void uncertainLootDeliveryKeepsTheUnlockAndBlocksDuplicateDelivery() {
    fixture.player.getQuestManager().refreshUnlocks();
    unlock(20411654);
    fixture.player.failGrant = true;
    var prop = flame(20411654);
    assertEquals(
        Retcode.PLAYER_DATA_ERROR,
        WorldChests.open(fixture.player, 112131, prop.getEntityId()).retcode());
    assertEquals(PropState.ChestClosed, prop.getState());
    assertEquals(0, fixture.progress(6029856));
    fixture.player.failGrant = false;
    assertEquals(
        Retcode.SYSTEM_BUSY,
        WorldChests.open(fixture.player, 112131, prop.getEntityId()).retcode());
    assertEquals(1, fixture.player.grants);
  }

  @Test
  void aLockedPuzzleCannotBeOpenedWithoutItsFinishReport() {
    var prop = flame(20411654);
    assertNotEquals(
        Retcode.SUCC, WorldChests.open(fixture.player, 112131, prop.getEntityId()).retcode());
    assertEquals(PropState.ChestLocked, prop.getState());
    assertEquals(0, fixture.player.grants);
    assertTrue(fixture.player.getGameplay().getWorldChests().getUnlocks().isEmpty());
  }

  @Test
  void aForgedClosedActorCannotReplaceTheDurableFinishReport() {
    var prop = flame(20411654);
    prop.setState(PropState.ChestClosed, false);
    assertEquals(
        Retcode.UNSUPPORTED_PROP_STATE,
        WorldChests.open(fixture.player, 112131, prop.getEntityId()).retcode());
    assertEquals(0, fixture.player.grants);
    assertTrue(fixture.player.getGameplay().getWorldChests().getRecords().isEmpty());
  }

  @Test
  void replayedFinishReportsCannotCreateAnotherUnlockOrReward() {
    unlock(20411654);
    var prop = flame(20411654);
    assertNotEquals(
        Retcode.SUCC, WorldChests.open(fixture.player, 111120, prop.getEntityId()).retcode());
    assertEquals(1, fixture.player.getGameplay().getWorldChests().getUnlocks().size());
    assertEquals(0, fixture.player.grants);
    assertTrue(fixture.player.getGameplay().getWorldChests().getRecords().isEmpty());
  }

  @Test
  void changingTheOriginalGroupAfterUnlockBlocksRewardDelivery() throws Exception {
    unlock(20411654);
    var path = root.resolve(TimeRelayFlameSources.PUZZLES.get(20411654).path());
    var source = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    source.addProperty("GroupName", "changed-source");
    Files.writeString(path, source.toString());
    assertNotEquals(
        Retcode.SUCC,
        WorldChests.open(fixture.player, 112131, flame(20411654).getEntityId()).retcode());
    assertEquals(0, fixture.player.grants);
  }

  @Test
  void changingTheLoadedPropDefinitionCannotAcceptTheFinishReport() {
    GameData.getPropExcelMap().get(104046).getPropStateList().remove(PropState.ChestUsed);
    assertNotEquals(
        Retcode.SUCC,
        WorldChests.open(fixture.player, 111120, flame(20411654).getEntityId()).retcode());
    assertTrue(fixture.player.getGameplay().getWorldChests().getUnlocks().isEmpty());
  }

  @Test
  void anUnlockForAnotherSourceIdentityCannotAuthorizeOpening() {
    unlock(20411654);
    var gson = ResourceLoader.getGson();
    var saved = gson.toJsonTree(fixture.player.getGameplay().getWorldChests()).getAsJsonObject();
    saved.getAsJsonObject("unlocks").getAsJsonObject("20411654").addProperty("group", 232);
    fixture.player.getGameplay().worldChests(gson.fromJson(saved, WorldChestState.class));
    assertEquals(
        Retcode.PLAYER_DATA_ERROR,
        WorldChests.open(fixture.player, 112131, flame(20411654).getEntityId()).retcode());
    assertEquals(0, fixture.player.grants);
  }

  @Test
  void anInterruptedFinalSaveKeepsAPreparedReceiptAndBlocksRedelivery() {
    fixture.player.getQuestManager().refreshUnlocks();
    unlock(20411654);
    fixture.player.failOnSave = fixture.player.saves + 2;
    var prop = flame(20411654);
    assertEquals(
        Retcode.SYSTEM_BUSY,
        WorldChests.open(fixture.player, 112131, prop.getEntityId()).retcode());
    assertEquals(1, fixture.player.grants);
    fixture.player.failOnSave = -1;
    assertEquals(0, fixture.progress(6029856));
    fixture.scene();
    prop = flame(20411654);
    assertEquals(PropState.ChestClosed, prop.getState());
    assertEquals(
        Retcode.SYSTEM_BUSY,
        WorldChests.open(fixture.player, 112131, prop.getEntityId()).retcode());
    assertEquals(1, fixture.player.grants);
  }

  @Test
  void openingsOutsideTheActivityWindowCannotBecomeRetroactiveTypeCredit() {
    GameplayDefinitions.windows.clear();
    unlock(20411654);
    var prop = flame(20411654);
    assertSame(prop, fixture.player.interactWithProp(112131, prop.getEntityId()));
    assertTrue(fixture.player.getGameplay().getWorldChests().getQuests().isEmpty());
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(30013, 3001301, 1, Long.MAX_VALUE));
    assertEquals(0, fixture.state(6029856).getProgress());
    assertTrue(
        fixture
            .player
            .getGameplay()
            .getWorldChests()
            .getRecords()
            .get(20411654)
            .getAcceptances()
            .isEmpty());
  }

  @Test
  void ordinaryVersionOneHistoryWithoutTheNewFieldRemainsValid() {
    var ordinary = fixture.chest(20411601);
    assertSame(ordinary, fixture.player.interactWithProp(112131, ordinary.getEntityId()));
    var gson = ResourceLoader.getGson();
    var legacy = gson.toJsonTree(fixture.player.getGameplay().getWorldChests()).getAsJsonObject();
    legacy.remove("unlocks");
    fixture.player.getGameplay().worldChests(gson.fromJson(legacy, WorldChestState.class));
    assertTrue(
        fixture
            .player
            .getGameplay()
            .getWorldChests()
            .valid(WorldChestRules.load(), fixture.player));
    unlock(20411654);
    var prop = flame(20411654);
    assertSame(prop, fixture.player.interactWithProp(112131, prop.getEntityId()));
    assertEquals(2, fixture.player.getGameplay().getWorldChests().getRecords().size());
  }
}
