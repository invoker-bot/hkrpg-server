package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.LineupManager;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.junit.jupiter.api.*;

public class UniverseLifecycleTest {
  static final int AREA = 19996, MAP = 1999601, ROOM = 19996001;

  public static class Explorer extends Player {
    final GameAvatar avatar =
        new GameAvatar() {
          public int getAvatarId() {
            return 199961;
          }

          public void save() {}
        };
    int entry = 11;
    boolean rejectScene;
    final LineupManager teams = new LineupManager(this);

    public LineupManager getLineupManager() {
      return teams;
    }

    final emu.lunarcore.util.Position position = new emu.lunarcore.util.Position();
    final emu.lunarcore.util.Position rotation = new emu.lunarcore.util.Position();

    public emu.lunarcore.util.Position getPos() {
      return position;
    }

    public emu.lunarcore.util.Position getRot() {
      return rotation;
    }

    public Explorer() {
      getAvatars().getAvatars().put(199961, avatar);
      getCurrentLineup().getAvatars().add(199961);
      getPos().set(120, 30, 400);
      getRot().set(0, 90, 0);
    }

    public GameAvatar getAvatarById(int id) {
      return id == 199961 ? avatar : null;
    }

    public void save() {}

    public int getEntryId() {
      return entry;
    }

    public int getPlaneId() {
      return entry == 11 ? 199961 : 199962;
    }

    public int getFloorId() {
      return getPlaneId() * 1000 + 1;
    }

    public Scene getScene() {
      var plane =
          new Gson()
              .fromJson(
                  "{\"PlaneID\":"
                      + getPlaneId()
                      + ",\"PlaneType\":\""
                      + (entry == 11 ? "Town" : "Rogue")
                      + "\"}",
                  MazePlaneExcel.class);
      return new Scene(this, plane, getFloorId());
    }

    public boolean enterScene(int id, int teleport, boolean send) {
      if (rejectScene) return false;
      entry = id;
      getPos().set(1, 2, 3);
      return true;
    }

    public boolean loadScene(
        int plane,
        int floor,
        int id,
        emu.lunarcore.util.Position pos,
        emu.lunarcore.util.Position rot,
        boolean send) {
      if (rejectScene) return false;
      entry = id;
      getPos().set(pos);
      getRot().set(rot);
      return true;
    }
  }

  @BeforeEach
  void definitions() {
    GameplayDefinitions.areas.put(AREA, new GameplayDefinitions.Area(AREA, 19996, 1, 0, 0));
    GameplayDefinitions.maps.put(
        MAP, List.of(new GameplayDefinitions.Site(MAP, 1, true, List.of(), 1)));
    GameplayDefinitions.rooms.put(ROOM, new GameplayDefinitions.Room(ROOM, 1, 12, 8));
    GameplayDefinitions.roomPool.put(1, List.of(ROOM));
  }

  @AfterEach
  void reset() {
    GameplayDefinitions.areas.remove(AREA);
    GameplayDefinitions.maps.remove(MAP);
    GameplayDefinitions.rooms.remove(ROOM);
    GameplayDefinitions.roomPool.remove(1);
  }

