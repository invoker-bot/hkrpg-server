package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FinalMascot46Test {
  @TempDir Path root;
  final Mascot46Test fixture = new Mascot46Test();
  static final int CHEST = 20433651;

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.setup();
    // The quest prerequisite is fixture history; capture, talk, unlock and loot are real calls.
    var prerequisite = new QuestState(1040401);
    prerequisite.advance(1, 1, 1);
    prerequisite.reserveReward();
    prerequisite.closeReward();
    fixture.fixture.player.getGameplay().getMainMissions().put(1040401, prerequisite);
    fixture.fixture.player.getMissionManager().refresh();
    fixture.travel(CHEST);
  }

  @AfterEach
  void restore() throws Exception {
    fixture.cleanup();
  }

  void reload() throws Exception {
    var p = fixture.fixture.player;
    assertNotNull(p.saved);
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(
        p,
        fixture.fixture.gson.fromJson(fixture.fixture.gson.toJson(p.saved), GameplayState.class));
    fixture.fixture.scene();
  }

  @Test
  void sceneSnapshotDoesNotHoldSceneWhileWaitingForPlayer() throws Exception {
    fixture.catchAndTalk(CHEST);
    var p = fixture.fixture.player;
    var scene = p.scene;
    p.getCurrentLineup().getAvatars().add(1001);
    var failure = new AtomicReference<Throwable>();
    var serializer =
        Thread.ofPlatform()
            .daemon()
            .unstarted(
                () -> {
                  try {
                    assertEquals(20433001, scene.toProto().getFloorId());
                  } catch (Throwable e) {
                    failure.set(e);
                  }
                });
    ThreadInfo blocked = null;
    // The game timer owns this mutex before entering Scene.onTick. Observe the other
    // side without entering the scene mutex, so a failing regression cannot deadlock the JVM.
    synchronized (p) {
      serializer.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (serializer.isAlive() && System.nanoTime() < deadline) {
        if (serializer.getState() == Thread.State.BLOCKED) {
          blocked =
              ManagementFactory.getThreadMXBean()
                  .getThreadInfo(new long[] {serializer.threadId()}, true, true)[0];
          break;
        }
        Thread.sleep(5);
      }
    }
    serializer.join(5000);
    assertFalse(
        serializer.isAlive(),
        "Scene serialization did not resume after the timer released the player");
    assertNull(failure.get(), "Scene serialization failed");
    assertNotNull(blocked, "Scene snapshot did not synchronize with player state");
    assertEquals(System.identityHashCode(p), blocked.getLockInfo().getIdentityHashCode());
    assertFalse(
        Arrays.stream(blocked.getLockedMonitors())
            .anyMatch(m -> m.getIdentityHashCode() == System.identityHashCode(scene)),
        "Scene query holds the scene mutex while waiting for the player, reversing the game timer"
            + " lock order");
  }

  @Test
  void originalLockedRewardWaitsForCaptureTalkAndReturnThenPersistsOnce() throws Exception {
    var p = fixture.fixture.player;
    assertFalse(p.scene.getLoadedGroups().contains(152));
    assertNotEquals(0, fixture.request(CHEST, 4));
    assertEquals(0, fixture.request(CHEST, 1));
    reload();
    assertEquals(1, p.scene.getGroups().get(151).getState());
    assertEquals(0, fixture.request(CHEST, 2));
    reload();
    assertNotEquals(0, fixture.request(CHEST, 4));
    assertEquals(Retcode.SUCC, p.getMissionManager().finishTalk(401572702, "Mission_401572702"));
    reload();
    assertFalse(p.scene.getLoadedGroups().contains(152));
    assertEquals(0, fixture.request(CHEST, 4));
    reload();
    assertEquals(4, p.getGameplay().getWorldChests().getMascots().get(CHEST).state());
    assertTrue(
        p.getGameplay().getWorldChests().valid(WorldChestRules.load(), p),
        "Saved mascot journal invalid");
    assertTrue(
        Mascots.shouldLoad(p.scene, p.scene.getResourceGroups().get(152)),
        "Reward source validation failed");
    assertTrue(p.scene.getLoadedGroups().contains(152), "Reward group missing after return");
    var chest = fixture.fixture.chest(CHEST);
    assertEquals(PropState.ChestClosed, chest.getState());
    assertEquals(Retcode.SUCC, WorldChests.open(p, 112131, chest.getEntityId()).retcode());
    int grants = p.grants;
    reload();
    assertEquals(PropState.ChestUsed, fixture.fixture.chest(CHEST).getState());
    assertNotEquals(
        Retcode.SUCC,
        WorldChests.open(p, 112131, fixture.fixture.chest(CHEST).getEntityId()).retcode());
    assertEquals(grants, p.grants);
    assertEquals(
        0,
        fixture.fixture.progress(6029853),
        "This source chest is a challenge, not CHEST_PUZZLE_MASCOT");
  }

  @Test
  void failedCaptureSaveCanRetryWithoutAdvancingTheQuest() throws Exception {
    var p = fixture.fixture.player;
    assertEquals(0, fixture.request(CHEST, 1));
    p.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture.request(CHEST, 2));
    assertEquals(1, p.scene.getGroups().get(151).getState());
    assertFalse(MissionStateRecords.subCompleted(p, 401572701));
    p.failSave = false;
    reload();
    assertEquals(0, fixture.request(CHEST, 2));
    assertTrue(MissionStateRecords.subCompleted(p, 401572701));
  }

  @Test
  void returnSaveFailureDoesNotRevealOrChangeFloorValue() throws Exception {
    fixture.catchAndTalk(CHEST);
    var p = fixture.fixture.player;
    p.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY.getVal(), fixture.request(CHEST, 4));
    assertFalse(p.scene.getLoadedGroups().contains(152));
    assertEquals(
        1, p.getGameplay().getStoryValues().get("floor:20433001:FSV_QTEGotchard_RewardGet_27"));
    p.failSave = false;
    reload();
    assertEquals(3, p.getGameplay().getWorldChests().getMascots().get(CHEST).state());
    assertEquals(0, fixture.request(CHEST, 4));
    reload();
    assertEquals(PropState.ChestClosed, fixture.fixture.chest(CHEST).getState());
  }

  @Test
  void floorValueAloneCannotCreateACompletedCatch() {
    var p = fixture.fixture.player;
    p.getGameplay().getStoryValues().put("floor:20433001:FSV_QTEGotchard_RewardGet_27", 3);
    fixture.fixture.scene();
    assertFalse(p.scene.getLoadedGroups().contains(152));
    assertEquals(0, Mascots.floorValue(p.scene, "FSV_QTEGotchard_RewardGet_27", 3));
  }
}
