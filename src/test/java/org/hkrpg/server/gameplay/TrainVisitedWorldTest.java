package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.FloorInfo;
import emu.lunarcore.data.excel.MazePlaneExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.util.Position;
import java.util.*;
import org.junit.jupiter.api.*;

class TrainVisitedWorldTest {
  static final int PLANE = 199968, FLOOR = 19996801, WORLD = 199968501;
  final Gson gson = new Gson();
  final List<GameplayState> saved = new ArrayList<>();
  MazePlaneExcel oldPlane;
  FloorInfo oldFloor;
  Player player;

  @BeforeEach
  void setup() {
    oldPlane = GameData.getMazePlaneExcelMap().get(PLANE);
    oldFloor =
        GameData.getFloorInfos()
            .put(
                "P" + PLANE + "_F" + FLOOR,
                gson.fromJson(
                    "{\"FloorID\":19996801,\"DimensionList\":[{\"ID\":0,\"GroupIndexList\":[]}],\"GroupInstanceList\":[]}",
                    FloorInfo.class));
    GameplayDefinitions.worlds.put(WORLD, new GameplayDefinitions.World(WORLD));
    player =
        new Player() {
          final PlayerLineup team = new PlayerLineup(this, 0);
          final Position pos = new Position(), rot = new Position();

          public Position getPos() {
            return pos;
          }

          public Position getRot() {
            return rot;
          }

          {
            team.getAvatars().add(1);
          }

          public PlayerLineup getCurrentLineup() {
            return team;
          }

          public void save() {
            saved.add(gson.fromJson(gson.toJson(getGameplay()), GameplayState.class));
          }
        };
  }

  void plane(String type, int world) {
    GameData.getMazePlaneExcelMap()
        .add(
            gson.fromJson(
                "{\"PlaneID\":"
                    + PLANE
                    + ",\"PlaneType\":\""
                    + type
                    + "\",\"WorldID\":"
                    + world
                    + "}",
                MazePlaneExcel.class));
  }

  boolean enter(int plane) {
    return player.loadScene(plane, FLOOR, FLOOR, new Position(), new Position(), false, 0);
  }

  @AfterEach
  void cleanup() {
    GameData.getMazePlaneExcelMap().keySet().remove(PLANE);
    if (oldPlane != null) GameData.getMazePlaneExcelMap().add(oldPlane);
    if (oldFloor == null) GameData.getFloorInfos().remove("P" + PLANE + "_F" + FLOOR);
    else GameData.getFloorInfos().put("P" + PLANE + "_F" + FLOOR, oldFloor);
    GameplayDefinitions.worlds.remove(WORLD);
  }

  @Test
  void aSuccessfulTownOrMazeEntrySavesItsActualVisitedWorldWithThePlayerLocation() {
    for (String type : List.of("Town", "Maze")) {
      plane(type, WORLD);
      assertTrue(enter(PLANE));
      assertEquals(WORLD, player.getWorldId());
      assertEquals(1, player.getGameplay().getStoryValues().get("train:visited:" + WORLD));
      assertEquals(1, saved.getLast().getStoryValues().get("train:visited:" + WORLD));
      assertFalse(player.getGameplay().getStoryValues().containsKey("train:world"));
    }
  }

  @Test
  void anExistingSceneWithoutAVisitRecordStillSavesItsNewlyConfirmedVisit() {
    plane("Town", WORLD);
    assertTrue(enter(PLANE));
    player.getGameplay().getStoryValues().remove("train:visited:" + WORLD);
    saved.clear();
    var scene = player.getScene();
    assertTrue(enter(PLANE));
    assertSame(scene, player.getScene());
    assertEquals(1, saved.size());
    assertEquals(1, saved.getFirst().getStoryValues().get("train:visited:" + WORLD));
    assertTrue(enter(PLANE));
    assertEquals(1, saved.size());
  }

  @Test
  void failedEntriesTrainPlanesAndUnknownWorldResourcesDoNotCreateVisitedWorlds() {
    plane("Town", WORLD);
    assertFalse(enter(PLANE + 1));
    assertTrue(player.getGameplay().getStoryValues().isEmpty());
    plane("Train", WORLD);
    assertTrue(enter(PLANE));
    assertFalse(player.getGameplay().getStoryValues().containsKey("train:visited:" + WORLD));
    plane("Town", WORLD + 1);
    assertTrue(enter(PLANE));
    assertFalse(player.getGameplay().getStoryValues().containsKey("train:visited:" + (WORLD + 1)));
  }
}