  @Test
  void pendingStoryReturnCannotStartANewExplorationAndOverwriteItsHome() {
    var player = new Explorer();
    var location = new StoryLineState.Place(player);
    player.getGameplay().getStoryLines().begin(199931, location, location);
    player.getGameplay().getStoryLines().begin(0, location, location);
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, player.getUniverseManager().start(AREA));
    assertEquals(GameplayState.RogueRun.Phase.IDLE, player.getGameplay().getRogue().getPhase());
    assertEquals(11, player.entry);
    assertSame(location, player.getGameplay().getStoryLines().getReturnDestination());
  }

  @Test
  void pausedExplorationCannotResumeDuringAStoryReturnTransition() {
    var player = new Explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(AREA));
    assertEquals(Retcode.SUCC, player.getUniverseManager().leave(false));
    var run = player.getGameplay().getRogue();
    var location = new StoryLineState.Place(player);
    player.getGameplay().getStoryLines().begin(199931, location, location);
    player.getGameplay().getStoryLines().begin(0, location, location);
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, player.getUniverseManager().resume(AREA, false));
    assertFalse(run.isInside());
    assertEquals(11, player.entry);
    assertEquals(120, run.getReturnTo().getPos().getX());
  }

  @Test
  void startingUsesIsolatedRogueTeamAndNeverOverwritesTheNormalAvatarsHealth() {
    var player = new Explorer();
    var normal = player.getCurrentLineup();
    player.avatar.setCurrentHp(normal, 7000);
    player.avatar.setCurrentSp(normal, 1500);
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(AREA));
    var rogue = player.getCurrentLineup();
    assertEquals(2, rogue.getExtraLineupType());
    assertNotSame(normal, rogue);
    assertEquals(List.of(199961), rogue.getAvatars());
    assertEquals(7000, player.avatar.getCurrentHp(rogue));
    player.avatar.setCurrentHp(rogue, 2300);
    player.avatar.setCurrentSp(rogue, 4500);
    assertEquals(7000, player.avatar.getCurrentHp(normal));
    assertEquals(1500, player.avatar.getCurrentSp(normal));
    assertEquals(2300, player.avatar.getCurrentHp(rogue));
  }

  @Test
  void emptyOrUnownedTeamsCannotStartAndSceneFailureLeavesTheOriginalTeamUntouched() {
    var player = new Explorer();
    var normal = player.getCurrentLineup();
    normal.getAvatars().clear();
    assertEquals(Retcode.REQ_PARA_INVALID, player.getUniverseManager().start(AREA));
    normal.getAvatars().add(199962);
    assertEquals(Retcode.REQ_PARA_INVALID, player.getUniverseManager().start(AREA));
    normal.getAvatars().set(0, 199961);
    player.rejectScene = true;
    assertEquals(Retcode.ROGUE_AREA_INVALID, player.getUniverseManager().start(AREA));
    assertSame(normal, player.getCurrentLineup());
    assertEquals(GameplayState.RogueRun.Phase.IDLE, player.getGameplay().getRogue().getPhase());
    assertEquals(11, player.entry);
  }

  @Test
  void pauseRestoresExactLocationResumePreservesHealthAndQuitCannotBeReplayed() {
    var player = new Explorer();
    var normal = player.getCurrentLineup();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(AREA));
    var run = player.getGameplay().getRogue();
    String id = run.getRunId();
    player.avatar.setCurrentHp(player.getCurrentLineup(), 1200);
    player.getCurrentLineup().setMp(2);
    run.clear();
    run.offer(List.of(199960));
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, player.getUniverseManager().quit(AREA));
    assertEquals(Retcode.SUCC, player.getUniverseManager().leave(false));
    assertSame(normal, player.getCurrentLineup());
    assertEquals(11, player.entry);
    assertEquals(120, player.getPos().getX());
    assertEquals(30, player.getPos().getY());
    assertEquals(400, player.getPos().getZ());
    assertEquals(90, player.getRot().getY());
    assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run.getPhase());
    assertEquals(List.of(199960), run.getOffers());
    assertEquals(Retcode.ROGUE_AREA_INVALID, player.getUniverseManager().resume(AREA + 1, false));
    assertFalse(run.isInside());
    assertEquals(Retcode.SUCC, player.getUniverseManager().resume(AREA, false));
    assertEquals(id, run.getRunId());
    assertEquals(1200, player.avatar.getCurrentHp(player.getCurrentLineup()));
    assertEquals(2, player.getCurrentLineup().getMp());
    assertEquals(Set.of(1), run.getCleared());
    assertFalse(player.getLineupManager().switchLineup(1));
    player.getCurrentLineup().replace(List.of(199962));
    assertEquals(List.of(199961), run.getRoster());
    player.rejectScene = true;
    assertEquals(Retcode.ROGUE_AREA_INVALID, player.getUniverseManager().leave(false));
    assertTrue(run.isInside());
    assertEquals(2, player.getCurrentLineup().getExtraLineupType());
    player.rejectScene = false;
    assertEquals(Retcode.SUCC, player.getUniverseManager().leave(false));
    player.rejectScene = true;
    assertEquals(Retcode.ROGUE_AREA_INVALID, player.getUniverseManager().resume(AREA, false));
    assertFalse(run.isInside());
    assertSame(normal, player.getCurrentLineup());
    assertEquals(Retcode.SUCC, player.getUniverseManager().quit(AREA));
    assertEquals(GameplayState.RogueRun.Phase.ABANDONED, run.getPhase());
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, player.getUniverseManager().quit(AREA));
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, player.getUniverseManager().resume(AREA, false));
    assertTrue(player.getGameplay().getRewards().isEmpty());
  }

  @Test
  void finishedAndFailedRunsCanBeDismissedAfterLeavingWithoutChangingTheOutcomeOrGrantingRewards() {
    for (var outcome :
        List.of(GameplayState.RogueRun.Phase.FAILED, GameplayState.RogueRun.Phase.FINISHED)) {
      var player = new Explorer();
      assertEquals(Retcode.SUCC, player.getUniverseManager().start(AREA));
      var run = player.getGameplay().getRogue();
      run.finish(outcome);
      assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, player.getUniverseManager().quit(AREA));
      assertEquals(Retcode.SUCC, player.getUniverseManager().leave(false));
      assertEquals(Retcode.SUCC, player.getUniverseManager().quit(AREA));
      assertEquals(outcome, run.getPhase());
      assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, player.getUniverseManager().quit(AREA));
      assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, player.getUniverseManager().resume(AREA, false));
      assertTrue(player.getGameplay().getRewards().isEmpty());
    }
  }

  @Test
  void oldRunsWithoutTeamOrReturnCoordinatesAreNotSilentlyReinitializedOnResume() {
    var player = new Explorer();
    var run = new GameplayState.RogueRun(AREA, MAP, Map.of(1, ROOM));
    run.enter(1, ROOM);
    run.clear();
    run.offer(List.of(199960));
    player.getGameplay().setRogue(run);
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, player.getUniverseManager().resume(AREA, false));
    assertEquals(Retcode.ROGUE_STATUS_NOT_MATCH, player.getUniverseManager().leave(false));
    assertEquals(11, player.entry);
    assertEquals(Set.of(1), run.getCleared());
    assertEquals(List.of(199960), run.getOffers());
    assertTrue(run.getRoster().isEmpty());
    assertNull(run.getReturnTo());
    assertEquals(GameplayState.RogueRun.Phase.EXPLORING, run.getPhase());
  }
}
